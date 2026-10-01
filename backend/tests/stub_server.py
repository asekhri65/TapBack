"""Runs the real service with a stand-in for the model.

Everything except the model is genuine: the same app, the same auth, the same
limits, the same schemas, the same HTTP. What it replaces is the one part that
costs money and needs a key.

This exists so the phone-side loop — observe, ask, ground, draw, watch — can be
exercised deterministically, and so that a failure can be attributed to the
loop rather than to the model. It is not a test of the model and nothing run
against it should be described as one. `/healthz` reports `"provider": "stub"`
while it is running, and every response carries the same.

    cd backend
    set -a && . ./.env && set +a
    STUB_DELAY_MS=0 ./.venv/bin/python -m tests.stub_server

STUB_DELAY_MS holds every answer back by that long, which is how a stale
answer — one arriving after the screen has moved on — is produced on purpose.

STUB_FLOWS=1 adds a few generic rules for the debug fixture's flows, so the
phone's own handling of them can be exercised: a goal "search for X" types X
into the field and then sends it; Install is tapped, then waited on, then
Open is tapped; the app's own screen after that is a finish.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re

import uvicorn

from app import main
from app.schemas import (
    Ask, Blocked, Complete, Highlight, Manual, Navigate, NextStepRequest, Scroll, Submit, TypeText, Wait,
)

STOP_WORDS = {
    "the", "a", "an", "my", "me", "i", "to", "on", "in", "for", "of", "and",
    "help", "please", "how", "do", "can", "you", "it", "is", "make", "want",
    "with", "get", "go", "find", "set", "up", "change", "open", "turn", "phone",
}

LAUNCHERS = ("launcher", "nexuslauncher", "trebuchet", "home")


def keywords(goal: str) -> list[str]:
    words = re.findall(r"[a-z]+", goal.lower())
    return [w for w in words if w not in STOP_WORDS and len(w) > 2]


def naming_of(candidate) -> str:
    """What a person would call this control: its words, or its description."""
    return (candidate.label or candidate.description or "").strip()


class StubProvider:
    """Picks the candidate whose words best overlap the goal.

    Crude on purpose. It is not pretending to be a model; it is a fixture that
    behaves plausibly enough to drive the phone through the branches of its
    own logic — a question, a grounded highlight, a scroll suggestion, a wait,
    going Home, a completion.
    """

    name = "stub"
    calls = 0

    async def decide(self, request: NextStepRequest):
        # One line per request, so an acceptance run can prove when the phone
        # is and is not uploading. Counts and a goal length only — never the
        # goal itself, and never the screen.
        StubProvider.calls += 1
        logging.getLogger("dot").warning(
            "stub decide #%d: %d candidates, goal %d chars, screenshot=%s",
            StubProvider.calls,
            len(request.observation.candidates),
            len(request.goal),
            request.screenshot_jpeg_b64 is not None,
        )
        # STUB_FAIL_FIRST=<category>:<count>[:<retry seconds>] fails the first
        # <count> requests as that kind of provider failure, so the phone's
        # recovery can be watched end to end. Simulated, and labelled so in
        # the log.
        spec = os.environ.get("STUB_FAIL_FIRST", "")
        if spec:
            category, count, *rest = spec.split(":")
            if StubProvider.calls <= int(count):
                from app.failures import Failure
                from app.provider import ProviderUnavailable
                retry = float(rest[0]) if rest else None
                logging.getLogger("dot").warning("stub: simulated %s failure #%d", category, StubProvider.calls)
                raise ProviderUnavailable(Failure(category, "provider", retryable=category != "quota_exhausted",
                                                  retry_after_s=retry))
        delay = int(os.environ.get("STUB_DELAY_MS", "0"))
        if delay:
            await asyncio.sleep(delay / 1000)

        o = request.observation
        wanted = keywords(request.goal)
        common = {"observation_id": o.observation_id}

        if not wanted and not request.answers:
            return Ask(**common, question="What would you like to change?",
                       options=["Text size", "Brightness"])

        if os.environ.get("STUB_FLOWS") == "1":
            flow = _flow(request, common)
            if flow is not None:
                return flow

        if "wait" in wanted:
            return Wait(**common, reason="Waiting for the page to finish loading.",
                        recheck_after_ms=1500, max_rechecks=2)

        best, best_score = None, 0
        for candidate in o.candidates:
            text = f"{candidate.label} {candidate.description}".lower()
            score = sum(1 for word in wanted if word in text)
            if score > best_score:
                best, best_score = candidate, score

        if best and best_score >= 2 and request.progress:
            return Complete(**common, summary="That looks like the right screen.",
                            evidence=f"'{naming_of(best)}' is on screen.")

        if best:
            return Highlight(**common, candidate_id=best.id,
                             instruction=f"Tap {naming_of(best)}.",
                             expected_outcome=f"The {naming_of(best)} screen opens.",
                             assessment="unclear")

        if not any(l in o.package_name for l in LAUNCHERS) and not o.home_screen and not o.scroll_containers:
            return Navigate(**common, to="home", reason="Apps are opened from the Home screen.",
                            expected_outcome="The Home screen shows.")

        if o.scroll_containers:
            return Scroll(**common, reveal="below", container_id=o.scroll_containers[0],
                          looking_for=wanted[0] if wanted else "", expected_outcome="More comes into view.")

        return Blocked(**common, explanation="I cannot see anything to do with that here.",
                       suggestion="Open Settings and ask again.")


def _flow(request: NextStepRequest, common: dict):
    """The fixture flows, decided from the screen as any planner would."""
    o = request.observation
    if not o.candidates and o.screenshot_note.startswith("A private sign-in screen"):
        if "install" in request.goal.lower():
            return Complete(**common, summary="Example Social is installed and open.",
                            evidence="It opened at its own sign-in screen.", assessment="succeeded")
        return Manual(**common, instruction="Sign in to continue.", reason="Signing in is needed.")
    by_name = {naming_of(c).lower(): c for c in o.candidates}
    match = re.search(r"search for ([\w ]+)", request.goal, re.IGNORECASE)
    field = next((c for c in o.candidates if c.editable), None)
    if match and field is not None:
        words = match.group(1).strip()
        if words.lower() in (field.label or "").lower():
            return Submit(**common, via="keyboard", assessment="succeeded",
                          progress_note=f"'{words}' is typed.")
        return TypeText(**common, candidate_id=field.id, text=words,
                        instruction=f"Type {words} in the search box.", expected_outcome="The words are in the box.")
    if "open" in by_name:
        return Highlight(**common, candidate_id=by_name["open"].id, instruction="Tap Open.",
                         expected_outcome="The app opens.", assessment="succeeded",
                         progress_note="The install finished.")
    if "install" in by_name:
        return Highlight(**common, candidate_id=by_name["install"].id, instruction="Tap Install.",
                         expected_outcome="It starts installing.")
    if request.last_instruction.startswith("Tap Install"):
        return Wait(**common, reason="Example Social is installing.", recheck_after_ms=2000, max_rechecks=8)
    if "log in" in by_name and request.last_instruction.startswith("Tap Open"):
        return Complete(**common, summary="Example Social is installed and open.",
                        evidence="Its own sign-in screen is showing.", assessment="succeeded")
    return None


if __name__ == "__main__":
    main.set_provider_for_tests(StubProvider())
    print("STUB provider: the model is NOT being called.")
    uvicorn.run(main.app, host="127.0.0.1", port=8000, log_level="warning")
