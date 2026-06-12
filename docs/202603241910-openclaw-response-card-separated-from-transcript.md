## About

The dashboard now renders the OpenClaw reply in a dedicated card below the transcript/send card.

## Changes

- Removed the OpenClaw reply block from the transcript card.
- Added a dedicated reply card below the transcript area.
- Hid the reply card entirely while a blocking announcement or attention-required state is active.
- Kept the floating blocking banner as the only visible feedback during impediments.
- Simplified the reply card to render only the last assistant text.
- Removed reply tags, confidence, overlap, and status lines from the normal reply card.
- Added automatic fade-out so the last reply disappears shortly after being shown.

## Validation

- `./gradlew.bat :app:compileDebugKotlin :app:assembleDebug`

## Notes

- The service layer was already clearing `lastAssistantReply` when the reply required attention.
- This change completes the UX separation in the dashboard so blocked turns do not show normal assistant output.