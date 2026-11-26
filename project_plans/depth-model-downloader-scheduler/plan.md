# Plan: depth-model-downloader-scheduler

Route `DepthModelDownloader`'s cancel calls through `BackgroundTaskScheduler.cancel(key)` and
remove all direct `workManagerOrNull` uses from `DepthModelDownloader.kt`.

---

## Implementation steps (dependency order)

### Step 1 — Add `workInfoFlow` seam to `DepthModelDownloader` and remove `workManagerOrNull` from the `init` block

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`

Add `workInfoFlow: Flow<List<WorkInfo>>? = null` as the last parameter of the `internal constructor`
(after `afterStateWrite`). The `init` block currently reads:

```kotlin
init {
    scope.launch {
        val workManager = workManagerOrNull(context) ?: return@launch
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).collect { infos -> apply(infos) }
    }
}
```

Replace with:

```kotlin
init {
    scope.launch {
        val flow = workInfoFlow
            ?: WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
        flow.collect { infos -> apply(infos) }
    }
}
```

Add `import androidx.work.WorkManager` (it is already an indirect dependency via the `WorkInfo`
import; it may already be present — check before adding). Remove `import dev.stapler.stelekit.jobs.workManagerOrNull`
only once all other uses are gone (Step 2 and 3 must precede the import removal).

**Why `WorkManager.getInstance` instead of `workManagerOrNull`?** `workManagerOrNull` is a
helper in `jobs.WorkManagerAccess` that the downloader should no longer depend on. `WorkManager.getInstance`
throws `IllegalStateException` if WorkManager is not initialized; because Jetpack App Startup
initializes WorkManager before `Application.onCreate` returns, the window where this could throw is
zero in production. In tests, callers that inject `workInfoFlow` bypass this call entirely, and
tests that use the no-arg constructor (`DepthModelDownloader(context)`) already initialize WorkManager
via `WorkManagerTestInitHelper` in `setUp()`.

---

### Step 2 — Replace the `workManagerOrNull` guard in `downloadModel()` with `taskScheduler.capabilities.schedulesWork`

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`, line 124

Current:
```kotlin
workManagerOrNull(context)
    ?: return DomainError.SensorError.HardwareUnavailable("Background downloads are unavailable right now").left()
```

Replace with:
```kotlin
if (!taskScheduler.capabilities.schedulesWork)
    return DomainError.SensorError.HardwareUnavailable("Background downloads are unavailable right now").left()
```

`taskScheduler.capabilities.schedulesWork` is `false` on `NoopTaskScheduler` and `true` on
`WorkManagerTaskScheduler`. The behavioral difference (live WorkManager check vs. static capability
flag) is documented in research Q4 and is accepted: `WorkManagerTaskScheduler`'s own methods guard
against an uninitialized WorkManager internally, so the worst case is a `Downloading` state that
sits unresolved rather than an immediate error — and the window is zero in production.

---

### Step 3 — Replace the `workManagerOrNull` guard-cancel in `downloadModel()` with the port call

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`, line 143

Current:
```kotlin
if (cancelGeneration.get() != generation) {
    workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
    return DomainError.SensorError.HardwareUnavailable("Depth model download cancelled").left()
}
```

Replace with:
```kotlin
if (cancelGeneration.get() != generation) {
    scope.launch { taskScheduler.cancel(ModelDownloadTask.KEY) }
    return DomainError.SensorError.HardwareUnavailable("Depth model download cancelled").left()
}
```

`taskScheduler.cancel` is `suspend`; the guard-cancel is inside the `suspend fun downloadModel()`,
so it *could* be called directly as `taskScheduler.cancel(ModelDownloadTask.KEY)` without a
`scope.launch` wrapper.

Using `scope.launch` is preferred here for two reasons: it is consistent with Step 4 (which has
no suspend alternative), and it avoids blocking the return path with a suspend call that acquires
the scheduler's `operationLock`. The fire-and-forget is safe because: (a) `cancel` is idempotent
and guarded, (b) `cancelGeneration` is the actual synchronization mechanism — that integer was
already incremented by `cancelDownload()` and the `downloadModel()` caller has already been
returned the cancellation error, so the WorkManager cancel timing is not critical to correctness.

**Alternative (direct suspend call):** `taskScheduler.cancel(ModelDownloadTask.KEY)` without
`scope.launch` is also correct here because `downloadModel()` is itself a suspend function. Use
it if consistency with the existing `taskScheduler.runOnce` call style is preferred. The plan
records both; the implementer picks one and applies it consistently to Step 4 for the
non-suspend `cancelDownload()`.

---

### Step 4 — Replace the `workManagerOrNull` cancel in `cancelDownload()` with the port call

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`, line 185

