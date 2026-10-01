package com.aaravsekhri.dot.guide

import com.aaravsekhri.dot.guide.ai.AiDecision
import com.aaravsekhri.dot.guide.ai.CandidateControl
import com.aaravsekhri.dot.guide.ai.DecisionValidator
import com.aaravsekhri.dot.guide.ai.Observation
import com.aaravsekhri.dot.guide.ai.ObservationBuilder
import com.aaravsekhri.dot.guide.ai.SensitiveScreenPolicy
import com.aaravsekhri.dot.guide.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks that stand between a model's answer and somebody's screen.
 *
 * The whole safety argument for this feature is here: an answer is a proposal
 * about a reading that is already a second old, and nothing is drawn from it
 * until the control it names has been found again on the screen as it is now.
 */
class AiGroundingTest {

    private fun control(
        id: String,
        label: String,
        top: Int = 100,
        role: String = "LinearLayout",
        enabled: Boolean = true,
    ) = CandidateControl(
        id = id,
        label = label,
        description = "",
        role = role,
        bounds = Bounds(0, top, 1170, top + 200),
        clickable = true,
        editable = false,
        enabled = enabled,
        scrollable = false,
        parentId = null,
    )

    private fun observation(
        id: String = "obs-1",
        controls: List<CandidateControl> = listOf(control("n1", "Display & touch")),
        generation: Long = 1L,
        packageName: String = "com.android.settings",
        windowId: Int = 7,
    ) = Observation(
        sessionId = "s1",
        observationId = id,
        takenAtMs = 0,
        screenGeneration = generation,
        packageName = packageName,
        windowTitle = "",
        windowId = windowId,
        screenWidth = 1170,
        screenHeight = 2532,
        candidates = controls,
        scrollContainerIds = emptyList(),
        truncated = false,
    )

    private fun highlight(candidateId: String = "n1", from: String = "obs-1") =
        AiDecision.Highlight(from, candidateId, "Tap it.", "It opens.")

    @Test
    fun `a control still on screen is grounded and may be drawn`() {
        val decided = observation()
        val live = observation(id = "obs-2")

        val result = DecisionValidator.ground(highlight(), decided, live)

        assertTrue(result is DecisionValidator.Result.Ready)
        assertEquals("Display & touch", (result as DecisionValidator.Result.Ready).target.naming)
    }

    @Test
    fun `the position comes from the live reading, not the one asked about`() {
        val decided = observation(controls = listOf(control("n1", "Display & touch", top = 100)))
        val live = observation(id = "obs-2", controls = listOf(control("n9", "Display & touch", top = 1600)))

        val result = DecisionValidator.ground(highlight(), decided, live) as DecisionValidator.Result.Ready

        assertEquals(
            "drawn where it is now, not where it was",
            1600,
            result.target.bounds.top,
        )
    }

    @Test
    fun `an answer about a screen that has since changed is dropped`() {
        val decided = observation(generation = 1L)
        val moved = observation(id = "obs-2", generation = 2L)

        assertEquals(
            DecisionValidator.Result.Stale,
            DecisionValidator.ground(highlight(), decided, moved),
        )
    }

    @Test
    fun `an answer that arrives after the person left the app is dropped`() {
        val decided = observation()
        val elsewhere = observation(id = "obs-2", packageName = "com.android.chrome")

        assertEquals(
            DecisionValidator.Result.Stale,
            DecisionValidator.ground(highlight(), decided, elsewhere),
        )
    }

    @Test
    fun `an answer about a different reading than the one sent is dropped`() {
        val decided = observation(id = "obs-1")

        assertEquals(
            DecisionValidator.Result.Stale,
            DecisionValidator.ground(highlight(from = "obs-other"), decided, observation("obs-2")),
        )
    }

    @Test
    fun `a control that has gone is not approximated`() {
        val decided = observation()
        val live = observation(id = "obs-2", controls = listOf(control("n4", "Sound & vibration")))

        assertEquals(
            DecisionValidator.Result.NotFound,
            DecisionValidator.ground(highlight(), decided, live),
        )
    }

