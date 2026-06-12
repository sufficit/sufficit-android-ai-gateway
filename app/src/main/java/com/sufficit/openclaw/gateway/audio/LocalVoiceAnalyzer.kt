package com.sufficit.openclaw.gateway.audio

import kotlin.math.abs
import kotlin.math.sqrt

internal data class LocalVoiceAnalysisResult(
    val gender: String?,
    val emotion: String?,
    val voiceSignature: VoiceSignature? = null,
    val multipleVoicesLikely: Boolean = false
)

internal data class VoiceSignature(
    val pitchMeanHz: Double?,
    val pitchStdHz: Double,
    val energyMean: Double,
    val voicedRatio: Double
)

internal object LocalVoiceAnalyzer {
    private const val minPitchHz = 85.0
    private const val maxPitchHz = 280.0
    private const val frameMs = 32
    private const val hopMs = 16

    fun analyzePcm16(
        pcmBytes: ByteArray,
        sampleRate: Int
    ): LocalVoiceAnalysisResult {
        val samples = pcm16ToDoubleArray(pcmBytes)
        if (samples.isEmpty()) {
            return LocalVoiceAnalysisResult(gender = null, emotion = null)
        }

        val frameSize = ((sampleRate * frameMs) / 1000.0).toInt().coerceAtLeast(256)
        val hopSize = ((sampleRate * hopMs) / 1000.0).toInt().coerceAtLeast(128)
        if (samples.size < frameSize) {
            return classifyFromFeatures(
                pitches = emptyList(),
                energies = listOf(computeRms(samples, 0, samples.size))
            )
        }

        val pitches = mutableListOf<Double>()
        val energies = mutableListOf<Double>()
        var offset = 0
        while (offset + frameSize <= samples.size) {
            val rms = computeRms(samples, offset, frameSize)
            energies += rms

            if (rms >= 0.018) {
                estimatePitchHz(samples, offset, frameSize, sampleRate)?.let { pitches += it }
            }

            offset += hopSize
        }

        return classifyFromFeatures(pitches, energies)
    }

    private fun classifyFromFeatures(
        pitches: List<Double>,
        energies: List<Double>
    ): LocalVoiceAnalysisResult {
        val meanPitch = pitches.averageOrNull()
        val pitchStd = pitches.standardDeviation()
        val meanEnergy = energies.averageOrNull() ?: 0.0
        val pitchRange = if (pitches.isEmpty()) 0.0 else (pitches.maxOrNull() ?: 0.0) - (pitches.minOrNull() ?: 0.0)
        val largePitchJumps = pitches.zipWithNext().count { (a, b) -> abs(a - b) >= 55.0 }
        val multipleVoicesLikely =
            pitches.size >= 8 &&
                (
                    (pitchRange >= 95.0 && pitchStd >= 30.0 && largePitchJumps >= 3) ||
                        largePitchJumps >= 5
                    )

        val gender = when {
            meanPitch == null -> null
            meanPitch < 145.0 -> "male"
            meanPitch > 185.0 -> "female"
            else -> "ambiguous"
        }

        val emotion = when {
            meanEnergy > 0.11 && pitchStd > 28.0 -> "angry"
            meanEnergy > 0.085 && pitchStd < 24.0 -> "energetic"
            meanEnergy < 0.028 && (meanPitch ?: 0.0) < 155.0 -> "sad"
            meanEnergy < 0.04 && pitchStd < 16.0 -> "calm"
            (meanPitch ?: 0.0) > 185.0 && pitchStd > 20.0 -> "happy"
            meanEnergy > 0.035 || meanPitch != null -> "neutral"
            else -> null
        }

        return LocalVoiceAnalysisResult(
            gender = gender,
            emotion = emotion,
            voiceSignature = VoiceSignature(
                pitchMeanHz = meanPitch,
                pitchStdHz = pitchStd,
                energyMean = meanEnergy,
                voicedRatio = if (energies.isEmpty()) 0.0 else pitches.size.toDouble() / energies.size.toDouble()
            ),
            multipleVoicesLikely = multipleVoicesLikely
        )
    }

    private fun estimatePitchHz(
        samples: DoubleArray,
        offset: Int,
        frameSize: Int,
        sampleRate: Int
    ): Double? {
        val minLag = (sampleRate / maxPitchHz).toInt().coerceAtLeast(16)
        val maxLag = (sampleRate / minPitchHz).toInt().coerceAtMost(frameSize / 2)
        var bestLag = -1
        var bestScore = 0.0

        for (lag in minLag..maxLag) {
            var correlation = 0.0
            var energyA = 0.0
            var energyB = 0.0
            var i = 0
            while (i + lag < frameSize) {
                val a = samples[offset + i]
                val b = samples[offset + i + lag]
                correlation += a * b
                energyA += a * a
                energyB += b * b
                i += 1
            }

            val denom = sqrt(energyA * energyB)
            if (denom <= 1e-9) {
                continue
            }

            val normalized = correlation / denom
            if (normalized > bestScore) {
                bestScore = normalized
                bestLag = lag
            }
        }

        if (bestLag <= 0 || bestScore < 0.35) {
            return null
        }

        return sampleRate.toDouble() / bestLag.toDouble()
    }

    private fun computeRms(
        samples: DoubleArray,
        offset: Int,
        length: Int
    ): Double {
        var sum = 0.0
        for (index in 0 until length) {
            val sample = samples[offset + index]
            sum += sample * sample
        }
        return sqrt(sum / length.coerceAtLeast(1))
    }

    private fun pcm16ToDoubleArray(pcmBytes: ByteArray): DoubleArray {
        if (pcmBytes.isEmpty()) {
            return DoubleArray(0)
        }

        val count = pcmBytes.size / 2
        val output = DoubleArray(count)
        var byteIndex = 0
        var sampleIndex = 0
        while (byteIndex + 1 < pcmBytes.size) {
            val low = pcmBytes[byteIndex].toInt() and 0xFF
            val high = pcmBytes[byteIndex + 1].toInt()
            val value = ((high shl 8) or low).toShort()
            output[sampleIndex] = value / Short.MAX_VALUE.toDouble()
            byteIndex += 2
            sampleIndex += 1
        }
        return output
    }

    private fun List<Double>.averageOrNull(): Double? {
        if (isEmpty()) {
            return null
        }
        return average()
    }

    private fun List<Double>.standardDeviation(): Double {
        if (size < 2) {
            return 0.0
        }
        val mean = average()
        var variance = 0.0
        for (value in this) {
            val diff = value - mean
            variance += diff * diff
        }
        return sqrt(variance / size)
    }
}
