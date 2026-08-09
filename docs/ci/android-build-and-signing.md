# Android build and release signing

This project separates ordinary Android validation from release signing. Pull requests
and normal pushes build a debug APK without reading signing secrets. Release signing is
available only in GitHub Actions, using an `android-release` environment and secrets
provided to one signing step.

## Workflow coverage

| Workflow | Trigger | Build and verification | Secret access | Uploaded evidence |
| --- | --- | --- | --- | --- |
| `.github/workflows/android-ci.yml` | Pull request, every push, or manual dispatch | `testDebugUnitTest`, `assembleDebug`, and `apksigner verify` for every debug APK | None | Debug APKs and safe test/report files for 14 days |
| `.github/workflows/android-release.yml` | `v*` tag push, or manual dispatch with `release=true` | Pinned native QEMU runtime build first, debug APK validation with all runtime members, then Gradle signing from the protected CI environment, fallback `apksigner` signing only when an APK is not already signed by the configured certificate, and certificate verification for every APK/AAB | Only the `android-release` environment's four signing secrets | Signed APKs/AABs, runtime/provenance, verification results, and SHA-256 checksums for 30 days |
| `.github/workflows/android-native-qemu.yml` | Every push or manual dispatch | Pinned QEMU/Termux source verification, Android host-ABI runtime build, generated APK member and 16 KiB alignment checks, and API 35 emulator `--version`/`-machine help` plus production-controller smoke tests | None | Runtime, provenance, APK, and test evidence for 14 days |

Android builds use `ubuntu-24.04`; the Windows-only product scope does not apply to
this Android lane. The validation workflow cancels obsolete push runs. The release
workflow does not cancel an in-progress release, because cancellation could leave a
tag without its build evidence.

The manual routes use GitHub Actions `workflow_dispatch`; the release workflow exposes
a required `release` boolean so an accidental manual run does not consume signing
secrets.

## Gradle contract

The Android project should expose a conventional Gradle wrapper at the repository root
and provide these tasks:

```text
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease bundleRelease
```

The release variant is unsigned during ordinary local development. In the protected
release job, the workflow exposes the decoded keystore through four short-lived
environment variables and Gradle attaches the `ciRelease` signing config to both the
APK and AAB release outputs. The build script rejects a partial CI signing environment,
and no secret or keystore path is committed. The workflow still verifies every output
and can sign an APK with `apksigner` if a future Android Gradle Plugin emits an unsigned
or differently signed APK.

The signing step reads the following values:

| Value | Purpose | Supplied by the release workflow |
| --- | --- | --- |
| Temporary keystore path | Path to the decoded keystore | Created under the runner temp directory |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password | Yes; GitHub Actions secret |
| `ANDROID_KEY_ALIAS` | Signing key alias | Yes; GitHub Actions secret |
| `ANDROID_KEY_PASSWORD` | Signing key password | Yes; GitHub Actions secret |

The workflow maps those secrets only inside the signing job:

| Gradle environment variable | Source |
| --- | --- |
| `OPENVM_RELEASE_KEYSTORE` | Temporary decoded keystore path |
| `OPENVM_RELEASE_STORE_PASSWORD` | `ANDROID_KEYSTORE_PASSWORD` |
| `OPENVM_RELEASE_KEY_ALIAS` | `ANDROID_KEY_ALIAS` |
| `OPENVM_RELEASE_KEY_PASSWORD` | `ANDROID_KEY_PASSWORD` |

The workflow passes passwords to `apksigner` and `jarsigner` through environment-based
password arguments, never as literal command-line values.

## Configure the signing environment

Create a GitHub Actions environment named `android-release`. Add these secrets to that
environment, not to source control and not to a plaintext configuration file:

| Secret | Contents |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the release JKS or PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Alias of the release key |
| `ANDROID_KEY_PASSWORD` | Password for the release key |

Create the signing identity once on a trusted development machine. `keytool` prompts for
the passwords, so they do not need to appear in shell history or chat:

