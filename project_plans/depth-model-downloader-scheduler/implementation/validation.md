# Validation: depth-model-downloader-scheduler

Criteria are the backlog item's eight. `Port` = `DepthModelDownloaderCancelPortTest` (new).

| # | Criterion | Proof |
|---|---|---|
| 1 | `cancelDownload()` cancels through the port | Port: `cancelDownload_cancelsThroughThePort` |
| 2 | guard-cancel goes through the port | Port: `cancelDuringRunOnce_cancelsAgain` |
| 3 | availability guard uses `capabilities.schedulesWork` | Port: `schedulerThatCannotSchedule_returnsLeft` |
| 4 | no `workManagerOrNull` in the downloader | grep of `DepthModelDownloader.kt`; existing WorkManager-backed tests |
| 5 | estimator accepts and passes the scheduler | compile; read (no default, so omission fails the build) |
| 6 | root builds and passes the scheduler | `:androidApp` compile; read |
| 7 | existing tests pass | Gradle `testDebugUnitTest`: `platform.ml.*`, `ui.ModelDownloadUiTest`, `jobs.WorkManagerJobRegistryTest` |
| 8 | new test, registered | Port class listed in `AllAndroidUnitTests.kt`; Bazel android unit tests |

Added by review, beyond the criteria:

- Port: `runOnce_waitsForAPendingCancel`, `downloadModel_waitsForAPendingCancel` (ordering; fail without the wait)
- Port: `cancelWhileWaiting_neverEnqueues`, `cancelDuringDownloadNowAnyway_cancelsAgain`,
  `unavailableWorkManager_returnsLeft`

Not testable here: real-device WorkManager timing, and process death between `cancelDownload()` and the
launched cancel (accepted risk in `plan.md`).
