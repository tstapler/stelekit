# Implementation Plan: Scheduling Hygiene

**Date:** 2026-11-26  
**Depends on:** research.md findings

## Summary

**Superseded in part:** the claim below that production code has no `runBlocking` was wrong; see the correction in research.md.

Production scheduling code has no `runBlocking` — that half of the audit is complete and needs
only a brief confirmation comment in the PR. The work is entirely in the test layer:

- Migrate Category A `runBlocking` tests to `runTest`.
- Replace the 30-second `withTimeout` in `OneShotSkipTest` with a deterministic signal.
- Justify and annotate the remaining real-clock waits that cannot be removed.

## Tasks

### Task 1 — Confirm and document production-code audit result (0.5 h, test)

Add a one-line comment in `TaskWorker.kt` and `ScheduleStartup.kt` confirming the absence of
`runBlocking`.

Alternatively, add a `ProductionSchedulingNoBlockingTest` (businessTest or androidUnitTest)
that greps the source files to assert no `runBlocking` call appears in the production
scheduling package. A source-text guard in a `@Test` enforces this permanently.

**Acceptance check:** AC-1 satisfied.

### Task 2 — Migrate ScheduleStartupTest to runTest (1 h, test)

Replace all 11 `= runBlocking {` bodies with `= runTest {`. Verify no `CountDownLatch` or
blocking thread-synchronization is present. Add `kotlinx-coroutines-test` import where needed.

**Acceptance check:** AC-2a for ScheduleStartupTest.

### Task 3 — Migrate TaskWorkerTest to runTest (1 h, test)

Replace the helper `private fun run(...): Result = runBlocking {` with
`private suspend fun run(...): Result` and call it from `runTest` bodies. Migrate all ~20 test
methods from `runBlocking` to `runTest`.

**Acceptance check:** AC-2a for TaskWorkerTest.

### Task 4 — Migrate WorkManagerTaskScheduler constraint/pause tests to runTest (1 h, test)

`WorkManagerTaskSchedulerConstraintsTest.kt` (13 occurrences) and
`WorkManagerTaskSchedulerPauseResumeTest.kt` (14 occurrences) have no real-thread sync — they
use `SynchronousExecutor`-backed WorkManager which runs everything on the test thread. Replace
`runBlocking` with `runTest` throughout.

**Acceptance check:** AC-2a for both files.

### Task 5 — Migrate ScheduledTaskReconcilerTest and LegacyWorkUpgradeTest (0.5 h, test)

Both files have simple `runBlocking` test bodies (4 and 3 occurrences respectively). Replace
with `runTest`.

**Acceptance check:** AC-2a for both files.

### Task 6 — Fix OneShotSkipTest: replace 30-second timeout with deterministic signal (2 h, test)

**Root cause:** `assertDownloadResolvesFailed` awaits `f.downloader.downloadModel()` with a
30-second real-clock guard. The fix has two parts:

1. **Expose a deterministic terminal signal from `DepthModelDownloader`.** The `modelState`
   `StateFlow` already carries `ModelState.Failed` / `ModelState.Downloaded`. Change
   `assertDownloadResolvesFailed` to use:
   ```kotlin
   val outcome = scope.async { f.downloader.downloadModel() }
   // ... trigger WorkManager ...
   f.downloader.modelState.first { it is ModelState.Failed || it is ModelState.Downloaded }
   assertEquals(DepthModelDownloader.ModelState.Failed(expectedReason), f.downloader.modelState.value)
   ```
   This eliminates the timeout entirely; resolution is event-driven.

2. **Or, if the async/await shape must be kept**, replace `withTimeout(30_000)` with
   `withTimeout(5_000)` and add a comment explaining the ceiling exists only for CI
   safety, not normal execution. This is the minimum fix if the full refactor is deferred.

Preferred: approach 1. The `workerRun` helper in the same file should also be migrated from
`runBlocking` to `suspend fun` (called via `runTest`).

**Acceptance check:** AC-3.

