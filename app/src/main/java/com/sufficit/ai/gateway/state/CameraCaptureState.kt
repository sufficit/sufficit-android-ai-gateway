package com.sufficit.ai.gateway.state

/**
 * Describes whether the gesture capture pipeline is running and whether preview is visible.
 */
data class CameraCaptureState(
    val enabled: Boolean,
    val active: Boolean,
    val previewVisible: Boolean
)
