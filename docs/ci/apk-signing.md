# APK signing

OpenVM intentionally does not generate, store, or use APK signing certificates. The
current build and release contract is documented in
[Android build and unsigned release artifacts](android-build-and-signing.md). This
compatibility page remains available for existing project documentation links and makes
the no-signing boundary explicit. Both Android build types clear `signingConfig`, and CI
independently rejects JAR signature metadata, APK/AAB Signing Blocks, `.idsig` files,
generated signing material, and symbolic-link escapes before requiring the expected
verification-tool rejection. The release lane also validates AAB semantics with a pinned,
checksum-verified bundletool and stages packages only after every check passes. Stock
Android therefore refuses direct installation; no installable signed APK or signing
identity is supplied by this project.
