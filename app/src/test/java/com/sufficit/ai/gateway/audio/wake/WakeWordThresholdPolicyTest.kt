package com.sufficit.ai.gateway.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordThresholdPolicyTest {
    @Test
    fun automaticProfileReceivesOperationalFloor() {
        assertEquals(
            0.20,
            WakeWordThresholdPolicy.resolveAutomaticUpdate(
                currentThreshold = 0.18,
                suggestedThreshold = 0.18,
                automatic = true
            )!!,
            0.0001
        )
    }

    @Test
    fun automaticProfileAcceptsMeaningfulSuggestion() {
        assertEquals(
            0.27,
            WakeWordThresholdPolicy.resolveAutomaticUpdate(
                currentThreshold = 0.20,
                suggestedThreshold = 0.27,
                automatic = true
            )!!,
            0.0001
        )
    }

    @Test
    fun smallOscillationDoesNotRewriteConfiguration() {
        assertNull(
            WakeWordThresholdPolicy.resolveAutomaticUpdate(
                currentThreshold = 0.20,
                suggestedThreshold = 0.205,
                automatic = true
            )
        )
    }

    @Test
    fun manualProfileIsNeverChanged() {
        assertNull(
            WakeWordThresholdPolicy.resolveAutomaticUpdate(
                currentThreshold = 0.12,
                suggestedThreshold = 0.30,
                automatic = false
            )
        )
    }
}
