# Voice Friendly Replies And Response Fade

## About

This round improves how Android presents and speaks assistant replies.

## Changes

- The OpenClaw reply card now stays visible for at least 10 seconds.
- The visible duration now scales with the estimated speech time of the reply text.
- Android now prefers the spoken reply variant when one is available.
- Local TTS now suppresses replies that still look like raw code or highly technical snippets.
- The Android final-agent bootstrap now explicitly forbids raw code-heavy answers in normal voice replies and allows `spokenReplyText` for structured responses.

## Log Review

- Recent Android logs confirmed that replies were reaching TTS normally, but the app log only preserves a truncated slice of the raw OpenClaw payload.
- In the problematic window, Samsung TTS received synthetic content while the payload preview was truncated, so the safest fix is to harden both the final-agent contract and the Android-side speech sanitization.

## Validation

- `./gradlew.bat :app:compileDebugKotlin`
- `npx eslint extensions/android/src/voice-pre-agent.js --fix`