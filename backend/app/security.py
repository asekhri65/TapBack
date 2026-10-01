"""Who may call, and how often.

Both are deliberately simple. This is a single-tenant development service for
one phone; the point is that it cannot be called by anything that has not been
given the token, and that a loop in the client cannot run up a bill.
"""

from __future__ import annotations

import hmac
import time
from collections import defaultdict, deque

from fastapi import Header, HTTPException, Request, status

from .config import SETTINGS
from .failures import local_limit


def require_client_token(authorization: str | None = Header(default=None)) -> None:
    """Bearer token, compared in constant time.

    A service with no token configured refuses everything rather than running
    open: an unauthenticated endpoint that forwards to a paid model is not a
    development convenience, it is a bill waiting to happen.
    """
    expected = SETTINGS.client_token
    if not expected:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "This service has no DOT_CLIENT_TOKEN configured.",
        )
    supplied = ""
    if authorization and authorization.lower().startswith("bearer "):
        supplied = authorization[7:].strip()
    if not hmac.compare_digest(supplied, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Bad or missing token.")


def token_ok(authorization: str | None) -> bool:
    expected = SETTINGS.client_token
    if not expected:
        return False
    supplied = authorization[7:].strip() if authorization and authorization.lower().startswith("bearer ") else ""
    return hmac.compare_digest(supplied, expected)


def require_caller(request: Request, authorization: str | None = Header(default=None)) -> None:
    """Who may call at all.

    With the shared token, anyone (development, a trusted build). Without it,
    only when the service runs with install identities (DOT_INSTALL_AUTH=1),
    and then every endpoint past registration checks the caller's own
    identity (see require_identity in main), and registration is limited per
    address. Otherwise exactly as require_client_token.
    """
    ok = token_ok(authorization)
    request.state.token_ok = ok
    if ok:
        return
    if SETTINGS.install_auth:
        return
    require_client_token(authorization)


class Window:
    """At most [limit] events per [seconds], per key: for rare, costly actions."""

    def __init__(self, limit: int, seconds: float) -> None:
        self._limit, self._seconds = limit, seconds
        self._events: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str, now: float | None = None) -> bool:
        now = time.monotonic() if now is None else now
        events = self._events[key]
        while events and now - events[0] > self._seconds:
            events.popleft()
        if len(events) >= self._limit:
            return False
        events.append(now)
        return True


REGISTRATIONS = Window(SETTINGS.registrations_per_hour, 3600.0)


def limit_registration(request: Request) -> None:
    """New identities from one address, without the shared token, are few."""
    if getattr(request.state, "token_ok", False):
        return
    key = request.client.host if request.client else "unknown"
    if not REGISTRATIONS.allow(key):
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            {"category": "local_rate_limit", "source": "dot", "retryable": True, "retry_after_s": 600.0,
             "reason": "registrations", "correlation_id": getattr(request.state, "correlation_id", "")},
            headers={"Retry-After": "600"},
        )


class RateLimiter:
    """A small sliding window, per caller.

    Keyed by client address rather than by token, because there is one token
    and the thing being protected against is a runaway loop on one device.
    """

    def __init__(self, per_minute: int, burst: int) -> None:
        self._per_minute = per_minute
        self._burst = burst
        self._calls: dict[str, deque[float]] = defaultdict(deque)

    def retry_after(self, key: str, now: float | None = None) -> float | None:
        """Seconds until one more call would be allowed, or None if it would be now."""
        now = time.monotonic() if now is None else now
        calls = self._calls[key]
        while calls and now - calls[0] > 60.0:
            calls.popleft()
        waits = []
        if len(calls) >= self._per_minute:
            # The oldest call leaves the minute's window first.
            waits.append(60.0 - (now - calls[0]))
        recent = [t for t in calls if now - t < 5.0]
        if len(recent) >= self._burst:
            # The call that must age out of the five-second window.
            waits.append(5.0 - (now - recent[len(recent) - self._burst]))
        return max(waits) if waits else None

    def check(self, key: str, correlation_id: str = "") -> None:
        now = time.monotonic()
        wait = self.retry_after(key, now)
        if wait is not None:
            # Says when, so the phone waits that long once rather than asking
            # again and again into the limit.
            failure = local_limit(max(0.1, wait))
            raise HTTPException(
                status.HTTP_429_TOO_MANY_REQUESTS,
                failure.body(correlation_id),
                headers={"Retry-After": str(max(1, int(-(-wait // 1))))},
            )
        self._calls[key].append(now)


LIMITER = RateLimiter(SETTINGS.rate_limit_per_minute, SETTINGS.rate_limit_burst)


def rate_limit(request: Request) -> None:
    LIMITER.check(
        request.client.host if request.client else "unknown",
        getattr(request.state, "correlation_id", ""),
    )
