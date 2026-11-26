# Scheduling Hygiene: runBlocking Audit and Real-Clock Test Polling

**Item:** b9823895-f00e-4172-9672-931c0796ad53  
**Follow-up from:** #642 (merged as 9656e5e3f6)  
**Date:** 2026-11-26

## Background

PR #642 introduced the background task scheduling subsystem (`jobs/schedule/`). Two hygiene
items were deferred from that review:

1. **`runBlocking` audit** — confirm that scheduling workers and `ScheduleStartup` do not call
   `runBlocking` on a main thread or WorkManager worker thread, blocking the platform from
   making forward progress.
2. **Real-clock test polling** — test cases that sit on `withTimeout(30_000)` or similar
   real-wall-clock waits inflate CI time and are fragile under load. The named example is
   `OneShotSkipTest`, which uses a 30-second `withTimeout` guard.

## Problem Statement

### runBlocking audit

`runBlocking` on a WorkManager worker thread is a correctness issue: WorkManager caps the
thread pool and will starve other work if a `runBlocking` call holds a thread while suspended.
On the main thread it causes an ANR. The audit must confirm that no production path in the
scheduling subsystem (`jobs/schedule/`, `ScheduleStartup`, `TaskWorker`, `GitSyncWorker`,
`DepthModelDownloadWorker`) contains a `runBlocking` that could block a constrained thread.
Test uses of `runBlocking` must also be classified: justified (real-thread concurrency tests)
or should-be-`runTest` (ordinary coroutine tests that happen to use `runBlocking`).

### Real-clock test polling

`withTimeout(30_000)` in a Robolectric test adds up to 30 real seconds to CI if the condition
is never met. Even when it succeeds quickly, the existence of a 30s timeout makes the test
appear to hang until the deferred resolves, which is nondeterministic under CI load. The same
pattern appears in `GitSyncWorkerGateAndRecordTest` (10 s) and
`WorkManagerTaskSchedulerConcurrencyTest` (1.5 s).

## Scope

### In scope
- All files under `kmp/src/*/kotlin/**/jobs/schedule/`
- `ScheduleStartup.kt` and its test `ScheduleStartupTest.kt`
- `TaskWorker.kt` and `TaskWorkerTest.kt`
- Worker bindings: `GitSyncWorkerBinding`, `DepthModelDownloadWorker`,
  `GitCloneWorker`, `AndroidGitCloneWorkerLauncher`
- `OneShotSkipTest.kt` (platform/ml, but exercises the scheduling path)
- `GitSyncWorkerGateAndRecordTest.kt`, `WorkManagerTaskSchedulerConcurrencyTest.kt`

### Out of scope
- Non-scheduling coroutine uses of `runBlocking` elsewhere in the codebase
- Robolectric test configuration or WorkManager test harness rewrites

## Test scope (settled during review)

"Scheduling and worker tests" means any test of `jobs.schedule`, workers, schedulers, git-sync runs
(service, coordinator, single-flight, repo gate), background-sync quiesce and model download, in every test
source set. Out of scope: `GraphRelocationCoordinator*`, `SidecarReconcileTest`, `ThreeStateSubscriptionTest`,
`StelekitViewModelShareExportTest`, `GitRepositoryUnshallowTest` (none schedules or runs background work).

## Acceptance Criteria

1. No `runBlocking` exists in any production scheduling/worker source file
   (`androidMain/jobs/schedule/`, `androidMain/git/GitSyncWorker*`,
   `androidMain/platform/ml/DepthModelDownloadWorker*`).
2. Every `runBlocking` in scheduling and worker **test** files is either:
   (a) replaced with `runTest` where the body is a simple coroutine with no real-thread
   synchronization, or
   (b) annotated with an inline comment explaining why real-thread blocking is required
   (e.g., `CountDownLatch`-based concurrency checks).
3. `OneShotSkipTest.assertDownloadResolvesFailed` replaces `withTimeout(30_000)` with a
   deterministic signal — either a `CompletableDeferred`-backed gate on the downloader or a
   `runTest`-compatible virtual-time advance — such that the test does not wait more than a
   few virtual milliseconds.
4. Any remaining real-clock waits > 2 s in scheduling tests are documented with an inline
   comment explaining why virtual time or a deterministic handshake cannot be used, plus a
   `test-hygiene-baseline.txt` entry if the case is temporarily accepted rather than fixed.
5. The `test-hygiene-baseline.txt` file does not grow as a result of this change (all
   addressed items are removed or never added).
6. `bazel test //kmp:business_tests` (or `./gradlew testDebugUnitTest`) passes with no new
   failures after all changes.
