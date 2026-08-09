# Security policy

## Reporting

Please report suspected vulnerabilities privately to the project maintainers rather than opening a public issue with exploit details. Include the affected version, device/API level, backend, guest image format, reproduction steps, and impact. Do not attach credentials, signing keys, or private guest images.

## Design boundaries

- Guest images are user-selected local data and are not executed by the current control-plane milestone.
- A future runtime adapter must validate image descriptors, enforce CPU/memory/storage limits, clean up child processes, and fail closed when the backend cannot prove readiness.
- APK and AAB artifacts are intentionally unsigned. Do not submit keystores, certificates,
  private keys, passwords, or signing secrets in issues, pull requests, or workflow
  inputs.
- `scripts/verify_unsigned_android_artifacts.py` independently rejects JAR signature
  metadata, APK Signing Blocks in APK or AAB containers, `.idsig` files,
  signing-material filenames, and symbolic-link escapes. CI also validates ZIP
  integrity, Android package metadata, AAB semantics, and alignment before interpreting
  verification-tool rejection as the expected unsigned result. Failed or unchecked
  packages are never copied into the uploaded package staging directory.
- Configuration exports contain profile metadata and content URIs, not guest image bytes or credentials.

