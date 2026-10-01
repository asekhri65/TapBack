package com.aaravsekhri.dot.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aaravsekhri.dot.guide.overlay.CardContent
import com.aaravsekhri.dot.guide.overlay.CardDensity
import com.aaravsekhri.dot.guide.overlay.CardEmphasis
import com.aaravsekhri.dot.guide.overlay.CelebrationCard
import com.aaravsekhri.dot.guide.overlay.ChoiceCard
import com.aaravsekhri.dot.guide.overlay.RecoveryCard
import com.aaravsekhri.dot.guide.overlay.StatusAction
import com.aaravsekhri.dot.guide.overlay.StatusCard
import com.aaravsekhri.dot.guide.overlay.StepCard
import com.aaravsekhri.dot.guide.overlay.TextQuestionCard
import com.aaravsekhri.dot.guide.overlay.WaitingKind
import com.aaravsekhri.dot.ui.pebble.CARD_PEBBLE_TAG
import com.aaravsekhri.dot.ui.theme.DotTheme
import com.aaravsekhri.dot.ui.util.LocalMotionOff
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every kind of guidance card Dot draws has Pebble in it — exactly one — in
 * every way it can be drawn: with an outline on the screen, with its own
 * button to press, while planning, compact, and with motion turned off.
 *
 * Rendered for real, on a device, rather than trusted from the code: the
 * rule used to be decided card by card, and cards lost Pebble that way.
 */
@RunWith(AndroidJUnit4::class)
class CardPebbleRenderingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertOnePebble(motionOff: Boolean = false, card: @Composable () -> Unit) {
        compose.setContent {
            DotTheme {
                CompositionLocalProvider(LocalMotionOff provides motionOff) { card() }
            }
        }
        compose.onAllNodesWithTag(CARD_PEBBLE_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun step(
        emphasis: CardEmphasis = CardEmphasis.Card,
        waiting: WaitingKind? = null,
        done: Boolean = false,
        density: CardDensity = CardDensity.Full,
        motionOff: Boolean = false,
    ) = assertOnePebble(motionOff) {
        StepCard(
            stepLabel = "Helping you",
            instruction = "Tap Display.",
            cannotSeeLabel = "That didn't work",
            pauseLabel = "Pause",
            onCannotSee = {},
            onPause = {},
            doneLabel = if (done) "Next" else null,
            onDone = if (done) ({}) else null,
            waiting = waiting,
            emphasis = emphasis,
            density = density,
            moveLabel = "Move",
            onMove = {},
        )
    }

    @Test fun instructionWithAnOutlineOnTheScreenKeepsPebbleOnTheCard() = step(emphasis = CardEmphasis.Target)

    @Test fun instructionThatIsItselfTheGuidance() = step(emphasis = CardEmphasis.Card)

    @Test fun teachingWithItsOwnNextButton() = step(emphasis = CardEmphasis.DoneButton, done = true)

    @Test fun teachingCompact() = step(emphasis = CardEmphasis.DoneButton, done = true, density = CardDensity.Compact)

    @Test fun instructionCompact() = step(emphasis = CardEmphasis.Target, density = CardDensity.Compact)

    @Test fun planning() = step(waiting = WaitingKind.Thinking)

    @Test fun planningWithMotionOff() = step(waiting = WaitingKind.Thinking, motionOff = true)

    @Test fun readingTheScreen() = step(waiting = WaitingKind.Looking)

    @Test fun askingThePageToHoldStill() = step(waiting = WaitingKind.HoldStill)

    @Test fun waitingForAnotherApp() = step(waiting = WaitingKind.External)

    @Test fun instructionWithMotionOff() = step(emphasis = CardEmphasis.Target, motionOff = true)

    @Test fun recoverableFailureWithARecommendedWayOn() = assertOnePebble {
        StatusCard(
            message = "Dot's guidance service isn't answering right now.",
            actionLabel = "Try again later",
            onAction = {},
            secondaryLabel = "Keep trying",
            onSecondary = {},
            preferred = true,
            reassuring = true,
        )
    }

    @Test fun plainStatus() = assertOnePebble {
        StatusCard(message = "Opening Settings…", actionLabel = "Pause", onAction = {})
    }

    @Test fun clarifyingChoice() = assertOnePebble {
        ChoiceCard(question = "Do you already have an account?", options = listOf("Yes", "No"), onChoose = {}, pauseLabel = "Pause", onPause = {})
    }

    @Test fun clarifyingChoiceCompact() = assertOnePebble {
        ChoiceCard(
            question = "Do you already have an account?",
            options = listOf("Yes", "No"),
            onChoose = {},
            pauseLabel = "Pause",
            onPause = {},
            density = CardDensity.Compact,
        )
    }

    @Test fun textQuestion() = assertOnePebble {
        TextQuestionCard(
            question = "Where would you like to go?",
            sendLabel = "Send",
            fieldDescription = "Your answer",
            onSend = {},
            pauseLabel = "Pause",
            onPause = {},
        )
    }

    @Test fun recovery() = assertOnePebble {
        RecoveryCard(
            title = "Not on this screen?",
            body = "Try scrolling down a little.",
            primaryLabel = "Find it for me",
            secondaryLabel = "Go back a step",
            closeDescription = "End guide",
            onPrimary = {},
            onSecondary = {},
            onClose = {},
        )
    }

    @Test fun completion() = assertOnePebble {
        CelebrationCard(
            content = CardContent.Celebration(
                headline = "You found your first place!",
                detail = "Next: Check the details",
                progress = "1 of 4 lessons completed",
                fraction = 0.25f,
                primaryLabel = "Next lesson",
                primaryAction = StatusAction.NextLesson,
                secondaryLabel = "Finish for now",
                secondaryAction = StatusAction.Finish,
                fuller = false,
                eventId = 1L,
                animate = true,
            ),
            onPrimary = {},
            onSecondary = {},
        )
    }
}
