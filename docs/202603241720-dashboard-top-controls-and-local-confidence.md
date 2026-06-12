# Dashboard Top Controls And Local Confidence

## About

This change frees vertical space in the Android dashboard and makes the local voice-confidence signal visible at transcription time.

## Changes

- Removed the large start/stop action strip from the content flow.
- Moved the listen control to the header as a compact icon.
- When the service is already running, the header control now opens a small action menu instead of stopping immediately.
- Kept assistant speech interruption available from the same top control menu when needed.
- Reworked the two status indicators so they no longer look like duplicated settings icons.
- Reduced the spectrum card height again and lowered the waveform amplitude.
- Added a local confidence line in the transcription card using the Android same-speaker probability.

## Validation

- Kotlin compile: `./gradlew.bat :app:compileDebugKotlin`
- APK build/install/foreground validation performed after implementation