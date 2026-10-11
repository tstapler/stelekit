# Validation Plan: git-sync-resilience

**Date**: 2026-09-23

> **Phases 1-2 do NOT close the original bug report.** The reporter's literal repro — clone/sync
> silently stopping after the app is backgrounded / the screen is locked mid-transfer — is only
> fixed (and only validated) by Phase 3's Android foreground-service work plus the real-device
> manual release gate below. Do not close the originating issue/backlog item once Phase 1-2's
> automated tests go green; close it only after the **Phase 3 Manual Release Gate** section below
> has actually been run and has passed on real hardware. See pre-mortem.md P1 #3.

## Happy Path Scenario

Given the Baseline (a user starts a clone/fetch/push and one transient network interruption
today aborts the whole operation with no recovery, restarting from zero on manual retry), when
the user taps "Save configuration" on Step 5 to clone a large graph and a `SocketException`
occurs once mid-transfer during the shallow-clone transfer, then `classifyGitFailure` classifies
it `GitFailureClass.Transient`, `runGitTransportOpWithRetry` retries with backoff, Step 5 shows
"Cloning your graph…" / "Reconnecting… Attempt 2 of 4" without the spinner stopping, and the
clone completes successfully on a later attempt without the user manually restarting or losing
the already-fetched shallow pack.

This anchors the test design below: every story's tests either build a piece of this path
(classification → retry → shallow depth → checkpoint → foreground survival → UI/notification
state) or prove the two sharpest regressions the plan calls out — a permanent failure must still
fail fast (Story 1.3.1), and the stable-network/small-repo path must show zero added overhead
(Story 1.3.2).

---

## Requirement → Test Mapping

Requirement IDs (REQ-1…REQ-7) are requirements.md's **In Scope** bullets, the seven concrete
deliverables the Success Metrics decompose into. Each of plan.md's 31 stories is mapped to the
one it most directly satisfies. Two stories (6.1.1, 6.1.2) are marked **(FOUNDATIONAL)** — they
build the fault-injection harness other stories' integration tests depend on and must land
first; every dependent row says so explicitly in its Scenario.

