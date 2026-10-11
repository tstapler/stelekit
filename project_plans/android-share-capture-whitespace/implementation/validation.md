# Validation Plan: android-share-capture-whitespace

**Date**: 2026-08-10

## Happy Path Scenario

Given a share payload from a browser containing whitespace artifacts (double-space runs,
non-breaking spaces, whitespace-only blank lines, CRLF endings), when the user shares it into
SteleKit via the Android share sheet and completes capture, then `buildShareText()` returns text
with those artifacts normalized (single spaces, single blank lines, LF-only) while text the same
user types manually into the capture field is saved byte-for-byte unchanged.

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| AC1: space/tab run collapses to single space | CaptureShareTextTest.kt | `internal space run collapses to single space` | Unit | Happy path — `"hello    world"` → `"hello world"` |
| AC1: tab/space mixed run collapses | CaptureShareTextTest.kt | `tab and space mixed run collapses` | Unit | Edge — `"a\t \tb"` → `"a b"`, proves char class `[ \t]{2,}` not just literal spaces |
| AC1: leading indentation collapsed per scope decision | CaptureShareTextTest.kt | `leading indentation is collapsed per AC1 as written` | Unit | Edge — `"  indented line"` → `" indented line"`, pins the deliberate no-exemption decision in plan.md's Scope Decision section |
| AC1: unicode/emoji boundary not corrupted | CaptureShareTextTest.kt | `emoji adjacent to space run is not corrupted` | Unit | Edge — `"🎉  🎊"` → `"🎉 🎊"`, guards against a naive byte-oriented regex mishandling surrogate pairs |
| AC1: empty-input boundary | CaptureShareTextTest.kt | `empty string returns empty string` | Unit | Edge (gap found — not in plan's task list) — `normalize("")` → `""`; proves no exception/underflow on empty input to any of the 4 chained `.replace()` calls |
| AC1: true no-op on already-clean text | CaptureShareTextTest.kt | `text with no whitespace to normalize is returned unchanged` | Unit | Edge (gap found — not in plan's task list) — `"clean text\nwith single spaces\nand single breaks"` returned identically; proves the transform is a no-op when there's nothing to collapse, not just idempotent on already-collapsed input |
| AC2: single mid-string NBSP normalizes | CaptureShareTextTest.kt | `single mid-string NBSP normalizes to space` | Unit | Happy path — `"a b"` → `"a b"` |
| AC2: repeated NBSP collapses | CaptureShareTextTest.kt | `repeated NBSP collapses to single space` | Unit | Edge — `"hello  world"` → `"hello world"` |
| AC2: mixed space/NBSP run collapses (ordering trap) | CaptureShareTextTest.kt | `mixed space and NBSP run collapses` | Unit | Edge — `"a   b"` style; catches a reordering regression (NBSP-normalize must run before space-collapse) |
| AC3: 3+ newlines collapse to one blank line | CaptureShareTextTest.kt | `three newlines collapse to one blank line` | Unit | Happy path — `"para one\n\n\npara two"` → `"para one\n\npara two"` |
| AC3: whitespace-only line collapses like blank line | CaptureShareTextTest.kt | `whitespace-only line between content collapses like a blank line` | Unit | Edge — `"a\n \nb"` → `"a\n\nb"`, the browser-DOM artifact that motivated this fix |
| AC3: CRLF endings unified and collapsed | CaptureShareTextTest.kt | `crlf line endings are unified and collapsed` | Unit | Edge — `"a\r\n\r\n\r\nb"` → `"a\n\nb"` |
| AC3: idempotency on already-single blank line | CaptureShareTextTest.kt | `legitimate single blank line is left unchanged` | Unit | Edge — `"a\n\nb"` stays `"a\n\nb"`; proves `BLANK_LINE_RUN`'s ≥2-`\n` requirement doesn't over-match an already-correct blank line |
| AC3: markdown bullet after excess blank lines not corrupted | CaptureShareTextTest.kt | `markdown bullet after excess blank lines is not corrupted` | Unit | Edge — `"para one\n\n\n- bullet"` → `"para one\n\n- bullet"`, real-world regression case from research/features.md §2 |
| AC4: single line break preserved unchanged | CaptureShareTextTest.kt | `single line break between two lines is preserved` | Unit | Happy path — `"line one\nline two"` unchanged |
| AC4: single CRLF break normalizes to bare LF (not collapsed) | CaptureShareTextTest.kt | `single crlf break between two lines normalizes to a single bare newline` | Unit | Edge — `"line one\r\nline two"` → `"line one\nline two"`; pins the CRLF-to-bare-`\n` output convention for the single-break case |
| AC5: 12 pre-existing source-priority/fallback tests pass unmodified | CaptureShareTextTest.kt | All 12 pre-existing methods (`url only in EXTRA_TEXT...`, `url in clipData preferred...`, `empty clipData does not eat...`, `blank clipData does not eat...`, `subject and url are combined...`, `subject and clipData url combined`, `subject only when no text fields`, `all null returns empty string`, `subject equals body text — not duplicated`, `non-ACTION_SEND action returns empty regardless of extras`, `EXTRA_TEXT used as fallback when clipData is null`, `subject falls back when clipData and EXTRA_TEXT are both blank`) | Unit (regression suite) | Non-regression — none of the 12 winning outputs contain multi-space/multi-newline content (confirmed by research/pitfalls.md §4), so normalization is a byte-for-byte no-op on all of them; verified by running the full class, not by re-deriving new assertions |
| AC6: manually-typed text bypasses normalization | CaptureViewModelTest.kt | `updateText does not normalize manually typed whitespace` | Unit (Robolectric) | Happy + edge combined in one structural proof — `viewModel.updateText("raw   text here")` (a 3-space run + NBSP, both of which *would* change if routed through `normalizeShareWhitespace`) leaves `viewModel.captureText.value` byte-for-byte identical; only one test method needed because the scope boundary is structural (`updateText` never calls `normalizeShareWhitespace` at all — see plan.md Pattern Decision B), not a partial/conditional behavior with a separate failure mode to probe |
| AC1/AC7: `buildShareText` wiring to `normalizeShareWhitespace` actually fires (Task 1.1.1b) | CaptureShareTextTest.kt | `buildShareText output is normalized` | Unit | Edge (gap found via pre-mortem.md P2 #1 — not in the plan's original task list) — `build("hello   world", null, null)` returns `"hello world"`; every other new test calls `normalize(...)` directly, so this is the only test that fails if Task 1.1.1b's composition wiring is ever removed or miswired |
| AC7: new test coverage exists for AC1/AC2/AC3 + combined payload | CaptureShareTextTest.kt | `combined browser share payload normalizes all artifacts at once` | Unit | Happy path — realistic fixture combining double-space, NBSP, CRLF, whitespace-only blank line, and single line break in one payload; `"Example  Page Title\r\n\r\n \r\n\r\nBody   text here.\r\nSecond line."` → `"Example Page Title\n\nBody text here.\nSecond line."`. Note: AC7 as a whole is satisfied by the full set of AC1/AC2/AC3 rows above (that coverage *is* what AC7 requires) — this row is the one genuinely new artifact AC7 adds beyond those: the single combined-payload case. |
| AC8: `android` CI job compiles and runs `androidApp/src/test` | .github/workflows/ci.yml | (config change, Task 3.1.1a — no test method; adds `:androidApp:testDebugUnitTest` to the job's Gradle invocation) | CI config | N/A — wiring change, verified by the integration test below actually running under it |
| AC8: full local run proves CI wiring + tests work together | N/A (verification-only, Task 4.1.1a) | `./gradlew :kmp:testDebugUnitTest :androidApp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache` | Integration | Happy path — exits 0; JUnit report shows all 12 original + new `CaptureShareTextTest` methods and the new `CaptureViewModelTest` method passing. This is the AC8 acceptance criterion's actual Given-When-Then (a deliberately-broken assertion in either file failing the job is the CI-side proof, not separately re-tested locally) |

## UX Acceptance Tests
N/A — no user-facing surface (invisible text normalization on the share-capture ingestion path; no new UI, no new user-visible states beyond "the note reads cleaner," which is exercised by the unit tests above, not a separate UX flow).

## Test Stack
- **Unit**: JUnit 4 (`junit:junit:4.13.2`), plain JVM for `CaptureShareTextTest`; JUnit 4 + Robolectric 4.13 for `CaptureViewModelTest`
- **Integration**: Gradle task `:androidApp:testDebugUnitTest` run locally and in CI (Task 4.1.1a / AC8)
- **E2E / UX**: N/A

## Coverage Targets and How to Measure

No Jacoco plugin exists in this repo — checked `androidApp/build.gradle.kts`, root `build.gradle.kts`,
and `settings.gradle.kts` for a `jacoco` reference (`grep -ril jacoco`); none found. There is no
`./gradlew jacocoTestReport` task available, so a numeric line-coverage percentage is not a claim
this plan can make. Coverage target instead: **all 8 acceptance criteria have ≥1 passing test** (per
the mapping table above — AC8's "test" is CI wiring + the local integration run), and
`:androidApp:testDebugUnitTest` exits 0. Both are directly checkable via the exact command in
Task 4.1.1a's row above.

- All public service methods: happy path + error paths covered — `normalizeShareWhitespace` and
  `buildShareText` have happy-path coverage for every AC plus edge/boundary coverage (empty string,
  no-op, ordering traps, unicode); there are no traditional error paths since this is an
  exception-free deterministic string transform (per requirements.md's Non-functional/constraints
  section).
- All external integrations: N/A (no external integrations in this feature — no DB, no network, no
  file I/O in `normalizeShareWhitespace`/`buildShareText`).
- UX acceptance criteria: N/A
