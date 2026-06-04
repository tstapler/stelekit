# Pitfalls Research: Risks and Edge Cases in the Planned Fix

## Files Examined
- `androidApp/src/main/kotlin/dev/stapler/stelekit/VoiceCaptureWidgetViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

---

## 1. Race: Double `switchGraph` — Does It Leave a Dangling `RepositorySet`?

### Scenario
With the proposed fix, `GraphManager.init` calls `switchGraph(activeId)`. Later, `StelekitApp` (via `MainActivity`) calls `switchGraph(activeId)` again with the same ID.

### What Happens in `switchGraph` on the Second Call

```kotlin
fun switchGraph(id: String) {
    val registry = _graphRegistry.value
    val currentGraphId = registry.activeGraphId
    currentGraphId?.let { activeGraphJobs.remove(it)?.cancel() }   // (1)

    _activeGitSyncService.value?.shutdown()
    _activeGitSyncService.value = null
    _activeVaultCredentialStore.value = null

    currentFactory?.close()          // (2)
    currentFactory = null
    _activeRepositorySet.value = null // (3)

    val graphScope = CoroutineScope(coroutineScope.coroutineContext)
    activeGraphJobs[id] = graphScope

    val deferred = CompletableDeferred<Unit>()
    _pendingMigration = deferred      // (4)

    graphScope.launch(PlatformDispatcher.IO) { ... }
}
```

Step (1): `activeGraphJobs.remove(id)?.cancel()` cancels the first `graphScope`. The first IO coroutine (from `init`'s `switchGraph` call) may still be running. Cancelling its scope causes a `CancellationException` to propagate through the coroutine. The `finally` block in the IO coroutine still runs:

```kotlin
} finally {
    deferred.complete(Unit)  // OLD deferred gets completed
}
```

The first `deferred` (from the first `switchGraph` call) is completed normally via the `finally` block (even on cancellation). However, `_pendingMigration` was already replaced with the second call's `deferred` at step (4). So anyone calling `awaitPendingMigration()` after the second `switchGraph` call awaits the second deferred — correct.

Step (2): `currentFactory?.close()` closes the database driver. If the first IO coroutine was in the middle of running migrations or creating repositories when its scope is cancelled, the `CancellationException` will interrupt those operations. The `PooledJdbcSqliteDriver` is closed, and any pending queries throw `SQLException`. This is safe — the first `RepositorySet` (if partially created) is never exposed to `_activeRepositorySet.value` because the `_activeRepositorySet.value = repoSet` line in the first coroutine is never reached (CancellationException was thrown before it).

Step (3): `_activeRepositorySet.value = null` — the widget or CaptureActivity observing this via `collectAsState()` will briefly see `null`. For widgets, this means re-rendering `NoGraphContent`. For `CaptureActivity`, it means showing `NoGraphPlaceholderContent` briefly.

### Dangling `RepositorySet`?

No dangling `RepositorySet` is left. The first coroutine is cancelled before setting `_activeRepositorySet.value`. Even if there was a timing gap where the first coroutine set `_activeRepositorySet.value = repoSet` just before cancellation, the second `switchGraph` call immediately sets `_activeRepositorySet.value = null` at step (3).

The `RepositoryFactoryImpl` from the first call IS closed via `currentFactory?.close()` at step (2). All `SqlDriver` resources are released. There is no resource leak.

### Risk Level: LOW — Functionally Safe, But Wastes One Full DB Open/Migration Cycle

The double `switchGraph` causes:
- One unnecessary database open + migration run.
- One brief null flash on `activeRepositorySet`.
- One extra close of the SQLite driver.

**Mitigation**: Add an idempotency guard to `switchGraph`:
```kotlin
fun switchGraph(id: String) {
    // Skip if already switching or already set for this ID
    if (activeGraphJobs.containsKey(id) && _graphRegistry.value.activeGraphId == id) return
    // ...
}
```

However this guard is tricky: if the first `switchGraph` (from `init`) is still in-flight (IO coroutine running), and `MainActivity` calls `switchGraph(id)` again, the guard would incorrectly skip the second call. A better guard: track a `currentSwitchingId: String?` that is set when a switchGraph is initiated and cleared when the IO coroutine finishes.

The simplest safe mitigation: accept the double call for now (it's correct, just slightly wasteful), and add the optimization in a follow-up. The null flash is real but will be imperceptible (~50-100ms on a fast device).

---

## 2. Race: `provideGlance` Timeout — ANR or Silent Timeout Risk?

### `awaitPendingMigration()` Inside `provideGlance`

```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    graphManager?.awaitPendingMigration()  // proposed addition
    provideContent { ... }
}
```

`awaitPendingMigration()` awaits `_pendingMigration: Deferred<Unit>`.

### Can This Cause an ANR?

**No.** `provideGlance` is a suspend function called from a coroutine — it runs on a background thread (the Glance framework's coroutine dispatcher). Suspending inside `provideGlance` does not block the main thread and cannot cause an ANR.

### Is There a Widget Update Timeout?

Android's widget update mechanism does not have a short timeout on `provideGlance` itself. However:
- The `BroadcastReceiver.onReceive()` has a 10-second limit. The `goAsync()` pattern in `CaptureWidgetReceiver` extends this, but `goAsync()` is only extended for as long as `pendingResult.finish()` is not called. In `CaptureWidgetReceiver.onUpdate()`:

```kotlin
override fun onUpdate(...) {
    val pendingResult = goAsync()
    appScope.launch {
        try {
            super.onUpdate(...)   // <-- calls provideGlance internally
        } finally {
            pendingResult.finish()
        }
    }
}
```

If `awaitPendingMigration()` (inside `provideGlance`) takes longer than ~30 seconds (the goAsync extended limit), Android may kill the process. In practice, the migration should complete in <5 seconds even on first launch (the UUID migration is O(n) on block count, typically <1s for empty graphs, <5s for large graphs).

### What If the Migration Coroutine Is Cancelled?

The `finally { deferred.complete(Unit) }` guarantee means `awaitPendingMigration()` always resolves, even if the migration was cancelled. So `provideGlance` will not hang indefinitely.

### Risk Level: LOW — Migration always resolves; no ANR possible; timing is bounded.

The one theoretical risk: if `switchGraph` is called from `init` and then immediately cancelled (e.g., due to memory pressure killing the process), the `graphScope` is cancelled, the first IO coroutine cancels, and `deferred.complete(Unit)` is called from the `finally` block. `awaitPendingMigration()` then returns, `getActiveRepositorySet()` returns `null`, and the widget shows `NoGraphContent`. This is a graceful degradation, not a crash.

---

## 3. Fresh Install Path — Guard Against Calling `switchGraph` on Null `activeGraphId`

### Current `GraphManager.init`

```kotlin
init {
    loadRegistry()
}
```

`loadRegistry()` ends with `_graphRegistry.value = GraphRegistry()` (empty) on a fresh install (no `lastGraphPath` in SharedPreferences either). An empty `GraphRegistry` has `activeGraphId = null`.

### Proposed Change

```kotlin
init {
    loadRegistry()
    val activeId = _graphRegistry.value.activeGraphId
    if (activeId != null) switchGraph(activeId)  // <-- guard is required
}
```

The guard `if (activeId != null)` is correctly placed. On a fresh install, `activeGraphId` is `null`, so `switchGraph` is NOT called. This is the correct behavior.

### Edge Case: `migrateFromSingleGraph()` Creates a Registry with `activeGraphId` Set

`migrateFromSingleGraph()` (called from `loadRegistry()` when no registry exists but `lastGraphPath` is set) creates a `GraphRegistry` with `activeGraphId = graphId` set. After `loadRegistry()` returns, `_graphRegistry.value.activeGraphId` will be non-null. The proposed `init` fix will then call `switchGraph(graphId)` — **this is correct behavior** and is actually a bonus fix: the first migration from single-graph to multi-graph will correctly open the database.

### Risk Level: NONE — The `if (activeId != null)` guard is sufficient and straightforward.

---

## 4. Hibernate vs. Hibernation — Is `SharedPreferences` Preserved?

### Android 12 App Hibernation (True Hibernation)

Android 12 introduced `AppHibernationManager`. When an app is hibernated:
- The app is placed in the `HIBERNATED` App Standby Bucket.
- The system revokes all runtime permissions (location, camera, mic, storage).
- The process is killed (if running).
- **App data is NOT deleted**: `SharedPreferences`, SQLite databases, and files in the app's private data directory (`/data/data/<package>/`) are fully preserved.
- The app "wakes up" normally when the user or a component triggers it — `Application.onCreate()` runs, SharedPreferences is readable, database files are on disk.

### What Is Actually Lost After Hibernation

1. **Runtime permissions** — SAF `persistableUriPermission` for the user's graph folder is revoked. `fileSystem.hasStoragePermission()` returns `false`. Reads and writes via SAF fail with `SecurityException`. This means `switchGraph()` may succeed (database opens from internal storage), but `GraphLoader` will fail to read markdown files from the user's folder. This is a **separate, more severe bug** that is out of scope for this fix but worth documenting.
2. **In-memory state** — all Kotlin singletons and `object`s are re-initialized. The `GraphManager` starts fresh. This is already handled by the proposed fix.

### Does the Proposed Fix Help After True Hibernation?

**Partially.** The proposed fix (auto-`switchGraph` from `init`) correctly opens the database and restores `activeRepositorySet`. The widget will show the "Capture" UI instead of "No Graph Detected". However, if the user tries to save a note via `CaptureActivity`, `GraphWriter` will fail when it tries to write to the SAF folder (permission revoked). This is a deeper problem that requires either:
- Detecting revoked SAF permissions and showing a "Reconnect" screen.
- Gracefully degrading to in-memory pending saves that sync after the user grants permission again.

This is out of scope for the current fix but should be a known limitation.

### Distinction: App Standby Buckets vs. True Hibernation

| Scenario | Process killed? | SharedPreferences? | Permissions? | Widget visible? |
|---|---|---|---|---|
| Memory pressure kill | Yes | Preserved | Preserved | Yes |
| App Standby RESTRICTED | Yes (when not used) | Preserved | Preserved (rate-limited) | Yes |
| Android 12 True Hibernation | Yes | Preserved | Revoked | Depends on OEM |
| Uninstall | Yes | Deleted | N/A | Removed |

**Conclusion**: For all scenarios except true hibernation, the proposed fix fully restores widget functionality. For true hibernation, the widget will show the capture UI, but saves may fail due to revoked SAF permissions. SharedPreferences (and thus the graph registry) is always preserved.

---

## 5. `VoiceCaptureWidgetViewModel` Coroutine Scope — `viewModelScope.launch` with `awaitPendingMigration()`

### Current Structure

```kotlin
class VoiceCaptureWidgetViewModel(app: Application) : AndroidViewModel(app) {
    fun initialize(requestMicPermission: suspend () -> Boolean) {
        if (inner != null) return
        val steleApp = getApplication<SteleKitApplication>()
        val repoSet = steleApp.graphManager?.getActiveRepositorySet()
        if (repoSet == null) {
            _state.value = VoiceCaptureState.Error(...)
            return
        }
        // ... build pipeline
    }
}
```

### Proposed Fix: `viewModelScope.launch { awaitPendingMigration(); ... }`

```kotlin
fun initialize(requestMicPermission: suspend () -> Boolean) {
    if (inner != null) return
    viewModelScope.launch {
        val steleApp = getApplication<SteleKitApplication>()
        steleApp.graphManager?.awaitPendingMigration()
        val repoSet = steleApp.graphManager?.getActiveRepositorySet()
        if (repoSet == null) {
            _state.value = VoiceCaptureState.Error(...)
            return@launch
        }
        // ... build pipeline (was previously synchronous)
    }
}
```

### Lifecycle Concern Analysis

`viewModelScope` is bound to the `ViewModel`'s lifecycle. It is cancelled when `onCleared()` is called, which happens when the owning `Activity` is destroyed.

**Scenario**: User opens `VoiceCaptureActivity` (triggering `viewModel.initialize()`), then immediately closes the Activity while `awaitPendingMigration()` is suspended. The `Activity` is destroyed → `ViewModel.onCleared()` is called → `viewModelScope` is cancelled → the `launch` block receives `CancellationException` → the coroutine exits cleanly. No issue.

**Scenario**: `awaitPendingMigration()` completes, `repoSet` is obtained, and the pipeline is being built inside the `viewModelScope.launch` block, but the Activity is destroyed mid-way. Again, `viewModelScope` cancellation interrupts the coroutine cleanly. The `VoiceCaptureViewModel.inner` is never set, `onCleared()` calls `inner?.close()` (which is a no-op since `inner == null`). Clean exit.

**Re-entrancy concern**: `initialize()` has a guard `if (inner != null) return`. With the async version, `inner` is set at the end of the `launch` block:

```kotlin
viewModelScope.launch {
    ...
    val vm = VoiceCaptureViewModel(...)
    inner = vm
    vm.onMicTapped()
}
```

If `initialize()` is called twice before the first coroutine sets `inner`, the guard `if (inner != null) return` will NOT prevent a second coroutine from launching (since `inner` is still null when the second call arrives). This could result in two pipeline instances being created.

**Fix for re-entrancy**: Use an `AtomicBoolean` flag instead of checking `inner`:

```kotlin
private val initializing = AtomicBoolean(false)

