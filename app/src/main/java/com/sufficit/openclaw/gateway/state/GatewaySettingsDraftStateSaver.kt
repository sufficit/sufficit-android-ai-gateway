package com.sufficit.openclaw.gateway.state

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

val GatewaySettingsDraftStateSaver: Saver<GatewaySettingsDraftState, Any> = listSaver(
    save = { state ->
        listOf(
            state.localEndpointUrl,
            state.openClawServerAddress,
            state.openClawGatewayToken,
            state.openClawDeviceToken,
            state.openClawSessionKey,
            state.whisperUrl,
            state.remoteModel,
            state.whisperAuthToken,
            state.autoStartEnabled,
            state.cameraGestureEnabled,
            state.voiceChannelSkillEnabled,
            state.voiceChannelWakeTermsInput,
            state.voiceChannelFollowUpSecondsInput,
            state.voiceChannelIdlePromptSecondsInput,
            state.assistantVoiceEnabled,
            state.assistantVoiceStyle,
            state.assistantSpeechRateInput,
            state.assistantPitchInput,
            state.transcriptionMode,
            state.localModelName,
            state.localExecutionMode,
            state.development,
            state.microphoneAutoSensitivityEnabled,
            state.microphoneGainInput,
            state.transcriptionRepeatSuppressionInput,
            state.colloquialNormalizationStrengthInput,
            state.vadThresholdInput,
            state.debugSpeechHoldMsInput,
            state.debugMaxSpeechSegmentMsInput,
            state.debugMinTranscriptionMsInput,
            state.debugPhraseBreakSilenceMsInput,
            state.transcriptionTermsInput,
            state.transcriptionDictionaryInput,
            state.screenMode,
            state.screenHoldSecondsInput,
            state.transcriptClearTimeoutSecsInput,
            state.openClawAccumulationWindowSecsInput
        )
    },
    restore = { values ->
        if (values.size != 37) {
            null
        } else {
            GatewaySettingsDraftState(
                localEndpointUrl = values[0] as String,
                openClawServerAddress = values[1] as String,
                openClawGatewayToken = values[2] as String,
                openClawDeviceToken = values[3] as String,
                openClawSessionKey = values[4] as String,
                whisperUrl = values[5] as String,
                remoteModel = values[6] as String,
                whisperAuthToken = values[7] as String,
                autoStartEnabled = values[8] as Boolean,
                cameraGestureEnabled = values[9] as Boolean,
                voiceChannelSkillEnabled = values[10] as Boolean,
                voiceChannelWakeTermsInput = values[11] as String,
                voiceChannelFollowUpSecondsInput = values[12] as String,
                voiceChannelIdlePromptSecondsInput = values[13] as String,
                assistantVoiceEnabled = values[14] as Boolean,
                assistantVoiceStyle = values[15] as String,
                assistantSpeechRateInput = values[16] as String,
                assistantPitchInput = values[17] as String,
                transcriptionMode = values[18] as String,
                localModelName = values[19] as String,
                localExecutionMode = values[20] as String,
                development = values[21] as Boolean,
                microphoneAutoSensitivityEnabled = values[22] as Boolean,
                microphoneGainInput = values[23] as String,
                transcriptionRepeatSuppressionInput = values[24] as String,
                colloquialNormalizationStrengthInput = values[25] as String,
                vadThresholdInput = values[26] as String,
                debugSpeechHoldMsInput = values[27] as String,
                debugMaxSpeechSegmentMsInput = values[28] as String,
                debugMinTranscriptionMsInput = values[29] as String,
                debugPhraseBreakSilenceMsInput = values[30] as String,
                transcriptionTermsInput = values[31] as String,
                transcriptionDictionaryInput = values[32] as String,
                screenMode = values[33] as String,
                screenHoldSecondsInput = values[34] as String,
                transcriptClearTimeoutSecsInput = values[35] as String,
                openClawAccumulationWindowSecsInput = values[36] as String
            )
        }
    }
)
