# Requirements: Android Share Capture Whitespace Normalization

item_id: a3b1ba34-7ecf-456b-9b4a-ad25eb2de5d5

## Problem

Capturing content via the Android share sheet (`CaptureActivity`) sometimes lands in the
journal block with extra spaces the user didn't intend.

## Root cause

`CaptureActivity.buildShareText()`
(`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`) only
resolves *which source* wins (clipData vs `EXTRA_TEXT` vs `EXTRA_SUBJECT`) and handles
blank-source fallback — it performs no whitespace normalization on the winning string.
`CaptureViewModel.save()` only calls `.trim()` on the whole string
(`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:54` — outer
whitespace only).

Confirmed by reading both files directly:
- `buildShareText()` (`CaptureActivity.kt:186-201`) concatenates `body`/`title` with a
  single `\n` join and returns the result verbatim — no `replace`/`collapse`/regex step
  anywhere in the function.
- `CaptureViewModel.save()` (`CaptureViewModel.kt:53-55`) does `_captureText.value.trim()`
  and nothing else before the text becomes block content.

Many share sources (browsers sharing a text selection, apps that share HTML-derived text
via `EXTRA_TEXT`) commonly include runs of internal whitespace, non-breaking spaces
(U+00A0), or repeated blank lines carried over from the source DOM/layout — none of which
are collapsed before the block is saved.

`androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt` covers
source-priority and blank-fallback behavior only (12 existing cases) — no case exercises
internal whitespace runs, non-breaking spaces, or excess blank lines, confirming this path
is untested/unhandled today.

## Why this matters

Silent formatting corruption on every capture from apps whose share payload isn't already
clean plain text — this is the highest-frequency, lowest-friction entry point into the
journal (companion issue: auto-link + tag-suggest on capture).

## Scope

In scope:
- Normalize whitespace on the winning share text before it becomes block content:
  - Collapse runs of spaces/tabs (2+) to a single space.
  - Normalize non-breaking spaces (U+00A0 and other Unicode space variants pulled from
    HTML-derived clipboard/share text) to regular spaces before collapsing.
  - Collapse 3+ consecutive newlines to at most 2 (i.e., cap blank-line runs at one blank
    line) while preserving intentional single line breaks.
  - Preserve the manual-typing path: text the user types directly into the capture field
    should not be mangled by the same normalization if applied on every keystroke — apply
    normalization once, at the share-intent ingestion point (`buildShareText()` /
    `parseShareIntent()`), not in `updateText()`/`save()`, so manual edits are untouched.
- Add test cases to `CaptureShareTextTest` covering: non-breaking spaces, repeated
  internal spaces/tabs, excessive blank lines (3+ newlines), and a combined
  realistic-browser-share-payload case.

Out of scope:
- Auto-link/tag-suggest on capture (tracked as a separate companion issue).
- Changes to `CaptureViewModel.save()`'s existing outer `.trim()` (still needed as a
  final guard; may become redundant with the new step but removing it is not required).
- Any change to image-share handling (`copyStreamToPrivateStorage`) or the
  `[image: ...]` prefix formatting.
- Server-side / desktop / iOS capture paths — this bug is specific to the Android share
  intent path.

## Acceptance Criteria

1. A share payload containing runs of 2+ regular spaces/tabs is collapsed to single
   spaces before being written to the journal block.
2. A share payload containing non-breaking spaces (U+00A0) has them normalized to regular
   spaces (and then collapsed per AC1 if repeated).
3. A share payload containing 3 or more consecutive newlines is collapsed to at most one
   blank line (2 consecutive newlines) between content.
4. A single intentional line break (one `\n`) between two lines of shared text is
   preserved unchanged.
5. Existing `CaptureShareTextTest` source-priority/fallback behavior (12 current cases)
   continues to pass unmodified.
6. Text typed manually into the capture field (not via share intent) is not subject to
   the new normalization pass — only text ingested through `parseShareIntent()` /
   `buildShareText()` is normalized.
7. New unit tests in `CaptureShareTextTest` cover: internal whitespace collapsing,
   non-breaking-space normalization, excess-blank-line collapsing, and one combined
   realistic browser-share payload.

## References

- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`
- `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`
