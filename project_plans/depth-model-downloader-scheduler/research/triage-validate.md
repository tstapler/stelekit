# Validation: depth-model-downloader-scheduler

> Superseded: V1 and V4 are wrong. See `research/pitfalls.md` and `implementation/adversarial-review.md`.

Adversarial review of `plan.md` against the actual codebase.

Sources read:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/jobs/schedule/WorkManagerTaskScheduler.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/NoopTaskScheduler.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/jobs/schedule/InProcessTaskScheduler.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderRaceTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloaderAbsentRaceTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadTestSupport.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/ModelDownloadEndToEndTest.kt`
- `kmp/src/androidUnitTest/kotlin/BUILD.bazel`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`

---

## V1 — `scope.launch` thread safety in `cancelDownload()`

**Claim being validated:** The `cancelGeneration.incrementAndGet()` on line 183 of `DepthModelDownloader.kt` combined with the generation check at line 142 makes the asynchronous WorkManager cancel in `cancelDownload()` safe. The race window does not matter.

**Findings:**

`cancelDownload()` (actual lines 182–188) runs:
1. `cancelGeneration.incrementAndGet()` — atomic, synchronous.
2. `settleJob?.cancel()` — synchronous.
3. `scope.launch { taskScheduler.cancel(ModelDownloadTask.KEY) }` — dispatched to `Dispatchers.IO`, fire-and-forget.
4. File cleanup and state write.

The generation check in `downloadModel()` at line 142 reads `cancelGeneration.get()` and compares it to `generation` (captured at line 128 before `runOnce` was called). The increment at step 1 above is visible to any `downloadModel()` coroutine that checks the generation after `cancelDownload()` returns, because `AtomicInteger.incrementAndGet()` happens-before any subsequent `AtomicInteger.get()`.

Race scenario — `cancelDownload()` fires while `downloadModel()` is between `runOnce` returning and reaching the generation check:
- `cancelGeneration` is already incremented before `downloadModel()` reads it.
- `downloadModel()` sees the new generation, fires its own guard-cancel (also via `scope.launch`), and returns `Left`.
- Two `cancel` calls may land on the scheduler; `cancel` is idempotent (confirmed in research Q1 and in the `WorkManagerTaskScheduler.cancel` implementation: ledger clear and `cancelUniqueWork` are both no-ops for a key that is already absent).

The async cancel timing is not critical to correctness because `cancelGeneration` is the true synchronization mechanism. The WorkManager cancel is a cleanup path.

**Minor note:** The plan cites "line 182" for `cancelGeneration.incrementAndGet()`; actual line is 183. This is a one-line offset caused by an intervening blank line; it does not affect implementation.

**Verdict: CONFIRMED** — The generation mechanism provides sufficient synchronization. The fire-and-forget cancel is safe.

---

## V2 — Direct suspend call vs `scope.launch` for guard-cancel in `downloadModel()`

**Claim being validated:** Calling `taskScheduler.cancel(...)` as a direct suspend call inside `downloadModel()` (line 142) could deadlock on `operationLock`.

**Findings:**

`WorkManagerTaskScheduler.operationLock` is declared at line 268 of `WorkManagerTaskScheduler.kt`:

```kotlin
private val operationLock = Mutex()
```

It is a companion-object-level `Mutex` — process-wide, non-reentrant. It is acquired only inside the `guarded` helper (lines 243–251), which wraps every public method.

The sequence in `downloadModel()`:
1. Line 131: `taskScheduler.runOnce(...)` — calls `guarded()`, acquires `operationLock`, does work, releases it, returns.
2. Line 142: guard check executes — `runOnce` has already returned; the lock is free.
3. If we call `taskScheduler.cancel(...)` directly here, it calls `guarded()`, acquires `operationLock` (now free), does work, releases it.

There is no deadlock because `runOnce` and `cancel` are called sequentially, not from within the same `guarded` block. The lock is released between them.

