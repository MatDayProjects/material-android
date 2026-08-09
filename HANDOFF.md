# Handoff

## 2026-08-09 · Native QEMU build and packaging lane

### Completed in this lane

- Added `native/qemu/qemu-build.json`, pinning QEMU 11.0.3, its official archive
  SHA-256, the Termux Android package-tree commit, the QEMU recipe, the immutable
  Termux package-builder image digest, and all 20 Android patch SHA-256 values.
- Added the disposable Linux/Docker build path in `native/qemu/build-android.sh` and
  `native/qemu/collect-runtime.sh`. It verifies every source and patch digest, builds
  through the open Termux recipe, compares the complete patch set, validates Android
  ELF loader/machine/16 KiB alignment, collects only the transitive runtime closure and
  bounded, manifest-owned QEMU runtime data (an allowlist for the headless `q35`/`virt`
  paths), and records builder/recipe/patch provenance plus the data byte count.
- Added generated Gradle packaging through `OPENVM_QEMU_RUNTIME_DIR`. Packaged QEMU
  executables live in Android `nativeLibraryDir`; the app searches their adjacent
  libraries and extracts optional data into app-private storage. Source-only APKs keep
  the user-imported executable route.
- Added native runtime discovery, QEMU `-L` data-directory construction, host-ABI
  validation, and an instrumentation smoke test for QEMU `--version` and
  `-machine help`.
- Added the native workflow and its hand-written dependency inventory. It builds both
  host ABIs, packages the APK, validates all four host/guest library combinations and
  both data roots, pins every action by commit SHA, and runs the production controller
  smoke test with a required-runtime flag. The release workflow consumes the same
  generated runtime before building APK/AAB artifacts.

### Local verification

- `./gradlew.bat testDebugUnitTest assembleDebug` — passed with JDK 21 and Android SDK
  platform 35; 43 unit tests ran successfully with zero failures/errors.
- `./gradlew.bat connectedDebugAndroidTest` — passed on `Pixel_10_Pro_XL` API 37;
  6 instrumentation tests were recorded with zero failures/errors; 4 executed and 2
  native-runtime tests were expected source-only skips.
- `bash -n native/qemu/build-android.sh native/qemu/collect-runtime.sh` and ShellCheck
  — passed through Git for Windows Bash with the isolated tool binaries.
- `native/qemu/build-android.sh --verify-only` — passed locally; the JSON, immutable
  builder-image, Android packaging contract, patch count, path safety, and duplicate
  checks all validated.
- The hosted native build and Android-target QEMU smoke test have not run yet. A real
  Android guest boot is still unverified.

### Next owner

Review the first hosted native workflow run, inspect the built runtime's actual dynamic
loader/ABI behavior, and only then prepare a documented bootable Android guest image
for the separate guest-boot harness. Do not interpret QEMU probes or a running process
as Android guest readiness.

## 2026-08-09 · OpenVM bootstrap and protected signing

### Completed

- Bootstrapped a new Android Gradle project under `org.openvm.app`.
- Added VM profile validation, local JSON persistence, Storage Access Framework image references, export/import, and local history.
- Added backend readiness boundaries for AVF and QEMU without claiming guest execution.
- Added a process-backed QEMU lifecycle with private asset copying, ELF/path checks,
  deterministic TCG command construction, bounded output, stop timeouts, cancellation
  before launch, and explicit exit states. The app still requires a user-supplied QEMU
  executable and bootable image.
- Added a private UNIX-domain VNC/RFB framebuffer transport and a real display surface
  for running profiles. It now sends bounded touch/key events over the same local
  channel; guest boot readiness, serial console, and file transfer remain unverified.
- Added a strict version-1 guest-image manifest. QEMU profiles now require the
  manifest, its architecture/machine pair must match the profile, and the selected
  raw image's size and SHA-256 must match before launch. Kernel/initrd contracts
  validate safe relative paths plus size/SHA-256 metadata and pass selected artifacts
  through explicit QEMU arguments. Manifest bytes use strict UTF-8 and bounded JSON
  nesting; runtime assets use unique atomic staging files and are excluded from
  Android backup/transfer.
- Commit [`af43751`](https://github.com/MatDayProjects/material-android/commit/af43751fb7b1ed162242e739a131c36f6b4706d9) completes this milestone's source,
  tests, documentation, and API 37 evidence. The application-owned QEMU controller
  survives Activity recreation; start generations prevent cancelled work from
  resurrecting; RFB input rejects letterbox clicks, forwards modifier keys, and
  bounds update pixels; failed display connections unregister themselves.
- Added localizable language mode, independent funny levels, emoji toggle, display name, dark theme, regex search builder, and `Ctrl+Shift+F` command palette.
- Added unit and instrumentation test coverage plus a protected GitHub Actions workflow
  that signs and verifies release APK/AAB artifacts from an encrypted environment.

### Verification boundary

The host toolchain was installed in an isolated user-scoped directory outside the repository. Verified locally with JDK 21, Android SDK platform 35, and Gradle wrapper 8.10.2:

- `./gradlew --no-daemon --no-scan testDebugUnitTest assembleRelease bundleRelease` with JDK 21 — passed; ordinary local release outputs remain unsigned unless the protected signing environment is explicitly supplied.
- `./gradlew --no-daemon --no-scan testDebugUnitTest lintDebug assembleDebugAndroidTest` with JDK 21 — passed.
- `./gradlew --no-daemon --no-scan connectedDebugAndroidTest` — passed on the installed `Pixel_10_Pro_XL` API 37 emulator: 4 instrumentation tests passed.
- `./gradlew --no-daemon --no-scan testDebugUnitTest` — passed after the RFB, collision,
  and cancellation changes; the next integration run must repeat the API 37 emulator gate.
- `./gradlew.bat testDebugUnitTest` — passed with strict manifest, kernel/initrd
  integrity, nesting/UTF-8, closed-controller, RFB, and profile coverage.
- `./gradlew.bat connectedDebugAndroidTest` — passed on `Pixel_10_Pro_XL` API 37:
  4 instrumentation tests passed after the final build; the editor controls were
  captured at the initial and scrolled positions.
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

The next implementation pass should package a reproducible QEMU native executable, then add serial-console presentation and host↔guest file transfer. The current framebuffer/input surface and manifest validation must not be mistaken for a booted Android guest. Actual guest boot remains unverified until a compatible QEMU binary and bootable Android image are supplied.
