# Android Settings Backup And Speaker History

## About

This change separates Android gateway configuration responsibilities and adds two operational capabilities:

- full JSON export/import for settings backup and standardization
- persisted speaker continuity history used to explain same-speaker confidence decisions

## Implementation

### Settings refactor

- `GatewaySettings.kt` keeps the core settings model and store responsibilities.
- `GatewaySettingsPatch.kt` contains WebSocket patch validation and application rules.
- `GatewaySettingsJson.kt` contains JSON serialization, backup export, backup import, and file reading helpers.

### JSON backup contract

- Schema: `openclaw-android-settings`
- Version: `1`
- Format: top-level metadata plus a `settings` object using the same normalized keys accepted by `settingsPatch`

This keeps remote tuning and backup/import aligned with the same field names and coercion rules.

### UI integration

- The General configuration section now exposes `Export JSON` and `Import JSON` actions.
- Import applies the parsed `GatewaySettings` back into the Compose state before saving.
- Export shares a generated JSON file so it can be archived or reused on another device.

### Speaker continuity history

- `SpeakerContinuityTracker.kt` now produces structured computation details for each update.
- `SpeakerContinuityHistoryLogger.kt` persists those details to `files/history/speaker-continuity-history.jsonl`.
- `RoomAudioForegroundService.kt` appends accepted, held, reset, and overlap-skipped events.
- Recent history is summarized into `speakerContinuityHistory` inside outbound OpenClaw metadata.

## Operational Notes

- The persisted JSONL file is intended for diagnostics and agent-side reasoning about confidence values.
- The metadata summary is bounded to recent entries to avoid bloating the WebSocket payload.
- The backup JSON is intended to be portable across devices running the same app contract version.

## Validation

- Branch verified: `main`
- Kotlin validation: `./gradlew.bat :app:compileDebugKotlin`
- Result: success
- Remaining manual checks:
  - import/export a real JSON backup file on device
  - confirm `speakerContinuityHistory` arrives in a real OpenClaw session metadata payload