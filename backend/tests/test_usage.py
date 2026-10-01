"""Cost measurement: counted once, and unknown when the provider did not say."""

from __future__ import annotations

import os

os.environ.setdefault("DOT_CLIENT_TOKEN", "test-token")

from fastapi.testclient import TestClient

from app import main, usage
from app.schemas import Blocked

from .test_contract import auth, body, serving


def test_cached_and_thinking_tokens_are_priced_once():
    p = usage.Pricing(1.50, 0.15, 9.00, "2026-09-24", "test")
    # 1,000 prompt tokens of which 400 cached; 50 answer and 200 thinking.
    a = usage.Attempt(True, 100, prompt=1000, cached=400, output=50, thoughts=200)
    expected = (600 * 1.50 + 400 * 0.15 + 250 * 9.00) / 1_000_000
    assert abs(a.cost(p) - expected) < 1e-12


def test_missing_usage_is_unknown_not_free():
    p = usage.Pricing(1.50, 0.15, 9.00, "2026-09-24", "test")
    assert usage.Attempt(False, 10).cost(p) is None
    m = usage.RequestMeter("s", "start", False)
    m.record(usage.Attempt(True, 10, prompt=100, output=5))
    m.record(usage.Attempt(False, 10))
    summary = m.summary("gemini-3.5-flash")
    assert summary["estimated_usd"] is None
    assert summary["usage_unknown_attempts"] == 1


def test_an_unpriced_model_has_no_estimate():
    assert usage.pricing_for("some-other-model") is None


def test_the_usage_report_holds_counts_and_nothing_else():
    with serving(Blocked(observation_id="obs-1", explanation="no", suggestion="try")) as c:
        c.post("/v1/next-step", json=body(trigger="start", goal="a very private goal"), headers=auth())
        report = c.get("/v1/usage", headers=auth()).json()
    text = str(report)
    assert "private" not in text and "Display" not in text
    session = next(iter(report["sessions"].values()))
    assert session["requests"] >= 1
    assert session["triggers"].get("start", 0) >= 1


def test_the_usage_report_needs_the_token():
    main.set_provider_for_tests(None)
    with TestClient(main.app) as c:
        assert c.get("/v1/usage").status_code == 401


def test_a_trigger_is_a_fixed_word_not_text():
    with serving(Blocked(observation_id="obs-1", explanation="no", suggestion="try")) as c:
        r = c.post("/v1/next-step", json=body(trigger="Tap Display & touch"), headers=auth())
    assert r.status_code == 422
