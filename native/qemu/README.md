# Native QEMU build lane

This directory contains the reproducible source and packaging contract for OpenVM's
optional bundled QEMU runtime. It is deliberately separate from the Android control
plane: QEMU remains upstream GPL-licensed software, and the Android application is
responsible for the local process, image, manifest, display, and input boundaries.

## Source and configuration

`qemu-build.json` pins QEMU 11.0.3 by the official source archive SHA-256, pins the
Termux Android package-tree revision and its complete Android patch series, and pins
the public Termux package-builder container by an immutable digest. Termux's recipe is
used because upstream QEMU does not list Android as a maintained host build platform.
The recipe and patches remain open source and are fetched at the exact Git revision
recorded in the manifest; no QEMU source or binary is copied into this repository.

The build currently targets `arm64-v8a` and `x86_64` Android hosts, uses Android API 29
or newer, and records the required 16 KiB page-size assumption. Each host build emits
the `aarch64` and `x86_64` QEMU system executables, their transitive Termux shared
library closure, an allowlisted set of QEMU data files needed by OpenVM's headless
`q35`/`virt` command paths, and a provenance JSON file. The allowlist deliberately
omits documentation, keymaps, non-target firmware, and unused ROMs; `runtime.json`
records the policy, exact file list, file count, and byte count. The output is a build
artifact, not tracked source.

## Build

From a Linux host with Docker, `jq`, `curl`, Git, `sha256sum`, and `readelf`:

```bash
./native/qemu/build-android.sh --verify-only
./native/qemu/build-android.sh --host-abi x86_64 --output "$RUNNER_TEMP/openvm-qemu/x86_64"
```

The build downloads the official QEMU archive for preflight verification, clones the
exact Termux revision, compares the manifest's patch list with every patch in that
revision, runs the digest-pinned package builder, extracts only the runtime prefix from
the disposable builder container, and collects the dependency closure. The provenance
file records the container, recipe, patch-manifest, source, API, and page-size hashes.

Generated runtime directories must stay outside Git. The build script creates a unique,
marker-owned temporary child and never recursively removes a caller-owned work parent;
the collector refuses to replace an existing output directory. The Android Gradle build accepts
`OPENVM_QEMU_RUNTIME_DIR` and copies a supplied runtime into generated `jniLibs` and
assets. A normal debug build without that variable remains source-only and does not
silently download a runtime.

## Android packaging and runtime behavior

QEMU executables are renamed to `libopenvm-qemu-{guest}.so` and are installed by Android
in `ApplicationInfo.nativeLibraryDir`. This avoids attempting to execute code copied
into ordinary app data on Android configurations that enforce W^X. The app sets a
bounded library search path for the child process and extracts the optional QEMU data
directory into its private `runtime-assets/native-qemu/{abi}` directory. A bundled
runtime is selected only when its host ABI and guest executable are both available;
otherwise the existing user-imported executable route remains available.

The bundled native runtime is selected only on Android API 29 or newer and only after
the host ABI, executable mode, and guest executable are available. The bundle is not a
guest image. Users still provide a bootable image and the strict
guest-image manifest. A QEMU process reaching `RUNNING`, `--version`, or `-machine help`
does not prove that Android booted; the guest-boot harness remains a separate gate.

## Failure modes and security

- A source, recipe, patch-set, builder-image, ELF loader/machine, or 16 KiB segment
  alignment mismatch stops the build before packaging.
- A missing Termux revision, recipe, builder image digest, or runtime dependency stops
  the build; no partial runtime is treated as installable.
- The collector refuses libraries outside the Termux prefix, missing transitive
  dependencies, output-root replacement, wrong Android interpreter, wrong ABI, invalid
  segment alignment, a missing allowlisted QEMU data file, or allowlisted QEMU runtime
  data larger than 64 MiB.
- The app never downloads QEMU, guest images, or libraries at runtime. All packaged
  bytes are produced by the workflow and checked by the artifact manifest.
- QEMU and the guest execute with the OpenVM process privileges. Packaging an open
  source binary does not make an untrusted guest image safe.

## Verification

The native workflow verifies the manifest, source digest, Termux commit, ELF output,
runtime dependency closure, generated APK contents, QEMU `--version`, and QEMU
`-machine help` on an Android emulator. It also uploads the runtime, APK, provenance,
and safe build reports on both successful and failed attempts. A real Android guest
boot is not claimed until a compatible kernel, initrd, raw image, display, input path,
and guest readiness signal have all been exercised.

## Suggested articles

- [QEMU runtime adapter](../../docs/features/qemu-runtime.md)
- [Guest-image manifest](../../docs/features/guest-image-manifest.md)
- [Android build and release signing](../../docs/ci/android-build-and-signing.md)
