"""Preview access: a code checked on the server, granted to the signed-in
install for a bounded time, once, and revocable."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main
from app.billing import Entitlements, parse_entitlement
from app.config import SETTINGS
from app.ledger import Ledger, NOT_PLUS
from app.preview import PreviewConfig, hash_code
from app.schemas import Highlight

from .test_billing import Clock, NOW
from .test_contract import Fake, auth

CODE = "TB-PREVIEW-7K2Q"


class FakeEntitlements(Entitlements):
    """RevenueCat as it answers, without the network: grants recorded."""

    def __init__(self, clock, configured=True):
        async def fetch(user_id):
            return {"subscriber": {"entitlements": {}}}
        super().__init__(secret_key="sk_test" if configured else None, fetch=fetch, clock=clock)
        self.granted: dict[str, int] = {}
        self.revoked: list[str] = []

    async def grant_promotional(self, user_id, end_ms):
        self.granted[user_id] = end_ms
        return True

    async def revoke_promotionals(self, user_id):
        self.revoked.append(user_id)
        return True


@pytest.fixture
def svc():
    clock = Clock(NOW)
    led = Ledger(":memory:", clock=clock)
    ents = FakeEntitlements(clock)
    main.set_billing_for_tests(
        ledger=led, entitlements=ents,
        preview=PreviewConfig(env={"DOT_PREVIEW_CODE_SHA256": hash_code(CODE), "DOT_PREVIEW_MAX_GRANTS": "2"}, clock=clock),
    )
    object.__setattr__(SETTINGS, "require_identity", True)
    main.set_provider_for_tests(Fake(Highlight(observation_id="obs-1", candidate_id="c1", instruction="Tap it.", expected_outcome="x")))
    try:
        with TestClient(main.app) as c:
            yield c, led, ents, clock
    finally:
        object.__setattr__(SETTINGS, "require_identity", False)
        main.set_provider_for_tests(None)
        main.set_billing_for_tests(preview=PreviewConfig(env={}))


def who(c):
    ident = c.post("/v1/identity", headers=auth()).json()
    return {**auth(), "X-Dot-User": ident["user_id"], "X-Dot-Key": ident["key"]}, ident["user_id"]


def test_a_valid_code_grants_seven_days_of_plus_to_the_signed_in_install_and_unlocks_lessons(svc):
    c, led, ents, clock = svc
    h, user = who(c)
    assert c.post("/v1/tasks", headers=h, json={"task_id": "lesson-locked-1", "kind": "lesson", "course_id": "whatsapp"}).json()["admitted"] is False
    r = c.post("/v1/preview/redeem", headers=h, json={"code": "tb preview 7k2q"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["preview"]["new"] and body["preview"]["revenuecat"]
    assert body["allowance"]["plus"]["active"] and body["allowance"]["plus"]["status"] == "preview"
    assert body["allowance"]["plus"]["will_renew"] is False, "a preview is never a renewing purchase"
    assert body["allowance"]["plus"]["product"] == "", "a preview names no store product"
    assert body["allowance"]["plus_left"] == 10
    assert abs(body["preview"]["expires_at"] - (NOW + 7 * 86400)) < 1
    assert ents.granted[user] == int((NOW + 7 * 86400) * 1000), "RevenueCat's own promotional entitlement, with the same end"
    assert c.post("/v1/tasks", headers=h, json={"task_id": "lesson-locked-2", "kind": "lesson", "course_id": "whatsapp"}).json()["admitted"]


def test_redeeming_again_is_the_same_grant_and_never_extends_it(svc):
    c, led, ents, clock = svc
    h, _ = who(c)
    first = c.post("/v1/preview/redeem", headers=h, json={"code": CODE}).json()["preview"]
    clock.t += 3 * 86400
    again = c.post("/v1/preview/redeem", headers=h, json={"code": CODE}).json()["preview"]
    assert again["new"] is False and again["expires_at"] == first["expires_at"]


def test_the_preview_ends_by_itself_and_can_be_revoked(svc):
    c, led, ents, clock = svc
    h, user = who(c)
    c.post("/v1/preview/redeem", headers=h, json={"code": CODE})
    clock.t += 7 * 86400 + 60
    assert c.get("/v1/allowance", headers=h).json()["plus"]["active"] is False

    h2, user2 = who(c)
    c.post("/v1/preview/redeem", headers=h2, json={"code": CODE})
    r = c.post("/v1/preview/revoke", headers=h2).json()
    assert r["revoked"] and r["allowance"]["plus"]["active"] is False
    assert user2 in ents.revoked
    # A revoked install does not simply redeem again.
    assert c.post("/v1/preview/redeem", headers=h2, json={"code": CODE}).status_code == 403


def test_a_wrong_code_an_unsigned_caller_and_too_many_tries_are_refused(svc):
    c, led, ents, clock = svc
    h, _ = who(c)
    assert c.post("/v1/preview/redeem", headers=h, json={"code": "WRONG-CODE"}).status_code == 403
    # No identity: nobody to grant it to.
    assert c.post("/v1/preview/redeem", headers=auth(), json={"code": CODE}).status_code in (401, 403)
    # Another install's id with a made-up key is refused.
    other = {**auth(), "X-Dot-User": h["X-Dot-User"], "X-Dot-Key": "not-the-key"}
    assert c.post("/v1/preview/redeem", headers=other, json={"code": CODE}).status_code in (401, 403)
    for _ in range(5):
        c.post("/v1/preview/redeem", headers=h, json={"code": "WRONG-CODE"})
    assert c.post("/v1/preview/redeem", headers=h, json={"code": CODE}).status_code == 429


def test_grants_are_bounded_and_a_code_can_be_switched_off(svc):
    c, led, ents, clock = svc
    for _ in range(2):
        h, _ = who(c)
        assert c.post("/v1/preview/redeem", headers=h, json={"code": CODE}).status_code == 200
    h3, _ = who(c)
    assert c.post("/v1/preview/redeem", headers=h3, json={"code": CODE}).json()["detail"]["category"] == "preview_exhausted"
    main.set_billing_for_tests(preview=PreviewConfig(env={}))
    assert c.post("/v1/preview/redeem", headers=h3, json={"code": CODE}).status_code == 404


def test_the_code_is_never_in_the_app_and_only_its_hash_is_configured():
    cfg = PreviewConfig(env={"DOT_PREVIEW_CODE_SHA256": hash_code(CODE), "DOT_PREVIEW_UNTIL": "2020-01-01"})
    assert cfg.matches(CODE.lower()) and not cfg.matches("TB-PREVIEW-7K2R")
    assert not cfg.open(), "past its last day, the code redeems nothing"


def test_a_paid_subscription_is_never_mistaken_for_a_preview_and_promotional_access_does_not_renew():
    body = {"subscriber": {
        "entitlements": {"dot_plus": {"expires_date": "2031-01-08T00:00:00Z", "purchase_date": "2031-01-01T00:00:00Z", "product_identifier": "rc_promo_dot_plus_custom"}},
        "subscriptions": {"rc_promo_dot_plus_custom": {"store": "promotional"}},
    }}
    period = parse_entitlement(body, "dot_plus", now=1_900_000_000.0)
    assert period.active and period.promotional and not period.will_renew
