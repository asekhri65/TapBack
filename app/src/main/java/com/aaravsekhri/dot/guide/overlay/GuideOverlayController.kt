package com.aaravsekhri.dot.guide.overlay

import android.content.Context
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.aaravsekhri.dot.R
import android.os.Build
import android.view.HapticFeedbackConstants
import com.aaravsekhri.dot.guide.GuideLog
import com.aaravsekhri.dot.guide.model.Bounds
import com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry
import com.aaravsekhri.dot.guide.motion.TargetPose
import com.aaravsekhri.dot.guide.model.GuideDefinition
import com.aaravsekhri.dot.guide.model.GuideSessionState
import com.aaravsekhri.dot.guide.model.RecoveryReason
import com.aaravsekhri.dot.guide.model.ScrollDirection
import com.aaravsekhri.dot.guide.model.SemanticTargetRegion
import com.aaravsekhri.dot.guide.model.StepAction
import com.aaravsekhri.dot.ui.pebble.PebbleMood
import com.aaravsekhri.dot.ui.theme.DotTheme

/**
 * The only thing that decides what is on screen.
 *
 * Guide state goes in, an [OverlayScene] is built from it, and the difference
 * between that and what is already up is applied. Nothing else shows or hides a
 * window. That single ownership is what makes the overlays deterministic:
 * there is no path by which two highlights, two Pebbles, or a layer left behind
 * by an earlier branch can exist, because the scene says what exists.
 *
 * The three layers stay in separate windows because they need different input
 * behaviour. The highlight and the cue must let every touch through to the app;
 * the card has buttons and must not. One window cannot be both.
 *
 * @param context the accessibility service. Held for the service's lifetime and
 *   released in [destroy]; no Activity context ever reaches here.
 */
