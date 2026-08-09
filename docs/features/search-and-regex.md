# Search and regex builder

## Behavior

Profile and history lists use plain-text, case-insensitive search by default. Each search bar has its own adjacent Regex builder. Enabling a pattern is an explicit action; the pattern and flags remain local to that search surface.

The builder provides insertion helpers for anchors, character classes, groups, alternation, and quantifiers, plus raw pattern and sample text fields. Supported flags are `i` (ignore case), `m` (multiline), and `s` (dot matches all). The engine is Kotlin/JVM `Regex`.

## Failure modes and bounds

- Empty patterns are rejected by the builder.
- Invalid syntax is shown inline and cannot be applied.
- Patterns are bounded to 256 characters to reduce accidental denial-of-service risk.
- No pattern or sample text is sent to a network service or written to an export.

## Verification

The builder is exercised by the real UI path; profile model tests cover the persistence boundary. Future UI tests should add Unicode, multiline, zero-width, capture-group, adversarial, and no-match cases.

## Suggested articles

- [VM profiles](vm-profiles.md)
- [Unsigned Android artifacts](../ci/android-build-and-signing.md)

