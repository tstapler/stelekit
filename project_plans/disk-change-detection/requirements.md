# Requirements: Disk Change Detection — Journals Refresh & Detection Reliability

## Overview

SteleKit allows users to edit their notes with external tools (Termux, sync clients, desktop editors) while the app is running. The app must detect these external changes reliably and reload affected pages without discarding the user's own in-progress edits.

Three distinct failure modes were identified:

1. **Journals page never refreshes on poll** — the watcher guard treated all open pages (even unedited ones) as "active" and skipped `onReloadFile`. Opening the journals screen prevented live updates from appearing, even with no edits in progress.

2. **Fast-path detectors never fire for subdirectory changes** — `FileObserver` watched the graph root directory inode (inotify is not recursive); `ContentObserver` was registered on the root children URI which doesn't string-prefix-match change notifications from `pages/` or `journals/` children.

3. **Write-behind flush drops mtime marker** — `ShadowFlushActor` flushed dirty shadow pages to SAF but did not call `markWrittenByUs`, causing the next poll to emit a spurious own-write event for encrypted `.md.stek` files (where the content-hash guard is disabled). Also, `ShadowFlushActor` created a `CoroutineScope` that was never cancelled (scope leak).

4. **Graph switch skips shadow purge for new graph** — `freshProcess: AtomicBoolean` in `PlatformFileSystem.Companion` was process-wide. When the user switched graphs in a session, the second graph's `ShadowFileCache` never got its initial full purge, so external writes during the backgrounded period could be silently served from a stale shadow.

---

## Goals

- **G1 — Journals page live refresh**: An open-but-unedited journal page must automatically reload when an external change is detected on the next poll cycle, without requiring the user to navigate away and back.
- **G2 — Reliable fast-path detection on Android**: Both `FileObserver` (inotify) and `ContentObserver` (SAF) must fire for changes in `pages/` and `journals/` subdirectories, not just at the graph root.
- **G3 — Flush mtime integrity**: After a write-behind SAF flush completes successfully, `markWrittenByUs` must be called so `FileRegistry` records the post-flush mtime and suppresses the subsequent poll event.
- **G4 — Per-graph shadow freshness**: On first access of each `ShadowFileCache` instance (not just first process start), the shadow directory must be fully purged and re-synced from SAF.
- **G5 — No scope leaks**: Long-lived objects must own their own `CoroutineScope` with cancellation; transient operations must be stateless suspending functions.

---

## Non-Goals

- No new user-visible UI (no new dialogs, settings, or permissions).
- No changes to the `.md.stek` encryption format.
- No new SAF permission flows.
- No changes to how the app handles file conflicts (conflict dialog behavior unchanged).

---

## Platforms

| Platform | Scope |
|----------|-------|
| Android (SAF) | Primary — all five fixes apply here |
| JVM/Desktop | Guard fix (G1) applies; file watching uses `java.nio.file.WatchService`, not `FileObserver` |
| iOS / WASM | Shared code must compile; no platform-specific changes needed |

---

## Functional Requirements

### FR-1 — Watcher guard uses unsaved-edit pages only
The `GraphFileWatcher` active-page guard must skip `onReloadFile` only for pages that have **unsaved block edits** (dirty blocks), not for pages that are merely open/observed. A page that is open but not edited must be reloaded normally when an external change is detected.

**Acceptance**: Opening the journals page and waiting ≥ 5 seconds after an external write results in the journal content updating on screen.

### FR-2 — `dirtyPageUuids` StateFlow in `BlockStateManager`
`BlockStateManager` must expose a `dirtyPageUuids: StateFlow<Set<String>>` derived from the intersection of `_dirtyBlocks` (block UUIDs with unsaved content) and `_blocks` (page UUID → block list). This set contains only page UUIDs that have at least one dirty block.

**Acceptance**: `dirtyPageUuids` emits an empty set when no blocks are being edited; emits the page UUID when a block edit starts; returns to empty when the edit is saved.

### FR-3 — `setUnsavedPageUuids` on `GraphLoaderPort`
`GraphLoaderPort` must expose `setUnsavedPageUuids(uuids: StateFlow<Set<String>>?)`. `GraphLoader` must implement it by maintaining an `unsavedPageFilePaths: Set<FilePath>` field (derived by resolving UUID → file path) and using this set as the watcher guard instead of `activePageFilePaths`.

