# Handoff

## 2026-08-08 · OpenVM bootstrap

### Completed

- Bootstrapped a new Android Gradle project under `org.openvm.app`.
- Added VM profile validation, local JSON persistence, Storage Access Framework image references, export/import, and local history.
- Added backend readiness boundaries for AVF and QEMU without claiming guest execution.
- Added localizable language mode, independent funny levels, emoji toggle, display name, dark theme, regex search builder, and `Ctrl+Shift+F` command palette.
- Added tests and GitHub Actions signing workflow scaffolding.

### Verification boundary

The host toolchain was installed in an isolated user-scoped directory outside the repository. Verified locally with JDK 21, Android SDK platform 35, and Gradle wrapper 8.10.2:

- `./gradlew testDebugUnitTest assembleDebug lintDebug` — passed.
- `./gradlew assembleDebugAndroidTest` — passed; no emulator was available for executing the instrumentation test.
- `./gradlew assembleRelease bundleRelease` — passed; the release APK/AAB outputs remain unsigned until the protected GitHub Actions signing step.
- `actionlint -shellcheck=` — passed structural workflow validation. The host does not have `shellcheck`, so the run-block shell content was not covered by that local actionlint invocation; hosted CI remains the shell verification gate.
- `apksigner verify --verbose --print-certs` — passed for the debug APK.

### Next owner

The next implementation pass should own the native QEMU adapter or AVF adapter as a separate, licensed module. It must not turn the current “not configured” state into a fake “running” state.
