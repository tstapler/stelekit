# Research: Integration Point for Share-Text Whitespace Normalization

item_id: a3b1ba34-7ecf-456b-9b4a-ad25eb2de5d5

## Prior-research check

`project_plans/android-features-integration/research/synthesis.md` covers the
widget/tile/share-target *architecture* decision (Application singleton +
shared `CaptureActivity`, Options A/B/C). It does not address text
normalization at all — irrelevant to this question except as background
confirming the current `CaptureActivity` shape (share intent parsed
synchronously in `onCreate`/`onNewIntent`, image bytes copied to private
storage before any coroutine hand-off). Proceeding with fresh research below,
grounded directly in current source.

## Current code (as read)

- `CaptureActivity.buildShareText(clipText, extraText, subject)` —
  `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`.
  `internal` function on the companion object. Pure string function: resolves
  clipData → EXTRA_TEXT → EXTRA_SUBJECT priority and dedupes subject/body. No
  whitespace handling beyond the fallback logic itself.
- `CaptureActivity.parseShareIntent(intent)` — `CaptureActivity.kt:127-144`.
  Instance method (needs `Context`/`Intent`), calls `buildShareText()` at
  line 134, then separately resolves an image URI via
  `copyStreamToPrivateStorage()` (line 140).
- `onCreate` — `CaptureActivity.kt:72-112`. When `savedInstanceState == null`,
  calls `parseShareIntent(intent)` (line 80), then at lines 81-85 branches: if
  `imageLocalPath != null`, calls
  `viewModel.initializeText("[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim())`;
  else `viewModel.initializeText(shareContent.text)`.
- `onNewIntent` — `CaptureActivity.kt:115-124`. Identical
  parse-and-branch logic duplicated (singleTop re-launch path).
- `CaptureViewModel.initializeText(text)` — `CaptureViewModel.kt:47-51`. Sets
  `_captureText.value = text` only if the current value is empty and the
  incoming text is non-empty — this is what makes onCreate/onNewIntent
  reentrancy safe today; it is a **value-level idempotence guard**, not a
  text-transform guard.
- `CaptureViewModel.updateText(text)` — `CaptureViewModel.kt:42-44`. Raw
  passthrough, used for live keyboard input.
- `CaptureViewModel.save()` — `CaptureViewModel.kt:53-69`. Calls
  `_captureText.value.trim()` only (outer whitespace).
- `CaptureShareTextTest` — `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`.
  **Every test calls `CaptureActivity.buildShareText(clip, extra, subject)`
  directly** (line 12-13 shorthand `build()`), never `parseShareIntent`. No
  Robolectric/Android `Intent` mocking anywhere in the file.

## Findings

**1. Normalization belongs inside `buildShareText()`, not as a separate
function called after it in `parseShareIntent()`.**

`parseShareIntent()` takes an Android `Intent` and requires `Context`
(`intent.clipData?.getItemAt(0)?.coerceToText(this)`), so it cannot be unit
tested without Robolectric. `buildShareText()` is already a pure,
`internal`, companion-object function taking three nullable `String?`
args, and it is the sole thing `CaptureShareTextTest` exercises (confirmed
above — every one of its 12 tests calls `build()`, which forwards straight to
`CaptureActivity.buildShareText`). Putting normalization inside
`buildShareText()` means the existing test file continues to be the complete
test harness for this feature — no new Robolectric/mocking infrastructure is
needed, and the 12 existing test cases stay valid as regression coverage for
the resolution logic, with new cases appended for normalization behavior. A
separate post-call function in `parseShareIntent()` would be untestable
through the existing harness and would require standing up Intent mocking
that doesn't exist in this codebase today — clearly the worse option for a
"simple, localized bugfix" per the requirements doc's stated scope.

Both call sites (`onCreate:80` and `onNewIntent:118`) already funnel through
`parseShareIntent() → buildShareText()`, so normalizing inside
`buildShareText()` automatically covers both entry points with zero
additional wiring.

**2. Normalize before the image-marker concatenation — i.e., inside
`buildShareText()`/on `shareContent.text`, not after.**

The image path (`CaptureActivity.kt:82`,`120`) does:
```kotlin
"[image: ${shareContent.imageLocalPath}]\n${shareContent.text}".trim()
```
`shareContent.text` comes from `buildShareText()`'s return value
(`ShareContent(text, imagePath)` at `CaptureActivity.kt:143`, built from
`val text = buildShareText(...)` at line 134). If normalization lives inside
`buildShareText()`, then by the time this concatenation runs,
`shareContent.text` is already normalized and the `[image: ...]` marker line
is untouched — it is prepended afterward and is not part of the string that
went through collapse/normalize logic. This is the correct order: the
image-marker line must never be run through space-collapsing (it's a literal
path, could theoretically contain adjacent spaces in a directory name) or
newline-collapsing (only one `\n` separates it from the body, well under the
"3+ consecutive newlines" collapse threshold, so it's a non-issue in
practice, but keeping normalization scoped to `shareContent.text` avoids any
future risk if the marker format changes). If normalization were done
*after* concatenation instead, the whitespace-collapsing regex would run
across the synthetic marker line too — no known bug today, but an
unnecessary and untested coupling. Scope normalization strictly to
`shareContent.text` (i.e., inside `buildShareText()`), never to the
already-concatenated image+text string.

