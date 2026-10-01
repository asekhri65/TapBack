"""Telling one failure from another, and not asking twice when it will not help.

The provider is replaced by a fake SDK client, so these run without a key or a
network. Nothing here says anything about the live provider.
"""

from __future__ import annotations

import asyncio
import os

os.environ.setdefault("DOT_CLIENT_TOKEN", "test-token")

import pytest
from fastapi.testclient import TestClient
from google.genai import errors

from app import failures, main, security
from app.config import SETTINGS
from app.provider import GeminiProvider, ProviderUnavailable
from app.schemas import NextStepRequest

from .test_contract import auth, body


def api_error(code: int, status: str, message: str = "", details: list | None = None) -> errors.APIError:
    return errors.APIError(code, {"error": {"code": code, "status": status, "message": message, "details": details or []}})


# ------------------------------------------------------------ classification

SPEND_CAP = ("Your project has exceeded its monthly spending cap. Please go to AI Studio at "
             "https://ai.studio/spend to manage your project spend cap.")


def classify(e: errors.APIError) -> failures.Failure:
    inner = e.details["error"]
    return failures.classify_provider_error(e.code, e.status, inner.get("details"), e.message)


def test_a_spend_cap_is_exhausted_quota_and_not_worth_retrying():
    f = classify(api_error(429, "RESOURCE_EXHAUSTED", SPEND_CAP))
    assert (f.category, f.reason, f.retryable) == ("quota_exhausted", "spend_cap", False)


def test_a_per_day_quota_is_exhausted_quota():
    f = classify(api_error(429, "RESOURCE_EXHAUSTED", "Quota exceeded.", [{
        "@type": "type.googleapis.com/google.rpc.QuotaFailure",
        "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_requests",
                        "quotaId": "GenerateRequestsPerDayPerProjectPerModel"}],
    }]))
    assert (f.category, f.reason, f.retryable) == ("quota_exhausted", "daily_quota", False)


def test_a_short_throttle_carries_the_providers_own_delay():
    f = classify(api_error(429, "RESOURCE_EXHAUSTED", "Quota exceeded.", [
        {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
         "violations": [{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel"}]},
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "13s"},
    ]))
    assert (f.category, f.retryable, f.retry_after_s) == ("provider_throttled", True, 13.0)


def test_an_unexplained_429_is_neither_a_throttle_nor_an_exhausted_quota():
    f = classify(api_error(429, "RESOURCE_EXHAUSTED", "Resource has been exhausted."))
    assert (f.category, f.retryable, f.retry_after_s) == ("provider_limited", True, None)


def test_an_absurd_retry_delay_is_capped():
    f = classify(api_error(429, "RESOURCE_EXHAUSTED", "", [
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "86400s"}]))
    assert f.retry_after_s == failures.MAX_HONOURED_DELAY_S


@pytest.mark.parametrize("code,status,category,retryable", [
    (503, "UNAVAILABLE", "provider_capacity", True),
    (500, "INTERNAL", "provider_capacity", True),
    (401, "UNAUTHENTICATED", "auth", False),
    (403, "PERMISSION_DENIED", "auth", False),
    (404, "NOT_FOUND", "model_not_found", False),
    (400, "INVALID_ARGUMENT", "bad_request", False),
    (418, "UNKNOWN", "upstream", True),
])
def test_other_provider_errors(code, status, category, retryable):
    f = classify(api_error(code, status, "whatever"))
    assert (f.category, f.retryable) == (category, retryable)


def test_the_providers_message_never_leaves_the_classifier():
    f = classify(api_error(429, "RESOURCE_EXHAUSTED", SPEND_CAP))
    assert "spend" not in str(f.body("cid")).replace("spend_cap", "")


# --------------------------------------------------- attempts in the backend

class FakeModels:
    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls = 0

    async def generate_content(self, **_):
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class FakeResponse:
    text = '{"kind":"blocked","observation_id":"obs-1","assessment":"not_applicable","explanation":"no","suggestion":"try"}'


def provider_with(*outcomes) -> tuple[GeminiProvider, FakeModels]:
    models = FakeModels(*outcomes)
    provider = object.__new__(GeminiProvider)
    provider._client = type("C", (), {"aio": type("A", (), {"models": models})()})()
    return provider, models


def decide(provider):
    return asyncio.run(provider.decide(NextStepRequest.model_validate(body())))


def test_a_spend_cap_is_asked_once_and_not_again():
    provider, models = provider_with(api_error(429, "RESOURCE_EXHAUSTED", SPEND_CAP))
    with pytest.raises(ProviderUnavailable) as raised:
        decide(provider)
    assert raised.value.failure.reason == "spend_cap"
    assert models.calls == 1


def test_an_unexplained_limit_is_left_to_the_phone():
    provider, models = provider_with(api_error(429, "RESOURCE_EXHAUSTED", "Resource has been exhausted."))
    with pytest.raises(ProviderUnavailable):
        decide(provider)
    assert models.calls == 1


def test_a_capacity_blip_is_tried_once_more_and_then_answers():
    provider, models = provider_with(api_error(503, "UNAVAILABLE", "overloaded"), FakeResponse())
    assert decide(provider).kind == "blocked"
    assert models.calls == 2


def test_a_long_provider_delay_is_not_waited_out_here():
    provider, models = provider_with(api_error(429, "RESOURCE_EXHAUSTED", "", [
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "30s"}]))
    with pytest.raises(ProviderUnavailable) as raised:
        decide(provider)
    assert models.calls == 1
    assert raised.value.failure.retry_after_s == 30.0