    @Test
    fun `two equally good matches are refused rather than chosen between`() {
        val decided = observation()
        val live = observation(
            id = "obs-2",
            controls = listOf(
                control("n4", "Display & touch", top = 100),
                control("n9", "Display & touch", top = 900),
            ),
        )

        assertEquals(
            DecisionValidator.Result.Ambiguous,
            DecisionValidator.ground(highlight(), decided, live),
        )
    }

    @Test
    fun `a control that is greyed out is not offered as something to tap`() {
        val decided = observation()
        val live = observation(
            id = "obs-2",
            controls = listOf(control("n4", "Display & touch", enabled = false)),
        )

        assertTrue(
            DecisionValidator.ground(highlight(), decided, live)
                is DecisionValidator.Result.NotActionable,
        )
    }

    @Test
    fun `a model naming a control that was never offered is refused`() {
        val decided = observation()

        assertEquals(
            DecisionValidator.Result.NotFound,
            DecisionValidator.ground(highlight(candidateId = "n999"), decided, observation("obs-2")),
        )
    }

    // ------------------------------------------------------- what gets sent

    @Test
    fun `a password screen is never described at all`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Sign in", top = 400)
            node(
                text = "",
                className = "android.widget.EditText",
                bounds = com.aaravsekhri.dot.guide.model.Bounds(0, 800, 1080, 900),
                password = true,
            )
        }.build()

        val verdict = SensitiveScreenPolicy.judge(screen)

        assertTrue(verdict.sensitive)
        assertEquals(SensitiveScreenPolicy.Reason.PasswordField, verdict.reason)
    }

    @Test
    fun `a screen asking for a security code is left alone`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Enter the code we sent you", top = 400)
        }.build()

        assertTrue(SensitiveScreenPolicy.judge(screen).sensitive)
    }

    @Test
    fun `a screen asking for card details is left alone`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Card number", top = 400)
        }.build()

        assertEquals(
            SensitiveScreenPolicy.Reason.Payment,
            SensitiveScreenPolicy.judge(screen).reason,
        )
    }

    @Test
    fun `a window the app marked secure stops Dot reading it too`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Your balance", top = 400)
        }.build()

        val verdict = SensitiveScreenPolicy.judge(screen, screenshotRefused = true)

        assertTrue(verdict.sensitive)
        assertEquals(SensitiveScreenPolicy.Reason.ProtectedByTheApp, verdict.reason)
    }

    @Test
    fun `an ordinary settings page is not treated as private`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 400)
            settingsRow("Sound & vibration", top = 700)
        }.build()

        assertFalse(SensitiveScreenPolicy.judge(screen).sensitive)
    }

    @Test
    fun `only things worth acting on are described`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 400)
            settingsRow("Sound & vibration", top = 700)
        }.build()

        val observation = ObservationBuilder.build(
            sessionId = "s",
            screen = screen,
            screenGeneration = 1,
            windowTitle = "",
            takenAtMs = 0,
        )

        val labels = observation.candidates.map { it.naming }
        assertTrue("Display & touch" in labels)
        assertTrue("Sound & vibration" in labels)
        assertTrue(
            "a page of nodes should not become a page of candidates: $labels",
            observation.candidates.size <= 8,
        )
    }

    @Test
    fun `two readings of the same screen get different ids`() {
        val screen = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 400)
        }.build()

        val first = ObservationBuilder.build("s", screen, 1, "", 0)
        val second = ObservationBuilder.build("s", screen, 1, "", 0)

        assertNotEquals(
            "an observation id names a reading, never a screen",
            first.observationId,
            second.observationId,
        )
        assertEquals(
            "but the same screen is recognisably the same, so it is not asked about twice",
            first.contentKey,
            second.contentKey,
        )
    }

    @Test
    fun `a screen that has scrolled is still the same screen`() {
        val high = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 400)
        }.build()
        val low = ScreenBuilder().apply {
            scrollingList()
            settingsRow("Display & touch", top = 900)
        }.build()

        assertEquals(
            ObservationBuilder.build("s", high, 1, "", 0).contentKey,
            ObservationBuilder.build("s", low, 1, "", 0).contentKey,
        )
    }

    /**
     * Seen live on the Display size and text page: two "+" buttons, one per
     * slider. Refused as ambiguous, the step came out as words only. When
     * nothing on the page has moved, the one in the same place is the same
     * control; when anything has, the refusal stands.
     */
    @Test
    fun `identical controls are told apart only on an unchanged page`() {
        val controls = listOf(
            control("n1", "Larger", top = 1800),
            control("n2", "Larger", top = 2140),
        )
        val decided = observation(controls = controls)
        val unchanged = observation(id = "obs-2", controls = controls)

        val ready = DecisionValidator.ground(highlight(candidateId = "n2"), decided, unchanged)
            as DecisionValidator.Result.Ready
        assertEquals("the one beside Display size, not Font size", 2140, ready.target.bounds.top)

        val scrolled = observation(
            id = "obs-3",
            controls = controls.map { it.copy(bounds = it.bounds.copy(top = it.bounds.top - 50, bottom = it.bounds.bottom - 50)) },
        )
        assertEquals(
            DecisionValidator.Result.Ambiguous,
            DecisionValidator.ground(highlight(candidateId = "n2"), decided, scrolled),
        )
    }

    /**
     * Found live on the Home screen: the scrolling workspace took the label of
     * the first icon inside it and swallowed the icon. The model was told
     * "Play Store" was a full-screen scrolling list, with no Play Store icon,
     * and kept asking to scroll for it.
     */
    @Test
    fun `a scrolling container never takes the name of a control inside it`() {
        val screen = com.aaravsekhri.dot.guide.model.ScreenSnapshot(
            packageName = "com.google.android.apps.nexuslauncher",
            nodes = listOf(
                node(0, null, "", Bounds(0, 0, 1170, 2532), clickable = false, scrollable = true),
                node(1, 0, "Play Store", Bounds(69, 1515, 291, 1804), clickable = true),
                node(2, 0, "Gmail", Bounds(339, 1515, 561, 1804), clickable = true),
            ),
            screenWidth = 1170,
            screenHeight = 2532,
        )

        val candidates = ObservationBuilder.build("s", screen, 1, "", 0).candidates

        val icon = candidates.single { it.naming == "Play Store" }
        assertTrue("the icon itself is offered, tappable", icon.clickable && !icon.scrollable)
        assertTrue("and the workspace does not wear its name", candidates.none { it.scrollable && it.naming == "Play Store" })
    }

    @Test
    fun `a settings row still borrows the words of its title`() {
        val screen = com.aaravsekhri.dot.guide.model.ScreenSnapshot(
            packageName = "com.android.settings",
            nodes = listOf(
                node(0, null, "", Bounds(0, 400, 1170, 600), clickable = true),
                node(1, 0, "Display & touch", Bounds(100, 420, 800, 480), clickable = false),
            ),
            screenWidth = 1170,
            screenHeight = 2532,
        )

        val row = ObservationBuilder.build("s", screen, 1, "", 0).candidates.single()
        assertEquals("Display & touch", row.naming)
        assertTrue(row.clickable)
    }

    private fun node(
        id: Int,
        parent: Int?,
        text: String,
        bounds: Bounds,
        clickable: Boolean,
        scrollable: Boolean = false,
    ) = com.aaravsekhri.dot.guide.model.NodeSnapshot(
        id = id, parentId = parent, depth = if (parent == null) 0 else 1, windowId = 1,
        packageName = "p", className = if (scrollable) "android.widget.ScrollView" else "android.widget.TextView",
        viewId = null, text = text, contentDescription = null, bounds = bounds,
        isVisibleToUser = true, isEnabled = true, isClickable = clickable, isFocusable = clickable,
        isScrollable = scrollable, isPassword = false, isCheckable = false,
    )
}
