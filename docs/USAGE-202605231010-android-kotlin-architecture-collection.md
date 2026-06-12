# Android Kotlin Architecture Collection

## About
This note consolidates official Android guidance and practical architecture direction for `sufficit-android-openclaw-gateway`, focusing on Kotlin, Jetpack Compose, lifecycle-aware state, CameraX, permissions, and foreground-service behavior.

## Official Documentation References
- Android app architecture guide: https://developer.android.com/topic/architecture
- Guide to app architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Compose architecture: https://developer.android.com/develop/ui/compose/architecture
- Compose state: https://developer.android.com/develop/ui/compose/state
- Compose state hoisting: https://developer.android.com/develop/ui/compose/state-hoisting
- Lifecycle-aware flow collection: https://developer.android.com/jetpack/androidx/releases/lifecycle
- Lifecycle overview: https://developer.android.com/topic/libraries/architecture/lifecycle
- CameraX overview: https://developer.android.com/media/camera/camerax
- Foreground services overview: https://developer.android.com/develop/background-work/services/foreground-services
- Permissions best practices: https://developer.android.com/training/permissions/usage-notes
- Coroutines on Android: https://developer.android.com/kotlin/coroutines

## Core Architecture Principles
### 1. Single source of truth
Keep business/runtime state in one authoritative layer. UI should render state, not invent parallel truth. For this project, `GatewayRuntime` can remain the shared runtime state holder, but UI-facing configuration should be funneled through a dedicated state holder instead of ad-hoc local duplication.

### 2. Unidirectional data flow
Follow the Compose recommendation: state flows down, events flow up. Avoid side effects scattered across composables. Gesture detection, microphone gating, Whisper requests, and OpenClaw dispatch should be triggered by explicit events and reflected back as immutable state updates.

### 3. Lifecycle-aware collection
Any `Flow`/`StateFlow` observed by Compose should be collected with lifecycle awareness. The Android guidance favors lifecycle-aware observation so backgrounded UI does not keep collecting needlessly.

### 4. State hoisting
Composable functions should receive state and callbacks, with mutation lifted out whenever the state is shared or business-relevant. The current project already does part of this with `ConfigPageState` and `ConfigPageActions`; extend that pattern to camera-debug and startup orchestration.

## Recommended Target Shape For This Project
### UI layer
- `MainActivity` should become a thin host.
- Compose screens should consume a single screen state object plus callbacks.
- Camera preview visibility state should stay UI-scoped.
- Business state such as gesture gate, active transcription backend, and Whisper auth status should not be owned by composables.

### State holder / ViewModel layer
Introduce a dedicated state holder, preferably `ViewModel`, for:
- startup orchestration
- permission state translation
- settings loading/saving coordination
- camera start policy
- foreground-service command dispatch
- derived UI state for dashboard/debug/config pages

This aligns with official Android recommendations for separating UI rendering from business/state coordination.

### Runtime/service layer
Keep long-running capture and transcription work outside the UI layer:
- foreground microphone capture
- Whisper network calls
- OpenClaw websocket traffic
- camera gesture analysis engine

The service/runtime layer should expose observable state and explicit commands, not rely on implicit Compose timing.

## Concrete Guidance For Current Pain Points
### Camera startup
The latest logs already show the camera binding can happen while the activity becomes stopped. That means camera startup policy should be explicit and testable:
- define one startup decision point
- log why camera should start or not start
- separate "capture active" from "preview visible"
- do not let debug-page navigation be the trigger for the real capture pipeline

### Gesture gate and microphone gate
The gate should be modeled as an explicit state machine, not a loose boolean mirrored in multiple places.
Suggested states:
- `Disabled`
- `WaitingForCamera`
- `WaitingForGesture`
- `GestureMatched`
- `MicrophoneOpen`
- `Error`

This makes logs, UI messages, and service behavior consistent.

### Whisper authentication
Treat Whisper auth as configuration integrity, not only network behavior.
Recommended safeguards:
- normalize blank token to seeded/runtime default on load
- normalize blank token again before persisting settings
- surface a clear runtime status when token is blank
- log whether Authorization will be sent, but never log the token value itself

## Compose-Specific Recommendations
- Prefer smaller screen-specific state classes over many unrelated `rememberSaveable` values.
- Use `remember` only for UI-local ephemeral concerns.
- Keep suspend side effects inside lifecycle-aware effects or state-holder scopes.
- Avoid chaining important runtime actions directly to recomposition-sensitive conditions unless those conditions are idempotent and instrumented.

## CameraX-Specific Recommendations
- Keep a single owner for camera bind/unbind decisions.
- Treat `bindToLifecycle(...)` success as the authoritative startup checkpoint.
- Log camera provider acquisition, bind attempt, bind success, and unbind reason separately.
- If background capture must survive screen changes, validate whether Activity lifecycle is the correct owner or whether architecture should move to a lifecycle owner with clearer persistence semantics.

## Foreground-Service Recommendations
- The service should own microphone capture policy.
- UI should request start/stop, but the service should publish the real active state.
- Service notification text should reflect the same gate state machine used by runtime/UI.

## Suggested Refactoring Order
1. Create a dedicated gateway state holder / ViewModel.
2. Move startup policy and permission orchestration out of `MainActivity` body.
3. Model camera/microphone gate as a finite state machine.
4. Centralize Whisper config normalization and validation.
5. Reduce direct cross-calls between UI callbacks and service/camera internals.
6. Add structured diagnostic logs around state transitions.

## What To Preserve
- JSON-first configuration rule
- background capture intent
- debug preview isolated from normal startup UI
- explicit compile/install validation on every code change

## Practical Next Step
Before broad refactoring, create one focused pass that introduces a dedicated state holder for:
- permission state
- startup mode
- camera capture active state
- preview visible state
- whisper auth validity
- gesture gate state

That single change will reduce ambiguity and make the remaining camera/gesture issues much easier to isolate.
