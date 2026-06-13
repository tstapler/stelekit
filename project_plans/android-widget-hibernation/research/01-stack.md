# Stack Research: Android Glance API & Hibernation Behavior

## Relevant Files Examined
- `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/CaptureWidget.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/widget/CaptureWidgetReceiver.kt`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt`

---

## 1. `provideGlance` Timing: Is It Called Synchronously After Process Wakeup?

`provideGlance` is a `suspend` function on `GlanceAppWidget`. The Android Glance framework calls it from within a coroutine scope managed by the Glance internals (bound to the widget's `AppWidgetManager` update lifecycle). Key behaviors:

- **Not synchronous**: `provideGlance` is called inside a coroutine launched by `GlanceAppWidgetManager` (or via `GlanceAppWidget.update()`), which itself is triggered by `AppWidgetManager` broadcasts.
- **After process wakeup**: When the process is dead and the home screen needs to update the widget, Android sends an `ACTION_APPWIDGET_UPDATE` broadcast. This wakes the process, runs `Application.onCreate()`, then delivers the broadcast to the `BroadcastReceiver` (`GlanceAppWidgetReceiver`). The Glance internals then invoke `provideGlance` in a coroutine.
- **In the current code**: `CaptureWidgetReceiver.onUpdate()` uses the `goAsync()` pattern and runs `super.onUpdate()` on `appScope` (a `SupervisorJob + Dispatchers.Default` scope). This correctly prevents the 10-second BroadcastReceiver timeout. The Glance framework's `GlanceAppWidgetReceiver.onUpdate()` internally calls `provideGlance` which is a suspending function — so the full chain is async and non-blocking.

**Conclusion**: `provideGlance` is not synchronous. It is called within a coroutine *after* `Application.onCreate()` completes. The widget process wakeup sequence is: `Application.onCreate()` → broadcast receiver delivery → Glance coroutine → `provideGlance`.

---

## 2. Is It Safe to Do Async Work Before `provideContent {}`?

Yes, with caveats:

- `provideGlance` is a `suspend fun`. Doing `suspend` work before calling `provideContent {}` is explicitly supported by the Glance API. The function signature is designed for this.
- `provideContent {}` is the terminal call that installs the Composable tree into the widget's RemoteViews. Until `provideContent` is called, the widget displays its last-known RemoteViews (or a loading placeholder).
- **Timeout risk**: Android's `AppWidgetManager` does not impose a strict documented timeout on `provideGlance`. However, the 10-second `BroadcastReceiver.onReceive()` limit applies to the *starting* of the update. The `goAsync()` pattern (already in `CaptureWidgetReceiver`) extends this to ~30 seconds for the BroadcastReceiver itself. Beyond that, Android may kill the process via the App Standby scheduler.
  - **Practical limit**: Glance documentation does not specify an explicit coroutine timeout for `provideGlance`. However, the work it does should be bounded — an unbounded `await()` that never completes (e.g., if `awaitPendingMigration()` hangs) would prevent `provideContent` from ever being called, leaving the widget stale or blank.
  - **ANR risk**: `provideGlance` runs off the main thread in a coroutine, so it cannot directly cause an ANR (which requires main-thread blocking). However, if the update takes too long, the widget may simply show stale content or be skipped by the scheduler.
- **Recommendation**: `awaitPendingMigration()` already has a `finally { deferred.complete(Unit) }` guard in `switchGraph`, so it will always resolve. Awaiting it in `provideGlance` is safe as long as `switchGraph()` was called before `provideGlance` is reached (which requires the proposed auto-`switchGraph` in `GraphManager.init`).

**Conclusion**: Async work before `provideContent {}` is safe and by design. The risk is only if the suspension never resolves. `awaitPendingMigration()` is guaranteed to resolve (it has a `finally` guard), so awaiting it is safe.

---

## 3. Android App Hibernation vs Process Kill — Does `Application.onCreate()` Always Run?

### Process Kill (Standard)
When Android kills the process due to memory pressure (LRU eviction), the next time any component (widget, tile, activity) is triggered:
- Android creates a fresh process.
- `Application.onCreate()` is called first, unconditionally.
- All static state, in-memory objects, and singleton `object`s are re-initialized from scratch.
- `SharedPreferences` data is **preserved** (it lives in the app's private data directory).

### App Standby Buckets (API 28+)
Android places apps in standby buckets (ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED). In the RESTRICTED bucket (most aggressive), background work is severely limited but:
- The app is not "hibernated" in the data-reset sense.
- Widget updates may be rate-limited.
- Process is still killed/revived normally; `Application.onCreate()` still runs on next wakeup.

### App Hibernation (Android 12 / API 31+ — HIBERNATED bucket)
Android 12 introduced true app hibernation via the Permission Revocation system (`AppHibernationManager`). When an unused app is hibernated:
- **Permissions are revoked** (runtime permissions, not install-time).
- **The app process is killed** (if running).
- **App data is NOT deleted** — `SharedPreferences`, databases, and files in the app's private data directory are **preserved**. This is a common misconception. Hibernation ≠ data wipe. It is closer to "force stop + permission revoke."
- When the user re-opens the app or a widget triggers it, the process starts fresh: `Application.onCreate()` runs, `SharedPreferences` is intact, and the database files are present on disk.
- **Widget impact**: Hibernated apps have their widget slots silently removed by the launcher on some OEM implementations. Google Pixel launchers may keep widgets visible but show a "not installed" state. This is a separate concern from the process lifecycle.

### What This Means for SteleKit
- `Application.onCreate()` always runs before any component is activated.
- `SteleKitApplication.onCreate()` creates `GraphManager`, which calls `loadRegistry()`, which reads `SharedPreferences`. The registry data survives both process kill and hibernation.
- The gap: `loadRegistry()` restores `graphRegistry` and `activeGraphId` in memory, but does NOT call `switchGraph()`, so `_activeRepositorySet` remains `null`.
- After real hibernation, runtime permissions may be revoked. If the graph path uses SAF (`saf://...`), the `persistableUriPermission` may be gone, causing `GraphLoader` to fail when it tries to read files. This is a separate bug from the widget blank-screen issue but should be documented.

