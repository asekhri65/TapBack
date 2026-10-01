"""Everything the service reads from its environment.

The provider key lives here and nowhere else: not in the app, not in Gradle,
not in source control. The Android client talks to this service, and this
service talks to the model.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """Read once at import. Nothing here is logged."""

    # The model. Stable and image-capable; see README for how it was chosen.
    model: str = field(default_factory=lambda: os.environ.get("GEMINI_MODEL", "gemini-3.5-flash"))

    # Present only when the operator has configured a key. Its absence is a
    # supported state: the service starts, reports itself unconfigured on
    # /healthz, and refuses planning requests with a clear message rather than
    # failing obscurely at the first call.
    api_key: str | None = field(default_factory=lambda: os.environ.get("GEMINI_API_KEY") or None)

    # What the Android client must present. Generated per deployment; the debug
    # client reads it from a local property, never from a checked-in constant.
    client_token: str | None = field(
        default_factory=lambda: os.environ.get("DOT_CLIENT_TOKEN") or None
    )

    request_timeout_s: float = field(default_factory=lambda: float(os.environ.get("DOT_TIMEOUT_S", "20")))
    max_attempts: int = field(default_factory=lambda: _int("DOT_MAX_ATTEMPTS", 2))
    # The whole request, every attempt included. Kept under the phone's 30 s
    # read timeout so it always hears the outcome it is paying for.
    request_budget_s: float = field(default_factory=lambda: float(os.environ.get("DOT_REQUEST_BUDGET_S", "25")))

    # A screen's worth of controls and an optional screenshot. Generous enough
    # for a dense settings page, small enough that a runaway client cannot post
    # arbitrary data.
    max_body_bytes: int = field(default_factory=lambda: _int("DOT_MAX_BODY_BYTES", 4 * 1024 * 1024))
    max_candidates: int = field(default_factory=lambda: _int("DOT_MAX_CANDIDATES", 120))

    rate_limit_per_minute: int = field(default_factory=lambda: _int("DOT_RATE_PER_MIN", 30))
    rate_limit_burst: int = field(default_factory=lambda: _int("DOT_RATE_BURST", 8))

    # Off by default, and the only thing it turns on is *structural* tracing:
    # how many candidates arrived, which decision came back. Never the goal,
    # never a label, never an image.
    verbose: bool = field(default_factory=lambda: os.environ.get("DOT_VERBOSE") == "1")

    # A hosted service reached by release builds, which carry no shared
    # secret: each install proves itself with its own identity instead of
    # DOT_CLIENT_TOKEN, and registering a new identity is limited per
    # address. The shared token still works (development, trusted clients).
    install_auth: bool = field(default_factory=lambda: os.environ.get("DOT_INSTALL_AUTH") == "1")
    registrations_per_hour: int = field(default_factory=lambda: _int("DOT_REGISTRATIONS_PER_HOUR", 10))

    # Every planning request belongs to a person and an admitted task. Off
    # only for the contract tests that predate identities.
    require_identity: bool = field(default_factory=lambda: os.environ.get("DOT_REQUIRE_IDENTITY", "1") != "0")

    # The usage ledger: who is who, and which tasks were started. SQLite, on
    # this machine; ":memory:" for tests.
    db_path: str = field(
        default_factory=lambda: os.environ.get(
            "DOT_DB_PATH", os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "dot.sqlite3")
        )
    )


SETTINGS = Settings()
