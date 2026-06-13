package com.sufficit.ai.gateway.state

/**
 * High-level finite phases for gesture-controlled microphone gating.
 */
enum class GestureGatePhase {
    DISABLED,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_CAMERA,
    WAITING_FOR_GESTURE,
    GESTURE_MATCHED,
    MICROPHONE_OPEN,
    ERROR
}
