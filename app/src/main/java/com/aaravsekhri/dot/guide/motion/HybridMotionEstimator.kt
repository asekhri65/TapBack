package com.aaravsekhri.dot.guide.motion

import kotlin.math.abs
import kotlin.math.max

/**
 * Decides what the screen actually did, from sources that each know part of it.
 *
 * The rules below are deliberately explicit rather than a weighted average.
 * Averaging is the wrong instinct here: when two sources disagree about a
 * scroll, the truth is almost never halfway between them — one of them is
 * simply wrong, and drawing the midpoint puts the outline somewhere neither
 * source ever claimed. So a disagreement produces *uncertainty*, not a blend.
 *
 * The ranking reflects what each source is actually good at:
 *
 *  - An accessibility scroll delta is an exact number reported by the view
 *    that scrolled. When one arrives it is simply correct, and it arrives
 *    before anything has been drawn.
 *  - Global visual motion is continuous and survives flings, but it is an
 *    estimate, and it cannot tell scrolling from a page transition on its own.
 *  - A local template match is precise but parochial: on a list of near
 *    identical rows it can be confidently wrong by exactly one row. It is
 *    therefore only ever allowed to nudge, never to carry the movement.
 *  - A semantic read is the truth, and is far too late to draw from. It
 *    confirms and corrects.
 *
 * Plain Kotlin and a monotonic clock passed in, so every rule here is unit
 * tested rather than argued about.
 */