The plan's risk section R2 describes "suspends to acquire operationLock, which is held for the duration of the cancel" — this means the calling coroutine acquires and holds the lock while `cancel` runs (ordinary mutex behavior), not that the lock is already held by this coroutine. The phrasing is accurate but could be misread as implying a deadlock. No deadlock is possible in this sequential pattern.

The only scenario where the direct call suspends is if another concurrent operation on a different coroutine (e.g., an `apply()` or a second `runOnce()`) happens to hold `operationLock` at the moment `cancel` is called. That is a contention wait, not a deadlock, and `cancel` is fast (ledger clear + `cancelUniqueWork`).

**Verdict: CONFIRMED** — The direct suspend call is safe. The plan's recommendation to use `scope.launch` for consistency with Step 4 (which has no suspend alternative) is sound. The plan does not need correction here.

---

## V3 — `WorkManager.getInstance(context)` exception behavior

**Claim being validated:** `WorkManager.getInstance(context)` can only throw `IllegalStateException`. The plan's proposal to call it without try/catch inside `scope.launch` is safe because the `CoroutineExceptionHandler` catches all Throwables.

**Findings:**

`workManagerOrNull` (in `WorkManagerAccess.kt`) catches only `IllegalStateException`:

```kotlin
internal fun workManagerOrNull(context: Context): WorkManager? = try {
    WorkManager.getInstance(context)
} catch (e: IllegalStateException) { ... null }
```

The Android API contract for `WorkManager.getInstance(context)` documents `IllegalStateException` as the exception when WorkManager is not initialized. In multi-process configurations on Android 12+ (API 31+), WorkManager may also throw `IllegalArgumentException` if the process name is not registered — but this is a misconfiguration scenario that does not occur in a correctly bootstrapped SteleKit app (Jetpack App Startup runs before `Application.onCreate` returns).

The plan replaces the `workManagerOrNull(context)` call in `init` with `WorkManager.getInstance(context)` inside `scope.launch`:

```kotlin
init {
    scope.launch {
        val flow = workInfoFlow
            ?: WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
        flow.collect { infos -> apply(infos) }
    }
}
```

The `scope` is:
```kotlin
CoroutineScope(
    SupervisorJob() + PlatformDispatcher.IO +
        CoroutineExceptionHandler { _, e -> logger.warn("Uncaught in DepthModelDownloader scope", e) },
)
```

Any `Throwable` thrown inside `scope.launch` that is not `CancellationException` propagates to the `CoroutineExceptionHandler`, which logs it as a warning. The coroutine ends; WorkInfo updates stop. This is a graceful degradation identical to the `workManagerOrNull` returning null — the downloader still functions for one-shot `downloadModel()` calls, just without WorkInfo-driven state updates.

Concern about catching `Throwable` being too broad: in this context (a long-lived observer coroutine), catching all Throwables is the right behavior — the app should not crash if WorkManager has an unexpected initialization failure. The `CoroutineExceptionHandler` logs it; engineering can investigate. No `Error` subclass that should propagate to the process handler is swallowed in a way that hides a real crash.

**Verdict: CONFIRMED** — The scope's exception handler catches all Throwables including non-`IllegalStateException` variants. Behavior is equivalent to or better than `workManagerOrNull`. No change needed.

---

## V4 — `schedulesWork` replacement correctness

**Claim being validated:** Replacing `workManagerOrNull(context) ?: return error` with `if (!taskScheduler.capabilities.schedulesWork) return error` is correct for all scheduler types.

**Findings:**

Actual `schedulesWork` values, verified from source:

| Scheduler | `schedulesWork` |
|---|---|
| `WorkManagerTaskScheduler` | `true` (companion `CAPABILITIES`, line 262 of `WorkManagerTaskScheduler.kt`) |
| `InProcessTaskScheduler` | `true` (line 78 of `InProcessTaskScheduler.kt`) |
| `NoopTaskScheduler` | `false` (line 19 of `NoopTaskScheduler.kt`) |

