# Research: Stdlib facilities for share-text whitespace normalization

Backlog item: `a3b1ba34-7ecf-456b-9b4a-ad25eb2de5d5`
Scope: `CaptureActivity.buildShareText()` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`)

## Environment facts (verified)

- Kotlin version: **2.3.21**, pinned in
  [`settings.gradle.kts:9-13`](settings.gradle.kts) (`kotlin("multiplatform")`,
  `kotlin("android")`, etc. all pinned together). No separate version catalog
  (`libs.versions.toml`) exists in this repo — versions live directly in
  `settings.gradle.kts` / `kmp/build.gradle.kts`.
- `androidApp/build.gradle.kts:26,30`: `compileSdk = 36`, `minSdk = 26`. Modern
  `java.util.regex`-backed `kotlin.text.Regex` is fully available at this
  floor — no API-level regex caveats.
- `CaptureActivity.buildShareText()` is a `internal` companion-object function,
  pure `String -> String`, already exercised directly by
  `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`
  (12 cases, JVM unit test, no Android framework dependency needed).

## Existing normalization utilities in the repo — none reusable

Searched `androidApp/src/main` and `kmp/src/commonMain` for `normalize`,
`collapse`, `Whitespace` (case-insensitive). Hits were all false positives for
this problem:

- `editor/blocks/IBlockOperations.kt` / `BlockOperations.kt`:
  `collapseSubtree()` — outliner UI block-tree collapse, unrelated to text.
- `editor/text/TextModels.kt`: `TextRange.collapsed(at)` — a zero-width
  cursor range, unrelated to text.
- `model/Models.kt:14`: `validateString(input, maxLength, allowWhitespace)` —
  length/blank validation, not whitespace-run collapsing.

**Conclusion: no existing whitespace-collapsing utility exists anywhere in
the codebase.** This is new, self-contained code — not a duplicate of
anything.

## Kotlin/Android facility survey

| Need | Right tool | Why |
|---|---|---|
| Collapse 2+ spaces/tabs → 1 space | `Regex("[ \t]{2,}").replace(str, " ")` | `String.replace(oldValue, newValue)` only does literal substring replace, not "any run of N" — needs a real pattern. `kotlin.text.Regex` wraps `java.util.regex.Pattern`, available since Kotlin 1.0 / Android API 1. No stdlib "collapse whitespace" helper exists in Kotlin (unlike Guava's `CharMatcher.WHITESPACE.collapseFrom`) — a small custom regex is the idiomatic approach here. |
| Normalize U+00A0 → U+0020 | `str.replace(' ', ' ')` | `String.replace(Char, Char)` is the simplest correct tool for a 1:1 char substitution — no regex needed, and it must run *before* the space/tab collapse so a solitary NBSP is still normalized even when not repeated (AC2). |
| Collapse 3+ newlines / blank-only-line runs → 1 blank line | `Regex("\n[ \t]*(?:\n[ \t]*)+").replace(str, "\n\n")` | This is the one place `lines()` + manual re-join was considered and rejected: a single regex pass is simpler to reason about and to unit test than a stateful loop over `lines()`, and naturally requires ≥2 literal `\n` in the match (so a lone `\n` between two content lines can never match — AC4 falls out for free, not as a special case). |
| CRLF handling | `str.replace("\r\n", "\n").replace('\r', '\n')` as a **pre-pass**, before the two regex steps above | Unifying line endings first means the blank-line regex only ever has to reason about `\n`, not `\r\n` vs `\n` vs bare `\r`. This is standard practice and satisfies the requirement's "`\r\n` treated equivalently to `\n` for blank-line collapsing" — it doesn't require preserving CRLF byte-for-byte in the output, which the requirements don't ask for either (only that CRLF inputs are *handled*, i.e. tested and collapsed correctly). |
| `android.text.TextUtils` | **Not used** | `TextUtils` (`isEmpty`, `join`, `split`, `ellipsize`, `htmlEncode`, …) has no whitespace-collapse facility comparable to this, and pulling in `android.text` for a pure `String -> String` transform would make the function harder to unit-test outside Robolectric for no benefit. `kotlin.text` is sufficient and keeps `buildShareText()` a plain JVM-testable function (as it already is). |

### Why not `String.trim()` / `String.lines()` alone

- `trim()` only strips leading/trailing whitespace (this is already what
  `CaptureViewModel.save()` does today, per the requirements doc) — it does
  nothing for *internal* runs, which is the actual bug.
- `lines()` (or `lineSequence()`) is the right tool if the algorithm needs to
  branch per-line (it doesn't, here) — for a pure "collapse N-or-more
  newlines-with-optional-blank-content" transform, one `Regex.replace` is
  both shorter and easier to characterize with the AC1–AC4 test matrix than a
  manual split/filter/join over `lines()`.

## Recommended implementation

Add a small private, pre-compiled-`Regex` normalization step, invoked once at
the end of `buildShareText()` so there's a single call site (satisfies the
"not scattered across call sites" constraint) and `onCreate`/`onNewIntent`
need no changes since they already just call `buildShareText()`:

```kotlin
companion object {
    // Compiled once — Regex construction is not free, and this runs on every share intent.
    private val SPACE_TAB_RUN = Regex("[ \t]{2,}")
    private val BLANK_LINE_RUN = Regex("\n[ \t]*(?:\n[ \t]*)+")

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

    /**
     * Normalizes whitespace artifacts common in browser/HTML-aware share payloads:
     * NBSP -> regular space, runs of 2+ spaces/tabs -> one space, and 3+ newlines
     * (or newlines separated only by whitespace-only lines) -> one blank line.
     * A single `\n` between two content lines is left untouched.
     */
    internal fun normalizeShareWhitespace(text: String): String {
        val unifiedLineEndings = text.replace("\r\n", "\n").replace('\r', '\n')
        val nbspNormalized = unifiedLineEndings.replace(' ', ' ')
        val spacesCollapsed = nbspNormalized.replace(SPACE_TAB_RUN, " ")
        return spacesCollapsed.replace(BLANK_LINE_RUN, "\n\n")
    }
}
```

Notes for the plan phase:

- `normalizeShareWhitespace` is exposed `internal` (same visibility as
  `buildShareText`) so `CaptureShareTextTest` (same package,
  `androidApp/src/test`) can unit-test it directly if the new AC7 cases are
  written against it in isolation, in addition to the combined
  `buildShareText()` cases the requirements call for.
- Order of operations matters and should be preserved exactly as above:
  unify line endings → normalize NBSP → collapse space/tab runs → collapse
  blank-line runs. Collapsing spaces before blank-lines is required so a line
  that was originally e.g. `"    "` (multiple spaces/NBSP, no other
  content) is correctly recognized as whitespace-only by the blank-line
  regex's `[ \t]*`.
- `CaptureViewModel.updateText()` (AC6) must **not** call
  `normalizeShareWhitespace` — only `buildShareText()`'s call site should.
  This keeps manually-typed text untouched, per the requirement's scope
  boundary.
- No new Gradle dependency is needed anywhere in this design — everything is
  `kotlin.text` (`Regex`, `String.replace`), consistent with the "ponytail
  ladder rung 2 / pure stdlib" non-functional constraint.
