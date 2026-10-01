package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.matching.TargetIdentity
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.CueDirection
import com.aaravsekhri.dot.guide.motion.OutlineVisibilityPolicy
import com.aaravsekhri.dot.guide.overlay.HighlightMetrics
import com.aaravsekhri.dot.guide.overlay.PebbleAnchor
import com.aaravsekhri.dot.guide.tracking.RenderedTargetTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The promises enhanced tracking makes, checked rather than asserted in prose.
 *
 * Several of these are read off the source rather than exercised, because the
 * guarantee is an absence — no gesture, no captured pixel leaving the process —
 * and an absence is proved by looking everywhere, not by running one path.
 */
class EnhancedTrackingContractTest {

    private val policy = OutlineVisibilityPolicy()
    private val viewport = Bounds(0, 100, 1170, 2400)

    private val identity = TargetIdentity(
        windowId = 1,
        viewId = "row",
        normalizedText = "display and touch",
        normalizedDescription = "",
        className = "android.widget.LinearLayout",
    )

    // ------------------------------------------------- outline and Pebble

    @Test
    fun `the outline and Pebble receive exactly the same transform`() {
        val region = Bounds(60, 1000, 1110, 1190)
        val moved = Bounds(region.left, region.top - 220, region.right, region.bottom - 220)

        val first = PebbleAnchor.choose(
            region = region,
            viewport = viewport,
            avoid = emptyList(),
            size = 96,
            straddle = 24,
        )
        val second = PebbleAnchor.choose(
            region = moved,
            viewport = viewport,
            avoid = emptyList(),
            size = 96,
            straddle = 24,
        )

        assertEquals(first.bounds.left, second.bounds.left)
        assertEquals(first.bounds.top - 220, second.bounds.top)
    }

    // ------------------------------------------------------- visibility

    @Test
    fun `a target mostly on screen keeps its outline`() {
        val region = Bounds(60, 2200, 1110, 2390)
        assertTrue(policy.isUsefullyVisible(region, viewport, currentlyHidden = false))
    }

    @Test
    fun `a target mostly gone gives up its outline`() {
        // Thirty of its 190 pixels are left inside the viewport.
        val region = Bounds(60, 2370, 1110, 2560)
        assertFalse(policy.isUsefullyVisible(region, viewport, currentlyHidden = false))
    }

    @Test
    fun `a target must come back further than it went before the outline returns`() {
        // A third visible: enough to have kept the outline, not enough to bring
        // it back. Without this the two states alternate every frame.
        val region = Bounds(60, 2330, 1110, 2520)
        val fraction = policy.visibleFraction(region, viewport)
        assertTrue("fraction=$fraction", fraction > OutlineVisibilityPolicy.DEFAULT_HIDE_FRACTION)
        assertTrue("fraction=$fraction", fraction < OutlineVisibilityPolicy.DEFAULT_SHOW_FRACTION)

        assertTrue(policy.isUsefullyVisible(region, viewport, currentlyHidden = false))
        assertFalse(policy.isUsefullyVisible(region, viewport, currentlyHidden = true))
    }

    @Test
    fun `the cue points the way the target went`() {
        val above = Bounds(60, 120, 1110, 300)
        val below = Bounds(60, 2300, 1110, 2480)

        assertEquals(CueDirection.Up, policy.cueDirectionFor(above, viewport))
        assertEquals(CueDirection.Down, policy.cueDirectionFor(below, viewport))
    }

    // ------------------------------------------------------------- lost

    @Test
    fun `an outline that cannot be tracked comes down within a tenth of a second`() {
        val unusableSince = 1_000_000_000L
        val hideNanos = OutlineVisibilityPolicy.DEFAULT_LOST_HIDE_NANOS

        assertTrue("budget is 100 ms", hideNanos <= 100_000_000L)
        assertFalse(
            policy.shouldHideForLostTracking(unusableSince, unusableSince + 10_000_000L, true),
        )
        assertTrue(
            policy.shouldHideForLostTracking(unusableSince, unusableSince + hideNanos, true),
        )
    }

    @Test
    fun `a still screen with nothing to report is not a failure`() {
        val unusableSince = 1_000_000_000L
        assertFalse(
            policy.shouldHideForLostTracking(
                unusableSince,
                unusableSince + 5_000_000_000L,
                motionExpected = false,
            ),
        )
    }

    // ------------------------------------------ same screen versus navigation