`DepthModelDownloader` is in `androidMain` — it cannot be constructed with `InProcessTaskScheduler` in production (that is a Desktop scheduler). But the question is relevant for tests.

Existing tests that call `downloadModel()`:
- `DepthModelDownloaderTest`: uses no-arg constructor → lazy `WorkManagerTaskScheduler` → `schedulesWork = true`. Guard passes.
- `DepthModelDownloaderRaceTest`: uses `DepthModelDownloader(context, readyCheck = ..., ...)` → no `scheduler` param → lazy `WorkManagerTaskScheduler` → `schedulesWork = true`. Guard passes.
- `DepthModelDownloaderAbsentRaceTest`: confirmed `setUp()` initializes WorkManager; same lazy path applies.
- `ModelDownloadEndToEndTest` via `ModelDownloadFixture`: passes a real `WorkManagerTaskScheduler` → `schedulesWork = true`. Guard passes.

For the new AC-5 test: the fake scheduler skeleton in the plan declares `SchedulerCapabilities(schedulesWork = true)` — so the guard passes, as expected for a cancel-port test.

The behavioral change relative to the old `workManagerOrNull` guard: the old guard was a live check that WorkManager is initialized at call time; the new check is a static capability flag. This matters only in the sub-window between app startup and WorkManager initialization, which is zero in production (Jetpack App Startup). The plan acknowledges this in the R4 risk entry.

**Verdict: CONFIRMED** — `schedulesWork` is `false` on `NoopTaskScheduler` (the only scheduler that should not schedule downloads) and `true` on both `WorkManagerTaskScheduler` and `InProcessTaskScheduler`. The replacement is correct. No test is broken.

---

## V5 — Existing cancel tests assert no direct WorkManager call

**Claim being validated:** No existing test asserts that `WorkManager.cancelUniqueWork` is called directly. AC-4 tests will pass without modification.

**Findings:**

`DepthModelDownloaderTest.kt` cancel-related tests (lines 97–139):

1. `cancel resolves a waiting caller and surfaces Cancelled` (lines 97–109): asserts `waiting.await().isLeft()`, asserts `modelState.value == ModelState.Cancelled`, asserts UI state mapping, asserts presentation copy. No assertion on WorkManager call.

2. `a queued-state snapshot that finishes after cancel never shows Downloading again` (lines 111–134): uses `downloader.cancelDownload()`, then asserts `afterCancel.none { it is ModelState.Downloading }`. No assertion on WorkManager call.

3. `cancel with nothing in flight is a no-op` (lines 136–139): only calls `cancelDownload()`; no assertion.

The helper `jobs()` (line 54) reads WorkManager state but is called only in the "re-attach" test, not in cancel tests.

`DepthModelDownloaderRaceTest` and `DepthModelDownloaderAbsentRaceTest`: also do not assert on `WorkManager.cancelUniqueWork` call counts.

**Verdict: CONFIRMED** — No existing test asserts `WorkManager.cancelUniqueWork` was called directly. The plan's AC-4 claim holds.

---

## V6 — BUILD.bazel registration for new test

**Claim being validated:** The plan's instruction to "run `scripts/kmp-module-tests-sync.py` after adding the class to `AllAndroidUnitTests.kt`" is correct for androidUnitTest registration.

**Findings:**

`kmp/src/androidUnitTest/kotlin/BUILD.bazel` uses a `glob(["**/*.kt"], exclude = [...])` pattern to compile ALL test sources into `android_unit_test_lib` (lines 19–293). A new file added to `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ml/` is automatically included in the compilation target — **no BUILD.bazel edit is required**.

The `android_local_test` target (lines 295–307) uses `test_class = "dev.stapler.stelekit.AllAndroidUnitTests"`. This JUnit 4 suite class is the sole entry point for Bazel test runs. `AllAndroidUnitTests.kt` has an explicit `@Suite.SuiteClasses(...)` annotation that lists every test class. A new test class must be added to this annotation list manually — otherwise it compiles but never runs under `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests`.

