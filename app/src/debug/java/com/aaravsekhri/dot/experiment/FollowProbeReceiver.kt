package com.aaravsekhri.dot.experiment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aaravsekhri.dot.guide.catalog.GuideCatalog
import com.aaravsekhri.dot.guide.service.DotAccessibilityService

/**
 * The harness's way in. Debug builds only; not declared in a release package.
 *
 * `adb shell am broadcast -a com.aaravsekhri.dot.FOLLOW_START --es label "..."`
 */
class FollowProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL)
                if (label.isNullOrBlank()) {
                    Log.d(BenchmarkRecorder.RESULT_TAG, "start ignored: no label")
                    return
                }
                recorder.reset()
                val paceMs = intent.getIntExtra(EXTRA_PACE_MS, 0).toLong()
                val started = startBenchmark(label, paceMs)
                Log.d(BenchmarkRecorder.RESULT_TAG, "start accepted=$started paceMs=$paceMs")
            }

            ACTION_STOP -> {
                tracker?.shutdown()
                tracker = null
                Log.d(BenchmarkRecorder.RESULT_TAG, "stopped")
            }

            ACTION_DUMP ->
                recorder.dump(intent.getStringExtra(EXTRA_GESTURE) ?: "unnamed")

            ACTION_GUIDE_FIXTURE -> {
                GuideCatalog.registerForDebug(FixtureGuide.definition)
                val started = DotAccessibilityService.debugStartGuideHere(FixtureGuide.ID)
                Log.d(BenchmarkRecorder.RESULT_TAG, "fixture guide started=$started")
            }

            ACTION_GUIDE_REAL -> {
                val id = intent.getStringExtra(EXTRA_LABEL) ?: "text_size"
                com.aaravsekhri.dot.guide.service.GuideCoordinator.requestGuide(id)
                val started = DotAccessibilityService.startRequestedGuide()
                Log.d(BenchmarkRecorder.RESULT_TAG, "real guide started=$started")
            }

            ACTION_DRAW_RESET -> DotAccessibilityService.debugDrawTrace(dump = false)

            ACTION_DRAW_DUMP -> DotAccessibilityService.debugDrawTrace(dump = true)

            ACTION_GESTURE_CARD ->
                DotAccessibilityService.debugShowGestureCard(intent.getStringExtra(EXTRA_LABEL).orEmpty())

            ACTION_TUNING -> {
                // Switches the cost measures for a comparison run. Debug builds only.
                com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks =
                    intent.getBooleanExtra("localWait", com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks)
                com.aaravsekhri.dot.guide.ai.PlanningTuning.selectiveScreenshots =
                    intent.getBooleanExtra("selective", com.aaravsekhri.dot.guide.ai.PlanningTuning.selectiveScreenshots)
                Log.d(
                    BenchmarkRecorder.RESULT_TAG,
                    "tuning localWait=${com.aaravsekhri.dot.guide.ai.PlanningTuning.localWaitChecks} " +
                        "selective=${com.aaravsekhri.dot.guide.ai.PlanningTuning.selectiveScreenshots}",
                )
            }

            ACTION_START_GOAL -> {
                // A general session in these words, as though typed in Ask.
                val goal = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                if (goal.isNotBlank()) {
                    com.aaravsekhri.dot.guide.service.GuideCoordinator.requestGeneralHelp(goal)
                    Log.d(BenchmarkRecorder.RESULT_TAG, "goal started=${DotAccessibilityService.startRequestedGuide()}")
                }
            }

            ACTION_MANUAL -> DotAccessibilityService.debugManual(
                intent.getStringExtra("words").orEmpty(),
                intent.getStringExtra(EXTRA_LABEL).orEmpty(),
            )

            ACTION_POINT_AT ->
                DotAccessibilityService.debugPointAt(intent.getStringExtra(EXTRA_LABEL).orEmpty())

            ACTION_OBSERVE_PROBE -> {
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                Log.d(BenchmarkRecorder.RESULT_TAG, DotAccessibilityService.debugProbeCandidate(label))
            }

            ACTION_END_AI -> DotAccessibilityService.endGeneralSession()

            ACTION_WINDOWS -> Log.d(BenchmarkRecorder.RESULT_TAG, "windows: " + (DotAccessibilityService.debugWindows() ?: "no service"))

            ACTION_START_LESSON -> {
                // A catalogue lesson, through the same path as Learn's button.
                val course = intent.getStringExtra("course").orEmpty()
                val lesson = intent.getStringExtra("lesson").orEmpty()
                com.aaravsekhri.dot.guide.service.GuideCoordinator.requestLesson(course, lesson)
                Log.d(BenchmarkRecorder.RESULT_TAG, "lesson started=${DotAccessibilityService.startRequestedGuide()}")
            }

            ACTION_FIXTURE_LESSON -> {
                val (course, lesson) = FixtureCourse.lesson(intent.getStringExtra("lesson").orEmpty())
                DotAccessibilityService.debugRunLesson(course, lesson)
            }

            ACTION_CARD -> DotAccessibilityService.debugCommand(intent.getStringExtra("command").orEmpty())

            ACTION_ASK -> DotAccessibilityService.debugAsk(
                intent.getStringExtra("question").orEmpty(),
                intent.getStringExtra("options").orEmpty().split('|').filter(String::isNotBlank),
            )

            ACTION_GUIDE_STOP -> {
                val stopped = DotAccessibilityService.debugStopGuide()
                Log.d(BenchmarkRecorder.RESULT_TAG, "guide stopped=$stopped")
            }
        }
    }

    private companion object {
        const val ACTION_START = "com.aaravsekhri.dot.FOLLOW_START"
        const val ACTION_STOP = "com.aaravsekhri.dot.FOLLOW_STOP"
        const val ACTION_DUMP = "com.aaravsekhri.dot.FOLLOW_DUMP"
        const val EXTRA_LABEL = "label"
        const val EXTRA_GESTURE = "gesture"
        const val ACTION_GUIDE_FIXTURE = "com.aaravsekhri.dot.GUIDE_FIXTURE"
        const val ACTION_GUIDE_STOP = "com.aaravsekhri.dot.GUIDE_STOP"
        const val ACTION_GUIDE_REAL = "com.aaravsekhri.dot.GUIDE_REAL"
        const val ACTION_DRAW_RESET = "com.aaravsekhri.dot.DRAW_RESET"
        const val ACTION_DRAW_DUMP = "com.aaravsekhri.dot.DRAW_DUMP"
        const val ACTION_OBSERVE_PROBE = "com.aaravsekhri.dot.OBSERVE_PROBE"
        const val ACTION_GESTURE_CARD = "com.aaravsekhri.dot.GESTURE_CARD"
        const val ACTION_POINT_AT = "com.aaravsekhri.dot.POINT_AT"
        const val ACTION_START_GOAL = "com.aaravsekhri.dot.START_GOAL"
        const val ACTION_TUNING = "com.aaravsekhri.dot.TUNING"
        const val ACTION_MANUAL = "com.aaravsekhri.dot.MANUAL"
        const val ACTION_END_AI = "com.aaravsekhri.dot.END_AI"
        const val ACTION_WINDOWS = "com.aaravsekhri.dot.WINDOWS"
        const val ACTION_START_LESSON = "com.aaravsekhri.dot.START_LESSON"
        const val ACTION_FIXTURE_LESSON = "com.aaravsekhri.dot.FIXTURE_LESSON"
        const val ACTION_CARD = "com.aaravsekhri.dot.CARD"
        const val ACTION_ASK = "com.aaravsekhri.dot.ASK"
        const val EXTRA_PACE_MS = "paceMs"

        /** One recorder across broadcasts, so a run can be dumped after it ends. */
        val recorder = BenchmarkRecorder()

        /** The Phase A follower. Debug builds only; never part of a guide. */
        private var tracker: TargetRefreshTracker? = null

        /**
         * Starts a Phase A measurement run.
         *
         * The looping follower lives here now rather than in the service. It
         * measured what it was built to measure — a held control will report a
         * fresh position about sixty times a second — and that measurement is
         * worth keeping. What it cannot do is produce an outline worth showing,
         * so no guide runs it.
         */
        private fun startBenchmark(label: String, paceMs: Long): Boolean {
            tracker?.shutdown()
            tracker = null
            val located = DotAccessibilityService.debugLocateRow(label) ?: return false
            val (row, labelNode, bounds) = located
            if (bounds.isEmpty || bounds.right < bounds.left || bounds.bottom < bounds.top) {
                Log.d(BenchmarkRecorder.RESULT_TAG, "refusing: the first reading is clipped")
                return false
            }
            val started = TargetRefreshTracker(recorder, paceMs * 1_000_000L)
            tracker = started
            started.acquire(
                root = row,
                label = if (row === labelNode) null else labelNode,
                expectedLabel = label,
                canonicalBounds = bounds,
            )
            return true
        }
    }
}
