package com.sufficit.ai.gateway.vision

/**
 * Pure decision used by the camera watchdog.
 *
 * CameraX can keep a use case logically bound while Android temporarily
 * refuses the physical camera (for example while the lock screen is being
 * dismissed). In that state no exception reaches the original bind call and
 * the recognizer used to remain marked as running forever without frames.
 */
internal object CameraGestureHealthPolicy {
    fun shouldRecover(
        running: Boolean,
        interactionActive: Boolean,
        lifecycleResumed: Boolean,
        nowElapsedMs: Long,
        sessionStartedAtElapsedMs: Long,
        lastFrameAtElapsedMs: Long,
        lastRecoveryAtElapsedMs: Long,
        stallTimeoutMs: Long,
        recoveryCooldownMs: Long
    ): Boolean {
        if (!running || !interactionActive || !lifecycleResumed) return false
        if (sessionStartedAtElapsedMs <= 0L || nowElapsedMs < sessionStartedAtElapsedMs) return false

        val lastCameraActivityAt = maxOf(sessionStartedAtElapsedMs, lastFrameAtElapsedMs)
        if (nowElapsedMs - lastCameraActivityAt < stallTimeoutMs) return false

        return lastRecoveryAtElapsedMs <= 0L ||
            nowElapsedMs - lastRecoveryAtElapsedMs >= recoveryCooldownMs
    }
}
