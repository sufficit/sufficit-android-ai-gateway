# Dashboard Transcript Stack And Spectrum Layout

## About

The Android dashboard was reorganized to prioritize live transcription reading and reduce visual competition from controls and the spectrum panel.

## Changes

- Moved the main action strip above the spectrum card.
- Reduced the spectrum card height.
- Reduced the visual bar amplitude inside the spectrum card.
- Removed the `Transcricao recente` title from the transcription card.
- Moved the four transcription metadata icons to the top of the transcription card.
- Kept those metadata icons tied to the most recent transcription state only.
- Replaced the strict `Atual` / `Anterior` presentation with a short fading stack of recent transcripts.

## Runtime support

- `GatewayUiState` now carries `recentTranscripts`.
- `RoomAudioForegroundService` pushes committed phrases into that short stack when a phrase rotates or finalizes.
- The current transcript remains the main visible line; older phrases fade below it.

## Validation

- Kotlin compile: `./gradlew.bat :app:compileDebugKotlin`
- Result: success