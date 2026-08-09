# Security policy

## Reporting

Please report suspected vulnerabilities privately to the project maintainers rather than opening a public issue with exploit details. Include the affected version, device/API level, backend, guest image format, reproduction steps, and impact. Do not attach credentials, signing keys, or private guest images.

## Design boundaries

- Guest images are user-selected local data and are not executed by the current control-plane milestone.
- A future runtime adapter must validate image descriptors, enforce CPU/memory/storage limits, clean up child processes, and fail closed when the backend cannot prove readiness.
- Release signing material stays in GitHub Actions encrypted secrets. The workflow must not echo it, upload it, or write it into the repository.
- Configuration exports contain profile metadata and content URIs, not guest image bytes or credentials.

