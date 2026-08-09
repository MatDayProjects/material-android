# Contributor instructions mirror

This is a sanitized mirror of the shared agent instructions. The canonical instruction source lives outside this repository; edit that source rather than expecting changes here to propagate.

## Repository discipline

- Read this file and the relevant feature documentation before editing.
- Preserve unrelated user work. Use small, reviewable changes and inspect the diff before committing.
- Keep secrets, credentials, signing keys, dependency directories, build output, and scratch files out of Git.
- Use Git for repository operations. Do not rewrite history or force-push unless the user explicitly requests it.
- Commit messages should state the real change in English and include a playful Hong Kong-style Cantonese counterpart in the body.
- Changes must be tested proportionately and documented in `README.md`, the relevant feature article, `ROADMAP.md`, and `HANDOFF.md`.

## Android and release rules

- Keep the app open source, local-first, and honest about which runtime capabilities are available.
- Do not claim that an Android API level, device node, or stored setting proves that a guest VM can boot; verify the native backend and image path separately.
- Never commit a keystore, certificate private key, password, token, or guest image. Release signing uses the encrypted GitHub Actions secret store and verifies artifacts with `apksigner`.
- Code signing is allowed only for the user-requested APK release path and only from the CI secret boundary; signing material never enters source control or logs.
- User-facing copy must remain accessible, localizable, keyboard-operable where applicable, and factual at every funny level.
- Search fields need a local regex builder with bounded patterns, clear engine/flag semantics, validation, and tests.

## Verification

- Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` before handoff when the Android SDK is available.
- Treat a missing Android SDK or remote CI result as an explicit verification boundary, not as a green result.
- Report exact files, commands, failures, and remaining work.

