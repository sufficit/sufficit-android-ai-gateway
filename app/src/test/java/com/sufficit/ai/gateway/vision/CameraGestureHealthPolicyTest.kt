package com.sufficit.ai.gateway.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGestureHealthPolicyTest {
    @Test
    fun `recovers when resumed chat has no frames past timeout`() {
        assertTrue(
            CameraGestureHealthPolicy.shouldRecover(
                running = true,
                interactionActive = true,
                lifecycleResumed = true,
                nowElapsedMs = 10_000L,
                sessionStartedAtElapsedMs = 1_000L,
                lastFrameAtElapsedMs = 4_000L,
                lastRecoveryAtElapsedMs = 0L,
                stallTimeoutMs = 4_500L,
                recoveryCooldownMs = 6_000L
            )
        )
    }

    @Test
    fun `does not recover while banner or lifecycle overlay pauses activity`() {
        assertFalse(
            CameraGestureHealthPolicy.shouldRecover(
                running = true,
                interactionActive = true,
                lifecycleResumed = false,
                nowElapsedMs = 10_000L,
                sessionStartedAtElapsedMs = 1_000L,
                lastFrameAtElapsedMs = 2_000L,
                lastRecoveryAtElapsedMs = 0L,
                stallTimeoutMs = 4_500L,
                recoveryCooldownMs = 6_000L
            )
        )
    }

    @Test
    fun `does not recover when frames are current`() {
        assertFalse(
            CameraGestureHealthPolicy.shouldRecover(
                running = true,
                interactionActive = true,
                lifecycleResumed = true,
                nowElapsedMs = 10_000L,
                sessionStartedAtElapsedMs = 1_000L,
                lastFrameAtElapsedMs = 9_000L,
                lastRecoveryAtElapsedMs = 0L,
                stallTimeoutMs = 4_500L,
                recoveryCooldownMs = 6_000L
            )
        )
    }

    @Test
    fun `backs off repeated recovery attempts`() {
        assertFalse(
            CameraGestureHealthPolicy.shouldRecover(
                running = true,
                interactionActive = true,
                lifecycleResumed = true,
                nowElapsedMs = 10_000L,
                sessionStartedAtElapsedMs = 1_000L,
                lastFrameAtElapsedMs = 2_000L,
                lastRecoveryAtElapsedMs = 7_000L,
                stallTimeoutMs = 4_500L,
                recoveryCooldownMs = 6_000L
            )
        )
    }
}
