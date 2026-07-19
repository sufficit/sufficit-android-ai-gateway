package com.sufficit.ai.gateway.transcription

/**
 * Preflight result for remote (Whisper/ElevenLabs) transcription config.
 * Lets callers surface a blank-url/blank-token state before dispatching an
 * HTTP request, instead of only discovering it from a failed response.
 */
sealed interface WhisperConfigurationCheck {
    data object Ready : WhisperConfigurationCheck
    data class Blocked(val reason: String) : WhisperConfigurationCheck
}

fun checkWhisperConfiguration(whisperUrl: String, authToken: String): WhisperConfigurationCheck {
    if (whisperUrl.isBlank()) {
        return WhisperConfigurationCheck.Blocked("Endpoint remoto nao configurado.")
    }
    if (authToken.isBlank()) {
        return WhisperConfigurationCheck.Blocked("Token de autenticacao remoto nao configurado.")
    }
    return WhisperConfigurationCheck.Ready
}