    @Test
    fun `a layout rebuild keeps the target and does not move the outline`() {
        val tracker = RenderedTargetTracker()
        tracker.adopt(identity, Bounds(60, 1000, 1110, 1190))

        // A font-size change replaces every node, and the window with them.
        val rebuilt = identity.copy(windowId = 2)
        tracker.rebind(rebuilt, Bounds(60, 1000, 1110, 1230))

        val geometry = tracker.geometry!!
        assertEquals(rebuilt, geometry.identity)
        assertEquals(1000, geometry.renderedRegion.top)
        // Same control, so nothing here should look like arriving somewhere new.
        assertTrue(geometry.identity.isSameControlAs(identity))
    }

    @Test
    fun `navigation forgets the target outright`() {
        val tracker = RenderedTargetTracker()
        tracker.adopt(identity, Bounds(60, 1000, 1110, 1190))
        tracker.forget()

        assertEquals(null, tracker.geometry)
    }

    // ------------------------------------------------------------ privacy

    @Test
    fun `a captured frame is never stored, logged, or sent without consent`() {
        // This test used to say a frame never leaves the process at all, and
        // for the fixed tutorials that is still true — they take no picture.
        // A general session does, with its own separate consent, so the
        // invariant is now narrower and worth stating precisely: a frame may
        // go to the planning service the person agreed to, and nowhere else.
        val offenders = mutableListOf<String>()
        forEachSource { file, text ->
            val neverAllowed = listOf(
                "FileOutputStream", "openFileOutput", "MediaStore",
                "logEvent", "recordException",
            )
            neverAllowed.filter { text.contains(it) }.forEach { offenders += "${file.name}: $it" }

            // Exactly one file may open a socket, so there is one place to
            // audit and no second path a frame could take.
            if (text.contains("HttpURLConnection") && file.name != "AiClient.kt") {
                offenders += "${file.name}: opens its own connection"
            }
        }
        assertTrue("a frame must not be stored or logged: $offenders", offenders.isEmpty())

        val host = File(SOURCE_ROOT, "guide/ai/AiGuideHost.kt").readText()
        assertTrue(
            "nothing may be gathered before the person has agreed to it",
            withoutComments(host).contains("if (!uploadsAllowed)"),
        )
        assertTrue(
            "and a screen judged private is never described at all",
            withoutComments(host).indexOf("SensitiveScreenPolicy.judge") <
                withoutComments(host).indexOf("ObservationBuilder.build"),
        )

        val client = withoutComments(File(SOURCE_ROOT, "guide/ai/AiClient.kt").readText())
        assertFalse(
            "a frame is never held once its request is built",
            client.contains("var lastScreenshot") || client.contains("cachedScreenshot"),
        )
    }

