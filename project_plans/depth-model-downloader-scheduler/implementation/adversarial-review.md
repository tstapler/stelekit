CONCERNS

# Adversarial and architecture review: depth-model-downloader-scheduler

No blocker. Fix C1-C4 in the plan before coding. Nothing was run; interleavings are INFERRED from source.

`DMD` = `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`. Tests are under `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/`.

## Concerns

**C1. An earlier call's guard-cancel can still kill the retry.** `DMD:131-144`, `WorkManagerTaskScheduler.kt:243-250`.
1. Call A is inside `runOnce`, holding `operationLock`.
2. The user cancels (job1 waits for the lock), then taps "Download again". Call B joins job1.
3. A's `runOnce` returns. A sees the new generation and creates job2 (`join job1; cancel`).
4. job1 finishes. B and job2 both resume. B joined only job1, so its `runOnce` can run before job2's cancel, which then kills it.

Recommendation: join until stable, in both enqueue paths: `do { val p = pendingCancel; p?.join() } while (p !== pendingCancel)`. For Main-thread callers, A creates job2 before B rechecks. Add this interleaving to the new test.

**C2. `downloadNowAnyway()` has no generation check.** `DMD:157-165`. The plan says "a cancel arriving during the join is caught by the existing generation check", which is true only of `downloadModel()`. A cancel during this join does not stop the later `runOnce(Replace)`. That can leave an any-network (metered) job running after the user cancelled. `DMD:162` can also overwrite `Cancelled` with `Downloading` (pre-existing). The window is narrow.

Recommendation: capture the generation before the join. After `runOnce`, if it changed, call `requestCancel()` and return without writing state. Test it.

**C3. Nothing checks that the root passes its scheduler.** `OnnxMonocularDepthEstimator(context, scheduler = null)` plus the lazy fallback at `DMD:82-84` means a missing argument compiles and passes every test. That is the main goal of the item (backlog AC-5 and AC-6).

Recommendation: declare `scheduler: BackgroundTaskScheduler?` with no default on the estimator. Its only construction site is `SteleKitApplication.kt:270`. The downloader's own default can stay for tests.

**C4. The verification scope misses tests.**
- `ModelDownloadUiTest` is in `ui/`, not `platform/ml/`, so a `platform.ml` filter skips it.
- `jobs/WorkManagerJobRegistryTest.kt:74,87,100` drives `downloadModel()` and is not listed.
- `DepthModelDownloaderTest`, `RaceTest` and `AbsentRaceTest` are not in `AllAndroidUnitTests.kt` (only EndToEnd, Task, Presentation and OneShotSkip are, lines 82-85). `ModelDownloadUiTest` is excluded too (line 190).

Recommendation: name the Gradle `testDebugUnitTest` command with filters for `platform.ml.*`, `ui.ModelDownloadUiTest` and `jobs.WorkManagerJobRegistryTest`. A green Bazel run does not prove backlog AC-7.

## Minor

- **Join placement.** It must be inside the `try` (`DMD:130`). Between lines 127 and 130, a cancelled caller leaks `inFlight`. Mark `pendingCancel` `@Volatile`, since it is read outside `@Synchronized`.
- **"`runOnce` waits" test.** Start `downloadModel()` with `CoroutineStart.UNDISPATCHED`, or it can pass without the join. The `statusFlow == null` branch has no test.
- **Wider async windows (pitfalls §5) are not recorded in the plan.**
  - Process death before the launched cancel leaves the job persisted.
  - `.part` is deleted under a live worker (`DMD:186`, `ModelDownloadTask.kt:67-69`), which can turn `Cancelled` into `Failed`.
  - While a retry waits in the join, `inFlight > 0` lets old-job emissions rewrite state (`DMD:211-239`). A late old `CANCELLED` could resolve the retry as cancelled.

  Accept these explicitly or handle them.
- **Test leakage.** `DepthModelDownloaderTest.kt:136-139` now leaves a cancel in flight. It could hit the next test's job under the same unique name (flake risk).
- **Second adapter.** It would use the default worker resolver, unlike `SteleKitApplication.kt:334`. This is harmless for `model-download`. Passing the same resolver, or sharing one lazy field, avoids a later git-sync mis-binding (`WorkManagerTaskScheduler.kt:27-28`).
- **Document drift.**
  - `requirements.md` has 5 ACs; the backlog item has 8, and the plan's "AC-7" is backlog numbering.
  - `requirements.md` AC-3 says the downloader no longer reaches into WorkManager. The fallback still does, which backlog AC-4 allows.
  - `validate.md` V1 and V4 contradict the plan. Mark them superseded.
  - The `getCapabilities` stub edit to `AbsentRaceTest` should be stated in the PR.

## Architecture

- **Layering and port boundary: sound.** Commands go through the port. Observation stays Android-only in `androidMain`, and `Flow<List<WorkInfo>>` does not leak into `commonMain`.
- **Composition root: sound.** The task is registered (`SteleKitApplication.kt:240`) and `SteleKitContext.init` runs (`:260`) before the estimator is built (`:270`). Adapter instances share `operationLock`. See C3.
- **Over-building: none significant.** The ordered-cancel helper is needed once cancel is asynchronous.
- **Existing tests:** with the `getCapabilities` stub, I found none that fails deterministically.
