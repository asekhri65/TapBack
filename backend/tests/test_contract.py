"""The service's contract, exercised without a key or a network.

A fake provider stands in for the model, so everything around it — auth, limits,
validation, the shape of a decision — is tested deterministically. The live
provider is exercised separately, by hand, and is not claimed here.
"""

from __future__ import annotations

import os

os.environ.setdefault("DOT_CLIENT_TOKEN", "test-token")

import pytest
from fastapi.testclient import TestClient

from app import main
from app.provider import ProviderRefused, ProviderUnavailable
from app.schemas import Ask, Blocked, Highlight


def observation(**over):
    base = {
        "observation_id": "obs-1",
        "taken_at_ms": 1,
        "package_name": "com.android.settings",
        "screen_width": 1170,
        "screen_height": 2532,
        "candidates": [
            {
                "id": "c1",
                "label": "Display & touch",
                "role": "row",
                "bounds": [0, 100, 1170, 400],
                "clickable": True,
            },
            {
                "id": "c2",
                "label": "Sound & vibration",
                "role": "row",
                "bounds": [0, 400, 1170, 700],
                "clickable": True,
            },
        ],
    }
    base.update(over)
    return base


def body(**over):
    base = {
        "session_id": "s-1",
        "goal": "make the text bigger",
        "observation": observation(),
    }
    base.update(over)
    return base


class Fake:
    name = "fake"

    def __init__(self, decision=None, error=None):
        self.decision, self.error = decision, error
        self.calls = 0

    async def decide(self, request):
        self.calls += 1
        if self.error:
            raise self.error
        return self.decision


@pytest.fixture(autouse=True)
def fresh_limiter():
    """Each test starts with a full allowance; they share one client address."""
    from app import security
    original = security.LIMITER
    security.LIMITER = security.RateLimiter(original._per_minute, original._burst)
    yield
    security.LIMITER = original


@pytest.fixture
def client():
    main.set_provider_for_tests(Fake(
        Highlight(observation_id="obs-1", candidate_id="c1",
                  instruction="Tap Display & touch.", expected_outcome="The display page opens.")
    ))
    with TestClient(main.app) as c:
        yield c
    main.set_provider_for_tests(None)


def serving(decision=None, error=None):
    """A client whose model answers with exactly this."""
    main.set_provider_for_tests(Fake(decision, error))
    return TestClient(main.app)


def auth():
    return {"Authorization": "Bearer test-token"}


def test_health_says_what_is_configured(client):
    r = client.get("/healthz")
    assert r.status_code == 200
    assert r.json()["auth_configured"] is True
    assert "model" in r.json()


def test_a_call_without_a_token_is_refused(client):
    assert client.post("/v1/next-step", json=body()).status_code == 401


