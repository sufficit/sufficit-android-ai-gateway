# Dynamic Ambient Noise Gate Before Transcription

## About

This change adds a dynamic ambient noise gate to Android capture, so stable background noise or low constant music does not automatically become transcription input.

## What Changed

- Added runtime state fields for ambient detection and stability score.
- Added a dynamic stability classifier in the capture loop before speech candidate promotion.
- The classifier combines:
  - dynamic RMS contrast against current noise floor
  - normalized RMS variance in a short window
  - recent spectrum motion
- Added hold and release hysteresis to avoid rapid toggling.
- Added speech override conditions so clear voice transitions are still accepted quickly.
- Added a small visual badge in the spectrum card when ambient stable noise is being blocked.
- Added development-only stability telemetry in the spectrum card line.

## Behavioral Effect

- While ambient is stable and speech transition is weak, frames are blocked from speech candidate flow.
- This prevents low-information stable environments from creating repeated false transcriptions.
- When speech transitions are strong, the gate is bypassed and transcription proceeds.

## Files

- `app/src/main/java/com/sufficit/openclaw/gateway/audio/RoomAudioForegroundService.kt`
- `app/src/main/java/com/sufficit/openclaw/gateway/runtime/GatewayRuntime.kt`
- `app/src/main/java/com/sufficit/openclaw/gateway/GatewayDashboardUi.kt`

## Validation

- Kotlin compile: `./gradlew.bat :app:compileDebugKotlin`
- APK build: `./gradlew.bat :app:assembleDebug`
- Device install: `adb -s RX8N60B5CZM install -r app-debug.apk`
- Foreground validation: `ResumedActivity` confirmed twice for `com.sufficit.openclaw.gateway/.MainActivity`

## Notes

- Threshold constants are intentionally conservative for first rollout.
- Final tuning should be done with real ambient scenarios (silence, constant noise, low music, voice over background).