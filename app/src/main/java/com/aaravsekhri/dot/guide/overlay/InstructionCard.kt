package com.aaravsekhri.dot.guide.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aaravsekhri.dot.ui.components.PreferredAction
import com.aaravsekhri.dot.ui.components.PreferredActionHeadroom
import com.aaravsekhri.dot.ui.components.dotClickable
import com.aaravsekhri.dot.ui.pebble.CARD_PEBBLE_TAG
import com.aaravsekhri.dot.ui.pebble.CardPebble
import com.aaravsekhri.dot.ui.pebble.PebbleMotion
import com.aaravsekhri.dot.ui.pebble.PebbleWait
import com.aaravsekhri.dot.ui.pebble.TravelingHopPebble
import com.aaravsekhri.dot.ui.pebble.WaitingPebble
import com.aaravsekhri.dot.ui.theme.DotColors
import com.aaravsekhri.dot.ui.theme.DotType
import com.aaravsekhri.dot.ui.theme.SchibstedGrotesk

/**
 * The cards Dot shows above the app being guided.
 *
 * Restrained on purpose: white, one hairline border, a soft lift rather than a
 * drop shadow, and never more than a line or two of text. These sit over
 * somebody else's interface, so they must not look like a system warning and
 * must not bury the thing they are pointing at.
 */

private val CardShape = RoundedCornerShape(24.dp)
private val CardBorder = Color(0xFFE7E9EC)

/** Type sizes specific to the overlay, taken from the approved boards. */
private object OverlayType {
    val Instruction = TextStyle(
        fontFamily = SchibstedGrotesk,
        fontWeight = FontWeight.W600,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.012).em,
    )
    val CardHeading = TextStyle(
        fontFamily = SchibstedGrotesk,
        fontWeight = FontWeight.W600,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.012).em,
    )
    val Body = TextStyle(
        fontFamily = SchibstedGrotesk,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 22.4.sp,
    )
    val Pill = TextStyle(
        fontFamily = SchibstedGrotesk,
        fontWeight = FontWeight.W500,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )
    val WideButton = TextStyle(
        fontFamily = SchibstedGrotesk,
        fontWeight = FontWeight.W600,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )
}

/**
 * Where a card's one Pebble is, and how it moves.
 *
 * Every card has exactly one, drawn by [OverlayCard] itself rather than by
 * each kind of card, so no card can leave Pebble out and none can draw two.
 */
internal data class CardCharacter(
    val motion: CardMotion,
    /**
     * Perch Pebble on the card's recommended action, when it has one — the
     * orange outline and Pebble every recommended action in Dot wears — and
     * not beside the words as well.
     */
    val onPreferred: Boolean = true,
    /** Beside the words: at the start of the line, or at its end. */
    val leading: Boolean = false,
)

/** How the card's Pebble moves; see [PebbleMotion] and [TravelingHopPebble]. */
internal enum class CardMotion {
    /** Instructions and teaching. */
    Bob,

    /** A question for the person. */
    Curious,

    /** A pause, a failure, a way back. */
    Reassure,

    /** A planning request is out: Pebble hops across its own band below the words. */
    Planning,

    /** The page is moving: Pebble keeps still, and only blinks. */
    HoldStill,

    /** Another app is taking its time: Pebble glances down, as at a progress bar. */
    External,

    /** Something finished: one happy bounce and a little confetti. */
    Celebrate,
}

/** The one recommended action on a card, drawn by [OverlayCard]. */
internal data class PreferredButton(val label: String, val onClick: () -> Unit)

/**
 * The shell every overlay card shares, and the one place its Pebble is drawn.
 *
 * Exactly the card, edge to edge: the window it sits in is the size of what
 * can be seen, so every pixel that takes a touch is a pixel of the card and
 * the app underneath keeps every other one. There is no transparent margin
 * and no shadow drawn outside it — the soft lift is drawn by the layer that
 * cannot be touched ([CardShadow]).
 *
 * Laid out as: the top line ([top]) with Pebble beside it, or Pebble's
 * planning band beneath it; then [body]; then the one [preferred] action,
 * with Pebble perched on it when [CardCharacter.onPreferred]; then [footer].
 * Pebble is always in fixed-size space, so its animation never changes what
 * the card measures and never moves the words.
 *
 * @param density [CardDensity.Compact] trims padding for a card that has to
 *   fit in a small space; the text stays the same size, and Pebble stays.
 */