### FR-4 — `StelekitViewModel` wires `dirtyPageUuids`
`StelekitViewModel.init` must call `graphLoader.setUnsavedPageUuids(blockStateManager.dirtyPageUuids)` alongside the existing `setActivePageUuids` call.

### FR-5 — `FileObserver` watches pages/ and journals/ subdirectories
`SafChangeDetector.startFileObserver` must create two `FileObserver` instances — one for `$graphPath/pages` and one for `$graphPath/journals` — rather than watching the graph root. Both must use the same change mask (`CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO`). The `start()` method must filter to directories that exist.

**Acceptance**: Writing a file under `$graphPath/pages/` triggers `onExternalChange` within 1 second when `MANAGE_EXTERNAL_STORAGE` is granted.

### FR-6 — `ContentObserver` registered on subdirectory children URIs
`SafChangeDetector.startContentObserversAndPoller` must register `ContentObserver` instances on `buildChildDocumentsUriUsingTree(treeUri, "$treeDocId/pages")` and `buildChildDocumentsUriUsingTree(treeUri, "$treeDocId/journals")` — not on the root children URI.

**Acceptance**: A SAF provider that delivers `ContentObserver` notifications for subdirectory changes triggers `onExternalChange` for file changes in `pages/` and `journals/`.

### FR-7 — `ShadowFlushActor` calls `onFlushed` after each successful SAF write
`ShadowFlushActor` must accept an optional `onFlushed: (suspend (safPath: String) -> Unit)?` callback. After a successful `fileSystem.writeFile()` call, and after stamping the shadow mtime, `onFlushed` must be invoked with the SAF path. It must NOT be called on write failure or when the shadow is missing.

### FR-8 — `FileSystem.setOnFlushComplete` plumbing
`FileSystem` (common interface) must expose `fun setOnFlushComplete(callback: (suspend (String) -> Unit)?)` with a default no-op body. `PlatformFileSystem` must override it, store the callback, and pass it to `ShadowFlushActor` in `flushPendingWrites()`. `App.kt` must wire this callback to `graphLoader::markFileWrittenByUs`.

### FR-9 — `ShadowFlushActor` is stateless (no owned scope)
`ShadowFlushActor` must not create a `CoroutineScope`. `flush()` is a `suspend fun` that runs on `Dispatchers.IO`. No `start()`/`stop()` lifecycle; callers control suspension.

### FR-10 — `freshProcess` flag is per-`ShadowFileCache` instance
`ShadowFileCache` must own a `private val freshInstance: AtomicBoolean(true)` and expose `fun isFirstAccess(): Boolean`. `PlatformFileSystem.invalidateStaleShadow` must call `cache.isFirstAccess()` to decide between full purge and mtime-based invalidation. The `freshProcess` companion field must be removed.

---

## Testing Requirements

### TR-1 — GraphFileWatcherTest: open-but-unedited page reloads
A journal page that is open but has no dirty blocks must trigger `onReloadFile` on the next poll cycle when an external change is detected.

### TR-2 — GraphFileWatcherTest: dirty page is NOT auto-reloaded
A page with unsaved edits (in `activePageFilePaths`) must NOT trigger `onReloadFile`. `onDirtyFile` must still be called. `externalFileChanges` must still emit (conflict dialog path).

### TR-3 — ShadowFlushActorTest: onFlushed fires on success
After a successful SAF write, `onFlushed` is called with the correct SAF path. Queue is drained.

### TR-4 — ShadowFlushActorTest: onFlushed NOT called on failure
When `writeFile` returns false, `onFlushed` is not called. Queue retains the entry.

### TR-5 — ShadowFlushActorTest: onFlushed NOT called when shadow missing
When shadow file does not exist, the entry is dequeued (no retry) but `onFlushed` is not called.

### TR-6 — ShadowFileCacheTest: isFirstAccess is per-instance
`isFirstAccess()` returns `true` on first call and `false` on all subsequent calls. Two cache instances have independent flags.

---

## Constraints

- All writes to `SteleDatabaseQueries` must go through `DatabaseWriteActor` (existing `@DirectSqlWrite` enforcement).
- `rememberCoroutineScope()` must not be passed to any class that outlives composition.
- The `_dirtyBlocks` declaration must precede `dirtyPageUuids` in `BlockStateManager` to avoid "variable must be initialized" compile error.
- `FileObserver` directories that don't exist at `start()` time must be skipped gracefully (graph may be on SAF-only path where real paths aren't available).
- `ContentObserver` registration failures must be caught and logged, not thrown (some SAF providers may reject the registration).
