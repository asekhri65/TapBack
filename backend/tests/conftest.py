"""Test settings, set before the service is imported.

The contract tests predate identities and send planning requests with the
client token alone; the ledger's own tests turn identities on explicitly.
The ledger lives in memory, never on disk.
"""

import os

os.environ.setdefault("DOT_REQUIRE_IDENTITY", "0")
os.environ.setdefault("DOT_DB_PATH", ":memory:")
os.environ.setdefault("DOT_CLIENT_TOKEN", "test-token")

import pytest


@pytest.fixture(autouse=True)
def _fresh_limiter():
    """Each test starts with a full allowance; they share one client address."""
    from app import security

    original = security.LIMITER
    security.LIMITER = security.RateLimiter(original._per_minute, original._burst)
    yield
    security.LIMITER = original
