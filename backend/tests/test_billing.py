"""The usage ledger and the entitlement check, without RevenueCat or a model.

A fake clock moves time; a fake RevenueCat answers with records shaped like
its v1 subscriber API. Nothing here says anything about the live services.
"""

from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient

from app import billing, main
from app.billing import Entitlements, parse_entitlement
from app.config import SETTINGS
from app.ledger import (
    FREE_WEEKLY, LESSON_FREE, LESSON_PLUS, MAX_REQUESTS_PER_TASK, NOT_PLUS, PLUS, PLUS_TASKS_PER_PERIOD, WEEK_S,
    WELCOME, Ledger, LedgerError, PlusPeriod,
)
from app.schemas import Highlight, Blocked

from .test_contract import Fake, auth, body


class Clock:
    def __init__(self, t: float = 1_800_000_000.0):
        self.t = t

    def __call__(self) -> float:
        return self.t


def used(led: Ledger, user: str, task_id: str, kind="ask", plus_period=NOT_PLUS, course_id=""):
    """Start a task and have Dot help with it: its credit is spent for good."""
    adm, allowance = led.admit(user, task_id, kind, plus_period, course_id)
    if adm.admitted:
        led.count_request(user, task_id)
        led.mark_useful(task_id)
    return adm, allowance


def ledger(clock=None) -> tuple[Ledger, str, Clock]:
    clock = clock or Clock()
    led = Ledger(":memory:", clock=clock)
    user, _key = led.register()
    return led, user, clock


def plus(start: float, days: float = 7.0) -> PlusPeriod:
    return PlusPeriod(active=True, period_start=start, expires_at=start + days * 86400, will_renew=True)


# ------------------------------------------------------------------ identity

def test_only_the_install_with_the_secret_is_that_person():
    led = Ledger(":memory:")
    user, key = led.register()
    assert led.authenticate(user, key) == user
    for bad in ((user, "wrong"), ("dot_nobody", key), (user, None), (None, key)):
        with pytest.raises(LedgerError):
            led.authenticate(*bad)


# ------------------------------------------------------------ free allowance

def test_two_welcome_tasks_once_and_no_free_task_comes_back_by_itself():
    led, user, clock = ledger()
    for i in range(2):
        adm, _ = used(led, user, f"welcome-{i}", "ask", NOT_PLUS)
        assert adm.admitted and adm.source == WELCOME
    adm, allowance = used(led, user, "third", "ask", NOT_PLUS)
    assert not adm.admitted and adm.reason == "no_allowance"
    assert allowance.next_free_at is None, "no recurring weekly free task: nothing to count down to"
    assert not allowance.free_weekly_available

    # Weeks later: still nothing free for Ask. The free course stays free.
    clock.t += 5 * WEEK_S
    assert not used(led, user, "week-5", "ask", NOT_PLUS)[0].admitted
    assert used(led, user, "lesson", "lesson", NOT_PLUS, course_id="maps")[0].admitted


def test_the_same_task_admitted_twice_is_charged_once():
    led, user, _ = ledger()
    first, _ = led.admit(user, "same-task", "ask", NOT_PLUS)
    again, allowance = led.admit(user, "same-task", "ask", NOT_PLUS)
    assert first.admitted and again.admitted and again.replay
    assert allowance.welcome_left == 1


def test_a_task_dot_never_helped_with_gives_its_credit_back_and_one_it_helped_with_does_not():
    led, user, _ = ledger()
    led.admit(user, "failed", "ask", NOT_PLUS)
    led.count_request(user, "failed")
    assert led.release(user, "failed")
    assert led.allowance(user, NOT_PLUS).welcome_left == 2

    led.admit(user, "helped", "ask", NOT_PLUS)
    led.count_request(user, "helped")
    led.mark_useful("helped")
    assert not led.release(user, "helped")
    assert led.allowance(user, NOT_PLUS).welcome_left == 1


def test_an_unused_reservation_comes_back_and_a_resume_takes_it_again_only_if_it_is_there():
    led, user, clock = ledger()
    led.admit(user, "left-open", "ask", NOT_PLUS)
    clock.t += 7 * 3600
    assert led.allowance(user, NOT_PLUS).welcome_left == 2, "released after sitting unused"
    led.count_request(user, "left-open")  # resumed: takes its credit again
    assert led.allowance(user, NOT_PLUS).welcome_left == 1
    assert led.task("left-open")["state"] == "active"


