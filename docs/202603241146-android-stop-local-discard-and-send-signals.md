# Android Stop Local Discard And Send Signals

## About

Android no longer decides whether a captured phrase should be discarded because of low local confidence, ambient markers, or neutral markers.

## Changes

- Kept low-confidence segments flowing into transcription instead of returning early before OpenClaw dispatch.
- Stopped suppressing transcripts such as `[*]` or ambient-only markers after transcription.
- Removed short-transcript sanitizers that converted some suspicious phrases into empty strings.
- Added explicit outbound metadata flags `ambientTranscriptLikely` and `neutralTranscriptMarker`.
- Extended the Android pre-agent normalization and audit trail to surface those flags as context signals only.

## Result

- Android now reports its local impressions.
- The pre-agent or final agent remains responsible for deciding hold, review, discard, or speak behavior.
- Dispatch buffering and transcript merging remain unchanged in this round.

## Validation

- Planned Kotlin validation: `./gradlew.bat :app:compileDebugKotlin`
- Planned JS validation: `npx eslint extensions/android/src/voice-pre-agent.js extensions/android/src/session-audit.js`