# Research: Scheduling Hygiene Audit

**Date:** 2026-11-26  
**Codebase ref:** worktree triage-b9823895 (post-#642 merge, 9656e5e3f6)

## 1. runBlocking in Production Scheduling Code

**Correction (implementation): the finding below was wrong.** `androidMain/git/WorkManagerSyncScheduler.kt`
(which also defines `GitSyncWorker`) had four `runBlocking` calls; they were removed by making `schedule`,
`cancel`, `pauseFor` and `resumeFor` suspend. `DriverFactory.android.kt` also uses `runBlocking`, reached from
the cold git-sync worker; it stays, with its justification at the call site.

~~Finding: CLEAN — no `runBlocking` in production scheduling source.~~

| File | Result |
|------|--------|
| `androidMain/jobs/schedule/ScheduleStartup.kt` | No `runBlocking`. `run()` is `suspend fun`. |
| `androidMain/jobs/schedule/TaskWorker.kt` | No `runBlocking`. `doWork()` is `suspend fun` (CoroutineWorker). |
| `androidMain/jobs/schedule/TaskWorkRunner.kt` | No `runBlocking` found. |
| `androidMain/git/GitSyncWorkerBinding.kt` | No `runBlocking`. |
| `androidMain/platform/ml/DepthModelDownloadWorker.kt` | No `runBlocking` found. |
| `androidMain/git/GitCloneWorker.kt` | No `runBlocking` found. |
| `androidMain/git/AndroidGitCloneWorkerLauncher.kt` | No `runBlocking` found. |

The production scheduling code is correct: `TaskWorker` extends `CoroutineWorker` whose `doWork()` is already a suspend function, so there is no need to bridge to coroutines with `runBlocking`.

## 2. runBlocking in Test Code (Classification)

### Category A: Should be `runTest` (ordinary coroutine tests)

These files use `= runBlocking {` as a test body bridge where there is no real-thread
synchronization. The standard replacement is `runTest { }` from `kotlinx-coroutines-test`,
which uses a `StandardTestDispatcher` and virtual time by default.

| File | Occurrences | Notes |
|------|-------------|-------|
| `ScheduleStartupTest.kt` | 11 | All test methods; no CountDownLatch or real-thread sync |
| `TaskWorkerTest.kt` | ~20 | Most test methods; helper `run()` also uses `runBlocking` |
| `ScheduledTaskReconcilerTest.kt` | 4 | Simple coroutine tests |
| `LegacyWorkUpgradeTest.kt` | 3 | Simple coroutine tests |
| `WorkManagerTaskSchedulerConstraintsTest.kt` | 13 | Constraint-setting tests with no cross-thread deps |
| `WorkManagerTaskSchedulerPauseResumeTest.kt` | 14 | Pause/resume state machine tests |
| `OneShotSkipTest.kt` | 2 | `workerRun` helper + `assertDownloadResolvesFailed` |

### Category B: Justified `runBlocking` (real-thread concurrency tests)

These tests deliberately use real threads and thread-synchronization primitives. Using
`runTest` here would not reproduce the race condition being tested.

| File | Occurrences | Justification |
|------|-------------|---------------|
| `WorkManagerTaskSchedulerConcurrencyTest.kt` | 1 | Uses `ParkingSettings` (a `CountDownLatch`-backed Settings mock) + two `Dispatchers.Default` async coroutines to verify that the check-then-act sequence is atomic. Virtual time cannot reproduce thread interleaving. |
| `GitSyncWorkerGateAndRecordTest.kt` | Several helpers | Helper `run()` calls `doWork()` via TestListenableWorkerBuilder. Some test methods need a real executor for WorkManager state transitions. |
| `GitSyncWorkerStaleGateTest.kt` | Several | Real-thread WorkManager helpers. |
| `AndroidGitCloneWorkerLauncherTest.kt` | 5 | Launches real coroutines on `Dispatchers.Default`; fake worker polls with `Thread.sleep(25)`. |

**Note on `WorkManagerTaskSchedulerConcurrencyTest`:** The `withTimeoutOrNull(1_500)` at line 84
is a *bounded negative wait* — it checks that `second` did NOT complete before `first` was
released. Replacing it requires either a signal from the scheduling code that the critical
section was entered (not currently exposed) or a redesigned probe. This should be commented as
justified rather than removed, unless a better deterministic signal can be injected.

## 3. Real-Clock Waits (Full Inventory)

| File | Line | Timeout | Type | Verdict |
|------|------|---------|------|---------|
| `OneShotSkipTest.kt` | 103 | 30 000 ms | `withTimeout` | **Fix** — replace with deterministic downloader signal |
| `GitSyncWorkerGateAndRecordTest.kt` | 316 | 10 000 ms | `withTimeout` | **Fix if possible** — waits for WorkManager state; use `SynchronousExecutor` + explicit trigger instead |
| `WorkManagerTaskSchedulerConcurrencyTest.kt` | 84 | 1 500 ms | `withTimeoutOrNull` | **Comment + justify** — bounded negative assertion for concurrency correctness; cannot easily remove |
| `AndroidGitCloneWorkerLauncherTest.kt` | 188 | Polling | `Thread.sleep(25)` × 200 | **Comment + justify** — fake worker stopping loop; bounded to 5 s max |
| `JvmGitCloneWorkerLauncherTest.kt` | 98, 101 | 5 000 ms ×2 | `withTimeout` | **Fix if possible** — replace with `CompletableDeferred` signals from fake launcher |

## 4. OneShotSkipTest Root Cause

The 30-second timeout guard in `assertDownloadResolvesFailed` is protecting an `async { f.downloader.downloadModel() }` call. The `downloadModel()` suspend function is waiting for an internal state channel to emit a terminal state (`Failed` or `Downloaded`). The sequence is:

1. Enqueue a one-shot WorkManager request.
2. `awaitDownloadingState()` — waits for the downloader's state to reach `Downloading`.
3. `setAllConstraintsMet(id)` — signals WorkManager's test driver to run the worker.
4. `withTimeout(30_000) { result.await() }` — waits for the coroutine to resolve.

The 30s timeout is a defensive ceiling, not a real wait — in a healthy run this resolves in
< 100 ms because `SynchronousExecutor` runs WorkManager on the test thread. The risk is that
under CI load, the `downloadModel()` state machine never reaches `Failed` (the deferred hangs),
and the test hangs for 30 real seconds before the timeout fires and the test fails.

**Fix approach:** `DepthModelDownloader.modelState` is already a `StateFlow`. Instead of a
raw `withTimeout`, use `modelState.first { it is ModelState.Failed }` or expose a
`CompletableDeferred<ModelState>` that is completed when the terminal state arrives. Combined
with `runTest`, this gives deterministic resolution without any wall-clock timeout.

## 5. Relationship to TestHygieneAuditTest

`TestHygieneAuditTest` forbids new `Thread.sleep`, `GlobalScope`, uncancelled
`Dispatchers.IO` scopes, and real-clock `delay` in production. It also tracks offenders in
`test-hygiene-baseline.txt`.

The `withTimeout` / `withTimeoutOrNull` calls in test code are not currently flagged by this
audit (it only checks production code patterns). The item's spirit — deterministic tests —
is nonetheless aligned with that gate's intent.

## 6. Test Infrastructure Already In Place

- `InProcessTaskSchedulerContractTest.kt` uses `TestScope` + `advanceTimeBy` — confirming
  virtual-time testing is already adopted for the in-process scheduler. This is the right
  pattern to replicate in the Android scheduler tests.
- `WorkManagerTestInitHelper` with `SynchronousExecutor` is already the harness for
  WorkManager tests — it runs worker `doWork()` synchronously on the test thread, which
  makes most real-clock waits unnecessary.