`scripts/kmp-module-tests-sync.py` manages businessTest and jvmTest module assignments — it reads `AllBusinessTests.kt` and `AllJvmTests.kt` and moves classes to per-module targets. **It does not read or write `AllAndroidUnitTests.kt`**, and running it after adding a new androidUnitTest class has no effect on that class's Bazel registration.

Existing pattern: `DepthModelDownloaderTest`, `DepthModelDownloaderRaceTest`, and `DepthModelDownloaderAbsentRaceTest` are NOT in `AllAndroidUnitTests.kt` — they run under Gradle (`testDebugUnitTest`) but not under `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests`. `ModelDownloadEndToEndTest` IS listed (line 84 of `AllAndroidUnitTests.kt`), so end-to-end tests run in both environments.

The plan says "add the class to `AllAndroidUnitTests.kt` and to `kmp/src/androidUnitTest/kotlin/BUILD.bazel`". The BUILD.bazel edit is unnecessary (glob covers it). The `AllAndroidUnitTests.kt` edit is correct and required for Bazel coverage. The `kmp-module-tests-sync.py` instruction is incorrect for this source set and should be ignored — the script will make no change but its mention may confuse the implementer into thinking something went wrong if they look for a generated file.

**Secondary concern: AC-5 test flake risk.** The plan's skeleton uses `yield(); yield()` to let the `scope.launch { taskScheduler.cancel(...) }` execute. The `scope` uses `PlatformDispatcher.IO` (which maps to `Dispatchers.IO` on JVM — a real thread pool, not a test scheduler). Under Robolectric's default configuration, `Dispatchers.IO` runs on real background threads. `yield()` in a `runBlocking` block yields to other coroutines on the SAME dispatcher; it does not advance coroutines on a separate thread pool. The `scope.launch` coroutine is on `Dispatchers.IO`, so `yield()` in `runBlocking` may not advance it, making the assertion `assertEquals(listOf(ModelDownloadTask.KEY), cancelledKeys)` flaky (cancelledKeys may still be empty when checked).

The plan's adversarial section correctly identifies this as a gap and proposes adding a `testScope: CoroutineScope? = null` seam. That is the right fix. The implementer must apply it before shipping the AC-5 test. The `yield(); yield()` skeleton as written is unreliable.

**Verdict: CONCERN** — Two corrections needed:
1. Remove the reference to `scripts/kmp-module-tests-sync.py` for this test; it does nothing here. Only edit `AllAndroidUnitTests.kt` (import + `::class` entry). No BUILD.bazel edit needed.
2. The AC-5 test skeleton's `yield(); yield()` approach is unreliable; apply the `testScope` seam fix from the plan's adversarial section before treating the test as written.

---

## Overall Recommendation: APPROVE WITH NOTES

The plan is implementable as written. No step has a correctness bug that would break existing tests or introduce a silent regression. The two items below must be applied during coding:

**Note 1 (from V6):** `scripts/kmp-module-tests-sync.py` does not apply to `androidUnitTest` classes. To register `DepthModelDownloaderCancelPortTest` in Bazel, add an import and a `::class` entry to `AllAndroidUnitTests.kt` only. No BUILD.bazel edit is needed (glob covers it automatically). Do not run `kmp-module-tests-sync.py` expecting it to register the class.

**Note 2 (from V6, also flagged in plan's adversarial section):** The AC-5 test skeleton using `yield(); yield()` to wait for `scope.launch` to execute is flaky. `Dispatchers.IO` is a real thread pool under Robolectric; `yield()` in `runBlocking` does not advance coroutines on a different thread pool. Add `testScope: CoroutineScope? = null` as a seam to `DepthModelDownloader`'s `internal constructor` and use a scope backed by `UnconfinedTestDispatcher` or a synchronous dispatcher in the AC-5 test so the assertion is deterministic.
