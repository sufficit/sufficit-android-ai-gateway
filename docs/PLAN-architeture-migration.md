# Architecture Migration Plan

## Goal
Refactor `sufficit-android-openclaw-gateway` toward a clearer Android architecture based on a thin activity, lifecycle-aware state holders, explicit runtime state machines, and smaller files with one model/class per file whenever practical.

## Constraints
- Keep JSON as the source of truth for configuration import/export and runtime persistence.
- Preserve current behavior while reducing ambiguity.
- Prefer one file per class/type/model when practical.
- Avoid oversized files; split by responsibility before reaching large monolithic structures.
- Preserve local compile/install verification after each code change.

## Migration Principles
1. `MainActivity` should only host Compose, permission launchers, and navigation shell concerns.
2. Screen state should come from dedicated state holders, ideally `ViewModel`-backed.
3. Camera, microphone, Whisper, and OpenClaw orchestration should move to explicit coordinators.
4. Gesture and microphone gating should become a finite state machine instead of mirrored booleans.
5. UI should render immutable state and emit events upward.

## Proposed Package Direction
### UI / Activity shell
- `MainActivity`
- `navigation/`
- `ui/`
- `config/ui/`

### State holders
- `state/` or `viewmodels/`
- one holder per screen/domain slice when possible

### Runtime / coordinators
- `runtime/`
- `audio/`
- `vision/`
- `transcription/`
- `openclaw/`

### Models
Create separate files for each model/type when possible:
- startup state
- permission state
- camera capture state
- gesture gate state
- whisper auth state
- dashboard ui state slices

## Incremental Migration Order

### Phase 1 - Stabilize state boundaries
Create explicit domain models in separate files.

#### Files to introduce
- `state/GatewayStartupState.kt`
- `state/GatewayPermissionState.kt`
- `state/CameraCaptureState.kt`
- `state/GestureGateState.kt`
- `state/WhisperAuthState.kt`
- `state/GatewayNavigationState.kt`

#### Objective
Replace scattered booleans and string-status coupling with structured state.

### Phase 2 - Introduce a top-level state holder
Add a dedicated state holder, preferably `GatewayViewModel`.

#### Candidate files
- `state/GatewayViewModel.kt`
- `state/GatewayViewModelFactory.kt`
- `state/GatewayUiReducer.kt`
- `state/GatewayUiEvent.kt`
- `state/GatewayUiCommand.kt`

#### Responsibilities
- load settings
- normalize settings
- expose dashboard/config/debug state
- translate permission results
- decide startup actions
- emit commands to runtime/service/camera layers

### Phase 3 - Move startup orchestration out of `MainActivity`
Current startup decisions are embedded inside composable effects and local functions.

#### Target
`MainActivity` should:
- obtain ViewModel/state holder
- collect ui state with lifecycle awareness
- forward Android callbacks/events
- render screens

#### Move out of `MainActivity`
- camera startup policy
- foreground service startup policy
- permission-state interpretation
- gesture gate synchronization policy
- whisper token integrity handling

### Phase 4 - Split configuration assembly and persistence
Current settings construction is broad and UI-driven.

#### Candidate files
- `config/GatewaySettingsAssembler.kt`
- `config/GatewaySettingsValidator.kt`
- `config/GatewaySettingsNormalizer.kt`
- `config/GatewaySettingsPersistenceCoordinator.kt`

#### Objective
Make config behavior testable and keep token/default recovery isolated from Compose.

### Phase 5 - Formalize gate state machine
Create a single state machine for camera/microphone gating.

#### Candidate files
- `runtime/GestureMicrophoneGateStateMachine.kt`
- `runtime/GestureMicrophoneGateState.kt`
- `runtime/GestureMicrophoneGateEvent.kt`

#### Suggested states
- `Disabled`
- `WaitingForPermissions`
- `WaitingForCameraBind`
- `WaitingForGesture`
- `GestureMatched`
- `MicrophoneOpening`
- `MicrophoneOpen`
- `Error`