Current:
```kotlin
fun cancelDownload() {
    cancelGeneration.incrementAndGet()
    settleJob?.cancel()
    workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
    runCatching { File(modelFile.parentFile, "${modelFile.name}.part").delete() }
    if (!isModelReady()) _modelState.value = ModelState.Cancelled
}
```

Replace:
```kotlin
fun cancelDownload() {
    cancelGeneration.incrementAndGet()
    settleJob?.cancel()
    scope.launch { taskScheduler.cancel(ModelDownloadTask.KEY) }
    runCatching { File(modelFile.parentFile, "${modelFile.name}.part").delete() }
    if (!isModelReady()) _modelState.value = ModelState.Cancelled
}
```

`cancelDownload()` is non-suspend (the `DownloadableDepthModel` interface in `commonMain` declares
`fun cancelDownload()`, called from a Compose lambda in `ScreenRouter.kt` line 594). Changing the
signature to `suspend` would require touching the `commonMain` interface, the
`OnnxMonocularDepthEstimator` override, `ScreenRouter`, and all test call sites — a scope
violation per `requirements.md`. `scope.launch` is the established pattern. The `scope` has a
`CoroutineExceptionHandler`, so a scheduler failure does not propagate uncaught.

---

### Step 5 — Remove the now-unused `workManagerOrNull` import

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`, line 27

After Steps 1–4 are applied, `workManagerOrNull` is no longer referenced. Remove:
```kotlin
import dev.stapler.stelekit.jobs.workManagerOrNull
```

If `WorkManager` is not already imported (it is used in Step 1), add:
```kotlin
import androidx.work.WorkManager
```

---

### Step 6 — Update `OnnxMonocularDepthEstimator` to accept and thread through the scheduler

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/OnnxMonocularDepthEstimator.kt`

Change the class constructor from:
```kotlin
class OnnxMonocularDepthEstimator(
    private val context: Context,
) : MonocularDepthEstimator, DownloadableDepthModel {
    ...
    val downloader: DepthModelDownloader = DepthModelDownloader(context)
```

To:
```kotlin
class OnnxMonocularDepthEstimator(
    private val context: Context,
    scheduler: dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler? = null,
) : MonocularDepthEstimator, DownloadableDepthModel {
    ...
    val downloader: DepthModelDownloader = DepthModelDownloader(context, scheduler = scheduler)
```

Add import `import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler` at the top of the
file.

Using a `null` default preserves the existing no-arg call path (`OnnxMonocularDepthEstimator(context)`)
for callers not updated in this change and for tests. When `null`, `DepthModelDownloader`'s own
lazy fallback constructs a `WorkManagerTaskScheduler`, matching today's behavior. The
`SteleKitApplication` wiring in Step 7 passes a non-null scheduler, fulfilling AC-2.

---

### Step 7 — Wire the root scheduler in `SteleKitApplication`

**File:** `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`, around line 270

The existing line:
```kotlin
SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator(applicationContext)
```

Must be replaced with a call that passes a `WorkManagerTaskScheduler`. Because
`startScheduleStartup()` constructs its scheduler as a local variable inside a coroutine body
(and cannot be reused without refactoring that function), build a second adapter instance with the
same `taskRegistry` — the `WorkManagerTaskScheduler`'s own class-level comment explicitly
sanctions this ("a second adapter instance shares both WorkManager and ledger state"):

```kotlin
val settings = PlatformSettings()
val downloadStore = dev.stapler.stelekit.jobs.schedule.TaskSettingsStore(
    settings,
    dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime.legacySeed,
    taskRegistry,
)
val downloadScheduler = dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler(
    context = applicationContext,
    registry = taskRegistry,
    store = downloadStore,
    ledger = dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger(settings),
)
SensorModule.monocularDepthEstimator = OnnxMonocularDepthEstimator(applicationContext, downloadScheduler)
```

