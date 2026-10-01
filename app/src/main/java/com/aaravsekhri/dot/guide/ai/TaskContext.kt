package com.aaravsekhri.dot.guide.ai

/**
 * What a session knows about the task beyond the person's own words.
 *
 * Carried alongside the goal, never in place of it: the goal stays exactly
 * what was asked for or what the lesson is for, and this adds which app is
 * meant and, in a lesson, what the lesson is teaching and how it is known to
 * be done.
 */
data class TaskContext(
    val app: TargetApp? = null,
    val lesson: LessonContext? = null,
    /** The admitted task this session is, on Dot's service: its credit, and the requests counted against it. */
    val taskId: String = "",
) {
    val isEmpty: Boolean get() = app == null && lesson == null
}

/**
 * The app a task is about, resolved on the phone before anything is asked.
 *
 * [installState] comes from Android's package manager for the few apps Dot
 * declares it may look up. [InstallState.Unknown] is the honest answer for any
 * other app: Android 11 and later hide installed apps from Dot unless it
 * declares them, so not being able to see one is not evidence it is missing.
 */
data class TargetApp(
    val name: String,
    val packageName: String,
    val publisher: String,
    val installState: InstallState,
    /** The name the phone shows under its icon, when it could be read. */
    val label: String = name,
)

enum class InstallState {
    /** Installed and enabled. */
    Installed,

    /** Installed but turned off; the Play Store offers Enable rather than Install. */
    Disabled,

    /** Declared, looked up, and not there. */
    NotInstalled,

    /** Not something Dot can look up; the screen has to tell. */
    Unknown;

    /**
     * The word the planner is sent. "not_found" rather than "not installed":
     * even for a declared app, a lookup is evidence to be checked against the
     * screen, not proof.
     */
    val wire: String get() = when (this) {
        Installed -> "installed"
        Disabled -> "disabled"
        NotInstalled -> "not_found"
        Unknown -> "unknown"
    }
}

/**
 * The lesson a session is running, as the planner needs it.
 *
 * @property objective what the learner will do, in one sentence — the thing
 *   that is guided.
 * @property doneWhen what on screen shows the lesson is done. Completion is
 *   only ever judged from that, or from the learner saying so when
 *   [learnerConfirms] (for an outcome the screen cannot show).
 * @property rules the lesson's own limits: what never to do for the
 *   learner, what they must choose themselves.
 */
data class LessonContext(
    val courseId: String,
    val courseTitle: String,
    val lessonId: String,
    val lessonTitle: String,
    val objective: String,
    val doneWhen: String,
    val rules: List<String> = emptyList(),
    val needsAccount: Boolean = false,
    val learnerConfirms: Boolean = false,
    val contentVersion: Int = 1,
    /** How it is practised; null when the lesson needs nothing made up. See [com.aaravsekhri.dot.courses.Practice]. */
    val practice: PracticeContext? = null,
    /** The lesson's purpose, for its first card. Blank: no introduction card. */
    val purpose: String = "",
    /** What the result means, for the card before the celebration. Blank: none. */
    val result: String = "",
) {
    /** The teaching points, by their stable ids within this lesson version: "c1", "c2"… */
    val checkpoints: List<Pair<String, String>>
        get() = practice?.checkpoints.orEmpty().mapIndexed { i, words -> "c${i + 1}" to words }
}

/**
 * A lesson's practice, as the planner is sent it: with the example in use
 * already chosen — the course's own, or the learner's if they picked one.
 */
data class PracticeContext(
    val intro: String,
    val example: String = "",
    val exampleKey: String = "",
    val typeText: String = "",
    val checkpoints: List<String> = emptyList(),
    val ownChoice: String = "",
    val stopAt: String = "",
)

/**
 * Why a task stopped moving, as far as the screen showed.
 *
 * Only what was seen: an app that was tapped and never opened, or what the
 * screen itself said. Anything else is [Unknown] — never a guess at a cause
 * that would blame the network, the app, or Dot without evidence.
 */
sealed interface StallCause {
    data object Unknown : StallCause

    /** The app's icon was tapped and the app did not open. */
    data class AppNotOpening(val appName: String) : StallCause

    /** What the screen showed, in the planner's words describing it. */
    data class Observed(val description: String) : StallCause

    /**
     * The app itself says what was tried did not work — an error it shows,
     * not a screen Dot could not read. [description] is what it says, briefly.
     */
    data class AppError(val description: String) : StallCause
}
