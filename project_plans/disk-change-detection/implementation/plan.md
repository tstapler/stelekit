# Implementation Plan: Disk Change Detection

## Overview

SteleKit's external-change detection had four failure modes: open-but-unedited pages were blocked from live reload, subdirectory `FileObserver`/`ContentObserver` events were silently missed, write-behind flushes dropped their mtime marker (causing spurious own-write events for encrypted files), and the shadow-cache freshness flag was process-wide instead of per-graph-instance. All four fixes have been implemented on the `stelekit-disk-load` branch. Two follow-up gaps — `FileObserver` dead-inode recovery after directory replacement and an mtime-recording race window in `ShadowFlushActor` — remain as `TODO` tasks.

---

## Epics

### Epic 1: Watcher Guard — Unsaved Edits Only

**Goal:** Replace the `activePageFilePaths`-based watcher guard (which blocked reload for all open pages) with a `dirtyPageUuids`-derived guard that only blocks reload for pages that actually have unsaved block edits. Open-but-unedited journal pages must reload automatically when an external change arrives.

**Status:** IMPLEMENTED

#### Stories

##### Story 1.1: `dirtyPageUuids` StateFlow in `BlockStateManager`

- [x] Task: Add `dirtyPageUuids: StateFlow<Set<String>>` derived via `combine(_dirtyBlocks, _blocks)` with `SharingStarted.Eagerly`
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/BlockStateManager.kt`
  - Description: `combine` cross-references block UUIDs from `_dirtyBlocks` with page→block lists from `_blocks` to yield only page UUIDs that have at least one dirty block. Short-circuits to `emptySet()` when `_dirtyBlocks` is empty. Declaration order: `_dirtyBlocks` must precede `dirtyPageUuids` to satisfy Kotlin's property-initializer ordering requirement.
  - Status: IMPLEMENTED
  - Test: `BlockStateManagerDirtyPageUuidsTest` — verifies `dirtyPageUuids` emits empty set when no edits, page UUID when a block edit starts, and returns to empty after save.

##### Story 1.2: `setUnsavedPageUuids` on `GraphLoaderPort` and `GraphLoader`

- [x] Task: Add `setUnsavedPageUuids(uuids: StateFlow<Set<String>>?)` to `GraphLoaderPort` interface
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoaderPort.kt`
  - Description: New interface method; allows `StelekitViewModel` to wire `BlockStateManager.dirtyPageUuids` into `GraphLoader` without a direct dependency.
  - Status: IMPLEMENTED

- [x] Task: Implement `setUnsavedPageUuids` in `GraphLoader`
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`
  - Description: Maintains `@Volatile private var unsavedPageFilePaths: Set<FilePath>` and `private var unsavedPageFilePathsJob: Job?`. On each call, cancels the previous job, collects the new `StateFlow`, and resolves each UUID to a `FilePath` via `pageRepository.getPageByUuid(...).first().getOrNull()`. Uses `parallelScope` (already owned by `GraphLoader`) to avoid scope escape. Sets `unsavedPageFilePaths = emptySet()` when `uuids == null`.
  - Status: IMPLEMENTED

##### Story 1.3: `GraphFileWatcher` guard updated to use `unsavedPageFilePaths`

- [x] Task: Replace `activePageFilePaths` guard lambda with `unsavedPageFilePaths` in `checkDirectoryForChanges`
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt`
  - Description: Guard at line ~254 changed from `activePageFilePaths?.invoke()?.contains(...)` (all open pages) to a check against `unsavedPageFilePaths` (dirty-block pages only). Open-but-unedited pages are no longer excluded from auto-reload.
  - Status: IMPLEMENTED
  - Test: `GraphFileWatcherTest` TR-1 — open-but-unedited page triggers `onReloadFile` on next poll after external write; TR-2 — dirty page skips `onReloadFile`, `onDirtyFile` still fires, `externalFileChanges` still emits.

##### Story 1.4: `StelekitViewModel` wires `dirtyPageUuids`

- [x] Task: Call `graphLoader.setUnsavedPageUuids(blockStateManager.dirtyPageUuids)` in `StelekitViewModel.init`
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
  - Description: Called alongside existing `setActivePageUuids` wiring. On graph unload/switch, call `setUnsavedPageUuids(null)` to cancel the collection job and clear the guard.
  - Status: IMPLEMENTED

---

### Epic 2: SafChangeDetector — Subdirectory Coverage

**Goal:** Both fast-path change detectors (`FileObserver` for MANAGE_EXTERNAL_STORAGE and `ContentObserver` for SAF) must fire for file events in `pages/` and `journals/` subdirectories, not just at the graph root.

**Status:** IMPLEMENTED (with one follow-up gap for directory deletion/recreation)

#### Stories

