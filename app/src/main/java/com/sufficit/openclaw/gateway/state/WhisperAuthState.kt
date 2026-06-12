package com.sufficit.openclaw.gateway.state

/**
 * Describes whether remote Whisper authentication is configured for request dispatch.
 */
data class WhisperAuthState(
    val configured: Boolean,
    val usingSeedFallback: Boolean,
    val endpoint: String
)
