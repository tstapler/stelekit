# Adversarial Review: capture-whitespace-normalize

**Date**: 2026-08-10
**Verdict**: CLEAN

## Blockers
None.

## Concerns
None.

## Minors
None.

## Re-review notes (scoped to the 4 previously-flagged items)

1. **BLOCKER — CI wiring (Task 1.1.3b): RESOLVED.**
   Plan's quoted "before" line —
   `./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug --no-daemon --build-cache` —
   is byte-for-byte identical to the current `android` job's "Run Android tests and build APK"
   step in `.github/workflows/ci.yml:89`. The proposed "after" line simply inserts
   `:androidApp:testDebugUnitTest` before `:androidApp:assembleDebug`, which is the standard
   AGP-generated task name for running `androidApp`'s debug-variant JVM unit tests (the module
   uses `com.android.application`, confirmed in `androidApp/build.gradle.kts:5`) — a syntactically
   ordinary multi-target Gradle invocation. This would compile and run
   `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt` and the new
   `CaptureViewModelTest.kt`. The existing "Publish test report" step
   (`report_paths: '**/build/test-results/testDebugUnitTest/**/TEST-*.xml'`, ci.yml:102) and the
   `android-test-results` artifact glob (ci.yml:117) already use recursive `**` globs, so no
   further edit is needed to pick up `androidApp`'s results — the plan's claim on this point checks out.

2. **CONCERN — raw NBSP literal in code samples: RESOLVED.**
   `grep -nP '[^\x00-\x7F]' plan.md` finds only em dashes (U+2014, ordinary markdown prose
   punctuation). A targeted `grep -nP '\x{00A0}'` (literal NBSP codepoint) finds zero matches.
   Every NBSP reference in the plan — glossary, pattern-decision table, implementation
   constraints, code block (`.replace(" ", " ")`), and all four AC/test examples — uses the
   explicit ` ` escape, never a raw NBSP byte.

3. **CONCERN — AC6 has no executable test: RESOLVED, and the proposed test is plausible.**
   - Robolectric dependency: `androidApp/build.gradle.kts:117` is exactly
     `testImplementation("org.robolectric:robolectric:4.13")` — the plan's citation is accurate.
   - Pattern fidelity: `AudiobookAutoSettingsTest.kt` uses
     `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [29])` +
     `ApplicationProvider.getApplicationContext()` — the plan's `CaptureViewModelTest.kt` sample
     follows this exactly (same two annotations, same `ApplicationProvider` call, differing only
     in the generic type parameter needed for `AndroidViewModel`'s `Application` constructor arg).
   - API cross-check against the real `CaptureViewModel.kt`: constructor is
     `class CaptureViewModel(app: Application) : AndroidViewModel(app)` (line 27) — matches
     `CaptureViewModel(app)` in the test. `captureText` is a public
     `StateFlow<String> = _captureText.asStateFlow()` (line 30) — matches
     `viewModel.captureText.value` in the test. `updateText(text: String)` (lines 42-44) does
     exactly `_captureText.value = text` with no normalization call — confirms the assertion
     `"hello   world"` in, `"hello   world"` out. The test as written would compile and pass.

4. **CONCERN — blank-line-with-stray-space edge case undocumented: RESOLVED.**
   The Risk Control section (plan.md:50) now has an explicit "Accepted risks" bullet titled
   "Blank line with a stray space between newlines is not collapsed," with a concrete example
   (`"Para one\n \n \nPara two"`), a mechanical explanation of why steps 3/4 of the normalization
   pipeline don't catch it, a note on what a fix would require, and an explicit statement that
   AC3's literal wording doesn't cover this case. This is a genuine documented accepted-risk
   entry, not a cosmetic one-liner.

## Verification method
Read requirements.md, the current plan.md, ci.yml, androidApp/build.gradle.kts,
AudiobookAutoSettingsTest.kt, CaptureViewModel.kt, and CaptureShareTextTest.kt directly (no
reliance on the prior review's or repair pass's summaries). Ran two targeted greps against
plan.md for non-ASCII and literal NBSP bytes. Counted the 12 existing `CaptureShareTextTest`
cases directly from the file to confirm the plan's "12 existing" claim.