##### Story 2.1: `FileObserver` watches `pages/` and `journals/` subdirectories

- [x] Task: Replace single root-level `FileObserver` with two subdirectory `FileObserver` instances
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt`
  - Description: `startFileObservers` creates one `FileObserver(File(graphPath, "pages"), mask)` and one `FileObserver(File(graphPath, "journals"), mask)` with mask `CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO`. Both use `filter { it.isDirectory }` before calling `start()` to skip missing subdirs gracefully (handles SAF-only graphs with no real path). Root-level watcher removed.
  - Status: IMPLEMENTED

##### Story 2.2: `ContentObserver` registered on subdirectory children URIs

- [x] Task: Replace single root-children `ContentObserver` with two subdirectory children `ContentObserver` registrations
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt`
  - Description: `startContentObserversAndPoller` builds `"$treeDocId/pages"` and `"$treeDocId/journals"` document IDs and calls `buildChildDocumentsUriUsingTree(treeUri, subdirDocId)` for each. Registers two `ContentObserver` instances with `notifyForDescendants = true`. Registration wrapped in `try/catch` — providers that reject registration are logged, not thrown.
  - Status: IMPLEMENTED

##### Story 2.3 (Follow-up): `FileObserver` dead-inode recovery after directory replacement

- [ ] Task: Handle `FileObserver.DELETE_SELF` to recover from directory deletion/recreation
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt`
  - Description: Add `FileObserver.DELETE_SELF | FileObserver.MOVE_SELF` to the watch mask. On receipt, call `stopWatching()` on the dead observer and schedule a retry loop on the graph's scope that polls until the directory reappears (e.g., `while (!dir.isDirectory) delay(2000)`) then re-arms by calling `startWatching()`. Without this, a `mv pages pages.bak && mkdir pages` from Termux leaves the watcher pointing at a dead inode; the 5-second poll on JVM and `onStart` scan recover eventually, but on Android MANAGE_EXTERNAL_STORAGE path there is no `ContentObserver` fallback — up to 30 seconds of missed events until the next foreground transition.
  - Status: TODO

---

### Epic 3: ShadowFlushActor — Flush Callback and Scope Cleanup

**Goal:** After a write-behind SAF flush succeeds, `markWrittenByUs` must be invoked with the post-flush SAF path so `FileRegistry` records the new mtime and suppresses the subsequent poll event. The actor must not own a `CoroutineScope` (prevents scope leaks).

**Status:** IMPLEMENTED (with one follow-up for the mtime-recording race window)

#### Stories

##### Story 3.1: `ShadowFlushActor` accepts `onFlushed` callback and calls it on success

- [x] Task: Add `onFlushed: (suspend (safPath: String) -> Unit)?` parameter and invoke it after each successful SAF write
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFlushActor.kt`
  - Description: After `fileSystem.writeFile(safPath, content)` returns true and shadow mtime is stamped, `onFlushed?.invoke(safPath)` is called. `onFlushed` is NOT called on write failure (queue retains entry) or when the shadow file is missing (entry dequeued, no retry).
  - Status: IMPLEMENTED
  - Tests: `ShadowFlushActorTest` TR-3, TR-4, TR-5 — success fires callback with correct path; failure does not fire; missing shadow does not fire.

##### Story 3.2: `ShadowFlushActor` made stateless (`suspend fun`, no owned scope)

- [x] Task: Remove owned `CoroutineScope` from `ShadowFlushActor`; make `flush()` a plain `suspend fun`
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFlushActor.kt`
  - Description: Eliminates scope that was never cancelled (coroutine/memory leak). `flush()` runs on `Dispatchers.IO` via `withContext`; caller provides suspension context. No `start()`/`stop()` lifecycle methods. `PlatformFileSystem.flushPendingWrites()` calls `actor.flush()` as a suspending call directly.
  - Status: IMPLEMENTED

##### Story 3.3 (Follow-up): Pre-write flush-pending set to close mtime race window

- [ ] Task: Record SAF path in a "flush-pending" set before `writeFile()`, clear it in `onFlushed`; check this set in `FileRegistry.detectChanges`
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFlushActor.kt` and `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`
  - Description: The current implementation records the post-flush mtime in `onFlushed`, but there is a race: `ProcessLifecycleOwner.onStart` can trigger `detectChanges` in the window between `writeFile()` returning and `onFlushed` being invoked (~5–50 ms of Binder IPC time). For encrypted `.md.stek` files — where the content-hash guard is disabled — `detectChanges` sees a newer SAF mtime and emits a spurious conflict dialog. Fix: add a `private val flushPendingPaths: ConcurrentHashMap<String, Unit>` (or `MutableSet` with mutex); add path before `writeFile()`, remove in `onFlushed` (success or failure); in `FileRegistry.detectChanges`, skip files present in this set.
  - Status: TODO

