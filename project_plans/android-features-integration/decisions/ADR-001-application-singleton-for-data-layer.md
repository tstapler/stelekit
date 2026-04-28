# ADR-001: Promote GraphManager to Application Scope for Widget/Tile/Share Access

**Status**: Proposed
**Date**: 2026-04-24
**Project**: android-features-integration

## Context

SteleKit's data layer (`GraphManager`, `RepositorySet`, `DatabaseWriteActor`, `GraphWriter`) is currently initialized inside `MainActivity.onCreate` and bound to the main Activity's lifecycle. There is no `Application` subclass.

Android home screen widgets (`AppWidgetProvider`), Quick Settings Tiles (`TileService`), and share targets (`Activity` with `ACTION_SEND` intent filter) can all be cold-started by the OS without `MainActivity` ever running. This means these components currently have no path to the data layer: `DriverFactory.staticContext` is null, `GraphManager` does not exist, and calling any repository method would throw `IllegalStateException`.

Three architectural options were evaluated for bridging this gap:

- **A. Application singleton** — Create `SteleKitApplication : Application`; initialize `GraphManager` in `Application.onCreate`; all Android components access it via `(application as SteleKitApplication).graphManager`.
- **B. WorkManager-deferred writes** — Widget captures enqueue a `CoroutineWorker`; the worker opens `GraphManager` when scheduled; no in-process data layer needed from non-Activity components.
- **C. ContentProvider** — Expose graph data through a `ContentProvider`; widget/tile reads via `query()`, writes via `insert()`.

## Decision

We decided to create a `SteleKitApplication` subclass that initializes `GraphManager` (and its dependencies: `SteleKitContext`, `DriverFactory`, `PlatformFileSystem`, `PlatformSettings`) in `Application.onCreate`, promoting the data layer to process scope. All Android components — `MainActivity`, `AppWidgetProvider`/`GlanceAppWidgetReceiver`, `TileService`, and the share `Activity` — access the graph via `(application as SteleKitApplication).graphManager`.

All writes from non-Activity components continue to flow through the existing `DatabaseWriteActor` + `GraphWriter` pipeline, unchanged.

## Alternatives Considered

- **WorkManager-deferred writes**: Rejected because WorkManager does not guarantee ordering relative to the main UI's debounced `GraphWriter` flush. A worker write racing with an active UI session breaks `DatabaseWriteActor`'s serialization invariant and creates `SQLITE_BUSY` risk. More critically, 2–15 s write latency violates the capture-UX requirement: a user who taps the widget and immediately opens the app will see a missing note.

- **ContentProvider**: Rejected because all Android components for SteleKit run in the same process — a ContentProvider would add a mandatory Binder round-trip with no benefit. The existing repository layer is built on `Flow`/`StateFlow`; wrapping it in cursor adapters adds high implementation cost and poor KMP testability.

- **Direct SQLite access from widget**: Rejected outright. Bypasses `DatabaseWriteActor` serialization, risks DB corruption, and leaves the Markdown file stale (only `GraphWriter` maintains the on-disk source of truth).

## Rationale

`GraphManager` initialization is cheap: its constructor reads `PlatformSettings` JSON (SharedPreferences) and builds an in-memory `GraphRegistry`. The SQLDelight driver opens lazily inside `RepositoryFactoryImpl` — no DB connection is created until the first repository call. A cold-started widget pays only a SharedPreferences read, not a full database open.

This option requires zero new synchronization primitives because `DatabaseWriteActor` already serializes all writes. Promoting `GraphManager` to `Application` scope simply makes the existing safety model available to more entry points.

## Consequences

**Positive:**
- Widget, tile, and share target all use the same write path as the main UI — no parallel code paths to maintain.
- `GraphManager` is fully unit-testable; the Application singleton does not require Espresso or UI tests for write-path validation.
- `DriverFactory` initialization race (null `staticContext`) is eliminated entirely.
- SAF (Storage Access Framework) URI permissions are held against `applicationContext`, which is process-scoped — restoring them in `Application.onCreate` makes them valid for all components.

**Negative / Risks:**
- `Application.onCreate` is now a harder failure point: if initialization throws (e.g., corrupt `PlatformSettings`), no Android component can start. Must guard with `try/catch` and fallback to a safe empty state.
- `GraphManager.getActiveGraphInfo()` returns `null` on first launch or if no graph has been configured. All non-Activity components must check for null and show a "No graph — open SteleKit" placeholder rather than crashing.
- `GraphManager.switchGraph()` cancels the current graph's `CoroutineScope`. A widget write racing with a graph switch receives `ClosedSendChannelException`. The capture Activity must treat `Result.failure` as a user-visible error with a retry option.

**Follow-up work:**
- Create `SteleKitApplication.kt` in `androidApp/src/main/kotlin/dev/stapler/stelekit/`.
- Add `android:name=".SteleKitApplication"` to `<application>` in `AndroidManifest.xml`.
- Refactor `MainActivity` to remove its `SteleKitContext.init` / `DriverFactory.setContext` calls (now owned by Application) and obtain `graphManager`/`fileSystem` from `(application as SteleKitApplication)`.
- Add null-graph guard to all new Android components before accessing any repository.

## Related

- Requirements: `project_plans/android-features-integration/requirements.md`
- Research synthesis: `project_plans/android-features-integration/research/synthesis.md`
- Supersedes: (none)
- Related ADRs: ADR-002 (Glance widget), ADR-003 (shared CaptureActivity)
