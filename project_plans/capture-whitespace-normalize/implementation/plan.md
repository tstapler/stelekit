# Implementation Plan: capture-whitespace-normalize

**Feature**: Normalize incidental whitespace (space/tab runs, NBSP, excess blank lines) in Android share-capture text before it becomes journal block content
**Date**: 2026-08-10
**Status**: Ready for implementation
**ADRs**: None

---

## Domain Glossary

This is a small, localized bugfix inside one existing companion-object pure function (`CaptureActivity.buildShareText()`) — no new subsystem, no new domain types.

| Term | Definition | Notes |
|------|-----------|-------|
| `normalizeShareWhitespace` | New private function in `CaptureActivity`'s companion object that collapses incidental whitespace noise (line-ending variants, NBSP, space/tab runs, excess blank lines) in a share payload string. | The only new symbol this feature introduces. |
| NBSP | Non-breaking space, Unicode U+00A0. Appears in share text when `ClipData.Item.coerceToText()` decodes `&nbsp;` HTML entities from HTML-typed clipboard/share data. | Matched literally (`\u00A0`), not via `\p{Zs}` — see Pattern Decisions. |
| `buildShareText` | Existing companion-object function (`CaptureActivity.kt:186-201`) that resolves clipData/EXTRA_TEXT/EXTRA_SUBJECT priority into one string. | Unchanged signature; normalization is appended as the final step of its return value. |
| `ShareContent` | Existing private data class carrying the resolved share text + optional image path. | Untouched by this change. |

No other new domain types are introduced.

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Whitespace-normalization location | Pure function co-located in `CaptureActivity`'s companion object, called once at the end of `buildShareText()` | research/architecture.md | Extracted standalone utility object/file | Single call site; requirements scope this to Android-share-only; matches the existing precedent of `copyStreamToPrivateStorage()` (private instance helper, not extracted) — extraction adds indirection with no reuse benefit. |
| Normalization trigger point | Applied once, inside `buildShareText()`, on the fully-resolved/joined string | research/architecture.md | Apply inside `parseShareIntent()` after calling `buildShareText()`; or normalize each of clipText/extraText/subject separately before joining | `parseShareIntent()` needs a real `Intent`/`Context` and isn't reachable by `CaptureShareTextTest` (would require Robolectric). Normalizing each input separately would risk re-introducing the double-newline-join artifact the existing title/body dedup logic already guards against, and would run the regex chain 3x instead of once. |
| Unicode space matching | Literal NBSP match (`"\u00A0"`) | research/pitfalls.md #3 | `Regex("\\p{Zs}")` (all Unicode space separators) | Requirements literally scope this to NBSP. Broadening to all `\p{Zs}` would also normalize semantically-significant spacing (e.g. French narrow no-break space U+202F used as a numeric thousands separator) — undiscussed scope expansion. |
| Dependency | Hand-rolled chained `String.replace`/`Regex` calls, stdlib only | research/build-vs-buy.md | Adopt a string utility library (Commons Lang3, Guava, ICU4J) | No such library is on the classpath anywhere in the repo; none replicate this exact spec (preserve single newlines, collapse only 3+) out of the box; adding one buys neither convenience nor correctness while adding APK size/maintenance surface. Matches CLAUDE.md's stdlib-first dependency ladder. |

No GoF/PoEAA pattern (Strategy, Template Method, etc.) genuinely applies to a 5-line regex pipeline — none is force-fit here.

---

## Migration Plan
N/A — no schema or data changes; this is a pure in-memory string transform in an existing function.

## Observability Plan
N/A — client-side string transform with no new service boundary; existing save-error UI/logging paths are unaffected and untouched.

## Risk Control
Ships as a normal PR — no feature flag needed for a client-side text-formatting bugfix scoped entirely to the share-capture path.

Accepted risks (documented, not fixed by this plan):
- **Code/markdown corruption**: blanket space-run collapsing would destroy leading indentation if a user shares a multi-line code snippet. Low probability given the capture UI's 3-8 line quick-note scope (`minLines=3`/`maxLines=8`) and typical share-sheet traffic (tweets, article excerpts, URLs, page titles); no code-fence detection is added, since that would be undiscussed scope expansion. Mitigating factor confirmed by triad UX review: normalization happens inside `buildShareText()`, called before `CaptureViewModel.initializeText()` sets `_captureText.value` — the same `StateFlow` the editable `OutlinedTextField` renders (`CaptureActivity.kt:211`/`288`). The user sees the already-normalized text in an editable field before pressing Save, so if collapsing ever mangles something they care about, they see it and can fix or cancel before it's persisted — this is a real, if previously unstated, review/undo safety net for this accepted risk.
- **Post-concatenation blank-line overflow**: normalizing only `buildShareText()`'s own output means the `[image: path]\n{text}` concatenation (`CaptureActivity.kt:82,120`, outside `buildShareText()` and out of scope) can still combine to produce 3 newlines if the body itself starts with a blank line. Not fixed here — touching the image-concatenation code is explicitly out of scope per requirements.