---

### Epic 4: ShadowFileCache — Per-Instance Freshness Flag

**Goal:** Each `ShadowFileCache` instance (one per graph per session) must perform a full shadow purge on first access, regardless of whether other graphs have already been accessed in this process. This ensures stale cached content from a backgrounded graph is not served after a graph switch.

**Status:** IMPLEMENTED

#### Stories

##### Story 4.1: Replace process-wide `freshProcess` with per-instance `freshInstance`

- [x] Task: Remove `freshProcess: AtomicBoolean` from `PlatformFileSystem.Companion`; add `private val freshInstance = AtomicBoolean(true)` to `ShadowFileCache`
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt`
  - Description: `isFirstAccess()` returns `true` exactly once per `ShadowFileCache` instance (first call resets to `false`). `PlatformFileSystem.invalidateStaleShadow` calls `cache.isFirstAccess()` to branch between full-purge (`cache.deleteAll()` + SAF re-sync) and mtime-based invalidation. On graph switch, a new `ShadowFileCache` instance is created with `freshInstance = AtomicBoolean(true)`, so the new graph always gets a full purge.
  - Status: IMPLEMENTED
  - Test: `ShadowFileCacheTest` TR-6 — `isFirstAccess()` returns `true` on first call and `false` on all subsequent calls; two independent instances each have their own flags.

---

### Epic 5: FileSystem Interface — `setOnFlushComplete` Plumbing

**Goal:** Expose a `setOnFlushComplete` hook on the common `FileSystem` interface so `App.kt` can wire `graphLoader::markFileWrittenByUs` into the write-behind flush path without a platform-specific import.

**Status:** IMPLEMENTED

#### Stories

##### Story 5.1: `setOnFlushComplete` default no-op on `FileSystem` interface

- [x] Task: Add `fun setOnFlushComplete(callback: (suspend (String) -> Unit)?)` with default no-op body to `FileSystem`
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`
  - Description: Default implementation is a no-op so non-Android implementations (JVM, iOS, WASM) require no change. Only `PlatformFileSystem` overrides it.
  - Status: IMPLEMENTED

##### Story 5.2: `PlatformFileSystem` stores callback and passes it to `ShadowFlushActor`

- [x] Task: Override `setOnFlushComplete` in `PlatformFileSystem`; store callback; pass to `ShadowFlushActor` in `flushPendingWrites()`
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
  - Description: `private var onFlushComplete: (suspend (String) -> Unit)? = null`. `flushPendingWrites()` passes it as the `onFlushed` parameter to `actor.flush(onFlushed = onFlushComplete)`.
  - Status: IMPLEMENTED

##### Story 5.3: `App.kt` wires `graphLoader::markFileWrittenByUs` as flush callback

- [x] Task: Call `fileSystem.setOnFlushComplete(graphLoader::markFileWrittenByUs)` in `App.kt` initialization
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
  - Description: Wired alongside the existing `GraphWriter` `onFileWritten` callback so both direct-write and write-behind paths record their mtime in `FileRegistry` after each successful write.
  - Status: IMPLEMENTED

---

### Epic 6: Testing

**Goal:** Validate all implemented behavior with automated tests; document CI constraints for Android-specific tests.

**Status:** IMPLEMENTED (see note on Android SDK constraint)

#### Stories

##### Story 6.1: `GraphFileWatcherTest` — open-but-unedited page reloads (TR-1 and TR-2)

- [x] Task: Add `GraphFileWatcherTest` cases for TR-1 and TR-2
  - File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphFileWatcherTest.kt`
  - Description: TR-1 — journal page open with no dirty blocks triggers `onReloadFile` on next poll after external write. TR-2 — page with unsaved edits does NOT trigger `onReloadFile`; `onDirtyFile` fires; `externalFileChanges` emits.
  - Status: IMPLEMENTED

##### Story 6.2: `ShadowFlushActorTest` — `onFlushed` callback behavior (TR-3, TR-4, TR-5)

- [x] Task: Add `ShadowFlushActorTest` cases for TR-3, TR-4, TR-5
  - File: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ShadowFlushActorTest.kt`
  - Description: TR-3 — successful SAF write invokes `onFlushed` with correct SAF path and drains queue. TR-4 — failed write does not invoke `onFlushed`; entry retained in queue. TR-5 — missing shadow dequeues entry without invoking `onFlushed`.
  - Status: IMPLEMENTED
  - Note: `ShadowFlushActorTest` runs in `androidUnitTest` source set and requires Android SDK in the test classpath. On Linux CI without the Android SDK configured, this test class may need to be skipped or run under `testDebugUnitTest` with Robolectric. Confirm this runs in `ciCheck`.

