# Native QEMU CI dependency inventory

The native workflow is experimental build and validation work. This inventory is
deliberately hand-written so a new job cannot quietly appear without a bootstrap path.

| Job | Runner | Dependencies bootstrapped by the job | Cache or external input | Safe evidence |
| --- | --- | --- | --- | --- |
| `build-runtime` | `ubuntu-24.04` | `bash`, `curl`, Git, Docker, `jq`, GNU `binutils/readelf`, `sha256sum`; immutable-digest Termux package-builder image; pinned QEMU archive; pinned Termux package tree | GitHub Actions runner tool cache plus Docker image cache; QEMU source URL, Termux Git revision, patch set, and builder image digest are pinned in `native/qemu/qemu-build.json` | Runtime files, runtime manifest, builder/recipe/patch provenance, Gradle-independent logs |
| `package-and-smoke-test` | `ubuntu-24.04` | Temurin JDK 17, Gradle wrapper, Android SDK platform 35, build-tools 35.0.0, platform-tools, API 35 default x86_64 emulator | Native runtime artifacts from the two successful `build-runtime` matrix jobs | APK, test XML/HTML, runtime manifests, emulator instrumentation result |

The workflow fails before the real build when a required command, source digest,
Termux revision, patch count, runtime binary, APK member, or emulator smoke signal is
missing. Artifact collection is `always()` and `continue-on-error` so diagnostic
evidence does not overwrite the original failure.

The builder image is the public image maintained by Termux and is consumed only through
the immutable digest in `qemu-build.json`; the build fails if Docker reports a different
digest. Each runtime's `build-provenance.json` records that same digest plus the recipe
and patch-manifest hashes.
