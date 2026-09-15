# Build vs. Buy: Android Share Capture Whitespace Normalization

Scope: ~10-20 lines of Kotlin string/regex logic to collapse space/tab runs, normalize NBSP
(U+00A0), and collapse excess blank lines in `CaptureActivity.buildShareText()` output. See
`project_plans/android-share-capture-whitespace/requirements.md`.

## 1. Existing OSS library on the classpath

Checked `kmp/build.gradle.kts` (all source sets) and `androidApp/build.gradle.kts` for
Guava (`CharMatcher`/`Whitespace`), Apache Commons Lang/Text (`StringUtils`), and ICU4J —
none present, confirmed by grep (`guava|commons-lang|commons-text|icu4j|apache\.commons|CharMatcher`
→ no matches in either file).

The closest thing already on the classpath is **`com.fleeksoft.ksoup:ksoup:0.2.6`**
(commonMain, a KMP port of jsoup), used in `HtmlBlockConverter.kt` and the URL-fetcher
preview pipeline — `Element.text()` there does HTML-aware whitespace collapsing as a side
effect of text extraction. It's not usable here: (a) it's a commonMain dependency, not
currently on `androidApp`'s classpath, so using it would mean adding a new dependency edge
to a module that has none of the KMP libraries today; (b) it's an HTML-DOM text extractor,
not a generic string-whitespace normalizer — it has no CRLF-aware blank-line-run collapsing
semantics (AC3/AC4), which is the actual crux of this bug; (c) share-intent text is often
already plain text (from `EXTRA_TEXT`), not HTML, so routing it through an HTML parser to
extract text is solving the wrong problem.

**Pros of pulling in a library:** none material. Guava's `CharMatcher.whitespace().collapseFrom()`
or Commons Lang `StringUtils.normalizeSpace()` would handle AC1/AC2 in one line each, but
neither has built-in support for AC3's "collapse 3+ newlines or whitespace-only-line-separated
blank runs to at most one blank line" — that's bespoke regex regardless of which library is
chosen, so a library only buys back ~2 of the ~15 lines.

**Cons:** New dependency (Guava is large; Commons Lang/Text and ICU4J are lighter but still new
transitive surface) on a module (`androidApp`) that currently has zero general-purpose string
utility libraries. Requirements explicitly state "no new dependencies... pure String/Regex
stdlib work (ponytail ladder rung 2)" — pulling in a library would directly violate a stated
non-functional requirement for no proportionate benefit.

**Verdict: Skip.** Not justified — the library only covers a fraction of the logic, adds
dependency surface for a module that has none today, and contradicts an explicit requirement.

## 2. SaaS / managed API

Not applicable. This is synchronous, offline, in-process string transformation on
device-local clipboard/share-intent text (potentially containing sensitive personal notes).
There is no network-bound service that makes sense to call for regex-level string cleanup —
latency, offline-capability loss (capture must work without connectivity), and privacy
(sending arbitrary share payloads to a third party) all argue against it. Dismissed without
further evaluation.

## 3. LLM-generated bespoke regex vs. library

This is simple, well-bounded, non-adversarial text processing — not a security-sensitive
parser (no untrusted-input injection risk; worst case of a wrong regex is a formatting
mistake in a journal block, not a vulnerability) and not a general-purpose text-cleanup
utility that needs to handle arbitrary future input shapes. The four rules (AC1-AC4) are
independently testable with small, deterministic regexes:

- space/tab run collapse: `Regex("[ \t]+")` → `" "`
- NBSP normalize: `' '` → `' '` (then falls through to the space/tab collapse)
- CRLF normalize: `"\r\n"` → `"\n"` before line-based collapsing
- blank-line-run collapse: a regex over lines that are empty or whitespace-only, collapsing
  runs of 2+ such separators to a single blank line

Correctness risk is low and cheaply bounded by tests: AC7/AC8 already require adding the
exact test cases (space runs, NBSP, blank-line collapsing incl. whitespace-only lines and
CRLF, one combined browser-payload case) plus the 12 existing `CaptureShareTextTest` cases
staying green — this is a complete correctness net for the four rules in scope. Maintenance
burden is near zero: no dependency to track for updates/CVEs, no version bump coordination,
and the logic is small enough that a future reader can verify it by inspection in under a
minute (unlike a Guava/Commons call whose exact whitespace-class semantics require checking
docs).

**Verdict: Build.** Hand-written regex is the right call here — the problem is small, fully
covered by the required test matrix, and matches the requirements doc's explicit rung-2
("stdlib only") ponytail guidance.

## 4. Fork / adapt existing pattern in this monorepo

Searched `kmp/src/commonMain` and `androidApp/src/main` for existing whitespace-normalization
regex patterns:

- `EditorViewModel.kt:733` and `DatalogQuery.kt:28` both define `private val WHITESPACE_REGEX
  = Regex("\\s+")` — but these are used for **word-splitting** (word count, Datalog token
  split), not normalization-in-place. `\s+` also matches newlines, which would violate AC4
  (single `\n` must be preserved) if reused directly — not a fit.
- `LlmProviderSupport.kt:54` — same word-count use case via `text.split(Regex("\\s+"))`.
- `HtmlBlockConverter.kt` — uses ksoup's `Element.text().trim()` for DOM text extraction
  (discussed in §1); no bespoke blank-line-collapse or NBSP-specific logic to lift.
- No hits for `normalizeWhitespace`, `collapseWhitespace`, `trimBlankLines`, `normalizeText`,
  or any NBSP (`u00a0`/`nbsp`) handling anywhere in `kmp/src` or `androidApp/src`.

**Verdict: Nothing to fork.** No existing implementation in the monorepo solves (or even
partially solves) AC1-AC4's specific combination of space/tab collapsing, NBSP normalization,
and CRLF-aware blank-line-run collapsing while preserving single line breaks. The two
`WHITESPACE_REGEX` occurrences are a different problem (tokenization) and are actively
wrong for this use case since they'd collapse newlines. New code is required regardless of
this path.

## Summary

| Option | Verdict |
|---|---|
| Existing OSS library (Guava/Commons/ICU4J) | Skip — not on classpath, covers only part of the logic, adds dependency surface, violates stated no-new-deps requirement |
| SaaS/managed API | Not applicable — dismissed |
| LLM-generated bespoke regex | **Build** — small, fully testable via required AC7 test matrix, matches ponytail rung-2 guidance |
| Fork/adapt in-repo | Nothing reusable found — proceed with new code |

**Overall recommendation: write the ~15-line bespoke normalization function in
`CaptureActivity.kt` (or a small extracted helper), backed by the AC5/AC7 test matrix in
`CaptureShareTextTest`, with no new dependencies.**
