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
}
