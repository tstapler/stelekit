# Android SAF Performance — Implementation Plan

**Feature**: Android SAF Performance  
**Status**: Planning  
**Target**: Android (KMP — `androidMain` + `commonMain`)

---

## Table of Contents

1. [Epic Overview](#epic-overview)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Current State Assessment](#current-state-assessment)
5. [Architecture Overview](#architecture-overview)
6. [Architecture Decisions](#architecture-decisions)
7. [Dependency Graph](#dependency-graph)
8. [Story Breakdown](#story-breakdown)
   - [Story 1: Fix Phase 3 / BlockStateManager Race Condition](#story-1-fix-race-condition)
   - [Story 2: Eliminate DocumentFile Overhead in Hot Paths](#story-2-documentfile-elimination)
   - [Story 3: Lazy Phase 3 Indexing](#story-3-lazy-phase3)
   - [Story 4: SAF Shadow Copy](#story-4-shadow-copy)
9. [Known Issues](#known-issues)
10. [Test Strategy](#test-strategy)
11. [Integration Checkpoints](#integration-checkpoints)
12. [Success Criteria](#success-criteria)

---

## Epic Overview

### User Value

On a flagship Pixel 8 Pro class device with a 1000+ page personal wiki stored via Storage Access Framework (SAF), the app is unusably slow for daily note-taking. Creating bullets, reordering blocks, and inserting page links from search are noticeably laggy. Session logs confirmed individual pages taking 4–48 seconds to index. Edit operations are silently dropped because Phase 3 background indexing invalidates `BlockStateManager`'s in-memory block UUID references mid-edit.

This epic makes the app interactive on real-world Android graphs.

### Confirmed Evidence

```
Performance  06:23:17  Slow operation detected: parseAndSavePage took 4377ms
Performance  06:23:12  Slow operation detected: parseAndSavePage took 48497ms
BlockStateManager 06:23:00  applyContentChange: block not found — content update dropped
GraphWriter  06:22:52  Saved page to: saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki%2Flogseq/journals/2026_04_29.md
```

### Root Causes (confirmed)

| Root Cause | Mechanism | Fix |
|-----------|-----------|-----|
| SAF Binder IPC per-file | `contentResolver.openInputStream()` crosses process boundary for every `readFile()` call during Phase 3; each call 15–100ms+ | Stories 2, 3, 4 |
| Phase 3 starves interactive saves | `DatabaseWriteActor` serializes all writes; 1000+ page Phase 3 run queues LOW writes for minutes | Story 3 (eliminates volume) |
| BlockStateManager UUID race | Phase 3 replaces blocks for pages being edited, invalidating in-memory UUID references | Story 1 |
| DocumentFile metadata round-trip | `fileExists()` and `directoryExists()` call `DocumentFile.fromSingleUri().exists()`, adding an extra metadata query per call | Story 2 |

### Already Done

- PRAGMA tuning: `wal_autocheckpoint=4000`, `temp_store=MEMORY`, `cache_size=-8000` in `DriverFactory.android.kt`
- Android benchmark upgraded to MEDIUM 500-page graph
- `safIoOverhead` benchmark test added

### Scope

**In scope:**
- Phase 3 / BlockStateManager race condition fix
- `DocumentFile` elimination in `fileExists()` and `directoryExists()`
- Lazy Phase 3 indexing (index on-demand, background idle sweep)
- SAF shadow copy (all reads from `filesDir`, writes to both)
- Search "still indexing" indicator for lazy phase 3

**Out of scope:**
- iOS performance
- JVM performance (excellent; no regression target only)
- `AhoCorasickMatcher` search performance
- UI frame rendering jank unrelated to data loading
- Parallel SAF reads (Option D) — explicitly rejected; see ADR-004

---

## Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| SAF-01 | Phase 3 must not replace blocks for a page that has an active `BlockStateManager` editing session | Must |
| SAF-02 | `fileExists()` and `directoryExists()` must not call `DocumentFile.fromSingleUri()` for SAF paths | Must |
| SAF-03 | On a 1000-page SAF graph, Phase 3 background indexing must not block interactive write operations by more than 50ms | Must |
| SAF-04 | `parseAndSavePage` must complete in under 500ms per page on a flagship device with a SAF-backed graph | Must |
| SAF-05 | Search must remain functional while lazy indexing is in progress; a "still indexing" indicator must be shown | Must |
| SAF-06 | The SAF tree URI remains the write target (source of truth); shadow copy is a read cache only | Must |
| SAF-07 | On graph open, the shadow copy sync must complete in under 3 seconds for a 1000-page graph | Should |
| SAF-08 | External file changes detected by `SafChangeDetector` must re-sync the corresponding shadow file | Should |
| SAF-09 | No regression on JVM Phase 1, Phase 3, or write p95 benchmark numbers | Must |

---

## Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Performance** | Write p95 during Phase 3 contention must stay below 50ms on MEDIUM benchmark graph |
| **Data Integrity** | SAF is always written first; shadow is a read cache; crash between SAF write and shadow write is safe (stale shadow is re-synced on next startup via mtime comparison) |
| **Reliability** | If shadow copy fails to sync a file, Phase 3 falls back to SAF read for that file (no silent data loss) |
| **Maintainability** | `PlatformFileSystem` shadow logic must be isolated behind a `ShadowFileCache` helper class; `GraphLoader` must not know whether it is reading from shadow or SAF |
| **Observability** | `PerformanceMonitor` spans must cover shadow sync duration and per-file shadow read vs. SAF read on fallback |
| **Backwards Compatibility** | Non-SAF graphs (internal storage) must be unaffected; all SAF-specific code is gated on `path.startsWith("saf://")` |

---

## Current State Assessment

### What Exists (Correctly)

| Component | File | Status |
|-----------|------|--------|
| PRAGMA tuning | `DriverFactory.android.kt:48–57` | Done — `wal_autocheckpoint=4000`, `temp_store=MEMORY`, `cache_size=-8000` |
| `DatabaseWriteActor` priority queue | `DatabaseWriteActor.kt:112–113` | Correct — HIGH drains before LOW; `processSaveBlocks` preempts batch for HIGH |
| `listFilesWithModTimes` batch cursor | `PlatformFileSystem.kt:330–340` | Correct — single `queryChildren()` round trip for directory listing |
| MEDIUM benchmark (500 pages) | `AndroidGraphBenchmark.kt:246` | Done |
| `safIoOverhead` benchmark | `AndroidGraphBenchmark.kt:181` | Done |
| `indexRemainingPages` chunked batch | `GraphLoader.kt:447–501` | Correct chunk/flush pattern; missing edit-session guard |
| `flushChunkWritesPreemptible` | `GraphLoader.kt:542–579` | Correct per-page atomic delete+insert; HIGH can preempt between pages |

### What Is Broken / Missing

| Issue | File | Lines | Problem |
|-------|------|-------|---------|
| No edit-session guard in `indexRemainingPages` | `GraphLoader.kt` | 466–479 | Phase 3 calls `parseAndSavePage` for pages currently held by `BlockStateManager`, replacing their blocks with new UUIDs |
| `fileExists()` uses `DocumentFile` | `PlatformFileSystem.kt` | 253–260 | Each call adds an extra `query()` metadata round-trip via Binder IPC |
| `directoryExists()` uses `DocumentFile` | `PlatformFileSystem.kt` | 262–276 | Same overhead; called during every `sanitizeDirectory` pass |
| Phase 3 reads all pages eagerly at startup | `GraphLoader.kt` | 447–501 | Reads 1000+ files via SAF at startup even if user never visits most pages |
| No shadow copy | `PlatformFileSystem.kt` | — | Every `readFile()` call crosses SAF Binder IPC boundary |
| Read-before-write in `savePageInternal` | `GraphWriter.kt` | 200–208 | Safety check reads old file content via SAF on every debounced autosave |

---

## Architecture Overview

```
User edits block (BlockStateManager)
        │
        ▼
GraphWriter.queueSave()  →  500ms debounce  →  savePageInternal()
                                                     │
                              [Story 4]              ├── writeFile(safPath)   ← SAF write (source of truth)
                                                     └── writeFile(shadowPath) ← shadow update

GraphLoader.indexRemainingPages()  [Story 3: lazy + background]
        │
        ├── [Story 1] skip if page.uuid in BlockStateManager.activePageUuids
        │
        └── readFile(path)
              │
              [Story 4] ShadowFileCache.resolve(safPath) → shadowPath (filesDir)
                                └── File(shadowPath).readText()  ← ~0.1ms, no Binder IPC

PlatformFileSystem.fileExists(safPath)  [Story 2]
        │
        └── DocumentsContract cursor query (no DocumentFile wrapper)
```

### Key Invariants

1. SAF is the only persistent write target. Shadow is a read-only cache derived from SAF.
2. Shadow files are never written directly from outside `ShadowFileCache`. All shadow updates go through `ShadowFileCache.update(safPath, content)`.
3. `GraphLoader` uses the `FileSystem` interface for all file I/O. It does not know whether it is reading from shadow or SAF. The shadow resolution happens inside `PlatformFileSystem.readFile()` when a shadow is available.
4. `indexRemainingPages` checks `activePageUuids` before every `parseAndSavePage` call. The check is a non-suspending `StateFlow.value` read (no lock contention).

---

## Architecture Decisions

| # | File | Decision |
|---|------|----------|
| ADR-004 | `project_plans/android-save-parse-performance/decisions/ADR-004-lazy-phase3-vs-shadow-copy.md` | Implement Lazy Phase 3 before Shadow Copy; shadow copy gated on post-lazy measurement |
| ADR-005 | `project_plans/android-save-parse-performance/decisions/ADR-005-phase3-edit-session-guard.md` | Expose `activePageUuids: StateFlow<Set<String>>` from `BlockStateManager`; `indexRemainingPages` skips pages in the set |

---

## Dependency Graph

```
Story 1 (Race condition fix)
    │
    └── independent — ships first, zero risk

Story 2 (DocumentFile elimination)
    │
    └── independent — ships in parallel with Story 1

Story 1 ──┐
Story 2 ──┤
           └─→ Story 3 (Lazy Phase 3)
                   │
                   └── measure write latency from session exports
                               │
                               └─→ Story 4 (Shadow Copy) [gated on measurement]
```

Stories 1 and 2 are independent and can be implemented in parallel. Story 3 depends on Story 1 (the edit-session guard must be in place before reducing Phase 3 indexing volume; otherwise a lazy-loaded page can still race with an edit session). Story 4 is gated on measurement from Story 3.

---

## Story Breakdown

---

### Story 1: Fix Phase 3 / BlockStateManager Race Condition {#story-1-fix-race-condition}

**User value**: Edit operations (creating bullets, moving blocks, inserting links) are never silently dropped because Phase 3 invalidated the underlying block UUIDs.  
**Design reference**: ADR-005

#### Acceptance Criteria

- `BlockStateManager` exposes `activePageUuids: StateFlow<Set<String>>` containing the UUID of every page with an open editing session.
- `GraphLoader.indexRemainingPages` skips any page whose UUID is in `activePageUuids` at the time of the check.
- `applyContentChange: block not found` log line is eliminated during concurrent Phase 3 + editing sessions in tests.
- Skipped pages are re-indexed on the next app start (warm reconcile) or on post-save trigger.

---

#### Task 1.1 — Expose `activePageUuids` from `BlockStateManager` [2h]

**Objective**: Add a `StateFlow<Set<String>>` that tracks the set of page UUIDs with an active in-progress edit session.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (instantiates `BlockStateManager`)

**Implementation Approach**:
1. Add `private val _activePageUuids = MutableStateFlow<Set<String>>(emptySet())` to `BlockStateManager`.
2. Expose `val activePageUuids: StateFlow<Set<String>> = _activePageUuids.asStateFlow()`.
3. When a page editor is opened (when `BlockStateManager` loads blocks for a page), add `pageUuid` to `_activePageUuids` via `.update { it + pageUuid }`.
4. When the page editor is closed or the editing session ends (on `clearBlocks()` or equivalent), remove the UUID: `.update { it - pageUuid }`.
5. The set must be updated before any `DatabaseWriteActor` calls in the open/close paths to ensure Phase 3 sees a consistent view.

**Validation Strategy**:
- Unit test in `businessTest`: open a `BlockStateManager` for page UUID "page-A"; assert `activePageUuids.value == setOf("page-A")`; close; assert set is empty.
- Unit test: open two sessions for "page-A" and "page-B"; assert set contains both.

**INVEST Check**:
- Independent: no other story depends on completing this task first
- Valuable: provides the guard signal required by Task 1.2
- Estimable: 2h — two state updates, one StateFlow exposure
- Small: confined to `BlockStateManager`
- Testable: `StateFlow.value` assertions are deterministic

---

#### Task 1.2 — Add edit-session guard to `indexRemainingPages` [2h]

**Objective**: Skip `parseAndSavePage` for pages whose UUID is in `activePageUuids` during Phase 3 background indexing.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `indexRemainingPages` (lines 447–501)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` constructor — add nullable `activePageUuids` parameter

**Implementation Approach**:
1. Add `private val activePageUuids: StateFlow<Set<String>>?` parameter to `GraphLoader` constructor (default `null` for backwards compatibility with tests that don't supply it).
2. In `indexRemainingPages`, after reading `unloadedPages`, filter: skip any page whose `uuid` is in `activePageUuids?.value ?: emptySet()`. Log at DEBUG: `"Skipping Phase 3 re-index for ${page.name} — active edit session"`.
3. The check is a non-suspending `StateFlow.value` read. Do not acquire any mutex; do not suspend.
4. Wire the parameter: in `RepositorySet` or `GraphManager`, pass `blockStateManager.activePageUuids` when constructing `GraphLoader`.

**Validation Strategy**:
- Integration test in `businessTest` or `jvmTest`: create a `GraphLoader` with a `MutableStateFlow<Set<String>>` pre-populated with page UUID "page-A". Call `indexRemainingPages`. Assert `parseAndSavePage` is NOT called for "page-A" (verify via a spy or by checking the DB block count does not change for that page).
- Regression test: verify that pages NOT in `activePageUuids` are still indexed normally.

**INVEST Check**:
- Depends on: Task 1.1 (needs `activePageUuids` StateFlow)
- Valuable: eliminates the `applyContentChange: block not found` error
- Estimable: 2h — one filter clause, one constructor parameter, one wiring change
- Small: three files, confined change
- Testable: integration test with spy on `parseAndSavePage`

---

### Story 2: Eliminate `DocumentFile` Overhead in Hot Paths {#story-2-documentfile-elimination}

**User value**: Graph enumeration and directory checks during startup and `sanitizeDirectory` are faster; each file check no longer incurs an extra Binder IPC metadata round-trip.  
**Design reference**: `findings-saf-optimization.md` Option C

#### Acceptance Criteria

- `fileExists()` for SAF paths does not call `DocumentFile.fromSingleUri()`. It uses a direct `DocumentsContract` cursor query.
- `directoryExists()` for SAF paths does not call `DocumentFile.fromSingleUri()`. It uses a direct `DocumentsContract` cursor query.
- The `safIoOverhead` benchmark shows no regression (overhead ratio for `fileExists`/`directoryExists` should decrease).
- `getLastModifiedTime()` is updated consistently (it also uses `DocumentFile.fromSingleUri()`).

#### Acceptance Criteria Detail

The `DocumentsContract` direct query pattern is already proven in `queryChildren()`. `fileExists` and `directoryExists` must adopt the same pattern: build the document URI, issue a `contentResolver.query()` with `COLUMN_MIME_TYPE` projection, check if the cursor has one row and the MIME type matches (`MIME_TYPE_DIR` for directories, non-dir for files).

---

#### Task 2.1 — Replace `DocumentFile` in `fileExists()` with direct cursor query [2h]

**Objective**: Eliminate the extra `query()` metadata round-trip from `DocumentFile.fromSingleUri().exists()` by querying `COLUMN_MIME_TYPE` directly via `DocumentsContract`.

**Context Boundary**:
- Primary: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — `fileExists()` (lines 252–260)
- Supporting: same file — `queryChildren()` (lines 145–171) as the reference pattern

**Implementation Approach**:
1. Extract a private helper `queryDocumentMimeType(docUri: Uri): String?` that runs:
   ```kotlin
   context.contentResolver.query(
       docUri,
       arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
       null, null, null
   )?.use { cursor ->
       if (cursor.moveToFirst()) cursor.getString(0) else null
   }
   ```
2. Replace `DocumentFile.fromSingleUri(ctx, docUri)?.exists() == true && df.isFile` in `fileExists()` with: `queryDocumentMimeType(docUri)?.let { it != DocumentsContract.Document.MIME_TYPE_DIR } == true`.
3. Keep the `SecurityException` and `IllegalArgumentException` catch blocks unchanged.

**Validation Strategy**:
- Instrumented test: call `fileExists()` on a known SAF file; assert returns `true`. Call on a non-existent path; assert returns `false`. Call on a directory; assert returns `false`.
- `safIoOverhead` benchmark run before and after — confirm overhead ratio does not increase.

---

#### Task 2.2 — Replace `DocumentFile` in `directoryExists()` with direct cursor query [1h]

**Objective**: Same as Task 2.1 but for `directoryExists()`.

**Context Boundary**:
- Primary: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — `directoryExists()` (lines 262–276)

**Implementation Approach**:
1. Reuse the `queryDocumentMimeType` helper from Task 2.1.
2. Replace `DocumentFile.fromSingleUri(ctx, docUri)?.exists() == true && df.isDirectory` with: `queryDocumentMimeType(docUri) == DocumentsContract.Document.MIME_TYPE_DIR`.
3. Remove the existing `Log.d` calls that are only needed because `DocumentFile` was opaque.

**Validation Strategy**:
- Instrumented test: call `directoryExists()` on a known SAF directory; assert `true`. Call on a file path; assert `false`. Call on non-existent path; assert `false`.

---

#### Task 2.3 — Replace `DocumentFile` in `getLastModifiedTime()` with direct cursor query [1h]

**Objective**: `getLastModifiedTime()` also uses `DocumentFile.fromSingleUri()` (line 321). Apply the same direct cursor pattern.

**Context Boundary**:
- Primary: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — `getLastModifiedTime()` (lines 319–328)

**Implementation Approach**:
1. Add `queryDocumentLastModified(docUri: Uri): Long?` helper using `COLUMN_LAST_MODIFIED` projection.
2. Replace `DocumentFile.fromSingleUri(ctx, docUri)?.lastModified()?.takeIf { it > 0L }` with the direct cursor helper.

**Validation Strategy**:
- Instrumented test: write a file via SAF; call `getLastModifiedTime()`; assert returned value is within 2 seconds of `System.currentTimeMillis()`.

---

### Story 3: Lazy Phase 3 Indexing {#story-3-lazy-phase3}

**User value**: App startup is interactive within seconds even on a 1000-page SAF graph. Pages are indexed on-demand when navigated to; remaining pages are indexed by a background idle worker.  
**Design reference**: ADR-004, `findings-saf-optimization.md` Option B

#### Acceptance Criteria

- On a fresh cold start with a 1000-page SAF graph, Phase 1 TTI is under 3 seconds.
- `indexRemainingPages` is not called eagerly at startup; it runs as a background idle job.
- Navigating to an un-indexed page triggers `GraphLoader.loadFullPage()` (already exists) before the page content is displayed.
- Search shows a "still indexing" indicator while background indexing is incomplete; results include only indexed pages.
- The `loadPhaseTimings` benchmark passes with `phase3Ms` reported separately from Phase 1 TTI.
- Story 1 edit-session guard is in place before this story ships (required dependency).

---

#### Task 3.1 — Remove eager `indexRemainingPages` call from startup path [2h]

**Objective**: Stop calling `indexRemainingPages` eagerly in `loadGraphProgressive` and the warm-start reconcile path. The call is currently at `GraphLoader.kt:337` (warm reconcile) and implicitly via `loadDirectory(ParseMode.METADATA_ONLY)` which feeds `getUnloadedPages`.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `loadGraphProgressive` (lines 278–395)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — `onFullyLoaded` callback

**Implementation Approach**:
1. Identify all callsites of `indexRemainingPages` — currently only called from `StelekitViewModel` in the `onFullyLoaded` callback after `loadGraphProgressive`.
2. Remove the eager `indexRemainingPages` call from `onFullyLoaded`.
3. Replace with a background idle worker launch (Task 3.2).
4. Ensure `getUnloadedPages()` still returns the correct set — `loadDirectory(ParseMode.METADATA_ONLY)` still runs and populates page stubs, so `getUnloadedPages()` has a correct list for the background worker to process.

**Validation Strategy**:
- `loadPhaseTimings` benchmark: `phase1Ms` should not include Phase 3 time. Assert `phase1Ms < 3000` on MEDIUM graph.
- Manual: open app on a 1000-page SAF graph; verify UI is interactive (can navigate, type) within 3 seconds.

---

#### Task 3.2 — Launch background idle indexer with lowest priority [3h]

**Objective**: After `onFullyLoaded`, launch a background coroutine that calls `indexRemainingPages` page-by-page at `Priority.LOW`, yielding between pages so the dispatcher serves any pending HIGH-priority work.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `indexRemainingPages`

**Implementation Approach**:
1. In `StelekitViewModel`, after `onFullyLoaded` fires, launch a coroutine on `Dispatchers.Default` at `CoroutineStart.UNDISPATCHED` with the following pattern:
   ```kotlin
   viewModelScope.launch(Dispatchers.Default + CoroutineName("lazy-phase3")) {
       delay(500) // let UI settle after Phase 1
       loader.indexRemainingPages { progress ->
           _indexingProgress.value = IndexingState.InProgress(progress)
       }
       _indexingProgress.value = IndexingState.Complete
   }
   ```
2. Expose `_indexingProgress: MutableStateFlow<IndexingState>` from `StelekitViewModel`. `IndexingState` is a sealed class: `Idle`, `InProgress(message: String)`, `Complete`.
3. Cancel the lazy-phase3 job if the user switches graphs (existing `cancelBackgroundWork()` already exists on `GraphLoader` and should be called here too).

**Validation Strategy**:
- `writeLatencyDuringPhase3` benchmark: `jankFactor` must stay below 5.0 (existing gate). With lazy indexing, the gate should be reachable even on real SAF paths.
- Unit test: verify `IndexingState.Complete` is emitted after all pages are indexed.

---

#### Task 3.3 — Add "still indexing" indicator to search UI [2h]

**Objective**: Show a non-intrusive indicator in the search dialog/results when `indexingProgress` is `InProgress`. Search results are still shown (from already-indexed pages); the indicator communicates that results may be incomplete.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/` — search dialog composable
- Supporting: `StelekitViewModel.kt` — `indexingProgress` StateFlow

**Implementation Approach**:
1. Collect `indexingProgress` in the search composable via `collectAsState()`.
2. When state is `InProgress`, show a single-line `Text("Still indexing pages — some results may be missing")` below the search field, using `MaterialTheme.colorScheme.outline` for low visual weight.
3. When `Complete` or `Idle`, show nothing.

**Validation Strategy**:
- Manual: open search immediately after app start on a large SAF graph; verify indicator is shown. Wait for indexing to complete; verify indicator disappears.
- Screenshot test (Roborazzi): render the search composable in `InProgress` state; assert the indicator text is visible.

---

#### Task 3.4 — Trigger per-page re-index on navigation to un-indexed page [2h]

**Objective**: When `StelekitViewModel.navigateTo(pageUuid)` opens a page whose `isContentLoaded == false`, trigger `GraphLoader.loadFullPage(pageUuid)` before rendering block content. This is the on-demand indexing path.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — `navigateTo()`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `loadFullPage()` (lines 587–639; already exists)

**Implementation Approach**:
1. In `navigateTo(pageUuid)`, after resolving the page from the repository, check `page.isContentLoaded`. If `false`, call `loader.loadFullPage(pageUuid)` before emitting the navigation state.
2. While `loadFullPage` is running, emit a loading state to the UI (e.g. `PageState.Loading`).
3. `loadFullPage` already has the freshness check: if the page is already loaded and the file hasn't changed, it returns immediately. No double-load risk.

**Validation Strategy**:
- Integration test: load graph with `ParseMode.METADATA_ONLY` for all pages. Navigate to a specific page. Assert `isContentLoaded == true` after navigation.
- Manual: navigate to a page that was not in Phase 1; verify content loads within 1 second.

---

### Story 4: SAF Shadow Copy {#story-4-shadow-copy}

**User value**: All Phase 3 reads and `savePageInternal` read-before-write checks use direct `filesDir` I/O (~0.1ms) instead of SAF Binder IPC (15–100ms+). This eliminates the last remaining SAF read overhead after Lazy Phase 3 reduces read volume.

**Gate**: Only implement after Story 3 ships and session exports confirm that write-path SAF reads (read-before-write in `savePageInternal`) or lazy-load SAF reads are still contributing > 50ms to interactive latency.  
**Design reference**: ADR-004, `findings-saf-optimization.md` Option A

#### Acceptance Criteria

- On graph open, all markdown files are copied from SAF to `context.filesDir/graphs/<graphId>/shadow/` using `listFilesWithModTimes()` for mtime comparison (one batch cursor, not per-file queries).
- `PlatformFileSystem.readFile()` for SAF paths returns shadow content when shadow is fresh; falls back to SAF read when shadow is absent or stale.
- After `GraphWriter.savePageInternal()` writes to SAF, the shadow file is updated with the new content.
- When `SafChangeDetector` fires (external change), the changed file's shadow is invalidated and re-synced.
- Shadow directory is stored at `context.filesDir/graphs/<encodedTreeDocId>/shadow/`. Shadow is deleted if SAF permission is revoked.
- The `loadPhaseTimings` benchmark `phase3Ms` (lazy background) does not regress after shadow copy is added.

---

#### Task 4.1 — Implement `ShadowFileCache` helper class [4h]

**Objective**: Encapsulate shadow directory management: startup sync, per-file update, invalidation, and fallback-to-SAF logic.

**Context Boundary**:
- New file: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt`
- Supporting: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

**Implementation Approach**:
1. `ShadowFileCache(context: Context, graphId: String)` — shadow root is `context.filesDir/graphs/$graphId/shadow/`.
2. `suspend fun syncFromSaf(listModTimes: List<Pair<String, Long>>, readSafFile: (String) -> String?)` — iterates the pre-fetched mtime list; copies only files where `shadow.lastModified() != safMtime`. Runs on `Dispatchers.IO`.
3. `fun resolve(safPath: String): File?` — returns the shadow `File` if it exists and `lastModified() > 0`, else `null`.
4. `fun update(fileName: String, content: String)` — writes content to the shadow file after a SAF write.
5. `fun invalidate(fileName: String)` — deletes the shadow file (forces re-sync on next `resolve`).
6. `fun deleteAll()` — clears the entire shadow directory (called on SAF permission revoke).

**Validation Strategy**:
- Unit tests in `androidUnitTest`: `syncFromSaf` with mock mtime list; assert only stale files are copied. `resolve` returns null for missing shadow, non-null for present. `update` writes correct content.

---

#### Task 4.2 — Integrate shadow reads into `PlatformFileSystem.readFile()` [2h]

**Objective**: When a shadow file is available and fresh, return its content directly from `filesDir` instead of going through SAF `ContentResolver`.

**Context Boundary**:
- Primary: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — `readFile()` (lines 188–197)
- Supporting: `ShadowFileCache` (Task 4.1)

**Implementation Approach**:
1. In `readFile(path)`, before the SAF `openInputStream` call, check `shadowCache?.resolve(fileName)`:
   ```kotlin
   val shadow = shadowCache?.resolve(path.substringAfterLast('/'))
   if (shadow != null) return shadow.readText()
   ```
2. If shadow is null or `readText()` throws, fall through to the existing SAF read path. Log at DEBUG: `"shadow miss for $path — falling back to SAF"`.
3. `shadowCache` is nullable: non-SAF graphs have no shadow cache.

**Validation Strategy**:
- Instrumented test: populate shadow for a file; call `readFile()`; verify shadow file is read (instrument with a counter on `shadow.readText()` vs SAF reads).
- Fallback test: delete shadow file; call `readFile()`; verify SAF read succeeds and shadow miss is logged.

---

#### Task 4.3 — Write shadow after SAF write in `GraphWriter` path [2h]

**Objective**: After `fileSystem.writeFile(safPath, content)` succeeds in `savePageInternal`, update the shadow copy so it stays in sync.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` — `savePageInternal()` (lines 191–286)
- Supporting: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — add `fun updateShadow(path: String, content: String)` to `FileSystem` interface (default no-op)

**Implementation Approach**:
1. Add `fun updateShadow(path: String, content: String)` to `FileSystem` interface with a default no-op body. Android implementation calls `shadowCache?.update(fileName, content)`.
2. In `GraphWriter.savePageInternal()` Saga Step 1, after the `writeFile` action succeeds, call `fileSystem.updateShadow(filePath, content)`. Shadow update failure is non-fatal (log and continue — shadow will be re-synced on next startup).
3. Shadow update compensation (if saga rolls back): call `fileSystem.invalidate(filePath)` to force re-sync.

**Validation Strategy**:
- Integration test: write a page via `GraphWriter`; assert shadow file exists and contains the new content.
- Saga rollback test: make `writeFile` fail; assert shadow is not updated (compensation path).

---

#### Task 4.4 — Startup shadow sync and external change invalidation [3h]

**Objective**: On graph open, sync the shadow from SAF using the existing `listFilesWithModTimes()` batch cursor. On `externalFileChanges`, invalidate the shadow for the changed file.

**Context Boundary**:
- Primary: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `startWatching()` and `externalFileChanges` flow

**Implementation Approach**:
1. In `PlatformFileSystem`, add `suspend fun syncShadow(graphPath: String)` that:
   - Calls `listFilesWithModTimes(graphPath)` for `pages/` and `journals/` subdirectories.
   - Passes the result to `shadowCache.syncFromSaf(modTimes) { readFile(safPath) }`.
   - Runs on `Dispatchers.IO`.
2. Call `syncShadow()` from `GraphLoader.loadGraphProgressive` during the warm-start reconcile phase, before `loadDirectory` runs. This ensures shadow is warm before Phase 3 reads start.
3. In `GraphLoader.checkDirectoryForChanges()`, when an external change is detected for a file, call `fileSystem.invalidate(filePath)` (add default no-op to `FileSystem` interface) so the shadow is re-synced on next read.
4. On SAF permission revoke (detected in `isSafPermissionValid` returning false), call `shadowCache.deleteAll()`.

**Validation Strategy**:
- Instrumented test: generate 50 files in SAF mock; call `syncShadow()`; assert all 50 shadow files exist.
- Stale test: write a file to SAF directly (bypassing app); call `syncShadow()`; assert shadow is updated.
- External change test: emit external change; assert `invalidate()` is called for the changed file.

---

## Known Issues

### Bug 001: Block UUID Invalidation Race [SEVERITY: CRITICAL]

**Description**: Phase 3 `indexRemainingPages` calls `parseAndSavePage` for pages currently being edited by the user. This deletes and replaces all blocks for those pages with new UUIDs. `BlockStateManager` holds the old UUIDs. Subsequent calls from `BlockStateManager` to the repository (e.g. `applyContentChange`, `updateBlockContentOnly`, `moveBlock`) silently fail with "block not found". The content change is dropped with no user-visible error. This was confirmed in live session logs: `applyContentChange: block not found — content update dropped`.

**Mitigation**:
- Story 1 (Task 1.1 + 1.2) adds the edit-session guard that skips Phase 3 re-indexing for any page with an active `BlockStateManager` session.
- Story 1 must ship before Story 3 (Lazy Phase 3), since lazy indexing runs Phase 3 at lower priority but does not eliminate the race; the guard eliminates the race.

**Files Likely Affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `indexRemainingPages` (skip guard)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` — `activePageUuids` exposure

**Prevention Strategy**:
- Integration test: start Phase 3 indexing for page A while `BlockStateManager` holds page A's UUID. Assert `parseAndSavePage` is NOT called for page A.
- Regression test: edit a block; confirm the edit is persisted after Phase 3 completes.

**Related Tasks**: Tasks 1.1, 1.2

---

### Bug 002: Shadow Divergence on App Crash [SEVERITY: Medium]

**Description**: `savePageInternal` writes SAF first, then shadow. If the app crashes between these two steps, the shadow file is stale (contains old content). On next startup, `syncShadow()` detects the mtime mismatch and re-copies the SAF file — this is correct. However, if the mtime of the SAF file and shadow file are identical (e.g. the crash happened after SAF write but the mtime was the same second), the sync skips the stale shadow.

**Mitigation**:
- Use `shadowMtime < safMtime` (strict less-than, not equals) for the stale detection. SAF writes update the mtime; if shadow mtime equals SAF mtime, the shadow was last updated simultaneously with SAF, which means it is current.
- Add a shadow version marker (e.g. first line `# shadow:<safMtime>`) to detect sub-second staleness. This is optional — a 1-second granularity miss is a theoretical risk only.

**Files Likely Affected**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt` — `syncFromSaf` mtime comparison logic

**Prevention Strategy**:
- Unit test: simulate crash scenario by calling `update()` on SAF only (not shadow); call `syncFromSaf()`; assert shadow is updated.

**Related Tasks**: Tasks 4.1, 4.3

---

### Bug 003: Lazy Indexer Cancellation on Graph Switch [SEVERITY: Medium]

**Description**: When the user switches graphs while the lazy background indexer is running, `cancelBackgroundWork()` is called on the old `GraphLoader`. If the indexer is mid-chunk (between `flushChunkWritesPreemptible` calls), it will complete the current chunk's DB writes but then be cancelled before the next chunk. Pages in the current chunk are left partially indexed (page saved, blocks saved, correct). Pages in subsequent chunks are not indexed until the user navigates to them (on-demand load path). This is correct behavior but must be tested.

**Mitigation**:
- `cancelBackgroundWork()` already exists on `GraphLoader` and cancels `backgroundIndexJob`. Verify it is called on graph switch in `StelekitViewModel`.
- The lazy indexer loop must check `isActive` between chunks and exit cleanly on cancellation.

**Files Likely Affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `indexRemainingPages` (add `yield()` or `isActive` check between chunks)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — graph switch path

**Prevention Strategy**:
- Integration test: start `indexRemainingPages`; call `cancelBackgroundWork()` mid-run; assert no further DB writes occur after cancellation; assert the job completes without exception.

**Related Tasks**: Tasks 3.1, 3.2

---

### Bug 004: `sanitizeDirectory` DocumentFile Calls [SEVERITY: Low]

**Description**: `sanitizeDirectory` calls `fileSystem.fileExists(newPath)` (line 724 in `GraphLoader.kt`). After Story 2 fixes `fileExists()`, this is resolved. However, `sanitizeDirectory` also calls `fileSystem.directoryExists(path)` (line 710), `fileSystem.readFile(oldPath)`, and `fileSystem.writeFile(newPath, content)`, and `fileSystem.deleteFile(oldPath)` — each a separate Binder IPC round-trip via SAF. On a 1000-page graph with many files requiring sanitization, this can still be slow.

**Mitigation**:
- Story 2 removes `DocumentFile` from `fileExists` and `directoryExists`. The remaining `readFile`/`writeFile`/`deleteFile` SAF calls in `sanitizeDirectory` are unavoidable for any files that actually need sanitization (malformed filenames).
- On a healthy graph (no malformed filenames), `sanitizeDirectory` does nothing beyond a `listFiles` call. The cost is bounded by the number of malformed filenames, which is typically zero.
- For Story 4, when shadow copy is in place, `sanitizeDirectory`'s `readFile` calls will hit the shadow cache.

**Files Likely Affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `sanitizeDirectory` (lines 709–738)

**Prevention Strategy**: No code change required. Monitor via `PerformanceMonitor.startTrace("sanitizeDirectory")` span.

**Related Tasks**: Tasks 2.1, 2.2

---

### Bug 005: Search Returns Stale Results During Lazy Indexing [SEVERITY: Low]

**Description**: While the background lazy indexer is running, `SearchRepository` does not have full-text content for un-indexed pages. A search for content that only appears in un-indexed pages returns no results until that page is indexed. The "still indexing" indicator (Task 3.3) communicates this limitation, but users may still be surprised if a known page does not appear in search results.

**Mitigation**:
- Metadata-only pages (Phase 2 stubs) are in the DB and will appear in page-name search. Block content search will miss them.
- The lazy background indexer (Task 3.2) runs continuously at low priority, so un-indexed pages are indexed within minutes of app start.
- For frequently-accessed pages, on-demand indexing (Task 3.4) ensures they are fully indexed immediately on first navigation.

**Files Likely Affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/` — search composable (Task 3.3 adds indicator)

**Prevention Strategy**: The "still indexing" indicator is the accepted UX mitigation. No code change to search logic required.

**Related Tasks**: Task 3.3

---

## Test Strategy

### Unit Tests (`businessTest` / `jvmTest`)

| Test | File | Coverage |
|------|------|----------|
| `activePageUuids` state transitions | `BlockStateManagerTest.kt` | Story 1: open/close edit session |
| `indexRemainingPages` skips active pages | `GraphLoaderTest.kt` | Story 1: guard check |
| `ShadowFileCache.syncFromSaf` copies only stale files | `ShadowFileCacheTest.kt` | Story 4: mtime comparison |
| `ShadowFileCache.resolve` returns null on miss | `ShadowFileCacheTest.kt` | Story 4: fallback path |
| Lazy indexer emits `IndexingState.Complete` | `StelekitViewModelTest.kt` | Story 3: state emission |

### Instrumented Tests (`androidInstrumentedTest`)

| Test | Coverage |
|------|----------|
| `fileExists()` via direct cursor, no `DocumentFile` | Story 2: regression guard |
| `directoryExists()` via direct cursor | Story 2: regression guard |
| `readFile()` prefers shadow over SAF | Story 4: shadow read path |
| Shadow updated after `GraphWriter.savePage` | Story 4: write-through |
| `syncShadow()` updates stale shadow files | Story 4: startup sync |

### Android Benchmarks (`AndroidGraphBenchmark`)

| Benchmark | Gate | Story |
|-----------|------|-------|
| `loadPhaseTimings` — `phase1Ms < 3000` | Existing + tightened | Story 3 |
| `writeLatencyDuringPhase3` — `jankFactor < 2.0` (tightened from 5.0) | Tighten after Story 3 | Story 3 |
| `safIoOverhead` — overhead ratio should decrease | No hard gate; track trend | Story 2 |

---

## Integration Checkpoints

### Checkpoint 1: After Stories 1 and 2

**Deliverables**:
- `BlockStateManager.activePageUuids` exposed and wired to `GraphLoader`
- `fileExists()` / `directoryExists()` / `getLastModifiedTime()` use direct cursor queries
- `applyContentChange: block not found` log eliminated in manual testing
- All unit and instrumented tests for Stories 1 and 2 pass

**Measurement**:
- Run `writeLatencyDuringPhase3` benchmark; record `jankFactor` baseline with edit-session guard active
- Collect session export from device; confirm no more "block not found" log lines during Phase 3

### Checkpoint 2: After Story 3

**Deliverables**:
- Phase 1 TTI < 3 seconds on MEDIUM benchmark
- Background lazy indexer running; "still indexing" indicator functional in search
- On-demand load on navigation working for un-indexed pages

**Measurement**:
- Run `loadPhaseTimings` benchmark; verify `phase1Ms < 3000`
- Collect session export from device after Story 3; measure `file.readCheck` spans in `savePageInternal` — if > 50ms per save, proceed to Story 4

### Checkpoint 3: After Story 4 (conditional)

**Deliverables** (if measurement from Checkpoint 2 justifies shadow copy):
- Shadow directory populated on graph open
- `readFile()` serving from shadow for all Phase 3 reads
- Write-through shadow update working
- External change invalidation working

**Measurement**:
- `safIoOverhead` benchmark: `directMs` and `safMs` should converge (shadow reads bypass SAF)
- Session export: `file.readCheck` span duration should drop to < 1ms for shadow-served reads

---

## Success Criteria

| Criterion | Target | Story |
|-----------|--------|-------|
| `applyContentChange: block not found` eliminated | Zero occurrences during Phase 3 + editing | Story 1 |
| Phase 1 TTI on MEDIUM benchmark | < 3 seconds | Story 3 |
| Write p95 during Phase 3 contention | < 50ms | Story 3 |
| `parseAndSavePage` per page (shadow path) | < 5ms | Story 4 |
| `fileExists()` / `directoryExists()` SAF overhead | Reduced by eliminating extra metadata query | Story 2 |
| JVM benchmark regression | None — Phase 1, Phase 3, write p95 unchanged | All |
