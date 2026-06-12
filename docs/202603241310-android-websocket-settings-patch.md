# Android WebSocket Settings Patch

## About

The Android OpenClaw app now accepts remote configuration patches over the existing WebSocket reply envelope.

The final Android agent may return a single JSON object with `settingsPatch`, and the app will validate, persist, and apply the patch locally.

## Supported flow

1. Android sends a transcript through the `android` channel.
2. The final agent may answer with JSON instead of plain text.
3. When `settingsPatch` is present, the server forwards it inside the Android reply envelope.
4. The app parses the patch, applies only supported fields, persists them in `GatewaySettingsStore`, refreshes TTS when needed, reconnects the OpenClaw socket when needed, and restarts audio capture when required.

## JSON contract

Example:

```json
{
  "text": "Ajustei o ganho do microfone para 3.0x e acelerei a voz.",
  "shouldSpeak": true,
  "settingsPatch": {
    "microphoneGain": 3.0,
    "assistantSpeechRate": 1.25
  }
}
```

## Supported patch fields

- `localEndpointUrl`
- `openClawServerAddress`
- `openClawGatewayToken`
- `openClawDeviceToken`
- `openClawSessionKey`
- `whisperUrl`
- `remoteModel`
- `whisperAuthToken`
- `autoStartEnabled`
- `voiceChannelSkillEnabled`
- `voiceChannelWakeTerms`
- `voiceChannelFollowUpSeconds`
- `voiceChannelIdlePromptSeconds`
- `assistantVoiceEnabled`
- `assistantVoiceStyle`
- `assistantSpeechRate`
- `assistantPitch`
- `transcriptionMode`
- `localModelPath`
- `localExecutionMode`
- `development`
- `microphoneAutoSensitivityEnabled`
- `microphoneGain`
- `transcriptionRepeatSuppression`
- `colloquialNormalizationStrength`
- `vadThreshold`
- `debugSpeechHoldMs`
- `debugMaxSpeechSegmentMs`
- `debugMinTranscriptionMs`
- `debugPhraseBreakSilenceMs`
- `transcriptionTerms`
- `transcriptionDictionary`
- `screenMode`
- `screenHoldSeconds`

## Validation rules

- Unknown fields are ignored.
- Numeric values are clamped to the same ranges used by the Android configuration UI.
- Nullable debug fields may be reset with `null`.
- Invalid enum values are ignored instead of silently mapped.

## Runtime behavior

- TTS-related fields refresh immediately.
- OpenClaw connection fields trigger a reconnect.
- Capture and transcription fields trigger a clean capture restart when the microphone loop is active.

## Verification

- `npx eslint extensions/android/src/voice-pre-agent.js --fix`
- `./gradlew.bat :app:compileDebugKotlin`