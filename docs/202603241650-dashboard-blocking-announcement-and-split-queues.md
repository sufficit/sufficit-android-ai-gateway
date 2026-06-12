# Dashboard Blocking Announcement And Split Queues

## About

The transcription dashboard now separates blocking OpenClaw announcements from the transcript flow and distinguishes pending transcription from pending OpenClaw dispatch.

## Changes

- Added a dedicated runtime counter for pending OpenClaw dispatch.
- Kept the transcription queue counter focused on transcription backlog only.
- Added a second top badge in the transcription card for OpenClaw dispatch status.
- Moved blocking OpenClaw attention messages to a single floating banner fixed at the bottom of the screen.
- Limited that banner to one active blocking message at a time.
- Kept regular assistant replies inside the transcription card only when they are not blocking.

## Runtime Behavior

- The dispatch badge lights up when a phrase is buffered or being sent to OpenClaw.
- The blocking banner is populated only for `needsAttention` replies.
- When OpenClaw returns no explicit reply text, the app now synthesizes a human-readable blocking reason from tags, overlap, or confidence signals.

## Validation

- Kotlin compile: `./gradlew.bat :app:compileDebugKotlin`
- APK build: `./gradlew.bat :app:assembleDebug`
- Device install: `adb install -r app-debug.apk`
- Activity foreground confirmed twice on `RX8N60B5CZM`