    @Test
    fun `nothing derived from a frame's pixels reaches a log line`() {
        val offenders = mutableListOf<String>()
        forEachSource { file, text ->
            Regex("""GuideLog\.d\([^)]*\)\s*\{([^}]*)}""").findAll(text).forEach { match ->
                val body = match.groupValues[1]
                // Frames and their buffers are the things that must never be
                // interpolated into a message; measurements of them are fine.
                listOf("luminance", ".at(", "pixels").forEach { token ->
                    if (body.contains(token)) offenders += "${file.name}: $token"
                }
            }
        }
        assertTrue("logs must carry numbers only: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the guide still never taps, swipes, or gestures for the person`() {
        val offenders = mutableListOf<String>()
        forEachSource { file, text ->
            listOf(
                "dispatchGesture",
                "ACTION_CLICK",
                "ACTION_SCROLL_FORWARD",
                "ACTION_SCROLL_BACKWARD",
                "ACTION_SET_PROGRESS",
                "canPerformGestures",
            ).forEach { token ->
                // Comments are stripped first: the guarantee is stated in the
                // service's own documentation, and a test that tripped over the
                // promise rather than the code would be unkeepable.
                if (withoutComments(text).contains(token)) offenders += "${file.name}: $token"
            }
        }
        assertTrue("the person performs every action: $offenders", offenders.isEmpty())
    }

    @Test
    fun `screen capture cannot start in the production guide`() {
        // The tracker's source is kept as research. What must be true is that
        // nothing reachable from a guide can start it, and that the app no
        // longer asks for the permissions that would let it.
        val service = withoutComments(
            File(SOURCE_ROOT, "guide/service/DotAccessibilityService.kt").readText(),
        )
        listOf(
            "VisualTrackingSession",
            "DotProjectionService",
            "ProjectionConsentActivity",
            "MediaProjection",
            "startVisualTracking",
        ).forEach { token ->
            assertFalse(
                "the accessibility service must not reference $token",
                service.contains(token),
            )
        }

        val launcher = withoutComments(
            File(SOURCE_ROOT, "ui/guide/GuideLaunchController.kt").readText(),
        )
        assertFalse(launcher.contains("ProjectionConsentActivity"))
        assertFalse(launcher.contains("VisualTrackingCoordinator"))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declarations = withoutXmlComments(manifest)
        assertFalse(
            "no capture permission may ship",
            declarations.contains("FOREGROUND_SERVICE_MEDIA_PROJECTION"),
        )
        assertFalse(
            "the capture service must not be declared",
            declarations.contains("DotProjectionService"),
        )
        assertFalse(
            "nor the consent activity",
            declarations.contains("ProjectionConsentActivity"),
        )
    }

    @Test
    fun `the tracking experiment is not part of a release build`() {
        // The benchmark fixture and its remote control exist to measure the
        // tracker, and a shipped app has no business carrying either: one is a
        // screen full of dummy rows, the other an exported receiver that starts
        // things. They are declared in the debug manifest only, so the system
        // cannot start them in a release package even if something tried.
        val manifest = withoutXmlComments(File("src/main/AndroidManifest.xml").readText())
        listOf("FixtureScrollActivity", "FollowProbeReceiver").forEach {
            assertFalse(
                "the experiment must not be declared in the shipped manifest: $it",
                manifest.contains(it),
            )
        }

        // And nothing in the shipped code may reach into it.
        File(SOURCE_ROOT).walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                assertFalse(
                    "${file.name} reaches into the debug-only experiment",
                    withoutComments(file.readText()).contains("com.aaravsekhri.dot.experiment"),
                )
            }
    }

    @Test
    fun `following a scroll never means estimating one`() {
        val service = withoutComments(
            File(SOURCE_ROOT, "guide/service/DotAccessibilityService.kt").readText(),
        )
        // The outline does follow a scroll now, by asking the control where it
        // is. What it must never do again is work the position out for itself:
        // three attempts at that — predicting from velocity, fusing motion
        // signals, and matching captured frames — each drew a rectangle from
        // coordinates that were already stale.
        listOf(
            "MotionPredictor",
            "HybridMotionEstimator",
            "VisualMotionEstimator",
            "TemplateMatchEstimator",
        ).forEach { token ->
            assertFalse("estimating motion must stay out: $token", service.contains(token))
        }
    }

    @Test
    fun `nothing on the drawing path tweens towards a position`() {
        val highlight = withoutComments(
            File(SOURCE_ROOT, "guide/overlay/TargetHighlight.kt").readText(),
        )
        // A position that arrives is already the best answer available to
        // where the control is. Easing towards it can only make the outline
        // late, and being late is the entire failure this replaced.
        val drawing = highlight.substringAfter("Canvas(Modifier.fillMaxSize())")
        listOf("animateTo(targetRect", "animateDpAsState", "animateRectAsState").forEach {
            assertFalse("no catch-up animation on the outline: $it", drawing.contains(it))
        }
    }

    private fun withoutXmlComments(text: String): String =
        text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

    @Test
    fun `nothing in the shipped app polls the target`() {
        val probe = withoutComments(
            File(SOURCE_ROOT, "guide/follow/TargetProbe.kt").readText(),
        )
        // The production probe answers when asked and does nothing in between.
        // A repeating post here would be the old follower coming back: a loop
        // that runs whenever a guide is open, costing the processor on a screen
        // nobody is touching, in the service of an outline that is no longer
        // drawn while anything moves.
        assertFalse("no timer", probe.contains("postDelayed"))
        assertFalse("and nothing that re-posts itself", probe.contains("handler.post(this)"))
        assertTrue("one handler thread", probe.contains("HandlerThread"))
    }

    @Test
    fun `capture is never requested before a guide asks for it`() {
        val activity = File(SOURCE_ROOT, "MainActivity.kt").readText()
        assertFalse(activity.contains("ProjectionConsentActivity"))
        assertFalse(activity.contains("MediaProjection"))
    }

