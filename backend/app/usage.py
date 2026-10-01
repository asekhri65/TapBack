"""What planning costs, measured per request and per session.

Counts only: calls, attempts, tokens, whether a picture went, how long it
took, why the phone asked, and an estimated price. Never the goal, the
screen, the picture or anything typed. Sessions are keyed by a short hash of
the phone's random session id.

Token fields are read as the Gemini SDK defines them:
`prompt_token_count` is all input *including* any cached tokens;
`cached_content_token_count` is the cached part of it; `candidates_token_count`
is the visible answer; `thoughts_token_count` is thinking, billed as output
and not included in `candidates_token_count`. So input is priced as
(prompt − cached) at the input rate plus cached at the cached rate, and
output as candidates + thoughts at the output rate — nothing twice.

When the provider gives no usage for an attempt, its cost is unknown, and the
totals say so rather than counting it as free.
"""

from __future__ import annotations

import contextvars
import hashlib
import os
import time
from collections import OrderedDict
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Pricing:
    """US dollars per million tokens, and the date they were read."""

    input_per_m: float
    cached_per_m: float
    output_per_m: float
    as_of: str
    source: str


# Published Standard paid-tier prices for the default model, as read from
# https://ai.google.dev/gemini-api/docs/pricing (page dated 2026-09-24). An
# estimate: the bill is what Google says it is.
_KNOWN = {
    "gemini-3.5-flash": Pricing(1.50, 0.15, 9.00, "2026-09-24", "ai.google.dev/gemini-api/docs/pricing"),
}


def pricing_for(model: str) -> Pricing | None:
    """Prices from the environment if all are set, else the known table, else none."""
    env = [os.environ.get(k) for k in ("DOT_PRICE_INPUT_PER_M", "DOT_PRICE_CACHED_PER_M", "DOT_PRICE_OUTPUT_PER_M")]
    if all(env):
        try:
            return Pricing(float(env[0]), float(env[1]), float(env[2]),
                           os.environ.get("DOT_PRICE_AS_OF", "unspecified"), "environment")
        except ValueError:
            return None
    return _KNOWN.get(model)


@dataclass
class Attempt:
    """One call to the provider."""

    ok: bool
    latency_ms: int
    prompt: int | None = None
    cached: int | None = None
    output: int | None = None
    thoughts: int | None = None

    @property
    def usage_known(self) -> bool:
        return self.prompt is not None

    def cost(self, p: Pricing | None) -> float | None:
        if p is None or not self.usage_known:
            return None
        cached = self.cached or 0
        fresh = max(0, (self.prompt or 0) - cached)
        out = (self.output or 0) + (self.thoughts or 0)
        return (fresh * p.input_per_m + cached * p.cached_per_m + out * p.output_per_m) / 1_000_000


@dataclass
class RequestMeter:
    """Everything one planning request cost."""

    session: str
    trigger: str
    image: bool
    attempts: list[Attempt] = field(default_factory=list)
    started: float = field(default_factory=time.monotonic)

    def record(self, attempt: Attempt) -> None:
        self.attempts.append(attempt)

    def summary(self, model: str) -> dict:
        p = pricing_for(model)
        costs = [a.cost(p) for a in self.attempts]
        known = [c for c in costs if c is not None]
        return {
            "session": self.session,
            "trigger": self.trigger or "unspecified",
            "image": self.image,
            "attempts": len(self.attempts),
            "retries": max(0, len(self.attempts) - 1),
            "prompt_tokens": _sum(a.prompt for a in self.attempts),
            "cached_tokens": _sum(a.cached for a in self.attempts),
            "output_tokens": _sum(a.output for a in self.attempts),
            "thinking_tokens": _sum(a.thoughts for a in self.attempts),
            "latency_ms": int((time.monotonic() - self.started) * 1000),
            # None, not zero, when any attempt's usage is missing.
            "estimated_usd": round(sum(known), 6) if known and len(known) == len(costs) else None,
            "usage_unknown_attempts": len(costs) - len(known),
        }


def _sum(values) -> int | None:
    values = list(values)
    if not values or any(v is None for v in values):
        present = [v for v in values if v is not None]
        return sum(present) if present else None
    return sum(values)


# The request being metered, so the provider can record attempts without a
# change to its signature.
CURRENT: contextvars.ContextVar[RequestMeter | None] = contextvars.ContextVar("dot_meter", default=None)


def session_key(session_id: str) -> str:
    return hashlib.sha256(session_id.encode()).hexdigest()[:10]


class Ledger:
    """Per-session totals, in memory, for the most recent sessions only."""

    def __init__(self, keep: int = 200) -> None:
        self._keep = keep
        self._sessions: OrderedDict[str, dict] = OrderedDict()

    def add(self, summary: dict, ok: bool) -> None:
        s = self._sessions.setdefault(summary["session"], {
            "requests": 0, "failed_requests": 0, "attempts": 0, "retries": 0, "with_image": 0,
            "prompt_tokens": 0, "cached_tokens": 0, "output_tokens": 0, "thinking_tokens": 0,
            "latency_ms": 0, "estimated_usd": 0.0, "cost_unknown_requests": 0, "triggers": {},
        })
        self._sessions.move_to_end(summary["session"])
        s["requests"] += 1
        s["failed_requests"] += 0 if ok else 1
        s["attempts"] += summary["attempts"]
        s["retries"] += summary["retries"]
        s["with_image"] += 1 if summary["image"] else 0
        for k in ("prompt_tokens", "cached_tokens", "output_tokens", "thinking_tokens"):
            s[k] += summary[k] or 0
        s["latency_ms"] += summary["latency_ms"]
        if summary["estimated_usd"] is None:
            if summary["attempts"]:
                s["cost_unknown_requests"] += 1
        else:
            s["estimated_usd"] = round(s["estimated_usd"] + summary["estimated_usd"], 6)
        s["triggers"][summary["trigger"]] = s["triggers"].get(summary["trigger"], 0) + 1
        while len(self._sessions) > self._keep:
            self._sessions.popitem(last=False)

    def snapshot(self) -> dict:
        return {"sessions": dict(self._sessions), "estimate_note": _NOTE}


_NOTE = ("estimated_usd is an estimate from published per-token prices; "
         "requests whose usage the provider did not report are counted in "
         "cost_unknown_requests, not as zero")

LEDGER = Ledger()
