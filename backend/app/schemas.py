"""The contract between the phone and the model.

Both directions are validated. The response is a closed set of decisions, so a
model that invents an action, or names a control that was never on screen, is
rejected here rather than reaching somebody's phone as an instruction.

Every decision says what it means in fields, not only in prose. The phone
builds the words for anything whose wording has to agree with something else
it shows — which way a finger moves, how to get to the Home screen on this
particular phone — and uses the model's sentence only where the model is the
one who knows.
"""

from __future__ import annotations

from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field, StringConstraints

ShortText = Annotated[str, StringConstraints(min_length=1, max_length=200, strip_whitespace=True)]
Note = Annotated[str, StringConstraints(max_length=160, strip_whitespace=True)]
Id = Annotated[str, StringConstraints(min_length=1, max_length=64, strip_whitespace=True)]


# --------------------------------------------------------------- request


class Candidate(BaseModel):
    """One control the phone can currently see, as the model sees it.

    `id` is scoped to a single observation. It is not an identity that survives
    a scroll, and the client re-grounds every decision against a fresh reading
    before anything is drawn.
    """

    id: Id
    label: str = Field(default="", max_length=200)
    description: str = Field(default="", max_length=200)
    role: str = Field(default="", max_length=64)
    bounds: list[int] = Field(default_factory=list, min_length=4, max_length=4)
    clickable: bool = False
    editable: bool = False
    enabled: bool = True
    scrollable: bool = False
    # Scroll containers only: true when the list moves sideways.
    horizontal: bool = False
    # State, so the result of a step can be checked rather than assumed: a
    # switch that is now on, a slider that has moved, a field with text in it.
    checked: bool | None = None
    selected: bool = False
    focused: bool = False
    value: str = Field(default="", max_length=60)
    hint: str = Field(default="", max_length=120)
    # For a form's box: "empty", "prefilled" (something was already in it,
    # often a default, and the person has not confirmed it), "confirmed"
    # (changed while watched, or confirmed by the person) or "error" (the app
    # flags it). Empty when nothing can be said.
    readiness: Literal["", "empty", "prefilled", "confirmed", "error"] = ""
    # The app's own error message on the box, when it sets one.
    error: str = Field(default="", max_length=120)
    parent: Id | None = None


class Information(BaseModel):
    id: Id
    text: str = Field(default="", max_length=120)
    bounds: list[int] = Field(default_factory=list, max_length=4)


class Observation(BaseModel):
    observation_id: Id
    taken_at_ms: int = 0
    package_name: str = Field(default="", max_length=200)
    window_title: str = Field(default="", max_length=200)
    screen_width: int = 0
    screen_height: int = 0
    candidates: list[Candidate] = Field(default_factory=list)
    # In a lesson: the page's information that is not a control (opening
    # hours, an address), for explain only; never tapped.
    information: list[Information] = Field(default_factory=list, max_length=40)
    scroll_containers: list[Id] = Field(default_factory=list)
    truncated: bool = False
    keyboard_visible: bool = False
    # The phone's own judgement that this is the launcher's home screen.
    home_screen: bool = False
    # Anything the model should know about the picture, such as parts of it
    # masked out because they were Dot's own overlay.
    screenshot_note: str = Field(default="", max_length=200)
    # Words the app shows about the form that are not controls: an error, a
    # warning, the age a date works out to.
    notices: list[Annotated[str, StringConstraints(max_length=120)]] = Field(default_factory=list, max_length=6)


class Answer(BaseModel):
    """A question Dot asked, and what the person said. Kept together."""

    question: ShortText
    answer: Annotated[str, StringConstraints(min_length=1, max_length=200, strip_whitespace=True)]


class Device(BaseModel):
    navigation: Literal["gestures", "three_button", "two_button", "unknown"] = "unknown"
    sdk: int = 0
    font_scale: float = 1.0


class TargetApp(BaseModel):
    """The app a task is about, resolved on the phone. Authored, not screen text."""

    name: Annotated[str, StringConstraints(min_length=1, max_length=60, strip_whitespace=True)]
    package_name: Annotated[str, StringConstraints(max_length=200, pattern=r"^[A-Za-z0-9._]*$")] = ""
    publisher: Annotated[str, StringConstraints(max_length=80, strip_whitespace=True)] = ""
    label: Annotated[str, StringConstraints(max_length=60, strip_whitespace=True)] = ""
    # "not_found" is evidence from a lookup, not proof; "unknown" means the
    # phone could not look.
    install_state: Literal["installed", "disabled", "not_found", "unknown"] = "unknown"


