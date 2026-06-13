# Adversarial Review: journal-watch-fix

**Date**: 2026-05-29
**Verdict**: CONCERNS

---

## Blockers

*(none)*

---

## Concerns

- [ ] **Duplicate midnight-watcher on graph reload** — The plan says "no duplicate-watcher risk because the scope is cancelled before a new loadGraph is called." This is true only if `StelekitViewModel.close()` (which calls `scope.cancel()`) is always invoked between two `loadGraph` calls. In practice, `loadGraph` can be called on the same live ViewModel (e.g. switching graphs via the graph picker) without `close()` being called first. In that case, the old watcher coroutine remains running on the still-alive scope, and `startMidnightBoundaryWatcher` launches a second one. Both will call `ensureTodayJournal()` at midnight. Since `ensureTodayJournal` is idempotent and mutex-protected this is not data-corrupting, but it wastes resources and can cause double log entries. Recommendation: store the watcher `Job` in a member variable (`private var midnightWatcherJob: Job? = null`) and cancel the previous job before launching a new one inside `startMidnightBoundaryWatcher`.

- [ ] **`millisUntilNextMidnight` is private — test is forced to duplicate the formula** — Task 1.2d notes the test must either expose the helper via `internal` visibility or duplicate its logic inline. The plan says "mark it `internal`" as the preferred approach but does not specify the visibility modifier in Task 1.2a. If left `private`, the `GraphLoaderProgressiveTest` test in the same module cannot call it, making the "focused unit test on the helper" approach impossible. The plan's alternative (inline duplication) creates a divergence risk. Recommendation: Task 1.2a must explicitly state `internal fun millisUntilNextMidnight(...)`, not just "private fun".

- [ ] **`StelekitViewModelDependencies` stub is marked TODO in the test** — Task 1.2d's primary test body contains `TODO("stub")` for `graphLoader`, `graphWriter`, and `platformSettings`. The plan acknowledges this and offers an "alternative / preferred approach" that avoids constructing the full ViewModel. However, the alternative test also inlines the watcher loop rather than calling the real `startMidnightBoundaryWatcher`. This means the test does NOT exercise the production code path in `StelekitViewModel`. A refactoring of `startMidnightBoundaryWatcher` (e.g. changing the loop condition) would not be caught by this test. Recommendation: either (a) make `startMidnightBoundaryWatcher` `internal` so the test calls it directly on a real ViewModel with a minimal stub, or (b) accept the inline approach but document explicitly that it tests the algorithm, not the integration. The plan should resolve this ambiguity.

- [ ] **Own-write suppression analysis assumes `modTimes` is always current after `markWrittenByUs`** — The "why this doesn't break own-write suppression" section in `architecture.md` (and echoed in the plan) argues that own-written files always satisfy `modTime == lastKnown` so the `invalidateShadow` branch is never entered. This is correct under normal flow. However, the plan does not address the SAF mtime lag edge case documented in `pitfalls.md`: if the SAF provider reports a higher mtime than what `markWrittenByUs` stored (because the provider lags by one polling cycle), the own-write path CAN enter `modTime > lastKnown` and call `invalidateShadow`. After invalidation, `readFile` goes to SAF; the returned content is identical to what was written (no actual change), so the content-hash check correctly suppresses the change — but the shadow file that `WriteBehindQueue` needs for its flush is now deleted. The plan mentions this risk in the pitfalls section but dismisses it as "theoretical". For Android users with slow SAF providers (Google Drive, iCloud for Android), this could cause silent data loss if the write-behind flush silently skips a page. Recommendation: add a comment in the `detectChanges` diff noting the write-behind risk and the mitigation (`markWrittenByUs` stores the SAF-queried mtime). Consider a defensive check: if the content hash matches after `invalidateShadow` + `readFile`, also call `fileSystem.updateShadow(filePath, content)` to restore the shadow so the write-behind queue can still flush.

- [ ] **FakeClock is placed in `jvmTest` but may be needed in `commonTest` or `androidUnitTest`** — If future tests in `androidUnitTest` need clock injection (e.g. to test `JournalService.ensureTodayJournal` on Android), they cannot access a `FakeClock` defined in `jvmTest`. The plan places `FakeClock` in `kmp/src/jvmTest/.../testing/FakeClock.kt`. Recommendation: check whether `commonTest` or a shared test fixture already exists and, if so, place `FakeClock` there. This is low-risk for the immediate fix (jvmTest covers both stories) but worth noting.

---

## Minors

- The plan does not include a test for the **third midnight crossing** in the watcher loop. The test advances by 24h to get a second call but stops there. A third iteration would confirm the loop correctly re-computes the delay using the updated clock, not a stale cached value. Low risk given the loop is a simple `while (isActive)`, but worth a one-line assertion.

- Task 1.1a lists 8 `Clock.System` occurrences to replace but the actual line numbers in `JournalService.kt` may shift as the constructor is modified. The plan should advise running `grep -n "Clock.System" JournalService.kt` after the constructor change to confirm all occurrences are replaced, rather than relying on static line numbers.

- The plan says `import kotlinx.datetime.plus` is needed in `StelekitViewModel.kt` for `LocalDate.plus(1, DateTimeUnit.DAY)`. This import may conflict or duplicate if `kotlinx.datetime.*` is already imported via a wildcard. Should note: check existing imports before adding line-level imports.

- The test imports section references `dev.stapler.stelekit.repository.InMemorySearchRepository` — verify this class exists and is in the expected package before Task 1.2d attempts to use it.

- **No smoke test for Android SAF path** — All new tests are JVM tests. The `invalidateShadow` fix is Android-specific; the JVM tests exercise the no-op path. Actual validation on Android requires a manual test or an instrumented test (`androidInstrumentedTest`), which is outside the current test plan. The plan should explicitly note this gap so the reviewer knows manual Android verification is required before merging.