This block runs synchronously in `onCreate` before line 270, inside the existing try/catch that
already wraps the entire block. `taskRegistry` is a class-level `val` (line 66), available at
this point. The `PlatformSettings()` construction is cheap (also done by `startScheduleStartup`
separately).

**Ordering requirement:** `SteleKitContext.init(this)` must precede this block (it already does,
at line 260). `PlatformSettings()` depends on `SteleKitContext` being initialized.

---

## File-level change list

| File | Lines touched | Nature of change |
|---|---|---|
| `kmp/src/androidMain/kotlin/.../platform/ml/DepthModelDownloader.kt` | constructor params (after line 67), init block (107–112), downloadModel guard (124–125), downloadModel guard-cancel (143–145), cancelDownload (185), import line 27 | Add `workInfoFlow` param, replace 3× `workManagerOrNull`, remove import |
| `kmp/src/androidMain/kotlin/.../platform/ml/OnnxMonocularDepthEstimator.kt` | constructor (line 41–43), downloader field (line 53) | Add `scheduler` param, thread it to `DepthModelDownloader` |
| `androidApp/src/main/kotlin/.../SteleKitApplication.kt` | around line 270 | Build `downloadScheduler`; pass to `OnnxMonocularDepthEstimator` |
| `kmp/src/androidUnitTest/kotlin/.../platform/ml/DepthModelDownloaderCancelPortTest.kt` | new file | AC-5 cancel-via-port test |

No changes to `DownloadableDepthModel` (commonMain interface), `ScreenRouter`, any test logic, or
any existing test file beyond potential no-op `workInfoFlow = null` additions if the named-parameter
style is used in fixture calls (not required since `null` is the default).

---

## Test plan

### Existing tests (AC-4): no test logic changes expected

**`DepthModelDownloaderTest`** — uses `DepthModelDownloader(context)` (no-arg constructor path).
Step 1 replaces `workManagerOrNull(context)` in `init` with `WorkManager.getInstance(context)`.
Tests already call `WorkManagerTestInitHelper.initializeTestWorkManager` in `setUp()`, so
`WorkManager.getInstance` does not throw. No test change needed.

**`DepthModelDownloaderRaceTest`** and **`DepthModelDownloaderAbsentRaceTest`** — use
`ModelDownloadFixture`, which constructs `DepthModelDownloader` with the five-parameter internal
constructor. The new `workInfoFlow` parameter has a `null` default; no call-site change required.
Fixture helper in `ModelDownloadTestSupport.kt` line 86 does not need updating.

**`ModelDownloadEndToEndTest`** — verify it also uses `ModelDownloadFixture` or the no-arg
constructor and confirm no structural change is needed. (Expected: no change.)

### New test (AC-5): `DepthModelDownloaderCancelPortTest`

**Location:** `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderCancelPortTest.kt`

**What it proves:** `cancelDownload()` calls `BackgroundTaskScheduler.cancel(ModelDownloadTask.KEY)`,
not `WorkManager.cancelUniqueWork` directly.

**Pattern:** Use a fake/spy `BackgroundTaskScheduler` (implement via a simple class or Mockito)
that records `cancel` invocations. Inject it via the `internal constructor` alongside a
`MutableSharedFlow<List<WorkInfo>>()` as the `workInfoFlow` seam (so the downloader does not need
WorkManager at all). Call `cancelDownload()`. Assert the spy recorded one call with
`ModelDownloadTask.KEY`.

