# VM profiles

## Behavior

OpenVM stores profile metadata locally in a versioned JSON document inside app-private preferences. A profile describes a name, guest Android version, architecture, memory, storage, vCPU count, selected backend, and an optional Storage Access Framework content URI.

Creating, editing, deleting, importing, and exporting profiles creates a local history entry. Export contains metadata and URI references only; it never copies guest image bytes.

## Validation

The current bounds are:

| Field | Rule |
| --- | --- |
| Name | 1–64 characters |
| Memory | 256–65536 MB |
| Storage | 1–4096 GB |
| vCPUs | 1–32 |
| Architecture | `arm64-v8a` or `x86_64` |

## Runtime failure mode

The Start action does not mutate a profile to `RUNNING`. It reports the exact missing prerequisite when there is no image or when the selected native adapter is not configured. This is intentional: a platform API level or a saved URI is not evidence that a guest boot succeeded.

## Security considerations

Guest images are potentially executable code. The control-plane milestone stores a persisted URI permission but does not execute the image. A future backend must validate image metadata, bound resources, supervise child processes, and document its upstream license.

## Verification

- `VmProfileTest` covers shipped defaults, resource bounds, and JSON round trips.
- `MainActivitySmokeTest` checks the truthful blank state on a fresh profile store.

## Suggested articles

- [Search and regex builder](search-and-regex.md)
- [APK signing](../ci/apk-signing.md)

