# Requirements: Android Share Capture Whitespace Normalization

Backlog item: `a3b1ba34-7ecf-456b-9b4a-ad25eb2de5d5`
Source: bug report, no interactive ideation (derived directly from item description + acceptance criteria).

## Problem

`CaptureActivity.buildShareText()` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`)
resolves which share-intent source wins (clipData / EXTRA_TEXT / EXTRA_SUBJECT) but does no
whitespace normalization on the winning string. `CaptureViewModel.save()`
(`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:54`) only calls `.trim()`,
which strips leading/trailing whitespace only. Share payloads from browsers and HTML-aware apps
commonly carry internal whitespace runs, non-breaking spaces (U+00A0), and repeated/whitespace-only
blank lines from the source DOM — all of which land verbatim in the journal block today.

Confirmed independently: CI's `android` job (`.github/workflows/ci.yml:88-89`) runs
`:kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug` — it never invokes
`:androidApp:testDebugUnitTest`, so `androidApp/src/test/kotlin/...` (including
`CaptureShareTextTest`) does not run in CI at all today. AC8 is real, not speculative.

## Functional requirements

Derived 1:1 from the backlog item's acceptance criteria:

1. **Space/tab collapsing** — 2+ consecutive regular spaces or tabs collapse to a single space.
2. **NBSP normalization** — U+00A0 (non-breaking space) normalizes to a regular space U+0020,
   then is subject to the same collapsing as AC1 if repeated.
3. **Blank-line collapsing** — 3+ consecutive newlines, OR newlines separated only by
   whitespace-only lines (e.g. a line containing a single space — the browser-DOM artifact that
   motivated this fix), collapse to at most one blank line (i.e. at most `\n\n` between content
   blocks).
4. **Single line breaks preserved** — one `\n` between two lines of shared text is left unchanged
   (not collapsed, not expanded).
5. **No regression** — all 12 existing `CaptureShareTextTest` cases (source priority / blank
   fallback / dedup) continue to pass unmodified.
6. **Scope boundary: share path only** — normalization applies only to text ingested through
   `parseShareIntent()` → `buildShareText()`. Text the user types manually into the capture
   `OutlinedTextField` (`updateText()`) must NOT be run through the new normalization pass. This
   must be proven by an executable `CaptureViewModelTest`, not just by code inspection.
7. **New test coverage** — `CaptureShareTextTest` gains cases for: internal whitespace collapsing,
   NBSP normalization, excess-blank-line collapsing (including whitespace-only lines and CRLF line
   endings), and one combined realistic browser-share payload exercising several of the above at
   once.
8. **CI enforcement** — the `android` CI job must compile and run `androidApp/src/test`
   (`CaptureShareTextTest`, `CaptureViewModelTest`) on every PR, so a future change that breaks or
   deletes this coverage fails CI instead of merging silently.

## Non-functional / constraints

- No new dependencies — normalization is pure `String`/`Regex` stdlib work (ponytail ladder rung 2).
- Preserve existing `buildShareText()` behavior (source priority, blank-source fallback,
  subject/body dedup) — normalization is an additional, separable transform, not a rewrite of that
  logic.
- Normalization must be applied to the *result* of `buildShareText()` (or equivalently inside it,
  documented in Research/Plan) — not scattered across call sites. `CaptureActivity.onCreate` and
  `onNewIntent` both call `parseShareIntent()` and must not need duplicated normalization logic.
- `CaptureViewModel.save()`'s existing outer `.trim()` stays — it still matters for the final
  save-time cleanup regardless of source.
- CRLF (`\r\n`) line endings must be handled — treated equivalently to `\n` for blank-line
  collapsing (AC7 explicitly calls out CRLF as a required test case).

## Out of scope

- Auto-link / tag-suggest on capture (explicitly called out as a companion/separate issue).
- Any change to `CaptureActivity`'s image-handling, tile-prompt, or UI code.
- Any whitespace normalization for manually-typed capture text (explicitly excluded by AC6).

## Acceptance criteria (verbatim from backlog item, numbered for traceability)

1. A share payload containing runs of 2+ regular spaces/tabs is collapsed to single spaces before
   being written to the journal block.
2. A share payload containing non-breaking spaces (U+00A0) has them normalized to regular spaces,
   then collapsed per AC1 if repeated.
3. A share payload containing 3+ consecutive newlines, OR newlines separated only by
   whitespace-only "blank" lines, is collapsed to at most one blank line (2 consecutive newlines)
   between content.
4. A single intentional line break (one `\n`) between two lines of shared text is preserved
   unchanged.
5. Existing `CaptureShareTextTest` source-priority/fallback behavior (12 current cases) continues
   to pass unmodified.
6. Text typed manually into the capture field is not subject to the new normalization pass — only
   text ingested through `parseShareIntent()`/`buildShareText()` is normalized, backed by an
   executable `CaptureViewModelTest`.
7. New unit tests in `CaptureShareTextTest` cover: internal whitespace collapsing,
   non-breaking-space normalization, excess-blank-line collapsing (including whitespace-only lines
   and CRLF line endings), and one combined realistic browser-share payload.
8. CI's `android` job compiles and runs `androidApp/src/test` (`CaptureShareTextTest`,
   `CaptureViewModelTest`) on every PR.
