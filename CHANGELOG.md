# Changelog

## Unreleased · 2026-08-09

- [`08eeecc`](https://github.com/MatDayProjects/material-android/commit/08eeecc0e8162e399fa28df8ab806d2830496e85) adds the process-backed QEMU runtime boundary, private bounded asset materialization, lifecycle/output tracking, and API 37 emulator coverage; native guest boot and display transport remain explicitly unimplemented.
- [`1c2a439`](https://github.com/MatDayProjects/material-android/commit/1c2a43979bc8324529e71d28ec8ec44d3f85f6cb) wires the protected GitHub Actions keystore into the Gradle release APK/AAB variants and verifies the uploaded certificate and checksums.

## 0.1.0 · 2026-08-08

- Created the OpenVM Android project from an empty repository.
- Added local VM profile management, image URI import, JSON import/export, and local history.
- Added backend readiness reporting for AVF and QEMU boundaries.
- Added settings for language mode, funny levels, emoji decoration, display name, and theme.
- Added a bounded regex builder and keyboard command palette.
- Added unit/instrumentation test scaffolding and a GitHub Actions signing workflow.
