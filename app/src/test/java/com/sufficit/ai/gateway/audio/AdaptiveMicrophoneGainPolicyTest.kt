package com.sufficit.ai.gateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMicrophoneGainPolicyTest {
    @Test
    fun silentRoomKeepsMaximumGainForWakeWordRange() {
        val gain = AdaptiveMicrophoneGainPolicy.resolveTargetGain(
            peakGain = 2.4,
            minGain = 0.55,
            noiseFloorRms = 0.003,
            inputRms = 0.003,
            inputPeakNormalized = 0.012,
            speechLikeFrame = false,
            speechActive = false
        )

        assertEquals(2.4, gain, 0.0001)
        assertTrue(
            AdaptiveMicrophoneGainPolicy.shouldRaiseGainImmediately(
                currentGain = 0.94,
                targetGain = gain,
                inputRms = 0.003,
                inputPeakNormalized = 0.012,
                speechLikeFrame = false,
                speechActive = false
            )
        )
        assertFalse(
            AdaptiveMicrophoneGainPolicy.shouldAttenuateStableBackground(
                environmentStable = true,
                noiseFloorRms = 0.003,
                inputRms = 0.003,
                inputPeakNormalized = 0.012
            )
        )
    }

    @Test
    fun quietFrameRecoversRangeEvenWhenNoiseFloorIsStillStale() {
        val gain = AdaptiveMicrophoneGainPolicy.resolveTargetGain(
            peakGain = 2.4,
            minGain = 0.55,
            noiseFloorRms = 0.030,
            inputRms = 0.004,
            inputPeakNormalized = 0.018,
            speechLikeFrame = false,
            speechActive = false
        )

        assertEquals(2.4, gain, 0.0001)
    }

    @Test
    fun distantVoiceBelowVadStillReceivesMaximumGain() {
        val gain = AdaptiveMicrophoneGainPolicy.resolveTargetGain(
            peakGain = 2.4,
            minGain = 0.55,
            noiseFloorRms = 0.004,
            inputRms = 0.008,
            inputPeakNormalized = 0.032,
            speechLikeFrame = false,
            speechActive = false
        )

        assertEquals(2.4, gain, 0.0001)
    }

    @Test
    fun stableRealBackgroundCanBeAttenuated() {
        assertTrue(
            AdaptiveMicrophoneGainPolicy.shouldAttenuateStableBackground(
                environmentStable = true,
                noiseFloorRms = 0.014,
                inputRms = 0.016,
                inputPeakNormalized = 0.060
            )
        )

        val gain = AdaptiveMicrophoneGainPolicy.resolveTargetGain(
            peakGain = 2.4,
            minGain = 0.55,
            noiseFloorRms = 0.020,
            inputRms = 0.022,
            inputPeakNormalized = 0.090,
            speechLikeFrame = false,
            speechActive = false
        )
        assertTrue(gain < 2.4)
        assertTrue(gain >= 0.55)
    }

    @Test
    fun wakeWordKeepsMaximumGainOverModerateBackgroundMusic() {
        val transcriptionGain = AdaptiveMicrophoneGainPolicy.resolveTargetGain(
            peakGain = 2.4,
            minGain = 0.55,
            noiseFloorRms = 0.020,
            inputRms = 0.022,
            inputPeakNormalized = 0.20,
            speechLikeFrame = false,
            speechActive = false
        )
        val wakeWordGain = AdaptiveMicrophoneGainPolicy.resolveWakeWordGain(
            peakGain = 2.4,
            minGain = 0.55,
            inputPeakNormalized = 0.20
        )

        assertTrue(transcriptionGain < wakeWordGain)
        assertEquals(2.4, wakeWordGain, 0.0001)
    }

    @Test
    fun wakeWordGainIsPeakLimitedForLoudInput() {
        val gain = AdaptiveMicrophoneGainPolicy.resolveWakeWordGain(
            peakGain = 2.4,
            minGain = 0.55,
            inputPeakNormalized = 0.80
        )

        assertEquals(0.875, gain, 0.0001)
    }

    @Test
    fun loudSpeechIsPeakLimitedBeforeSoftClip() {
        val gain = AdaptiveMicrophoneGainPolicy.resolveTargetGain(
            peakGain = 2.4,
            minGain = 0.55,
            noiseFloorRms = 0.010,
            inputRms = 0.32,
            inputPeakNormalized = 0.80,
            speechLikeFrame = true,
            speechActive = true
        )

        assertEquals(0.875, gain, 0.0001)
    }
}