def _lesson_request():
    b = body()
    b["lesson"] = {
        "course": "Google Maps", "title": "Check the details", "objective": "Open the details.",
        "practice": {"checkpoints": ["This section tells you when the museum opens and closes."]},
    }
    b["observation"]["information"] = [{"id": "t9", "text": "Open · Closes 5:30 PM", "bounds": [0, 900, 1080, 980]}]
    return NextStepRequest.model_validate(b)


def _explain(extra=""):
    return type("R", (), {"text": '{"kind":"explain","observation_id":"obs-1","assessment":"succeeded","checkpoint_id":"c1"' + extra + "}"})()


def test_a_teaching_point_that_names_nothing_is_asked_for_once_more_with_the_reason():
    provider, models = provider_with(_explain(), _explain(',"information_id":"t9"'))
    decision = asyncio.run(provider.decide(_lesson_request()))
    assert decision.candidate_id == "t9"
    assert models.calls == 2


def test_a_teaching_point_that_still_names_nothing_is_used_rather_than_failed():
    provider, models = provider_with(_explain(), _explain())
    decision = asyncio.run(provider.decide(_lesson_request()))
    assert decision.kind == "explain" and decision.candidate_id is None
    assert models.calls == 2


def test_the_whole_request_fits_inside_the_phones_timeout():
    # The phone reads for 30 s; every attempt together must fit well inside.
    assert SETTINGS.request_budget_s < 30


# ----------------------------------------------------------- local limiter

def test_the_local_limit_says_when_to_come_back():
    limiter = security.RateLimiter(per_minute=5, burst=3)
    limiter.check("k")
    limiter.check("k")
    limiter.check("k")
    wait = limiter.retry_after("k")
    assert wait is not None and 0 < wait <= 5.0


def test_the_local_limit_answers_with_a_retry_after():
    original = security.LIMITER
    security.LIMITER = security.RateLimiter(per_minute=100, burst=2)
    try:
        main.set_provider_for_tests(None)
        with TestClient(main.app) as c:
            responses = [c.post("/v1/next-step", json=body(), headers=auth()) for _ in range(3)]
    finally:
        security.LIMITER = original
    limited = responses[-1]
    assert limited.status_code == 429
    assert int(limited.headers["Retry-After"]) >= 1
    detail = limited.json()["detail"]
    assert (detail["category"], detail["source"], detail["retryable"]) == ("local_rate_limit", "dot", True)
    assert 0 < detail["retry_after_s"] <= 5.0


def test_ordinary_human_paced_use_fits_the_allowance():
    # A step every three seconds for two minutes — faster than anyone follows
    # instructions — is never refused at the configured limits.
    limiter = security.RateLimiter(SETTINGS.rate_limit_per_minute, SETTINGS.rate_limit_burst)
    t = 1000.0
    for _ in range(40):
        assert limiter.retry_after("k", t) is None
        limiter._calls["k"].append(t)
        t += 3.0


def test_a_runaway_loop_is_stopped_within_a_second():
    limiter = security.RateLimiter(SETTINGS.rate_limit_per_minute, SETTINGS.rate_limit_burst)
    t, refused_at = 1000.0, None
    for i in range(50):
        if limiter.retry_after("k", t) is not None:
            refused_at = i
            break
        limiter._calls["k"].append(t)
        t += 0.1
    assert refused_at is not None and refused_at <= SETTINGS.rate_limit_burst


# ------------------------------------------------ refusals the phone can read

def test_a_request_out_of_shape_is_refused_in_the_phones_vocabulary_without_echoing_it():
    from .test_contract import Fake
    main.set_provider_for_tests(Fake())
    try:
        bad = body(goal="x" * 600)
        bad["observation"]["candidates"][0]["label"] = "SECRET-SCREEN-TEXT" * 20
        with TestClient(main.app) as c:
            r = c.post("/v1/next-step", json=bad, headers=auth())
        assert r.status_code == 422
        detail = r.json()["detail"]
        assert detail["category"] == "request_rejected" and detail["retryable"] is False
        assert "body.goal:string_too_long" in detail["fields"]
        assert "SECRET-SCREEN-TEXT" not in r.text, "the refusal must not echo screen text back"
    finally:
        main.set_provider_for_tests(None)


def test_plain_refusals_carry_a_category_too():
    with TestClient(main.app) as c:
        assert c.post("/v1/nothing-here", json={}).json()["detail"]["category"] == "not_found"
        r = c.post("/v1/next-step", json=body(), headers={"Authorization": "Bearer nope"})
        assert r.status_code == 401 and r.json()["detail"]["category"] == "unauthorized"


def test_an_abandoned_request_stops_asking_the_model():
    stopped = asyncio.Event()

    async def slow():
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            stopped.set()
            raise

    class Gone:
        async def is_disconnected(self):
            return True

    async def run():
        with pytest.raises(ProviderUnavailable) as raised:
            await main._unless_abandoned(slow(), Gone(), poll_s=0.01)
        await asyncio.sleep(0)
        return raised.value.failure.category

    assert asyncio.run(run()) == "client_gone"
    assert stopped.is_set(), "the model call was cancelled, not left running"


def test_a_request_that_stays_gets_its_answer():
    class Here:
        async def is_disconnected(self):
            return False

    async def quick():
        await asyncio.sleep(0.02)
        return "answer"

    assert asyncio.run(main._unless_abandoned(quick(), Here(), poll_s=0.005)) == "answer"
