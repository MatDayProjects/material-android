# QEMU runtime adapter

OpenVM now contains a process-backed QEMU adapter for profiles whose backend is
`qemu`. It is deliberately headless in this milestone: the adapter proves the
guest process lifecycle and serial output boundary, while the Android guest
display transport and image packaging remain separate work.

## Configuration

1. Create or edit a VM profile.
2. Select **QEMU** as the runtime backend.
3. Import an Android-compatible QEMU executable. It must be an ELF executable
   for the device's host ABI; OpenVM does not download or bundle an opaque binary.
4. Import a bootable raw guest disk image.
5. Select **Start**.

OpenVM copies the selected executable and image from their Storage Access Framework
URI into `files/runtime-assets/`. Each copy is bounded, SHA-256 hashed, written to a
temporary file, and atomically committed. The QEMU controller accepts only regular
files inside that app-private directory.

## Command contract

The controller invokes QEMU through `ProcessBuilder`, never through a shell. It uses
the profile's memory and vCPU limits and selects a portable TCG machine:

- `x86_64`: `q35,accel=tcg`
- `arm64-v8a`: `virt,accel=tcg`

The guest image is attached as a raw virtio disk. The first runtime milestone uses
`-display none`, `-serial stdio`, `-monitor none`, and `-no-reboot`, so the process
and serial boundary are inspectable without pretending that a guest screen exists.
The option meanings follow [QEMU's system-emulation documentation](https://www.qemu.org/docs/master/system/qemu-manpage.html).

## Lifecycle and failure behavior

The controller exposes `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, and `ERROR`.
`RUNNING` is emitted only after a process exists and is still alive. Natural exit,
non-zero exit, missing assets, malformed ELF input, an unexecutable file, an
external path, and invalid profile resources all produce explicit error or stopped
states. Output is retained only as a bounded tail for diagnostics.

Stopping first sends a normal process destroy request and waits for a bounded period;
it then forcefully destroys the process if it has not exited. A restart/import never
restores a running state from profile JSON.

## Security boundary

Guest images execute code with the privileges available to the QEMU process. OpenVM
does not grant a guest extra permissions, upload assets, or run a path outside its
private runtime directory. Resource values are validated before command construction.
The imported executable is not trusted merely because it has a friendly filename;
its ELF header, readability, executability, location, and process result are checked.

The Android Virtualization Framework remains a separate optional backend. Android's
[VirtualizationService](https://source.android.com/docs/core/virtualization/virtualization-service)
is platform-controlled and is not treated as available to an ordinary app solely
because the device API level is new enough.

## Verification

- `QemuRuntimeControllerTest` covers architecture-specific command construction,
  unsupported architectures, private-path enforcement, and natural process exit.
- `RuntimeAssetStoreTest` runs on the API 37 `Pixel_10_Pro_XL` emulator and verifies
  copy, hash, size, and app-private placement.
- `MainActivitySmokeTest` runs on the same emulator and verifies the profile editor
  exposes backend selection and QEMU executable import.

The repository does not yet ship a QEMU binary or claim a verified Android guest boot.
The next runtime milestone must build and package a reproducible QEMU target, define
an Android guest image manifest, and add a real display/console transport before
claiming VMOS-level feature parity.
