package com.sufficit.openclaw.gateway.audio

import kotlin.math.abs

internal data class SpeakerContinuityState(
    val anchor: VoiceSignature,
    val sameSpeakerProbability: Double,
    val sampleCount: Int,
    val mismatchStreak: Int,
    val anchorConfidence: Double
)

internal data class SpeakerContinuityComputation(
    val decision: String,
    val rawProbability: Double?,
    val adjustedProbability: Double?,
    val sampleCount: Int,
    val mismatchStreak: Int,
    val anchorConfidence: Double,
    val anchor: VoiceSignature?,
    val current: VoiceSignature?
)

internal data class SpeakerContinuityUpdateResult(
    val state: SpeakerContinuityState?,
    val computation: SpeakerContinuityComputation?
)

internal object SpeakerContinuityTracker {
    private const val sameSpeakerThreshold = 0.55
    private const val resetThreshold = 0.34
    private const val maxMismatchStreakBeforeReset = 2

    fun update(
        currentState: SpeakerContinuityState?,
        signature: VoiceSignature?
    ): SpeakerContinuityState? {
        return updateWithComputation(currentState, signature).state
    }

    fun updateWithComputation(
        currentState: SpeakerContinuityState?,
        signature: VoiceSignature?
    ): SpeakerContinuityUpdateResult {
        if (signature == null) {
            return SpeakerContinuityUpdateResult(
                state = currentState,
                computation = null
            )
        }

        if (currentState == null) {
            val initialState = SpeakerContinuityState(
                anchor = signature,
                sameSpeakerProbability = 1.0,
                sampleCount = 1,
                mismatchStreak = 0,
                anchorConfidence = 0.35
            )
            return SpeakerContinuityUpdateResult(
                state = initialState,
                computation = SpeakerContinuityComputation(
                    decision = "initialized",
                    rawProbability = 1.0,
                    adjustedProbability = 1.0,
                    sampleCount = initialState.sampleCount,
                    mismatchStreak = initialState.mismatchStreak,
                    anchorConfidence = initialState.anchorConfidence,
                    anchor = initialState.anchor,
                    current = signature
                )
            )
        }

        val probability = sameSpeakerProbability(currentState.anchor, signature)
        val acceptedAsSameSpeaker = probability >= sameSpeakerThreshold
        val mismatchStreak = if (acceptedAsSameSpeaker) 0 else currentState.mismatchStreak + 1
        val shouldResetAnchor = probability < resetThreshold &&
            mismatchStreak >= maxMismatchStreakBeforeReset

        val nextAnchor = when {
            acceptedAsSameSpeaker -> blendAnchor(currentState.anchor, signature, currentState.sampleCount)
            shouldResetAnchor -> signature
            else -> currentState.anchor
        }

        val nextSampleCount = when {
            acceptedAsSameSpeaker -> currentState.sampleCount + 1
            shouldResetAnchor -> 1
            else -> currentState.sampleCount
        }

        val nextAnchorConfidence = when {
            acceptedAsSameSpeaker -> (currentState.anchorConfidence + 0.08).coerceAtMost(1.0)
            shouldResetAnchor -> 0.35
            else -> (currentState.anchorConfidence - 0.06).coerceAtLeast(0.15)
        }

        val adjustedProbability = if (shouldResetAnchor) {
            0.0
        } else {
            ((probability * 0.75) + (nextAnchorConfidence * 0.25)).coerceIn(0.0, 1.0)
        }

        val nextState = SpeakerContinuityState(
            anchor = nextAnchor,
            sameSpeakerProbability = adjustedProbability,
            sampleCount = nextSampleCount,
            mismatchStreak = if (shouldResetAnchor) 0 else mismatchStreak,
            anchorConfidence = nextAnchorConfidence
        )
        return SpeakerContinuityUpdateResult(
            state = nextState,
            computation = SpeakerContinuityComputation(
                decision = when {
                    shouldResetAnchor -> "reset"
                    acceptedAsSameSpeaker -> "accepted"
                    else -> "held"
                },
                rawProbability = probability,
                adjustedProbability = adjustedProbability,
                sampleCount = nextState.sampleCount,
                mismatchStreak = nextState.mismatchStreak,
                anchorConfidence = nextState.anchorConfidence,
                anchor = currentState.anchor,
                current = signature
            )
        )
    }

    fun estimateSameSpeakerProbability(
        anchor: VoiceSignature,
        current: VoiceSignature
    ): Double {
        return sameSpeakerProbability(anchor, current)
    }

    private fun sameSpeakerProbability(
        anchor: VoiceSignature,
        current: VoiceSignature
    ): Double {
        val pitchScore = when {
            anchor.pitchMeanHz == null || current.pitchMeanHz == null -> 0.55
            else -> (1.0 - (abs(anchor.pitchMeanHz - current.pitchMeanHz) / 120.0)).coerceIn(0.0, 1.0)
        }
        val pitchStdScore = (1.0 - (abs(anchor.pitchStdHz - current.pitchStdHz) / 45.0)).coerceIn(0.0, 1.0)
        val energyScore = (1.0 - (abs(anchor.energyMean - current.energyMean) / 0.12)).coerceIn(0.0, 1.0)
        val voicedRatioScore = (1.0 - (abs(anchor.voicedRatio - current.voicedRatio) / 0.65)).coerceIn(0.0, 1.0)

        return (
            (pitchScore * 0.46) +
                (pitchStdScore * 0.18) +
                (energyScore * 0.18) +
                (voicedRatioScore * 0.18)
            ).coerceIn(0.0, 1.0)
    }

    private fun blendAnchor(
        anchor: VoiceSignature,
        incoming: VoiceSignature,
        sampleCount: Int
    ): VoiceSignature {
        val weightOld = sampleCount.coerceAtLeast(1).toDouble()
        val weightNew = 1.0

        return VoiceSignature(
            pitchMeanHz = blendNullable(anchor.pitchMeanHz, incoming.pitchMeanHz, weightOld, weightNew),
            pitchStdHz = weightedMean(anchor.pitchStdHz, incoming.pitchStdHz, weightOld, weightNew),
            energyMean = weightedMean(anchor.energyMean, incoming.energyMean, weightOld, weightNew),
            voicedRatio = weightedMean(anchor.voicedRatio, incoming.voicedRatio, weightOld, weightNew)
        )
    }

    private fun blendNullable(
        oldValue: Double?,
        newValue: Double?,
        weightOld: Double,
        weightNew: Double
    ): Double? {
        return when {
            oldValue == null -> newValue
            newValue == null -> oldValue
            else -> weightedMean(oldValue, newValue, weightOld, weightNew)
        }
    }

    private fun weightedMean(
        oldValue: Double,
        newValue: Double,
        weightOld: Double,
        weightNew: Double
    ): Double {
        return ((oldValue * weightOld) + (newValue * weightNew)) / (weightOld + weightNew)
    }
}
