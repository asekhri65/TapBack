"""Whether someone has Dot Plus, asked of RevenueCat by this service.

The phone never says it has Plus: it could say anything. This service looks
the person's own id up with RevenueCat's REST API, using a secret key only it
holds, and reads the entitlement's dates. A verified answer is kept for a few
minutes; a refresh can be asked for after a purchase or restore, but not more
often than every few seconds, so nothing on the phone can hammer RevenueCat
through here.

If RevenueCat cannot be reached, a recent verified answer stands; an old one
does not. Nothing here grants Plus without RevenueCat having said so.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import quote

import httpx

from .ledger import NOT_PLUS, PlusPeriod

log = logging.getLogger("dot")

API = "https://api.revenuecat.com/v1/subscribers/"

# How long a verified answer is used as it is.
FRESH_S = 300.0
# How long, when RevenueCat cannot be reached, an older verified answer still stands.
STALE_OK_S = 6 * 3600.0
# A forced refresh at most this often per person.
MIN_REFRESH_S = 8.0
TIMEOUT_S = 6.0


def _epoch(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def parse_entitlement(body: dict, entitlement: str, now: float) -> PlusPeriod:
    """RevenueCat's subscriber record, read for one entitlement. Pure, for testing."""
    ent = ((body or {}).get("subscriber") or {}).get("entitlements", {}).get(entitlement)
    if not ent:
        return PlusPeriod(active=False)
    expires = _epoch(ent.get("expires_date"))
    grace = _epoch(ent.get("grace_period_expires_date"))
    ends = max(t for t in (expires, grace) if t is not None) if (expires or grace) else None
    # Null expiry is a lifetime purchase: active with no end.
    active = ends is None or ends > now
    product = ent.get("product_identifier") or ""
    sub = ((body.get("subscriber") or {}).get("subscriptions") or {}).get(product) or {}
    # A subscription that has seen an unsubscribe keeps its access until it
    # ends; it just will not renew.
    will_renew = active and ends is not None and not sub.get("unsubscribe_detected_at") and not sub.get("billing_issues_detected_at")
    promotional = product.startswith("rc_promo") or sub.get("store") == "promotional"
    return PlusPeriod(
        active=active,
        period_start=_epoch(ent.get("purchase_date")),
        expires_at=expires,
        will_renew=bool(will_renew) and not promotional,
        status="verified",
        promotional=promotional,
        product=product,
    )


@dataclass
class _Cached:
    period: PlusPeriod
    at: float


class Entitlements:
    """Verified Plus status per person, cached and bounded."""

    def __init__(self, secret_key: str | None = None, entitlement: str | None = None, fetch=None, clock=time.time):
        self._key = secret_key if secret_key is not None else os.environ.get("REVENUECAT_SECRET_KEY") or None
        self._entitlement = entitlement or os.environ.get("REVENUECAT_ENTITLEMENT", "dot_plus")
        self._fetch = fetch or self._fetch_http
        self._clock = clock
        self._cache: dict[str, _Cached] = {}
        self._last_forced: dict[str, float] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    @property
    def configured(self) -> bool:
        return self._key is not None

    async def _fetch_http(self, user_id: str) -> dict:
        async with httpx.AsyncClient(timeout=TIMEOUT_S) as client:
            r = await client.get(API + quote(user_id, safe=""), headers={"Authorization": f"Bearer {self._key}"})
            r.raise_for_status()
            return r.json()

    async def status(self, user_id: str, refresh: bool = False) -> PlusPeriod:
        if not self.configured:
            return PlusPeriod(active=False, status="unconfigured")
        now = self._clock()
        cached = self._cache.get(user_id)
        if refresh and now - self._last_forced.get(user_id, 0.0) < MIN_REFRESH_S:
            refresh = False
        if cached and not refresh and now - cached.at < FRESH_S:
            return cached.period
        lock = self._locks.setdefault(user_id, asyncio.Lock())
        async with lock:
            cached = self._cache.get(user_id)
            if cached and not refresh and self._clock() - cached.at < FRESH_S:
                return cached.period
            if refresh:
                self._last_forced[user_id] = self._clock()
            try:
                period = parse_entitlement(await self._fetch(user_id), self._entitlement, self._clock())
            except Exception as exc:  # network, 4xx/5xx, bad JSON: never the key, never the body
                log.warning("entitlement check failed kind=%s", type(exc).__name__)
                if cached and self._clock() - cached.at < STALE_OK_S:
                    return PlusPeriod(**{**cached.period.__dict__, "status": "cached"})
                return PlusPeriod(active=False, status="unverified")
            self._cache[user_id] = _Cached(period, self._clock())
            return period


    async def grant_promotional(self, user_id: str, end_ms: int) -> bool:
        """RevenueCat's own promotional entitlement for [user_id], until [end_ms].
        @return True when RevenueCat granted it. Needs the secret key; logs only the outcome."""
        if not self.configured:
            return False
        url = API + quote(user_id, safe="") + "/entitlements/" + quote(self._entitlement, safe="") + "/promotional"
        try:
            async with httpx.AsyncClient(timeout=TIMEOUT_S) as client:
                r = await client.post(url, headers={"Authorization": f"Bearer {self._key}"}, json={"end_time_ms": int(end_ms)})
            ok = r.status_code in (200, 201)
            if not ok:
                log.warning("promotional grant refused status=%s", r.status_code)
        except Exception as exc:
            log.warning("promotional grant failed kind=%s", type(exc).__name__)
            ok = False
        self._cache.pop(user_id, None)
        return ok

    async def revoke_promotionals(self, user_id: str) -> bool:
        """Takes back every promotional grant of the entitlement at RevenueCat."""
        if not self.configured:
            return False
        url = API + quote(user_id, safe="") + "/entitlements/" + quote(self._entitlement, safe="") + "/revoke_promotionals"
        try:
            async with httpx.AsyncClient(timeout=TIMEOUT_S) as client:
                r = await client.post(url, headers={"Authorization": f"Bearer {self._key}"})
            ok = r.status_code == 200
            if not ok:
                log.warning("promotional revoke refused status=%s", r.status_code)
        except Exception as exc:
            log.warning("promotional revoke failed kind=%s", type(exc).__name__)
            ok = False
        self._cache.pop(user_id, None)
        return ok


ENTITLEMENTS = Entitlements()
NOT_CONFIGURED = NOT_PLUS
