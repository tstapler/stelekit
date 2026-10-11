# Implementation Plan: android-share-capture-whitespace

**Feature**: Normalize internal whitespace (space/tab runs, NBSP, excess blank lines) in Android
share-capture payloads before they reach the journal block, without touching manually-typed text.
**Date**: 2026-08-10
**Status**: Ready for implementation
**ADRs**: None — stdlib-only regex transform, single file, no new dependency, no non-standard
technology choice. Formal ADR would be overhead for a function-level change (see Pattern Decisions).

---

## Domain Glossary
*(Ubiquitous language — every domain term that appears as a type, method, or variable name.)*

| Term | Definition | Notes |
|------|-----------|-------|
| `buildShareText` | Existing `internal` companion-object function in `CaptureActivity` that resolves clipData/EXTRA_TEXT/EXTRA_SUBJECT priority into one string. | Unchanged signature; its `return` now pipes through `normalizeShareWhitespace`. |
| `normalizeShareWhitespace` | New `internal` companion-object function: `String -> String`, applies the 4-step whitespace transform (CRLF unify → NBSP normalize → space/tab collapse → blank-line collapse). | New symbol, `CaptureActivity.kt`. |
| `SPACE_TAB_RUN` | Private compiled `Regex("[ \t]{2,}")` constant matching 2+ consecutive regular spaces/tabs (AC1). | Compiled once at class-load, not per call. |
| `BLANK_LINE_RUN` | Private compiled `Regex("\n[ \t]*(?:\n[ \t]*)+")` constant matching 2+ consecutive newlines, optionally separated only by whitespace-only lines (AC3). | Requires ≥2 literal `\n` to match, so a lone `\n` (AC4) never matches. |
| NBSP | Non-breaking space, Unicode U+00A0 (`'\u00A0'` in Kotlin source). Normalized to regular space U+0020 before collapsing (AC2). | Distinct from Kotlin's `Char.isWhitespace()`, which already treats NBSP as whitespace for `.trim()` purposes but not for mid-string regex matching. |
| `updateText` | Existing `CaptureViewModel` method bound to the capture `OutlinedTextField`'s `onValueChange`. Writes raw text to `_captureText` with no transform. | Scope boundary for AC6 — must never call `normalizeShareWhitespace`. |
| `initializeText` | Existing `CaptureViewModel` method, called once with `shareContent.text` (the string that already came out of `buildShareText`) from `onCreate`/`onNewIntent`. | Receives already-normalized text; itself does no normalization. |
| `CaptureShareTextTest` | Existing JVM (non-Robolectric) unit test class exercising `buildShareText`/`normalizeShareWhitespace` directly. 12 existing cases + new cases added by this plan. | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`. |
| `CaptureViewModelTest` | New Robolectric unit test class proving AC6's scope boundary structurally (manual text via `updateText` is never normalized). | New file, same package. |
| `android` CI job | The GitHub Actions job in `.github/workflows/ci.yml` that currently runs `:kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug` and, after this plan, also runs `:androidApp:testDebugUnitTest`. | AC8 target. |

---

## Pattern Decisions

**Step 0.5 creative pass — integration-point alternatives considered:**

| # | Approach | Strength (1 sentence) | Weakness (1 sentence) |
|---|----------|------------------------|------------------------|
| A | Inline the regex transform directly into `buildShareText`'s existing `when` block | Fewest new symbols — everything in one function body | Entangles source-priority logic with whitespace regex in one function, making it harder to tell which concern regressed when a test fails |
| B (chosen) | New pure function `normalizeShareWhitespace`, called from `buildShareText`'s `return` statement only | Independently unit-testable, and structurally guarantees manual text can never reach it since only `buildShareText` calls it | One additional internal symbol in the companion object (minor, and it's a natural unit of testing) |
| C | Wrap the call in `parseShareIntent()` (or hook `CaptureViewModel.initializeText`) instead of inside `buildShareText` | Leaves `buildShareText`'s existing 12-test-covered body byte-for-byte unchanged | Breaks the established "`CaptureShareTextTest` calls `buildShareText()` directly, no Robolectric" pattern for AC7's combined-payload test, since `buildShareText()` would return unnormalized text under this option |

Chosen: **B**. Rejected A and C for the reasons above (matches `research/architecture.md`'s Option B
recommendation, independently re-derived here).

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Whitespace transform itself | None — plain function (sequential `.replace()` calls) | N/A | Strategy pattern (pluggable `Normalizer` interface) | Only one normalization behavior exists and none is anticipated; an interface/strategy here is enterprise ceremony with no second implementation to justify it |
| Integration point (`buildShareText` → `normalizeShareWhitespace`) | Function composition (approach B above) | N/A (plain Kotlin) | Approach A (inline) | Entangles two concerns in one function body — see table above |
| | | | Approach C (wrap at call site) | Breaks AC7's test-authoring pattern and weakens AC5's "no regression" guarantee to discipline rather than structure — see table above |
| CI wiring | None — extend the existing single Gradle invocation | N/A | New, separate CI job/step just for `:androidApp:testDebugUnitTest` | The `android` job already batches multiple Gradle test tasks into one invocation deliberately, to avoid paying for Gradle configuration/daemon startup twice (see the job's own inline comment at `.github/workflows/ci.yml`) — splitting it out would regress that optimization for no benefit |

---

## Scope Decision: AC1 (global whitespace collapse) vs. leading-indentation significance

`research/features.md` §3 flagged a real tension: AC1's literal wording ("2+ consecutive regular
spaces or tabs collapse to a single space") does not exempt *leading* indentation, but
`MarkdownPreprocessor.calculateLevel()` / `OutlinerPipeline.calculateLevel()` elsewhere in this
codebase treat 2-space/4-space leading indentation as structurally significant (nested outline
levels).

**Decision: implement AC1 exactly as written — global collapse, including leading whitespace on
every line of the captured text. No line-position-aware exemption.**

Rationale:
1. AC1 is a locked, numbered acceptance criterion from the backlog item, not a criterion this plan
   is authorized to silently narrow. Any deviation must be a deliberate, documented trade-off, not
   an implementation detail the planner picks unilaterally.
2. Blast radius is low by construction, per `research/features.md` §3's own finding:
   `CaptureViewModel.performSave()` creates exactly **one flat `Block`** per capture — the captured
   text is never run through `OutlinerPipeline`/`MarkdownPreprocessor` at capture time, so there is
   no outline-level parsing for a collapsed leading-indent run to corrupt *at capture time*.
3. The feature's own problem statement is specifically about browser/HTML DOM whitespace artifacts
   (prose captured via share sheet) — not code-snippet or outline-export paste via share sheet,
   which is a different, out-of-scope usage this ticket does not target.
4. A line-classification exemption (Option 1 or Option 3 from `features.md`) adds real complexity —
   "does this line look like a markdown list item / start with 4+ spaces" — that itself needs new
   test coverage and can misfire (e.g. a shared line that legitimately starts with two spaces of
   prose after a stripped bullet character). That complexity is not asked for by any acceptance
   criterion, so building it would be scope creep in the other direction.
5. Residual risk (a later re-parse of the saved block's content through `MarkdownPreprocessor`
   elsewhere in the app) is real but unverified-as-in-scope by research, and — critically — is not
   a *new* risk category introduced by this feature: manually-typed capture text with real leading
   indentation already bypasses no such protection today (AC6 explicitly keeps manual text outside
   normalization, meaning manual text's indentation was never protected from downstream re-parsing
   either). This feature does not make that pre-existing situation worse.

This is recorded here as the authoritative decision; no further exemption logic is scheduled in
this plan's tasks below.

**User-trust mitigation (not a code change, an existing structural fact worth naming):**
normalized text never reaches the journal silently — `buildShareText`'s output flows through
`CaptureViewModel.initializeText()` into the same `OutlinedTextField` the user already reviews and
can edit before tapping Save or Dismiss (`CaptureActivity.kt:287-296`). A user whose shared text
had its whitespace collapsed sees the normalized result in an editable field before it's ever
written to their journal, and can correct it there if the collapse (e.g. of a code snippet's
indentation) wasn't wanted — this is the existing safety net for AC1's global-collapse decision,
not a new one introduced by this feature.

---

## Migration Plan
N/A — no schema or data changes.

## Observability Plan
Not applicable — no new logs/metrics/alerts needed for this scope. `normalizeShareWhitespace` is a
pure, deterministic string transform with no failure mode to alert on (no exceptions thrown, no I/O).

## Risk Control

| Risk | Mitigation |
|------|-----------|
| Regex change accidentally collapses a legitimate single blank line (AC4) | `BLANK_LINE_RUN` requires ≥2 literal `\n` to match at all (see Domain Glossary) — a lone `\n` structurally cannot match. Explicit AC4 test case added (Task 1.2.1d). |
| `\s{2,}` used instead of `[ \t]{2,}`, silently merging lines that should stay separate | `research/pitfalls.md` confirms this trap; `SPACE_TAB_RUN` is defined as `[ \t]{2,}` explicitly (character class, not `\s`) — reviewed at Task 1.1.1a. |
| NBSP collapsing order swapped (space-collapse before NBSP-normalize) silently breaks AC2 for mixed "space NBSP space" runs | Order is fixed and documented in the function's own KDoc: unify line endings → NBSP normalize → space/tab collapse → blank-line collapse. Task 1.2.1b's test uses a mixed run to catch a reordering regression. |
| NBSP test fixture placed at string edges, making a no-op `.trim()`-only implementation pass falsely | `research/pitfalls.md` confirms Kotlin's `.trim()` already strips edge NBSP. Task 1.2.1b places NBSP fixtures mid-string only. |
| Existing 12 `CaptureShareTextTest` cases regress silently | Task 1.2.2 runs the full test class and confirms all 12 pass with zero modification to their assertions. |
| AC8 lands without the underlying test files existing yet, or vice versa, letting "no regression" be enforced only by developer memory in the gap | Phase ordering below sequences CI wiring (Phase 3) *after* the test files exist (Phases 1–2) but *before* Phase 4's final verification run — so the final verification run is the first CI-equivalent proof that wiring + tests both work together. |
| `CaptureViewModelTest` accidentally exercises `save()` (which requires a real `SteleKitApplication`/`GraphManager` and would crash under a plain Robolectric `Application`) | `androidApp/src/main/AndroidManifest.xml:24` DOES set `android:name="dev.stapler.stelekit.SteleKitApplication"`, so `ApplicationProvider.getApplicationContext<Application>()` actually instantiates and runs `onCreate()` on the real `SteleKitApplication`, not a plain `Application` — a claim in an earlier draft of this table was factually wrong and has been corrected here. This is nonetheless safe today because `SteleKitApplication.onCreate()` (`SteleKitApplication.kt:58-104`) wraps its heavy initialization (SQLite driver, GraphManager, ARCore, ONNX, BLE, etc.) in one outer `catch (e: Throwable)` that swallows failures — not the absence of `android:name`. Verified empirically: `./gradlew :androidApp:testDebugUnitTest` run locally (`ANDROID_HOME` set) passed 86/86 tests, exit 0, including `AudiobookAutoSettingsTest`, which already calls `ApplicationProvider.getApplicationContext()` under this same manifest today. Task 2.1.1a additionally pins `@Config(application = Application::class)` on `CaptureViewModelTest` so it gets the lightweight plain-`Application` behavior structurally, rather than relying on this try/catch as an accidental safety net. If that try/catch is ever narrowed or removed, tests relying on it (not `CaptureViewModelTest`, which overrides via `@Config`) would be exposed — noted here so a future maintainer isn't misled about which invariant is load-bearing. |
| CRLF-to-bare-`\n` output convention (Decision, applies to Task 1.1.1a) | Deliberate, stated choice: the pipeline normalizes ALL line endings to bare `\n` in its output — not just within blank-line runs, but for a single, non-blank-line-run `\r\n` between two content lines too (`unifiedLineEndings = text.replace("\r\n", "\n")` in Task 1.1.1a runs globally, before blank-line collapsing). This matches Markdown/journal content's conventional LF-only style. Task 1.2.1c adds a test case for a single `\r\n` between two content lines normalizing to a single bare `\n` (the AC4-equivalent case for CRLF; AC4/Task 1.2.1d itself only tests bare `\n`). |
| Task 3.1.1a's CI wiring makes the entire `androidApp/src/test` source set merge-blocking, not just this plan's files | This wiring change makes all 10 files in `androidApp/src/test` (2 new/touched by this plan + 8 pre-existing, never CI-run before) merge-blocking going forward. The 8 pre-existing files were baselined locally during planning/review: `./gradlew :androidApp:testDebugUnitTest` passed 86/86 tests, exit 0 (same run cited above), so this wiring change introduces no currently-known regression risk from those files. **However, the local baseline alone is not sufficient proof** (`pre-mortem.md` P1 #2) — Robolectric tests are commonly locale/timezone-sensitive, and none of these 8 files has ever executed inside the actual GitHub Actions runner environment. This PR's own `android` CI job run (triggered when the PR opens) is the authoritative check, not the local run — see Task 4.1.1b below. If that CI run surfaces a failure in one of the 8 pre-existing files unrelated to this feature, treat it as a pre-existing environment-specific issue to triage separately (do not silently work around it inside this PR's scope), and do not merge Task 3.1.1a's CI-wiring change until the `android` job is green on the real PR. |
| Scope Decision point 5's "not a new risk" claim rests on an unverified baseline (whether saved capture blocks with real leading indentation are ever later re-parsed through `MarkdownPreprocessor`/`OutlinerPipeline` elsewhere in the app) | Carried forward as a named residual risk, out of scope for this ticket — not investigated or resolved here. |

## Unresolved Questions
None. The AC1-vs-indentation tension is resolved above (Scope Decision). All 8 acceptance criteria
have concrete Given-When-Then examples below (Step 4 requirement) — none required rewriting for
ambiguity.

## Dependency Visualization

```
Phase 1: Core normalization logic
  Epic 1.1 (transform)          Epic 1.2 (tests)
  Task 1.1.1a ──> Task 1.1.1b        │
       │                             │
       └────────────> Task 1.2.1a ──┤
                       Task 1.2.1b ──┤ (all depend on 1.1.1a/b existing)
                       Task 1.2.1c ──┤
                       Task 1.2.1d ──┤
                       Task 1.2.1e ──┤
                                     └──> Task 1.2.2 (run full suite, confirm 12 old + new pass)

