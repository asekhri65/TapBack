package com.aaravsekhri.dot.onboarding

import android.content.Context
import androidx.core.content.edit

/** The first-run steps, in order. Stored by [wire] name, so a later reorder cannot misread an old value. */
enum class OnboardingStep(val wire: String) {
    MeetPebble("meet"),
    Practice("practice"),
    EnableGuidance("enable"),
    Ready("ready"),
    ;

    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)
    val previous: OnboardingStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        fun of(wire: String?): OnboardingStep = entries.firstOrNull { it.wire == wire } ?: MeetPebble
    }
}

/**
 * Where somebody is in Dot's first-run introduction, kept across a trip to
 * Android's settings, a rotation, or the process going while they were away.
 *
 * Only which step, whether the practice tap was made, and whether it is
 * finished. Nothing about the person.
 */
class OnboardingStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var completed: Boolean
        get() = preferences.getBoolean(KEY_COMPLETED, false)
        set(value) = preferences.edit { putBoolean(KEY_COMPLETED, value) }

    var step: OnboardingStep
        get() = OnboardingStep.of(preferences.getString(KEY_STEP, null))
        set(value) = preferences.edit { putString(KEY_STEP, value.wire) }

    /** The practice tap was made: its celebration is not asked for again after a rotation. */
    var practiced: Boolean
        get() = preferences.getBoolean(KEY_PRACTICED, false)
        set(value) = preferences.edit { putBoolean(KEY_PRACTICED, value) }

    /**
     * Android's settings were opened from the access step: coming back
     * without access is "not on yet", not the first explanation again —
     * across a rotation or the process going while they were away.
     */
    var wentToSettings: Boolean
        get() = preferences.getBoolean(KEY_WENT_TO_SETTINGS, false)
        set(value) = preferences.edit { putBoolean(KEY_WENT_TO_SETTINGS, value) }

    /** Whether to show the introduction now; an existing user is marked finished instead. */
    fun shouldShow(signals: ExistingUserSignals): Boolean {
        if (completed) return false
        // Begun here, then interrupted — consent given on the access step,
        // then the process went: carried on, not mistaken for an old user.
        if (preferences.contains(KEY_STEP)) return true
        if (decide(completed = false, signals = signals) == Decision.SkipExistingUser) {
            completed = true
            return false
        }
        return true
    }

    /** "Replay introduction" from Settings: from the start, with nothing else reset. */
    fun replay() = preferences.edit {
        putBoolean(KEY_COMPLETED, false)
        putString(KEY_STEP, OnboardingStep.MeetPebble.wire)
        putBoolean(KEY_PRACTICED, false)
        putBoolean(KEY_WENT_TO_SETTINGS, false)
    }

    fun finish() = preferences.edit {
        putBoolean(KEY_COMPLETED, true)
        putString(KEY_STEP, OnboardingStep.Ready.wire)
    }

    /**
     * What an existing install already shows about its person, read before
     * this introduction existed: they have used Dot, so the welcome is not
     * for them.
     */
    data class ExistingUserSignals(
        val acceptedDisclosure: Boolean,
        val acceptedCloudGuidance: Boolean,
        val hasCourseProgress: Boolean,
        val hasSavedSession: Boolean,
    ) {
        val any: Boolean get() = acceptedDisclosure || acceptedCloudGuidance || hasCourseProgress || hasSavedSession
    }

    enum class Decision { Show, AlreadyDone, SkipExistingUser }

    companion object {
        private const val NAME = "dot_onboarding"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_STEP = "step"
        private const val KEY_PRACTICED = "practiced"
        private const val KEY_WENT_TO_SETTINGS = "went_to_settings"

        /** Pure, so the rule is tested rather than trusted. */
        fun decide(completed: Boolean, signals: ExistingUserSignals): Decision = when {
            completed -> Decision.AlreadyDone
            signals.any -> Decision.SkipExistingUser
            else -> Decision.Show
        }
    }
}
