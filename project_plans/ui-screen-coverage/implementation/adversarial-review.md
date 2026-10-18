# Adversarial Review: ui-screen-coverage

**Date**: 2026-10-13
**Verdict**: CONCERNS (0 blockers, 4 concerns, 3 minors)

---

## Blockers

- [x] **AC-8 references the wrong JaCoCo task, making it unverifiable as written** — AC-8 says "as
  reported by `./gradlew :kmp:jacocoTestReport`". Confirmed in `kmp/build.gradle.kts:1521`:
  `jacocoTestReport` consumes `jacoco/jvmTest.exec` and reads from
  `build/classes/kotlin/jvm/main` (JVM classes only). Every test the plan prescribes is a
  Robolectric test in `testDebugUnitTest`, whose execution data goes to
  `jacoco/testDebugUnitTest.exec` → `jacocoAndroidTestReport` (`build.gradle.kts:1677`). No
  Robolectric test can move the `jacocoTestReport` number. The plan flags this as an "unresolved
  question" but defers it to the owner. **Resolution required before Phase 3**: owner must either
  (a) update AC-8 to reference `jacocoAndroidTestReport`, or (b) confirm the intent is to gate on
  the union report produced by `diffCoverage` (which depends on both tasks). Until the correct
  task name is established, Phase 3.1.1 has no verified "done" state and the implementer cannot
  confirm success.

  **Blocker resolved by plan repair**: Story 3.1.1 now specifies
  `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport` explicitly (plan.md line 441)
  with the correct XML path (`kmp/build/reports/jacoco/jacocoAndroidTestReport/jacocoAndroidTestReport.xml`).
  The Tech Debt Disposition section marks this RESOLVED per ADR-001. The Observability Plan and
  Story 3.1.1 Given-When-Then both reference `jacocoAndroidTestReport` consistently. Phase 3.1.1
  now has a concrete, verifiable "done" state. Minor inconsistency: the "Unresolved Questions"
  section still shows the original `[ ]` item without being struck, but this is cosmetic — the
  substantive plan content is correct throughout.

---

## Concerns

- [ ] **Coverage projection has no supporting arithmetic — overall 55% is not implied by per-file
  ACs passing** — The plan targets 7 individual files (ACs 1–7) and separately claims "overall
  ui.screens ≥55%". No line-count analysis shows how the per-file gains combine to reach the
  package-level threshold. If `ui.screens` contains additional low-coverage files not addressed by
  the plan (e.g. files that the existing 37.5% baseline masks), hitting all 7 per-file ACs does
  not guarantee AC-8. Recommendation: run `jacocoAndroidTestReport` baseline now, extract
  per-file line counts across the full `ui.screens` package, and confirm 48–66 tests in the
  targeted files are sufficient to shift the denominator to ≥55%. If not, add targeted tests for
  any other low-coverage files before claiming the plan is complete.

- [ ] **`AllPagesViewModel` filter debounce runs on real wall-clock time with `Dispatchers.Unconfined`**
  — `AllPagesViewModel.pages` applies `debounce(300)` to `_filterQuery` (confirmed at
  `AllPagesViewModel.kt`). `Dispatchers.Unconfined` is not a test dispatcher and does not provide
  virtual-time control; it only executes coroutines on the calling thread eagerly. The debounce
  timer still runs real 300ms wall-clock time. Every filter-related test in Story 1.3.2
  (`AllPagesScreen tests`) will therefore take ≥300ms of real time per assertion. The existing
  `AllPagesViewModelTest` (jvmTest) uses `advanceTimeBy(400)` to skip debounce instantly — that
  idiom is unavailable in Robolectric Compose tests. Recommendation: accept the slowdown
  explicitly in the plan, and add a note that filter tests should use `waitUntil(2_000)` (not
  5_000) to keep the suite fast. Do not attempt to replace `Dispatchers.Unconfined` with
  `UnconfinedTestDispatcher` inside a `createComposeRule()` test — that combination is not
  supported by Compose UI test infrastructure.