Line = Annotated[str, StringConstraints(max_length=160, strip_whitespace=True)]


class PracticeInfo(BaseModel):
    """How a lesson is practised: Dot's own authored, harmless example.

    Never personal: a public place, a harmless phrase, a draft. The learner may
    use their own instead; it is never required.
    """

    intro: Line = ""
    example: Line = ""
    type_text: Line = ""
    checkpoints: list[Line] = Field(default_factory=list, max_length=6)
    own_choice: Line = ""
    stop_at: Line = ""


class LessonInfo(BaseModel):
    """The lesson a session is running: Dot's own authored content."""

    course: Annotated[str, StringConstraints(max_length=80, strip_whitespace=True)] = ""
    title: Annotated[str, StringConstraints(max_length=80, strip_whitespace=True)] = ""
    objective: Annotated[str, StringConstraints(min_length=1, max_length=300, strip_whitespace=True)]
    done_when: Annotated[str, StringConstraints(max_length=300, strip_whitespace=True)] = ""
    rules: list[Annotated[str, StringConstraints(max_length=200, strip_whitespace=True)]] = Field(
        default_factory=list, max_length=8
    )
    needs_account: bool = False
    learner_confirms: bool = False
    practice: PracticeInfo | None = None
    # Teaching points already shown and acknowledged, by id ("c1", "c2"…).
    explained: list[Annotated[str, StringConstraints(max_length=8)]] = Field(default_factory=list, max_length=8)


Detail = Annotated[str, StringConstraints(max_length=120, strip_whitespace=True)]


class TaskDetails(BaseModel):
    destination: Detail = ""
    search_text: Detail = ""
    contact: Detail = ""


class NextStepRequest(BaseModel):
    session_id: Id
    goal: Annotated[str, StringConstraints(min_length=1, max_length=500, strip_whitespace=True)]
    observation: Observation
    answers: list[Answer] = Field(default_factory=list, max_length=8)
    # Verified progress only: things a screen has shown to be done.
    progress: list[Note] = Field(default_factory=list, max_length=10)
    subgoal: Note = ""
    failed_approaches: list[Note] = Field(default_factory=list, max_length=6)
    recent_apps: list[Annotated[str, StringConstraints(max_length=200)]] = Field(
        default_factory=list, max_length=6
    )
    last_instruction: str = Field(default="", max_length=300)
    last_expected_outcome: str = Field(default="", max_length=300)
    last_kind: str = Field(default="", max_length=20)
    # The person pressed "That didn't work" on the last instruction.
    reported_problem: bool = False
    device: Device = Field(default_factory=Device)
    # Base64 JPEG of the settled screen, when the device could take one and the
    # screen was not sensitive. Optional by design: most phones the product
    # targets are older, and the flow must work without it.
    screenshot_jpeg_b64: str | None = Field(default=None, max_length=3_000_000)
    attempt: int = 0
    # Why the phone asked: a short fixed word such as "start", "navigation",
    # "typed", "retry". For measuring cost; never content.
    trigger: Annotated[str, StringConstraints(max_length=30, pattern=r"^[a-z_]*$")] = ""
    # Which app the goal is about, and the lesson being run, when there is one.
    target_app: TargetApp | None = None
    lesson: LessonInfo | None = None
    # What the person has settled about the task: where to, what to search
    # for, who to contact. Personal; used only to plan this request, never logged.
    task_details: TaskDetails = Field(default_factory=lambda: TaskDetails())


# -------------------------------------------------------------- decisions


class _Base(BaseModel):
    observation_id: Id
    # How the last instruction went, judged from this screen. "succeeded" only
    # with something visible to show for it.
    assessment: Literal["succeeded", "failed", "unclear", "not_applicable"] = "not_applicable"
    # A short fact now true, recorded only when the assessment is "succeeded".
    progress_note: Note = ""
    # What is being worked towards right now, in a few words.
    subgoal: Note = ""
    # In a lesson: what the learner chose to practise with instead of the
    # lesson's example, when they chose something of their own.
    practice_choice: Annotated[str, StringConstraints(max_length=120, strip_whitespace=True)] = ""
    # Facts the person has just settled about the task; only what changed.
    task_details: dict[Literal["destination", "search_text", "contact"], Detail] = Field(default_factory=dict)


class Highlight(_Base):
    kind: Literal["highlight"] = "highlight"
    candidate_id: Id
    instruction: ShortText
    expected_outcome: ShortText