```powershell
keytool -genkeypair -v `
  -keystore openvm-release.keystore `
  -alias openvm-release `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Keep `openvm-release.keystore` outside the repository. Encode it locally and send the
result directly to GitHub's encrypted secret store; do not print or commit the encoded
value. The password prompts for the three remaining secrets should also be completed
through `gh secret set` without putting their values in a file:

```powershell
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes('.\openvm-release.keystore'))
$encoded | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-release
gh secret set ANDROID_KEY_ALIAS --env android-release
gh secret set ANDROID_KEY_PASSWORD --env android-release
```

The certificate is intentionally stable across releases. Generating a new key on every
workflow run would prevent Android update continuity and is not a safe release design.

Use environment protection rules, such as required reviewers, when the repository's
release policy requires approval. Keep the original keystore outside the repository.
The encoded value is still sensitive: do not commit it, paste it into an issue, or
print it in an Actions log.

The workflow performs the following checks before and after the Gradle build:

1. It checks that all four secret values are present without printing their values.
2. It decodes the keystore into a temporary file with restrictive permissions.
3. It validates the keystore password and alias with `keytool` without emitting the
   command output.
4. It passes the temporary keystore and protected values to Gradle so the release APK
   and AAB are signed by the configured certificate.
5. It signs an APK with `apksigner` only when its source digest is not already the
   configured certificate, then verifies the result.
6. It requires both a release APK and a release AAB before publishing evidence.
7. It verifies every signed release APK with `apksigner` and compares its certificate digest
   with the configured keystore alias.
8. It verifies every signed release AAB with `jarsigner -verify` and compares its
   certificate digest with the configured keystore alias.
9. It copies only signed, verified APKs/AABs, a verification summary, and a checksum file to
   the upload directory. The temporary keystore is deleted when the signing step ends.

No private key, keystore, certificate file, password, or encoded keystore belongs in
the repository. The debug workflow does not reference `secrets` at all, so pull
requests from untrusted forks cannot use release credentials.

## Local developer path

Local development uses the debug variant and does not require a release keystore:

```bash
chmod +x ./gradlew
./gradlew testDebugUnitTest assembleDebug
```

On Windows, use the checked-in wrapper equivalent:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Install the JDK and Android SDK versions required by the Android project's Gradle
configuration, then let the wrapper resolve the declared Gradle version. The debug APK
will be under a module's `build/outputs/apk/debug/` directory.

Release signing is intentionally not part of the local developer path. Do not create a
local release keystore merely to make a local build pass, and do not add local signing
properties to the repository. To exercise the release lane, push a version tag such as
`v1.0.0` or manually dispatch `Android Release` with `release=true` after the protected
environment secrets are configured.

## Failure modes and recovery

| Failure | Meaning | Safe recovery |
| --- | --- | --- |
| Missing secret names are reported | The protected environment is incomplete | Add the named secret to `android-release`; do not paste its value into a log or issue |
| Base64 decode or empty-keystore failure | The encoded keystore value is malformed or empty | Re-encode the original keystore outside the repository and replace only the GitHub secret |
| Keystore/alias validation failure | A password or alias does not match the keystore | Correct the protected secret values; no artifact is uploaded as a release |
| No release APK/AAB | Gradle did not produce the expected release outputs | Fix the Android release build tasks; the signing step cannot sign a missing artifact |
| `apksigner` rejects an APK | The APK is malformed or could not be signed | Fix the release artifact or signing inputs; do not substitute a debug APK |
| Certificate digest mismatch | The artifact was signed by a different key than the configured alias | Inspect the selected keystore, alias, and generated artifact; do not bypass the comparison |
| Manual dispatch reports no release | `release` was left false | Dispatch again with the boolean input set to `true` |

The upload steps use `if: always()`, bounded retention, and `if-no-files-found: warn`
so safe build reports remain available after a failure without hiding the original
failure. They never upload the repository, Gradle caches, signing directory, or secret
material.