class HybridMotionEstimator(
    /** Replaced when a target is adopted, so a row's height can inform the rules. */
    var config: Config = Config(),
) {

    data class Config(
        /** How long a scroll delta still describes the present. */
        val scrollFreshnessNanos: Long = 140_000_000L,
        /** How long a visual estimate still describes the present. */
        val visualFreshnessNanos: Long = 120_000_000L,
        /** Screen pixels two sources may differ by and still be agreeing. */
        val agreementTolerancePx: Float = 24f,
        /** Beyond this fraction of the movement, a difference is a disagreement. */
        val agreementToleranceFraction: Float = 0.35f,
        /** The most a local match may move the outline on its own. */
        val maxLocalCorrectionPx: Float = 40f,
        /** A row's height, so a one-row local jump can be named when refused. */
        val rowHeightPx: Float = 0f,
        /** How close to a row height a refused jump must be to be counted as one. */
        val rowJumpTolerancePx: Float = 60f,
        /** How long vision may carry the target before something must confirm it. */
        val confirmationWindowNanos: Long = 400_000_000L,
        /** How long a contradiction is tolerated before the target is given up. */
        val disagreementGraceNanos: Long = 120_000_000L,
        /** Below this, a visual estimate is not worth acting on at all. */
        val minimumVisualConfidence: Float = 0.5f,
    )

    private var pendingScrollDx = 0f
    private var pendingScrollDy = 0f
    private var scrollAtNanos = 0L

    private var pendingGlobalDx = 0f
    private var pendingGlobalDy = 0f
    private var globalConfidence = 0f
    private var globalAtNanos = 0L

    /** The capture timestamp of the oldest global measurement still pending. */
    private var globalFrameNanos = 0L

    /** The local match's residual: observed minus predicted, not a displacement. */
    private var localResidualDx = 0f
    private var localResidualDy = 0f
    private var localConfidence = 0f
    private var localAtNanos = 0L

    /** Nothing older than this may be applied; it describes a screen already gone. */
    private var lastAppliedNanos = 0L

    private var awaitingConfirmationSince = 0L
    private var disagreementSince = 0L
    private var uncertainSince = 0L

    /** Counted for the report: local matches that tried to jump a whole row. */
    var suppressedRowJumps = 0L
        private set

    /** Counted for the report: times the sources contradicted each other. */
    var disagreements = 0L
        private set

    @Synchronized
    fun reset() {
        pendingScrollDx = 0f
        pendingScrollDy = 0f
        scrollAtNanos = 0L
        pendingGlobalDx = 0f
        pendingGlobalDy = 0f
        globalConfidence = 0f
        globalAtNanos = 0L
        clearLocal()
        lastAppliedNanos = 0L
        awaitingConfirmationSince = 0L
        disagreementSince = 0L
        uncertainSince = 0L
    }

    /**
     * Takes a source's account of the movement.
     *
     * Scroll and global signals are *displacements* and accumulate, so a burst
     * between two display frames becomes one movement. A local signal is a
     * *residual* and replaces whatever came before it, because only the newest
     * measurement of the drift is meaningful.
     */
    @Synchronized
    fun offer(signal: MotionSignal) {
        // A frame captured before movement already drawn describes the past.
        // Letting it through would drag the outline backwards, which is the
        // single most visible failure this whole arrangement exists to avoid.
        if (signal.timestampNanos < lastAppliedNanos) return

        when (signal.source) {
            MotionSource.ACCESSIBILITY_SCROLL_DELTA -> {
                pendingScrollDx += signal.dx
                pendingScrollDy += signal.dy
                scrollAtNanos = max(scrollAtNanos, signal.timestampNanos)
                awaitingConfirmationSince = 0L
            }

            MotionSource.GLOBAL_VISUAL_MOTION -> {
                if (signal.confidence < config.minimumVisualConfidence) return
                if (pendingGlobalDx == 0f && pendingGlobalDy == 0f) {
                    globalFrameNanos = signal.timestampNanos
                }
                pendingGlobalDx += signal.dx
                pendingGlobalDy += signal.dy
                globalConfidence = signal.confidence
                globalAtNanos = max(globalAtNanos, signal.timestampNanos)
            }

            MotionSource.LOCAL_TARGET_MATCH -> {
                if (signal.confidence < config.minimumVisualConfidence) return
                localResidualDx = signal.dx
                localResidualDy = signal.dy
                localConfidence = signal.confidence
                localAtNanos = signal.timestampNanos
                awaitingConfirmationSince = 0L
            }

            MotionSource.SEMANTIC_RECONCILIATION -> {
                // Contributes no displacement — the authoritative region is
                // applied directly by the target tracker. It counts as proof
                // that vision has not wandered off.
                awaitingConfirmationSince = 0L
            }
        }
    }

    /** How long the fused answer has been untrustworthy, for the hide decision. */
    fun uncertainDurationNanos(nowNanos: Long): Long =
        if (uncertainSince == 0L) 0L else nowNanos - uncertainSince

    /**
     * Resolves everything offered since the last call into one movement.
     *
     * Consumes what it applies, so calling this once per display frame is what
     * turns a burst of events into a single translation.
     */
    @Synchronized
    fun fuse(nowNanos: Long): FusionResult {
        val scrollFresh = scrollAtNanos != 0L &&
            nowNanos - scrollAtNanos <= config.scrollFreshnessNanos &&
            (pendingScrollDx != 0f || pendingScrollDy != 0f)

        val globalFresh = globalAtNanos != 0L &&
            nowNanos - globalAtNanos <= config.visualFreshnessNanos &&
            (pendingGlobalDx != 0f || pendingGlobalDy != 0f)

        val localFresh = localAtNanos != 0L &&
            nowNanos - localAtNanos <= config.visualFreshnessNanos

        if (!scrollFresh) dropStaleScroll(nowNanos)
        if (!globalFresh) dropStaleGlobal(nowNanos)

        return when {
            scrollFresh && globalFresh -> fuseScrollWithGlobal(nowNanos, localFresh)
            scrollFresh -> applyScrollAlone(nowNanos, localFresh)
            globalFresh -> applyGlobalAlone(nowNanos, localFresh)
            localFresh -> applyLocalAlone(nowNanos)
            else -> starve(nowNanos)
        }
    }

    // ------------------------------------------------------------- rules

    /**
     * Both sources spoke. They are measuring the same thing, so the question is
     * whether they agree — not what their average is.
     */
    private fun fuseScrollWithGlobal(nowNanos: Long, localFresh: Boolean): FusionResult {
        val agreesX = agrees(pendingScrollDx, pendingGlobalDx)
        val agreesY = agrees(pendingScrollDy, pendingGlobalDy)

        if (!agreesX || !agreesY) {
            if (disagreementSince == 0L) {
                disagreementSince = nowNanos
                disagreements++
            }
            // Held briefly: one more event or frame usually resolves it. If it
            // does not, both are dropped and the caller hides the outline.
            if (nowNanos - disagreementSince > config.disagreementGraceNanos) {
                pendingScrollDx = 0f
                pendingScrollDy = 0f
                pendingGlobalDx = 0f
                pendingGlobalDy = 0f
            }
            return uncertain(
                nowNanos,
                setOf(MotionSource.ACCESSIBILITY_SCROLL_DELTA, MotionSource.GLOBAL_VISUAL_MOTION),
            )
        }

        disagreementSince = 0L
        // They agree, so the exact number is preferred over the estimate.
        val dx = pendingScrollDx
        val dy = pendingScrollDy
        val sources = mutableSetOf(
            MotionSource.ACCESSIBILITY_SCROLL_DELTA,
            MotionSource.GLOBAL_VISUAL_MOTION,
        )
        val rejected = mutableSetOf<MotionSource>()
        val correction = takeLocalCorrection(localFresh, sources, rejected)

        consumeScroll()
        consumeGlobal()
        return confident(nowNanos, dx + correction.first, dy + correction.second, 1f, sources, rejected)
    }

    private fun applyScrollAlone(nowNanos: Long, localFresh: Boolean): FusionResult {
        disagreementSince = 0L
        val dx = pendingScrollDx
        val dy = pendingScrollDy
        val sources = mutableSetOf(MotionSource.ACCESSIBILITY_SCROLL_DELTA)
        val rejected = mutableSetOf<MotionSource>()
        val correction = takeLocalCorrection(localFresh, sources, rejected)

        consumeScroll()
        return confident(nowNanos, dx + correction.first, dy + correction.second, 1f, sources, rejected)
    }

    /**
     * Vision is carrying the target, which is exactly what it is for during a
     * fling — but it is on probation: something authoritative has to agree
     * before long, or the target is no longer trustworthy.
     */
    private fun applyGlobalAlone(nowNanos: Long, localFresh: Boolean): FusionResult {
        disagreementSince = 0L
        if (awaitingConfirmationSince == 0L) awaitingConfirmationSince = nowNanos

        val dx = pendingGlobalDx
        val dy = pendingGlobalDy
        val confidence = globalConfidence
        val sourceNanos = if (globalFrameNanos != 0L) globalFrameNanos else nowNanos
        val sources = mutableSetOf(MotionSource.GLOBAL_VISUAL_MOTION)
        val rejected = mutableSetOf<MotionSource>()
        val correction = takeLocalCorrection(localFresh, sources, rejected)
        consumeGlobal()

        if (nowNanos - awaitingConfirmationSince > config.confirmationWindowNanos) {
            return uncertain(nowNanos, setOf(MotionSource.GLOBAL_VISUAL_MOTION))
        }

        uncertainSince = 0L
        lastAppliedNanos = nowNanos
        return FusionResult(
            verdict = FusionVerdict.Tentative,
            motion = FusedMotionEstimate(
                dx = dx + correction.first,
                dy = dy + correction.second,
                confidence = confidence,
                sources = sources,
                timestampNanos = nowNanos,
                sourceTimestampNanos = sourceNanos,
            ),
            rejected = rejected,
        )
    }

    /** Nothing moved in bulk; the target has only drifted. Nudge it back. */
    private fun applyLocalAlone(nowNanos: Long): FusionResult {
        val sources = mutableSetOf<MotionSource>()
        val rejected = mutableSetOf<MotionSource>()
        val correction = takeLocalCorrection(true, sources, rejected)

        if (sources.isEmpty()) {
            // The only thing on offer was refused. Nothing moves.
            return FusionResult(FusionVerdict.Tentative, null, rejected)
        }
        uncertainSince = 0L
        lastAppliedNanos = nowNanos
        return FusionResult(
            verdict = FusionVerdict.Confident,
            motion = FusedMotionEstimate(
                dx = correction.first,
                dy = correction.second,
                confidence = localConfidence,
                sources = sources,
                timestampNanos = nowNanos,
            ),
            rejected = rejected,
        )
    }

    private fun starve(nowNanos: Long): FusionResult {
        if (uncertainSince == 0L) uncertainSince = nowNanos
        return FusionResult(FusionVerdict.Starved, null)
    }

    // --------------------------------------------------------- mechanics

    /**
     * The local match's contribution, if the rules allow one.
     *
     * A residual larger than the cap is not a correction, it is a different
     * opinion about which row the target is — and on a Settings list that is
     * exactly the mistake that must never reach the screen.
     */
    private fun takeLocalCorrection(
        localFresh: Boolean,
        sources: MutableSet<MotionSource>,
        rejected: MutableSet<MotionSource>,
    ): Pair<Float, Float> {
        if (!localFresh) return 0f to 0f
        val dx = localResidualDx
        val dy = localResidualDy
        val magnitude = max(abs(dx), abs(dy))

        if (magnitude > config.maxLocalCorrectionPx) {
            if (looksLikeARowJump(dy)) suppressedRowJumps++
            rejected += MotionSource.LOCAL_TARGET_MATCH
            clearLocal()
            return 0f to 0f
        }

        sources += MotionSource.LOCAL_TARGET_MATCH
        clearLocal()
        return dx to dy
    }

    private fun looksLikeARowJump(dy: Float): Boolean =
        config.rowHeightPx > 0f && abs(abs(dy) - config.rowHeightPx) <= config.rowJumpTolerancePx

    private fun agrees(exact: Float, estimate: Float): Boolean {
        val tolerance = max(
            config.agreementTolerancePx,
            abs(exact) * config.agreementToleranceFraction,
        )
        return abs(exact - estimate) <= tolerance
    }

    private fun confident(
        nowNanos: Long,
        dx: Float,
        dy: Float,
        confidence: Float,
        sources: Set<MotionSource>,
        rejected: Set<MotionSource>,
    ): FusionResult {
        uncertainSince = 0L
        lastAppliedNanos = nowNanos
        return FusionResult(
            verdict = FusionVerdict.Confident,
            motion = FusedMotionEstimate(dx, dy, confidence, sources, nowNanos),
            rejected = rejected,
        )
    }

    private fun uncertain(nowNanos: Long, involved: Set<MotionSource>): FusionResult {
        if (uncertainSince == 0L) uncertainSince = nowNanos
        return FusionResult(FusionVerdict.Uncertain, null, involved)
    }

    private fun consumeScroll() {
        pendingScrollDx = 0f
        pendingScrollDy = 0f
    }

    private fun consumeGlobal() {
        pendingGlobalDx = 0f
        pendingGlobalDy = 0f
        globalFrameNanos = 0L
    }

    private fun clearLocal() {
        localResidualDx = 0f
        localResidualDy = 0f
        localConfidence = 0f
        localAtNanos = 0L
    }

    private fun dropStaleScroll(nowNanos: Long) {
        if (scrollAtNanos != 0L && nowNanos - scrollAtNanos > config.scrollFreshnessNanos) {
            consumeScroll()
        }
    }

    private fun dropStaleGlobal(nowNanos: Long) {
        if (globalAtNanos != 0L && nowNanos - globalAtNanos > config.visualFreshnessNanos) {
            consumeGlobal()
            globalConfidence = 0f
        }
    }
}
