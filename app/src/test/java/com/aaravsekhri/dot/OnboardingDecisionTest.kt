package com.aaravsekhri.dot

import com.aaravsekhri.dot.onboarding.OnboardingStep
import com.aaravsekhri.dot.onboarding.OnboardingStore
import com.aaravsekhri.dot.onboarding.OnboardingStore.Decision
import com.aaravsekhri.dot.onboarding.OnboardingStore.ExistingUserSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingDecisionTest {

    private val none = ExistingUserSignals(false, false, false, false)

    @Test
    fun `someone new is shown the introduction`() {
        assertEquals(Decision.Show, OnboardingStore.decide(completed = false, signals = none))
    }

    @Test
    fun `someone who has already used Dot is not made to sit through it`() {
        assertEquals(Decision.SkipExistingUser, OnboardingStore.decide(false, none.copy(hasCourseProgress = true)))
        assertEquals(Decision.SkipExistingUser, OnboardingStore.decide(false, none.copy(acceptedCloudGuidance = true)))
        assertEquals(Decision.SkipExistingUser, OnboardingStore.decide(false, none.copy(acceptedDisclosure = true)))
        assertEquals(Decision.SkipExistingUser, OnboardingStore.decide(false, none.copy(hasSavedSession = true)))
    }

    @Test
    fun `finished stays finished`() {
        assertEquals(Decision.AlreadyDone, OnboardingStore.decide(completed = true, signals = none))
    }

    @Test
    fun `steps go forwards and back in order, and an unknown stored step starts at the beginning`() {
        assertEquals(OnboardingStep.Practice, OnboardingStep.MeetPebble.next)
        assertEquals(OnboardingStep.EnableGuidance, OnboardingStep.Ready.previous)
        assertNull(OnboardingStep.Ready.next)
        assertNull(OnboardingStep.MeetPebble.previous)
        assertEquals(OnboardingStep.EnableGuidance, OnboardingStep.of("enable"))
        assertEquals(OnboardingStep.MeetPebble, OnboardingStep.of("something-old"))
    }
}
