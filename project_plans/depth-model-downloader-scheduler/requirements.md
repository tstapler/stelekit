# Requirements: depth-model-downloader-scheduler

**Backlog item:** Route DepthModelDownloader through the shared scheduler and port-level cancel  
**Follow-up from:** #642 (merged as 9656e5e3f6)

---

## Problem Statement

`DepthModelDownloader` currently builds its own private `WorkManagerTaskScheduler` via lazy
initialization and cancels in-flight work by calling `WorkManager.cancelUniqueWork` directly in
two places:

1. `cancelDownload()` (line 185) — user-initiated cancel  
2. `downloadModel()` (line 143) — guard-cancel when a concurrent cancel arrived mid-enqueue

Both bypass `BackgroundTaskScheduler.cancel(key)`, so the `AppliedConfigLedger` and any future
scheduler adapter never see the cancellation. Additionally, the downloader uses
`workManagerOrNull(context)` in four places, coupling it directly to WorkManager internals rather
than the scheduling port.

---

## Acceptance Criteria

### AC-1: Cancel routes through the port
Both `cancelDownload()` and the in-`downloadModel()` guard-cancel call
`BackgroundTaskScheduler.cancel(ModelDownloadTask.KEY)` instead of
`workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)`.

### AC-2: Downloader receives the root's scheduler
`DepthModelDownloader` accepts the root-level `BackgroundTaskScheduler` instance from
`SteleKitApplication` instead of constructing its own. `OnnxMonocularDepthEstimator` is the
intermediary and must be updated to accept and thread through the scheduler.

### AC-3: `workManagerOrNull` removed from `DepthModelDownloader`
No `workManagerOrNull` calls remain inside `DepthModelDownloader.kt`. Specifically:
- The WorkManager availability guard (line 124) is replaced via
  `taskScheduler.capabilities.schedulesWork` or equivalent port-level check.
- The WorkInfo status observation (init block, line 109) is replaced by injecting a
  `Flow<List<WorkInfo>>` seam parameter so the downloader no longer reaches into WorkManager
  directly.

### AC-4: Downloader tests continue to pass
All existing tests in `DepthModelDownloaderTest`, `DepthModelDownloaderRaceTest`,
`DepthModelDownloaderAbsentRaceTest`, and `ModelDownloadEndToEndTest` pass without modification
to test logic (fixture helpers may be updated for new constructor parameters).

### AC-5: New cancel-via-port test
A new test asserts that `cancelDownload()` invokes `BackgroundTaskScheduler.cancel` (not
`WorkManager.cancelUniqueWork` directly), verifying the port contract.

---

## Scope

**In scope:**
- Routing cancel calls through `BackgroundTaskScheduler.cancel(key)`
- Removing `workManagerOrNull` from `DepthModelDownloader`
- Threading the root's scheduler through `OnnxMonocularDepthEstimator` to `DepthModelDownloader`
- Adding a `workInfoFlow` seam to `DepthModelDownloader` for testability
- Wiring `SteleKitApplication.onCreate` to pass the root scheduler to `OnnxMonocularDepthEstimator`

**Out of scope:**
- Adding new methods to the `BackgroundTaskScheduler` interface (not needed; `cancel` already exists)
- Changing `DepthModelDownloadWorker` or `ModelDownloadTask`
- Altering the status-observation semantics (WorkInfo flow continues to drive `modelState`)
- Any changes to the iOS/JVM/NoopTaskScheduler path

---

## Context

- `BackgroundTaskScheduler.cancel(key: TaskKey)` already exists on the interface and is
  implemented by `WorkManagerTaskScheduler` (calls `cancelUniqueWork` via the same
  `workManagerOrNull` guard it uses for all ops).
- `DepthModelDownloader` already accepts `scheduler: BackgroundTaskScheduler?` in its `internal
  constructor`; the lazy fallback only applies when the production no-arg path is used.
- `SteleKitApplication.startScheduleStartup()` builds a `WorkManagerTaskScheduler` for the
  `ScheduleStartup` reconciler but does not expose it; a scheduler built from `taskRegistry`
  can be reused for `OnnxMonocularDepthEstimator`.
- `cancelDownload()` is a non-suspend function; it must `scope.launch { taskScheduler.cancel(…) }`
  rather than calling the suspend port method directly.