def test_somebody_elses_task_is_not_yours():
    led, user, _ = ledger()
    other, _ = led.register()
    led.admit(user, "mine-1234", "ask", NOT_PLUS)
    with pytest.raises(LedgerError):
        led.admit(other, "mine-1234", "ask", NOT_PLUS)
    with pytest.raises(LedgerError):
        led.count_request(other, "mine-1234")
    with pytest.raises(LedgerError):
        led.release(other, "mine-1234")


def test_a_runaway_loop_stops_at_the_task_limit():
    led, user, _ = ledger()
    led.admit(user, "loop-task", "ask", NOT_PLUS)
    for _ in range(MAX_REQUESTS_PER_TASK):
        led.count_request(user, "loop-task")
    with pytest.raises(LedgerError) as raised:
        led.count_request(user, "loop-task")
    assert raised.value.code == "task_limit"


def test_requests_for_a_task_never_admitted_are_refused():
    led, user, _ = ledger()
    with pytest.raises(LedgerError) as raised:
        led.count_request(user, "never-admitted")
    assert raised.value.code == "not_admitted"


# --------------------------------------------------------------------- plus

def test_plus_is_ten_new_tasks_a_paid_period_and_the_next_period_starts_afresh():
    led, user, clock = ledger()
    period = plus(clock.t)
    for i in range(PLUS_TASKS_PER_PERIOD):
        adm, _ = used(led, user, f"plus-{i}", "ask", period)
        assert adm.source == PLUS
    # Plus used up: the welcome tasks are still there.
    adm, allowance = used(led, user, "after-plus", "ask", period)
    assert adm.source == WELCOME and allowance.plus_left == 0
    renewed = plus(clock.t + 7 * 86400)
    clock.t += 7 * 86400
    assert led.allowance(user, renewed).plus_left == PLUS_TASKS_PER_PERIOD


def test_an_expired_subscription_leaves_the_free_allowance_and_admitted_tasks_alone():
    led, user, clock = ledger()
    led.admit(user, "while-plus", "ask", plus(clock.t))
    # Expired: no more Plus tasks, but the one admitted carries on.
    led.count_request(user, "while-plus")
    adm, allowance = led.admit(user, "after", "ask", NOT_PLUS)
    assert adm.source == WELCOME and allowance.plus_left is None


# ------------------------------------------------------------------ lessons

def test_lessons_never_cost_an_ask_task_and_premium_courses_need_plus():
    led, user, clock = ledger()
    adm, allowance = led.admit(user, "maps-lesson", "lesson", NOT_PLUS, course_id="maps")
    assert adm.admitted and adm.source == LESSON_FREE and allowance.welcome_left == 2
    adm, _ = led.admit(user, "whatsapp-lesson", "lesson", NOT_PLUS, course_id="whatsapp")
    assert not adm.admitted and adm.reason == "premium_course"
    adm, allowance = led.admit(user, "whatsapp-plus", "lesson", plus(clock.t), course_id="whatsapp")
    assert adm.source == LESSON_PLUS and allowance.plus_left == PLUS_TASKS_PER_PERIOD
    # Plus lapses mid-lesson: the admitted lesson carries on.
    led.count_request(user, "whatsapp-plus")


# -------------------------------------------------------- RevenueCat record

NOW = 1_800_000_000.0


def record(expires="2027-02-10T00:00:00Z", **sub):
    return {
        "subscriber": {
            "entitlements": {"dot_plus": {"expires_date": expires, "purchase_date": "2027-01-12T00:00:00Z",
                                          "product_identifier": "dot_plus_weekly", "grace_period_expires_date": None}},
            "subscriptions": {"dot_plus_weekly": {"unsubscribe_detected_at": None, "billing_issues_detected_at": None, **sub}},
        }
    }


