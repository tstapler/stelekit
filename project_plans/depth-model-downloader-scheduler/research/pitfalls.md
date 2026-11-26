# Pitfalls: downloader cancel via the scheduler port

Nothing was run (no Gradle or Bazel). VERIFIED = read in source. INFERRED = follows from that source plus JVM, coroutine or WorkManager semantics.

Abbreviations: `DMD` = `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`, `WMTS` = `.../androidMain/.../jobs/schedule/WorkManagerTaskScheduler.kt`, tests are under `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/`.

## 1. BLOCKER: plan Step 2 breaks `DepthModelDownloaderAbsentRaceTest` (AC-4)

- VERIFIED: the test's scheduler is a `java.lang.reflect.Proxy` that returns `null` for every method (`DepthModelDownloaderAbsentRaceTest.kt:56-59`). It is not the lazy `WorkManagerTaskScheduler`, contrary to plan.md's adversarial section and validate.md V4.
- INFERRED (certain): `taskScheduler.capabilities.schedulesWork` dereferences that `null` and throws NPE. `downloadModel()` never reaches `Downloading`, and the `withTimeout` at line 74 fails.
- Fix: add a constructor seam `canSchedule: () -> Boolean = { isWorkManagerReady(context) }` (`ScheduleStartup.kt:119`). No test edit, no `workManagerOrNull` in `DMD`, and it also fixes finding 3. AC-3 says "`schedulesWork` or equivalent port-level check", so record that this seam is the equivalent. The alternative is to make the proxy answer `getCapabilities`, which edits a test AC-4 says to leave alone.

## 2. HIGH: an async cancel can overtake the next enqueue

Today `cancelUniqueWork` is requested before `cancelDownload()` returns (`DMD:185`), so a later enqueue is ordered after it (INFERRED: WorkManager's serial task executor). With `scope.launch` (plan Step 4) the request is not made until an IO thread runs the coroutine and gets the process-wide `operationLock` (`WMTS:243-250, 268`). `runOnce` competes for the same lock.

Interleaving (cancel, then "Download again"):
1. `cancelDownload()` bumps the generation, launches the cancel and sets `Cancelled`.
2. `downloadModel()` reads the *new* generation (`DMD:128`), and its `runOnce(Keep)` gets the lock first.
3. `Keep` leaves the old job in place (or enqueues a new one if there was none).
4. The delayed cancel kills it. The guard at `DMD:142` does not fire, and the user's new tap ends as `Cancelled`.

`downloadNowAnyway()` (`DMD:157-165`) is exposed the same way: its `Replace` job can be killed, and it has no generation check.

- research.md/plan R3 ("generation is the true synchronization, risk low") is wrong: the generation only covers a cancel that lands *after* line 128.
- Existing test at risk: `ModelDownloadTaskTest.kt:177-185` cancels, awaits (resolves immediately from the synchronous state write), then starts again and asserts the new request is `UNMETERED`. If the cancel is late, `Keep` retains the old `CONNECTED` job, or the new one is cancelled. This test is not in AC-4's list but is in `AllAndroidUnitTests`. Flake is INFERRED.
- Fix: keep the handle and order on it. In `cancelDownload`: `pendingCancel = scope.launch(start = CoroutineStart.UNDISPATCHED) { taskScheduler.cancel(KEY) }`. In `downloadModel` (after capturing `generation`) and `downloadNowAnyway`, call `pendingCancel?.join()` before `runOnce`. The signature stays non-suspend. `UNDISPATCHED` queues on the mutex in call order; `join` is the actual guarantee. It runs the ledger writes on the UI thread when the lock is free; drop it if that is unwanted, since `join` still orders in-process calls.

## 3. MEDIUM: "WorkManager unavailable" turns from an error into a hang

- VERIFIED: today `downloadModel()` returns `Left` (`DMD:124-125`). `schedulesWork` is a constant `true` (`WMTS:262`), `runOnce` returns silently without WorkManager (`WMTS:62`), and `awaitTerminal` has no timeout (`DMD:190-191`).
- With plan Step 1 the collector also dies in `init`, so no emission can ever arrive. The caller suspends forever on `Downloading`. research.md's "until the next WorkInfo emission" does not hold.
- Production likelihood looks low (INFERRED: no custom WorkManager initializer found), but it is a behavior loss.
- Fix: the `canSchedule` seam from finding 1.
- Related, pre-existing: `guarded` swallows an enqueue failure (`WMTS:243-250`), which gives the same hang.

## 4. MEDIUM: do not use the plan's "direct suspend call" alternative for the guard-cancel

- The guard exists for a cancel that ran before the enqueue (`DMD:132-134`), so it is then the only thing that stops the orphan job.
- A bare `taskScheduler.cancel(KEY)` suspends cancellably on `operationLock.withLock`. If the caller's scope is cancelled (user cancels, then leaves the screen; the caller is `depthModelScope`, `ScreenRouter.kt:591`) while the lock is contended, the cancel is skipped and a ~100 MB job survives. validate.md V2 checked only deadlock.
- Fix: `withContext(NonCancellable) { taskScheduler.cancel(ModelDownloadTask.KEY) }`. It is preferable to the plan's `scope.launch`, which reintroduces finding 2 for an immediate retry.

## 5. LOW: the wider window before WorkManager hears of the cancel

All INFERRED. Each exists in a narrow form today; waiting for `operationLock` widens it.
- Process death before the launched cancel runs: the persisted job resumes after restart, although the user cancelled.
- `.part` is deleted synchronously (`DMD:186`) while the worker is still running. If it finishes first, `part.length()` is 0 and it reports a terminal "too small" failure (`ModelDownloadTask.kt:69`), which can replace `Cancelled` with `Failed`.
- A `RUNNING` progress emission while `inFlight > 0` can briefly rewrite `Cancelled` to `Downloading` (`DMD:232-239`).
- Mitigation: the `UNDISPATCHED` start from finding 2 closes most of this when the lock is free.

## 6. AC-5 test

- `yield(); yield()` does not wait for `Dispatchers.IO`. Have the fake's `cancel` complete a `CompletableDeferred<TaskKey>` and await it under `withTimeout`. The `testScope` seam proposed by plan.md and validate.md is not needed.
- It covers only one of AC-1's two paths. Add a guard-cancel case: a fake `runOnce` that calls `cancelDownload()` before returning, then assert two `cancel` calls.
- Register it in `AllAndroidUnitTests.kt`.
- AC-2 has no test: the plan's `scheduler = null` default means a root that forgets to pass it still compiles and silently uses the private fallback.

## Checked, no problem found

- `cancel` is idempotent, and `ledger.clear` on a one-shot key only writes empty entries (`WMTS:75-79`, `AppliedConfigLedger.kt:82-85`).
- Port cancel swallows WorkManager exceptions (`WMTS:243-250`), where today they would reach the click handler.
- A second adapter instance shares the lock (`WMTS:265-268`), and `runOnce` for `model-download` needs only the registry spec, registered before the estimator is built.
