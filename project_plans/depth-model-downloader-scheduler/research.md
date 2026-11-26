# Research: depth-model-downloader-scheduler

## Q1 — Port cancel signature: idempotency, ledger update, WorkManager-absent behavior

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerTaskScheduler.kt`

```
75  override suspend fun cancel(key: TaskKey) = guarded("cancel ${key.workName()}") {
76      ledger.clear(key)
77      pausedKeys.remove(key)
78      cancelUnique(key)
79  }
```

```
237 private fun cancelUnique(key: TaskKey) {
238     workManagerOrNull(context)?.cancelUniqueWork(key.workName())
239 }
```

The `guarded` wrapper (lines 243–251) acquires the process-wide `operationLock` mutex and catches every `Throwable` except `CancellationException`, logging and returning normally. All three inner calls (`ledger.clear`, `pausedKeys.remove`, `cancelUnique`) are no-ops when the key is absent from their respective stores. WorkManager's own `cancelUniqueWork` is idempotent for work names that do not exist. **Conclusion: `cancel` is idempotent and safe to call multiple times.**

**Ledger update:** `ledger.clear(key)` runs unconditionally before the WorkManager call, so the `AppliedConfigLedger` always reflects the cancellation even if WorkManager itself throws.

**WorkManager absent:** `workManagerOrNull(context)` returns `null` when WorkManager is not initialized. The safe-call `?.cancelUniqueWork(...)` silently skips the platform call. The ledger and `pausedKeys` entries are still cleared. No error is thrown and no visible state is left behind.

---

## Q2 — cancelDownload() non-suspend constraint: call-chain analysis

`cancelDownload()` is a **non-suspend** function at three layers:

1. `DownloadableDepthModel` interface (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ml/MonocularDepthEstimator.kt`, line 79): `fun cancelDownload()` — no `suspend`, in a `commonMain` interface.
2. `OnnxMonocularDepthEstimator` override (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt`, line 65): `override fun cancelDownload() = downloader.cancelDownload()` — delegation only.
3. `ScreenRouter.kt` (commonMain, line 594): calls `it.cancelDownload()` inside a Compose lambda (non-suspend context).
4. All test call sites in `DepthModelDownloaderTest`, `ModelDownloadTaskTest`, `ModelDownloadUiTest` call it synchronously (no coroutine required).

Changing the signature to `suspend` would require touching `DownloadableDepthModel` (commonMain interface), `OnnxMonocularDepthEstimator` (androidMain override), `ScreenRouter` (commonMain compose lambda), and every test call site — a wide cross-cutting change.

**Conclusion:** `scope.launch { taskScheduler.cancel(...) }` is the correct pattern. The existing `scope` field in `DepthModelDownloader` (`CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + CoroutineExceptionHandler { ... })`, lines 98–101) already has a `CoroutineExceptionHandler`, so a fire-and-forget launch from `cancelDownload()` is safe. `cancelDownload()` stays non-suspend.

---

## Q3 — WorkInfo observation seam: injection approach

**Current init block** (`DepthModelDownloader.kt`, lines 107–112):

```kotlin
init {
    scope.launch {
        val workManager = workManagerOrNull(context) ?: return@launch
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).collect { infos -> apply(infos) }
    }
}
```

**Existing seam pattern** in the `internal constructor`: nullable parameters with lazy production fallbacks:
- `scheduler: BackgroundTaskScheduler? = null` → lazy `WorkManagerTaskScheduler(...)`  (line 82–84)
- `store: TaskSettingsStore? = null` → lazy `TaskSettingsStore(...)`  (line 79–81)
- `network: NetworkSnapshotProvider? = null` → lazy `AndroidNetworkSnapshotProvider(context)` (line 71)

The `workInfoFlow` seam follows the same nullable-parameter pattern. Proposed addition to the `internal constructor`:

```kotlin
workInfoFlow: Flow<List<WorkInfo>>? = null,
```

The init block becomes:

```kotlin
init {
    scope.launch {
        val flow = workInfoFlow ?: workManagerOrNull(context)
            ?.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME) ?: return@launch
        flow.collect { infos -> apply(infos) }
    }
}
```

`WorkInfo` is `androidx.work.WorkInfo` — an Android type. `DepthModelDownloader` already lives in `androidMain`, so the parameter type does not introduce a new Android dependency into `commonMain`.

In tests, a `MutableSharedFlow<List<WorkInfo>>()` can be passed without constructing a WorkManager instance at all.

---

## Q4 — WorkManager availability guard: `schedulesWork` as replacement

**Current guard** (`DepthModelDownloader.kt`, line 124):

```kotlin
workManagerOrNull(context)
    ?: return DomainError.SensorError.HardwareUnavailable("Background downloads are unavailable right now").left()
```

`SchedulerCapabilities.schedulesWork` is defined in `TaskModels.kt` line 97–100:

```kotlin
/** False for the no-op adapter; the Background tasks UI section is hidden. */
val schedulesWork: Boolean,
```

`WorkManagerTaskScheduler.CAPABILITIES` sets `schedulesWork = true` (`WorkManagerTaskScheduler.kt`, line 262). The KDoc explicitly identifies the `NoopTaskScheduler` as the case where this is `false`.

`taskScheduler` is already accessible as a property at the point of the guard (`DepthModelDownloader.kt` line 82, `by lazy`). The replacement is:

```kotlin
if (!taskScheduler.capabilities.schedulesWork)
    return DomainError.SensorError.HardwareUnavailable("Background downloads are unavailable right now").left()
