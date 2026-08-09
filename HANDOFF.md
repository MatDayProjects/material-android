# Handoff

## 2026-08-09 · OpenVM bootstrap and protected signing

### Completed

- Bootstrapped a new Android Gradle project under `org.openvm.app`.
- Added VM profile validation, local JSON persistence, Storage Access Framework image references, export/import, and local history.
- Added backend readiness boundaries for AVF and QEMU without claiming guest execution.
- Added a process-backed QEMU lifecycle with private asset copying, ELF/path checks,
  deterministic TCG command construction, bounded output, stop timeouts, and explicit
  exit states. The app still requires a user-supplied QEMU executable and bootable image.
- Added localizable language mode, independent funny levels, emoji toggle, display name, dark theme, regex search builder, and `Ctrl+Shift+F` command palette.
- Added unit and instrumentation test coverage plus a protected GitHub Actions workflow
  that signs and verifies release APK/AAB artifacts from an encrypted environment.

### Verification boundary

The host toolchain was installed in an isolated user-scoped directory outside the repository. Verified locally with JDK 21, Android SDK platform 35, and Gradle wrapper 8.10.2:

- `./gradlew --no-daemon --no-scan testDebugUnitTest assembleRelease bundleRelease` with JDK 21 — passed; ordinary local release outputs remain unsigned unless the protected signing environment is explicitly supplied.
- `./gradlew --no-daemon --no-scan testDebugUnitTest lintDebug assembleDebugAndroidTest` with JDK 21 — passed.
- `./gradlew --no-daemon --no-scan connectedDebugAndroidTest` — passed on the installed `Pixel_10_Pro_XL` API 37 emulator: 3 instrumentation tests passed.
- `actionlint -shellcheck=` — passed structural workflow validation. The host does not have `shellcheck`, so the run-block shell content was not covered by that local actionlint invocation; hosted CI remains the shell verification gate.
- `apksigner verify --verbose --print-certs` — passed for the debug APK.

## Hosted signing evidence

The pushed commit [`1c2a439`](https://github.com/MatDayProjects/material-android/commit/1c2a43979bc8324529e71d28ec8ec44d3f85f6cb) was verified by [GitHub Actions run 31290128594](https://github.com/MatDayProjects/material-android/actions/runs/31290128594).

- Debug validation passed.
- The protected release job passed Gradle signing for both `app-release.apk` and
  `app-release.aab`.
- The uploaded evidence contains `verification.txt`, `SHA256SUMS.txt`, the signed APK,
  and the signed AAB.
- APK SHA-256: `4b42a529d3a632a913a3402664235d9d20af943ba0acff722ad6e863280cd8a8`.
- AAB SHA-256: `548f4d28893b90ca1e52380d688ab61c14becb45c923d0fcc17e11d0df632398`.
- Public signing certificate SHA-256: `0dbe4aecd0c1911f6b8cb5a736efe3823ed2753cfa193464b40bf5a1f25b7428`.
- The private keystore and passwords remain only in the protected GitHub Actions
  environment and are not stored in this repository.

### Next owner

The next implementation pass should package a reproducible QEMU native executable, define the Android guest image manifest, and add a real guest display/console transport. It must not turn the current headless process boundary into a fake Android guest screen. Actual guest boot remains unverified until a compatible QEMU binary and bootable Android image are supplied.