- [ ] **`ImportScreen` Review-stage dismiss test depends on a complex async chain that is not
  validated** — Story 2.1.2 test "click confirm → assert `onDismiss` invoked" relies on
  `ImportScreen.kt:52–55`:
  ```kotlin
  LaunchedEffect(state.savedPageName, state.showUndoSnackbar) {
      if (state.savedPageName != null && !state.showUndoSnackbar) onDismiss()
  }
  ```
  This fires only after `viewModel.confirmImport()` completes and sets `savedPageName`. The
  confirm button launches `coroutineScope.launch { viewModel.confirmImport() }` where
  `coroutineScope` is `rememberCoroutineScope()` — not the VM's `Dispatchers.Unconfined` scope.
  The `rememberCoroutineScope()` runs on Compose's main-thread dispatcher, which under Robolectric
  is a Looper-based executor that requires Compose test harness pump calls to advance. A plain
  `waitUntil(5_000) { onDismissCalled }` loop may succeed, but if `confirmImport()` suspends
  internally (e.g. writing to `InMemoryPageRepository`) the Looper may not advance without a
  pump. Recommendation: add a test that confirms this works before the story is considered done;
  if it hangs, the fix is to use `composeTestRule.waitForIdle()` inside the `waitUntil` body, or
  restructure the test to assert `vm.state.value.savedPageName != null` (which bypasses the
  `LaunchedEffect` timing) as an intermediate assertion.

- [ ] **No automated CI gate enforces AC-8 — Phase 3 verification is purely manual** — The diff-
  coverage gate in CI (`build.gradle.kts:1696`, `.github/workflows/ci.yml:215`) checks incremental
  coverage of _changed production lines_ only. A test-only PR has no changed main classes, so
  `diffCoverage --selectTests` runs zero tests and emits no coverage data. There is no CI check
  that enforces the ≥55% package-level threshold — it exists only as a manual Phase 3 step. If
  Phase 3.1.1 is skipped (e.g. the branch merges incrementally over multiple PRs), the overall
  55% gate is never verified. Recommendation: either add a Gradle task that reads
  `jacoco-android.xml`, extracts the `ui.screens` package line-rate, and fails below 55%; or
  document explicitly that AC-8 is a manual owner verification at the final merge point, not a CI
  gate. Do not imply CI will catch a regression in the initiative completion comment.

---

## Minors

- **Blanket `waitUntil(5_000)` where `waitForIdle()` suffices for synchronous tests** — Pattern A
  tests (ConflictResolutionScreen, Step3Auth) make purely synchronous state changes via captured
  lambdas. `composeTestRule.waitForIdle()` is both correct and faster (no polling timeout). Using
  `waitUntil(5_000)` adds up to 5s of failure-path latency per test and obscures which assertions
  involve real async. Plan's blanket mandate conflates sync and async cases.

- **No `@Config(sdk = ...)` guidance for Robolectric 4.16** — The plan mentions
  `@Config(qualifiers = ...)` for viewport-sensitive tests but not `@Config(sdk = ...)`. Robolectric
  4.16 defaults to SDK 34 but the project's `compileSdk` should be confirmed. If the app targets
  a higher API, some Material3 Compose APIs may behave differently under the default Robolectric
  SDK. The existing test files in `androidUnitTest` appear to rely on global Robolectric config
  (`robolectric.properties` or build config); this should be confirmed to apply to new test files
  placed in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/` (a new subdirectory
  not previously used for Compose tests).

- **Phase 3.1.1 per-file coverage extraction method not described** — Story 3.1.1 says "confirm
  per-file coverage against ACs 1–7" but does not describe how to extract per-file percentages
  from `jacoco-android.xml`. The XML `<package name="...ui/screens">` → `<class name="...">` →
  `<counter type="LINE" missed="N" covered="M">` path requires either manual XML parsing or a
  tool invocation. Since the HTML report is not generated for `jacocoAndroidTestReport`
  (`html.required.set(false)` at `build.gradle.kts:1683`), the implementer must parse XML or
  enable HTML generation. Recommend enabling HTML for one verification run or providing the exact
  `xmllint`/`grep` command to extract line rates from `jacoco-android.xml`.