```

**Behavioral difference:** `workManagerOrNull(context)` performs a live runtime check that WorkManager is initialized in the current process. `schedulesWork` is a static capability flag on the adapter. If `WorkManagerTaskScheduler` is used but WorkManager has not yet been initialized at the time `downloadModel()` is called, `schedulesWork = true` would pass the guard but the subsequent `taskScheduler.runOnce(...)` would silently no-op (every public method in `WorkManagerTaskScheduler` checks `workManagerOrNull(context) ?: return` internally). This is acceptable: the downloader transitions to `Downloading` state and remains there until the next WorkInfo emission, rather than returning an immediate error. In practice WorkManager is initialized by `WorkManagerInitializer` (Jetpack App Startup) before `Application.onCreate` returns, so the window is zero in production.

---

## Q5 — Root scheduler wiring: reuse vs. build new

**Construction order in `SteleKitApplication.onCreate`:**
- Line 270: `SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator(applicationContext)` — estimator constructed.
- Line 297: `startScheduleStartup()` — coroutine launched.

**`startScheduleStartup()` (lines 317–346):** Builds a `WorkManagerTaskScheduler` as a local `val` inside the coroutine lambda (line 328–335). It is **not** stored as an instance field on `SteleKitApplication`. It is passed only to `ScheduleStartup(...).run()`.

**`taskRegistry` (line 66):** IS an instance field — `val taskRegistry = dev.stapler.stelekit.jobs.schedule.TaskRegistry()` — and the class-level KDoc notes it is "shared with MainActivity's scheduler."

**Conclusion:** The scheduler built inside `startScheduleStartup` cannot be directly reused without refactoring, because it is a local to the coroutine. Two viable approaches:

1. **Build a second `WorkManagerTaskScheduler` for the downloader** using the same `taskRegistry`. `WorkManagerTaskScheduler`'s own comment states: "a second adapter instance (e.g. a feature's own) shares both \[WorkManager and ledger state\]", and the process-wide `operationLock` serializes all operations across adapter instances. The root would build:
   ```kotlin
   val downloadScheduler = WorkManagerTaskScheduler(applicationContext, taskRegistry, store, AppliedConfigLedger(settings))
   SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator(applicationContext, downloadScheduler)
   ```
   This is explicit and requires minimal code movement.

2. **Lift the scheduler to a field on `SteleKitApplication`** — extract the `WorkManagerTaskScheduler` construction before line 270 and pass the same instance to both `ScheduleStartup` and `OnnxMonocularDepthEstimator`. More elegant long-term but involves restructuring `startScheduleStartup`.

Both are within scope. Option 1 (new instance, same `taskRegistry`) is lower-risk because `startScheduleStartup` is not modified and there is an explicit design precedent for multiple adapter instances.

---

## Q6 — Existing test fixture pattern

**File:** `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadTestSupport.kt`

`ModelDownloadFixture` constructs `DepthModelDownloader` at line 86:

```kotlin
val downloader = DepthModelDownloader(context, scheduler, store, NetworkSnapshotProvider { network }, notificationsOff = { false })
```

The five injected seams are: `context` (required), `scheduler: BackgroundTaskScheduler` (a real `WorkManagerTaskScheduler` backed by Robolectric's WorkManager), `store: TaskSettingsStore`, `NetworkSnapshotProvider { network }` (wrapping a mutable `var` for per-test control), `notificationsOff = { false }`.

**Impact of adding `workInfoFlow`:** The `ModelDownloadFixture` constructor call would add `workInfoFlow = null` (or nothing, since default is `null`). With `null`, the downloader uses the real `workManagerOrNull(context)?.getWorkInfosForUniqueWorkFlow(...)` — which works because `WorkManagerTestInitHelper.initializeTestWorkManager` is called in `DepthModelDownloaderTest.setUp()`. Existing tests in `DepthModelDownloaderTest` use `DepthModelDownloader(context)` directly (no-arg constructor path, not `ModelDownloadFixture`), so they are unaffected by the new parameter.

The new cancel-via-port test (AC-5) would use `ModelDownloadFixture` and pass a `MutableSharedFlow<List<WorkInfo>>()` to avoid waiting on WorkManager's own flow emission, verifying the `scheduler.cancel()` call directly.

---

## Q7 — WiringCompletenessTest impact

**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringAudit.kt`, lines 18–35.

The `SEAMS` list is:
```
BackgroundSyncScheduler, BackgroundTaskScheduler, AssetPipelinePlugin,
ConsoleCommand, MeasurementDeviceFactory, WaybackMachineService,
CameraProvider, TextMetricsProvider, SketchWriterLease, BrowserPersistence,
SketchDurability, PageLifecycle, PalmClassifier, PointerCancelDetector,
EdgeGestureExclusion, CameraLensCatalog
```

`DepthModelDownloader`, `OnnxMonocularDepthEstimator`, `MonocularDepthEstimator`, and `DownloadableDepthModel` are **not** in `SEAMS`. The wiring-completeness gate does not track them.

`OnnxMonocularDepthEstimator` is already constructed by the root at `SteleKitApplication.kt` line 270, so it already satisfies any potential `BackgroundTaskScheduler` construction check for the second adapter instance: `WorkManagerTaskScheduler` (which implements `BackgroundTaskScheduler`) is constructed there today (inside `startScheduleStartup`) and would continue to be.

**Wiring allowlist** (`kmp/src/businessTest/resources/wiring-allowlist.txt`): No entry for any depth-model or ONNX class. The allowlist contains only iOS stubs, benchmark entry points, and two false-positive entries. No action needed.

**Conclusion:** This change has no interaction with `WiringCompletenessTest` or the allowlist.