#### Suggested events
- `CameraPermissionGranted`
- `CameraPermissionDenied`
- `CameraBound`
- `CameraBindFailed`
- `GestureDetected`
- `GestureCleared`
- `MicrophoneStartRequested`
- `MicrophoneStarted`
- `MicrophoneFailed`
- `SettingsDisabled`

### Phase 6 - Separate preview concerns from capture concerns
Current flow mixes visual preview and background capture concerns.

#### Candidate files
- `vision/CameraCaptureCoordinator.kt`
- `vision/CameraPreviewController.kt`
- `vision/CameraLifecycleBinding.kt`
- `vision/GestureDetectionCoordinator.kt`

#### Objective
Support:
- background capture active
- preview hidden
- debug page preview visible on demand
without tying capture lifetime to preview navigation.

### Phase 7 - Isolate Whisper auth and remote transcription health
The recent 401 issue showed that config integrity and runtime health need their own layer.

#### Candidate files
- `transcription/WhisperAuthStatus.kt`
- `transcription/WhisperConfigurationGuard.kt`
- `transcription/RemoteTranscriptionHealth.kt`
- `transcription/WhisperRequestFactory.kt`

#### Objective
- guarantee header eligibility
- surface blank-token state before requests
- separate request formatting from service loop behavior

### Phase 8 - Reduce `GatewayRuntime` responsibilities
`GatewayRuntime` currently acts as a shared mutable bus.

#### Direction
Keep it as an observable runtime bridge for now, but progressively narrow it.

#### Possible future split
- `GatewayDisplayRuntime`
- `GestureRuntimeBridge`
- `AudioRuntimeBridge`
- `AttentionRuntimeBridge`

This should be done only after the ViewModel/state holder boundary is stable.

## File Size / Organization Rules For This Migration
- Prefer one model or one state machine type per file.
- Avoid adding new nested classes inside already-large files.
- If a file exceeds roughly 300-400 lines during refactor, split supporting models/reducers/helpers.
- Keep XML comments and code comments in English.

## First Concrete Refactor Slice
Recommended first implementation slice:
1. create `GatewayPermissionState.kt`
2. create `GestureGateState.kt`
3. create `WhisperAuthState.kt`
4. create `GatewayStartupState.kt`
5. create `GatewayViewModel.kt`
6. make `MainActivity` read state from that holder without changing all runtime internals yet

