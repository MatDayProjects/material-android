# CI documentation

This directory documents the Android build and unsigned release boundary.

- [Android build and unsigned release artifacts](android-build-and-signing.md)
- [Native QEMU dependency inventory](native-qemu-dependencies.md)

The workflows are intentionally independent of the Android application source. The
Gradle project must provide the conventional `./gradlew` wrapper and Android release
tasks described in the detailed guide.
