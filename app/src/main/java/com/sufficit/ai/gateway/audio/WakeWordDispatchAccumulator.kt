package com.sufficit.ai.gateway.audio

internal data class WakeWordDispatchSnapshot(
    val awakened: Boolean,
    val wakeWord: String
)

/**
 * Preserva a origem por wake word enquanto um ou mais trechos aguardam a
 * janela de acumulacao. A sessao ao vivo pode ser encerrada antes do envio,
 * mas isso nao pode apagar a origem dos trechos que ja entraram no lote.
 *
 * O chamador sincroniza o acesso junto dos demais campos do lote.
 */
internal class WakeWordDispatchAccumulator {
    private var awakened = false
    private var wakeWord = ""

    fun include(phraseAwakened: Boolean, phraseWakeWord: String) {
        if (!phraseAwakened) return
        awakened = true
        phraseWakeWord.trim().takeIf { it.isNotBlank() }?.let {
            wakeWord = it
        }
    }

    fun takeAndReset(): WakeWordDispatchSnapshot {
        val snapshot = WakeWordDispatchSnapshot(
            awakened = awakened,
            wakeWord = wakeWord
        )
        awakened = false
        wakeWord = ""
        return snapshot
    }
}
