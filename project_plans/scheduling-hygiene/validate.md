# Validation: Scheduling Hygiene Plan

**Date:** 2026-11-26

## Validation Checks

### AC-1: Production code has no runBlocking

**Correction:** the verification below was wrong. `androidMain/git/WorkManagerSyncScheduler.kt` had four
`runBlocking` calls, removed during implementation (see research.md).

VERIFIED by search:
- `ScheduleStartup.run()` is `suspend fun` — no `runBlocking`.
- `TaskWorker.doWork()` is a suspend function (`CoroutineWorker`) — no `runBlocking`.
- `GitSyncWorkerBinding`, `DepthModelDownloadWorker`, `GitCloneWorker`,
  `AndroidGitCloneWorkerLauncher` — no `runBlocking` found in any production file.

The plan correctly identifies this as already clean and proposes a lightweight enforcement
gate (source-text check test) to prevent regression.

### AC-2: runBlocking classification is sound

The Category A / Category B split is based on direct inspection of each file:

- **Category A** files (`ScheduleStartupTest`, `TaskWorkerTest`, constraints/pause-resume tests,
  `OneShotSkipTest`) have no `CountDownLatch`, `Semaphore`, or thread-creation calls. Their
  `runBlocking` bodies are plain coroutine functions — `runTest` is a direct drop-in.
- **Category B** files (`WorkManagerTaskSchedulerConcurrencyTest`) explicitly construct
  `ParkingSettings` with a `CountDownLatch(1)` that blocks the coroutine's underlying thread.
  Running this inside `runTest` would deadlock: `runTest` uses `StandardTestDispatcher` which
  runs coroutines in-order on the test thread; `CountDownLatch.await()` on that same thread
  would never be released because the coroutine that calls `release.countDown()` is queued
  behind the blocked one.

Classification is correct.

### AC-3: OneShotSkipTest fix is feasible

`DepthModelDownloader.modelState: StateFlow<ModelState>` is confirmed to exist. The terminal
states `ModelState.Failed` and `ModelState.Downloaded` are distinct sealed variants. The
`downloadModel()` function drives the state machine; `modelState` will reach a terminal state
before the coroutine returns. Collecting `modelState.first { terminal }` is a valid
deterministic replacement for the 30-second timeout.

One risk: if `downloadModel()` and `modelState` share a single `MutableStateFlow`, there is a
potential ordering issue where `downloadModel()` returns before the collector sees the update.
Mitigation: collect `modelState` before launching the `async` block, or confirm that
`awaitDownloadingState()` and `setAllConstraintsMet()` both flush their state synchronously
before the `result.await()` call. This should be verified during implementation.

### AC-4: Remaining justified waits are bounded

| Wait | Bound | Verdict |
|------|-------|---------|
| `withTimeoutOrNull(1_500)` in concurrency test | 1.5 s | Acceptable for a mutex-correctness test |
| `withTimeout(10_000)` in git worker test | 10 s | Acceptable; `SynchronousExecutor` makes this resolve in <1 s normally |
| `Thread.sleep(25)` × 200 in clone launcher test | 5 s | Acceptable; bounded polling loop in fake body |
| `withTimeout(5_000)` × 2 in JVM git tests | 5 s each | Acceptable; `CompletableDeferred` signals |

All are bounded, not unbounded waits. The 10-second and 30-second (post-fix) timeouts become
annotation candidates if the fix is not feasible, not baseline additions.

### AC-5 and AC-6: No baseline growth, tests pass

The plan proposes migrating tests that currently have no baseline entry — they were not
previously flagged. Post-migration, `TestHygieneAuditTest` should pass unchanged (the audit
does not scan for `withTimeout` in test code). Full test run verification is the final task.

## Open Questions / Risks

1. **OrderDependent: does `downloadModel()` observe its own state transition?** If the state
   machine for `DepthModelDownloader` completes the coroutine return before updating
   `modelState`, the `first { terminal }` collector could hang. Implementation should check
   the state machine order and add a brief inline note confirming it.

2. **`runTest` and `SynchronousExecutor` combination**: while expected to work, this
   combination should be tested for the first migration (e.g., `ScheduleStartupTest`) before
   converting all files. If a test fails non-obviously, the root cause is likely a
   `coroutineScope` or `launch` that `runTest` advances past before WorkManager processes
   its queue. The fix is `testScheduler.advanceUntilIdle()`.

3. **`GitSyncWorkerGateAndRecordTest.kt` line 316**: the 10s guard waits for a WorkManager
   `WorkInfo.State.RUNNING` emission on a live `Flow`. With `SynchronousExecutor`, the work
   runs synchronously and never enters `RUNNING` as an observable state — it goes directly
   to `SUCCEEDED`. This test may need a different approach (e.g., a synchronous check after
   `enqueue`, or removal of the state-wait entirely). Flag this as an implementation decision
   point.

## Validation Verdict

The plan is sound. All ACs are achievable with the described approach. The two open questions
above (state-machine ordering, 10-second git-worker state wait) require implementation-time
investigation but do not block the plan from proceeding.