##### Story 6.3: `ShadowFileCacheTest` — per-instance `isFirstAccess` (TR-6)

- [x] Task: Add `ShadowFileCacheTest` case for TR-6
  - File: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/ShadowFileCacheTest.kt`
  - Description: `isFirstAccess()` returns `true` on first call, `false` on all subsequent calls. Two independently constructed instances have independent flags.
  - Status: IMPLEMENTED

---

### Epic 7: Follow-Up Work (NOT YET IMPLEMENTED)

**Goal:** Address two remaining correctness gaps identified in pitfalls research that were not part of the initial implementation scope.

**Status:** TODO

#### Stories

##### Story 7.1: `FileObserver` dead-inode recovery (see Epic 2 Story 2.3)

- [ ] Task: Add `DELETE_SELF | MOVE_SELF` to `FileObserver` mask and implement restart logic
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt`
  - Description: See Epic 2 Story 2.3 for full specification. This is the highest-severity open gap: a sync client replacing the `pages/` or `journals/` directory atomically (e.g., `mv` + `mkdir`) leaves the watcher pointing at a dead inode with no automatic recovery until the next foreground transition. Affects only users with `MANAGE_EXTERNAL_STORAGE` (no `ContentObserver` fallback on that code path).
  - Status: TODO

##### Story 7.2: `ShadowFlushActor` pre-write flush-pending set (see Epic 3 Story 3.3)

- [ ] Task: Track paths in a "flush-pending" set before `writeFile()` and skip them in `detectChanges`
  - File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFlushActor.kt` and `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`
  - Description: See Epic 3 Story 3.3 for full specification. Closes the ~5–50 ms race window for encrypted `.md.stek` graphs where a concurrent `onStart` scan sees the post-flush SAF mtime before `onFlushed` records it, emitting a spurious conflict dialog. Low-frequency but deterministic for encrypted graphs under foreground pressure.
  - Status: TODO

##### Story 7.3: (Low priority) `combine` performance — `distinctUntilChanged` on `dirtyPageUuids`

- [ ] Task: Add `distinctUntilChanged()` after `combine(_dirtyBlocks, _blocks)` and before `stateIn` in `BlockStateManager`
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/BlockStateManager.kt`
  - Description: Every reactive block emission from any loaded page (not just dirty pages) triggers a recompute of the `combine`. With many pinned pages in the sidebar, this causes repeated recomputation even when the dirty set hasn't changed. `distinctUntilChanged()` prevents downstream recomposition when the resulting set is equal to the previous emission. Low-risk, low-priority.
  - Status: TODO

##### Story 7.4: (Low priority) UUID→path resolution: retry for in-flight DB writes

- [ ] Task: Add retry logic in `GraphLoader.setUnsavedPageUuids` collector for pages whose DB write is still in flight
  - File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`
  - Description: When the user creates a new page optimistically and its DB write is still queued behind `DatabaseWriteActor`, `pageRepository.getPageByUuid(...).first()` returns `null`, and `mapNotNull` silently omits that UUID from `unsavedPageFilePaths`. If an external change arrives for that path during the write-in-flight window, the dirty-page guard is empty and `onReloadFile` fires, clobbering the just-typed content. Fix: `retryWhen { _, attempt -> attempt < 5 && delay(50).let { true } }` around the `.first()` call, or use the raw UUID set as a fallback guard in `GraphFileWatcher`. Low-frequency (new-page creation race); surfaced via conflict dialog in current code.
  - Status: TODO

---

## Architectural Decisions

The following design decisions have ADR-level significance and may warrant formal ADR documents:

1. **Guard driven by dirty-block UUIDs, not open-page UUIDs** (Epic 1): The watcher guard split between "all open pages" (used by background indexer via `activePageUuids`) and "dirty-block pages" (used by watcher guard via `dirtyPageUuids`) is a core design choice. Alternatives: (a) a single guard for both use cases, (b) a per-file suppress() call from the UI on every open page. The chosen approach minimizes false-positive suppression and avoids any UI coupling to the watcher.

2. **`onFlushed` callback threading model** (Epic 3): The callback runs on `Dispatchers.IO` inside `ShadowFlushActor.flush()`. `FileRegistry.markWrittenByUs` takes `detectMutex` — the same mutex that `detectChanges` holds during a poll. If `flush()` and a poll race, they serialize on the mutex. This is correct but means the callback cannot take an application-managed lock that the poll coroutine already holds.

3. **Per-instance `freshInstance` flag vs. process-wide** (Epic 4): The per-instance design directly supports multi-graph sessions without any global coordination. The process-wide alternative (Companion singleton) was a latent bug; the per-instance model is strictly more correct. No ADR needed, but the rationale should be captured in code comments.