fun initialize(requestMicPermission: suspend () -> Boolean) {
    if (inner != null) return
    if (!initializing.compareAndSet(false, true)) return  // prevent double-init
    viewModelScope.launch {
        try {
            // ...
        } finally {
            initializing.set(false)  // reset if init fails
        }
    }
}
```

Or simpler: convert `inner` to `@Volatile` and use a `synchronized` block around the init launch. In practice, `VoiceCaptureActivity` likely only calls `initialize()` once in `onCreate()`, so this is a low-risk theoretical issue, not a real-world bug.

**Verdict**: `viewModelScope.launch { awaitPendingMigration() }` is lifecycle-safe. The re-entrancy concern is theoretical; add an `AtomicBoolean` guard for robustness.

---

## Summary Table

| Risk | Severity | Mitigation |
|---|---|---|
| Double `switchGraph` (init + MainActivity) | LOW — brief null flash, wasted db cycle | Accept for now; add idempotency guard in follow-up |
| `provideGlance` timeout from `awaitPendingMigration()` | VERY LOW — migration is bounded + always resolves | No change needed; the `finally` guarantee is sufficient |
| Fresh install calling `switchGraph(null)` | NONE — guard `if (activeId != null)` prevents it | Guard must be present in implementation |
| SharedPreferences lost after hibernation | NONE — SharedPreferences survives hibernation | Not a concern |
| SAF permissions lost after true hibernation | MEDIUM — saves fail silently after wakeup | Out of scope; document as known limitation |
| `viewModelScope.launch` double-init race | LOW — theoretical; Activity calls init once | Add `AtomicBoolean` guard for robustness |
| `RepositorySet` dangling after double switch | NONE — old factory is closed before new one created | No action needed |
