# Findings: Architecture — Widget/Tile/Share Data Access and Write-Back

**Authored**: 2026-04-22
**Input**: `project_plans/android-features-integration/requirements.md`

---

## Summary

Widget, Tile, and Share-target components on Android run in the **same process** as the main app but can be cold-started without the main Activity ever running. SteleKit's data layer is currently owned by `GraphManager`, which is constructed inside the Compose root (`StelekitApp`) and is therefore bound to `MainActivity`'s lifecycle. There is no `Application` subclass — the manifest declares only `MainActivity`. `SteleKitContext` and `DriverFactory` are initialized in `MainActivity.onCreate`, which means no component can access the data layer before the main activity runs.

The recommended architecture: **promote `GraphManager` to a process-scoped singleton in a custom `Application` subclass, expose a thin write API for widget/tile/share captures, and route all writes through the existing `DatabaseWriteActor` + `GraphWriter` pipeline**. No ContentProvider, no WorkManager for writes, no remote process.

---

## Options Surveyed

### Option A — Singleton `GraphManager` in a custom `Application` subclass (recommended)

Create `SteleKitApplication : Application`. In `onCreate`, call `SteleKitContext.init`, `DriverFactory.setContext`, instantiate `PlatformSettings`, `PlatformFileSystem`, and `GraphManager`. Restore the SAF tree URI in `PlatformFileSystem.init(applicationContext)`. Expose `graphManager`, `fileSystem`, and an application-level `CoroutineScope(SupervisorJob() + Dispatchers.Default)` as process-scoped vals.

All Android components (`MainActivity`, `AppWidgetProvider`, `TileService`, share `Activity`) access the graph manager via `(application as SteleKitApplication).graphManager`.

`GraphManager` initialization is cheap: the constructor only reads `PlatformSettings` JSON and builds the `GraphRegistry` in-memory. The SQLDelight driver is created **lazily** inside `RepositoryFactoryImpl` — it is not opened until the first repository method is called. A cold start for a widget add pays only a SharedPreferences read + JSON parse, not a full DB open.

**Write path for a widget capture:**
1. Share Activity calls `app.graphManager.getActiveRepositorySet()?.writeActor`
2. Calls `actor.execute { blockRepository.saveBlock(newBlock) }` — serialized through the existing actor
3. Calls `graphWriter.savePage(page, blocks, graphPath)` — flushes the markdown file to disk

### Option B — WorkManager-deferred write

Widget enqueues a `CoroutineWorker`; worker opens `GraphManager` and writes when scheduled.

**Rejected:** WorkManager does not guarantee ordering relative to the main UI's debounced `GraphWriter` flush. A worker write running concurrently with a UI save breaks `DatabaseWriteActor`'s serialization invariant and risks `SQLITE_BUSY`. Latency (typically 2–15 s) is unacceptable for a capture flow where the user expects immediate confirmation.

### Option C — Bound `Service`

A `Service` exposes an AIDL/Messenger interface; widget binds and sends write requests.

**Rejected:** All components are already in the same process. IPC adds latency and boilerplate for zero benefit. A `Service` is warranted only for cross-process (`:remote`) configurations.

### Option D — ContentProvider

Expose blocks/pages via a `ContentProvider`. Widget reads via `query()`, writes via `insert()`.

**Rejected:** ContentProvider is a cross-app data-sharing mechanism. It adds a mandatory Binder round-trip even within the same process and fights the existing `Flow`-based repository layer (cursors vs. `StateFlow`). Implementation cost is high; testability is poor in KMP.

### Option E — Direct SQLite access (bypass repositories)

Widget opens the `.db` file at `context.filesDir/stelekit-graph-<hash>.db` directly.

**Rejected outright.** Bypasses `DatabaseWriteActor` serialization, risks corruption, leaves the markdown file stale. The DB filename includes a 16-character SHA-256 hash of the graph path — reconstructing it from `PlatformSettings` is fragile.

---

## Trade-off Matrix

| Criterion | Option A (App singleton) | Option B (WorkManager) | Option C (Bound Service) | Option D (ContentProvider) | Option E (Direct SQLite) |
|---|---|---|---|---|---|
| Process safety | High — shared actor serializes all writes | Medium — race with UI debounce | High — same actor via IPC | Medium — cursor writes not actor-gated | Low — bypasses actor entirely |
| Data freshness (markdown on disk) | Immediate — `GraphWriter` called in share Activity | Delayed 2–15 s | Immediate | Immediate if actor-gated | Immediate but unsafe |
| Write latency | ~50–200 ms (DB + file write) | 2–15 s | ~50–200 ms + IPC overhead | ~50–200 ms + Binder overhead | ~20 ms (do not use) |
| Implementation complexity | Low — extends existing init path | Medium — new Worker class | High — AIDL/Messenger boilerplate | High — cursor layer over Flow repos | Very low (do not use) |
| Testability | High — same unit-testable `GraphManager` | Medium — WorkManager test rules required | Low — IPC is hard to unit test | Low — cursor adapters not KMP | N/A |
| Crash-safety if write fails | Actor returns `Result.failure`; Activity shows error | Worker retries automatically | Actor returns failure | Depends on provider impl | Silent corruption risk |