Phase 2: Scope boundary (AC6) — independent of Phase 1, can run in parallel
  Task 2.1.1a ──> Task 2.1.1b

Phase 3: CI wiring (AC8) — depends on Phase 1 + Phase 2 test files existing
  (Task 1.2.2, Task 2.1.1b) ──> Task 3.1.1a

Phase 4: Final verification — depends on everything above
  Task 3.1.1a ──> Task 4.1.1a
```

---

## Phase 1: Core normalization logic

### Epic 1.1: Whitespace normalization transform
**Goal**: Add the `normalizeShareWhitespace` function and wire it into `buildShareText`'s return
path, per the Pattern Decisions above.

#### Story 1.1.1: `normalizeShareWhitespace` implements AC1–AC4
**As a** SteleKit user sharing text from a browser, **I want** whitespace artifacts collapsed
before the text lands in my journal, **so that** my captured note reads as clean prose instead of
carrying DOM-rendering whitespace noise.

**Acceptance Criteria**:
- AC1 — 2+ consecutive regular spaces/tabs collapse to one space.
  - *Given* the share payload body `"hello    world"` (4 spaces), *When*
    `CaptureActivity.normalizeShareWhitespace("hello    world")` is called, *Then* it returns
    `"hello world"`.
- AC2 — NBSP normalizes to a regular space, then collapses per AC1 if repeated.
  - *Given* the string `"hello\u00A0\u00A0world"` (two NBSPs, mid-string), *When*
    `normalizeShareWhitespace` is called, *Then* it returns `"hello world"`.
- AC3 — 3+ consecutive newlines, or newlines separated only by whitespace-only lines, collapse to
  at most one blank line.
  - *Given* the string `"para one\n\n\npara two"` (3 newlines), *When* `normalizeShareWhitespace`
    is called, *Then* it returns `"para one\n\npara two"` (2 newlines = one blank line).
- AC4 — a single `\n` between two lines is preserved unchanged.
  - *Given* the string `"line one\nline two"` (single newline), *When* `normalizeShareWhitespace`
    is called, *Then* it returns `"line one\nline two"` unchanged.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 1.1.1a: Add `SPACE_TAB_RUN`/`BLANK_LINE_RUN` regex constants and `normalizeShareWhitespace` function (~5 min)
- In `CaptureActivity`'s companion object (near the existing `PREFS_NAME`/`KEY_TILE_PROMPTED`
  constants at `CaptureActivity.kt:175-176`), add:
  ```kotlin
  private val SPACE_TAB_RUN = Regex("[ \t]{2,}")
  private val BLANK_LINE_RUN = Regex("\n[ \t]*(?:\n[ \t]*)+")
  ```
- Add the function (KDoc documents the fixed 4-step order — see Risk Control):
  ```kotlin
  /**
   * Normalizes whitespace artifacts common in browser/HTML-aware share payloads.
   * Order is fixed: unify line endings -> normalize NBSP -> collapse space/tab runs ->
   * collapse blank-line runs. A single `\n` between two content lines is left untouched.
   */
  internal fun normalizeShareWhitespace(text: String): String {
      val unifiedLineEndings = text.replace("\r\n", "\n").replace('\r', '\n')
      val nbspNormalized = unifiedLineEndings.replace('\u00A0', ' ')
      val spacesCollapsed = nbspNormalized.replace(SPACE_TAB_RUN, " ")
      return spacesCollapsed.replace(BLANK_LINE_RUN, "\n\n")
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 1.1.1b: Pipe `buildShareText`'s return through `normalizeShareWhitespace` (~2 min)
- Change `buildShareText`'s `return when { ... }` (`CaptureActivity.kt:196-200`) to
  `return normalizeShareWhitespace(when { ... })`, wrapping the existing `when` expression exactly
  as-is with no changes to its branches.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

---

### Epic 1.2: Test coverage for normalization and non-regression
**Goal**: Extend `CaptureShareTextTest` with new cases per AC1/AC2/AC3/AC4/AC7, and confirm the 12
pre-existing cases still pass unmodified (AC5).

#### Story 1.2.1: New `CaptureShareTextTest` cases cover AC1, AC2, AC3, AC7
**As a** maintainer, **I want** executable tests for every whitespace-normalization behavior,
**so that** a future change that breaks normalization is caught by CI, not by a user bug report.

**Acceptance Criteria**:
- AC1 (tabs mixed with spaces) — see `research/features.md` §2 edge case.
  - *Given* the string `"a\t \tb"` (tab-space-tab), *When* `normalizeShareWhitespace` runs,
    *Then* it returns `"a b"`.
- AC1 (leading indentation collapsed too — proves the Scope Decision above is implemented as
  written, not silently exempted).
  - *Given* the string `"  indented line"` (2 leading spaces), *When* `normalizeShareWhitespace`
    runs, *Then* it returns `" indented line"` (1 space) — documenting the deliberate AC1-as-written
    behavior in an executable test, not just prose.
- AC2 (NBSP alone, not repeated, still normalizes per AC2's own wording).
  - *Given* the string `"a\u00A0b"` (single NBSP, mid-string), *When* `normalizeShareWhitespace`
    runs, *Then* it returns `"a b"`.
- AC3 (whitespace-only line between two blank-ish lines).
  - *Given* the string `"a\n \nb"` (a line containing a single space between two content lines),
    *When* `normalizeShareWhitespace` runs, *Then* it returns `"a\n\nb"`.
- AC3 (CRLF line endings).
  - *Given* the string `"a\r\n\r\n\r\nb"` (CRLF, 3 line-break groups), *When*
    `normalizeShareWhitespace` runs, *Then* it returns `"a\n\nb"` (CRLF normalized to bare `\n`
    per `research/pitfalls.md`'s recommended convention).
- AC7 (combined realistic browser-share payload).
  - *Given* the string
    `"Example  Page\u00A0Title\r\n\r\n \r\n\r\nBody   text\u00A0here.\r\nSecond line."`
    (mixed double-space, NBSP, CRLF, whitespace-only blank line, single line break), *When*
    `normalizeShareWhitespace` runs, *Then* it returns
    `"Example Page Title\n\nBody text here.\nSecond line."`.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.1a: Add AC1 test cases (space/tab collapsing, mixed tabs, leading indentation, emoji adjacency) (~5 min)
- Add a private `normalize(text: String) = CaptureActivity.normalizeShareWhitespace(text)`
  shorthand alongside the existing `build(...)` shorthand at the top of the test class.
- Add test methods: `` `internal space run collapses to single space` ``,
  `` `tab and space mixed run collapses` ``,
  `` `leading indentation is collapsed per AC1 as written` `` (documents the Scope Decision),
  `` `emoji adjacent to space run is not corrupted` `` (uses `"🎉  🎊"` -> `"🎉 🎊"`, per
  `research/features.md` §2).
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.1b: Add AC2 NBSP test cases (~3 min)
- Add test methods: `` `single mid-string NBSP normalizes to space` ``,
  `` `repeated NBSP collapses to single space` ``, `` `mixed space and NBSP run collapses` ``
  (`"a \u00A0 b"` style, per `research/pitfalls.md`'s ordering trap).
- All NBSP fixtures placed mid-string, never at string edges (per Risk Control above).
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.1c: Add AC3 blank-line-collapsing test cases (whitespace-only lines, CRLF, markdown bullet safety) (~5 min)
- Add test methods: `` `three newlines collapse to one blank line` ``,
  `` `whitespace-only line between content collapses like a blank line` ``,
  `` `crlf line endings are unified and collapsed` ``,
  `` `legitimate single blank line is left unchanged` `` (AC4-adjacent negative case: `"a\n\nb"`
  stays `"a\n\nb"`),
  `` `markdown bullet after excess blank lines is not corrupted` ``
  (`"para one\n\n\n- bullet"` -> `"para one\n\n- bullet"`, per `research/features.md` §2),
  `` `single crlf break between two lines normalizes to a single bare newline` `` — the
  CRLF-equivalent of AC4/Task 1.2.1d's single-`\n` case, not currently covered elsewhere:
  `normalize("line one\r\nline two")` equals `"line one\nline two"` (single bare `\n`, not `\r\n`),
  pinning down the CRLF-to-bare-`\n` output convention (see Risk Control) with an assertion.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.1d: Add AC4 single-line-break-preserved test case (~2 min)
- Add test method `` `single line break between two lines is preserved` ``:
  `normalize("line one\nline two")` equals `"line one\nline two"`.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.1e0: Add a test proving `buildShareText`'s wiring to `normalizeShareWhitespace` (Task 1.1.1b) (~2 min) (`pre-mortem.md` P2 #1)
- Every other new test (Tasks 1.2.1a–1.2.1d) calls `normalize(...)` directly, and all 12
  pre-existing `build(...)`-routed tests use whitespace-clean fixtures — nothing proves Task
  1.1.1b's composition (`return normalizeShareWhitespace(when { ... })`) actually fires for real
  `buildShareText` output.
- Add test method `` `buildShareText output is normalized` ``:
  `build("hello   world", null, null)` equals `"hello world"` — a whitespace-run fixture routed
  through `build(...)`, not `normalize(...)`, so it fails if Task 1.1.1b's wiring is ever removed
  or miswired even though `normalizeShareWhitespace` itself still works in isolation.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.1e: Add AC7 combined realistic browser-share payload test case (~3 min)
- Add test method `` `combined browser share payload normalizes all artifacts at once` `` using
  the fixture and expected output from Story 1.2.1's AC7 Given-When-Then above, called through
  `normalize(...)` directly — matching the pattern already established in Tasks 1.2.1a-1.2.1d, NOT
  `build(clip, extra, subject)`. `buildShareText` re-joins title/body with its own hardcoded `"\n"`
  (`CaptureActivity.kt:197`), which drops the CRLF/blank-line separator the fixture depends on and
  would produce a different (wrong) expected output than the one documented in Story 1.2.1's
  Given-When-Then. Source-priority-logic end-to-end coverage already exists via the pre-existing 12
  `CaptureShareTextTest` cases plus AC4's two `\n`-in-output cases, so nothing is lost by testing
  AC7 via `normalize(...)` directly.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

#### Story 1.2.2: Confirm the 12 pre-existing cases pass unmodified (AC5)
**As a** maintainer, **I want** proof that normalization doesn't change source-priority behavior,
**so that** AC5 is verified, not assumed.

**Acceptance Criteria**:
- AC5 — all 12 existing cases continue to pass unmodified.
  - *Given* `CaptureShareTextTest.kt` with its original 12 test methods untouched (per
    `research/pitfalls.md` §4's confirmation that none of their winning outputs contain
    multi-space/multi-newline content), *When* `./gradlew :androidApp:testDebugUnitTest` runs,
    *Then* all 12 original test methods plus the new ones from Story 1.2.1 pass with exit code 0.
**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`

##### Task 1.2.2a: Run the full test class and confirm zero modifications needed to the 12 original methods (~3 min)
- Run `./gradlew :androidApp:testDebugUnitTest --tests "dev.stapler.stelekit.CaptureShareTextTest"`.
- Diff the 12 original test method bodies against their state before this plan's changes — confirm
  byte-for-byte unchanged (verification-only task, no code edit expected).
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt` (read-only check)

---

## Phase 2: Scope boundary enforcement (AC6)

### Epic 2.1: Manual text isolation from normalization
**Goal**: Prove structurally, via an executable Robolectric test, that text typed into the capture
field never passes through `normalizeShareWhitespace`.

#### Story 2.1.1: `CaptureViewModelTest` proves `updateText` bypasses normalization
**As a** SteleKit user typing a manual note in the capture field, **I want** my exact keystrokes
preserved, **so that** intentional formatting (e.g. deliberate double spaces) is never silently
rewritten.

**Acceptance Criteria**:
- AC6 — manually-typed text is not subject to normalization, proven by an executable test.
  - *Given* a `CaptureViewModel` constructed with a Robolectric `ApplicationProvider
    .getApplicationContext<Application>()`, *When* `viewModel.updateText("raw   text\u00A0here")`
    is called (a string containing an internal 3-space run and an NBSP that *would* be altered if
    routed through `normalizeShareWhitespace`), *Then* `viewModel.captureText.value` equals
    `"raw   text\u00A0here"` exactly — unnormalized, byte-for-byte.
**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (new)

##### Task 2.1.1a: Create `CaptureViewModelTest.kt` with Robolectric setup mirroring `MediaSessionObserverTest` (~5 min)
- New file `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`, package
  `dev.stapler.stelekit`.
- Mirror the `@RunWith(RobolectricTestRunner::class)` pattern from
  `androidApp/src/test/kotlin/dev/stapler/stelekit/auto/MediaSessionObserverTest.kt:17-18`, but use
  `@Config(sdk = [29], application = Application::class)` — the extra `application = Application::class`
  is required here: `androidApp/src/main/AndroidManifest.xml:24` sets
  `android:name="dev.stapler.stelekit.SteleKitApplication"`, so without this override,
  `ApplicationProvider.getApplicationContext<Application>()` would instantiate and run `onCreate()`
  on the real `SteleKitApplication`, not a plain `Application`. `@Config(application = ...)` lets
  Robolectric override the manifest-declared `<application>` class per test class, which is what
  gets this test the lightweight plain-`Application` behavior it actually wants (see Risk Control
  table for why this is also safe in practice even without the override, via
  `SteleKitApplication.onCreate()`'s catch-all `Throwable` handler — but the `@Config` override is
  the structural fix, not a reliance on that handler).
- Construct the view model under test with
  `CaptureViewModel(ApplicationProvider.getApplicationContext())` in a `@Before` or inline per
  test — safe as long as `save()` is never called (see Risk Control).
- Imports needed: `android.app.Application`, `androidx.test.core.app.ApplicationProvider`,
  `org.junit.Test`, `org.junit.runner.RunWith`, `org.robolectric.RobolectricTestRunner`,
  `org.robolectric.annotation.Config`, `org.junit.Assert.assertEquals`.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 2.1.1b: Add the AC6 boundary test (~3 min)
- Add test method `` `updateText does not normalize manually typed whitespace` `` implementing the
  Given-When-Then from Story 2.1.1 above.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

---

## Phase 3: CI enforcement (AC8)

### Epic 3.1: Wire `androidApp/src/test` into the `android` CI job
**Goal**: Make CI fail if `CaptureShareTextTest`/`CaptureViewModelTest` ever break or get deleted,
closing the gap `research/pitfalls.md` confirmed exists today (`android` job never runs
`:androidApp:testDebugUnitTest`).

#### Story 3.1.1: `android` CI job runs `:androidApp:testDebugUnitTest`
**As a** maintainer, **I want** `androidApp/src/test` to run on every PR, **so that** a future
regression in share-text normalization (or anything else in that source set) fails CI instead of
merging silently.

**Acceptance Criteria**:
- AC8 — the `android` CI job compiles and runs `androidApp/src/test`.
  - *Given* `.github/workflows/ci.yml:89`'s current Gradle invocation
    `./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache`,
    *When* `:androidApp:testDebugUnitTest` is added to that same invocation and a PR is opened,
    *Then* the `android` job's log shows `CaptureShareTextTest` and `CaptureViewModelTest` results
    (pass/fail), and a deliberately-broken assertion in either file fails the job.
**Files**: `.github/workflows/ci.yml`

##### Task 3.1.1a: Add `:androidApp:testDebugUnitTest` to the `android` job's Gradle invocation (~2 min)
- Edit `.github/workflows/ci.yml:89` from
  `run: ./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache`
  to
  `run: ./gradlew :kmp:testDebugUnitTest :androidApp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache`
  (inserted before `:kmp:recordRoborazziDebug` so unit tests fail fast before the slower
  screenshot-recording and APK-assembly steps run).
- No other lines in the job change — the existing `actions/upload-artifact` and JUnit-report-publish
  steps below it (`ci.yml:90-99`) already pick up whatever JUnit XML Gradle produces, no new step
  needed.
- Files: `.github/workflows/ci.yml`

---

## Phase 4: Final verification

### Epic 4.1: End-to-end local proof before handoff
**Goal**: Run the exact command CI will run, locally, as the final gate — matching this repo's
"green first, then done" discipline.

#### Story 4.1.1: Full local run of the updated `android` job command
**As a** maintainer, **I want** the exact CI command run locally before calling this done,
**so that** "done" is backed by real command output, not by reading the diff.

**Acceptance Criteria**:
- All of AC1–AC8 hold simultaneously.
  - *Given* all Phase 1–3 changes committed, *When*
    `./gradlew :kmp:testDebugUnitTest :androidApp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache`
    runs locally, *Then* it exits 0, and the JUnit test report shows 12 + N new
    `CaptureShareTextTest` methods and the new `CaptureViewModelTest` method all passing (N =
    the count of new test methods added in Tasks 1.2.1a–1.2.1e).
**Files**: N/A (verification only — no new files)

##### Task 4.1.1a: Run the full updated CI command locally and confirm exit 0 (~5 min)
- Run the exact command from Story 4.1.1's Given-When-Then.
- Confirm test counts: 12 original + new `CaptureShareTextTest` methods (Tasks 1.2.1a–1.2.1e) +
  1 new `CaptureViewModelTest` method (Task 2.1.1b), all green.
- Files: none (verification only)

##### Task 4.1.1b: Confirm the real GitHub Actions `android` job is green on the PR before merging (~0 min added — this is a wait/observe step, not new work) (`pre-mortem.md` P1 #2)
- The local run in Task 4.1.1a is necessary but not sufficient: none of the 8 pre-existing
  `androidApp/src/test` files (unrelated to this feature, under `auto/`) has ever executed inside
  the actual GitHub Actions runner before, and Robolectric tests are commonly
  locale/timezone-sensitive in ways a local run may not surface.
- Once this PR is opened, wait for the `android` CI job to complete and confirm it is green there
  too — this is the authoritative check, not the local baseline.
- If the real CI run fails in one of the 8 pre-existing files for reasons unrelated to this
  feature's changes, treat it as a pre-existing environment-specific issue: do not silently patch
  around it inside this PR's scope — flag it separately and resolve or explicitly descope before
  this PR's CI-wiring change (Task 3.1.1a) is allowed to merge.
- Files: none (verification only — no new code)
