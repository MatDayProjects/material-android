# Native QEMU build lane

## Behavior

OpenVM keeps the Android UI/control plane and the guest engine as separate open-source
components. The native lane builds QEMU 11.0.3 for `arm64-v8a` and `x86_64` Android
hosts using the pinned Termux Android package recipe, patch series, and immutable
Termux package-builder image. It
collects the `aarch64` and `x86_64` system emulators, their transitive shared-library
closure, the runtime portion of QEMU's data tree, and provenance metadata. Documentation,
keymaps, and man pages are excluded from the bundled data tree; `runtime.json` records
the excluded directories, file count, and filtered byte count. The generated runtime is
passed to the Android Gradle build through `OPENVM_QEMU_RUNTIME_DIR`; no binary is stored
in Git.

The packaged executable is named `libopenvm-qemu-{guest}.so` and is installed in
Android's `nativeLibraryDir`. The app maps the profile's guest architecture to the
matching executable, sets the child process library path, and extracts optional QEMU
data to app-private storage. When the generated runtime is absent, the app retains the
explicit Storage Access Framework import route. This makes a source-only APK honest
and keeps the native capability visible in the backend readiness state.

## Configuration

`native/qemu/qemu-build.json` is the single source of build pins:

- official QEMU archive URL, version, and SHA-256;
- Termux package repository, full commit, recipe path, and 20 patch SHA-256 values;
- Android API/ABI/page-size assumptions, enforced as Android API 29+ and 16 KiB ELF
  load alignment; and
- runtime naming and Gradle input contract.

Run the workflow `.github/workflows/android-native-qemu.yml` on a push or with
`workflow_dispatch`. The runtime build matrix creates one host-ABI output for
`arm64-v8a` and one for `x86_64`; the packaging job combines both, builds the debug APK,
and runs instrumentation on an API 35 x86_64 emulator.

## Failure modes

- Invalid manifest values, duplicate or missing patch entries, or an extra patch in the
  pinned Termux revision stop before the build.
- A QEMU archive or Termux patch digest mismatch stops before packaging.
- A missing pinned Termux commit, recipe, builder image digest, or transitive runtime
  library stops the collector; a partial library set is not installable.
- A generated APK without all four host/guest library combinations or either filtered
  QEMU data root is rejected before the emulator job; the workflow also requires a
  non-empty data count, the documented excluded-directory list, and a byte count at or
  below 64 MiB. APK zip alignment is checked for 16 KiB.
- The instrumentation smoke test is skipped only for a source-only APK in ordinary
  local `connectedDebugAndroidTest`; the native workflow passes
  `-PopenvmRequireNativeRuntime=true`, so a missing or skipped runtime test fails that
  job.
- `--version` and `-machine help` prove an executable and its library closure, not an
  Android guest boot. A missing kernel, initrd, raw image, display handshake, or guest
  readiness signal remains a separate failure.

## Security considerations

QEMU and guest images execute with the app process's Android permissions. Source
availability and checksum validation are provenance and integrity controls, not a
guest sandbox. The workflow never accepts a runtime from a mutable app download,
never commits a keystore or binary, and uploads only the generated runtime, APK, test
reports, and provenance evidence. The collector rejects libraries outside the pinned
Termux prefix, validates the Android loader and ELF machine, enforces 16 KiB load
alignment, excludes documentation-only QEMU data trees, and caps the remaining bundled
runtime data at 64 MiB. The app's local manifest/image integrity gate remains required
before a profile starts.

## Verification

Local source-level verification:

```text
bash -n native/qemu/build-android.sh native/qemu/collect-runtime.sh
native/qemu/build-android.sh --verify-only
```

The Android control plane remains covered by `testDebugUnitTest`, `assembleDebug`, and
the API 37 emulator suite. The native workflow adds exact source/patch verification,
ELF/runtime collection, APK member checks, and the API 35 emulator's QEMU
`--version`/`-machine help` smoke test. A real guest boot is still unverified until a
compatible Android guest image and boot readiness contract are exercised.

## Suggested articles

- [QEMU runtime adapter](qemu-runtime.md)
- [Guest-image manifest](guest-image-manifest.md)
- [Android build and release signing](../ci/android-build-and-signing.md)