---

## Risk and Failure Modes

**R1 — No active graph at cold start.** `GraphManager.getActiveGraphInfo()` returns `null` if no graph has been selected. Widget/tile must show a "No graph configured — open SteleKit" placeholder rather than crashing.

**R2 — SAF permission not accessible from the widget context.** `PlatformFileSystem` restores the SAF tree URI from `SharedPreferences` in `init()`. Permissions are held against `applicationContext`, which is process-scoped and shared by all components. Calling `PlatformFileSystem.init(applicationContext, onPickDirectory = null)` from `Application.onCreate` ensures grants are valid for the share Activity. **Do not call `init()` with an Activity context** and do not attempt folder-picking from a non-Activity component (AppWidgetProvider, TileService).

**R3 — `DatabaseWriteActor` closed before write completes.** `GraphManager.switchGraph` cancels the current graph's `CoroutineScope`. A widget write racing with a graph switch will receive `ClosedSendChannelException`. The share Activity must treat `Result.failure` as a user-visible error with a retry option.

**R4 — Markdown file stale after SQLite write.** Blocks written through `DatabaseWriteActor` land in SQLite only. The markdown file is only updated when `GraphWriter.savePage` is invoked. **Always call `GraphWriter` immediately after the actor write in the share Activity.** Skipping it leaves SQLite and disk inconsistent until the next main-app save.

**R5 — ANR from main-thread write in `AppWidgetProvider.onUpdate`.** `onUpdate` runs on the main thread with a short budget. All writes must be dispatched via the Application-level coroutine scope. Use `goAsync()` or launch from `appScope` and call `result.finish()` when done.

**R6 — `DriverFactory` not initialized.** `DriverFactory.createDriver` throws `IllegalStateException` if `staticContext` is null. Initializing in `Application.onCreate` before any component starts eliminates this race completely.

---

## Migration and Adoption Cost

The codebase currently has **no `Application` subclass** and initializes both `SteleKitContext` and `DriverFactory` inside `MainActivity.onCreate`. Required changes to adopt Option A:

1. **Create `SteleKitApplication.kt`** in `androidApp/src/main/kotlin/dev/stapler/stelekit/` — moves init calls from `MainActivity` and exposes `graphManager`, `fileSystem`, `appScope`.
2. **Add `android:name=".SteleKitApplication"`** to `<application>` in `androidApp/src/main/AndroidManifest.xml`.
3. **Refactor `MainActivity`** — remove `SteleKitContext.init` and `DriverFactory.setContext` (now in Application); obtain `graphManager` and `fileSystem` from `(application as SteleKitApplication)` and pass them into `StelekitApp`.
4. **Declare new components** in the manifest: `ShareReceiverActivity`, `CaptureWidgetProvider`, `CaptureTileService`.
5. **No database schema changes** — writes use the existing `BlockRepository`/`PageRepository` APIs.

Estimated effort: 1–2 days for Application refactor; 1 day per new Android component thereafter.

---

## Operational Concerns

**Memory:** `GraphManager` holds one open SQLite driver per active graph. Widgets and tiles share the same driver instance through the Application singleton — no additional DB connections.

**Battery:** SAF writes go through ContentResolver IPC per file regardless of architecture. Keep widget writes to one SQLite write + one markdown file write per capture. Do not poll from `onUpdate`.

**Widget update after write:** After a successful capture, call `AppWidgetManager.updateAppWidget()` from the share Activity to refresh the widget's `RemoteViews` (e.g., "Last capture: 2 min ago").

**Coroutine scope for AppWidgetProvider:** Use `appScope` (from `SteleKitApplication`) for all background work in `onUpdate`. Never create a new `CoroutineScope` inside `onUpdate` without cancellation — it will leak. `DatabaseWriteActor` already owns its own `CoroutineScope(SupervisorJob() + PlatformDispatcher.Default)` and survives independently of any caller scope.

**Jetpack Glance compatibility:** Glance's `provideGlance` is a suspend function running on `Dispatchers.Default`, which composes naturally with the actor's channel-based API.

---

## Prior Art and Lessons Learned

**Android documentation on `AppWidgetProvider` lifecycle:** Google's guidance recommends `goAsync()` or dispatching to a process-scoped scope for any work beyond a few milliseconds in `onUpdate`. [TRAINING_ONLY — verify exact current recommendation]