class TypeText(_Base):
    """Type into one editable field the phone offered."""

    kind: Literal["type_text"] = "type_text"
    candidate_id: Id
    # Optional, like a submit's: the phone can say "Type Facebook in the
    # search box" itself, and the model leaving these out of an otherwise
    # sound answer was refused twice in a row in a measured run — two paid
    # calls for a missing sentence.
    instruction: Note = ""
    # What to type, when it is not private. Empty means the person decides.
    text: Annotated[str, StringConstraints(max_length=120, strip_whitespace=True)] = ""
    expected_outcome: Note = ""


class Submit(_Base):
    """Send what was typed: the keyboard's own key, or a button on screen."""

    kind: Literal["submit"] = "submit"
    via: Literal["keyboard", "control"]
    candidate_id: Id | None = None
    # Optional: the phone can word "press the search key" or "tap Search"
    # itself, and a live run showed the model leaving both out of a sound
    # answer — which was then refused twice.
    instruction: Note = ""
    expected_outcome: Note = ""


class Scroll(_Base):
    """Bring more of some content into view.

    `reveal` names which part of the content is wanted, never which way a
    finger moves: "below" means the content further down, which the finger
    reaches by moving up. The phone words the gesture itself so the two can
    never disagree. A suggestion, not proof of where anything is.
    """

    kind: Literal["scroll"] = "scroll"
    reveal: Literal["below", "above", "right", "left"]
    container_id: Id | None = None
    looking_for: Annotated[str, StringConstraints(max_length=80, strip_whitespace=True)] = ""
    # Optional here, unlike on a tap: the phone writes the words for a scroll
    # itself, and the model reliably leaves this out. Refusing an otherwise
    # sound answer for a missing sentence nobody reads cost two model calls
    # on the first live run.
    expected_outcome: Note = ""


class Navigate(_Base):
    """Go to the Home screen, or back one screen. Worded by the phone."""

    kind: Literal["navigate"] = "navigate"
    to: Literal["home", "back"]
    reason: Note = ""
    expected_outcome: Note = ""


class Ask(_Base):
    kind: Literal["ask"] = "ask"
    question: ShortText
    options: list[Annotated[str, StringConstraints(min_length=1, max_length=40, strip_whitespace=True)]] = Field(
        default_factory=list, max_length=3
    )
    # Answered in the person's own words; options are quick answers beside the box.
    free_text: bool = False
    # Asks for something personal, such as an address.
    personal: bool = False


class Wait(_Base):
    kind: Literal["wait"] = "wait"
    reason: ShortText
    recheck_after_ms: int = Field(default=2000, ge=500, le=15000)
    max_rechecks: int = Field(default=4, ge=1, le=10)


class Manual(_Base):
    kind: Literal["manual"] = "manual"
    instruction: ShortText
    # Why the phone cannot watch this step. Useful, not essential: a manual
    # step with a clear instruction and no reason is still a usable answer,
    # and refusing it cost two model calls and a "reconnecting" card while a
    # date picker was open.
    reason: str = Field(default="", max_length=200)
    # The control the person must use, when there is one in the list. Not
    # outlined — the phone only keeps its card off it.
    candidate_id: Id | None = None


class Explain(_Base):
    """A lesson's teaching point, now that the screen shows what it is about.

    The phone shows the lesson's own words for `checkpoint_id`; the model
    never writes them. `candidate_id` names the information to look at —
    read, never tapped. `missing` when it is not on this screen, with a short
    `instruction` saying so.
    """

    kind: Literal["explain"] = "explain"
    checkpoint_id: Annotated[str, StringConstraints(min_length=1, max_length=8, strip_whitespace=True)]
    candidate_id: Id | None = None
    missing: bool = False
    instruction: str = Field(default="", max_length=200)


class Complete(_Base):
    kind: Literal["complete"] = "complete"
    summary: ShortText
    evidence: ShortText


class Blocked(_Base):
    kind: Literal["blocked"] = "blocked"
    explanation: ShortText
    suggestion: ShortText
    # In a lesson: what the lesson teaches is not available here.
    unavailable: bool = False
    # The app itself shows an error about what was just tried ("We couldn't
    # create your account"), as opposed to the screen being hard to see.
    app_error: bool = False


Decision = Annotated[
    Union[Highlight, TypeText, Submit, Scroll, Navigate, Ask, Wait, Manual, Explain, Complete, Blocked],
    Field(discriminator="kind"),
]

KINDS = [
    "highlight", "type_text", "submit", "scroll", "navigate",
    "ask", "wait", "manual", "explain", "complete", "blocked",
]


class NextStepResponse(BaseModel):
    decision: Decision
    model: str
    provider: str
    latency_ms: int