Status:
- Done: `GatewayPermissionState.kt`
- Done: `GestureGatePhase.kt`
- Done: `GestureGateState.kt`
- Done: `WhisperAuthState.kt`
- Done: `GatewayStartupState.kt`
- Done: `CameraCaptureState.kt`
- Done: `GatewayNavigationState.kt`
- Done: initial `GatewayViewModel.kt`
- Done: initial `GatewayViewModelFactory.kt`
- Done: minimal `MainActivity` integration consuming derived summaries from the new holder
- Done: first migration of startup/pending flags into `GatewayViewModel`
- Done: `GatewayViewModel.startupState` is now observable via Compose state instead of a plain snapshot getter
- Done: remaining camera permission request paths now call `requestCameraGestureStart()` before launcher dispatch
- Done: duplicated service-start settings-save/start logic moved to `persistSettingsAndStartListening(...)`
- Done: imported-settings UI reapplication moved to shared helper `applySettingsToUi(...)`
- Done: current settings aggregation moved to `GatewaySettingsInputSnapshot` + `currentSettingsInputSnapshot(...)`
- Done: platform permission and notification helpers moved out of `MainActivity`
- Done: `ConfigPageState` construction moved to helper `currentConfigPageState(...)`
- Done: imported-settings reapply moved to local helper `reapplyImportedSettings(...)`
- Done: import cleanup pass removed additional dead imports from `MainActivity`
- Done: gesture debug stop and single-finger match side effects moved to shared helpers in `GatewayModelSupport.kt`
- Done: camera permission result handling moved to shared helper `handleCameraPermissionResult(...)`
- Done: disabled-camera and pending-camera-permission states moved to shared helpers `handleDisabledCameraGestureState(...)` and `handlePendingCameraPermissionState(...)`
- Done: notification and microphone permission callbacks moved to shared helpers `handleNotificationPermissionResult(...)` and `handleMicrophonePermissionResult(...)`
- Done: start-listening request branching moved to shared helper `handleStartForegroundListeningRequest(...)`
- Done: remaining camera gesture capture block moved to shared helper `startCameraGestureCapture(...)`
- Done: settings import result handling moved to shared helper `handleImportedSettingsResult(...)`
- Done: local download/history UI state grouped into `GatewayDownloadState` and `GatewayHistoryState`
- Done: lightweight navigation/back-press UI state grouped into `GatewayUiState`
- Done: model availability/loading UI state grouped into `GatewayModelState`
- Done: `ConfigPageSideEffectActions` introduced as a state-safe preparation step for future `ConfigPageActions` extraction
- Done: `MainActivity.kt` currently at 959 lines after the latest safe cleanup
- Validated: `assembleDebug`, reinstall, and explicit launch on device `RX8N60B5CZM`
- Done: thin host `GatewayConfigPageHost.kt` introduced; `MainActivity` now delegates config page rendering to it, preserving Compose/lifecycle/permission/camera/pager ownership
- Done: inline `ConfigPageActions` block replaced by host/action grouping via `buildConfigPageActions(...)` and `buildConfigPageSideEffectActions(...)`
- Validated (static): `get_errors` clean on `MainActivity.kt` and `GatewayConfigPageHost.kt`; no `assembleDebug`/reinstall in this iteration due to missing JDK/`JAVA_HOME` in current environment
- Done: `GatewaySettingsState.kt` introduced — all 37 individual `rememberSaveable` settings fields grouped into a single state holder with `listSaver` (process-death-safe); `rememberGatewaySettingsState(initial)` factory, `toSnapshot()`, and `applyFrom(settings)` methods; `@Suppress("UNCHECKED_CAST")` scoped to companion `Saver`
- Done: `GatewayConfigPageHost.kt` simplified — 37 field params + 37 `update*` lambda params replaced by `settingsState: GatewaySettingsState`; `currentLocalModelName` and `currentSettingsInputSnapshot` suppliers derived internally from `settingsState`; `resetDownloadState` constructed inline via `currentDownloadState()`
- Done: `MainActivity.kt` shrunk from 851 → ~621 lines (~230 lines removed); `reapplyImportedSettings` reduced from ~55 lines to 3; `settingsInputSnapshot` construction reduced from ~50 lines to 1; `GatewayConfigPageHost` call reduced from ~100 args to ~20; `java.io.File` and `java.util.Locale` imports removed
- Validated (static): `get_errors` clean on `GatewaySettingsState.kt`, `GatewayConfigPageHost.kt`, `MainActivity.kt`
- Done: permission/import helpers extracted to `GatewayPermissionSupport.kt` (`persistSettingsAndStartListening`, notification/microphone permission helpers, notification settings helpers, import result handling)
- Done: camera gesture helpers extracted to `GatewayCameraGestureSupport.kt` (`handleDisabledCameraGestureState`, permission-result handling, debug stop/start capture, single-finger match side effect)
- Done: `GatewayModelSupport.kt` reduced from1041 →627 lines by moving permission/camera responsibilities into focused helper files
- Done: download/HuggingFace helpers extracted to `GatewayModelDownloadSupport.kt` (`fixedModelsDirectoryPath`, `resolveLocalModelTarget`, `isLocalModelReady`, `downloadModelFromHuggingFace`, `checkHuggingFaceModelExists`, `huggingFaceModelUrl`, `huggingFaceRepoId`, `fetchHuggingFaceModelSize`, `computeSha256`)
- Done: model-options helpers extracted to `GatewayModelOptionsSupport.kt` (`selectedModelOption`, `loadLocalModelOptions`, `formatBytes`, `shareTranscriptHistory`)
- Done: `GatewayModelSupport.kt` now at338 lines (below400-line guideline); contains only `applySettingsToUi`, UI-state data classes, `buildSettings`, `currentSettingsInputSnapshot`
- Validated (static): `get_errors` clean on all five helper files and `MainActivity.kt`, `GatewayConfigActionsFactory.kt`, `GatewayConfigSideEffectActionsFactory.kt`
- Validated: `./gradlew :app:assembleDebug` succeeded on2026-06-10 after the helper extractions
- Blocked: reinstall + explicit launch on device `RX8N60B5CZM` could not be completed on2026-06-10 because `adb` reported `device not found`; `adb devices -l` showed `HA1NP62H` as `unauthorized` and `Ucamera001` as `offline`
- Done: `StatusIcons`, `ServiceStatusIcon`, `BackendStatusIcon`, `GatewayControlIcon` extracted from `TooltipIcons.kt` (549 lines) to new `GatewayStatusIcons.kt` (224 lines); `TooltipIcons.kt` is now at356 lines (below400-line guideline)
- Validated (static): `get_errors` clean on `TooltipIcons.kt`, `GatewayStatusIcons.kt`, and `GatewayDashboardUi.kt`
- Done: `GatewayDashboardUi.kt` (825 lines) split into:
  - `GatewayTranscriptCard.kt` (296 lines) — `TranscriptCard`, `TranscriptCardHint`
  - `GatewayOpenClawResponseCard.kt` (109 lines) — `OpenClawResponseCard`, `estimateResponseVisibilityMillis`
  - `GatewayDashboardBanners.kt` (176 lines) — `BlockingAnnouncementBanner`, `InfoAnnouncementBanner`, `formatProbabilityPercent`, `ListeningDotsPlaceholder`, `ActionStrip`
  - `GatewayDashboardUi.kt` now at303 lines — only `DashboardPage` and `SpectrumCard`