### Task 7 — Annotate justified real-clock waits (0.5 h, test)

Add `// runBlocking: real-thread concurrency test — CountDownLatch blocks; runTest cannot reproduce thread interleaving` above the `runBlocking` in `WorkManagerTaskSchedulerConcurrencyTest.kt`.

Add a similar comment above `withTimeoutOrNull(1_500)` in the same file: `// bounded negative assertion: checks second did NOT complete before first's lock was released`.

For `AndroidGitCloneWorkerLauncherTest.kt`'s `Thread.sleep(25)` loop: add
`// polling loop in fake worker body — real sleep, bounded to 200×25ms = 5s`.

For `GitSyncWorkerGateAndRecordTest.kt`'s `withTimeout(10_000)`: annotate with the reason (WorkManager state machine; synchronous executor should make this resolve in <1 s, timeout is a CI safety ceiling).

**Acceptance check:** AC-2b and AC-4.

### Task 8 — Verify test-hygiene-baseline.txt does not grow (0.5 h, test)

After all changes, run `./gradlew testDebugUnitTest` and confirm:
- `TestHygieneAuditTest` passes.
- `test-hygiene-baseline.txt` has no new entries.
- If any addressed items were previously in the baseline (unlikely, since these are test-only patterns), remove them.

**Acceptance check:** AC-5.

### Task 9 — Full test run verification (0.5 h, test)

Run `./gradlew testDebugUnitTest` (or `bazel test //kmp/modules:...` for affected modules) and
confirm no regressions.

**Acceptance check:** AC-6.

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `runTest` changes virtual-time semantics and breaks a timing-sensitive test | Medium | Low | Run tests after each migration task; revert to `runBlocking` + justify if a test fails |
| `OneShotSkipTest` refactor exposes a real bug in `DepthModelDownloader` state machine | Low | Medium | Add the 5-second fallback timeout as a safety ceiling; investigate if hit |
| `WorkManagerTaskSchedulerConcurrencyTest` concurrency test becomes flaky after `runBlocking → runTest` (it should stay as `runBlocking`) | Low | Low | Category B — this test is explicitly excluded from migration |

## Adversarial Review Notes

**Q: Is the `runBlocking` in Robolectric tests truly harmful?**  
A: Not to the test process's correctness — Robolectric tests run on a single test thread,
so `runBlocking` there is not an ANR risk. The issue is determinism and speed: `runBlocking`
in a test does not let `kotlinx-coroutines-test` control time, so any `delay()` or
`withTimeout()` inside runs on the real clock. Migrating to `runTest` enables virtual time for
free and removes the dependency on wall-clock speed.

**Q: Can `runTest` be used with `SynchronousExecutor`-backed WorkManager?**  
A: Yes — `WorkManagerTestInitHelper` with `SynchronousExecutor` runs workers on the calling
thread synchronously. Inside `runTest`, calling `doWork()` is equivalent to calling any
suspending function. No additional wiring needed.

**Q: What if `DepthModelDownloader.modelState` doesn't expose a signal early enough?**  
A: `modelState` is a `StateFlow` that is updated before `downloadModel()` resolves, by design
(the caller reads state separately from the return value). The `first { ... }` collector will
see the terminal state as soon as the worker emits it.

## Estimated Cost

| Task | Category | Estimate |
|------|----------|----------|
| 1 — Confirm production audit | test | 0.5 h |
| 2 — ScheduleStartupTest → runTest | test | 1 h |
| 3 — TaskWorkerTest → runTest | test | 1 h |
| 4 — Constraints + PauseResume → runTest | test | 1 h |
| 5 — Reconciler + Legacy → runTest | test | 0.5 h |
| 6 — OneShotSkipTest deterministic signal | test | 2 h |
| 7 — Annotate justified waits | test | 0.5 h |
| 8 — Hygiene baseline check | test | 0.5 h |
| 9 — Full test run | test | 0.5 h |
| **Total** | | **~7.5 h** |
