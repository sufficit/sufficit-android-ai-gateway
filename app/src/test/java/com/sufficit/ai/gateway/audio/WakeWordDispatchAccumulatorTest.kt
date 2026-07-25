package com.sufficit.ai.gateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDispatchAccumulatorTest {
    @Test
    fun wakeWordRemainsAttachedAfterLiveSessionIsClearedBeforeDispatch() {
        val accumulator = WakeWordDispatchAccumulator()

        accumulator.include(phraseAwakened = true, phraseWakeWord = "xuxu")
        // Representa a parada manual: a sessao ao vivo agora esta inativa.
        accumulator.include(phraseAwakened = false, phraseWakeWord = "")

        val snapshot = accumulator.takeAndReset()

        assertTrue(snapshot.awakened)
        assertEquals("xuxu", snapshot.wakeWord)
    }

    @Test
    fun snapshotIsConsumedOnlyByItsOwnBatch() {
        val accumulator = WakeWordDispatchAccumulator()
        accumulator.include(phraseAwakened = true, phraseWakeWord = "xuxu")

        accumulator.takeAndReset()
        val nextBatch = accumulator.takeAndReset()

        assertFalse(nextBatch.awakened)
        assertEquals("", nextBatch.wakeWord)
    }

    @Test
    fun wakeOriginSurvivesBothTranscriptionAndDispatchQueues() {
        val transcriptionWindow = WakeWordDispatchAccumulator()
        val dispatchWindow = WakeWordDispatchAccumulator()

        // O áudio fecha enquanto a sessão está acordada.
        transcriptionWindow.include(phraseAwakened = true, phraseWakeWord = "xuxu")
        // A parada manual acontece antes de o STT devolver o texto.
        val completedTranscript = transcriptionWindow.takeAndReset()
        dispatchWindow.include(
            phraseAwakened = completedTranscript.awakened,
            phraseWakeWord = completedTranscript.wakeWord
        )

        val outgoingMetadata = dispatchWindow.takeAndReset()
        assertTrue(outgoingMetadata.awakened)
        assertEquals("xuxu", outgoingMetadata.wakeWord)
    }
}