class GuideOverlayController(
    private val context: Context,
    private val commands: OverlayCommands,
    /** Injectable so the prolonged-search threshold is testable. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
        /**
     * The layer everything positional is drawn in: the outline, Pebble, and
     * the guidance card.
     *
     * One window, put up when a guide starts and left up until it ends. It
     * covers the display and carries `FLAG_NOT_TOUCHABLE`, so nothing it shows
     * can take a touch — the scroll the card is talking about goes straight
     * through it, and the control underneath stays tappable the instant the
     * outline returns.
     *
     * Keeping it alive is not an optimisation. Taking the window down and
     * putting it back cost a fresh ComposeView and a fresh composition each
     * time, which is both a visible flicker and, measured earlier, tens of
     * milliseconds of the main thread at exactly the moment the screen needs
     * to change cleanly.
     */
    private val positionalWindow = OverlayWindow(context, touchable = false)
    private val cardWindow = OverlayWindow(context, touchable = true)

    /** What is currently on screen. The single source of truth for diffing. */
    private var applied: OverlayScene = OverlayScene()

    /** Where the card is now, and at which density; null until placed. */
    private var spot: CardSpot? = null

    /** Where the person put the card with Move, and the step it was for. */
    private var pinned: CardSpot? = null
    private var pinnedFor: String? = null

    /** The card's measured height at each density, for what it says now. */
    private var cardHeights: Map<CardDensity, Int> = emptyMap()

    /**
     * What the card must stay off, and what it should leave in view, by who
     * asked: the control the step is about, a gesture's path, the controls a
     * step's words name, and the things that help — a box's label, the
     * form's Next. Each asker replaces only its own; an empty list clears
     * only its own. Kept until the next step's card, so re-drawing the same
     * step — "I'm done" becoming the thing to press, say — cannot forget it.
     */
    private val regionSets = KeepClear()

    /** Everything that must stay usable, merged. */
    private val clearRegions: List<Bounds> get() = regionSets.essential

    /** What would help to see, merged; kept clear when that costs nothing. */
    private val helpfulRegions: List<Bounds> get() = regionSets.helpful

    /** The card of this step key has not measured itself yet: kept unseen until it has. */
    private var measuringFor: String? = null

    /** Whether the last placement found a clear spot, for checking where the card really went. */
    private var placedClear = true

    /** Corrections made after layout for the card now up; bounded so layout cannot loop. */
    private var corrections = 0

    /** Where the keyboard's top edge is, or null when it is closed. */
    private var keyboardTop: Int? = null

    /** The card's real bounds on screen, from its last layout. */
    private var laidOutCard: Bounds? = null

    /** What the card window draws, as state: updated without a new composition. */
    private val liveCard = androidx.compose.runtime.mutableStateOf<CardLayer?>(null)
    private val liveDensity = androidx.compose.runtime.mutableStateOf(CardDensity.Full)
    private val liveBlockedNote = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The card's rectangle, for its shadow on the untouchable layer. */
    private val liveCardRect = androidx.compose.runtime.mutableStateOf<Rect?>(null)

    /** Whether the outline carries its Pebble: not while the card's own action is the emphasis. */
    private val liveTargetPebble = androidx.compose.runtime.mutableStateOf(true)

    /** The outline is information to read, not a control to tap: drawn quieter, dashed. */
    private val liveReading = androidx.compose.runtime.mutableStateOf(false)

    /** Whether the target now outlined is to read ("look here") rather than to tap. */
    fun setReadingTarget(reading: Boolean) {
        liveReading.value = reading
    }

    /**
     * A scroll cue is up: the one thing said while the page moves. The step's
     * card stands down, unseen and untouchable, and comes back when it goes.
     */
    private var cueShowing = false

    /** The gesture being demonstrated on the screen itself, if any. */
    private val liveGesture = androidx.compose.runtime.mutableStateOf<com.aaravsekhri.dot.guide.ai.GesturePlan?>(null)

    /** Placement runs on the main thread, after the card has laid out. */
    private val recheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** When the current uninterrupted search began, or 0 when not searching. */
    private var searchingSince: Long = 0L

    /**
     * The geometry the full-screen surface is drawing right now.
     *
     * Held as observable state rather than passed on every render, so moving
     * the outline during a scroll costs one state write and a redraw — not a
     * new composition, and certainly not a new window.
     */
    private val liveHighlight = androidx.compose.runtime.mutableStateOf<HighlightLayer?>(null)

    /**
     * Pebble's entrance, keyed separately.
     *
     * The only part of a highlight that must reach composition: a genuinely new
     * target gets one pop, and the same target moving gets none. Keeping it out
     * of [liveHighlight] is what lets a position change skip composition
     * entirely.
     */
    private val liveAppearanceKey = androidx.compose.runtime.mutableIntStateOf(0)

    /** The guidance card, or null while the page is standing still. */
    private val liveGuidance =
        androidx.compose.runtime.mutableStateOf<GuidanceLayer?>(null)

    /** Which edge the step card is on and how tall it is, for stacking. */
    private val liveStepCard =
        androidx.compose.runtime.mutableStateOf(false to 0)

    /** The system bars the guidance card must stay clear of. */
    private val liveInsets =
        androidx.compose.runtime.mutableStateOf(com.aaravsekhri.dot.guide.model.Insets.NONE)

    /** The wording for each message, resolved once rather than per frame. */
    private fun guidanceText(message: com.aaravsekhri.dot.guide.positional.ScrollGuidance): String =
        context.getString(
            when (message) {
                com.aaravsekhri.dot.guide.positional.ScrollGuidance.KeepScrollingDown ->
                    R.string.guide_keep_scrolling_down
                com.aaravsekhri.dot.guide.positional.ScrollGuidance.ScrollUp ->
                    R.string.guide_scroll_up_to_it
                com.aaravsekhri.dot.guide.positional.ScrollGuidance.StopHere ->
                    R.string.guide_stop_here
                com.aaravsekhri.dot.guide.positional.ScrollGuidance.StopForAMoment ->
                    R.string.guide_stop_for_a_moment
            },
        )

    /** How many pulses have been played, so none is repeated. */
    private var hapticsFired: Int = 0

    /**
     * One gentle pulse, played through the overlay's own view.
     *
     * Deliberately not the vibrator. Going through the view means Dot asks for
     * no permission at all and the system's own haptic setting is honoured for
     * free: somebody who has turned touch feedback off gets nothing, without
     * Dot having to look the setting up or remember to.
     */
    private fun pulse() {
        val view = positionalWindow.view ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(effect)
        GuideLog.d("overlay") { "haptic: arrived at the target" }
    }

    /** The target's size, changed only when the target itself does. */
    private val liveCanonical = androidx.compose.runtime.mutableStateOf<
        com.aaravsekhri.dot.guide.motion.CanonicalTargetGeometry?,
        >(null)

    /** The last viewport rendered against, for repositioning Pebble mid-scroll. */
    private var lastViewport: Bounds = Bounds(0, 0, 0, 0)

    /**
     * The experimental visual tracker's rectangle, drawn in blue on the same
     * surface as the production highlight. Debug only, and null whenever the
     * experiment is off — which is always, unless someone turned it on.
     */
    private val debugVisualRect =
        androidx.compose.runtime.mutableStateOf<Pair<Bounds, Float>?>(null)

    /**
     * Dot's own positional furniture, cut out of the highlight's drawing.
     *
     * The instruction card is opaque and Dot drew it, which makes it no less
     * opaque. Painting the outline over it produced a highlight around a
     * control the person could neither see nor reach.
     */
    private val occluderRects =
        androidx.compose.runtime.mutableStateOf<List<androidx.compose.ui.geometry.Rect>>(emptyList())

    // ------------------------------------------------------------- entry

    /** Shown while Settings is being opened, before any screen has been read. */
    fun showStarting() {
        apply(
            OverlayScene(
                card = CardLayer(
                    content = CardContent.Status(
                        message = context.getString(R.string.guide_opening_settings),
                        actionLabel = context.getString(R.string.guide_pause),
                        action = StatusAction.Pause,
                        secondaryLabel = null,
                        secondaryAction = null,
                    ),
                ),
            ),
        )
    }

    /**
     * Brings every layer into line with [state].
     *
     * @param viewport the area overlays may occupy: the display less the system
     *   bars and any cutout. Node bounds and overlay windows share one
     *   coordinate space — raw screen pixels — and the insets are applied here
     *   and nowhere else.
     */
    fun render(
        state: GuideSessionState,
        definition: GuideDefinition,
        viewport: Bounds,
    ) {
        if (state !is GuideSessionState.Searching) searchingSince = 0L
        lastViewport = viewport
        // A lesson finished by this guide is celebrated; the guide's own
        // plain "finished" card does not replace it.
        if (state is GuideSessionState.Completed && applied.card?.content is CardContent.Celebration) return
        // The card is all this owns. The outline and the cue belong to the
        // positional state machine and are carried through untouched — building
        // a scene without them here tore the highlight's window down and put it
        // back on every reading of the screen, which is a flicker on every
        // scan and a replayed entrance with it.
        apply(
            buildScene(state, definition, viewport).copy(
                highlight = applied.highlight,
                guidance = applied.guidance,
            ),
        )
        // A tutorial's card keeps clear of its control by the same policy as
        // every other card.
        val region = when (state) {
            is GuideSessionState.TargetFound -> state.region
            is GuideSessionState.WaitingForUser -> state.region
            else -> null
        }
        if (region != null) {
            if (setClearRegions(listOf(region.boundsInScreen.inflate(px(HighlightMetrics.Padding.value))))) {
                commands.onCardMoved()
            }
        }
    }

    /**
     * Moves the outline and Pebble together, without rebuilding anything.
     *
     * Called at display-frame cadence while a scroll is under way. It updates
     * the drawing coordinates inside the existing full-screen surface — no
     * window is repositioned, no composition is replaced, and Pebble's entrance
     * is untouched because nothing about the target has changed except where it
     * is.
     */
    /**
     * Puts on screen exactly what the positional state machine says.
     *
     * The one place a highlight or a cue ever appears or disappears. Every
     * other route was removed: when accessibility callbacks, delayed
     * coroutines and animation completions could each call show or hide,
     * what a person saw was whichever ran last, and that was the flicker.
     */
    fun applyPositional(output: com.aaravsekhri.dot.guide.positional.PositionalOutput) {
        val outline = output.outline
        val layer = outline?.let {
            HighlightLayer(
                canonical = it.geometry,
                pose = it.pose,
                pebble = it.pebble,
                corner = it.corner,
                appearanceKey = output.appearanceKey,
                clip = it.clip,
            )
        }
        val guidance = output.guidance?.let {
            GuidanceLayer(it.message, it.animate, it.atBottom)
        }

        // Haptics are requested by count, so one can be neither missed nor
        // fired twice by a renderer that reads the output more than once.
        if (output.hapticPulses > hapticsFired) {
            hapticsFired = output.hapticPulses
            pulse()
        }

        if (applied.highlight == layer && applied.guidance == guidance) return

        // One surface at a time: while the page moves, the cue says what is
        // true, and the step's card waits unseen until it settles.
        val cue = guidance != null
        if (cue != cueShowing) {
            cueShowing = cue
            GuideLog.d("overlay") { if (cue) "scroll cue up; the card stands down" else "scroll cue gone; the card is back" }
            shadowFor(null)
            place("scroll cue", notify = false)
        }

        liveAppearanceKey.intValue = output.appearanceKey
        if (liveCanonical.value != layer?.canonical) liveCanonical.value = layer?.canonical
        liveHighlight.value = layer
        liveGuidance.value = guidance
        apply(applied.copy(highlight = layer, guidance = guidance))
    }

    /** True when the outline is drawn right now, as opposed to merely known. */
    val isHighlightDrawn: Boolean get() = liveHighlight.value != null

    /**
     * Takes down everything positional, at once.
     *
     * Called the instant a page begins to change, so no outline or cue is left
     * over a screen on its way out.
     */
    fun clearTargetLayers() {
        liveHighlight.value = null
        liveGuidance.value = null
        apply(applied.copy(highlight = null, guidance = null))
    }

    /** Geometry Dot is currently drawing, so it can be kept out of visual matching. */
    fun currentHighlightBounds(): Bounds? = applied.highlight?.coveredBounds()

    /**
     * The area overlays may occupy, for callers that do not go through
     * [render].
     *
     * A general session owns its card directly through [showRuntimeCard], so
     * it never passed through the one place this used to be set. Everything
     * that reasons about where the card sits — what it covers, which edge to
     * move it to — was then working from a viewport of zero size, and the card
     * was left sitting on the very row it was describing.
     */
    fun setViewport(viewport: Bounds) {
        if (viewport == lastViewport) return
        lastViewport = viewport
        // A rotation, a window resize, a change of navigation bar: the card is
        // placed again against the screen as it now is.
        place("screen changed")
    }

    /**
     * What the card covers, from where its window really is.
     *
     * Measured, not calculated: the window reports its own bounds on screen
     * every time it is laid out. A calculation from the viewport, the
     * keyboard and the navigation bar had to agree with the window manager
     * about every one of those, and when it did not the card was judged clear
     * of a button it was sitting on.
     */
    fun currentCardBounds(): Bounds? {
        if (applied.card == null) return null
        laidOutCard?.let { return it }
        val at = spot ?: return null
        val area = cardArea() ?: return null
        val h = cardHeights[at.density] ?: return null
        return Bounds(area.left, at.top, area.right, at.top + h)
    }

    /**
     * What the guidance card covers, so the outline is never judged visible
     * through it.
     *
     * A band at whichever edge the card is on. It is only ever up while the
     * page is moving and no outline is drawn, so this matters for the reading
     * taken on the way back rather than for anything on screen at the time.
     */
    fun currentGuidanceBounds(): Bounds? = applied.guidance?.let {
        // Where it measured itself resting, at the edge it is on; the band
        // below is only for the moment before it has.
        guidanceRest?.takeIf { rest -> (rest.centerY > lastViewport.centerY) == it.atBottom }?.let { rest -> return rest }
        if (it.atBottom) {
            Bounds(
                lastViewport.left,
                lastViewport.bottom - GUIDANCE_MASK_HEIGHT,
                lastViewport.right,
                lastViewport.bottom,
            )
        } else {
            Bounds(
                lastViewport.left,
                lastViewport.top,
                lastViewport.right,
                lastViewport.top + GUIDANCE_MASK_HEIGHT,
            )
        }
    }

    /**
     * Keeps the card off a control that has just been read somewhere new —
     * scrolled underneath the card, say.
     *
     * @return true when the card moved and the scene needs redrawing.
     */
    fun ensureCardAvoids(target: Bounds): Boolean = setClearRegions(ClearSource.Target, listOf(target))

    /**
     * Keeps the card off everything in [regions] — the control the step is
     * about, with room round it for a finger — whether or not it is outlined.
     *
     * The regions stay until the next call replaces them. Every later change
     * that could put the card on top of them — the keyboard, the card's own
     * height, the screen turning — places it again against them.
     *
     * @return true when the card moved.
     */
    fun setClearRegions(regions: List<Bounds>): Boolean = setClearRegions(ClearSource.Target, regions)

    /**
     * Keeps the card off [regions] for [source], replacing only that
     * source's regions; an empty list clears them. The others stay.
     *
     * @return true when the card moved.
     */
    fun setClearRegions(source: ClearSource, regions: List<Bounds>): Boolean {
        val margin = px(TOUCH_MARGIN_DP)
        val widened = regions.filter { !it.isEmpty }.map { it.inflate(margin) }
        if (!regionSets.set(source, widened)) return place("same regions", notify = false)
        return place("${source.name.lowercase()} ${widened.size}", notify = false)
    }

    /**
     * Places the card by the one policy every card uses. See [CardPlacement].
     *
     * @return true when the card's position or density changed.
     */
    private fun place(why: String, notify: Boolean = true): Boolean {
        val card = applied.card ?: return false
        val area = cardArea() ?: return false
        if (cardHeights.isEmpty()) return false
        val key = pinKey(card.content)
        val result = CardPlacement.choose(
            area = area,
            heights = cardHeights,
            avoid = clearRegions,
            current = spot,
            pinned = pinned.takeIf { pinnedFor == key },
            minReachable = px(MIN_REACHABLE_DP),
            prefer = helpfulRegions,
            hysteresis = px(HYSTERESIS_DP),
        )
        placedClear = result.clear
        // The note changes the card's height, and a height change places the
        // card again: shown once for a card, it stays for that card, so the
        // two can never chase each other.
        if (!result.clear) blockedNoteFor = key
        liveBlockedNote.value = if (blockedNoteFor == key) blockedNote(card.content) else null
        val changed = result.spot != spot
        spot = result.spot
        liveDensity.value = result.spot.density
        // A new step's card stays unseen until it has measured itself, so it
        // is never shown for a frame at the last card's size over a control.
        val shown = measuringFor == null && !cueShowing
        cardWindow.move(OverlayPlacement.CardAt(area.left, result.spot.top, area.width, visible = shown))
        // The shadow goes where the card has just been put. A window that is
        // only moved reports no new layout, and a shadow left at the last
        // reported place drew a second, ghost card.
        shadowFor(if (shown) placedRect(area, result.spot) else null)
        // A card stood down for the scroll cue takes no room from it.
        liveStepCard.value = if (cueShowing) false to 0 else isLow(result.spot, area) to (cardHeights[result.spot.density] ?: 0)
        if (changed || !result.clear) {
            GuideLog.d("overlay") {
                "card placed ($why): ${result.reason} top=${result.spot.top} " +
                    "density=${result.spot.density} clear=${result.clear} regions=${clearRegions.size}+${helpfulRegions.size}"
            }
        }
        if (changed && notify) commands.onCardMoved()
        return changed
    }

    /** Where cards may go: the screen less its bars, less the keyboard, less a small gap. */
    private fun cardArea(): Bounds? {
        val viewport = lastViewport.takeIf { !it.isEmpty } ?: return null
        val side = px(SIDE_MARGIN_DP)
        val gap = px(EDGE_GAP_DP)
        val bottom = keyboardTop?.let { minOf(viewport.bottom, it) } ?: viewport.bottom
        return Bounds(viewport.left + side, viewport.top + gap, viewport.right - side, bottom - gap)
            .takeIf { it.height > 0 && it.width > 0 }
    }

    private fun isLow(at: CardSpot, area: Bounds): Boolean {
        val h = cardHeights[at.density] ?: 0
        return at.top + h / 2 > (area.top + area.bottom) / 2
    }

    /**
     * Something concrete to do when the card cannot be put anywhere clear.
     * Never silence: the card is over the thing, and says so.
     */
    private fun blockedNote(content: CardContent): String {
        val fix = context.getString(
            if (keyboardTop != null) R.string.card_blocked_keyboard else R.string.card_blocked_scroll,
        )
        val movable = (content as? CardContent.Step)?.movable == true ||
            content is CardContent.Choices || content is CardContent.TextQuestion
        return if (movable) fix + " " + context.getString(R.string.card_blocked_move) else fix
    }

    /** True for a card whose own action carries the emphasis, with Pebble on it. */
    private fun cardHasOwnEmphasis(content: CardContent): Boolean = when (content) {
        is CardContent.Step -> content.emphasis == CardEmphasis.DoneButton && content.doneLabel != null && !content.quietDone
        is CardContent.Status -> content.preferred
        is CardContent.Celebration -> true
        is CardContent.Choices, is CardContent.TextQuestion, is CardContent.Recovery -> false
    }

    /** True for a card that will say what it needs kept clear, replacing the last step's. */
    private fun bringsOwnRegions(content: CardContent): Boolean = when (content) {
        is CardContent.Step -> content.waiting == null
        is CardContent.Choices, is CardContent.TextQuestion -> false
        is CardContent.Status, is CardContent.Recovery, is CardContent.Celebration -> true
    }

    /**
     * The step a card belongs to, for its Move and its regions: the general
     * session's own step number where there is one — the same words on a new
     * screen are a new step — and the words otherwise.
     */
    private fun pinKey(content: CardContent): String = when (content) {
        is CardContent.Step -> "${content.step}|${content.instruction}"
        is CardContent.Status -> content.message
        is CardContent.Choices -> "q${content.questionId}|${content.question}"
        is CardContent.TextQuestion -> "q${content.questionId}|${content.question}"
        is CardContent.Recovery -> content.title
        is CardContent.Celebration -> "done|${content.eventId}"
    }

    /** The card measured itself: place it, and show a new one now it can be placed properly. */
    private fun onCardMeasured(heights: Map<CardDensity, Int>, key: String) {
        val current = applied.card?.let { pinKey(it.content) }
        if (key != current) return
        val changed = heights != cardHeights
        cardHeights = heights
        if (measuringFor == key) {
            measuringFor = null
            recheckHandler.removeCallbacks(revealAnyway)
            place("measured")
            reveal()
            return
        }
        if (!changed) return
        val first = spot == null
        place(if (first) "first layout" else "size changed")
        if (first) spot?.let { reveal() }
    }

    /** Should a card never report its size, it is shown anyway rather than left unseen. */
    private val revealAnyway = Runnable {
        if (measuringFor != null) {
            GuideLog.d("overlay") { "card never measured itself; showing it where it is" }
            measuringFor = null
            place("unmeasured")
            if (spot != null) {
                reveal()
            } else {
                // Not even a first size to place it by: shown at the top of
                // the space it may use, where it covers least of most pages,
                // rather than not at all.
                cardArea()?.let { cardWindow.move(OverlayPlacement.CardAt(it.left, it.top, it.width, visible = true)) }
            }
        }
    }

    /** Shows a card that was kept invisible until it had been placed. */
    /**
     * The card's soft shadow, which never leads the card. It is drawn by
     * another window, which redraws sooner than the card's: a shadow put
     * where the card was about to be showed an empty ghost card while the
     * card caught up. So the shadow comes only from the card window's own
     * report that it has been laid out where it was placed — and goes at
     * once whenever the card is to be anywhere else, or unseen.
     */
    private fun shadowFor(rect: Bounds?) {
        if (rect == null || rect != laidOutCard) liveCardRect.value = null
        syncShadow()
    }

    private fun syncShadow() {
        val b = laidOutCard
        val at = spot
        val area = cardArea()
        val ok = b != null && at != null && area != null && applied.card != null && measuringFor == null && !cueShowing &&
            kotlin.math.abs(b.top - at.top) <= 2 && placedRect(area, at)?.let { kotlin.math.abs(it.height - b.height) <= 2 } == true
        liveCardRect.value = if (ok) b!!.toRect() else null
    }

    /** Where a card at [at] is, from its measured height. */
    private fun placedRect(area: Bounds, at: CardSpot): Bounds? =
        cardHeights[at.density]?.let { h -> Bounds(area.left, at.top, area.right, at.top + h) }

    private fun reveal() {
        val area = cardArea() ?: return
        val at = spot ?: return
        if (!cueShowing) shadowFor(placedRect(area, at))
        cardWindow.move(OverlayPlacement.CardAt(area.left, at.top, area.width, visible = !cueShowing))
    }

    /** The window laid out: record where it really is. */
    private fun onCardLaidOut(bounds: Bounds) {
        if (bounds == laidOutCard) return syncShadow()
        val expected = spot?.top
        if (expected != null && bounds.top != expected) {
            GuideLog.d("overlay") { "card window is at top=${bounds.top}, placed at $expected" }
        }
        laidOutCard = bounds
        checkLaidOut(bounds)
        // The shadow follows only a card that can be seen, where it was placed.
        syncShadow()
        reportOverDot()
        commands.onCardMoved()
    }

    /**
     * Where the card really is, checked against what must stay usable. A
     * placement worked out from a height or a position that turned out
     * different is put right, from the real bounds — a few times at most per
     * card, so layout can never chase itself.
     */
    private fun checkLaidOut(bounds: Bounds) {
        if (!placedClear || measuringFor != null || corrections >= MAX_CORRECTIONS) return
        val area = cardArea() ?: return
        val at = spot ?: return
        if (!CardPlacement.covers(area, bounds, clearRegions, px(MIN_REACHABLE_DP))) return
        corrections++
        GuideLog.d("overlay") { "card laid out over what the step needs (attempt $corrections); placing again from its real size" }
        cardHeights = cardHeights + (at.density to bounds.height)
        place("laid out over the step")
    }

    /**
     * "Move": the card goes to the other end of the screen.
     *
     * For when it covers something Dot cannot see — a control the app hides
     * from accessibility, a banner, the app's own keyboard. Where it goes is
     * remembered for this step only; the next step places its card afresh.
     */
    fun moveCard() {
        val card = applied.card ?: return
        val at = spot ?: return
        val area = cardArea() ?: return
        val h = cardHeights[at.density] ?: return
        // Held against the other edge, so it stays there if the keyboard
        // opens or the card grows — for this step only, and never over what
        // the step needs.
        val otherEnd = if (isLow(at, area)) CardSpot(at.density, area.top, CardEdge.Top) else CardSpot(at.density, area.bottom - h, CardEdge.Bottom)
        // Where the other end would cover what the step needs, the nearest
        // clear place that is not where the card is now: Move always moves
        // it somewhere that leaves the step usable, or says it cannot.
        val here = Bounds(area.left, at.top, area.right, at.top + h)
        val elsewhere = CardPlacement.choose(
            area = area,
            heights = cardHeights,
            avoid = clearRegions + here,
            current = null,
            pinned = otherEnd,
            minReachable = px(MIN_REACHABLE_DP),
            prefer = helpfulRegions,
        )
        pinned = if (elsewhere.clear) elsewhere.spot else otherEnd
        pinnedFor = pinKey(card.content)
        if (!elsewhere.clear) GuideLog.d("overlay") { "Move: nowhere else leaves the step usable; staying" }
        place("moved by the person")
    }

    /** The card the "no clear spot" note was shown for; see [place]. */
    private var blockedNoteFor: String? = null

    /** Where the card is while it is being dragged by its handle. */
    private var dragTop: Int? = null

    /**
     * The card dragged by its handle: it follows the finger within the space
     * cards may use, and where it is let go is where it stays for this step —
     * unless that covers what the step needs, when it goes to the nearest
     * place that does not, as Move does.
     */
    fun dragCard(dy: Float, end: Boolean) {
        val card = applied.card ?: return
        val at = spot ?: return
        val area = cardArea() ?: return
        val h = cardHeights[at.density] ?: return
        val from = dragTop ?: at.top
        val top = (from + dy.toInt()).coerceIn(area.top, (area.bottom - h).coerceAtLeast(area.top))
        if (!end) {
            dragTop = top
            cardWindow.move(OverlayPlacement.CardAt(area.left, top, area.width, visible = true))
            shadowFor(null)
            return
        }
        dragTop = null
        pinned = CardSpot(at.density, top, CardEdge.Between)
        pinnedFor = pinKey(card.content)
        place("dragged by the person")
    }

    /**
     * This card goes at the top of the screen, for as long as it is up.
     *
     * For a card over Dot's own screens, whose actions are all in the lower
     * half: there is no control to keep clear of, only a whole half of the
     * screen, and that is a place rather than a region.
     */
    fun keepCardAtTop(overDot: Boolean = false) {
        val card = applied.card ?: return
        pinned = CardSpot(spot?.density ?: CardDensity.Full, Int.MIN_VALUE, CardEdge.Top)
        pinnedFor = pinKey(card.content)
        overDotFor = if (overDot) pinnedFor else null
        place("kept at the top")
        reportOverDot()
    }

    /** Told how far down the screen a card kept over Dot's own screen reaches, or 0. */
    var onCardOverDot: (Int) -> Unit = {}
    private var overDotFor: String? = null
    private var reportedOverDot = 0

    /** Tells Dot how much room to make, from where the card really is. */
    private fun reportOverDot() {
        val key = applied.card?.let { pinKey(it.content) }
        val bottom = if (key != null && key == overDotFor) laidOutCard?.bottom ?: 0 else 0
        if (bottom == reportedOverDot) return
        reportedOverDot = bottom
        onCardOverDot(bottom)
    }

    /**
     * A card that points at nothing goes to the bottom when it first appears.
     *
     * Otherwise it kept whatever place the step before had been pushed to — a
     * question about a code sat at the top of the screen, over the very
     * message saying where the code was sent. Placed once per [key]: the same
     * card shown again stays where the person moved it.
     */
    private fun placeFresh(key: Any?) {
        if (key == null) {
            freshCardFor = null
            return
        }
        if (key == freshCardFor) return
        freshCardFor = key
        spot = null
    }

    private var freshCardFor: Any? = null

    /**
     * Where the keyboard's top edge is, or null when it is closed.
     *
     * A card at the bottom sat on the keys a typing step needs. The keyboard
     * is simply the bottom of the space a card may use, and the card is
     * placed again whenever it opens, closes or changes height.
     */
    fun setKeyboardTop(top: Int?) {
        if (top == keyboardTop) return
        GuideLog.d("overlay") { "keyboard top=$top viewportBottom=${lastViewport.bottom}" }
        keyboardTop = top
        place("keyboard")
    }

    /**
     * The system bars the guidance card must stay clear of.
     *
     * The positional layer is laid out in raw screen pixels — it has to be, so
     * an outline lands exactly where accessibility says the control is — which
     * means nothing keeps the card out from under the status bar but this.
     */
    /**
     * Dot's own opaque layers, so the highlight is never painted over them.
     *
     * A no-op when nothing has actually moved. This is told on every new
     * position — sixty times a second while somebody scrolls — and handing
     * Compose a freshly built list each time made it a change every time, which
     * recomposed the whole highlight and undid the point of keeping positions
     * out of composition. The card moves perhaps twice in a guide.
     */
    fun setInsets(insets: com.aaravsekhri.dot.guide.model.Insets) {
        if (liveInsets.value == insets) return
        liveInsets.value = insets
        // The bars are part of the viewport the card is placed in; a change
        // of navigation style arrives with the new viewport and places the
        // card again there.
    }

    /** Where the scroll guidance card rests, from its own measurement; null until it has one. */
    private var guidanceRest: Bounds? = null

    fun setOccluders(regions: List<Bounds>) {
        if (regions == lastOccluders) return
        lastOccluders = regions
        occluderRects.value = regions.map { it.toRect() }
    }

    private var lastOccluders: List<Bounds> = emptyList()

    /** Empties the acceptance harness's record of what was drawn. */
    fun resetDrawTrace() = DrawTrace.reset()

    /**
     * Writes it out, and then where the outline stands right now.
     *
     * The trace only has entries for frames that were drawn, and a screen
     * nobody is touching draws nothing. The harness needs to be able to ask
     * "where is the outline" at rest — to tap it, among other things — so the
     * current layer is reported too.
     */
    fun dumpDrawTrace() {
        DrawTrace.dump()
        liveHighlight.value?.let { DrawTrace.report(it) }
    }

    fun showDebugVisualRect(region: Bounds?, confidence: Float) {
        debugVisualRect.value = region?.let { it to confidence }
    }

    /**
     * Puts a card up that no guide definition produced.
     *
     * A general session's wording comes from the model at runtime, so it
     * arrives as text rather than as a resource id. The card itself is the one
     * the tutorials use — same shape, same type, same place — because a person
     * being helped should not be able to tell which kind of help it is.
     */
    fun showRuntimeCard(
        label: String,
        instruction: String,
        primary: AiCardAction? = null,
        secondary: AiCardAction? = null,
        didNotWorkLabel: String? = null,
        done: AiCardAction? = null,
        gesture: com.aaravsekhri.dot.guide.ai.Gesture? = null,
        /** The wide button's own words, when the action's standard label is not right. */
        doneText: String? = null,
        /** Set for a card that points at nothing: see [placeFresh]. */
        freshPlacement: Any? = null,
        /** Set while waiting rather than instructing; Pebble waits in the card. */
        waiting: WaitingKind? = null,
        /** The done action as a small fallback link. */
        quietDone: Boolean = false,
        /** One more small link, such as "Change answer". */
        link: AiCardAction? = null,
        /** Where Pebble and the eye go on this card. */
        emphasis: CardEmphasis = CardEmphasis.Card,
        /** A status card's primary action is the one Dot recommends. */
        preferred: Boolean = false,
        /** A pause, a failure, a way back: Pebble is calm and reassuring. */
        reassuring: Boolean = false,
        /** The general session's step this card belongs to; see [CardContent.Step.step]. */
        step: Long = 0,
    ) {
        placeFresh(freshPlacement)
        val content = if (primary == null) {
            CardContent.Step(
                stepLabel = label,
                instruction = instruction,
                doneLabel = doneText ?: done?.let { context.getString(it.labelRes) },
                cannotSeeLabel = didNotWorkLabel,
                showCannotSee = didNotWorkLabel != null,
                doneAction = done?.internal,
                gesture = gesture,
                movable = true,
                waiting = waiting,
                quietDone = quietDone,
                linkLabel = link?.let { context.getString(it.labelRes) },
                linkAction = link?.internal,
                emphasis = emphasis,
                step = step,
            )
        } else {
            CardContent.Status(
                message = instruction,
                actionLabel = context.getString(primary.labelRes),
                action = primary.internal,
                secondaryLabel = secondary?.let { context.getString(it.labelRes) },
                secondaryAction = secondary?.internal,
                preferred = preferred,
                reassuring = reassuring,
            )
        }
        apply(applied.copy(card = CardLayer(content)))
    }

    /**
     * A task, a lesson or a course finished, said honestly from the records:
     * a course is "completed" only when every core lesson is, and otherwise
     * wrapped up with what was done. See [showCelebration].
     */
    fun showCompletion(lesson: com.aaravsekhri.dot.guide.ai.AiGuideHost.LessonTransition?, summary: String, eventId: Long) {
        val a = com.aaravsekhri.dot.guide.ai.AiGuideHost.actionsFor(com.aaravsekhri.dot.guide.ai.AiSessionState.Completed(summary), lesson)
        val progress = lesson?.takeIf { it.total > 0 }?.let { context.resources.getQuantityString(R.plurals.celebrate_progress, it.total, it.completed, it.total) }
        val fraction = lesson?.takeIf { it.total > 0 }?.let { it.completed.toFloat() / it.total }
        fun learned(l: com.aaravsekhri.dot.guide.ai.AiGuideHost.LessonTransition) =
            l.learned.ifBlank { context.getString(R.string.celebrate_lesson_headline, l.lessonTitle) }
        val (headline, detail) = when {
            lesson == null -> context.getString(R.string.celebrate_task_headline) to summary
            lesson.courseDone -> context.getString(R.string.celebrate_course_headline, lesson.courseName) to
                lesson.reinforcement.takeIf(String::isNotBlank)?.let { context.getString(R.string.celebrate_course_detail, it) }.orEmpty()
            lesson.nextLessonTitle != null -> learned(lesson) to context.getString(R.string.celebrate_lesson_next, lesson.nextLessonTitle)
            // Nothing left to do, but not everything done: said as it is.
            else -> learned(lesson) to context.getString(R.string.celebrate_course_wrapup) + " " +
                context.resources.getQuantityString(R.plurals.celebrate_course_wrapup_detail, lesson.total, lesson.completed, lesson.total)
        }
        showCelebration(
            headline = headline,
            detail = detail,
            progress = progress,
            fraction = fraction,
            primary = a.primary ?: AiCardAction.Finish,
            secondary = a.secondary,
            fuller = lesson?.courseDone == true,
            eventId = eventId,
        )
    }

    /** Completions already celebrated, so the same one is never celebrated twice. */
    private val celebrated = mutableSetOf<Long>()

    /**
     * A completion, shown with Pebble pleased and a small burst, once per
     * [eventId]: shown again for the same completion, it is the still pose.
     * Everything else on the screen — an outline, a demonstration, a cue —
     * goes first, so the celebration is the one thing to look at.
     */
    fun showCelebration(
        headline: String,
        detail: String,
        progress: String?,
        fraction: Float?,
        primary: AiCardAction,
        primaryLabel: String? = null,
        secondary: AiCardAction?,
        secondaryLabel: String? = null,
        fuller: Boolean,
        eventId: Long,
    ) {
        clearGestureDemo()
        clearTargetLayers()
        val first = celebrated.add(eventId)
        placeFresh("done|$eventId")
        apply(
            applied.copy(
                card = CardLayer(
                    CardContent.Celebration(
                        headline = headline,
                        detail = detail,
                        progress = progress,
                        fraction = fraction,
                        primaryLabel = primaryLabel ?: context.getString(primary.labelRes),
                        primaryAction = primary.internal,
                        secondaryLabel = secondary?.let { secondaryLabel ?: context.getString(it.labelRes) },
                        secondaryAction = secondary?.internal,
                        fuller = fuller,
                        eventId = eventId,
                        // Already on screen for this completion: keep what
                        // is showing rather than flip it to the still pose.
                        animate = first || (applied.card?.content as? CardContent.Celebration)?.let { it.eventId == eventId && it.animate } == true,
                    ),
                ),
            ),
        )
    }

    /** A question answered on the card, with its answers as buttons. */
    fun showChoices(question: String, options: List<String>, questionId: Int = 0) {
        placeFresh(question to options)
        apply(applied.copy(card = CardLayer(CardContent.Choices(question, options, questionId))))
    }

    /** A question answered in words typed on the card, over whatever app is in front. */
    fun showTextQuestion(question: String, questionId: Int, quickAnswers: List<String> = emptyList(), note: String? = null) {
        placeFresh(question)
        apply(applied.copy(card = CardLayer(CardContent.TextQuestion(question, questionId, quickAnswers, note))))
    }

    /**
     * Shows where to put a finger and which way to move it, on the screen
     * itself. Drawn on the layer that cannot be touched, so the swipe it
     * shows goes straight through to the app.
     */
    fun showGestureDemo(plan: com.aaravsekhri.dot.guide.ai.GesturePlan) {
        if (liveGesture.value == plan) return
        GuideLog.d("overlay") { "gesture demonstration: ${plan.purpose} from ${plan.start.x},${plan.start.y} to ${plan.end.x},${plan.end.y}" }
        liveGesture.value = plan
        showPositionalWindow()
    }

    /** The demonstration stops: the person has started, or the screen changed. */
    fun clearGestureDemo() {
        if (liveGesture.value == null) return
        GuideLog.d("overlay") { "gesture demonstration cleared" }
        liveGesture.value = null
    }

    /** Takes every window down. Safe at any time, including from onInterrupt. */
    fun hideAll() {
        if (applied.card != null) GuideLog.d("overlay") { "every layer hidden, card included" }
        liveHighlight.value = null
        liveGesture.value = null
        apply(OverlayScene())
        // The one place the highlight's window comes down. It is left up
        // through the whole of a guide, drawing nothing when there is nothing
        // to draw; this is the guide ending, or Dot standing down for a
        // password field or a permission dialog, and then it must genuinely go.
        positionalWindow.hide()
        cueShowing = false
        regionSets.clear()
        measuringFor = null
        recheckHandler.removeCallbacks(revealAnyway)
        spot = null
        pinned = null
        overDotFor = null
        reportOverDot()
        searchingSince = 0L
    }

    fun destroy() = hideAll()

    // ------------------------------------------------------ scene building

    /** Pure: guide state in, the complete set of layers out. */
    private fun buildScene(
        state: GuideSessionState,
        definition: GuideDefinition,
        viewport: Bounds,
    ): OverlayScene = when (state) {
        is GuideSessionState.Starting -> OverlayScene(
            card = CardLayer(statusContent(R.string.guide_opening_settings, StatusAction.Pause)),
        )

        // The tap landed and the next page is on its way. The card keeps the
        // step's own words rather than announcing the wait: a sentence that
        // appears for a third of a second and vanishes is noise.
        is GuideSessionState.AwaitingDestination -> OverlayScene(
            card = CardLayer(stepContent(state.stepIndex, definition, adjustmentSeen = false)),
        )

        is GuideSessionState.Searching -> {
            // Nothing positional while looking: a page change leaves a clear
            // screen for a moment rather than a stale outline.
            val now = clock()
            if (searchingSince == 0L) searchingSince = now
            val prolonged = now - searchingSince >= PROLONGED_SEARCH_MS

            OverlayScene(
                card = CardLayer(
                    content = if (prolonged) {
                        statusContent(
                            R.string.guide_looking,
                            StatusAction.Pause,
                            secondary = StatusAction.Exit,
                        )
                    } else {
                        stepContent(state.stepIndex, definition, adjustmentSeen = false)
                    },
                ),
            )
        }

        is GuideSessionState.ScrollNeeded -> {
            // The cue, like the highlight, comes from the positional state
            // machine rather than from here. Two owners of the same pixels is
            // what made them alternate.
            OverlayScene(
                card = CardLayer(stepContent(state.stepIndex, definition, false)),
            )
        }

        is GuideSessionState.TargetFound ->
            targetScene(state.stepIndex, state.region, state.targetGeneration, definition, viewport, false)

        is GuideSessionState.WaitingForUser ->
            targetScene(
                state.stepIndex,
                state.region,
                state.targetGeneration,
                definition,
                viewport,
                state.adjustmentSeen,
            )

        is GuideSessionState.Paused -> OverlayScene(
            card = CardLayer(
                content = statusContent(
                    R.string.guide_paused,
                    StatusAction.Resume,
                    secondary = StatusAction.Exit,
                    actionRes = R.string.guide_resume,
                ),
            ),
        )

        is GuideSessionState.Recovery -> recoveryScene(state, definition)

        is GuideSessionState.Completed -> OverlayScene(
            card = CardLayer(
                content = statusContent(
                    R.string.guide_finished,
                    StatusAction.Finish,
                    actionRes = R.string.guide_done,
                ),
            ),
        )

        GuideSessionState.Inactive,
        is GuideSessionState.Cancelled,
        is GuideSessionState.Failed,
        -> OverlayScene()
    }

    private fun targetScene(
        stepIndex: Int,
        region: SemanticTargetRegion,
        generation: Int,
        definition: GuideDefinition,
        viewport: Bounds,
        adjustmentSeen: Boolean,
    ): OverlayScene {
        // The highlight is no longer built here. It comes from the positional
        // state machine, which owns everything drawn over another app. This
        // decides only the card, because the card's edge depends on where the
        // target is and nothing else in the scene does.
        // Where the card goes is decided after the scene is applied, by the
        // policy every card uses: see [render].
        return OverlayScene(
            card = CardLayer(stepContent(stepIndex, definition, adjustmentSeen, region.label)),
        )
    }

    private fun recoveryScene(
        state: GuideSessionState.Recovery,
        definition: GuideDefinition,
    ): OverlayScene {
        if (state.reason == RecoveryReason.LeftExpectedApp) {
            return OverlayScene(
                card = CardLayer(
                    content = statusContent(
                        R.string.guide_left_settings,
                        StatusAction.ReopenSettings,
                        secondary = StatusAction.Exit,
                        actionRes = R.string.guide_open_settings,
                    ),
                ),
            )
        }

        val body = definition.steps.getOrNull(state.stepIndex)
            ?.let { context.getString(it.fallbackRes) }
            ?: context.getString(R.string.guide_recovery_generic)

        return OverlayScene(
            card = CardLayer(
                content = CardContent.Recovery(
                    title = context.getString(R.string.guide_recovery_title),
                    body = body,
                ),
            ),
        )
    }

    private fun stepContent(
        stepIndex: Int,
        definition: GuideDefinition,
        adjustmentSeen: Boolean,
        matchedLabel: String? = null,
    ): CardContent {
        val step = definition.steps.getOrNull(stepIndex)
            ?: return CardContent.Status("", null, null, null, null)

        // Name the control the way this phone does, when it has told us.
        val targetName = matchedLabel?.takeIf { it.isNotBlank() }
            ?: context.getString(step.targetNameRes)

        return CardContent.Step(
            stepLabel = context.getString(
                R.string.guide_step_counter,
                stepIndex + 1,
                definition.stepCount,
            ),
            instruction = context.getString(step.instructionRes, targetName),
            doneLabel = if (step.action == StepAction.Adjust) {
                context.getString(
                    if (adjustmentSeen) R.string.guide_done_adjusting else R.string.guide_done,
                )
            } else {
                null
            },
        )
    }

    private fun statusContent(
        messageRes: Int,
        action: StatusAction,
        secondary: StatusAction? = null,
        actionRes: Int = R.string.guide_pause,
    ) = CardContent.Status(
        message = context.getString(messageRes),
        actionLabel = context.getString(actionRes),
        action = action,
        secondaryLabel = secondary?.let { context.getString(R.string.guide_exit) },
        secondaryAction = secondary,
    )

    // ----------------------------------------------------------- applying

    /**
     * Makes the windows match [scene], touching only what differs.
     *
     * Every layer is handled the same way — gone means hidden, present means
     * shown or updated — so no state can leave a window behind.
     */
    private fun apply(scene: OverlayScene) {
        if (scene == applied) return

        // Logged when a layer appears or disappears, not when one moves. A
        // target being followed changes this scene on every display frame, and
        // a line of tracing per frame is both unreadable and, on the path that
        // has to keep up with a scroll, not free.
        val shapeChanged = (scene.highlight != null) != (applied.highlight != null) ||
            (scene.guidance != null) != (applied.guidance != null) ||
            scene.card?.content?.javaClass != applied.card?.content?.javaClass
        if (shapeChanged) {
            GuideLog.d("overlay") {
                "scene outline=${scene.highlight != null} guidance=${scene.guidance?.message} " +
                    "card=${scene.card?.content?.javaClass?.simpleName}"
            }
        }

        if (scene.highlight != applied.highlight || scene.guidance != applied.guidance) {
            liveHighlight.value = scene.highlight
            liveGuidance.value = scene.guidance

            // The window goes up as soon as there is anything positional to
            // show and stays up for the rest of the guide, whatever it happens
            // to be drawing.
            //
            // Taking it down and putting it back cost a fresh ComposeView and a
            // fresh composition each time — tens of milliseconds on the main
            // thread — and it is exactly at the moments this window changes what
            // it shows that the screen must change cleanly. An empty
            // transparent window that cannot be touched costs nothing to leave
            // in place.
            val wanted = scene.highlight != null || scene.guidance != null
            if (wanted) showPositionalWindow()
        }

        if (scene.card != applied.card) {
            val card = scene.card
            if (card == null) {
                liveStepCard.value = false to 0
                liveCard.value = null
                shadowFor(null)
                laidOutCard = null
                cardHeights = emptyMap()
                spot = null
                cardWindow.hide()
                reportOverDot()
            } else {
                val before = applied.card?.content
                if (before == null || pinKey(before) != pinKey(card.content)) {
                    // A different step: whatever the person moved belonged
                    // to the last one. A new instruction says what it needs
                    // kept clear, so the last one's regions go; a question or
                    // a wait is about the page the last step was on, and
                    // keeps them — a question about the Post button must not
                    // sit on the Post button.
                    pinned = null
                    pinnedFor = null
                    overDotFor = null
                    corrections = 0
                    if (bringsOwnRegions(card.content)) regionSets.clear()
                    // Unseen until it has measured itself at its real width
                    // and text size; shown the moment it has been placed.
                    measuringFor = pinKey(card.content)
                    // The last card's shadow and bounds go with it.
                    shadowFor(null)
                    laidOutCard = null
                    recheckHandler.removeCallbacks(revealAnyway)
                    recheckHandler.postDelayed(revealAnyway, REVEAL_ANYWAY_MS)
                }
                liveCard.value = card
                // The card's own emphasised action is the thing to look at:
                // the outline stays, restrained, without a second Pebble.
                liveTargetPebble.value = !cardHasOwnEmphasis(card.content)
                // Only a card being typed on may take the keyboard; every
                // other card leaves the app's own focus alone.
                cardWindow.setFocusable(card.content is CardContent.TextQuestion)
                // Placed at once from the last measured size; placed again
                // when this card has measured itself.
                if (cardHeights.isNotEmpty()) place("new card")
                if (!cardWindow.isShowing) {
                    // Put up once, drawing whatever the state says. Later
                    // cards change the state, not the window, so nothing
                    // is rebuilt and nothing replays its entrance.
                    val area = cardArea()
                    cardWindow.show(
                        OverlayPlacement.CardAt(
                            x = area?.left ?: 0,
                            y = spot?.top ?: area?.top ?: 0,
                            width = area?.width ?: android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            // Unseen until it has measured itself and been placed.
                            visible = false,
                        ),
                        onLaidOut = ::onCardLaidOut,
                    ) {
                        DotTheme {
                            val layer = liveCard.value
                            if (layer != null) {
                                val key = pinKey(layer.content)
                                MeasuredCard(
                                    chosen = liveDensity.value,
                                    key = key,
                                    onMeasured = { heights -> recheckHandler.post { onCardMeasured(heights, key) } },
                                ) { density ->
                                    CardContentView(layer.content, density, liveBlockedNote.value)
                                }
                            }
                        }
                    }
                }
                cardWindow.setFocusable(card.content is CardContent.TextQuestion)
                // The shadow lives on the layer nothing can touch.
                showPositionalWindow()
            }
        }

        applied = scene
        reportOverDot()
    }

    /**
     * The full-screen layer that cannot be touched: the outline, Pebble, the
     * scroll cue and the card's shadow. Put up once and left up for the rest
     * of the guide, drawing whatever the state says.
     */
    private fun showPositionalWindow() {
        if (positionalWindow.isShowing) return
        GuideLog.d("overlay") { "positional layer created" }
        positionalWindow.show(OverlayPlacement.FullScreen) {
            DotTheme {
                CardShadow(liveCardRect)
                GestureDemo(liveGesture)
                // The outline's layer is handed over as state, not as a
                // value. Reading it here would recompose this subtree on
                // every change; reading it inside the canvas and the
                // graphics layer re-runs only the drawing.
                TargetHighlight(
                    layer = liveHighlight,
                    appearanceKey = liveAppearanceKey.intValue,
                    canonical = liveCanonical.value,
                    occluders = occluderRects.value,
                    showPebble = liveTargetPebble,
                    reading = liveReading,
                )
                GuidanceSlot(
                    guidance = liveGuidance.value,
                    insets = liveInsets.value,
                    label = ::guidanceText,
                    stepCardAtBottom = liveStepCard.value.first,
                    stepCardHeightPx = liveStepCard.value.second,
                    onRest = { guidanceRest = it },
                )
                debugVisualRect.value?.let { (region, confidence) ->
                    VisualTrackingDebugOverlay(
                        trackedRect = region.toRect(),
                        confidence = confidence,
                    )
                }
            }
        }
    }

    @Composable
    private fun CardContentView(content: CardContent, strip: CardDensity, blockedNote: String?) {
        // The strip is drawn for a step; every other card keeps its compact
        // form in the strip's place, so it is never cut short.
        if (strip == CardDensity.Strip && content is CardContent.Step) {
            val onDone = content.doneLabel?.let {
                content.doneAction?.let { action ->
                    {
                        when (action) {
                            StatusAction.DoneTyping -> commands.onDoneTyping(content.step)
                            StatusAction.TeachingNext -> commands.onTeachingNext(content.step)
                            else -> invoke(action)
                        }
                    }
                } ?: { commands.onConfirmDone() }
            }
            StripStepCard(
                instruction = content.instruction,
                doneLabel = content.doneLabel?.takeUnless { content.quietDone },
                onDone = onDone.takeUnless { content.quietDone },
                moveLabel = if (content.movable) context.getString(R.string.ai_move_card) else null,
                onMove = if (content.movable) ::moveCard else null,
                pauseLabel = context.getString(R.string.guide_pause),
                onPause = commands::onPause,
                onDrag = if (content.movable) ::dragCard else null,
                waiting = content.waiting != null,
                endLabel = context.getString(R.string.guide_exit),
                onEnd = commands::onExit,
            )
            return
        }
        val density = if (strip == CardDensity.Strip) CardDensity.Compact else strip
        when (content) {
            is CardContent.Step -> StepCard(
                stepLabel = content.stepLabel,
                instruction = content.instruction,
                cannotSeeLabel = when {
                    !content.showCannotSee -> null
                    else -> content.cannotSeeLabel ?: context.getString(R.string.guide_cannot_see)
                },
                pauseLabel = context.getString(R.string.guide_pause),
                onCannotSee = commands::onCannotSeeTarget,
                onPause = commands::onPause,
                doneLabel = content.doneLabel,
                onDone = content.doneLabel?.let {
                    content.doneAction?.let { action ->
                        {
                            // Typing confirmed: said with the step it was for,
                            // so a tap on a card that has gone does nothing.
                            when (action) {
                                StatusAction.DoneTyping -> commands.onDoneTyping(content.step)
                                StatusAction.TeachingNext -> commands.onTeachingNext(content.step)
                                else -> invoke(action)
                            }
                        }
                    } ?: { commands.onConfirmDone() }
                },
                // Shown on the screen itself, where the finger goes: the card's
                // own small drawing would be a second demonstration beside it.
                gesture = content.gesture.takeIf { liveGesture.value == null },
                moveLabel = if (content.movable) context.getString(R.string.ai_move_card) else null,
                onMove = if (content.movable) ::moveCard else null,
                waiting = content.waiting,
                quietDone = content.quietDone,
                linkLabel = content.linkLabel,
                onLink = content.linkAction?.let { action -> { invoke(action) } },
                // A demonstration on the screen is where the eye goes: the
                // card's own Pebble steps back, so there is one Pebble.
                emphasis = if (liveGesture.value != null && content.emphasis == CardEmphasis.Card) CardEmphasis.Target else content.emphasis,
                density = density,
                blockedNote = blockedNote,
                onDrag = if (content.movable) ::dragCard else null,
                endLabel = context.getString(R.string.guide_exit),
                onEnd = commands::onExit,
            )

            is CardContent.Status -> StatusCard(
                message = content.message,
                actionLabel = content.actionLabel,
                onAction = content.action?.let { { invoke(it) } },
                secondaryLabel = content.secondaryLabel,
                onSecondary = content.secondaryAction?.let { { invoke(it) } },
                preferred = content.preferred,
                reassuring = content.reassuring,
                density = density,
                blockedNote = blockedNote,
                // Every card of a running guide can end it, except one that
                // already does, and the ones that say the guide is done.
                endLabel = context.getString(R.string.guide_exit).takeUnless { content.endsOrCompletes() },
                onEnd = commands::onExit,
            )

            is CardContent.Choices -> ChoiceCard(
                question = content.question,
                options = content.options,
                onChoose = { index -> commands.onChoice(index, content.questionId) },
                pauseLabel = context.getString(R.string.guide_pause),
                onPause = commands::onPause,
                density = density,
                blockedNote = blockedNote,
                moveLabel = context.getString(R.string.ai_move_card),
                onMove = ::moveCard,
                endLabel = context.getString(R.string.guide_exit),
                onEnd = commands::onExit,
            )

            is CardContent.TextQuestion -> TextQuestionCard(
                question = content.question,
                sendLabel = context.getString(R.string.ai_answer_send),
                fieldDescription = context.getString(R.string.ai_answer_field),
                onSend = { text -> commands.onAnswerText(text, content.questionId) },
                quickAnswers = content.quickAnswers,
                note = content.note,
                pauseLabel = context.getString(R.string.guide_pause),
                onPause = commands::onPause,
                moveLabel = context.getString(R.string.ai_move_card),
                onMove = ::moveCard,
                density = density,
                blockedNote = blockedNote,
                endLabel = context.getString(R.string.guide_exit),
                onEnd = commands::onExit,
            )

            is CardContent.Celebration -> CelebrationCard(
                content = content,
                onPrimary = { invoke(content.primaryAction) },
                onSecondary = content.secondaryAction?.let { action -> { invoke(action) } },
                density = density,
                blockedNote = blockedNote,
            )

            is CardContent.Recovery -> RecoveryCard(
                title = content.title,
                body = content.body,
                primaryLabel = context.getString(R.string.guide_find_it),
                secondaryLabel = context.getString(R.string.guide_go_back),
                closeDescription = context.getString(R.string.guide_exit),
                onPrimary = commands::onRetry,
                onSecondary = commands::onGoBackAStep,
                onClose = commands::onExit,
            )
        }
    }

    private fun CardContent.Status.endsOrCompletes(): Boolean = listOf(action, secondaryAction).any {
        it == StatusAction.Exit || it == StatusAction.Finish || it == StatusAction.NextLesson || it == StatusAction.OpenCourse
    }

    private fun invoke(action: StatusAction) = when (action) {
        StatusAction.Pause -> commands.onPause()
        StatusAction.Resume -> commands.onResume()
        StatusAction.Exit -> commands.onExit()
        StatusAction.Finish -> commands.onFinish()
        StatusAction.ReopenSettings -> commands.onReopenSettings()
        StatusAction.ContinueAfterHandoff -> commands.onContinueAfterHandoff()
        StatusAction.RetryPlanning -> commands.onRetryPlanning()
        StatusAction.AnswerInApp -> commands.onAnswerInApp()
        StatusAction.DoneTyping -> commands.onDoneTyping()
        StatusAction.TeachingNext -> commands.onTeachingNext()
        StatusAction.TryAnotherWay -> commands.onTryAnotherWay()
        StatusAction.KeepWaiting -> commands.onKeepWaiting()
        StatusAction.ChangeAnswer -> commands.onChangeAnswer()
        StatusAction.TryLater -> commands.onTryLater()
        StatusAction.KeepTrying -> commands.onKeepTrying()
        StatusAction.NextLesson -> commands.onNextLesson()
        StatusAction.OpenCourse -> commands.onOpenCourse()
        StatusAction.CheckAgain -> commands.onCheckAgain()
    }

    // ------------------------------------------------------------ helpers

    private fun px(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        context.resources.displayMetrics,
    ).toInt()

    private companion object {
        /** Room left round a control for a finger, besides the outline's own padding. */
        const val TOUCH_MARGIN_DP = 12f

        /** The least of an oversized control's middle that must stay uncovered. */
        const val MIN_REACHABLE_DP = 96f

        /** A target moving by less than this, into its finger margin, does not move a card already in place. */
        const val HYSTERESIS_DP = 6f

        /** Corrections after layout, per card, before the placement is left as it is. */
        const val MAX_CORRECTIONS = 2

        /** How long a new card may stay unseen waiting to measure itself. */
        const val REVEAL_ANYWAY_MS = 300L

        /** Space between the card and the sides of the screen. */
        const val SIDE_MARGIN_DP = 12f

        /** Space between the card and the bars, the keyboard, or the top and bottom. */
        const val EDGE_GAP_DP = 8f

        /**
         * How long a search may run before Dot says out loud that it is
         * looking. Comfortably longer than an ordinary page transition.
         */
        const val PROLONGED_SEARCH_MS = 2_000L

        /** Half-height masked out for the centred scroll cue. */
        /** A generous band at whichever edge the guidance card is on. */
        const val GUIDANCE_MASK_HEIGHT = 420
    }
}

/** Clamps a rectangle inside another, keeping it non-empty. */
internal fun Bounds.clampInto(viewport: Bounds): Bounds = Bounds(
    left = left.coerceIn(viewport.left, viewport.right),
    top = top.coerceIn(viewport.top, viewport.bottom),
    right = right.coerceIn(viewport.left, viewport.right),
    bottom = bottom.coerceIn(viewport.top, viewport.bottom),
)

internal fun Bounds.toRect() = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
