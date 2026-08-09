# Native QEMU build lane

## Behavior

OpenVM keeps the Android UI/control plane and the guest engine as separate open-source
components. The native lane builds QEMU 11.0.3 for `arm64-v8a` and `x86_64` Android
hosts using the pinned Termux Android package recipe, patch series, and immutable
Termux package-builder image. It
collects the `aarch64` and `x86_64` system emulators, their transitive shared-library
closure including versioned names such as `libz.so.1`, an allowlisted set of QEMU data
files needed by OpenVM's headless `q35`/`virt` command paths, and provenance metadata.
Documentation, keymaps, non-target firmware, and unused ROMs are omitted; `runtime.json`
records the allowlist policy, exact file list, file count, and byte count. The generated
runtime is passed to the Android Gradle build through `OPENVM_QEMU_RUNTIME_DIR`; no binary
is stored in Git.

The packaged executable is named `libopenvm-qemu-{guest}.so` and is installed in
Android's `nativeLibraryDir`. The complete dependency closure is kept as
`assets/native-qemu/{abi}/lib`, including `.so.*` files that JNI packaging would drop.
The app materializes those flat assets to app-private storage, checks an exact filename
and SHA-256 marker, and places that directory first in `LD_LIBRARY_PATH`; QEMU data is
materialized separately and passed with `-L`. When the generated runtime is absent, the
app retains the explicit Storage Access Framework import route. This makes a source-only
APK honest and keeps the native capability visible in the backend readiness state.

## Configuration

`native/qemu/qemu-build.json` is the single source of build pins:

- official QEMU archive URL, version, and SHA-256;
- Termux package repository, full commit, recipe path, and 20 patch SHA-256 values;
- Android API/ABI/page-size assumptions, enforced as Android API 29+ and 16 KiB ELF
  load alignment; and
- runtime naming and Gradle input contract.

Run the workflow `.github/workflows/android-native-qemu.yml` on a push or with
`workflow_dispatch`. The runtime build matrix creates one host-ABI output for
`arm64-v8a` and one for `x86_64`; the packaging job combines both and builds the debug
app and instrumentation APKs. Both are explicitly unsigned; the workflow rejects JAR
signature metadata, APK Signing Blocks, `.idsig` files, and generated signing material,
then requires `aapt2`, `zipalign`, and `apksigner` to produce their expected independent
verdicts. Only packages that pass every check are copied into the uploaded staging
directory; a rejected signed package stays out. Android refuses to install an unsigned APK, so this workflow does not launch
instrumentation or claim an on-device result. It records `instrumentation-skip.txt`
beside the static package and unit-test evidence. The application bootstrap still keeps
QEMU controller construction deferred until the Activity requests it.

## Failure modes

- Invalid manifest values, duplicate or missing patch entries, or an extra patch in the
  pinned Termux revision stop before the build.
- A QEMU archive or Termux patch digest mismatch stops before packaging.
- A missing pinned Termux commit, recipe, builder image digest, or transitive runtime
  library stops the collector; a partial library set is not installable.
- A generated APK without all four host/guest executable combinations, either versioned
  dependency asset, or either allowlisted QEMU data root is rejected before the emulator
  job; the workflow also requires a
  non-empty data count, an exact match with `qemu-build.json`, and a byte count at or
  below 64 MiB. APK structure, package metadata, and zip alignment are checked before
  the no-signature verdict is accepted.
- Android installation and instrumentation are unavailable for the current unsigned
  APK. Any historical debug-signed instrumentation run is diagnostic history, not
  evidence for the current build contract.
- `--version` and `-machine help` prove an executable and its library closure, not an
  Android guest boot. A missing kernel, initrd, raw image, display handshake, or guest
  readiness signal remains a separate failure.

## Security considerations

QEMU and guest images execute with the app process's Android permissions. Source
availability and checksum validation are provenance and integrity controls, not a
guest sandbox. The workflow never accepts a runtime from a mutable app download,
never creates or uses a signing identity, never commits a keystore or binary, and
uploads only the generated runtime, post-verification unsigned APKs, unit-test reports,
CI context manifest, boundary record,
and provenance evidence. The collector rejects libraries outside the pinned
Termux prefix, validates the Android loader and ELF machine, enforces 16 KiB load
alignment, uses an explicit QEMU data allowlist, and caps the bundled runtime data at
64 MiB. The app's local library marker rejects stale, incomplete, extra, or modified
dependency files before reuse. The local manifest/image integrity gate remains required
before a profile starts. UEFI and other machine-specific firmware are not claimed by
this runtime lane.

## Verification

Local source-level verification:

```text
bash -n native/qemu/build-android.sh native/qemu/collect-runtime.sh
native/qemu/build-android.sh --verify-only
```

The Android control plane remains covered by `testDebugUnitTest`, explicitly unsigned
`assembleDebug`, and explicitly unsigned `assembleDebugAndroidTest` artifacts. The native
workflow adds exact source/patch verification, ELF/runtime collection, versioned APK
member checks, archive/package/alignment checks, and independent signature-structure
checks. No current workflow installs the APK or proves QEMU
execution on Android. A real guest boot is still unverified until a compatible Android
guest image and boot readiness contract are exercised.

## Suggested articles

- [QEMU runtime adapter](qemu-runtime.md)
- [Guest-image manifest](guest-image-manifest.md)
- [Android build and unsigned release artifacts](../ci/android-build-and-signing.md)