def test_the_entitlement_is_read_from_its_dates():
    active = parse_entitlement(record(), "dot_plus", NOW)
    assert active.active and active.will_renew and active.period_start is not None
    expired = parse_entitlement(record(expires="2020-01-01T00:00:00Z"), "dot_plus", NOW)
    assert not expired.active
    cancelled = parse_entitlement(record(unsubscribe_detected_at="2027-01-13T00:00:00Z"), "dot_plus", NOW)
    assert cancelled.active and not cancelled.will_renew, "cancelling keeps access until the period ends"
    lifetime = parse_entitlement(record(expires=None), "dot_plus", NOW)
    assert lifetime.active
    assert not parse_entitlement({"subscriber": {"entitlements": {}}}, "dot_plus", NOW).active


def test_a_purchase_is_told_from_a_promotional_grant_by_its_product():
    bought = parse_entitlement(record(), "dot_plus", NOW)
    assert bought.product == "dot_plus_weekly" and not bought.promotional
    promo = {"subscriber": {"entitlements": {"dot_plus": {"expires_date": "2027-02-10T00:00:00Z",
             "purchase_date": "2027-01-12T00:00:00Z", "product_identifier": "rc_promo_dot_plus_custom"}},
             "subscriptions": {"rc_promo_dot_plus_custom": {"store": "promotional"}}}}
    granted = parse_entitlement(promo, "dot_plus", NOW)
    assert granted.active and granted.promotional and not granted.will_renew


def test_no_billing_configured_never_grants_plus():
    ent = Entitlements(secret_key=None, fetch=lambda u: record())
    ent._key = None
    status = asyncio.run(ent.status("dot_x"))
    assert not status.active and status.status == "unconfigured"


def test_revenuecat_unreachable_keeps_a_recent_answer_but_never_invents_one():
    clock = Clock(NOW)
    calls = {"n": 0, "fail": False}

    async def fetch(user_id):
        calls["n"] += 1
        if calls["fail"]:
            raise RuntimeError("down")
        return record()

    ent = Entitlements(secret_key="sk_test", fetch=fetch, clock=clock)
    assert asyncio.run(ent.status("dot_a")).active
    calls["fail"] = True
    clock.t += billing.FRESH_S + 1
    cached = asyncio.run(ent.status("dot_a"))
    assert cached.active and cached.status == "cached"
    fresh_user = asyncio.run(ent.status("dot_b"))
    assert not fresh_user.active and fresh_user.status == "unverified"


def test_forced_refreshes_are_bounded():
    clock = Clock(NOW)
    calls = {"n": 0}

    async def fetch(user_id):
        calls["n"] += 1
        return record()

    ent = Entitlements(secret_key="sk_test", fetch=fetch, clock=clock)
    for _ in range(5):
        asyncio.run(ent.status("dot_a", refresh=True))
    assert calls["n"] == 1
    clock.t += billing.MIN_REFRESH_S + 1
    asyncio.run(ent.status("dot_a", refresh=True))
    assert calls["n"] == 2


# ------------------------------------------------------------ over HTTP

@pytest.fixture
def service():
    clock = Clock(NOW)
    led = Ledger(":memory:", clock=clock)

    async def fetch(user_id):
        return {"subscriber": {"entitlements": {}}}

    main.set_billing_for_tests(ledger=led, entitlements=Entitlements(secret_key="sk_test", fetch=fetch, clock=clock))
    object.__setattr__(SETTINGS, "require_identity", True)
    main.set_provider_for_tests(Fake(Highlight(observation_id="obs-1", candidate_id="c1",
                                               instruction="Tap Display & touch.", expected_outcome="It opens.")))
    try:
        with TestClient(main.app) as c:
            yield c, led
    finally:
        object.__setattr__(SETTINGS, "require_identity", False)
        main.set_provider_for_tests(None)


def who(c):
    ident = c.post("/v1/identity", headers=auth()).json()
    return {**auth(), "X-Dot-User": ident["user_id"], "X-Dot-Key": ident["key"]}