Previously-accepted risk now fixed (pre-mortem P1, addressed pre-implementation): the original plan accepted "blank line with a stray space between newlines is not collapsed" as a known gap — but that case is verbatim the requirements doc's own motivating example ("repeated blank lines carried over from the source DOM/layout"), so a pre-mortem review flagged shipping it unfixed as P1 (the fix would ship without solving the problem it was written for). Story 1.1.1's operation order now includes a step 3 that strips whitespace-only lines to truly empty (`Regex("(?m)^[ \t]+$")` → `""`) before the newline-collapse step, closing this gap; see the new AC3 Given/When/Then case and Task 1.1.2c's added test.

## Unresolved Questions
None.

## Dependency Visualization
```
Task 1.1.1a (normalizeShareWhitespace + wire into buildShareText)
        |
        v
Task 1.1.2a (AC1 + AC4 tests: space/tab collapse, single-newline preserved)
Task 1.1.2b (AC2 test: NBSP normalization)
Task 1.1.2c (AC3 test: blank-line collapse, incl. CRLF ordering case)
Task 1.1.2d (AC5 regression check + AC7 combined browser-payload test)
Task 1.1.2e (AC6 regression test: CaptureViewModel.updateText is not normalized)
        |
        v
Task 1.1.3a (run CaptureShareTextTest locally — confirm 12 existing + 7 new tests pass)
Task 1.1.3b (wire :androidApp:testDebugUnitTest into ci.yml's android job — no *code*
             dependency on 1.1.1a-1.1.3a, but must ship in the SAME PR per pre-mortem P2:
             deferring it recreates the "CI stays green if tests are deleted" gap)
```

---

## Phase 1: Share-capture whitespace normalization

### Epic 1.1: Normalize whitespace in Android share capture
**Goal**: Winning share text is normalized (line endings, NBSP, space/tab runs, blank-line runs) exactly once at share-intent ingestion, before it becomes journal block content, without touching the manual-typing path.

#### Story 1.1.1: Normalize whitespace at share-intent ingestion
**As a** user sharing text from a browser or other app into SteleKit's capture sheet, **I want** the captured text's incidental whitespace cleaned up, **so that** my journal entry doesn't contain stray double spaces, non-breaking spaces, or extra blank lines I never typed.

**Acceptance Criteria** (requirements.md AC1, AC2, AC3, AC4, AC6):
- AC1 — runs of 2+ regular spaces/tabs collapse to a single space.
  - *Given* `extraText = "hello   world\tfoo"` (three spaces then one tab), *When* `CaptureActivity.buildShareText(null, "hello   world\tfoo", null)` is called, *Then* it returns `"hello world foo"`.
- AC2 — NBSP (U+00A0) is normalized to a regular space, then collapsed per AC1 if repeated.
  - *Given* `extraText = "hello\u00A0\u00A0\u00A0world"` (three consecutive NBSP characters), *When* `CaptureActivity.buildShareText(null, "hello\u00A0\u00A0\u00A0world", null)` is called, *Then* it returns `"hello world"`.
- AC3 — 3+ consecutive newlines collapse to at most one blank line (2 consecutive `\n`).
  - *Given* `extraText = "Para one\n\n\n\nPara two"` (4 newlines), *When* `CaptureActivity.buildShareText(null, "Para one\n\n\n\nPara two", null)` is called, *Then* it returns `"Para one\n\nPara two"`.
  - *Given* `extraText = "Para one\r\n\r\n\r\nPara two"` (CRLF line endings — no substring of 3 bare `\n` exists, so line endings must be normalized to `\n` before the newline-collapse step or this case silently fails), *When* `CaptureActivity.buildShareText(null, "Para one\r\n\r\n\r\nPara two", null)` is called, *Then* it returns `"Para one\n\nPara two"`.
  - *Given* `extraText = "Para one\n \n \nPara two"` (newlines separated by a lone space each — a whitespace-only "blank" line, the exact browser-DOM artifact requirements.md cites as its motivating example), *When* `CaptureActivity.buildShareText(null, "Para one\n \n \nPara two", null)` is called, *Then* it returns `"Para one\n\nPara two"`. (Pre-mortem P1 finding: without the whitespace-only-line stripping step below, this case silently passes through unchanged because a lone space is a run of length 1, invisible to both the horizontal-collapse and newline-collapse steps.)