**SQLite WAL concurrent reads:** The existing `PRAGMA journal_mode=WAL` and `PRAGMA busy_timeout=10000` in `DriverFactory.android.kt` allow concurrent reads while a write is in progress. Since all components share one `SqlDriver` instance through the Application singleton, WAL mode applies uniformly — no second connection to the same file is opened.

**Notion / Bear:** These apps use an Application-singleton pattern for their data layer and launch a minimal confirmation Activity from the share sheet. No WorkManager indirection. [TRAINING_ONLY — verify]

---

## Open Questions

- [ ] **Page picker in share Activity**: Should the share Activity present a page picker ("Save to today's journal" vs. "Save to specific page"), or always capture to the active journal page? If a picker is needed, the Activity must call `graphManager.awaitPendingMigration()` before reading `PageRepository`. Blocks: share Activity UI scope.
- [ ] **Live data in widget**: Will the Glance widget display live read data (recent journal entries) or only trigger write actions (quick capture)? Live data requires a reactive update channel. Blocks: widget feature scope.
- [ ] **Multi-graph widget targeting**: Can widget writes target a non-active graph? If yes, a separate lightweight `RepositoryFactoryImpl` for a pinned graph would be needed. Blocks: widget configuration scope.
- [ ] **`GraphWriter` mutex under concurrent callers**: Do `GraphWriter`'s `pendingMutex` and `saveMutex` correctly handle simultaneous calls from the main UI and the share Activity? The Activity bypasses debouncing and calls `savePage` directly — confirm `saveMutex` serializes correctly between both callers. Blocks: correctness validation.
- [ ] **`PlatformFileSystem.validateLegacyPath` restrictions**: Does this cause failures for users whose graph is outside `Environment.DIRECTORY_DOCUMENTS`? SAF-rooted graphs are exempt. Blocks: share Activity file write path.

---

## Recommendation

**Adopt Option A: process-scoped `GraphManager` singleton in a custom `Application` subclass.**

Concretely:

1. **`SteleKitApplication.kt`** (`androidApp/src/main/`): In `onCreate`, call `SteleKitContext.init(this)`, `DriverFactory.setContext(this)`, instantiate `PlatformFileSystem().apply { init(applicationContext, onPickDirectory = null) }` and `GraphManager(PlatformSettings(), DriverFactory(), fileSystem)`. Restore the active graph by calling `graphManager.switchGraph(activeId)` if `graphRegistry.activeGraphId != null`. Expose `val appScope`, `val graphManager`, `val fileSystem`.

2. **`AndroidManifest.xml`**: add `android:name=".SteleKitApplication"` to `<application>`.

3. **`MainActivity`**: remove `SteleKitContext.init` / `DriverFactory.setContext`; obtain objects from `(application as SteleKitApplication)`.

4. **`ShareReceiverActivity`**: declared with `ACTION_SEND` / `text/plain` intent filter. In `onCreate`, suspend on `graphManager.awaitPendingMigration()` via `appScope`. Show a minimal confirmation dialog. On confirm: call `actor.execute { blockRepository.saveBlock(newBlock) }`, then `graphWriter.savePage(page, updatedBlocks, graphPath)`, then `finish()`. On `Result.failure`, show a Snackbar with retry.

5. **`CaptureWidgetProvider`**: `onUpdate` launches `appScope.launch { ... }` (or uses `goAsync()`). On tap, start `ShareReceiverActivity` with a pre-filled `Intent` targeting today's journal. No direct DB write in `onUpdate`.

6. **`CaptureTileService`**: `onClick` launches `ShareReceiverActivity` identically. Tile active state driven by `graphManager.getActiveGraphInfo() != null`.

This design keeps the write path identical regardless of which Android component initiates it, reuses all existing actor/dispatcher/repository machinery, requires zero schema changes, and is fully unit-testable.

---

## Pending Web Searches

1. `"AppWidgetProvider coroutine scope Android goAsync" site:developer.android.com` — confirm recommended coroutine scope pattern for `onUpdate`.
2. `"Glance widget StateFlow data source" site:developer.android.com OR site:medium.com` — confirm how Jetpack Glance reads reactive repository data.
3. `"Android TileService onClick coroutine" site:developer.android.com` — confirm `onClick` threading model and recommended coroutine dispatch.
4. `"Android Application onCreate before AppWidgetProvider cold start"` — confirm that `Application.onCreate` is guaranteed to precede `AppWidgetProvider.onUpdate` when the process is cold-started by the widget host.
5. `"SQLite WAL same SqlDriver instance concurrent reads Android"` — confirm WAL concurrent-read behavior applies when widget and main UI share one `SqlDriver` instance.

## Web Search Results

*(To be populated by parent agent)*
