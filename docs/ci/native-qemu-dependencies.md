# Native QEMU CI dependency inventory

The native workflow is experimental build and validation work. This inventory is
deliberately hand-written so a new job cannot quietly appear without a bootstrap path.

| Job | Runner | Dependencies bootstrapped by the job | Cache or external input | Safe evidence |
| --- | --- | --- | --- | --- |
| `build-runtime` | `ubuntu-24.04` | `bash`, `curl`, Git, Docker, `jq`, GNU `binutils/readelf`, `sha256sum`; immutable-digest Termux package-builder image; pinned QEMU archive; pinned Termux package tree | GitHub Actions runner tool cache plus Docker image cache; QEMU source URL, Termux Git revision, patch set, and builder image digest are pinned in `native/qemu/qemu-build.json` | Runtime files, runtime manifest, builder/recipe/patch provenance, Gradle-independent logs |
| `package-and-smoke-test` | `ubuntu-24.04` | Python 3, Temurin JDK 17, Gradle wrapper, Android SDK platform 35, and build-tools 35.0.0 with `aapt2`, `zipalign`, and `apksigner` explicitly added to `PATH` | Native runtime artifacts from the two successful `build-runtime` matrix jobs | Post-verification staged unsigned app/instrumentation APKs, CI context, versioned dependency assets, unit-test XML/HTML, runtime manifests, and `instrumentation-skip.txt` |

The workflow fails before the real build when a required command, source digest,
Termux revision, patch count, runtime binary, versioned library asset, APK member,
alignment check, or unsigned-APK check is missing. The app and instrumentation APKs are
built in disposable home directories and inspected by the committed signing-block
verifier. Rejected packages remain outside the upload staging directory, while every
artifact-producing job records run, commit, status, and runner context. Live instrumentation is not part of
the active workflow because Android refuses unsigned APK installation and the project
does not create or use a signing identity. Artifact collection is `always()` and
`continue-on-error` so diagnostic evidence does not overwrite the original failure.

The builder image is the public image maintained by Termux and is consumed only through
the immutable digest in `qemu-build.json`; the build fails if Docker reports a different
digest. Each runtime's `build-provenance.json` records that same digest plus the recipe
and patch-manifest hashes.
