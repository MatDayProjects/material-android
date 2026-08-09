# Roadmap

## 0.1.x · Control plane hardening

- Add screenshot-driven UI regression coverage on supported Android API levels.
- Add profile duplication, bulk export, and explicit image metadata validation.
- Add an in-app documentation browser for the feature articles.
- ✅ Keep every generated APK/AAB unsigned and fail closed on JAR signatures, APK
  Signing Blocks, detached signatures, or generated signing material.

## 0.2.x · Native QEMU adapter

- ✅ Add the process-backed QEMU lifecycle, private asset materialization, resource validation, and bounded serial diagnostics.
- ✅ Add and verify the isolated, reproducible QEMU native module from pinned upstream source, including both host-ABI executables and the Android APK/AAB packaging path. On-device Android guest boot remains the separate shipping gate below.
- ✅ Define and enforce the version-1 guest-image manifest, raw-image integrity metadata, and supported ARM64/x86_64 Android machine types.
- ✅ Add a private UNIX-socket VNC/RFB framebuffer transport boundary.
- ✅ Add bounded guest touch/key input over the private RFB channel.
- Add serial console presentation and host↔guest file-transfer.
- Ship only after a real Android guest boot is verified on documented hardware.

## 0.3.x · AVF adapter

- Integrate Android Virtualization Framework through the supported system service boundary.
- Verify Microdroid/guest image packaging, vsock communication, and device capability checks.
- Keep AVF support optional; the app must continue to explain unsupported devices accurately.

## Compatibility target

The control plane targets Android 8.0/API 26 and newer. Native backends will publish narrower API/device matrices based on evidence rather than the UI's minimum SDK.
