# Validation Plan: capture-whitespace-normalize

**Date**: 2026-08-10

## Happy Path Scenario
Given a user shares a text selection from a browser containing internal whitespace runs, non-breaking spaces, and excess blank lines into SteleKit's share sheet, when `CaptureActivity.buildShareText()` resolves and normalizes that payload, then the text written to the journal block has collapsed spaces/tabs, regular spaces in place of NBSP, and blank-line runs capped at one, while a single intentional line break and the user's own manually-typed text remain untouched.

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| AC1: runs of 2+ regular spaces/tabs collapse to a single space | `CaptureShareTextTest.kt` | `internal runs of spaces and tabs collapse to a single space` (Task 1.1.2a) | Unit | Happy path |
| AC1 (combined, realistic): space runs + tab inside a full browser payload | `CaptureShareTextTest.kt` | `combined realistic browser share payload is normalized` (Task 1.1.2d) | Unit | Combined/integration-of-rules |
| AC2: NBSP (U+00A0) normalized to regular space, then collapsed if repeated | `CaptureShareTextTest.kt` | `non-breaking spaces normalize to regular spaces and collapse` (Task 1.1.2b) | Unit | Happy path |
| AC2 (combined): NBSP inside full browser payload | `CaptureShareTextTest.kt` | `combined realistic browser share payload is normalized` (Task 1.1.2d) | Unit | Combined/integration-of-rules |
| AC3: 3+ consecutive newlines collapse to at most one blank line (2 `\n`) | `CaptureShareTextTest.kt` | `3 or more consecutive newlines collapse to one blank line` (Task 1.1.2c) | Unit | Happy path |
| AC3 (CRLF ordering — line-ending normalization must precede newline collapse) | `CaptureShareTextTest.kt` | `CRLF line endings collapse correctly (line-ending normalization precedes newline collapse)` (Task 1.1.2c) | Unit | Edge case / operation-order regression |
| AC3 (whitespace-only blank line — pre-mortem P1 regression, closes the requirements doc's own motivating example) | `CaptureShareTextTest.kt` | `whitespace-only blank line between newlines collapses (pre-mortem P1 regression)` (Task 1.1.2c) | Unit | Edge case / pre-mortem-driven regression |
| AC3 (combined): CRLF blank-line run inside full browser payload | `CaptureShareTextTest.kt` | `combined realistic browser share payload is normalized` (Task 1.1.2d) | Unit | Combined/integration-of-rules |
| AC4: a single intentional line break is preserved unchanged | `CaptureShareTextTest.kt` | `single intentional line break is preserved` (Task 1.1.2a) | Unit | Happy path (negative case — proves no over-collapsing) |
| AC5: all 12 existing source-priority/fallback cases continue to pass unmodified | `CaptureShareTextTest.kt` | existing 12 `@Test` methods (unchanged, e.g. `` `url only in EXTRA_TEXT, no clipData, no subject` ``) | Unit | Regression |
| AC6: manually typed text is not subject to share-intent normalization | `CaptureViewModelTest.kt` | `updateText does not normalize manually typed whitespace` (Task 1.1.2e) | Unit | Negative case / isolation boundary |
| AC7: new tests cover internal whitespace, NBSP, excess-blank-line, and one combined realistic payload | `CaptureShareTextTest.kt` | Tasks 1.1.2a–1.1.2d (6 new test methods total) | Unit | Coverage completeness (satisfied by the AC1–AC4 rows above) |

**Gaps noted, not closed by this plan** (explicitly accepted in plan.md's Risk Control section, not test-design oversights):
- No test exercises code-fence / leading-indentation corruption from blanket space-run collapsing — plan.md accepts this as a low-probability risk given the capture UI's 3–8 line quick-note scope, with no code-fence detection added.
- No `CaptureViewModel.save()` / `performSave()` test exists or is added — Task 1.1.2e is deliberately scoped to `updateText()` only, since exercising `save()` requires a `GraphManager`/`SteleKitApplication` fixture that plan.md explicitly puts out of scope for this AC6 regression test.

(The "blank line with a stray space between newlines" case, originally an accepted gap, was reclassified by pre-mortem review as P1 — it is verbatim the requirements doc's own motivating example — and is now closed by plan.md's operation-order step 3 plus the new AC3 test above, not merely accepted.)

All 7 acceptance criteria have direct test coverage: 7/7.

## UX Acceptance Tests
N/A — pure text-normalization bugfix, no new user-facing surface, no `design/ux.md`.

## Test Stack
- **Unit**: JUnit 4 (`junit:junit:4.13.2`) with `org.junit.Assert.assertEquals`, per existing `CaptureShareTextTest.kt` convention. The new `CaptureViewModelTest.kt` additionally uses Robolectric (`org.robolectric:robolectric:4.13`, `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [29])`) with `androidx.test.core.app.ApplicationProvider` to construct the `AndroidViewModel`-derived `CaptureViewModel` — all already `testImplementation` dependencies in `androidApp/build.gradle.kts:115-119`, no new dependency required. Both test classes live in `androidApp/src/test/kotlin/dev/stapler/stelekit/`.
- **Integration**: N/A — `normalizeShareWhitespace()` is a pure string function reached through `buildShareText()`'s existing public test seam (`build(...)` helper); no cross-component or DB integration is introduced.
- **E2E / UX**: N/A — no new user-facing surface.

## Coverage Targets and How to Measure

| Module | Target | How to measure |
|--------|--------|-----------------|
| `androidApp` (`CaptureActivity.buildShareText`/`normalizeShareWhitespace`, `CaptureViewModel.updateText`) | 100% line coverage of the new `normalizeShareWhitespace` function specifically | `./gradlew :androidApp:testDebugUnitTest --tests "dev.stapler.stelekit.CaptureShareTextTest" --tests "dev.stapler.stelekit.CaptureViewModelTest"` (Task 1.1.3a) locally; enforced going forward via CI once Task 1.1.3b wires `:androidApp:testDebugUnitTest` into `.github/workflows/ci.yml`'s `android` job (currently only `:androidApp:assembleDebug` runs, which never compiles or executes `androidApp/src/test`) |

This is a small, localized 5-line regex pipeline behind one existing call site — the realistic target is 100% line/branch coverage of `normalizeShareWhitespace()` itself (achieved by the 6 new tests' combination of individual-rule and combined-payload cases), not a repo-wide 80% coverage bar. No JaCoCo/coverage-report task change is proposed; the existing 18+1 explicit assertions are the coverage mechanism for this change.

## Migration Plan
N/A — no schema or data changes; this is a pure in-memory string transform in an existing function (per plan.md's own "Migration Plan: N/A" section).