    @Suppress("unused")
    fun `the outline's way back does not depend on capture running`() {
        val service = File(SOURCE_ROOT, "guide/service/DotAccessibilityService.kt").readText()

        // Once the outline is taken down it returns only when semantics vouches
        // for the target again. If that reset lived on the capture path, a
        // projection that stopped would suppress the highlight for the rest of
        // the guide — which is what happened the first time this was wired up.
        val visualHandover = service.substringAfter("private fun offerToVisualTracker")
            .substringBefore("\n    private fun ")
        assertFalse(
            "revalidation must not be gated on a capture session",
            visualHandover.contains("awaitingRevalidation = false"),
        )
        assertTrue(
            service.contains("private fun onSemanticallyRevalidated()"),
        )
    }

    @Test
    fun `the outline is never tweened towards a new position`() {
        val highlight = File(SOURCE_ROOT, "guide/overlay/TargetHighlight.kt").readText()
        val code = withoutComments(highlight)

        // Easing each new rectangle over seventy milliseconds is what produced
        // the catch-up phase: every scroll started a fresh animation from
        // wherever the outline had got to, so the page moved and the outline
        // followed. Whatever position arrives is already the best estimate of
        // now, and the only correct thing to do with it is draw it.
        // The entrance pop is still animated, and should be; it plays once per
        // target rather than once per scroll. It is the *position* that must
        // be drawn as given.
        assertFalse("position must not be interpolated", code.contains("Rect.VectorConverter"))
        assertFalse("nor eased between rectangles", code.contains("outline.animateTo"))
        assertFalse("nor Pebble", code.contains("pebble.animateTo"))

        // The position reaches the screen as a layer translation taken straight
        // from the pose, with nothing in between. Anything that smoothed it
        // would have to sit on this line.
        assertTrue(
            "the newest position must be what is drawn",
            code.contains("translationX = current?.pose?.leftPx") &&
                code.contains("translationY = current?.pose?.topPx"),
        )
    }

    @Test
    fun `the target rectangle is never clamped into the viewport`() {
        val controller = File(SOURCE_ROOT, "guide/overlay/GuideOverlayController.kt").readText()
        val code = withoutComments(controller)

        // Clamping each edge independently is what made a row leaving the
        // screen lose its height instead of sliding out of view.
        assertFalse(
            "the highlight must be clipped, not clamped",
            code.contains("inflate(padding).clampInto"),
        )
    }

    @Test
    fun `canonical geometry is established in one place only`() {
        val controller = File(
            SOURCE_ROOT,
            "guide/positional/PositionalGuideController.kt",
        ).readText()

        // A target's shape is measured once, by the state machine, from a
        // reading it has decided to trust. Nothing else may construct one, so
        // no scroll, scan or reconciliation can quietly resize the outline.
        assertTrue(controller.contains("CanonicalTargetGeometry.of("))

        listOf(
            "guide/service/DotAccessibilityService.kt",
            "guide/overlay/GuideOverlayController.kt",
            "guide/overlay/TargetHighlight.kt",
        ).forEach { path ->
            val text = withoutComments(File(SOURCE_ROOT, path).readText())
            assertFalse(
                "$path must not build its own target geometry",
                text.contains("CanonicalTargetGeometry("),
            )
        }
    }

    @Test
    fun `nothing visual or predicted can name a target`() {
        val offenders = mutableListOf<String>()
        listOf(
            "guide/motion/MotionPredictor.kt",
            "guide/motion/HybridMotionEstimator.kt",
            "guide/visual/GlobalMotionEstimator.kt",
            "guide/visual/LocalDriftTracker.kt",
            "guide/visual/VisualTrackingSession.kt",
        ).forEach { path ->
            val text = withoutComments(File(SOURCE_ROOT, path).readText())
            // None of these may construct or choose an identity; they receive
            // one at most, and only to notice when it has changed.
            if (text.contains("TargetIdentity(")) offenders += "$path constructs an identity"
            if (text.contains("TargetMatcher")) offenders += "$path reaches for matching"
            if (text.contains("SemanticRegionResolver")) offenders += "$path resolves a region"
        }
        assertTrue("vision must not choose targets: $offenders", offenders.isEmpty())
    }

    private fun withoutComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//.*"), " ")

    private fun forEachSource(block: (File, String) -> Unit) {
        File(SOURCE_ROOT).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { block(it, it.readText()) }
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/java/com/aaravsekhri/dot"
    }
}