@Composable
private fun OverlayCard(
    character: CardCharacter,
    modifier: Modifier = Modifier,
    density: CardDensity = CardDensity.Full,
    /**
     * A restrained rust edge, for a card that is itself the guidance — no
     * outline on the screen to look at, so the card is where the eye goes.
     * Not a button: the edge is quiet and the card does nothing when tapped.
     */
    accent: Boolean = false,
    blockedNote: String? = null,
    preferred: PreferredButton? = null,
    /** A celebrating Pebble's own completion, so it is celebrated once. */
    celebration: CardContent.Celebration? = null,
    /** Dragging the card by its handle; null for a card that cannot be moved. */
    onDrag: ((Float, Boolean) -> Unit)? = null,
    top: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val compact = density == CardDensity.Compact
    val perched = preferred != null && character.onPreferred && character.motion != CardMotion.Planning &&
        character.motion != CardMotion.Celebrate
    val pebbleSize = if (compact) 28.dp else 34.dp
    @Composable
    fun Character(modifier: Modifier = Modifier) = when (character.motion) {
        CardMotion.Bob -> CardPebble(PebbleMotion.Bob, modifier, pebbleSize)
        CardMotion.Curious -> CardPebble(PebbleMotion.Curious, modifier, pebbleSize)
        CardMotion.Reassure -> CardPebble(PebbleMotion.Reassure, modifier, pebbleSize)
        CardMotion.HoldStill -> WaitingPebble(PebbleWait.Still, modifier, size = if (compact) 32.dp else 44.dp, tag = CARD_PEBBLE_TAG)
        CardMotion.External -> WaitingPebble(PebbleWait.External, modifier, size = if (compact) 32.dp else 44.dp, tag = CARD_PEBBLE_TAG)
        CardMotion.Celebrate -> CelebratingPebble(
            eventId = celebration?.eventId ?: 0L,
            animate = celebration?.animate ?: false,
            fuller = celebration?.fuller ?: false,
            modifier = modifier,
            size = if (compact) 56.dp else 76.dp,
            tag = CARD_PEBBLE_TAG,
        )
        CardMotion.Planning -> Unit
    }
    val beside = !perched && character.motion != CardMotion.Planning
    val waitingBeside = character.motion == CardMotion.HoldStill || character.motion == CardMotion.External
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DotColors.Surface, CardShape)
            .border(
                if (accent) 1.5.dp else 1.dp,
                if (accent) DotColors.Rust.copy(alpha = 0.55f) else CardBorder,
                CardShape,
            )
            .padding(
                start = if (compact) 12.dp else 18.dp,
                end = if (compact) 12.dp else 18.dp,
                bottom = if (compact) 12.dp else 18.dp,
                top = if (onDrag != null) 0.dp else if (compact) 12.dp else 18.dp,
            ),
    ) {
        if (onDrag != null) DragGrip(onDrag)
        Row(
            verticalAlignment = if (character.motion == CardMotion.Celebrate || waitingBeside) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (beside && (character.leading || waitingBeside || character.motion == CardMotion.Celebrate)) {
                Character()
                Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
            }
            top()
            if (beside && !(character.leading || waitingBeside || character.motion == CardMotion.Celebrate)) {
                Spacer(Modifier.width(10.dp))
                Character()
            }
        }
        if (character.motion == CardMotion.Planning) {
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
            TravelingHopPebble(height = if (compact) 40.dp else 48.dp)
        }
        BlockedNote(blockedNote)
        body()
        if (preferred != null) {
            Spacer(Modifier.height(PreferredActionHeadroom))
            PreferredAction(
                modifier = Modifier.fillMaxWidth(),
                showPebble = perched,
                pebble = if (perched) { m -> Character(m) } else null,
            ) {
                SolidWideButton(text = preferred.label, onClick = preferred.onClick, modifier = Modifier.fillMaxWidth())
            }
        }
        footer()
    }
}

