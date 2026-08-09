# Android build and unsigned release artifacts

OpenVM's Android build is fully open source and intentionally does not create or use a
code-signing certificate. Release APK and AAB artifacts are built unsigned so anyone can
reproduce them from the repository. Android may show an unknown-publisher warning when
an unsigned APK is installed; this is expected and is not an authenticity claim.

## Workflow coverage

| Workflow | Trigger | Build and verification | Secret access | Evidence |
| --- | --- | --- | --- | --- |
| `.github/workflows/android-ci.yml` | Pull request, every push, or manual dispatch | Unit tests, debug APK build, alignment, and safe artifact collection | None | Debug APKs and reports |
| `.github/workflows/android-release.yml` | `v*` tag push, or manual dispatch with `release=true` | Pinned native QEMU runtime build, debug validation, unsigned release APK/AAB build, runtime member checks, 16 KiB alignment, and absence of signing metadata | None | Unsigned APK/AAB, runtime, provenance, checksums, and reports |
| `.github/workflows/android-native-qemu.yml` | Every push or manual dispatch | Pinned QEMU/Termux source verification, runtime collection, APK member checks, unit tests, and API 35 QEMU process smoke tests when the runner exposes usable KVM | None | Runtime, provenance, APK, test evidence, or an explicit no-KVM record |

The release workflow never reads keystores, passwords, signing aliases, or certificate
secrets. It also does not invoke `apksigner`, `jarsigner`, `keytool`, or another signer.
The release step checks the APK/AAB archive for signing metadata and fails if it finds
certificate-bearing entries.

## Reproducible local build

```text
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease bundleRelease
```

The release variant has no CI signing configuration. The generated release APK and AAB
are therefore unsigned and can be inspected, hashed, and reproduced without private
inputs. The native runtime remains an explicit `OPENVM_QEMU_RUNTIME_DIR` input; ordinary
source-only builds do not download QEMU bytes.

## GitHub Actions release path

1. The two native-runtime matrix jobs verify the immutable QEMU/Termux manifest and
   collect both Android host ABI outputs.
2. The debug job combines those outputs, runs unit tests, and checks the APK's QEMU
   executable, versioned dependency, firmware-data, and 16 KiB alignment entries.
3. The unsigned-release job repeats the runtime input checks, builds `assembleRelease`
   and `bundleRelease`, verifies the same APK/AAB members, rejects signing metadata, and
   writes SHA-256 checksums beside the artifacts.
4. The API 35 live instrumentation step runs only when `/dev/kvm` is readable by the
   runner. GitHub-hosted Linux runners without usable KVM still verify the APK, unit
   tests, runtime layout, dependency members, and alignment, then upload an explicit
   no-KVM evidence record instead of hanging on an unreliable software emulator.
5. Safe evidence is uploaded even when an earlier step fails, without uploading source,
   dependency directories, or credentials.

The manual dispatch boolean is named `release` only to make publication intentional; it
does not enable signing. Tag pushes run the same unsigned path.

## Security boundary

Unsigned artifacts do not provide publisher authentication. Users should obtain builds
from a trusted source, compare the published commit and checksums, and understand that
Android may warn before installation. QEMU and guest images remain executable inputs;
the native runtime's source, patch, ELF, dependency, and APK checks are integrity and
reproducibility controls, not a guest sandbox.

## Verification

The native runtime's process-level checks prove QEMU `--version`, machine discovery, and
the production controller start/stop path on Android. They do not prove that a guest
Android image boots. The guest boot boundary remains documented separately in
[QEMU runtime adapter](../features/qemu-runtime.md).

## Suggested articles

- [Native QEMU build lane](../features/native-qemu-build.md)
- [QEMU runtime adapter](../features/qemu-runtime.md)
- [Guest-image manifest](../features/guest-image-manifest.md)