def test_a_call_with_the_wrong_token_is_refused(client):
    r = client.post("/v1/next-step", json=body(), headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401


def test_a_grounded_highlight_comes_back(client):
    r = client.post("/v1/next-step", json=body(), headers=auth())
    assert r.status_code == 200
    decision = r.json()["decision"]
    assert decision["kind"] == "highlight"
    assert decision["candidate_id"] == "c1"
    assert decision["observation_id"] == "obs-1"


def test_a_goal_that_is_too_long_is_refused(client):
    assert client.post("/v1/next-step", json=body(goal="x" * 600), headers=auth()).status_code == 422


def test_an_empty_goal_is_refused(client):
    assert client.post("/v1/next-step", json=body(goal="  "), headers=auth()).status_code == 422


def test_too_many_controls_are_refused(client):
    many = observation(candidates=[
        {"id": f"c{i}", "label": f"row {i}", "bounds": [0, i, 10, i + 1]} for i in range(500)
    ])
    r = client.post("/v1/next-step", json=body(observation=many), headers=auth())
    assert r.status_code == 413


def test_an_unusable_answer_is_a_retryable_failure_not_a_decision():
    # It used to come back as a "blocked" decision, which the phone treats as
    # final. An unusable answer is the model's failure, not the task's.
    with serving(error=ProviderRefused("nonsense")) as c:
        r = c.post("/v1/next-step", json=body(), headers=auth())
    assert r.status_code == 502
    assert r.json()["detail"]["category"] == "unusable_answer"


def test_a_provider_outage_is_reported_as_such():
    with serving(error=ProviderUnavailable("upstream")) as c:
        r = c.post("/v1/next-step", json=body(), headers=auth())
    assert r.status_code == 502
    assert r.json()["detail"]["category"] == "upstream"


@pytest.mark.parametrize("category,code,retryable", [
    ("auth", 502, False), ("timeout", 504, True), ("provider_throttled", 503, True),
    ("model_not_found", 502, False), ("quota_exhausted", 503, False), ("provider_limited", 503, True),
    ("network", 502, True),
])
def test_each_provider_failure_says_what_kind_it_was(category, code, retryable):
    with serving(error=ProviderUnavailable(category)) as c:
        r = c.post("/v1/next-step", json=body(), headers=auth())
    assert r.status_code == code
    detail = r.json()["detail"]
    assert detail["category"] == category
    assert detail["source"] == "provider"
    assert detail["retryable"] is retryable
    # The same id in the body and the header, and nothing else identifying.
    assert detail["correlation_id"] == r.headers["X-Dot-Correlation"]
    assert set(detail) == {"category", "source", "retryable", "retry_after_s", "reason", "correlation_id"}


def test_health_names_the_provider_so_a_stub_is_never_mistaken_for_the_model(client):
    assert client.get("/healthz").json()["provider"] == "fake"


def test_no_model_configured_is_a_clear_refusal():
    main.set_provider_for_tests(None)
    with TestClient(main.app) as c:
        r = c.post("/v1/next-step", json=body(), headers=auth())
    assert r.status_code == 503


def test_a_clarification_is_a_valid_decision():
    with serving(Ask(observation_id="obs-1", question="Do you mean the screen brightness?")) as c:
        r = c.post("/v1/next-step", json=body(goal="make it brighter"), headers=auth())
    assert r.json()["decision"]["kind"] == "ask"


def test_the_rate_limiter_stops_a_runaway_client():
    from app import security
    original = security.LIMITER
    security.LIMITER = security.RateLimiter(per_minute=5, burst=5)
    try:
        with serving(Blocked(observation_id="obs-1", explanation="no", suggestion="try")) as c:
            codes = [c.post("/v1/next-step", json=body(), headers=auth()).status_code
                     for _ in range(8)]
    finally:
        # Left in place, every test after this one was rate limited.
        security.LIMITER = original
    assert 429 in codes
    assert codes[0] == 200


def test_the_screen_description_marks_itself_untrusted():
    from app.provider import _describe
    from app.schemas import NextStepRequest

    text = _describe(NextStepRequest.model_validate(body()))
    assert "BEGIN UNTRUSTED SCREEN DESCRIPTION" in text
    assert "END UNTRUSTED SCREEN DESCRIPTION" in text
    # The goal sits outside the untrusted block, so a screen cannot rewrite it.
    assert text.index("GOAL:") < text.index("BEGIN UNTRUSTED")


def test_a_decision_naming_an_unknown_control_does_not_validate():
    from pydantic import TypeAdapter, ValidationError
    from app.schemas import Decision

    # The schema accepts any id; grounding is the phone's job, and it is tested
    # there. What the schema must reject is an action that does not exist.
    with pytest.raises(ValidationError):
        TypeAdapter(Decision).validate_python(
            {"kind": "tap_at", "observation_id": "obs-1", "x": 10, "y": 20}
        )


# ---------------------------------------------------------- parsing and grounding

from app.provider import parse_decision  # noqa: E402
from app.schemas import NextStepRequest  # noqa: E402


def request_with(**over):
    b = body()
    b["observation"]["candidates"].append(
        {"id": "f1", "label": "", "hint": "Search settings", "role": "EditText",
         "bounds": [0, 50, 1170, 100], "editable": True, "clickable": True}
    )
    b["observation"]["scroll_containers"] = ["list"]
    b.update(over)
    return NextStepRequest.model_validate(b)


def answer(**fields):
    import json
    base = {"observation_id": "obs-1", "assessment": "not_applicable"}
    base.update(fields)
    return json.dumps(base)


def test_a_highlight_naming_an_offered_control_parses():
    d = parse_decision(answer(kind="highlight", candidate_id="c1",
                              instruction="Tap Display & touch.", expected_outcome="It opens."),
                       request_with())
    assert d.kind == "highlight" and d.candidate_id == "c1"


def test_an_invented_control_id_is_refused_by_the_server():
    with pytest.raises(ProviderRefused):
        parse_decision(answer(kind="highlight", candidate_id="c999",
                              instruction="Tap it.", expected_outcome="x"), request_with())


def test_an_answer_about_another_observation_is_refused():
    with pytest.raises(ProviderRefused):
        parse_decision(answer(kind="ask", question="Which?", observation_id="obs-old"),
                       request_with())


def test_typing_is_only_into_an_editable_field():
    ok = parse_decision(answer(kind="type_text", candidate_id="f1", text="brightness",
                               instruction="Type brightness.", expected_outcome="Results appear."),
                        request_with())
    assert ok.kind == "type_text" and ok.text == "brightness"
    with pytest.raises(ProviderRefused):
        parse_decision(answer(kind="type_text", candidate_id="c1", text="x",
                              instruction="Type.", expected_outcome="x"), request_with())


def test_submitting_with_a_button_needs_the_button():
    with pytest.raises(ProviderRefused):
        parse_decision(answer(kind="submit", via="control", instruction="Tap Search.",
                              expected_outcome="Results."), request_with())
    d = parse_decision(answer(kind="submit", via="keyboard", instruction="Press search on the keyboard.",
                              expected_outcome="Results."), request_with())
    assert d.via == "keyboard"


def test_scroll_names_content_not_finger_and_forgets_an_unknown_list():
    d = parse_decision(answer(kind="scroll", reveal="below", container_id="nope",
                              looking_for="Brightness", expected_outcome="Brightness appears."),
                       request_with())
    assert d.reveal == "below" and d.container_id is None
    with pytest.raises(ProviderRefused):
        parse_decision(answer(kind="scroll", reveal="down", expected_outcome="x"), request_with())


def test_a_scroll_without_an_expected_outcome_is_still_usable():
    # Seen live: the model leaves this out of scroll answers, and refusing
    # them wasted two paid calls on a sound decision.
    d = parse_decision(answer(kind="scroll", reveal="below", looking_for="Settings"), request_with())
    assert d.kind == "scroll" and d.expected_outcome == ""


def test_a_keyboard_submit_without_words_is_still_usable():
    # Seen live: the model chose the keyboard's search key correctly and
    # left the sentence out; the phone words that step itself.
    d = parse_decision(answer(kind="submit", via="keyboard"), request_with())
    assert d.kind == "submit" and d.instruction == ""


def test_a_scroll_without_a_direction_follows_its_list():
    r = request_with()
    r.observation.candidates.append(
        type(r.observation.candidates[0])(id="pager", role="ViewPager", bounds=[0, 0, 10, 10],
                                          scrollable=True, horizontal=True))
    r.observation.scroll_containers.append("pager")
    sideways = parse_decision(answer(kind="scroll", container_id="pager"), r)
    assert sideways.reveal == "right"
    plain = parse_decision(answer(kind="scroll"), r)
    assert plain.reveal == "below"


def test_navigation_is_home_or_back_only():
    d = parse_decision(answer(kind="navigate", to="home", reason="Settings is opened from Home.",
                              expected_outcome="The Home screen shows."), request_with())
    assert d.to == "home"
    with pytest.raises(ProviderRefused):
        parse_decision(answer(kind="navigate", to="recents", reason="x", expected_outcome="x"),
                       request_with())


def test_fields_from_other_kinds_and_empty_strings_are_ignored():
    d = parse_decision(answer(kind="ask", question="Existing photo or a new one?",
                              options=["Existing photo", "Take a new one"],
                              candidate_id="", text="", reveal="below"), request_with())
    assert d.kind == "ask" and d.options == ["Existing photo", "Take a new one"]


def test_progress_is_carried_on_any_decision():
    d = parse_decision(answer(kind="highlight", candidate_id="c1", instruction="Tap it.",
                              expected_outcome="x", assessment="succeeded",
                              progress_note="Settings is open", subgoal="Find text size"),
                       request_with())
    assert (d.assessment, d.progress_note, d.subgoal) == ("succeeded", "Settings is open", "Find text size")


def test_answers_keep_their_questions_in_the_prompt():
    from app.provider import _describe
    r = request_with(answers=[{"question": "Existing photo or new?", "answer": "Existing"}],
                     failed_approaches=["Tapped Photos on page 1"], reported_problem=True)
    text = _describe(r)
    assert 'YOU ASKED: "Existing photo or new?" THEY ANSWERED: "Existing"' in text
    assert "DID NOT WORK: Tapped Photos on page 1" in text
    assert "DID NOT WORK" in text[: text.index("BEGIN UNTRUSTED")]


def test_a_form_box_says_how_ready_it_is_and_what_the_app_says_about_it():
    from app.provider import SYSTEM_PROMPT, _describe

    b = body()
    b["observation"]["candidates"].append(
        {"id": "b1", "label": "September 29, 2026", "hint": "Birthday", "role": "EditText",
         "bounds": [0, 480, 1080, 620], "editable": True, "clickable": True,
         "readiness": "prefilled", "error": ""}
    )
    b["observation"]["notices"] = ["Birthday (0 years old)"]
    text = _describe(NextStepRequest.model_validate(b))
    assert "readiness=prefilled" in text
    assert 'the app says: "Birthday (0 years old)"' in text
    # Inside the untrusted block: the app's words describe the screen, nothing more.
    assert text.index("BEGIN UNTRUSTED") < text.index("the app says") < text.index("END UNTRUSTED")
    # The planner is told what readiness means, and never to pick a date or a minimum age for anyone.
    assert "An enabled Next button" in SYSTEM_PROMPT
    assert "Never suggest a date, an age or a year" in SYSTEM_PROMPT
    assert "18" not in SYSTEM_PROMPT


def test_an_unknown_readiness_is_refused():
    import pydantic

    b = body()
    b["observation"]["candidates"][0]["readiness"] = "probably fine"
    with pytest.raises(pydantic.ValidationError):
        NextStepRequest.model_validate(b)


def test_an_older_phone_without_readiness_still_validates():
    b = body()
    b["observation"].pop("notices", None)
    for c in b["observation"]["candidates"]:
        c.pop("readiness", None)
        c.pop("error", None)
    assert NextStepRequest.model_validate(b).observation.notices == []


def test_a_lesson_carries_its_practice_and_the_planner_is_told_how_to_use_it():
    from app.provider import SYSTEM_PROMPT, _describe

    b = body()
    b["lesson"] = {
        "course": "Google Maps", "title": "Search for a place",
        "objective": "Practise searching for the practice place.",
        "practice": {
            "intro": "Let's practice finding a place.",
            "example": "Smithsonian National Museum of Natural History, Washington, DC",
            "type_text": "Smithsonian National Museum of Natural History, Washington, DC",
            "checkpoints": ["This search box can find places and addresses."],
            "own_choice": "a public place of their own",
        },
    }
    text = _describe(NextStepRequest.model_validate(b))
    assert "Let's practice finding a place." not in text, "the phone shows the practice card itself"
    assert 'PRACTICE TEXT, TO TYPE EXACTLY: "Smithsonian National Museum of Natural History, Washington, DC"' in text
    assert "TEACH c1: This search box can find places and addresses." in text
    assert "practice_choice" in SYSTEM_PROMPT
    # Authored practice text is allowed; personal words stay the learner's.
    assert "personal words" in SYSTEM_PROMPT
    assert "never the first suggestion because it is first" in SYSTEM_PROMPT


def test_settled_task_details_go_to_the_planner_and_asks_can_take_free_text():
    from pydantic import TypeAdapter
    from app.provider import _describe
    from app.schemas import Decision

    b = body()
    b["task_details"] = {"destination": "12 Oak Lane, Springfield"}
    text = _describe(NextStepRequest.model_validate(b))
    assert "TASK DETAILS SETTLED: destination: 12 Oak Lane, Springfield" in text

    ask = TypeAdapter(Decision).validate_python({
        "kind": "ask", "observation_id": "obs-1", "question": "What's your son's address?",
        "options": ["I'm not sure", "I'll type it in Maps"], "free_text": True, "personal": True,
        "task_details": {"destination": ""},
    })
    assert ask.free_text and ask.personal


def test_an_app_error_is_its_own_kind_of_blocked_and_manual_is_one_action():
    from pydantic import TypeAdapter
    from app.provider import SYSTEM_PROMPT
    from app.schemas import Decision

    blocked = TypeAdapter(Decision).validate_python({
        "kind": "blocked", "observation_id": "obs-1",
        "explanation": "Facebook couldn't finish creating the account.", "suggestion": "Try again later.",
        "app_error": True,
    })
    assert blocked.app_error
    assert "Never use manual to hand over a" in SYSTEM_PROMPT


def test_the_models_answer_can_carry_every_new_field_through_to_the_phone():
    import json
    from app.provider import FLAT_DECISION_SCHEMA, parse_decision

    props = FLAT_DECISION_SCHEMA["properties"]
    for field in ("app_error", "free_text", "personal", "practice_choice", "task_details"):
        assert field in props, field
    request = NextStepRequest.model_validate(body())
    ask = parse_decision(json.dumps({
        "kind": "ask", "observation_id": "obs-1", "assessment": "not_applicable",
        "question": "What's your son's address?", "options": ["I'm not sure"],
        "free_text": True, "personal": True, "task_details": {"contact": "son"},
    }), request)
    assert ask.free_text and ask.personal and ask.task_details == {"contact": "son"}
    blocked = parse_decision(json.dumps({
        "kind": "blocked", "observation_id": "obs-1", "assessment": "failed",
        "explanation": "Example couldn't create the account.", "suggestion": "Try later.", "app_error": True,
    }), request)
    assert blocked.app_error



def _lesson_body(explained=()):
    b = body()
    b["lesson"] = {
        "course": "Google Maps", "title": "Check the details", "objective": "Open the practice place's details.",
        "practice": {"checkpoints": ["This section tells you when the museum opens and closes."]},
        "explained": list(explained),
    }
    b["observation"]["information"] = [{"id": "t9", "text": "Open · Closes 5:30 PM", "bounds": [0, 900, 1080, 980]}]
    return b


def test_a_teaching_point_is_named_by_its_id_and_can_point_at_information():
    import json
    from app.provider import SYSTEM_PROMPT, _describe, parse_decision

    request = NextStepRequest.model_validate(_lesson_body())
    text = _describe(request)
    assert 'id=t9 text="Open · Closes 5:30 PM"' in text
    assert text.index("information (to read") < text.index("END UNTRUSTED")
    explain = parse_decision(json.dumps({
        "kind": "explain", "observation_id": "obs-1", "assessment": "succeeded",
        "checkpoint_id": "c1", "candidate_id": "t9",
    }), request)
    assert explain.checkpoint_id == "c1" and explain.candidate_id == "t9"
    # Asked for as information_id, its own field, which the model fills in.
    named = parse_decision(json.dumps({
        "kind": "explain", "observation_id": "obs-1", "assessment": "succeeded",
        "checkpoint_id": "c1", "information_id": "t9",
    }), request)
    assert named.candidate_id == "t9"
    assert "never before every tap" in SYSTEM_PROMPT
    assert "never describe where it is" in SYSTEM_PROMPT


def test_a_teaching_point_the_lesson_does_not_have_or_invented_information_is_refused():
    import json
    from app.provider import ProviderRefused, parse_decision

    request = NextStepRequest.model_validate(_lesson_body())
    for bad in ({"checkpoint_id": "c7"}, {"checkpoint_id": "c1", "candidate_id": "t404"}):
        with pytest.raises(ProviderRefused):
            parse_decision(json.dumps({"kind": "explain", "observation_id": "obs-1", "assessment": "unclear", **bad}), request)


def test_explained_points_are_listed_so_none_is_shown_twice():
    from app.provider import _describe

    text = _describe(NextStepRequest.model_validate(_lesson_body(explained=["c1"])))
    assert "ALREADY EXPLAINED: c1" in text


def test_a_manual_step_without_a_reason_is_still_a_usable_answer():
    # Seen live: a date picker's "Set the wheels, then tap SET" came back
    # without a reason, was refused twice, and the phone said "reconnecting".
    d = parse_decision(answer(kind="manual", instruction="Turn the wheels to your birthday."), request_with())
    assert d.kind == "manual" and d.reason == ""
