# Contributing to OpenVM

OpenVM is built around a strict honesty rule: the UI must never imply that a guest is running when no verified native runtime owns the process.

## Development flow

1. Read `README.md`, `AGENTS.md`, and the feature article you will change.
2. Make a focused change and add or update a unit/instrumentation test where practical.
3. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.
4. Update the feature documentation and handoff notes with the actual verification state.

## Runtime adapters

Native runtime work must be isolated behind `RuntimeBackendRegistry`. A new adapter must document its upstream license, device/API requirements, image format, resource limits, lifecycle ownership, and failure behavior before the UI exposes a “Start” action.

## Pull requests

Use a precise title, describe user-visible behavior, include verification output, and do not attach signing material or guest images. New public records use ordinary technical language; private conversation terminology must not leak into project files.

