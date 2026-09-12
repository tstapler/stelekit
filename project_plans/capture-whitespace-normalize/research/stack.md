# Research: Stack for Capture Whitespace Normalization

## minSdk / target API

`androidApp/build.gradle.kts:26-31`: `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`.

## Stdlib vs library

No third-party text-processing library is on the classpath — `androidApp/build.gradle.kts`
and `kmp/build.gradle.kts` have no `commons-text`, `commons-lang`, or `guava` dependency
(checked directly, no hits). The codebase already leans exclusively on `kotlin.text.Regex`
for this class of problem — 20+ existing `Regex("...\\s...")` call sites across
`kmp/src/commonMain` (e.g. `EditorViewModel.kt:733` `WHITESPACE_REGEX = Regex("\\s+")`,
`DatalogQuery.kt:28`, `TopicExtractor.kt:38`, `LlmProviderSupport.kt:54`). Kotlin stdlib
(`Regex`, `String.replace`, `String.trim`) is sufficient and is the established convention
here — no reason to reach for a library for this task.

## Unicode space handling on Android (java.util.regex / \p{Zs})

Android's regex engine (`java.util.regex`) has been backed by ICU-derived Unicode
character-property tables since API 24 (Nougat) — well below this project's `minSdk 26`.
`\p{Zs}` (Unicode `Zs` general category: SPACE, NO-BREAK SPACE U+00A0, EN SPACE, EM SPACE,
IDEOGRAPHIC SPACE, etc.) is supported without the `UNICODE_CHARACTER_CLASS` flag on
Android's implementation — unlike desktop OpenJDK, where plain `\s` is ASCII-only unless
`Pattern.UNICODE_CHARACTER_CLASS` is set. Practical implication: writing `\p{Zs}` explicitly
(rather than relying on `\s`) is the portable choice and works identically on the JVM host
(where `CaptureShareTextTest` actually executes, via plain `org.junit` — not Robolectric,
not an Android device) and on-device.

Recommended pattern:
```kotlin
private val UNICODE_SPACE_REGEX = Regex("\\p{Zs}")   // NBSP U+00A0 and all Unicode space separators
private val HORIZONTAL_RUN_REGEX = Regex("[ \\t]+")   // collapse runs of ASCII space/tab after normalization
private val EXCESS_BLANK_LINES_REGEX = Regex("\\n{3,}")
```
Order matters: normalize `\p{Zs}` → regular space *before* collapsing horizontal runs, so
NBSP-padded strings ("a  b") collapse correctly. Do not use bare `\s+` for the
horizontal-collapse step — `\s` also matches `\n`, which would defeat "preserve intentional
single line breaks."

## Existing whitespace-normalization utility to reuse

None found. Grepped `normalizeWhitespace|collapseWhitespace|WhitespaceNormalizer` across the
whole repo (`--include="*.kt"`) — zero hits. The closest precedents are single-purpose,
non-reusable `Regex("\\s+")` instances scoped to their own file (e.g.
`EditorViewModel.kt:733`, `DatalogQuery.kt:28`, `TopicExtractor.kt:38`,
`LlmProviderSupport.kt:54` `wordCount`) — none normalize NBSP or collapse blank-line runs.
This task needs a new small private function/regex set inside `CaptureActivity.kt`
(companion object, alongside `buildShareText`), not a shared utility — scope is
Android-capture-only per the requirements doc ("Apply once at share-intent ingestion").

## Where to hook it in

`CaptureActivity.buildShareText()` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`)
is a `internal fun` on the `companion object`, called from `parseShareIntent()` (line 134)
and unit-tested directly and statically in `CaptureShareTextTest.kt` (JVM `org.junit`, no
Robolectric/Android runtime — confirms `java.util.regex` behavior on host JVM is the actual
test oracle, so `\p{Zs}` usage must be verified there, not assumed from Android docs alone).
Normalization should be applied to the winning `body`/`title` strings inside
`buildShareText()` (or as a wrapping helper called on its result) — *not* in
`CaptureViewModel.updateText()`/`save()` (`CaptureViewModel.kt:42-55`), which must stay
untouched so manual typing in the capture text field is never mangled. `save()` currently
only does `.trim()` (line 54) — that stays as-is; it just now trims an already-normalized
string on the share-intent path.

## Recommended approach summary

1. Add a private normalization function to `CaptureActivity`'s companion object, e.g.
   `normalizeWhitespace(text: String): String`.
2. Steps: (a) replace `\p{Zs}` → `" "`, (b) collapse `[ \t]+` → `" "`, (c) collapse `\n{3,}`
   → `"\n\n"`, (d) leave single `\n` and `\n\n` untouched, (e) do NOT re-trim here if
   `buildShareText` callers already rely on outer `.trim()` in `save()` — but confirm no
   leading/trailing space regressions in new tests.
3. Call it on the resolved `body`/`title` (or on the final composed string) inside
   `buildShareText()`, before the `when` branch that joins title+body — apply per-source
   normalization or once on the final joined string; either is spec-compliant since the
   requirement is "once at share-intent ingestion," but normalizing the two inputs
   separately avoids the newline-join in the title+body case being miscounted as part of a
   3+ run.
4. Add cases to `CaptureShareTextTest.kt`: NBSP → space, multiple spaces/tabs collapsed, 3+
   newlines collapsed to 2, single newlines preserved, mixed NBSP+multi-space+newline
   combinations, and a manual-typing-unaffected sanity note (not testable here since
   `updateText`/`save` aren't exercised by this file — mention in comment/test name).
