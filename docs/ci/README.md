# CI documentation

This directory documents the Android build and unsigned release boundary.

- [Android build and unsigned release artifacts](android-build-and-signing.md)
- [APK signing compatibility and no-signing boundary](apk-signing.md)
- [Native QEMU dependency inventory](native-qemu-dependencies.md)

The workflows share the standard-library verifier in
`scripts/verify_unsigned_android_artifacts.py`. The Gradle project must provide the
conventional `./gradlew` wrapper and Android tasks described in the detailed guide.