Skeleton:
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderCancelPortTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `cancelDownload routes through BackgroundTaskScheduler cancel`() = runBlocking {
        val cancelledKeys = mutableListOf<TaskKey>()
        val fakeScheduler = object : BackgroundTaskScheduler {
            override val capabilities = SchedulerCapabilities(schedulesWork = true)
            override suspend fun cancel(key: TaskKey) { cancelledKeys += key }
            // ... other no-op overrides
        }
        val workInfoFlow = MutableSharedFlow<List<WorkInfo>>()
        val downloader = DepthModelDownloader(
            context = context,
            scheduler = fakeScheduler,
            workInfoFlow = workInfoFlow,
        )
        downloader.cancelDownload()
        // Allow the scope.launch to execute
        yield(); yield()
        assertEquals(listOf(ModelDownloadTask.KEY), cancelledKeys)
    }
}
```

**Registration:** Add the class to `AllAndroidUnitTests.kt` and to `kmp/src/androidUnitTest/kotlin/BUILD.bazel`
per the CLAUDE.md instructions.

---

## Risk and edge cases

### R1 — Ordering: `PlatformSettings()` in `SteleKitApplication` requires `SteleKitContext`

`PlatformSettings()` calls into `SteleKitContext` (which must be initialized first). `SteleKitContext.init(this)`
runs at line 260, before the new block at line 270. The ordering is already correct. **No risk.**

### R2 — `scope.launch` for the guard-cancel in `downloadModel()` is fire-and-forget

The guard-cancel at line 143 fires `scope.launch { taskScheduler.cancel(ModelDownloadTask.KEY) }`
and immediately returns `Left` to the caller. The WorkManager cancellation happens asynchronously
in `scope`. Because `cancelGeneration` was already incremented by `cancelDownload()` before the
guard check ran, any subsequent WorkInfo emission in the `init`-block collector will apply the
generation check and decline to overwrite `Cancelled` state. The async launch is not racing with
the generation check: the generation has been read as "changed" at the guard, and the only job
this launch cancels is an orphan that `runOnce` just enqueued. **Risk: low.**

If direct `suspend` is used instead of `scope.launch` for the guard-cancel (Step 3 alternative),
the coroutine suspends to acquire `operationLock`, which is held for the duration of the cancel.
In a heavily contended test with many in-flight jobs this could add measurable latency to the
`downloadModel()` return path. The fire-and-forget is preferred.

### R3 — `scope.launch` in non-suspend `cancelDownload()` is fire-and-forget

`cancelDownload()` returns before `taskScheduler.cancel` completes (it launches into `scope`).
The race window: a caller that calls `cancelDownload()` and then immediately calls
`downloadModel()` again could have its second `runOnce` land before the cancellation completes.
In this case `runOnce(OneShotConflict.Keep)` re-attaches to the existing job (which is
`CANCELLING`); WorkManager will cancel and the next WorkInfo emission will reflect it.
`cancelGeneration` is incremented synchronously before the launch, so the second `downloadModel()`
call checks a fresh generation and would itself fire a cancel if it also races. The generation
mechanism is the true synchronization; the async cancel is a cleanup path. **Risk: low.**

### R4 — `WorkManager.getInstance(context)` in `init` throws if WorkManager is uninitialized

The only callers that reach the `init` block without the `workInfoFlow` seam are the no-arg
constructor path and the `internal constructor` with `workInfoFlow = null`. In production,
Jetpack App Startup initializes WorkManager before `Application.onCreate` returns, so
`WorkManager.getInstance` never throws in production. In tests, existing tests that use
`DepthModelDownloader(context)` already call `WorkManagerTestInitHelper.initializeTestWorkManager`
in `setUp()`. New tests (AC-5) inject the `workInfoFlow` seam and never reach this branch.
**Risk: low.** Document the assumption with a comment in the `init` block.

### R5 — Second `WorkManagerTaskScheduler` instance shares ledger state via `AppliedConfigLedger`

Both the `downloadScheduler` (new) and the scheduler inside `startScheduleStartup` construct
`AppliedConfigLedger(settings)` from their respective `PlatformSettings()` instances.
`AppliedConfigLedger` reads and writes from `Settings` (backed by `SharedPreferences`), so both
instances share the same persisted ledger state. This is the documented behavior: the
`WorkManagerTaskScheduler` class comment states a second instance "shares both WorkManager and
ledger state". **No risk.**

### R6 — `WorkManagerTaskScheduler.cancel` clears the ledger before calling `cancelUniqueWork`

This is a behavioral improvement over the current direct `cancelUniqueWork` calls: `ledger.clear(key)`
now runs for every cancel, keeping `AppliedConfigLedger` consistent with WorkManager state. No
existing test asserts the ledger is *not* cleared on cancel, so this cannot break existing tests.
**No risk; positive behavior change.**

### R7 — `DepthModelDownloaderTest` uses `DepthModelDownloader(context)` (no-arg constructor)

The no-arg constructor delegates to `this(context, scheduler = null)`. With Step 7 applied,
`OnnxMonocularDepthEstimator` is the production caller and passes a scheduler; the downloader's
no-arg constructor is only used directly in `DepthModelDownloaderTest`. Those tests initialize
WorkManager in `setUp()`, so both `WorkManager.getInstance` (Step 1) and the lazy
`WorkManagerTaskScheduler` construction (Steps 2–4) work without issue. **No risk.**

---

## Adversarial review

**Gap: Step 1 — `WorkManager.getInstance` can still throw in some test scenarios**

The plan states "existing tests initialize WorkManager in `setUp()`," but `DepthModelDownloaderRaceTest`
and `DepthModelDownloaderAbsentRaceTest` use `ModelDownloadFixture`, which injects `scheduler` and
`store` but does NOT inject `workInfoFlow`. That means the `init` block will call
`WorkManager.getInstance(context)` in those tests too. Reading `DepthModelDownloaderRaceTest.kt`
would confirm whether it also initializes WorkManager. The research says `ModelDownloadFixture`
constructs a real `WorkManagerTaskScheduler` backed by Robolectric's WorkManager, so WorkManager
IS initialized in those tests. But the plan should make this explicit.

**Mitigation:** Note in Step 1 that all existing test classes that use the `internal constructor`
without `workInfoFlow` (i.e., `ModelDownloadFixture`) rely on WorkManager being initialized by the
test fixture's own `WorkManagerTestInitHelper` call. Confirm this is the case in
`DepthModelDownloaderRaceTest.setUp()` before shipping.

**Gap: Step 7 — `PlatformSettings()` constructs from scratch; two instances in `onCreate`**

`startScheduleStartup()` also calls `PlatformSettings()` (line 320), and Step 7 adds a third
construction at line 270 (before `startScheduleStartup`). `PlatformSettings()` is a lightweight
wrapper; the CLAUDE.md notes `SteleKitContext.init(context)` must precede it, which it does.
No functional gap, but the plan should note that this is not the last `PlatformSettings()`
construction in `onCreate` to avoid a reviewer reading it as a problem.

**Gap: AC-5 test skeleton uses `yield(); yield()` to let the launch execute**

`scope.launch { taskScheduler.cancel(...) }` dispatches to `PlatformDispatcher.IO`
(the scope's dispatcher in `DepthModelDownloader`). Under Robolectric's default scheduler, `yield()`
in `runBlocking` may not advance a coroutine on a different dispatcher. The test skeleton should
instead use `advanceUntilIdle()` from `kotlinx-coroutines-test` or switch the scope's dispatcher
to `UnconfinedTestDispatcher` via a test seam. The current skeleton would likely flake.

**Mitigation:** In the AC-5 test, either (a) add `testDispatcher: CoroutineDispatcher? = null` as
a seam parameter to `DepthModelDownloader` and default its `scope` to the injected dispatcher in
tests, or (b) pass the fake scheduler's `cancel` as a synchronous operation and verify it was
called by directly checking `cancelledKeys` after allowing enough yields in a `runBlocking` context
with a timeout. Option (b) is simpler: use `Dispatchers.Unconfined` on the scope or observe that
`scope.launch` with a `SupervisorJob + PlatformDispatcher.IO` will execute before `runBlocking`
returns if no real coroutine blocking is involved — but this is not guaranteed. The safest fix is
to add a `testScope: CoroutineScope? = null` seam to the `internal constructor` so the test can
inject a scope backed by `UnconfinedTestDispatcher`.

**Gap: Step 3 — guard-cancel with `scope.launch` vs direct `suspend` call**

Both options are presented but neither is committed to. Pick one before coding; use **`scope.launch`**
for consistency with Step 4 (which has no alternative). Record this choice in the commit message.

**Gap: `AllAndroidUnitTests.kt` and `BUILD.bazel` registration for AC-5 test**

The plan mentions these but does not give the exact location of `AllAndroidUnitTests.kt` or the
Bazel target name. The implementer should run `scripts/kmp-module-tests-sync.py` after adding the
class to `AllAndroidUnitTests.kt` and confirm the class lands in the right per-module target.

**No fatal gaps found.** The plan is implementable as written, with the mitigations above applied
during coding.