/**
 * The line a card adds when it could not be placed clear of what the step
 * needs: what to do about it, never a silent overlap.
 */
@Composable
private fun BlockedNote(note: String?) {
    if (note == null) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = note,
        style = OverlayType.Body,
        color = DotColors.Muted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * A completion: Pebble pleased beside what was learned, honest progress, one
 * clear way on and a quiet second. The words and buttons are there from the
 * first frame; the celebration never delays them.
 */
@Composable
internal fun CelebrationCard(
    content: CardContent.Celebration,
    onPrimary: () -> Unit,
    onSecondary: (() -> Unit)?,
    density: CardDensity = CardDensity.Full,
    blockedNote: String? = null,
) {
    val compact = density == CardDensity.Compact
    // The celebrating Pebble beside the words is the one Pebble on this card;
    // the way on keeps its orange outline without a second.
    OverlayCard(
        character = CardCharacter(CardMotion.Celebrate, onPreferred = false, leading = true),
        density = density,
        blockedNote = blockedNote,
        celebration = content,
        preferred = PreferredButton(content.primaryLabel, onPrimary),
        top = {
            Column(Modifier.weight(1f)) {
                Text(
                    text = content.headline,
                    style = OverlayType.Instruction,
                    color = DotColors.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (content.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = content.detail, style = OverlayType.Body, color = DotColors.Muted)
                }
            }
        },
        body = {
            if (content.progress != null) {
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                Text(text = content.progress, style = OverlayType.Body, color = DotColors.Muted)
                content.fraction?.let { fraction ->
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(CardBorder, RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .height(6.dp)
                                .background(DotColors.Rust, RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        },
        footer = {
            if (content.secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
                QuietTextButton(text = content.secondaryLabel, onClick = onSecondary, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

// ------------------------------------------------------------------- pieces

@Composable
private fun QuietTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .dotClickable(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OverlayType.Pill,
            color = DotColors.Muted,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Composable
private fun OutlinedPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .dotClickable(onClick = onClick, shape = shape)
            .background(DotColors.Surface, shape)
            .border(1.dp, DotColors.Border, shape)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OverlayType.Pill, color = DotColors.Ink)
    }
}

@Composable
private fun SolidWideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 50.dp)
            .dotClickable(onClick = onClick, shape = shape)
            .background(DotColors.Ink, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Wraps at a large text size rather than cutting the words off.
        Text(
            text = text,
            style = OverlayType.WideButton,
            color = DotColors.Surface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun OutlinedWideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 50.dp)
            .dotClickable(onClick = onClick, shape = shape)
            .background(DotColors.Surface, shape)
            .border(1.dp, DotColors.Border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OverlayType.WideButton, color = DotColors.Ink)
    }
}

// -------------------------------------------------------------------- cards

/**
 * A single line with Pebble and one action: "Opening Settings…", "Guide
 * paused", "Open Settings to carry on", and the finishing card all use it.
 *
 * With [preferred], the action is the one Dot recommends — "Try again later"
 * on a task that has stopped moving, "Continue" between lessons — and it is
 * drawn as a wide button with Pebble perched on it, the secondary action
 * quiet beneath. Otherwise Pebble sits beside the words.
 */
@Composable
fun StatusCard(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    preferred: Boolean = false,
    /** Calm and reassuring for a pause or a failure; the ordinary bob otherwise. */
    reassuring: Boolean = false,
    density: CardDensity = CardDensity.Full,
    blockedNote: String? = null,
    /** "End guide", quietly beneath: on every card of a guide that is running. */
    endLabel: String? = null,
    onEnd: (() -> Unit)? = null,
) {
    val recommended = preferred && actionLabel != null && onAction != null
    OverlayCard(
        character = CardCharacter(if (reassuring) CardMotion.Reassure else CardMotion.Bob, leading = true),
        density = density,
        blockedNote = blockedNote,
        preferred = if (recommended) PreferredButton(actionLabel!!, onAction!!) else null,
        top = {
            Text(
                text = message,
                style = OverlayType.CardHeading,
                color = DotColors.Ink,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!recommended && actionLabel != null && onAction != null) {
                Spacer(Modifier.width(12.dp))
                OutlinedPill(text = actionLabel, onClick = onAction, modifier = Modifier.align(Alignment.CenterVertically))
            }
        },
        footer = {
            if (secondaryLabel != null && onSecondary != null) {
                // One way on is emphasised; the other stays quiet but readable.
                Spacer(Modifier.height(if (recommended) (if (density == CardDensity.Compact) 2.dp else 6.dp) else 8.dp))
                QuietTextButton(text = secondaryLabel, onClick = onSecondary, modifier = Modifier.fillMaxWidth())
            }
            if (endLabel != null && onEnd != null) {
                Spacer(Modifier.height(if (secondaryLabel != null) 0.dp else if (density == CardDensity.Compact) 2.dp else 6.dp))
                QuietTextButton(text = endLabel, onClick = onEnd, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

/**
 * The ordinary step card: where you are, one thing to do, and two quiet ways
 * out. [doneLabel] appears only on a step the person adjusts rather than taps,
 * because that is the one case where Dot cannot tell when they are finished.
 *
 * Pebble is always on it. With an outline on the screen Pebble stays here too,
 * bobbing quietly beside the words, rather than leaving the card for the
 * outline; on a step whose own button is the next thing to press, it perches
 * on that button; while Dot works the next step out, it hops across its band
 * beneath the words.
 */
@Composable
fun StepCard(
    stepLabel: String,
    instruction: String,
    cannotSeeLabel: String?,
    pauseLabel: String,
    onCannotSee: () -> Unit,
    onPause: () -> Unit,
    doneLabel: String? = null,
    onDone: (() -> Unit)? = null,
    /** The movement to demonstrate beside the words; see [GestureIllustration]. */
    gesture: com.aaravsekhri.dot.guide.ai.Gesture? = null,
    /** "Move": sends the card to the other end of the screen, out of the way. */
    moveLabel: String? = null,
    onMove: (() -> Unit)? = null,
    /**
     * Set while Dot is waiting rather than instructing: Pebble waits in the
     * card in the way that fits what it is waiting for, and the words stay
     * exactly where they are.
     */
    waiting: WaitingKind? = null,
    /** The done action as a small fallback link, not the wide button. */
    quietDone: Boolean = false,
    linkLabel: String? = null,
    onLink: (() -> Unit)? = null,
    /** Where the eye goes on this card; see [CardEmphasis]. */
    emphasis: CardEmphasis = CardEmphasis.Card,
    density: CardDensity = CardDensity.Full,
    /** Said when the card could not be placed clear of what the step needs. */
    blockedNote: String? = null,
    /** Dragging the card by its handle, when it can be moved. */
    onDrag: ((Float, Boolean) -> Unit)? = null,
    endLabel: String? = null,
    onEnd: (() -> Unit)? = null,
) {
    val compact = density == CardDensity.Compact
    val motion = when (waiting) {
        WaitingKind.Thinking, WaitingKind.Looking -> CardMotion.Planning
        WaitingKind.HoldStill -> CardMotion.HoldStill
        WaitingKind.External -> CardMotion.External
        null -> CardMotion.Bob
    }
    val hasDone = doneLabel != null && onDone != null && !quietDone
    val recommended = hasDone && emphasis == CardEmphasis.DoneButton
    OverlayCard(
        onDrag = onDrag,
        character = CardCharacter(motion),
        density = density,
        accent = waiting == null && emphasis == CardEmphasis.Card,
        blockedNote = blockedNote,
        preferred = if (recommended) PreferredButton(doneLabel!!, onDone!!) else null,
        top = {
            Column(Modifier.weight(1f)) {
                // The small heading is decoration, and the first thing a tight
                // space gives up. The words never are; nor is Pebble.
                if (!compact) {
                    Text(
                        text = stepLabel.uppercase(),
                        style = DotType.SectionLabel,
                        color = DotColors.Faint,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = instruction,
                        style = OverlayType.Instruction,
                        color = DotColors.Ink,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (gesture != null && !compact) {
                        Spacer(Modifier.width(14.dp))
                        GestureIllustration(gesture)
                    }
                }
            }
        },
        body = {
            if (hasDone && !recommended) {
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                SolidWideButton(text = doneLabel!!, onClick = onDone!!, modifier = Modifier.fillMaxWidth())
            }
        },
        footer = {
            // Wraps rather than squeezes: at a large text size three controls do
            // not fit on one line, and a Row gave Pause what was left — one
            // letter per line.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 6.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // Absent while Dot is still working the step out: there is no
                // instruction yet for it to have not worked.
                if (cannotSeeLabel != null) {
                    QuietTextButton(text = cannotSeeLabel, onClick = onCannotSee)
                }
                if (linkLabel != null && onLink != null) {
                    QuietTextButton(text = linkLabel, onClick = onLink)
                }
                if (quietDone && doneLabel != null && onDone != null) {
                    QuietTextButton(text = doneLabel, onClick = onDone)
                }
                if (moveLabel != null && onMove != null) {
                    QuietTextButton(text = moveLabel, onClick = onMove)
                }
                OutlinedPill(text = pauseLabel, onClick = onPause)
                if (endLabel != null && onEnd != null) {
                    QuietTextButton(text = endLabel, onClick = onEnd)
                }
            }
        },
    )
}

/**
 * A question answered right here, with a button per answer — no trip into
 * Dot and no typing, for the questions whose answers are a short list.
 *
 * No answer is emphasised: these are the person's to choose, and a Pebble on
 * "Yes" would be Dot choosing for them.
 */
@Composable
fun ChoiceCard(
    question: String,
    options: List<String>,
    onChoose: (Int) -> Unit,
    pauseLabel: String,
    onPause: () -> Unit,
    density: CardDensity = CardDensity.Full,
    blockedNote: String? = null,
    moveLabel: String? = null,
    onMove: (() -> Unit)? = null,
    endLabel: String? = null,
    onEnd: (() -> Unit)? = null,
) {
    val compact = density == CardDensity.Compact
    OverlayCard(
        // Curious, beside the question; never perched on an answer.
        character = CardCharacter(CardMotion.Curious, onPreferred = false),
        density = density,
        accent = true,
        blockedNote = blockedNote,
        top = {
            Text(
                text = question,
                style = OverlayType.Instruction,
                color = DotColors.Ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        body = {
            options.forEachIndexed { index, option ->
                Spacer(Modifier.height(if (index == 0) (if (compact) 10.dp else 16.dp) else (if (compact) 6.dp else 10.dp)))
                OutlinedWideButton(text = option, onClick = { onChoose(index) }, modifier = Modifier.fillMaxWidth())
            }
        },
        footer = {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 4.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (moveLabel != null && onMove != null) QuietTextButton(text = moveLabel, onClick = onMove)
                QuietTextButton(text = pauseLabel, onClick = onPause)
                if (endLabel != null && onEnd != null) QuietTextButton(text = endLabel, onClick = onEnd)
            }
        },
    )
}

/**
 * A question answered in the person's own words, typed right here over the
 * app they are using — no trip into Dot.
 *
 * Only for ordinary answers ("What would you like to search for?"). Dot
 * never asks for a password, a code or a card number: those are typed in the
 * app itself, and a question that seems to want one is never given a box.
 */
@Composable
fun TextQuestionCard(
    question: String,
    sendLabel: String,
    fieldDescription: String,
    onSend: (String) -> Unit,
    pauseLabel: String,
    onPause: () -> Unit,
    moveLabel: String? = null,
    onMove: (() -> Unit)? = null,
    density: CardDensity = CardDensity.Full,
    blockedNote: String? = null,
    /** Answers that need no typing, sent as they are: "I'm not sure". */
    quickAnswers: List<String> = emptyList(),
    /** Where a personal answer goes, and the other way to give it. */
    note: String? = null,
    endLabel: String? = null,
    onEnd: (() -> Unit)? = null,
) {
    val compact = density == CardDensity.Compact
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    // Sent once: a second tap while the answer is on its way does nothing.
    var sent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val motionOff = com.aaravsekhri.dot.ui.util.LocalMotionOff.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Only the copy that is shown asks for the keyboard.
        if (!motionOff) runCatching { focus.requestFocus() }
    }
    fun send() {
        val answer = text.trim()
        if (answer.isEmpty() || sent) return
        sent = true
        onSend(answer)
    }
    OverlayCard(
        character = CardCharacter(CardMotion.Curious, onPreferred = false),
        density = density,
        accent = true,
        blockedNote = blockedNote,
        top = {
            Text(
                text = question,
                style = OverlayType.Instruction,
                color = DotColors.Ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        body = {
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Text(text = note, style = OverlayType.Body, color = DotColors.Muted)
            }
            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
            val shape = RoundedCornerShape(14.dp)
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = OverlayType.Body.copy(color = DotColors.Ink, fontSize = 18.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(DotColors.Rust),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                // One line, so the keyboard's own key sends rather than starting a new line.
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(DotColors.Surface, shape)
                    .border(1.dp, DotColors.Border, shape)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = fieldDescription },
            )
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            SolidWideButton(text = sendLabel, onClick = ::send, modifier = Modifier.fillMaxWidth())
            if (quickAnswers.isNotEmpty()) {
                // Plain, readable choices beside typing — never the recommended one.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compact) 6.dp else 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickAnswers.forEach { answer ->
                        OutlinedPill(text = answer, onClick = {
                            if (!sent) {
                                sent = true
                                onSend(answer)
                            }
                        })
                    }
                }
            }
        },
        footer = {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 4.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (moveLabel != null && onMove != null) QuietTextButton(text = moveLabel, onClick = onMove)
                QuietTextButton(text = pauseLabel, onClick = onPause)
                if (endLabel != null && onEnd != null) QuietTextButton(text = endLabel, onClick = onEnd)
            }
        },
    )
}

/** "Not on this screen?" — shown when Dot cannot see the target, or is asked. */
@Composable
fun RecoveryCard(
    title: String,
    body: String,
    primaryLabel: String,
    secondaryLabel: String,
    closeDescription: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onClose: () -> Unit,
) {
    OverlayCard(
        character = CardCharacter(CardMotion.Reassure, leading = true),
        top = {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = title,
                    style = OverlayType.CardHeading,
                    color = DotColors.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(text = body, style = OverlayType.Body, color = DotColors.Muted)
            }
        },
        body = {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SolidWideButton(text = primaryLabel, onClick = onPrimary, modifier = Modifier.weight(1f))
                OutlinedWideButton(
                    text = secondaryLabel,
                    onClick = onSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
            QuietTextButton(text = closeDescription, onClick = onClose, modifier = Modifier.fillMaxWidth())
        },
    )
}

@Preview(name = "Step card", showBackground = true, backgroundColor = 0xFFEFEFEF, widthDp = 390)
@Composable
private fun PreviewStepCard() {
    StepCard(
        stepLabel = "Step 1 of 3",
        instruction = "Tap Display.",
        cannotSeeLabel = "I don't see that",
        pauseLabel = "Pause",
        onCannotSee = {},
        onPause = {},
    )
}

@Preview(name = "Adjust step card", showBackground = true, backgroundColor = 0xFFEFEFEF, widthDp = 390)
@Composable
private fun PreviewAdjustCard() {
    StepCard(
        stepLabel = "Step 3 of 3",
        instruction = "Drag Font size to the right until the text looks right to you.",
        cannotSeeLabel = "I don't see that",
        pauseLabel = "Pause",
        onCannotSee = {},
        onPause = {},
        doneLabel = "Done",
        onDone = {},
    )
}

@Preview(name = "Recovery card", showBackground = true, backgroundColor = 0xFFEFEFEF, widthDp = 390)
@Composable
private fun PreviewRecoveryCard() {
    RecoveryCard(
        title = "Not on this screen?",
        body = "On some phones it is called Font size. Try scrolling down a little.",
        primaryLabel = "Find it for me",
        secondaryLabel = "Go back a step",
        closeDescription = "End guide",
        onPrimary = {},
        onSecondary = {},
        onClose = {},
    )
}

@Preview(name = "Status card", showBackground = true, backgroundColor = 0xFFEFEFEF, widthDp = 390)
@Composable
private fun PreviewStatusCard() {
    StatusCard(message = "Opening Settings…", actionLabel = "Pause", onAction = {})
}

/**
 * Where a card's emphasis goes. One at a time, so Pebble never competes with
 * itself.
 */
enum class CardEmphasis {
    /** An outline on the screen shows where to look; the card stays plain. */
    Target,

    /** The card is the guidance: Pebble on its corner, a quiet rust edge. */
    Card,

    /** The card's own button is the next thing to press. */
    DoneButton,
}

/**
 * A handle to drag the card by: a short grip at its top edge, named for
 * TalkBack. Dragging is one way to move the guide; "Move guide" on the card
 * is the other, and needs no dragging at all.
 *
 * Raw screen positions, not local ones: the card's window moves under the
 * finger while it is dragged, and a position within it would move with it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DragGrip(onDrag: (dy: Float, end: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var lastY by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .semantics { contentDescription = "Drag to move the guide" }
            .pointerInteropFilter { e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { lastY = e.rawY; true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!lastY.isNaN()) onDrag(e.rawY - lastY, false)
                        lastY = e.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        onDrag(0f, true)
                        lastY = Float.NaN
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(5.dp)
                .background(DotColors.Border, RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * The step card at its smallest: for when the full and compact cards would
 * cover the box being typed in, the control the step is about, or a dialog.
 * One short band — Pebble, the whole instruction, then one row with the
 * step's own button, Move guide and Pause — so it fits between a text box and
 * the keyboard. Nothing is left out: the words are the same, at a size still
 * easy to read, and every action the step needs stays.
 */
@Composable
internal fun StripStepCard(
    instruction: String,
    doneLabel: String?,
    onDone: (() -> Unit)?,
    moveLabel: String?,
    onMove: (() -> Unit)?,
    pauseLabel: String,
    onPause: () -> Unit,
    onDrag: ((Float, Boolean) -> Unit)?,
    waiting: Boolean,
    endLabel: String? = null,
    onEnd: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DotColors.Surface, CardShape)
            .border(1.5.dp, DotColors.Rust.copy(alpha = 0.55f), CardShape)
            .padding(start = 12.dp, end = 12.dp, top = if (onDrag != null) 0.dp else 10.dp, bottom = 8.dp),
    ) {
        if (onDrag != null) DragGrip(onDrag)
        Row(verticalAlignment = Alignment.Top) {
            if (waiting) {
                WaitingPebble(PebbleWait.Still, size = 28.dp, tag = CARD_PEBBLE_TAG)
            } else {
                CardPebble(PebbleMotion.Bob, Modifier, 26.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = instruction,
                style = OverlayType.Instruction.copy(fontSize = 18.sp, lineHeight = 23.sp),
                color = DotColors.Ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (doneLabel != null && onDone != null) {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .dotClickable(onClick = onDone, shape = shape)
                        .background(DotColors.Ink, shape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(doneLabel, style = OverlayType.Pill.copy(fontWeight = FontWeight.W600), color = DotColors.Surface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (moveLabel != null && onMove != null) StripLink(moveLabel, onMove)
            StripLink(pauseLabel, onPause)
            if (endLabel != null && onEnd != null) StripLink(endLabel, onEnd)
        }
    }
}

@Composable
private fun StripLink(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .dotClickable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OverlayType.Pill, color = DotColors.Ink, textDecoration = TextDecoration.Underline)
    }
}
