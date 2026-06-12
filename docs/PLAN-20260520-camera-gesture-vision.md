# PLAN 20260520 Camera Gesture Vision

## Goal
Finish camera gesture support in the Android gateway, keep the gesture debug preview page available only inside the Debug area, and validate wake flows on the device while background camera capture starts with the app.

## Steps
1. Keep the non-GL camera debug preview available only through the Debug menu while the gesture capture itself starts with the app.
2. Build and install the debug APK on the connected device.
3. Validate runtime camera/gesture behavior and wake flows.
4. Record status in `AGENTS.md`.

## Progress
- [x] Create `CameraGestureEvent.kt`
- [x] Create `MediaPipeCameraGestureRecognizer.kt`
- [x] Build debug APK
- [x] Install APK on device
- [x] Add centered gesture overlay with per-gesture icon
- [x] Fix `RoomAudioForegroundService.kt` compile regression after speech-gating edits
- [x] Harden `xuxu` wake-term matching for split and colloquial transcripts
- [x] Move gesture debug landing out of startup and into Debug menu access
- [ ] Final validation

## Notes
- Use MediaPipe `Hands` + `CameraInput` with front camera.
- Keep comments and documentation in English.
- User explicitly requested compile and install.
- Activity launch validated by ADB with no filtered crash evidence in `logcat`.
- After a later user report that the app "does not open", `dumpsys activity activities` still showed `ResumedActivity` and `mFocusedApp` on `com.sufficit.openclaw.gateway/.MainActivity`, so the current issue does not look like a process crash.
- Direct ADB overwrite of the old runtime config file was blocked by sandbox permissions, so the asset/fallback defaults were changed to enable camera gesture for future resets/reinstalls.
- After uninstall + reinstall, the recreated runtime config now shows `settings.general.cameraGestureEnabled = true` on the device.
- Temporary direction changed: the fullscreen gesture debug page no longer hijacks startup and is now opened only from the Debug menu, while still showing real-time match/non-match analysis from the camera pipeline.
- Current startup behavior target: the camera capture pipeline should initialize with the app in background mode for gesture gating, but the visual preview must remain hidden until the user explicitly opens the Debug page.
- Gate synchronization note: a live log capture later confirmed that the audio service was running but never saw the camera unlock event; the camera callback now mirrors its gate-open state through `GatewayRuntime` so `RoomAudioForegroundService` can unblock capture when the gesture matches.
- Startup instrumentation note: a subsequent diagnostic still showed microphone activity but no camera startup/bind evidence, so explicit logs were added around background camera startup and lifecycle binding, and the audio loop now re-syncs the gesture gate from runtime on every status update.
- Whisper auth note: a later device inspection showed `files/config.json` had persisted `whisperToken` as an empty string even though both the seed config and server env still had the valid token; runtime normalization and settings rebuild now refill blank Whisper tokens from defaults/current runtime so the Authorization header is sent again.
- Regression found during testing: the first debug landing page still triggered automatic foreground microphone startup, causing `ForegroundServiceStartNotAllowedException`; the current runtime was adjusted again to restore auto-start behavior after the crash workarounds.
- Device-specific limitation confirmed: starting the old MediaPipe camera path consistently crashed with `ExternalTextureConverter` / `Framebuffer not complete, status=36054`.
- Current approach uses `CameraX` + `ImageAnalysis` + bitmap rotation on CPU to feed MediaPipe `Hands`, while rendering a `PreviewView` behind the gesture-debug overlay.
- Default wake terms now include the user-requested term `xuxu`, and the current device runtime config also shows `xuxu` plus a non-empty Whisper token that was persisted directly into `files/config.json`.
- Visual tuning note: index-finger detection was too strict for the user pose; heuristics were updated to use distance-from-palm checks in addition to vertical ordering.
- Wake routing note: `VoiceChannelSkill` now accepts split/colloquial renderings of `xuxu` such as `xu xu`, `chu chu`, and `chuchu`, and the bundled normalization catalog also maps those variants back to the canonical wake term before routing.
- Architecture migration note: the repeated settings-save plus foreground-service start flow now goes through `persistSettingsAndStartListening(...)`, reducing duplication across permission and manual-start paths.
- Architecture migration note: imported-settings UI reapplication now also goes through shared helper `applySettingsToUi(...)`, allowing the local `MainActivity` copy block to be removed.
- Architecture migration note: repeated settings assembly now also goes through `GatewaySettingsInputSnapshot` plus `currentSettingsInputSnapshot(...)`, replacing multiple giant argument lists in `MainActivity`.
- Architecture migration note: `ConfigPageState` creation now also goes through helper `currentConfigPageState(...)`, shrinking another large inline section from `MainActivity`.
- Architecture migration note: a later safe cleanup pass removed additional dead imports after the refactor slices, bringing `MainActivity.kt` down to 1024 lines.
- Architecture migration note: gesture debug stop handling and the single-finger match side effects now also go through shared helpers in `GatewayModelSupport.kt`, trimming another part of the camera block.
- Architecture migration note: camera permission result handling now also goes through shared helper `handleCameraPermissionResult(...)`, further shrinking the launcher section in `MainActivity`.
- Architecture migration note: disabled-camera and pending-camera-permission status blocks now also go through shared helpers `handleDisabledCameraGestureState(...)` and `handlePendingCameraPermissionState(...)`.
- Architecture migration note: notification/microphone permission callbacks and the start-listening branching logic now also go through shared helpers `handleNotificationPermissionResult(...)`, `handleMicrophonePermissionResult(...)`, and `handleStartForegroundListeningRequest(...)`.
- Architecture migration note: the remaining body of `startCameraGestureCapture(...)` now also goes through shared helper `startCameraGestureCapture(...)`, leaving `MainActivity` with only the wiring call.
- Architecture migration note: the settings import success/empty/ignored result handling now also goes through shared helper `handleImportedSettingsResult(...)`.
- Architecture migration note: download/history UI-only flags are now grouped into `GatewayDownloadState` and `GatewayHistoryState`, trimming several scattered local variables from `MainActivity`.
- Architecture migration note: lightweight navigation/back-press UI state is now grouped into `GatewayUiState`, reducing a few more isolated locals without changing Compose ownership.
- Architecture migration note: model availability/loading flags are now grouped into `GatewayModelState`, replacing another small cluster of scattered locals.
- Validation note: after fixing a missing `GatewayRuntime` import introduced by that extraction, `assembleDebug`, streamed reinstall, and explicit activity launch all succeeded again on device `RX8N60B5CZM`.