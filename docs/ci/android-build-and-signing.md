# Android build and unsigned release artifacts

OpenVM's Android build is fully open source and intentionally does not create or use a
code-signing certificate. Release APK and AAB artifacts are built unsigned so anyone can
reproduce them from the repository. The debug and release build types both set
`signingConfig = null`; stock Android refuses to install these unsigned APKs. The project
does not provide a signing identity or claim publisher authenticity.

## Workflow coverage

| Workflow | Trigger | Build and verification | Secret access | Evidence |
| --- | --- | --- | --- | --- |
| `.github/workflows/android-ci.yml` | Pull request, every push, or manual dispatch | Unit tests; unsigned debug app and instrumentation APK builds; archive, Android metadata, alignment, signing-block, and `apksigner` checks | None | Unsigned APKs and reports |
| `.github/workflows/android-release.yml` | `v*` tag push, or manual dispatch with `release=true` | Pinned native QEMU runtime build; unsigned debug/instrumentation/release APK and AAB validation; runtime member checks; 16 KiB alignment; independent signature checks | None | Unsigned APK/AAB, runtime, provenance, checksums, and reports |
| `.github/workflows/android-native-qemu.yml` | Every push or manual dispatch | Pinned QEMU/Termux source verification, runtime collection, unsigned app/instrumentation package checks, and unit tests | None | Runtime, provenance, unsigned APKs, test evidence, and the explicit instrumentation boundary |

The workflows never read keystores, passwords, signing aliases, or certificate secrets.
They invoke `apksigner` and `jarsigner` only in verification mode and never invoke a
signing operation or `keytool`. A generic verifier failure is not accepted as proof:
`scripts/verify_unsigned_android_artifacts.py` first validates ZIP structure and CRCs,
required Android entries, path safety, v1/JAR metadata, APK v2/v3 Signing Blocks, v4
`.idsig` companions, signing-material filenames, and symbolic-link escapes. It rejects
APK Signing Blocks in both APK and AAB containers. CI then validates Android package
metadata with `aapt2`, alignment with `zipalign`, the explicit `DOES NOT VERIFY` result
from `apksigner`, the explicit `jar is unsigned` result from `jarsigner`, and AAB
semantics with Google's bundletool 1.18.3 pinned to SHA-256
`a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`.

## Reproducible local build

```text
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
./gradlew assembleRelease bundleRelease
python3 scripts/verify_unsigned_android_artifacts.py \
  app/build/outputs/apk app/build/outputs/bundle
```

Both build types explicitly clear their signing configuration. The generated debug and
release APKs, instrumentation APK, and release AAB are therefore unsigned and can be
inspected, hashed, and reproduced without private inputs. The native runtime remains an explicit
`OPENVM_QEMU_RUNTIME_DIR` input; ordinary source-only builds do not download QEMU bytes.

## GitHub Actions release path

1. The two native-runtime matrix jobs verify the immutable QEMU/Termux manifest and
   collect both Android host ABI outputs.
2. The debug job combines those outputs, runs unit tests, builds both debug APKs, checks
   the app APK's QEMU executable, versioned dependency, firmware-data, and 16 KiB
   alignment entries, and verifies that neither package carries any signature form.
3. The unsigned-release job repeats the runtime input checks, builds `assembleRelease`
   and `bundleRelease`, verifies the same APK/AAB members, rejects every supported
   signature form, generated signing-material filename, and symlink escape; validates
   the AAB with pinned bundletool; and writes SHA-256 checksums beside the artifacts.
4. Live APK instrumentation is not run: Android refuses to install an unsigned APK, and
   the permanent no-signing policy forbids creating or using the identity installation
   would require. The workflow uploads `instrumentation-skip.txt` beside the static and
   unit-test evidence instead of implying that an installation occurred.
5. Every Gradle build uses disposable `HOME`, `ANDROID_USER_HOME`, and
   `GRADLE_USER_HOME` directories; each is scanned for generated signing material.
6. APK/AAB files are copied into a dedicated staging directory only after every check
   succeeds. `always()` evidence collection can still upload test reports, failure logs,
   and a context manifest without publishing a rejected package. Each context manifest
   records the workflow/run/attempt, commit, job step outcome, operating system,
   architecture, and runner name.
7. Safe evidence is uploaded even when an earlier step fails, without uploading source,
   dependency directories, caches, credentials, or rejected packages.

The manual dispatch boolean is named `release` only to make publication intentional; it
does not enable signing. Tag pushes run the same unsigned path.

## Security boundary

Unsigned artifacts do not provide publisher authentication and cannot be installed on
stock Android without an independently supplied signature. This project does not supply
one. Users should compare the published commit and checksums. QEMU and guest images remain executable inputs;
the native runtime's source, patch, ELF, dependency, and APK checks are integrity and
reproducibility controls, not a guest sandbox.

## Verification

Unit tests verify runtime discovery, command construction, asset integrity, and lifecycle
logic. The unsigned-artifact suite has 17 regression tests: 10 cover structurally valid
APK/AAB containers, v1 metadata, APK/AAB Signing Blocks, detached signatures, invalid
ZIPs, generated signing material, and file/directory symlink escapes; 7 guard every
workflow's verifier invocation, exact app/instrumentation discovery, safe staging,
bundletool pin, and absence of signing commands. Pinned
bundletool validates the semantics of the real generated AAB; the synthetic unit fixture
only tests the container boundary. Earlier debug-signed instrumentation results are historical diagnostics, not
evidence for the current unsigned build. No current workflow proves APK installation or
Android guest boot. The guest boot boundary remains documented separately in [QEMU
runtime adapter](../features/qemu-runtime.md).

### Hosted correction evidence

The unsigned correction is hosted-verified at exact commit
[`0153e1c`](https://github.com/MatDayProjects/material-android/commit/0153e1c5a00e63efe1a8679926daa8ace5872a6f):

| Workflow | Run | Downloaded evidence |
| --- | --- | --- |
| Android CI | [`31327047886`](https://github.com/MatDayProjects/material-android/actions/runs/31327047886) | Artifact `9041854414`; 43/43 tests; unsigned debug and instrumentation APKs |
| Android Native QEMU | [`31327047882`](https://github.com/MatDayProjects/material-android/actions/runs/31327047882) | Runtime artifacts `9041954338` and `9041970503`; package artifact `9042005849`; unsigned APKs; 92 byte-matching runtime entries per ABI |
| Android Release | [`31327061429`](https://github.com/MatDayProjects/material-android/actions/runs/31327061429) | Runtime artifacts `9041976158` and `9041932186`; debug artifact `9042007420`; release artifact `9042054147`; checksums and bundletool validation |

The downloaded release APK SHA-256 is
`b3280bba178f737a3bb4b1a18873ce8fb16351a642b4983c50c349f84aeb24ce`; the AAB
SHA-256 is `c4cc46568ae8764a35ee14e4fe180dcd46499371a47e51d109726245f3b5e16c`.
Both are structurally unsigned, the APK is aligned and has valid Android metadata,
`jarsigner` explicitly reports the AAB as unsigned, pinned bundletool 1.18.3 validates
the AAB, and the downloaded checksums and job-context records match. These workflows do
not prove installation or guest boot because stock Android refuses unsigned APKs.

## Suggested articles

- [Native QEMU build lane](../features/native-qemu-build.md)
- [QEMU runtime adapter](../features/qemu-runtime.md)
- [Guest-image manifest](../features/guest-image-manifest.md)
