# Changelog

## Unreleased · 2026-08-09

- [`af43751`](https://github.com/MatDayProjects/material-android/commit/af43751fb7b1ed162242e739a131c36f6b4706d9) adds the version-1 guest-image manifest and boot contract, strict UTF-8/depth limits, image/kernel/initrd size and SHA-256 binding, Activity-safe QEMU ownership, bounded VNC touch/key input, SAF display names, backup exclusions, and API 37 editor evidence. Native QEMU packaging and verified Android guest boot remain unverified.
- [`8a7a1ba`](https://github.com/MatDayProjects/material-android/commit/8a7a1ba9172b8427c4ee40d3e36874f568729c6e) adds the private UNIX-socket VNC/RFB framebuffer boundary, a real running-profile display surface, collision-resistant asset paths, atomic replacement, bounded output, forced-stop verification, and cancellation before QEMU launch; guest boot and input remain unverified.
- [`08eeecc`](https://github.com/MatDayProjects/material-android/commit/08eeecc0e8162e399fa28df8ab806d2830496e85) adds the process-backed QEMU runtime boundary, private bounded asset materialization, lifecycle/output tracking, and API 37 emulator coverage; native guest boot and display transport remain explicitly unimplemented.
- [`1c2a439`](https://github.com/MatDayProjects/material-android/commit/1c2a43979bc8324529e71d28ec8ec44d3f85f6cb) wires the protected GitHub Actions keystore into the Gradle release APK/AAB variants and verifies the uploaded certificate and checksums.

## 0.1.0 · 2026-08-08

- Created the OpenVM Android project from an empty repository.
- Added local VM profile management, image URI import, JSON import/export, and local history.
- Added backend readiness reporting for AVF and QEMU boundaries.
- Added settings for language mode, funny levels, emoji decoration, display name, and theme.
- Added a bounded regex builder and keyboard command palette.
- Added unit/instrumentation test scaffolding and a GitHub Actions signing workflow.
