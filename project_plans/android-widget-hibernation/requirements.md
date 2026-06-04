# Requirements: Android Widget / Share-Target Hibernation Recovery

## Problem Statement

After Android hibernates the SteleKit process (App Standby Bucket RESTRICTED, or the OS kills the process for memory), the `Application` class is re-created when a widget or share-target entry point wakes the process. `GraphManager.init` calls `loadRegistry()` which restores the active graph ID from `SharedPreferences`, but **never calls `switchGraph()`**. As a result:

- `GraphManager.getActiveRepositorySet()` returns `null`
- `CaptureWidget` and `VoiceWidget` show "no graph detected" even though a graph is fully configured
- `CaptureActivity` (share target) shows the "No Graph" placeholder instead of the capture sheet
- `CaptureTileService` redirects to `MainActivity` instead of `CaptureActivity`
- `VoiceCaptureWidgetViewModel.initialize()` fails with NO_GRAPH immediately

The root cause: the database connection (RepositorySet) is only opened by `switchGraph()`, which is only called from the main-app Compose UI (`StelekitApp`). Entry points that don't go through `MainActivity` never open the database.

## Scope

Fix hibernation recovery for all four Android entry points that access the graph without going through `MainActivity`:
1. `CaptureWidget` (home-screen widget — add note)
2. `VoiceWidget` (home-screen widget — voice note)
3. `CaptureActivity` (share target + widget/tile launcher)
4. `VoiceCaptureActivity` / `VoiceCaptureWidgetViewModel` (voice widget launcher)
5. `CaptureTileService` (Quick Settings tile)

## Functional Requirements

### FR-1: Auto-restore graph on process wake
When `GraphManager` is created and the registry has an `activeGraphId`, it MUST automatically begin initializing the database (call `switchGraph(activeGraphId)`) in the background. This applies whether the process was freshly created by a widget wake-up or any other entry point.

### FR-2: Widget awaits graph initialization
`CaptureWidget.provideGlance` and `VoiceWidget.provideGlance` MUST await `awaitPendingMigration()` before rendering content. Since `provideGlance` is a `suspend` function, it can block until the DB is ready. This ensures the widget renders the correct state (action button vs. "no graph") on first render after hibernation.

### FR-3: CaptureActivity observes graph state reactively
`CaptureActivity` MUST observe `graphManager.activeRepositorySet` as a `StateFlow` (via `collectAsState()`) rather than reading it once at `setContent` time. When the graph transitions from `null` → non-null (async DB open completes), the Compose UI MUST automatically recompose and show the capture sheet.

### FR-4: VoiceCaptureWidgetViewModel awaits graph initialization
`VoiceCaptureWidgetViewModel.initialize()` MUST call `awaitPendingMigration()` before checking for an active repository set, so it does not fail with NO_GRAPH while the database is still being opened.

### FR-5: Preserve existing no-graph behavior
If no graph is configured at all (registry is empty / activeGraphId is null), all entry points MUST continue to show the existing "no graph" placeholder or redirect to `MainActivity`. The fix MUST NOT mask genuinely unconfigured state.

### FR-6: CaptureTileService reads graph state after migration
`CaptureTileService.onClick()` reads `getActiveRepositorySet()` synchronously. Since TileService `onClick` is not a coroutine, the tile cannot `await`. The auto-restore in FR-1 is the primary fix; if the graph is not yet loaded (race), the tile should fall back to launching `MainActivity` (existing behavior) — this is acceptable since it's a rare race.

## Non-Functional Requirements

### NFR-1: No main-thread blocking
`Application.onCreate()` must not block the main thread. `switchGraph()` already launches its DB work on `PlatformDispatcher.IO`; this invariant must be preserved.

### NFR-2: No duplicate initialization
If `switchGraph(activeGraphId)` is already in progress or complete when a second call would occur (e.g., `MainActivity` also calls it), the second call must not corrupt state. The existing `switchGraph` implementation handles this by closing the previous factory first; this behavior must be preserved.

### NFR-3: Timeout safety
`awaitPendingMigration()` uses a `Deferred` with a `finally { deferred.complete(Unit) }` guard. If DB init fails, the deferred always completes. No additional timeout is needed.

### NFR-4: No behavior change for the normal (non-hibernation) path
When `MainActivity` is the entry point, `switchGraph()` is called from `StelekitApp` as today. The auto-restore in `GraphManager.init` starts the same `switchGraph()` call first; the subsequent call from `StelekitApp` replaces the factory cleanly (existing behavior). This must not cause regressions.

## Success Criteria

1. After force-stopping and immediately opening the widget/share sheet, the capture UI appears (not "no graph")
2. After force-stopping and opening `MainActivity`, the app loads the graph as before (no regression)
3. If no graph is configured, all entry points still show the "no graph" / setup screen
4. Tests cover: auto-restore initializes RepositorySet; widget awaits migration; activity recomposes when graph becomes available

## Out of Scope

- Keeping the app process alive (foreground service / WorkManager keep-alive) — this is a deliberate OS decision
- File watching (`SafChangeDetector`) — it only runs while the main app is in the foreground, which is acceptable
- iOS, Desktop, Web — this is Android-specific