- Done: `GatewayConfigComponents.kt` (480 lines) split into:
  - `GatewayDictionaryPage.kt` (118 lines) — `DictionaryPage`
  - `GatewayDeviceGuideSupport.kt` (167 lines) — `DeviceGuideCard`, `buildGuideRecommendationLabel`, `resolveFieldGuideTooltip`, `translateGuideStatus`, `translateExperienceLevel`
  - `GatewayConfigComponents.kt` now at236 lines — `ConfigSection`, `SettingToggleRow`, `MetadataChip`, `configTextFieldColors`, `SliderSettingRow`, `OptionalSliderSettingRow`, `LocalModelOption`, `formatHistoryTimestamp`
- Done: `GatewayConfigSectionsPrimary.kt` (441 lines) split into:
  - `GatewayConfigTranscriptionSection.kt` (276 lines) — `ConfigTranscriptionSectionPage`, `LocalTranscriptionSection`
  - `GatewayConfigSectionsPrimary.kt` now at199 lines — `ConfigGeneralSectionPage`, `ConfigOpenClawSectionPage`
- Validated: `./gradlew :app:assembleDebug` succeeded on2026-06-10 after all extractions (BUILD SUCCESSFUL)
- All gateway package files now at or below400-line guideline
- Fixed: `IllegalArgumentException` crash on launch — `rememberSaveable` used with non-Bundle-safe types (`GatewayDownloadState`, `GatewayModelState`, `GatewayHistoryState`, `GatewayUiState` with `ConfigSectionDestination` enum, `TranscriptCardHint` enum); all four replaced with `remember`
- Validated: `./gradlew :app:assembleDebug` succeeded; uninstall + fresh install on `RX8N60B5CZM` succeeded; `logcat -b crash` shows 0 FATAL EXCEPTIONs after the new build
- Done: app opens and runs on device `RX8N60B5CZM` (Samsung Galaxy A51 SM-A515F)

This keeps risk moderate while immediately reducing Compose-local orchestration.

## Validation Strategy Per Slice
For each migration slice:
1. compile
2. reinstall on `RX8N60B5CZM`
3. validate normal app launch
4. validate background camera startup log
5. validate debug preview path
6. validate gesture gate behavior
7. validate Whisper remote auth path

## Risks
- over-refactoring multiple layers at once may hide regressions
- camera lifecycle can break if capture and preview ownership are not separated carefully
- service/UI race conditions may appear if runtime bridges are changed before state-holder adoption

## Recommended Next Action
Implement Phase 1 and Phase 2 first, without changing CameraX internals yet.
That means introducing the new state types and a `GatewayViewModel`, then migrating `MainActivity` startup and permission logic into that holder before moving deeper runtime pieces.