**3. Idempotence is not a risk for `onNewIntent` re-entrancy — but for a
different reason than one might assume, and it's worth being precise about
it.**

`onNewIntent` (singleTop re-launch) calls `parseShareIntent(intent)` again on
line 118, exactly like `onCreate` does. If a *new* share intent arrives with
different content, `parseShareIntent` → `buildShareText` runs on the new
raw text and normalizes it fresh — this is correct, not "double processing,"
because it's operating on new input, not re-normalizing old output.
`CaptureViewModel.initializeText()` is what actually guards against
overwriting user edits: it only applies the new (now-normalized) text if
`_captureText.value.isEmpty()` (`CaptureViewModel.kt:48`). So the real
sequencing is:
- First `onCreate` call: `_captureText` empty → normalized text applied.
- Subsequent `onNewIntent` for the *same* logical share (e.g., process
  survives and Android redelivers) or the user hasn't typed anything yet:
  `_captureText` is still exactly the previously-normalized string (not
  re-normalized — `initializeText` never re-applies once non-empty *unless*
  it was already empty). If it does get re-applied because the field is
  still empty, normalizing the same input string a second time produces
  the same output — collapsing runs of spaces/tabs, NBSP→space, and 3+
  newlines→2 are all idempotent transforms by construction (a string with no
  runs of 2+ spaces has no runs of 2+ spaces after another pass; same for
  newlines and NBSP). So even in the edge case, no double-processing bug is
  possible **as long as the normalization function itself is written as a
  true idempotent transform** (plain regex collapse, not e.g. a stateful
  counter). This is a design constraint on the implementation, not a
  pre-existing gap.
- If the user has already typed something (`_captureText.value.isNotEmpty()`
  and diverges from any share text), `initializeText` is a no-op regardless
  — manual typing is protected by the existing guard, consistent with the
  requirements doc's "NOT in updateText()/save(), so manual typing is
  untouched" instruction.

**4. Keep the normalization helper as a private helper in `CaptureActivity`'s
companion object, near `buildShareText()` — do not extract to a standalone
utility.**

Reuse pressure is low: the requirements doc explicitly scopes this to the
Android share-capture path only ("Out of scope: ... non-Android capture
paths"), and no other call site in the codebase needs this exact
transform (collapse spaces/tabs, NBSP→space, collapse 3+ newlines→2, single
line breaks preserved — a fairly specific policy tuned to share-sheet
mangling, not general markdown whitespace hygiene). `buildShareText()` is
already `internal` (package-visible, not `private`) specifically so the test
file in `androidApp/src/test/...` (same module, different source set) can
call it — the normalization helper can be `private` since only
`buildShareText()` itself needs to call it internally; the test file only
needs to observe `buildShareText()`'s output, not call the helper directly.
Extracting to a standalone `object`/file (e.g.
`androidApp/.../util/TextNormalization.kt`) would add an indirection layer
and a new test-visibility surface for a single call site — pure complexity
cost for a "simple, localized bugfix," consistent with the pattern already
used for `copyStreamToPrivateStorage()` (a private instance helper, not
extracted) in the same file.

**5. Confirmed test harness scope.** `CaptureShareTextTest.kt:12-13` — every
test method calls `build(clip, extra, subject)`, a private shorthand that
forwards directly to `CaptureActivity.buildShareText(clip, extra, subject)`.
No test in the file touches `parseShareIntent` or constructs an `Intent`.
This confirms finding #1: normalization must live inside `buildShareText()`
(or a helper it calls) to be reachable from the existing test harness without
adding Robolectric.

## Recommended integration point

```kotlin
internal fun buildShareText(
    clipText: String?,
    extraText: String?,
    subject: String?,
): String {
    val body = clipText?.takeIf { it.isNotBlank() }
        ?: extraText?.takeIf { it.isNotBlank() }
        ?: ""
    val title = subject?.takeIf { it.isNotBlank() }
    val combined = when {
        title != null && body.isNotBlank() && title != body -> "$title\n$body"
        body.isNotBlank() -> body
        else -> title ?: ""
    }
    return normalizeShareWhitespace(combined)
}

private fun normalizeShareWhitespace(text: String): String {
    // NBSP -> regular space, collapse space/tab runs, cap blank-line runs at 1 (i.e. max 2 newlines)
    ...
}
```

Normalize once, at the very end of `buildShareText()`, after the existing
priority/dedup resolution — not on the individual `clipText`/`extraText`/
`subject` inputs separately (that would risk re-introducing exactly the kind
of double-newline join artifact the dedup logic already guards against, and
would need to run three times instead of once). This keeps
`buildShareText()`'s existing behavior/tests cleanest: all 12 current test
cases use single-line inputs with no internal whitespace runs, so their
expected outputs are unaffected by adding a normalization pass at the end —
only new test cases exercising multi-space/NBSP/multi-newline inputs need to
be added to `CaptureShareTextTest`.
