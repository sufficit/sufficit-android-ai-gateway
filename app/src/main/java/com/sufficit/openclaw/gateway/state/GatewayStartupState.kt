package com.sufficit.openclaw.gateway.state

/**
 * Tracks one-shot startup orchestration flags for the current app process.
 */
data class GatewayStartupState(
    val autoStartAttempted: Boolean = false,
    val pendingServiceStart: Boolean = false,
    val pendingCameraGestureStart: Boolean = false
)