def test_a_task_is_admitted_once_over_http_and_planning_needs_it(service):
    c, led = service
    me = who(c)
    assert c.post("/v1/next-step", json=body(), headers={**me, "X-Dot-Task": "not-started"}).status_code == 403
    first = c.post("/v1/tasks", json={"task_id": "task-0001", "kind": "ask"}, headers=me).json()
    again = c.post("/v1/tasks", json={"task_id": "task-0001", "kind": "ask"}, headers=me).json()
    assert first["admitted"] and again["replay"] and again["allowance"]["welcome_left"] == 1
    r = c.post("/v1/next-step", json=body(), headers={**me, "X-Dot-Task": "task-0001"})
    assert r.status_code == 200
    assert led.task("task-0001")["useful"] == 1
    assert c.post("/v1/tasks/task-0001/release", headers=me).json()["released"] is False


def test_someone_elses_id_without_their_secret_gets_nothing(service):
    c, _ = service
    me = who(c)
    thief = {**me, "X-Dot-Key": "guessed"}
    assert c.get("/v1/allowance", headers=thief).status_code == 401
    # Saying you have Plus does nothing: only RevenueCat's word counts.
    r = c.get("/v1/allowance", headers={**me, "X-Dot-Plus": "true"}).json()
    assert r["plus"]["active"] is False


def test_a_failed_first_answer_leaves_the_credit_to_be_released(service):
    c, led = service
    main.set_provider_for_tests(Fake(Blocked(observation_id="obs-1", explanation="Not here.", suggestion="Try Settings.")))
    me = who(c)
    c.post("/v1/tasks", json={"task_id": "task-0002", "kind": "ask"}, headers=me)
    c.post("/v1/next-step", json=body(), headers={**me, "X-Dot-Task": "task-0002"})
    released = c.post("/v1/tasks/task-0002/release", headers=me).json()
    assert released["released"] and released["allowance"]["welcome_left"] == 2


# ------------------------------------------------ release builds: no secret

@pytest.fixture
def hosted():
    """The service as a release build meets it: no shared token in the app."""
    from app import security
    clock = Clock(NOW)
    led = Ledger(":memory:", clock=clock)

    async def fetch(user_id):
        return {"subscriber": {"entitlements": {}}}

    main.set_billing_for_tests(ledger=led, entitlements=Entitlements(secret_key="sk_test", fetch=fetch, clock=clock))
    object.__setattr__(SETTINGS, "install_auth", True)
    old = security.REGISTRATIONS
    security.REGISTRATIONS = security.Window(3, 3600.0)
    main.set_provider_for_tests(Fake(Highlight(observation_id="obs-1", candidate_id="c1",
                                               instruction="Tap Display & touch.", expected_outcome="It opens.")))
    try:
        with TestClient(main.app) as c:
            yield c
    finally:
        object.__setattr__(SETTINGS, "install_auth", False)
        security.REGISTRATIONS = old
        main.set_provider_for_tests(None)


def test_a_release_build_works_with_its_own_identity_and_no_shared_secret(hosted):
    c = hosted
    ident = c.post("/v1/identity").json()
    me = {"X-Dot-User": ident["user_id"], "X-Dot-Key": ident["key"]}
    assert c.post("/v1/tasks", json={"task_id": "task-rel-01", "kind": "ask"}, headers=me).json()["admitted"]
    assert c.post("/v1/next-step", json=body(), headers={**me, "X-Dot-Task": "task-rel-01"}).status_code == 200


def test_without_the_shared_secret_nothing_works_without_an_identity(hosted):
    c = hosted
    assert c.post("/v1/next-step", json=body()).status_code in (401, 403)
    assert c.get("/v1/allowance").status_code == 401
    assert c.get("/v1/usage").status_code == 401, "the developer report still needs the shared token"


def test_registration_without_the_shared_secret_is_limited_per_address(hosted):
    c = hosted
    codes = [c.post("/v1/identity").status_code for _ in range(4)]
    assert codes == [200, 200, 200, 429]
    assert c.post("/v1/identity", headers=auth()).status_code == 200, "the shared token is not limited this way"


def test_a_service_without_install_identities_still_needs_the_shared_token():
    with TestClient(main.app) as c:
        assert c.post("/v1/identity").status_code == 401


def test_the_allowance_says_where_plus_came_from_and_how_often_it_can_be_rechecked(service):
    c, led = service
    body = c.get("/v1/allowance", headers=who(c)).json()
    assert body["refresh_min_s"] == billing.MIN_REFRESH_S
    assert body["plus"]["promotional"] is False and body["plus"]["product"] == ""
