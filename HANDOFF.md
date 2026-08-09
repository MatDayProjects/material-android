# Handoff

## 2026-08-09 · Explicit unsigned debug enforcement

### Current state

- Commit [`52300f7`](https://github.com/MatDayProjects/material-android/commit/52300f74319f74ebea6fc7fbf025b4644df22976)
  contains the complete unsigned-artifact correction, verifier scripts, regression
  tests, workflow staging/context gates, bundletool pin, and documentation. Hosted
  replacement verification remains pending.
- Final artifact inspection found that the Android Gradle Plugin's default debug
  configuration had signed `app-debug.apk` with an auto-generated `Android Debug`
  certificate. That artifact and the earlier debug-signed instrumentation runs are
  historical diagnostics; they do not satisfy the repository's no-signing contract.
- Both `debug` and `release` now set `signingConfig = null`. Android CI, the native QEMU
  package lane, and the release lane build the debug app, instrumentation, and release
  packages in disposable home directories. The committed verifier rejects v1/JAR
  metadata, APK Signing Blocks in APK and AAB containers, detached v4 `.idsig` files,
  malformed archives, generated signing material, and symbolic-link escapes before
  `aapt2`, `zipalign`, `apksigner`, `jarsigner`, or pinned `bundletool` add independent
  checks. Workflows copy packages into upload staging only after every check passes.
- The native lane no longer attempts installation or instrumentation. Stock Android
  refuses unsigned APK installation, and the project does not create or use the signing
  identity that installation would require. `instrumentation-skip.txt` records this
  boundary alongside the static package and unit-test evidence.
- Android guest kernel/initrd/raw-image boot, display readiness, serial console, and
  host-to-guest file transfer remain unverified.

### Verification in this lane

- The superseded default-branch APK from run `31319585312` has SHA-256
  `43b7f8d6639e1af51fee91a3d6621b633dcd3592704a9eefaaffeee4371a13e1` and
  `apksigner verify --print-certs` reports one `CN=Android Debug` signer. This is the
  concrete evidence that triggered the correction; it is not an approved release
  artifact.
- A fresh-home local `assembleDebugAndroidTest` completed successfully. The debug app,
  instrumentation APK, release APK, and release AAB all passed the committed unsigned
  verifier; 17 security regression tests passed (10 artifact-parser tests and 7 workflow
  contract tests), including APK/AAB Signing Blocks, symlink escapes, staging coverage,
  and signing-command detection; pinned bundletool 1.18.3 validated the real release AAB; and the
  verifier rejected the superseded debug-signed APK above.
- Two independent read-only reviews found and prompted fixes for AAB Signing Block
  coverage, signed failure-artifact uploads, symlink escapes, exact instrumentation APK
  discovery, AAB semantic validation, locale-stable verifier output, and per-job context
  manifests. The review note that the new scripts were not yet tracked is resolved by
  including them in the correction commit rather than staging only pre-existing files.
- Repository-level GitHub Actions secrets are empty, and there is no `signing`
  environment. The authenticated account cannot inspect the existing `android-release`
  environment's secret-name inventory (`HTTP 403: Must have admin rights to Repository`),
  so that names-only external-state audit remains unverified. No workflow references an
  environment secret or invokes a signing operation.
- Hosted replacement-run evidence is recorded after the corrected workflows complete.

## 2026-08-09 · Bounded hosted emulator validation

### Current state

- Commit [`2aa0083`](https://github.com/MatDayProjects/material-android/commit/2aa00832bbfab20ee5159a633e5426c6ced5abaf) removes QEMU controller class resolution from `Application` startup and keeps the production controller in a process-level store. `MainActivity` still obtains the same controller lazily, so Activity recreation does not discard the running guest.
- The diagnostic run [`31312096672`](https://github.com/MatDayProjects/material-android/actions/runs/31312096672) proved the previous failure was an Android startup ANR under software-only x86_64 emulation: the emulator booted in 621.570 seconds, `system_server` reached 92% CPU, `org.openvm.app.debug` reached 64% CPU, and `ActivityManager` killed the target for `failed to complete startup`. The XML reported 0 tests because instrumentation never attached; this was not a native QEMU library crash.
- The graphics retry [`31316724775`](https://github.com/MatDayProjects/material-android/actions/runs/31316724775) was canceled after its smoke step stayed active for 15m22s without a test report; artifact [`9039286818`](https://github.com/MatDayProjects/material-android/actions/runs/31316724775/artifacts/9039286818) preserves the APK and logcat. The log shows Android framework services still starting under software-only emulation, not a native QEMU library failure.
- Commit [`b2f59e2`](https://github.com/MatDayProjects/material-android/commit/b2f59e2c6dcfcab001df4792c514e97f6a8a082d) gates live instrumentation on readable `/dev/kvm`, writes `emulator-skip.txt` when the hosted runner lacks it, combines the focused classes into one installation, and force-kills an overlong live process.
- Native run [`31318099320`](https://github.com/MatDayProjects/material-android/actions/runs/31318099320) is green: arm64-v8a 11m00s, x86_64 11m57s, and package 1m53s. Android CI [`31318099312`](https://github.com/MatDayProjects/material-android/actions/runs/31318099312) is green in 1m15s. Artifact [`9039518910`](https://github.com/MatDayProjects/material-android/actions/runs/31318099320/artifacts/9039518910) contains 43 unit tests with 0 failures/errors and the explicit no-KVM evidence. The Android guest kernel/initrd/raw-image boot boundary remains unverified.

### Verification in this lane

- Java 17 `testDebugUnitTest assembleDebug` — passed locally.
- Focused packaged `NativeQemuRuntimeSmokeTest` — passed locally, 2/2, using the x86_64 runtime artifact from run `31312096672` without adding generated binaries to the checkout.
- `actionlint -shellcheck= .github/workflows/android-native-qemu.yml` — passed locally.
- Native hosted collectors and package checks — passed in run `31318099320`; live instrumentation was explicitly skipped because `/dev/kvm` was not readable.
- `git diff --check` — passed.


## 2026-08-09 · Native runtime dependency packaging fix

### Current state

- QEMU root executables remain Android JNI libraries; the complete Termux dependency
  closure, including versioned files such as `libz.so.1`, is packaged under
  `assets/native-qemu/{abi}/lib` so Android does not drop `.so.*` names.
- `NativeQemuRuntime` materializes the flat asset set into app-private storage, records
  and validates an exact filename/SHA-256 marker, and rebuilds stale or incomplete
  caches atomically. `QemuRuntimeController` passes that ABI-specific directory first
  in `LD_LIBRARY_PATH`, followed by Android's native library directory, without
  inheriting an ambient library path.
- The native workflow's API 35 headless job selects the native runtime and asset-store
  instrumentation classes explicitly. UI focus tests remain in the API 37 local/control
  plane suite rather than being mixed into the process-level native gate.
- Android release artifacts are intentionally unsigned. No certificate, keystore,
  private key, or signing secret is generated or used by this task or the workflow.

### Verification in this lane

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest` — passed with JDK 21.
- `connectedDebugAndroidTest` — passed on `Pixel_10_Pro_XL` API 37; 6/6 tests.
- The exact native class-filtered Gradle invocation — passed; 2/2 native tests.
- `RuntimeAssetStoreTest` through the exact class-filtered invocation — passed; 2/2.
- APK inspection confirmed both ABI asset trees contain `libz.so.1` and
  `libz.so.1.3.2`.
- Hosted run `31305007119` is green at `61e33cb`: both ABI collectors, retry-safe
  artifact handoff, APK packaging, API 35 emulator boot, and the focused native plus
  asset-store instrumentation classes passed 2/2 each. The earlier `31304067368`
  failure was a workflow command-quoting issue and is superseded by this run.
- Android guest boot, serial console, and file transfer remain unverified.


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
- Hosted run `31305007119` verified the native build and Android-target QEMU smoke test;
  a real Android guest boot is still unverified.

### Next owner

The hosted native workflow is green. Keep the artifact and test evidence linked to the
handoff, then prepare a documented bootable Android guest image for the separate
guest-boot harness. Do not interpret QEMU probes or a running process as Android guest
readiness.

## 2026-08-09 · OpenVM bootstrap and unsigned Android artifacts

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
- Added unit and instrumentation test coverage plus a GitHub Actions workflow that builds
  and verifies reproducible unsigned release APK/AAB artifacts without signing inputs.

### Verification boundary

The host toolchain was installed in an isolated user-scoped directory outside the repository. Verified locally with JDK 21, Android SDK platform 35, and Gradle wrapper 8.10.2:

- `./gradlew --no-daemon --no-scan testDebugUnitTest assembleRelease bundleRelease` with JDK 21 — passed; release outputs remain unsigned by design.
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
- `apksigner verify --verbose --print-certs` — historically reported an `Android Debug`
  signer for the debug APK. That implicit signing path is superseded by the explicit
  unsigned-build correction above.

## Artifact policy

The current release workflow builds unsigned APK/AAB artifacts and publishes checksums;
it does not create or use a signing identity. Earlier historical automation may have
produced signed artifacts, but that path is no longer active and must not be restored.

### Next owner

The next implementation pass should package a reproducible QEMU native executable, then add serial-console presentation and host↔guest file transfer. The current framebuffer/input surface and manifest validation must not be mistaken for a booted Android guest. Actual guest boot remains unverified until a compatible QEMU binary and bootable Android image are supplied.
