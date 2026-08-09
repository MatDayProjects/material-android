# CI documentation

This directory documents the Android build and release-signing boundary.

- [Android build and signing](android-build-and-signing.md)

The workflows are intentionally independent of the Android application source. The
Gradle project must provide the conventional `./gradlew` wrapper and Android release
tasks described in the detailed guide.
