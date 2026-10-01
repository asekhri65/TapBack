package com.aaravsekhri.dot.experiment

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aaravsekhri.dot.R

/**
 * A scrolling list with one known row, for measuring how well the target can
 * be followed.
 *
 * Debug builds only — it is declared in the debug manifest and does not exist
 * in a release package.
 *
 * Two things make it a fixture rather than just a screen:
 *
 * It publishes ground truth. The target row's exact position on screen is
 * written to logcat before every frame it takes part in, stamped with the same
 * monotonic clock the tracker uses. The harness reads that; the tracker cannot,
 * because the tracker only ever looks at the accessibility tree, and nothing in
 * the tree carries it. Measuring a tracker against a number it could have read
 * is not measuring anything.
 *
 * It runs in its own process, so that the refresh calls the tracker makes are
 * real cross-process round trips. In the app's own process the framework can
 * short-circuit them, and a benchmark that quietly measures a direct method
 * call would say the tracker is fast when on any real screen it is not.
 *
 * The list sits between a bar above and a bar below, so the target can be
 * scrolled partly out of its container while still being well inside the
 * display — which is the case that made outlines shrink, and cannot be
 * reproduced on a list that fills the screen.
 */
class FixtureScrollActivity : Activity() {

    private lateinit var target: View
    private lateinit var container: View
    private val location = IntArray(2)
    private var lastReported: String? = null
    private var lastContainer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            bar("Fixture", dp(72)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)),
        )

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (index in 0 until ROW_COUNT) {
            val isTarget = index == TARGET_INDEX
            val row = TextView(this).apply {
                text = if (isTarget) TARGET_LABEL else "Row $index"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(24), dp(20), dp(24), dp(20))
                height = dp(72)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setTextColor(Color.BLACK)
                if (isTarget) {
                    id = R.id.dot_fixture_target
                    setBackgroundColor(Color.parseColor("#FFF3E0"))
                }
            }
            if (isTarget) target = row
            list.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)),
            )
        }

        val scroller = ScrollView(this).apply {
            id = R.id.dot_fixture_list
            isFillViewport = false
            addView(list)
        }
        container = scroller
        root.addView(
            FrameLayout(this).apply { addView(scroller) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(
            bar("", dp(96)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)),
        )

        setContentView(root)
        publishGroundTruth(root)
    }

    private fun bar(label: String, height: Int): View = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#37474F"))
        minimumHeight = height
    }

    /**
     * Writes the target's real position before every frame.
     *
     * A pre-draw listener rather than a scroll listener, because the question
     * the benchmark asks is "where was it on the frame the person saw", and
     * only the frames answer that. Identical consecutive positions are skipped
     * so a still screen does not fill the log.
     */
    private fun publishGroundTruth(root: View) {
        root.viewTreeObserver.addOnPreDrawListener {
            target.getLocationOnScreen(location)
            val line = "${location[0]} ${location[1]} " +
                "${location[0] + target.width} ${location[1] + target.height}"
            if (line != lastReported) {
                lastReported = line
                Log.d(GROUND_TRUTH_TAG, "${System.nanoTime()} $line")

                // The list's own visible rectangle, written beside every
                // position rather than only when it changes. What clips a row
                // is this, not the display — the bars above and below it are
                // why a row can be half cut off while sitting well inside the
                // screen — and a harness that starts reading mid-run needs it
                // present in the run, not in whatever came before.
                container.getLocationOnScreen(location)
                val box = "${location[0]} ${location[1]} " +
                    "${location[0] + container.width} ${location[1] + container.height}"
                if (box != lastContainer) {
                    lastContainer = box
                }
                Log.d(CONTAINER_TAG, "${System.nanoTime()} $box")
            }
            true
        }
    }

    companion object {
        /** The harness reads this tag. Nothing in the app does. */
        const val GROUND_TRUTH_TAG = "DotFixtureGT"
        const val CONTAINER_TAG = "DotFixtureBox"

        const val TARGET_LABEL = "Fixture target row"
        private const val ROW_COUNT = 80
        private const val TARGET_INDEX = 24
    }
}
