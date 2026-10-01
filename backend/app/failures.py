"""What went wrong, in terms the phone can act on.

One small vocabulary for every way a planning request can fail, shared by the
provider, the service and the local limiter, so the phone never has to guess
whether "busy" means "try again in a second" or "nothing will work until
somebody changes a setting".

Every field here is safe to send and to log: a fixed category, where it came
from, whether trying again could help, how long to wait, and a reason from a
fixed list. Never the provider's own message, the request, or anything from
the screen.

Classifying a provider error reads its structured details (Google's
`google.rpc.RetryInfo` and `google.rpc.QuotaFailure`) and, where those are
absent, looks for a few fixed phrases in its message. The message itself is
only ever read here, in memory; it is not stored, returned or logged.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Where a failure came from.
SOURCE_DOT = "dot"
SOURCE_PROVIDER = "provider"


@dataclass(frozen=True)
class Failure:
    category: str
    source: str
    retryable: bool
    retry_after_s: float | None = None
    # One of a fixed list, or empty: spend_cap, billing, daily_quota.
    reason: str = ""

    def body(self, correlation_id: str) -> dict:
        """The error body the phone reads. Nothing in it came from the provider verbatim."""
        return {
            "category": self.category,
            "source": self.source,
            "retryable": self.retryable,
            "retry_after_s": self.retry_after_s,
            "reason": self.reason,
            "correlation_id": correlation_id,
        }


# ----------------------------------------------------------------- provider

# The longest wait worth honouring from a provider's own retry hint. Anything
# longer is, for somebody waiting at their phone, the same as "not now".
MAX_HONOURED_DELAY_S = 60.0

_DELAY = re.compile(r"^\s*(\d+(?:\.\d+)?)s\s*$")


def _retry_delay(details: list) -> float | None:
    for item in details:
        if str(item.get("@type", "")).endswith("RetryInfo"):
            match = _DELAY.match(str(item.get("retryDelay", "")))
            if match:
                return float(match.group(1))
    return None


def _quota_ids(details: list) -> list[str]:
    ids: list[str] = []
    for item in details:
        if str(item.get("@type", "")).endswith("QuotaFailure"):
            for violation in item.get("violations", []) or []:
                ids.append(str(violation.get("quotaId", "")) + " " + str(violation.get("quotaMetric", "")))
    return ids


def classify_provider_error(code: int | None, status: str | None, details: list | None, message: str | None) -> Failure:
    """One provider HTTP error, as a Failure.

    429 is not one thing. Google's API returns it for a per-minute limit
    (retry shortly), a per-day quota (not until tomorrow), a rolling spend
    limit, and a project's monthly spend cap (not until someone raises it or
    the month turns). Only the structured details or the message can tell
    them apart, so they are read; a 429 with neither is reported as an
    unexplained limit, and the phone treats it cautiously rather than as
    either extreme.
    """
    details = details or []
    text = (message or "").lower()
    delay = _retry_delay(details)
    quotas = " ".join(_quota_ids(details)).lower()

    if code == 429 or (status or "").upper() == "RESOURCE_EXHAUSTED":
        if "spend cap" in text or "spending cap" in text:
            return Failure("quota_exhausted", SOURCE_PROVIDER, retryable=False, reason="spend_cap")
        if "prepayment" in text or "credits are depleted" in text or ("billing" in text and "exceeded" in text):
            return Failure("quota_exhausted", SOURCE_PROVIDER, retryable=False, reason="billing")
        if "perday" in quotas or "per_day" in quotas or "per day" in quotas:
            return Failure("quota_exhausted", SOURCE_PROVIDER, retryable=False, reason="daily_quota")
        if delay is not None or "perminute" in quotas or "per_minute" in quotas:
            return Failure(
                "provider_throttled", SOURCE_PROVIDER, retryable=True,
                retry_after_s=min(delay, MAX_HONOURED_DELAY_S) if delay is not None else None,
            )
        return Failure("provider_limited", SOURCE_PROVIDER, retryable=True)

    if code in (401, 403):
        return Failure("auth", SOURCE_PROVIDER, retryable=False)
    if code == 404:
        return Failure("model_not_found", SOURCE_PROVIDER, retryable=False)
    if code == 400:
        return Failure("bad_request", SOURCE_PROVIDER, retryable=False)
    if code in (500, 502, 503) or (status or "").upper() in ("UNAVAILABLE", "INTERNAL"):
        return Failure("provider_capacity", SOURCE_PROVIDER, retryable=True, retry_after_s=delay)
    if code == 504 or (status or "").upper() == "DEADLINE_EXCEEDED":
        return Failure("timeout", SOURCE_PROVIDER, retryable=True)
    return Failure("upstream", SOURCE_PROVIDER, retryable=True)


TIMEOUT = Failure("timeout", SOURCE_PROVIDER, retryable=True)
NETWORK = Failure("network", SOURCE_PROVIDER, retryable=True)
UNKNOWN = Failure("upstream", SOURCE_PROVIDER, retryable=True)
UNUSABLE = Failure("unusable_answer", SOURCE_PROVIDER, retryable=True)
NOT_CONFIGURED = Failure("not_configured", SOURCE_DOT, retryable=False)
# The phone's request was refused before any model was asked: out of the
# agreed shape, or too large. The same request is refused again, so never
# worth retrying as it is.
REQUEST_REJECTED = Failure("request_rejected", SOURCE_DOT, retryable=False, reason="validation")
TOO_LARGE = Failure("request_rejected", SOURCE_DOT, retryable=False, reason="too_large")
UNAUTHORIZED = Failure("unauthorized", SOURCE_DOT, retryable=False)
# The phone hung up before the answer: nothing to return, nothing to bill for.
CLIENT_GONE = Failure("client_gone", SOURCE_DOT, retryable=False)


def local_limit(retry_after_s: float) -> Failure:
    """Dot's own limiter, which exists so a loop on the phone cannot run up a bill."""
    return Failure("local_rate_limit", SOURCE_DOT, retryable=True, retry_after_s=round(retry_after_s, 1))