- REQ-1: Retry-with-backoff for transient transport failures (clone/fetch/push, Desktop+Android)
- REQ-2: Explicit connect/read timeout configuration for JGit transports
- REQ-3: Depth-limited (shallow) clone as the default for new large-repo clones
- REQ-4: Resume-from-interruption (checkpoint-by-depth, per ADR-001) for clone/fetch
- REQ-5: Android foreground-service survival (backgrounding/screen-lock), including notification
- REQ-6: Wizard Step 5 progress UI shows retry/resume status
- REQ-7: Regression coverage proving the common case is unaffected

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-1 (Story 1.1.1) | `git/GitOperationSupportClassifierTest.kt` | `classifyGitFailure returns Transient when TransportException wraps a SocketException` | Unit | Happy path — reproduces the reported bug's actual exception shape |
| REQ-1 (Story 1.1.1) | `git/GitOperationSupportClassifierTest.kt` | `classifyGitFailure returns Permanent when TransportException wraps NoRemoteRepositoryException` | Unit | Error path — 404-shaped failure must not be treated as retryable |
| REQ-1 (Story 1.1.1) | `git/GitOperationSupportClassifierTest.kt` | `classifyGitFailure returns Cancelled when JGit throws CanceledException, and Cancelled is never retried` | Unit | Third taxonomy branch — cancellation must not enter the retry gate |
| REQ-1 (Story 1.1.1) | `git/GitOperationSupportClassifierTest.kt` | `runGitTransportOp routes a Transient-classified TransportException to onFailed, not onAuthFailed` | Integration | Reproduces today's actual bug end-to-end through the real catch/routing path |
| REQ-2 (Story 1.1.2) | `git/JvmGitRepositoryTest.kt` | `clone() applies GIT_TRANSPORT_TIMEOUT_SECONDS to the CloneCommand builder before call()` | Unit | Happy path — constant is wired, not just declared |
| REQ-2 (Story 1.1.2) | `git/JvmGitRepositoryTest.kt` | `doFetch() and doPush() apply the same GIT_TRANSPORT_TIMEOUT_SECONDS constant as clone(), not a duplicated per-platform literal` | Unit | Error/regression path — a future edit changing one call site but not the others |
| REQ-2 (Story 1.1.2) | `git/JvmGitRepositoryTest.kt` | `clone() against a non-routable TEST-NET address fails with TransportException within GIT_TRANSPORT_TIMEOUT_SECONDS instead of hanging indefinitely` | Integration | Real network attempt (Task 1.1.2d) — proves the timeout is live, not just present in source |
| REQ-1 (Story 1.2.1) | `resilience/RetryPoliciesTest.kt` (new) | `gitTransportTransient schedule produces ~5 jittered attempts with delays from 1s up to 16s` | Unit | Happy path |
| REQ-1 (Story 1.2.1) | `resilience/RetryPoliciesTest.kt` | `gitTransportTransient schedule terminates once a delay exceeds 16 seconds, rather than retrying forever` | Unit | Error/termination path |
| REQ-1 (Story 1.2.1) | `resilience/RetryPoliciesTest.kt` | `gitTransportTransientImmediate applies zero delay for fast, deterministic test execution` | Unit | Test-support variant, no integration needed (pure `Schedule` construction) |
| REQ-1 (Story 1.2.2) | `git/GitTransportRetryTest.kt` (new) | `runGitTransportOpWithRetry returns Right after the 3rd attempt when the first two throw a Transient SocketException, invoking preResolvedToken exactly once` | Unit | Happy path — retry-then-succeed, credentials resolved once not per-attempt |
| REQ-1 (Story 1.2.2) | `git/GitTransportRetryTest.kt` | `runGitTransportOpWithRetry returns Left after exactly 1 attempt when the failure classifies Permanent` | Unit | Error path — permanent failures never enter the backoff loop |
| REQ-1 (Story 1.2.2) | `git/GitTransportRetryTest.kt` | `runGitTransportOpWithRetry returns Left(RetryExhausted(attempts=5,...)) when every attempt classifies Transient and the schedule is exhausted` | Unit | Exhaustion path |
| REQ-1 (Story 1.2.2) | `git/GitTransportRetryTest.kt` | `runGitTransportOpWithRetry rethrows CancellationException without retrying when the failure classifies Cancelled` | Unit | Cancellation path |
| REQ-1 (Story 1.2.2) | `git/GitTransportRetryTest.kt` | `AndroidGitRepository.clone() retries a StubGitRepository-faked Transient TransportException and succeeds on the 3rd attempt` | Integration | **(depends on Story 6.1.1's StubGitRepository failure-sequence support)** — full-stack proof beyond the fake `op` lambda; real JGit-thrown-exception proof is Story 6.1.2's job |
| REQ-1 (Story 1.2.3) | `git/WorkManagerSyncSchedulerRetryOwnerTest.kt` (new) | `GitSyncWorker.doWork() fast path returns Result.failure(), not Result.retry(), when fetchOnly() returns Left(RetryExhausted(...))` | Unit | Happy path — single-owner invariant (ADR-002) on the fast path |
| REQ-1 (Story 1.2.3) | `git/WorkManagerSyncSchedulerRetryOwnerTest.kt` | `GitSyncWorker.doWork() slow path returns Result.failure(), not Result.retry(), when the standalone AndroidGitRepository.fetch() exhausts its internal retry budget` | Unit | Error path — same invariant on the slow (process-was-killed) path |
| REQ-1 (Story 1.2.3) | `git/WorkManagerSyncSchedulerRetryOwnerTest.kt` | `a GitSyncWorker built via TestListenableWorkerBuilder against a retry-exhausting StubGitRepository returns Result.failure() and is not re-enqueued by WorkManager's own retry mechanism` | Integration | Robolectric + real WorkManager test driver |
| REQ-7 (Story 1.3.1) | `git/GitTransportRetryTest.kt` | `runGitTransportOpWithRetry invokes a NoRemoteRepositoryException-throwing op exactly once, never entering the backoff loop` | Unit | Regression: permanent failure fails fast (the plan's own named "sharpest regression risk") |
| REQ-7 (Story 1.3.2) | `git/GitTransportRetryTest.kt` | `runGitTransportOpWithRetry invokes a first-attempt-success op exactly once, with zero delay() calls` | Unit | Regression: stable-network path has zero added overhead |
| REQ-3 (Story 2.1.1) | `git/JvmGitRepositoryTest.kt` | `clone() calls CloneCommand.setDepth(DEFAULT_CLONE_DEPTH) before call()` | Unit | Happy path |
| REQ-3 (Story 2.1.1) | `git/JvmGitRepositoryTest.kt` | `clone() against a fixture repo with exactly DEFAULT_CLONE_DEPTH commits still produces a shallow, not full, local history` | Unit | Boundary/error path — off-by-one at the exact depth |
| REQ-3 (Story 2.1.1) | `git/JvmGitRepositoryTest.kt` | `clone() against a real local file:// fixture repo with more than DEFAULT_CLONE_DEPTH commits leaves Repository.objectDatabase.shallowCommits non-empty` | Integration | Real JGit clone against a local fixture |
| REQ-3 / REQ-4 (Story 2.1.2) | `git/GitConfigCloneDepthMappingTest.kt` (new) | `GitConfig round-trips cloneDepthState=CloneDepthState.Shallow(depth=50) through SqlDelightGitConfigRepository.getConfig/saveConfig` | Unit | Happy path |
| REQ-3 / REQ-4 (Story 2.1.2) | `git/GitConfigCloneDepthMappingTest.kt` | `SqlDelightGitConfigRepository maps an unrecognized clone_depth_state column value, or SHALLOW with a null shallow_depth, to CloneDepthState.None rather than throwing or fabricating a depth` | Unit | Error/fail-safe path — the sealed type's fail-closed SQL parse rule (architecture-review.md's Concern remediation), not the old enum's `valueOf(...)` fallback |
| REQ-3 / REQ-4 (Story 2.1.2) | `git/GitConfigCloneDepthMappingTest.kt` | `a successful shallow clone for graphId="graph-1" persists cloneDepthState=CloneDepthState.Shallow(depth=50), readable back via getConfig("graph-1")` | Integration | Exercises `clone()` + `GitConfigRepository` together against a real SQLDelight DB (distinct from the Migration Plan test below, which covers pre-migration rows) |
| REQ-4 (Story 2.1.3) | `git/GitTransportRetryTest.kt` | `beforeRetry cleanup deletes a partially-populated target directory before the 2nd clone attempt, letting the retry succeed instead of throwing "already exists and is not an empty directory"` | Unit | Happy path |
| REQ-4 (Story 2.1.3) | `git/GitTransportRetryTest.kt` | `beforeRetry cleanup does not run when the failure classifies Cancelled — a manually cancelled clone leaves the partial directory intact` | Unit | Error/negative path — cleanup must be retry-only, never cancel-triggered (also covered from the UX side by Story 4.1.4's Task 4.1.4d) |
| REQ-4 (Story 2.1.3) | `git/JvmGitRepositoryTest.kt` | `JvmGitRepository.clone(), retried after a simulated interrupted first attempt, succeeds against a real local fixture repo with the target directory cleaned between attempts` | Integration | Real JGit clone/retry cycle |
| REQ-4 (Story 2.1.4) | `git/GitRepositoryUnshallowTest.kt` (new) | `unshallow(config) transitions cloneDepthState from Shallow(depth) to FullHistory (shallow_depth persisted as NULL) after FetchCommand.setUnshallow(true) succeeds` | Unit | Happy path |
| REQ-4 (Story 2.1.4) | `git/GitRepositoryUnshallowTest.kt` | `unshallow(config) returns FetchFailed("Remote has diverged...") and never calls setUnshallow(true) when the ref-divergence guard detects an unsafe widen` | Unit | Error path |
| REQ-4 (Story 2.1.4) | `git/GitRepositoryUnshallowTest.kt` | `unshallow() against a real local fixture remote widens a shallow clone to full history and persists FULL_HISTORY via GitConfigRepository` | Integration | Real JGit fetch + DB persistence together |
| REQ-3 (Story 2.1.5) | `git/ShallowMergeTest.kt` (new) | `merge() against a shallow repo whose shallow boundary safely covers the merge base succeeds exactly as a full-history merge would` | Unit | Happy path — common-case regression guard |
| REQ-3 (Story 2.1.5) | `git/ShallowMergeTest.kt` | `merge() returns Left(ShallowHistoryInsufficient) and creates no merge commit when the remote has diverged past the local shallow boundary (absent merge base)` | Unit | Error path |
| REQ-3 (Story 2.1.5) | `git/ShallowMergeTest.kt` | `merge() returns Left(ShallowHistoryInsufficient) and creates no merge commit when RevWalk returns a wrong-but-present merge-base commit sitting at the shallow boundary (a parent missing from the local object database)` | Unit | Error path — pre-mortem.md P1 #2's "wrong-but-present" case, distinct from the absent-merge-base row above; guards against JGit's documented spurious-ancestor caveat |
| REQ-3 (Story 2.1.5) | `git/ShallowMergeTest.kt` | `a synthetic shallow-clone-then-diverged-remote fixture pair fails closed with ShallowHistoryInsufficient via a real JGit RevWalk merge-base search` | Integration | Real JGit merge-base computation against constructed fixture repos |
| REQ-3 (Story 2.1.6) | `git/JvmGitRepositoryTest.kt` | `log(config, maxCount) against a shallow repo returns up to maxCount commits bounded by the shallow history without throwing` | Unit | Happy path (verification-first per plan — production code changes only if this fails) |
| REQ-3 (Story 2.1.6) | `git/JvmGitRepositoryTest.kt` | `countRemoteCommitsBestEffort against a shallow repo whose window is smaller than 100 returns a non-negative count ≤ 100 without throwing` | Unit | Boundary/error path |
| REQ-3 (Story 2.1.6) | `git/JvmGitRepositoryTest.kt` | `countRemoteCommitsBestEffort and log() against a real shallow-cloned local fixture repo, after a fetch that advances remoteRef within the shallow window, both behave per AC` | Integration | Real shallow clone + fetch fixture |
| REQ-5 (Story 3.1.1) | manifest-merge check (no JUnit file) | `AndroidManifest.xml declares FOREGROUND_SERVICE_DATA_SYNC and POST_NOTIFICATIONS` | Unit | Happy path — declarative-only story, no error path (its failure mode is covered by Story 3.1.2's `setForeground()` denial test instead) |
| REQ-5 (Story 3.1.1) | CI build step (`aapt2 dump permissions` or manifest-merge equivalent) | `both new permissions are present in the built APK's merged manifest` | Integration | Build-verification check, not a JVM test |
| REQ-5 (Story 3.1.2) | `git/GitCloneWorkerTest.kt` (new, androidUnitTest/Robolectric) | `GitCloneWorker.doWork() calls setForeground() with a dataSync-typed ForegroundInfo before invoking AndroidGitRepository.clone()` | Unit | Happy path |
| REQ-5 (Story 3.1.2) | `git/GitCloneWorkerTest.kt` | `doWork() logs the denial, proceeds without foreground promotion, and surfaces Attempting(progress, foregroundPromoted=false) when setForeground() throws ForegroundServiceStartNotAllowedException` | Unit | Error path |
| REQ-5 (Story 3.1.2) | `git/GitCloneWorkerTest.kt` | `GitCloneWorker built via TestListenableWorkerBuilder against a StubGitRepository completes doWork() end-to-end, returning Result.success()` | Integration | Robolectric + real WorkManager test driver — **(depends on Story 6.1.1's StubGitRepository)** |
| REQ-5 (Story 3.1.3) | `git/JvmGitCloneWorkerLauncherTest.kt` (new) | `JvmGitCloneWorkerLauncher.launchClone() calls GitRepository.clone() directly and returns its Either result unchanged` | Unit | Happy path |
| REQ-5 (Story 3.1.3) | `git/AndroidGitCloneWorkerLauncherTest.kt` (new) | `AndroidGitCloneWorkerLauncher.launchClone() translates a WorkInfo.State.FAILED terminal state into Left(...) rather than hanging or throwing` | Unit | Error path |
| REQ-5 (Story 3.1.3) | `git/AndroidGitCloneWorkerLauncherTest.kt` | `launchClone() enqueues a GitCloneWorker one-off request and suspends until WorkInfo reaches SUCCEEDED, observed via WorkManager's real Robolectric test driver` | Integration | |
| REQ-5 (Story 3.1.4) | `git/GitTransportRetryTest.kt` | `runGitTransportOpWithRetry stops and returns RetryExhausted once maxElapsed (10 minutes) elapses, even though the schedule's own attempt count would allow more attempts` | Unit | Happy path — deadline enforced |
| REQ-5 (Story 3.1.4) | `git/GitTransportRetryTest.kt` | `the wall-clock deadline does not cut short a fast sequence of retries that would finish well under 10 minutes` | Unit | Error/false-positive path — deadline must not fire prematurely |
| REQ-5 (Story 3.1.4) | `git/GitCloneWorkerTest.kt` | `GitCloneWorker dismisses its foreground promotion/notification immediately upon RetryExhausted, rather than waiting for WorkManager's own timeout` | Integration | |
| REQ-5 (Story 3.1.5) | `git/GitCloneWorkerTest.kt` | `GitCloneWorker.doWork() returns Result.failure(), never Result.retry(), when clone() returns Left(RetryExhausted(...))` | Unit | Happy path (ADR-002 single-owner invariant, extended to the new worker) |
| REQ-5 (Story 3.1.5) | `git/GitCloneWorkerTest.kt` | `GitCloneWorker.doWork() returns Result.failure(), not Result.retry(), for a Left(NonRetryable-shaped GitError) on the very first attempt too` | Unit | Error path — no code path returns `Result.retry()` |
| REQ-5 (Story 3.1.5) | — | — | — | No additional integration test — already exercised by Stories 3.1.2/3.1.4's integration rows above |
| REQ-5 (Story 3.1.6) | `git/GitCloneWorkerThrowableSafetyTest.kt` (new) | `doWork() returns Result.failure() and tears down the foreground notification when the wrapped clone delegate throws OutOfMemoryError` | Unit | Happy path (an `Error` subtype is caught, not propagated) |
| REQ-5 (Story 3.1.6) | `git/GitCloneWorkerThrowableSafetyTest.kt` | `doWork() rethrows CancellationException rather than converting it to Result.failure() when the wrapped clone delegate is cancelled` | Unit | Error/ordering path — the mandatory `catch(CancellationException)` guard ahead of `catch(Throwable)` |
| REQ-5 (Story 3.1.6) | `git/GitCloneWorkerThrowableSafetyTest.kt` | `a GitCloneWorker built with TestListenableWorkerBuilder, stopped via WorkManager.cancelUniqueWork mid-clone, invokes onStopped() and tears down the notification exactly once` | Integration | |
| REQ-5 (Story 3.1.7) | `git/WorkManagerSyncSchedulerWatchdogTest.kt` (new) | `the watchdog surfaces "Sync may have been stopped by battery optimization…" when a GitCloneWorker WorkInfo has been RUNNING for longer than STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES` | Unit | Happy path — pre-mortem.md P1 #1's silent-kill case, which has no exception for `classifyGitFailure` to route at all |
| REQ-5 (Story 3.1.7) | `git/WorkManagerSyncSchedulerWatchdogTest.kt` | `the watchdog does not fire for a GitCloneWorker WorkInfo that is RUNNING but still within the threshold` | Unit | Error/false-positive path |
| REQ-6 (Story 4.1.1) | `git/JvmGitRepositoryTest.kt` | `the ProgressMonitor forwards CloneProgress(phase="Receiving objects", completed=42, totalWork=100) when JGit calls beginTask then update(42)` | Unit | Happy path |
| REQ-6 (Story 4.1.1) | `git/JvmGitRepositoryTest.kt` | `ProgressMonitor.update() before any beginTask() call reports CloneProgress with totalWork=0 (indeterminate) rather than throwing on an unset title` | Unit | Error/boundary path |
| REQ-6 (Story 4.1.1) | `git/JvmGitRepositoryTest.kt` | `a real JGit clone against a local fixture repo drives at least one non-zero CloneProgress callback through clone()'s onProgress parameter` | Integration | |
| REQ-6 (Story 4.1.2) | `git/GitTransportRetryStateTest.kt` (new) | `onStateChange emits Attempting then Retrying(attempt=1,max=4,...) on a Transient failure, matching the real attempt sequence` | Unit | Happy path |
| REQ-6 (Story 4.1.2) | `git/GitTransportRetryStateTest.kt` | `onStateChange emits NonRetryableFailure directly from Attempting with no intervening Retrying state when the first failure classifies Permanent` | Unit | Error path |
| REQ-6 (Story 4.1.2) | `git/GitSyncServiceTest.kt` | `a StubGitRepository configured to fail twice then succeed drives the observed GitTransportRetryState sequence Attempting → Retrying(1) → Retrying(2) → Idle/Success through GitSyncService` | Integration | **(depends on Story 6.1.1's StubGitRepository failure-sequence support)** |
| REQ-6 (Story 4.1.3) | `ui/screens/git/GitSetupStep5RetryStateTest.kt` (new, androidUnitTest/Robolectric per CLAUDE.md's Compose-behavior guidance) | `CloneProgressRow renders primary text "Cloning your graph…" and secondary text "Reconnecting… Attempt 2 of 4" when retryState=Retrying(2,4,null)` | Unit | Happy path |
| REQ-6 (Story 4.1.3) | `ui/screens/git/GitSetupStep5RetryStateTest.kt` | `CloneProgressRow renders the Exhausted warning-styled copy with a "Try again" action, not NonRetryableFailure's error styling, when retryState=Exhausted(reason)` | Unit | Error path |
| REQ-6 (Story 4.1.3) | `ui/screens/git/GitSetupStep5RetryStateTest.kt` | `CloneProgressRow shows no attempt counter and error-styled copy pointing at Step 3 when retryState=NonRetryableFailure("auth")` | Unit | Third state — no integration test needed (pure Compose state rendering, no store/external call) |
| REQ-6 (Story 4.1.4) | `ui/screens/git/GitSetupStep5CancelTest.kt` (new, androidUnitTest/Robolectric) | `tapping Cancel while cloneInProgress invokes onCancelClone(), and the row shows "Cancelled — your progress is saved. Resume anytime from Step 5."` | Unit | Happy path |
| REQ-6 (Story 4.1.4) | `git/GitTransportRetryTest.kt` | `beforeRetry cleanup does not run following a manual cancel — the partial target directory is left intact` (Task 4.1.4d) | Unit | Error/negative path |
| REQ-6 (Story 4.1.4) | `git/AndroidGitCloneWorkerLauncherTest.kt` | `AndroidGitCloneWorkerLauncher.cancel() calls WorkManager.cancelUniqueWork(workName), and the resulting CanceledException classifies Cancelled through the real retry loop` | Integration | |
| REQ-5 (Story 4.1.5) | `git/GitCloneWorkerNotificationTest.kt` (new, androidUnitTest/Robolectric) | `getForegroundInfo() builds a notification titled "Syncing {graph display name}" with body text mirroring the current GitTransportRetryState's Step 5 copy exactly` | Unit | Happy path |
| REQ-5 (Story 4.1.5) | `git/GitCloneWorkerNotificationTest.kt` | `the notification is rebuilt as dismissible ("tap to retry") rather than staying setOngoing(true) when the terminal state is Exhausted or NonRetryableFailure` | Unit | Error/terminal path |
| REQ-5 (Story 4.1.5) | `git/GitCloneWorkerNotificationTest.kt` | `NotificationManagerCompat.notify() is called with a stable notification ID across multiple GitTransportRetryState transitions, not a new notification per event, verified via Robolectric's ShadowNotificationManager` | Integration | |
| REQ-5 (Story 5.1.1) | `git/GitSyncBusyCounterTest.kt` (extend) | `gitSyncBusyCounter.isBusy is true for the duration of GitSyncService.fetchOnly(graphId)` | Unit | Happy path |
| REQ-5 (Story 5.1.1) | `git/GitSyncBusyCounterTest.kt` | `gitSyncBusyCounter.isBusy returns to false even when fetchOnly(graphId) throws (finally-equivalent guarantee)` | Unit | Error path — no integration test needed (in-memory counter only) |
| REQ-5 (Story 5.1.2) | `git/WorkManagerSyncSchedulerUniqueWorkTest.kt` (new) | `AndroidGitCloneWorkerLauncher computes the same workNameFor(graphId) as WorkManagerSyncScheduler's periodic job` | Unit | Happy path |
| REQ-5 (Story 5.1.2) | `git/WorkManagerSyncSchedulerUniqueWorkTest.kt` | `GitCloneWorker enqueued via beginUniqueWork(..., APPEND_OR_REPLACE, ...) for a graph with no existing periodic job still runs immediately` | Unit | Error/edge path — new-graph case must not be falsely blocked |
| REQ-5 (Story 5.1.2) | `git/WorkManagerSyncSchedulerUniqueWorkTest.kt` | `two work requests enqueued under the same workNameFor(graphId) — one periodic GitSyncWorker, one GitCloneWorker — do not run concurrently` | Integration | Real WorkManager test driver |
| REQ-5 (Story 5.1.3) | `git/WorkManagerSyncSchedulerSlowPathYieldTest.kt` (new) | `GitSyncWorker's slow path returns Result.success() as a no-op when getWorkInfosForUniqueWork reports a RUNNING GitCloneWorker for graphId` | Unit | Happy path |
| REQ-5 (Story 5.1.3) | `git/WorkManagerSyncSchedulerSlowPathYieldTest.kt` | `GitSyncWorker's slow path proceeds with its standalone fetch as before when no GitCloneWorker is RUNNING for graphId` | Unit | Error/regression path — must not over-suppress |
| REQ-5 (Story 5.1.3) | `git/WorkManagerSyncSchedulerSlowPathYieldTest.kt` | `a RUNNING GitCloneWorker and a process-restarted GitSyncWorker slow path for the same graphId never both invoke AndroidGitRepository.fetch(), driven through WorkManager's real test infrastructure` | Integration | |
| REQ-7 (Story 6.1.1) **(FOUNDATIONAL)** | `git/testsupport/StubGitRepository.kt` (extend) + `git/testsupport/StubGitRepositoryFailureSequenceTest.kt` (new) | `StubGitRepository configured to fail twice with a Transient cause then succeed drives Attempting → Retrying(1) → Retrying(2) → Success` | Unit | Happy path |
| REQ-7 (Story 6.1.1) **(FOUNDATIONAL)** | `git/testsupport/StubGitRepositoryFailureSequenceTest.kt` | `StubGitRepository configured to always throw NoRemoteRepositoryException drives exactly one Attempting → NonRetryableFailure transition, never Retrying` | Unit | Error path — no integration test needed; this story *is* the test-support fake. Stories 1.2.2, 3.1.2, 4.1.2, and 6.1.3 above depend on it existing first |
| REQ-1 / REQ-7 (Story 6.1.2) **(FOUNDATIONAL)** | new test-support fixture + `git/GitTransportFaultInjectionTest.kt` (new) | `a real JGit fetch()/clone() against a local file:// remote wrapped to throw mid-transfer after N objects produces an exception that classifyGitFailure classifies Transient and that runGitTransportOpWithRetry actually retries` | Integration | No unit variant — the whole point is exercising a real JGit-thrown exception, per `research/pitfalls.md` §5.2's "small number of slow/flaky-tolerant integration tests, not the primary coverage mechanism." Story 6.1.4 below reuses this fixture |
| REQ-7 (Story 6.1.3) | `git/GitCloneWorkerFastFailIntegrationTest.kt` (new, androidUnitTest/Robolectric) | `GitCloneWorker.doWork() against a StubGitRepository configured to always throw an auth-shaped failure returns Result.failure() after exactly one attempt, and the foreground notification is dismissed/converted to failure state immediately, not after any backoff delay` | Integration | No unit variant — end-to-end by design. **(depends on Story 6.1.1's StubGitRepository)** |
| REQ-7 (Story 6.1.4) | `git/AndroidGitRepositoryStorageGuardTest.kt` (extend) | `ensureFresh() correctly reports the shadow tree as fresh after a retried clone completes successfully following a simulated interruption between Git.cloneRepository().call() and syncShadowAfterInitOrClone` | Unit | Happy path |
| REQ-7 (Story 6.1.4) | `git/AndroidGitRepositoryStorageGuardTest.kt` | `ensureFresh() does not report the shadow tree as fresh based on stale/absent manifest state left by the failed first attempt, before the retry completes` | Unit | Error path |
| REQ-7 (Story 6.1.4) | `git/AndroidGitRepositoryStorageGuardTest.kt` | `a full retried-clone-then-ensureFresh() sequence against a real shadow-worktree-backed AndroidGitRepository fixture never accepts a half-synced state as fresh` | Integration | May reuse Story 6.1.2's fault-injection fixture to simulate the interruption point deterministically |
| **Migration** (plan.md's Migration Plan) | `db/MigrationRunnerApplyAllTest.kt` (extend) | `git_config_clone_depth_state migration adds clone_depth_state and shallow_depth to an existing pre-migration git_config row, defaulting to NONE and null respectively, with no data loss` | Migration | Forward-apply-on-existing-row proof — see note below on why no down-migration test is designed |
| **Migration** (plan.md's Migration Plan) | `db/MigrationRunnerSchemaSyncTest.kt` (extend) | `MigrationRunnerSchemaSyncTest confirms git_config_clone_depth_state's two AddColumn ops are registered in MigrationRunner.all, keeping SteleDatabase.sq's CREATE TABLE IF NOT EXISTS in sync` | Migration | Structural-invariant enforcement (this test file already exists and enforces exactly this rule for every table — extending it costs nothing new) |

**Note on the Migration test naming (Step 5 instruction):** plan.md's own Migration Plan states
this migration is forward-only/irreversible — SQLite has no `DROP COLUMN` in the versions this
app targets, and the stated rollback procedure is `git revert` of the PR (per `CLAUDE.md`'s
Release Process), not a down-migration script. A literal `migration_should_be_reversible` test
would contradict the plan's own approach, so it is not designed. The closest equivalent this
repo's convention actually supports — and what the first Migration row above tests — is: an
older binary (post-revert) simply never selects the two new columns, so nothing needs to be
undone; the "no data loss on an existing row" test is the correct proof for a forward-only,
additive migration.

---

## Fault-Injection Infrastructure — Foundational Dependency

Per requirements.md's Feasibility Risks ("No existing regression test exercises a mid-transfer
network interruption on either platform — new test infrastructure … is itself nontrivial work"),
Phase 6/Epic 6.1 builds two layers, and **both are prerequisites other stories' tests already
reference above, not just Phase 6's own tests**:

1. **Story 6.1.1 — `StubGitRepository` failure-sequence fake** (fast, fully deterministic, no
   real socket). Required by: Story 1.2.2's full-stack integration test, Story 3.1.2's
   `GitCloneWorker` integration test, Story 4.1.2's `GitSyncService`-level state-sequence
   integration test, and Story 6.1.3's end-to-end fast-fail test. None of those can be
   implemented before this fake's configurable failure-sequence mode exists.
2. **Story 6.1.2 — real JGit-transport-level fault injection** (a local `file://` remote wrapped
   to throw mid-transfer). Required by: Story 6.1.4's fixture reuse, and it is the only test in
   this whole plan that proves `classifyGitFailure`/`runGitTransportOpWithRetry` against an
   actual JGit-thrown exception rather than a hand-mocked one — every other "Transient" unit test
   in this document uses a constructed `TransportException(cause=SocketException(...))`, which is
   a reasonable-but-unverified assumption about what JGit actually throws until this story closes
   the gap.

Implementation order must put both ahead of the stories that depend on them, even though Phase 6
is drawn last in the dependency diagram — the diagram's own note says "Story 6.1.1 can start once
Epic 1.1/1.2 land," i.e., early, in parallel with Phase 2/3, specifically so these two rows are
ready before Phase 4's integration tests need them.

---

## UX Acceptance Tests

20 criteria from `design/ux.md` Step 3, across the 6 surfaces (A: in-progress status, B: terminal
failure, C: cancel, D: notification, E: deferred/no wireframe, F: logs — covered under
Observability, not here). Per `CLAUDE.md`'s testing-best-practices section, Compose-behavior
tests (dialog gating, text assertions, click handlers) that don't need true pixel rendering
belong in `androidUnitTest`/Robolectric, not `jvmTest`/Roborazzi — this app is Compose
Desktop+Android, not a browser app, so no Playwright/browser tooling applies here.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Cancel an in-progress clone in 1 click, no confirmation dialog | `GitSetupStep5CancelTest.kt` | `Cancel button is always visible and enabled during Attempting/Retrying/ResumingDeepen, with no confirmation dialog shown` | Robolectric | Render Step5 with `cloneInProgress=true`; find node with text "Cancel"; `performClick()`; assert `onCancelClone` invoked exactly once and no dialog composable is present |
| 2. Manual retry after `Exhausted` in 1 click | `GitSetupStep5RetryStateTest.kt` | `tapping Try again after Exhausted re-invokes the same performCloneAndSave entry point, not a special-cased retry function` | Robolectric | Render with `retryState=Exhausted(reason)`; find "Try again"; click; assert the same save-callback fires |
| 3. `NonRetryableFailure` recovery reaches the named step in 2 clicks | `GitSetupStep5RetryStateTest.kt` + manual click-through | `NonRetryableFailure copy names the specific step ("Step 3") the user must fix, and Back is enabled as the exit path` | Robolectric (copy assertion) + Manual (actual wizard-step navigation) | Render with `NonRetryableFailure("auth")`; assert rendered text contains "Step 3"; assert Back button enabled; manual: click Back, confirm the wizard lands on/near Step 3 |
| 4. Resuming a cancelled clone takes 1 click ("Save configuration" again) | `GitSetupStep5CancelTest.kt` | `after Cancelled, tapping Save configuration re-enters Attempting and resumes from the preserved checkpoint, not a fresh dialog or 0% state` | Robolectric | Drive to `Cancelled` state; click "Save configuration"; assert `performCloneAndSave` invoked again with the same `localPath`/checkpoint, no intermediate confirmation UI |
| 5. `Retrying` shows exact primary/secondary copy, never raw exception text | `GitSetupStep5RetryStateTest.kt` | `Retrying(2,4,null) renders primary "Cloning your graph…" and secondary "Reconnecting… Attempt 2 of 4", with no SocketException/exception-message substring anywhere in the row` | Robolectric | Render with `Retrying(attempt=2,max=4,progress=null)`; assert exact primary/secondary text nodes; assert absence of substrings `"Exception"`, `"Software caused"` |
| 6. `Exhausted` shows exact copy, warning (not error-red) icon, "Try again" | `GitSetupStep5RetryStateTest.kt` | `Exhausted renders "Couldn't finish after 4 attempts. Check your connection and try again — your progress is saved." with a Warning-tinted icon and a Try again action` | Robolectric | Render with `Exhausted("...")`; assert exact copy; assert icon `contentDescription="Warning"`; assert "Try again" button present |
| 7. `NonRetryableFailure` shows exact copy, error icon, no attempt counter | `GitSetupStep5RetryStateTest.kt` | `NonRetryableFailure("auth") renders the auth-specific copy with an Error-tinted icon and no attempt-count composable anywhere in the row` | Robolectric | Render with `NonRetryableFailure("auth")`; assert copy; assert icon `contentDescription="Error"`; assert no node matching "Attempt \d of \d" |
| 8. Cancelling shows exact copy | `GitSetupStep5CancelTest.kt` | `after cancellation, the row shows exactly "Cancelled — your progress is saved. Resume anytime from Step 5."` | Robolectric | Trigger cancel; assert exact text node |
| 9. No raw JGit/transport exception text anywhere in Step 5 or the notification | `GitSetupStep5RetryStateTest.kt` + `GitCloneWorkerNotificationTest.kt` | `every GitTransportRetryState variant's rendered Step 5 text and notification body is free of TransportException/SocketException class names and bare HTTP status numbers` | Robolectric (parameterized over all 6 `GitTransportRetryState` cases) | For each state, render Step5 and build the notification; assert rendered/notification text never contains raw exception class names or the literal strings `"401"`/`"403"`/`"404"` outside authored copy |
| 10. `Attempting`/`Retrying`/`ResumingDeepen` exit path = Cancel | `GitSetupStep5CancelTest.kt` | `Cancel is the only enabled action, and Back/Save remain disabled, across Attempting, Retrying, and ResumingDeepen` | Robolectric (parameterized over the 3 states) | For each state, assert Cancel enabled, Back/Save disabled (existing `busy` gating) |
| 11. `Exhausted` exit paths = Try again or Back | `GitSetupStep5RetryStateTest.kt` | `Exhausted enables both Try again and Back, with Save configuration also re-enabled` | Robolectric | Render `Exhausted`; assert all three buttons enabled |
| 12. `NonRetryableFailure` exit path = Back; Save remains available | `GitSetupStep5RetryStateTest.kt` | `NonRetryableFailure enables Back and Save, but shows no Try again button` | Robolectric | Render `NonRetryableFailure`; assert Back/Save enabled, "Try again" absent |
| 13. `Cancelled` exit paths = Save (resume) or Back (abandon) | `GitSetupStep5CancelTest.kt` | `Cancelled enables both Save configuration and Back` | Robolectric | Render post-cancel state; assert both enabled |
| 14. Live region semantics on the progress container | `GitSetupStep5RetryStateTest.kt` | `the CloneProgressRow container carries Modifier.semantics { liveRegion = LiveRegionMode.Polite }, matching FolderSyncReconciliationProgress.kt/StorageMoveProgressDialog.kt's existing convention` | Robolectric (Compose semantics tree assertion) | Render row; walk the semantics tree; assert `LiveRegionMode.Polite` present at the same container level as the two cited precedents, and not `Assertive` |
| 15. Live-region announcement rate — at most once per attempt transition / every ~10% resume progress | Manual (TalkBack/VoiceOver/NVDA checklist) | `listening through a simulated flaky clone, the number of live-region announcements matches the attempt-count transitions, not every raw ProgressMonitor tick` | Manual | Per ux.md's own note: a human tester drives a simulated flaky clone with a screen reader running and counts announcements against attempt-count transitions, not raw progress ticks — not automatable via Robolectric |
| 16. Cancel button has a distinct accessible label ("Cancel clone") | `GitSetupStep5CancelTest.kt` | `the in-progress-clone Cancel button's semantic label is "Cancel clone", distinct from the pre-existing Test Connection row's "Cancel" label, when both are present in the same traversal` | Robolectric | Render Step5 with both the Test Connection row and an in-progress clone visible; assert two distinguishable nodes via `onNodeWithContentDescription` |
| 17. Terminal-state icons have distinct `contentDescription` values | `GitSetupStep5RetryStateTest.kt` | `Exhausted's icon has contentDescription="Warning"; NonRetryableFailure's icon has contentDescription="Error", matching the existing TestResultRow failure semantics` | Robolectric | Render each terminal state; assert the respective `contentDescription` |
| 18. All interactive elements are standard, keyboard/screen-reader reachable controls | `GitSetupStep5CancelTest.kt` + `GitSetupStep5RetryStateTest.kt` + manual Desktop check | `Cancel and Try again are semantics-Role.Button nodes with no bare clickable Icon substitutes` | Robolectric (semantics role assertion) + Manual (Desktop Tab/Shift+Tab traversal spot check) | Assert `SemanticsProperties.Role == Role.Button` on both; manually Tab through Step 5 on Desktop to confirm focus order includes both |
| 19. Color contrast ≥ 4.5:1 for warning/error tokens | Manual (contrast-checker tool) | `colorScheme.tertiary (warning) and colorScheme.error (non-retryable) against their surrounding background meet ≥4.5:1 in the app's actual rendered Material3 theme, in both light and dark mode` | Manual | Per ux.md's own note, Compose doesn't statically guarantee this — spot-check the rendered theme's tertiary/error tokens with a contrast checker (e.g. Android Studio Layout Inspector color picker or a browser contrast tool against exported swatches) |
| 20. Foreground notification copy avoids jargon (graph name, not URL/path; plain-language state) | `GitCloneWorkerNotificationTest.kt` | `notification title/body across all GitTransportRetryState variants never contains a raw URL, local file path, or exception class name — only the graph's display name and authored state copy` | Robolectric (parameterized over all states) | For each state, build the notification; assert title/body contain the graph display name and authored copy only, never `"http"`, `"git@"`, or a filesystem path substring |

**Manual/instrumented-test dependency (requirements.md's Feasibility Risks):** this session has
no attached Android device/emulator (`adb devices` returned empty per requirements.md). Criteria
15 and 19 above are manual by nature (screen-reader listening, rendered-theme contrast). In
addition, the *end-to-end* proof of Surface D's background-survival flow — the app actually
staying alive through a real Doze/OEM-battery-manager kill while backgrounded — cannot be
verified by Robolectric (which doesn't model real process/Doze lifecycle). See the mandatory
**Phase 3 Manual Release Gate** below — this is not an optional nice-to-have check, it is the
gate that determines whether Phase 3 (and therefore this project's actual fix for the original
bug report) is done.

---

## Phase 3 Manual Release Gate — Real-Device OEM Validation (mandatory, not optional)

Per pre-mortem.md P1 #1: `GitCloneWorker`'s `setForeground(dataSync)` foreground-service survival
must not be marked "done" on CI/Robolectric signal alone. Real OEM battery managers (Samsung,
Xiaomi, Huawei, OnePlus, Asus, …) kill network/CPU for a foreground service that isn't on their
own allowlist, regardless of correct `setForeground()`/notification/manifest code — this is a
distinct mechanism from Android's own Doze, and a correctly-coded foreground service does not
defeat it. Robolectric does not model OEM battery-manager kills or real process lifecycle at all,
so no unit/Robolectric test in the Requirement → Test Mapping or UX Acceptance Tests tables above
can substitute for this gate.

**Gate procedure** (must be performed and its result recorded before Phase 3 — and therefore this
project — is marked done):

1. **Devices**: at least 2 real physical devices from different, aggressive-battery-management
   OEMs — e.g. one Samsung (One UI battery optimization) and one Xiaomi/Huawei (MIUI/EMUI
   aggressive app-freeze). Emulators do not exercise real OEM battery-manager code and do not
   satisfy this gate.
2. **Procedure per device**: start a clone of a large-enough graph that the transfer takes several
   minutes (use this project's own reference "large graph" scale, per `research/`'s benchmark
   fixtures, or throttle the network to force a multi-minute transfer). Background the app (home
   button) and lock the screen. Leave the device screen-off/backgrounded for the full multi-minute
   duration of the transfer — do not touch the device.
3. **Verification**: confirm via `dumpsys activity services` / `dumpsys jobscheduler` (showing the
   foreground service still alive) *and* by observing the clone actually complete (Step 5 or the
   notification reaches a terminal success state) that the transfer ran to completion while
   backgrounded — not just that no crash occurred.
4. **Pass/fail recording**: record the result (device model, OS/OEM version, pass/fail, and
   `dumpsys` evidence or a screen recording) against this gate before Phase 3 is considered
   verified. A failure on either device blocks marking Phase 3 (and the originating issue) done —
   it does not block Phase 1-2, which are independently shippable and were already gated on their
   own automated coverage.

**Explicit insufficiency statement**: green CI, green `bazel test //kmp:jvm_tests`/
`//kmp:business_tests`, and 100% Robolectric pass rate on every `GitCloneWorker`/notification test
in this document are **not** sufficient evidence that Phase 3 works. They prove the code is
*correctly written*; they do not prove it *survives the OS/OEM kill it exists to survive*. Only
this gate proves that.

---

## Test Stack

- **Unit**: `kotlin.test` (JUnit5 runner) with `kotest-assertions-core`/`kotest-property` as
  plain libraries (no Kotest Spec runner, per `CLAUDE.md`) — `commonTest`/`businessTest` for
  platform-agnostic logic (`classifyGitFailure`, `RetryPolicies`, `GitTransportRetryState`
  production, `GitConfig` mapping).
- **Integration**: same `kotlin.test` runner, but exercising real collaborators — a real JGit
  `CloneCommand`/`FetchCommand` against a local `file://` fixture repo (`jvmTest`), a real
  SQLDelight in-memory/temp-file DB (`businessTest`/`jvmTest`), and Robolectric + WorkManager's
  own `TestListenableWorkerBuilder`/`WorkManagerTestInitHelper` test driver (`androidUnitTest`)
  for `GitCloneWorker`/`WorkManagerSyncScheduler` coordination. Test doubles: `StubGitRepository`
  (Story 6.1.1) for fast/deterministic sequences; the Story 6.1.2 fault-injecting local transport
  for the one real-JGit-exception proof.
- **E2E / UX**: Robolectric (`androidUnitTest`) for Compose-behavior acceptance criteria per
  `CLAUDE.md`'s explicit guidance (this is a Compose Desktop+Android app, not a browser app — no
  Playwright/`ui-playwright` model applies); a small manual checklist for screen-reader
  announcement pacing, rendered-theme contrast, and real-device Doze/foreground-service survival
  (none of which Robolectric can verify).

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM/Android | `./gradlew jacocoTestReport` → check `kmp/build/reports/jacoco/` | ≥80% line, with 100% of `classifyGitFailure`'s taxonomy branches and `runGitTransportOpWithRetry`'s Transient/Permanent/Cancelled/Exhausted paths explicitly covered (branch coverage, not just line) |
| Bazel | `bazel test //kmp:business_tests` and `//kmp:jvm_tests` (with `scripts/jvm-display-check.sh`) | All new/extended test files pass; `//kmp:business_tests` classpath-resource bundling (per this project's MEMORY.md note) picks up every new `businessTest` file automatically |

- All public service methods touched by this plan (`GitOperationSupport.runGitTransportOp*`,
  `GitRepository.clone/fetch/push/unshallow/merge`, `GitCloneWorkerLauncher.launchClone`): happy
  path + error path covered per the table above.
- All external integrations (JGit transport, SQLDelight `git_config`, Android WorkManager/
  notifications): unit mocked (via `StubGitRepository`/fakes) + at least one integration test
  each, per the table above.
- UX acceptance criteria: all 20 from `design/ux.md` Step 3 have a corresponding automated test
  or an explicitly named manual step (criteria 15, 19, and the Surface D real-device flow).
- Migration: the additive `git_config` migration is covered against both a fresh install (no
  pre-existing row) and an existing pre-migration row, plus the structural
  `MigrationRunnerSchemaSyncTest` enforcement — no down-migration test, per the plan's stated
  forward-only/`git revert` rollback convention.

---

## Summary

- **32/32 plan.md stories** mapped to a requirement bullet and at least one designed test (100%
  story coverage) — includes Story 3.1.7 (stuck-`WorkInfo` watchdog), added per pre-mortem.md
  P1 #1.
- **7/7 requirements.md In Scope bullets** (REQ-1…REQ-7) have at least one story, and at least one
  test, mapped to them (100% requirement coverage).
- **Test counts by type** (requirement-mapping table only, excludes the UX table):
  - Unit: 63 (includes the 2 new Story 3.1.7 watchdog tests and the 1 new Story 2.1.5
    "wrong-but-present merge base" test, both added per pre-mortem.md's P1 items)
  - Integration: 24 (2 of which — Stories 6.1.1's tests and 6.1.2 — are foundational and block
    the 4 other integration tests marked "depends on Story 6.1.1"/"FOUNDATIONAL" above)
  - Migration: 2
  - Subtotal: 89 designed test cases
- **Phase 3 Manual Release Gate**: 1 mandatory real-device (≥2 OEM) gate, distinct from and not
  substitutable by any automated test above — see the dedicated section preceding UX Acceptance
  Tests. Phase 3 (and the originating bug report) is not done until this gate passes.
- **UX acceptance tests**: 20 (17 Robolectric-automatable, 1 mixed Robolectric+manual, 2 fully
  manual, plus one explicitly named real-device dependency for Surface D's full background-
  survival flow).
- **Migration test**: Yes — 2 tests (forward-apply-on-existing-row + schema-sync structural
  enforcement); no down-migration test, by design, per the plan's stated forward-only/`git
  revert` rollback convention.
