# Plan: depth-model-downloader-scheduler

Route `DepthModelDownloader`'s cancels through `BackgroundTaskScheduler.cancel(ModelDownloadTask.KEY)`,
remove `workManagerOrNull` from it, and give it the root's scheduler.

Supersedes the triage draft, which `../research/pitfalls.md` showed to be wrong on two points
(test proxy NPE, cancel/enqueue ordering).

## Design

### `DepthModelDownloader` (androidMain)

1. **Status seam.** New last constructor parameter `workInfoFlow: Flow<List<WorkInfo>>? = null`, and
   `private val statusFlow by lazy { workInfoFlow ?: try { WorkManager.getInstance(context)
   .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME) } catch (e: IllegalStateException) { log; null } }`.
   Declared before `init`; `init` collects `statusFlow` if non-null.
2. **Availability guard.** `if (!taskScheduler.capabilities.schedulesWork || statusFlow == null) return Left(...)`.
   The second term keeps today's behaviour: `schedulesWork` is a constant `true` on the WorkManager
   adapter and its `runOnce` is a silent no-op without WorkManager, so the flag alone would hang the caller.
3. **Ordered port cancel.** Both cancel sites call one helper:
   ```kotlin
   @Synchronized
   private fun requestCancel() {
       val previous = pendingCancel
       pendingCancel = scope.launch { previous?.join(); taskScheduler.cancel(ModelDownloadTask.KEY) }
   }
   ```
   `downloadModel()` and `downloadNowAnyway()` capture `cancelGeneration`, then (inside their `try`)
   `awaitPendingCancel()` before `runOnce`: `do { val p = pendingCancel; p?.join() } while (p !== pendingCancel)`,
   so a guard-cancel created by an earlier call during the join is waited for too. `pendingCancel` is `@Volatile`.
   `downloadNowAnyway()` also re-cancels if the generation changed while it was enqueueing.
   Without the wait, "cancel, then download again" can run the old cancel after the new enqueue and kill it.
   A cancel arriving during the join is caught by the
   existing generation check. `cancelDownload()` stays non-suspend. The launch is on the downloader's
   scope, so a cancelled caller cannot skip the guard-cancel.

### `OnnxMonocularDepthEstimator`

New **required** parameter `scheduler: BackgroundTaskScheduler?` (no default, so the root cannot silently forget it),
passed to `DepthModelDownloader`.

### `SteleKitApplication.onCreate`

Build a `WorkManagerTaskScheduler(applicationContext, taskRegistry, TaskSettingsStore(...),
AppliedConfigLedger(settings))` after `SteleKitContext.init` and pass it to the estimator. A second
adapter instance is sanctioned: WorkManager, ledger and `operationLock` are shared.

## Tests

- New `androidUnitTest` `DepthModelDownloaderCancelPortTest` with a recording fake scheduler and an
  empty `workInfoFlow`, registered in `AllAndroidUnitTests.kt`:
  - `cancelDownload()` calls `cancel(ModelDownloadTask.KEY)`
  - a cancel landing during `runOnce` produces the guard-cancel too
  - `runOnce` waits for a pending cancel
  - `schedulesWork = false` returns `Left` without enqueueing
  - waits are on `CompletableDeferred` under `withTimeout`, never `yield()` or sleeps
- `DepthModelDownloaderAbsentRaceTest`: its scheduler is a `Proxy` that answers `null` to everything,
  including `capabilities`. **One stub change** is needed: answer `getCapabilities`. Scenario and
  assertions stay as they are. This is the only way to satisfy both AC-3 and AC-7.
- Verify with Gradle `testDebugUnitTest` (the `DepthModelDownloader*Test` classes are not in the Bazel
  suite): all of `platform.ml`, `ui.ModelDownloadUiTest` and `jobs.WorkManagerJobRegistryTest`.

## Out of scope

- `guarded` swallowing an enqueue failure (pre-existing; the caller waits on `Downloading`).
- Sharing one scheduler instance with `startScheduleStartup` (its git-sync worker resolver does not
  matter for `model-download`).

## Accepted risks

The port cancel is asynchronous, so WorkManager hears of it slightly later than before: process death in
that window leaves the job queued, and a worker finishing in it can report a failure over `Cancelled`.

## Tech debt disposition

Extend as-is.