- AC4 — a single intentional line break is preserved unchanged.
  - *Given* `extraText = "Line one\nLine two"`, *When* `CaptureActivity.buildShareText(null, "Line one\nLine two", null)` is called, *Then* it returns `"Line one\nLine two"` (unmodified).
- AC6 — manual typing is not subject to this normalization.
  - *Given* a user types `"hello   world"` directly into the capture `OutlinedTextField`, *When* `CaptureViewModel.updateText("hello   world")` is called (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:42-44`), *Then* `_captureText.value` is exactly `"hello   world"` — `updateText()` never calls `buildShareText()`/`normalizeShareWhitespace()`, so no code change to `updateText()` or `save()`'s existing `.trim()` is required to satisfy this criterion. Backed by an executable test, not just this code-inspection argument — see Task 1.1.2e (`CaptureViewModelTest`).

**Implementation constraint — operation order** (must not be reordered):
1. Normalize line endings: `\r\n` → `\n`, then lone `\r` → `\n`.
2. Normalize NBSP → regular space (literal `\u00A0` match, not `\p{Zs}`).
3. Strip whitespace-only "blank" lines to truly empty: `Regex("(?m)^[ \t]+$")` → `""`. This turns a line containing only spaces/tabs (e.g. the middle line of `"Para one\n \n \nPara two"`) into a genuinely empty line, so it participates correctly in step 5's newline collapsing instead of silently blocking it. (Added post-plan-review per pre-mortem P1 finding — the requirements doc's own motivating example, "repeated blank lines carried over from the source DOM/layout," is exactly this whitespace-only-line case, not just bare `\n{3,}` runs.)
4. Collapse horizontal whitespace runs: `[ \t]+` → single space.
5. Collapse 3+ newlines → exactly 2 (`\n{3,}` → `\n\n`).
6. `.trim()` the result.

Why this exact order: (a) a `\r\n\r\n\r\n` run contains no substring of 3 consecutive bare `\n` characters — each `\r` breaks LF adjacency — so step 5 run before step 1 silently no-ops on CRLF input; (b) `[ \t]+` does not match `\u00A0`, so step 4 run before step 2 leaves runs of un-collapsed NBSP-turned-spaces behind; (c) step 3 must run before step 5 (it's what makes whitespace-only lines collapsible at all) and after step 2 (a blank line could itself contain only NBSP, which must already be a regular space for `[ \t]+$` to match it).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 1.1.1a: Add `normalizeShareWhitespace()` and wire it into `buildShareText()` (~5 min)
- In `CaptureActivity.kt`'s companion object (after the existing `buildShareText` function, `CaptureActivity.kt:186-201`), change the `return when { ... }` expression to assign to a local `combined` and return `normalizeShareWhitespace(combined)` instead:
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

  /**
   * Collapses incidental whitespace noise from share payloads (browser text selections,
   * HTML-derived clipboard text) without touching intentional single line breaks.
   * Order matters — see plan.md Story 1.1.1 "Implementation constraint".
   */
  private fun normalizeShareWhitespace(text: String): String =
      text
          .replace("\r\n", "\n")
          .replace('\r', '\n')
          .replace("\u00A0", " ")
          .replace(Regex("(?m)^[ \\t]+$"), "")
          .replace(Regex("[ \\t]+"), " ")
          .replace(Regex("\\n{3,}"), "\n\n")
          .trim()
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

#### Story 1.1.2: Add whitespace-normalization test coverage
**As a** maintainer, **I want** `CaptureShareTextTest` (and a new minimal `CaptureViewModelTest`) to cover the new normalization behavior without breaking existing coverage, **so that** regressions in whitespace handling are caught before release.

**Acceptance Criteria** (requirements.md AC5, AC6, AC7):
- AC5 — all 12 existing source-priority/fallback test cases continue to pass unmodified.
  - *Given* the existing test `` `url only in EXTRA_TEXT, no clipData, no subject` `` (`CaptureShareTextTest.kt:16-18`), *When* the test suite runs after Task 1.1.1a, *Then* `build(null, "https://example.com", null)` still returns `"https://example.com"` (single-line input with no internal whitespace runs — normalization is a no-op here).
- AC6 — manual typing is not subject to the new normalization, backed by an executable test (not just code inspection).
  - Satisfied by Task 1.1.2e below.
- AC7 — new tests cover internal whitespace collapsing, NBSP normalization, excess-blank-line collapsing, and one combined realistic browser-share payload.
  - Satisfied by Tasks 1.1.2a–1.1.2d below.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`, `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (new)

##### Task 1.1.2a: Add space/tab-run collapse and single-newline-preserved tests (~4 min)
- Add to `CaptureShareTextTest.kt` (after the last existing `@Test`, `CaptureShareTextTest.kt:74-77`):
  ```kotlin
  @Test
  fun `internal runs of spaces and tabs collapse to a single space`() {
      assertEquals("hello world foo", build(null, "hello   world\tfoo", null))
  }

  @Test
  fun `single intentional line break is preserved`() {
      assertEquals("Line one\nLine two", build(null, "Line one\nLine two", null))
  }
  ```
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.1.2b: Add NBSP normalization test (~3 min)
- Add:
  ```kotlin
  @Test
  fun `non-breaking spaces normalize to regular spaces and collapse`() {
      assertEquals("hello world", build(null, "hello\u00A0\u00A0\u00A0world", null))
  }
  ```
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.1.2c: Add excess-blank-line collapse tests, including CRLF ordering and whitespace-only-line cases (~5 min)
- Add:
  ```kotlin
  @Test
  fun `3 or more consecutive newlines collapse to one blank line`() {
      assertEquals("Para one\n\nPara two", build(null, "Para one\n\n\n\nPara two", null))
  }

  @Test
  fun `CRLF line endings collapse correctly (line-ending normalization precedes newline collapse)`() {
      assertEquals("Para one\n\nPara two", build(null, "Para one\r\n\r\n\r\nPara two", null))
  }

  @Test
  fun `whitespace-only blank line between newlines collapses (pre-mortem P1 regression)`() {
      // "Para one\n \n \nPara two" — a lone space on each blank line, the exact browser-DOM
      // artifact requirements.md cites as its motivating example. Without the whitespace-only-line
      // stripping step, this silently passes through unchanged (each lone space is a run of
      // length 1, invisible to both the horizontal-collapse and \n{3,} steps).
      assertEquals("Para one\n\nPara two", build(null, "Para one\n \n \nPara two", null))
  }
  ```
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.1.2d: Add combined realistic browser-share-payload test (~4 min)
- Add:
  ```kotlin
  @Test
  fun `combined realistic browser share payload is normalized`() {
      val raw = "Breaking:   Something\u00A0Happened\r\n\r\n\r\nRead more at the site.\ttoday."
      assertEquals(
          "Breaking: Something Happened\n\nRead more at the site. today.",
          build(null, raw, null),
      )
  }
  ```
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.1.2e: Add `CaptureViewModelTest` proving manual typing is not normalized (AC6) (~5 min)
- No `CaptureViewModelTest` exists yet. `CaptureViewModel` extends `AndroidViewModel`, which requires
  a real `Application` instance to construct — get one via Robolectric's `ApplicationProvider`,
  following the existing pattern already used in
  `androidApp/src/test/kotlin/dev/stapler/stelekit/auto/AudiobookAutoSettingsTest.kt`
  (`@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [29])`). Robolectric is already a
  `testImplementation` dependency (`androidApp/build.gradle.kts:117`) — no new dependency needed.
  `updateText()` only mutates the in-memory `_captureText` `StateFlow` and never touches the
  `Application`/`viewModelScope`, so this is a same-thread, no-Robolectric-quirks test.
- Create new file `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`:
  ```kotlin
  // Copyright (c) 2026 Tyler Stapler
  // SPDX-License-Identifier: Elastic-2.0

  package dev.stapler.stelekit

  import android.app.Application
  import androidx.test.core.app.ApplicationProvider
  import org.junit.Assert.assertEquals
  import org.junit.Test
  import org.junit.runner.RunWith
  import org.robolectric.RobolectricTestRunner
  import org.robolectric.annotation.Config

  @RunWith(RobolectricTestRunner::class)
  @Config(sdk = [29])
  class CaptureViewModelTest {

      @Test
      fun `updateText does not normalize manually typed whitespace`() {
          val app = ApplicationProvider.getApplicationContext<Application>()
          val viewModel = CaptureViewModel(app)

          viewModel.updateText("hello   world")

          assertEquals("hello   world", viewModel.captureText.value)
      }
  }
  ```
- Deliberately minimal — one test, no `save()`/`performSave()` coverage (that needs a
  `GraphManager`/`SteleKitApplication` fixture, out of scope for this AC6 regression test).
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (new)

#### Story 1.1.3: Wire into CI, then verify
**As a** maintainer, **I want** `CaptureShareTextTest` and `CaptureViewModelTest` wired into CI and confirmed passing, **so that** the 19 `CaptureShareTextTest` cases (12 existing + 7 new) and the 1 new `CaptureViewModelTest` case are checked automatically on every PR — not just once, locally, by the implementer — and a later PR that breaks or deletes this coverage is caught before release rather than passing silently.

Today, CI's `android` job runs `./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug` (`.github/workflows/ci.yml`) — `assembleDebug` compiles only the `main` source set and never compiles or runs `androidApp/src/test`. No job invokes `:androidApp:testDebugUnitTest` or `ciCheck`. That means, prior to Task 1.1.3b landing, this whole test file (and the new one) provide zero durable CI protection: a later PR could delete or break `CaptureShareTextTest`/`CaptureViewModelTest` and every CI check would stay green. Task 1.1.3b closes that gap.

**Acceptance Criteria**:
- CI's `android` job compiles and runs `androidApp/src/test`.
  - *Given* `.github/workflows/ci.yml`'s `android` job, *When* Task 1.1.3b lands, *Then* its Gradle invocation includes `:androidApp:testDebugUnitTest` alongside the existing `:kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug` targets, and a subsequent PR run of the `android` job compiles and executes both `CaptureShareTextTest` and `CaptureViewModelTest`, with failures reported via the job's existing "Publish test report" step.
- All tests in `CaptureShareTextTest` and `CaptureViewModelTest` pass.
  - *Given* Tasks 1.1.1a–1.1.2e are complete, *When* the test classes run (locally via Task 1.1.3a, and thereafter automatically in CI via Task 1.1.3b), *Then* all 19 `CaptureShareTextTest` `@Test` methods and the 1 `CaptureViewModelTest` `@Test` method pass with zero failures.

**Files**: `.github/workflows/ci.yml` (Task 1.1.3b); none for Task 1.1.3a (local verification command only)

##### Task 1.1.3a: Run `CaptureShareTextTest` and `CaptureViewModelTest` locally and confirm all cases pass (~3 min)
- Run: `./gradlew :androidApp:testDebugUnitTest --tests "dev.stapler.stelekit.CaptureShareTextTest" --tests "dev.stapler.stelekit.CaptureViewModelTest"`
- Confirm output shows 20 tests (19 + 1), 0 failures.
- Files: none (verification command only)

##### Task 1.1.3b: Wire `:androidApp:testDebugUnitTest` into the CI `android` job (~3 min)
- Root cause of the CI gap: the `android` job's only Gradle invocation targets `:androidApp:assembleDebug`, which builds the `main` source set — it has no reason to touch `src/test`, so it doesn't. `:kmp:testDebugUnitTest` in the same command runs the `kmp` module's Android unit tests, not `androidApp`'s.
- In `.github/workflows/ci.yml`, in the `android` job's "Run Android tests and build APK" step, change:
  ```yaml
  # before (.github/workflows/ci.yml, "Run Android tests and build APK" step)
  run: ./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache
  ```
  to:
  ```yaml
  # after
  run: ./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:testDebugUnitTest :androidApp:assembleDebug --no-daemon --build-cache
  ```
- No other changes needed: the same step's "Publish test report" (`report_paths: '**/build/test-results/testDebugUnitTest/**/TEST-*.xml'`) and the `android-test-results` artifact upload (`path: '**/build/test-results/testDebugUnitTest/**/*.xml'`) both already use recursive `**` globs, so they pick up `androidApp/build/test-results/testDebugUnitTest/` alongside `kmp/`'s existing results without edits.
- **Ship in the same PR as Tasks 1.1.1a–1.1.3a, not a follow-up** (pre-mortem P2 finding): deferring this task is the easiest way to silently recreate the exact "CI stays green if this test file is deleted" gap an earlier adversarial review caught once already. This PR is not done until `git diff` on `.github/workflows/ci.yml` shows `:androidApp:testDebugUnitTest` present in the `android` job's command.
- Files: `.github/workflows/ci.yml`
