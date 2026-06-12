# Centralized Gateway Config Json

## About

Operational settings that were previously scattered in Kotlin are now centralized in a JSON configuration flow.

## Scope

- all persisted gateway parameters

## Source Of Truth

- Seed asset: `app/src/main/assets/config.json`
- Runtime database file: internal `files/config.json`
- Loader and runtime persistence: `GatewayConfigCatalog.kt`

`GatewaySettingsStore` now loads from `files/config.json`, saves every update back to the same file, and seeds the first version from `assets/config.json`.

## Behavioral Notes

- Existing installs can still migrate from the legacy SharedPreferences store on first load.
- The managed Whisper endpoint detection now compares against the host extracted from the JSON seed, instead of a hardcoded domain string.
- A single in-code fallback remains only as a bootstrap safety net if the JSON seed is missing or invalid.

## Validation

- Branch verified: `main`
- Kotlin compile: `./gradlew.bat :app:compileDebugKotlin`
- Result: success