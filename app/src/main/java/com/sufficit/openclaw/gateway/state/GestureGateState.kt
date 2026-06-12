package com.sufficit.openclaw.gateway.state

/**
 * Aggregated gate state used to explain whether the microphone can react to speech.
 */
data class GestureGateState(
    val phase: GestureGatePhase,
    val gateOpen: Boolean,
    val statusText: String
)
