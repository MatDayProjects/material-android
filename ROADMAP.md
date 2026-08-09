# Roadmap

## 0.1.x · Control plane hardening

- Add screenshot-driven UI regression coverage on supported Android API levels.
- Add profile duplication, bulk export, and explicit image metadata validation.
- Add an in-app documentation browser for the feature articles.

## 0.2.x · Native QEMU adapter

- Add an isolated, reproducible QEMU native module from upstream source.
- Define the guest image manifest and supported ARM64/x86_64 machine types.
- Add lifecycle ownership, process supervision, resource limits, and a local console.
- Ship only after a real guest boot is verified on documented hardware.

## 0.3.x · AVF adapter

- Integrate Android Virtualization Framework through the supported system service boundary.
- Verify Microdroid/guest image packaging, vsock communication, and device capability checks.
- Keep AVF support optional; the app must continue to explain unsupported devices accurately.

## Compatibility target

The control plane targets Android 8.0/API 26 and newer. Native backends will publish narrower API/device matrices based on evidence rather than the UI's minimum SDK.

