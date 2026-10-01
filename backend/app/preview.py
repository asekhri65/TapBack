"""Temporary preview access to TapBack Plus, for recording lessons.

A preview code is checked here, against the hash of the configured code in
this service's own environment — never against anything in the app, which
holds no code at all. A valid code grants the person who is signed in (the
install's own identity, checked on every request) seven days of Plus:
through RevenueCat's own promotional entitlement when this service holds a
RevenueCat secret key, and in this service's ledger either way, marked
"preview" so nothing ever calls it a purchase.

Bounded: the code can be switched off by removing its hash, can carry an end
date, grants to at most a set number of installs, and redemption attempts are
rate-limited per install and per address. Redeeming again returns the grant
already made; it never extends it. A grant can be revoked.

Configuration (backend/.env):
  DOT_PREVIEW_CODE_SHA256   hex SHA-256 of the code; unset turns previews off
  DOT_PREVIEW_DAYS          length of a preview, default 7
  DOT_PREVIEW_UNTIL         optional last day the code may be redeemed (YYYY-MM-DD)
  DOT_PREVIEW_MAX_GRANTS    most installs that may hold a preview, default 5
"""

from __future__ import annotations

import hashlib
import hmac
import os
import time
from datetime import datetime, timezone


def _normalise(code: str) -> str:
    # Codes are read out and typed: case, spaces and dashes do not matter.
    return "".join(ch for ch in code.upper() if ch.isalnum())


def hash_code(code: str) -> str:
    return hashlib.sha256(_normalise(code).encode()).hexdigest()


class PreviewConfig:
    def __init__(self, env=None, clock=time.time):
        env = env if env is not None else os.environ
        self.hashes = [h.strip().lower() for h in env.get("DOT_PREVIEW_CODE_SHA256", "").split(",") if h.strip()]
        self.days = float(env.get("DOT_PREVIEW_DAYS", "7") or 7)
        self.max_grants = int(env.get("DOT_PREVIEW_MAX_GRANTS", "5") or 5)
        until = env.get("DOT_PREVIEW_UNTIL", "").strip()
        self.until = datetime.strptime(until, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp() + 86400 if until else None
        self._clock = clock

    @property
    def enabled(self) -> bool:
        return bool(self.hashes)

    def open(self) -> bool:
        return self.enabled and (self.until is None or self._clock() < self.until)

    def matches(self, code: str) -> bool:
        if not code or len(code) > 64:
            return False
        given = hash_code(code)
        # Constant-time against each configured hash.
        return any(hmac.compare_digest(given, h) for h in self.hashes)