**Conclusion**: `Application.onCreate()` is always called. `SharedPreferences` (and thus the graph registry) is always preserved. The widget blank-screen is purely a "no `switchGraph()` called" bug, not a data loss issue.

---

## 4. Glance `provideContent {}`: StateFlow / Reactive Updates

`provideContent {}` takes a `@Composable` lambda. Inside this lambda, Glance supports a limited subset of Compose:
- Standard Compose `remember` and `LaunchedEffect` are available.
- **`collectAsState()` works inside `provideContent {}`**: Glance's composable environment uses Compose's snapshot system. A `StateFlow.collectAsState()` call inside the `provideContent` block will recompose the widget tree when the flow emits. This is a supported pattern — the widget's Compose tree stays active until the next `provideGlance` call.
- **`GlanceStateDefinition`**: An alternative reactive pattern specific to Glance. It uses a `DataStore`-backed state, accessible via `currentState<T>()`. This is appropriate for simple key-value widget state but is overkill for `RepositorySet` availability (which is a complex in-memory object).
- **Limitation**: Widget composables cannot use all Compose features (no `Dialog`, no custom `Canvas`, no `ConstraintLayout`). But `collectAsState()` on a `StateFlow` is fully supported.

**For the planned fix in `CaptureWidget`**: Instead of calling `getActiveRepositorySet()` (one-shot) inside `provideContent {}`, the widget could collect `graphManager.activeRepositorySet.collectAsState()`. This would automatically recompose when the async `switchGraph()` coroutine completes and sets `_activeRepositorySet.value`. This avoids the race where `provideContent` runs before `switchGraph`'s IO coroutine finishes.

**Conclusion**: `collectAsState()` works inside `provideContent {}` and is the correct reactive pattern for observing `activeRepositorySet: StateFlow<RepositorySet?>` in the widget composable tree.

---

## Summary

| Question | Answer |
|---|---|
| Is `provideGlance` synchronous after wakeup? | No — it is a `suspend fun` called in a Glance-managed coroutine, after `Application.onCreate()` |
| Safe to `suspend` before `provideContent`? | Yes, with the caveat that the suspension must always resolve (which `awaitPendingMigration()` guarantees via `finally`) |
| Does `Application.onCreate()` always run? | Yes — for process kill and hibernation alike |
| Is `SharedPreferences` preserved after hibernation? | Yes — hibernation revokes permissions but does not wipe data |
| Does `provideContent` support `collectAsState()`? | Yes — Glance composables support Compose's snapshot system including `StateFlow.collectAsState()` |
