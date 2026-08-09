# OpenVM

OpenVM is a local-first, open-source Android VM control plane inspired by the isolation and multi-instance workflow of VMOS. It is written from scratch and does not copy VMOS code, assets, branding, or private services.

> **Status:** the repository is a working first milestone. It creates and stores VM profiles, imports guest image references through Android's Storage Access Framework, exports configuration, records local history, exposes an honest backend-readiness panel, and ships a reproducible GitHub Actions signing path. The native guest runtime adapters are intentionally separate modules; this build does not pretend that an Android API level alone can boot an arbitrary guest.

## Contents

- [What is implemented](#what-is-implemented)
- [Architecture](#architecture)
- [Build locally](#build-locally)
- [Release signing](#release-signing)
- [Security boundary](#security-boundary)
- [Project documentation](#project-documentation)
- [License](#license)

## What is implemented

- VM profile creation and editing with validation for memory, storage, vCPU count, architecture, and guest Android version.
- Android Storage Access Framework import for a user-selected guest image; OpenVM stores the persisted content URI and never copies or uploads the image automatically.
- Local JSON configuration import/export.
- Local append-only history for profile creation, editing, deletion, import, and export.
- Multiple profile cards with truthful lifecycle states. Starting is blocked with an explanation until a native backend adapter and a guest image are present; there is no fake “running” state.
- Backend readiness reporting for Android Virtualization Framework and the planned QEMU adapter.
- Profile/history search with an adjacent regex builder, bounded patterns, supported flags, live validation, and sample matching.
- Settings for language mode (English, playful Hong Kong-style Cantonese, or bilingual), independent funny-level sliders, emoji decoration, display name, and dark theme.
- `Ctrl+Shift+F` command palette for keyboard users.

The Google Play listing describes VMOS as supporting isolated Android systems, multiple virtual machines, background operation, configuration changes, and host↔guest file transfer. OpenVM's architecture keeps those goals separate: the Android UI/control plane is this project, while a native QEMU or AVF guest engine must be audited and built as its own open-source component.

## Architecture

```text
Android UI
  ├── VM profiles + local JSON store
  ├── Storage Access Framework image references
  ├── local history and export
  └── RuntimeBackendRegistry
        ├── AVF adapter boundary (Android 13+ capability check)
        └── QEMU adapter boundary (planned portable native module)
```

The boundary is deliberate. Android's VirtualizationService manages crosvm guests, but access depends on device and platform capabilities; a normal app must not claim full guest execution without a compatible native adapter, image, permissions, and runtime verification.

## Build locally

Requirements:

- JDK 17 or newer supported by the Android Gradle Plugin.
- Android SDK 35 and build-tools installed.
- Network access for the first Gradle dependency resolution.

```powershell
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Release signing is performed only in GitHub Actions from encrypted repository secrets. No keystore, certificate, or private key belongs in this repository. See [docs/ci/apk-signing.md](docs/ci/apk-signing.md) for the exact one-time secret setup and the workflow's verification steps.

The signing workflow builds the release APK/AAB outputs, signs them only inside the protected GitHub Actions environment, validates the APK with the Android SDK's `apksigner`, validates the AAB with `jarsigner`, and uploads safe workflow artifacts. It does not print secret values. A certificate is not “generated” by CI on every run: the signing identity is created once by the project owner, stored in the GitHub Actions secret store, and used reproducibly.

## Security boundary

OpenVM is local-first and has no account or telemetry service. Guest images can execute code, so importing an image is an explicit user action and a future runtime adapter must enforce resource limits, lifecycle cleanup, and a clear permission model. The current app never executes a selected image.

Do not open issues or pull requests containing private keystores, passwords, access tokens, or guest images. Report security issues through the process in [SECURITY.md](SECURITY.md).

## Project documentation

- [VM profiles](docs/features/vm-profiles.md)
- [Search and regex builder](docs/features/search-and-regex.md)
- [APK signing](docs/ci/apk-signing.md)
- [Roadmap](ROADMAP.md)
- [Handoff](HANDOFF.md)

## Shared instructions mirror

This repository carries a sanitized project-local mirror in [AGENTS.md](AGENTS.md). It is a mirror for contributors, not the canonical instruction source.

## License

OpenVM's original code is licensed under the Apache License 2.0. Native runtime components added later must retain their own upstream licenses and notices; a GPL-licensed QEMU component cannot be relabeled as Apache-2.0.
