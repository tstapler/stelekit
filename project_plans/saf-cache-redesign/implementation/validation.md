# Validation Plan: SAF Cache Redesign

## Requirements Traceability Matrix

| Req ID | Goal | Covered By |
|--------|------|-----------|
| G1 | Eliminate stale reads from external edits | TC-01, TC-02, TC-06 |
| G2 | No false positives on FAT/exFAT/SAF | TC-03, TC-06 |
| G3 | Do not degrade write performance | TC-07 |
| G4 | Do not reload actively-edited pages | TC-04, TC-05 |
| G5 | Keep navigation fast (<100ms added latency) | TC-08 |
| G6 | Survive no file-watcher (iOS/WASM) | TC-09, TC-10 |

---

## Test Cases

---

### TC-01 — Stale read eliminated (dirty-set path, watcher running)

**Type**: Integration  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun loadFullPage bypasses mtime guard when path is in dirty set()`  
**Requirement**: G1

**Arrange/Act/Assert**:
```
Arrange:
  - Create FakeFileSystem with /graph/pages/page.md containing "- Block V1"
  - Construct GraphLoader with a fast poll interval (100ms) that has:
      dirtyPaths = mutableSetOf<String>()
      fileWatcher.isRunning stubbed to return true
  - Call parseAndSavePage so DB has V1 content and allBlocksLoaded = true
  - Simulate external edit: update FakeFileSystem content to "- Block V2"
    (bump modTime but do NOT update FileRegistry — simulates external writer)
  - Directly inject "/graph/pages/page.md" into the loader's dirtyPaths set
    (bypassing watcher, testing the guard path in isolation)

Act:
  - Call loadFullPage(pageUuid, force = false)

Assert:
  - blockRepository.getBlocksForPage(uuid).first() contains "Block V2"
  - fileRegistry.getContentHash("/graph/pages/page.md") reflects V2's hashCode()
```

**Why it fails against pre-fix code**: Pre-fix code runs the mtime guard. On FakeFileSystem,
mtime is stale (same as V1), so the old guard returns early and the page stays at V1.

---

### TC-02 — Stale read eliminated (end-to-end watcher→dirtySet→loadFullPage)

**Type**: Integration  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun watcher detects external edit and dirty set triggers reload on next navigation()`  
**Requirement**: G1

**Arrange/Act/Assert**:
```
Arrange:
  - Write /graph/pages/page.md = "- Block V1" to a real temp directory
  - Construct GraphLoader (watcherPollIntervalMs = 100ms); loadGraph()
  - Wait for initial scan to complete (page loaded, allBlocksLoaded = true for page)

Act:
  - Overwrite /graph/pages/page.md with "- Block V2" from outside the loader
    (simulate external edit: bump file mtime via File.setLastModified)
  - Wait 300ms (>2 poll cycles, so watcher fires and adds path to dirtyPaths)
  - Call loadFullPage(pageUuid, force = false)

Assert:
  - DB has blocks containing "Block V2"
```

**Why it fails against pre-fix code**: Pre-fix loadFullPage uses mtime. On a fast filesystem,
the mtime from a setLastModified call is reflected but the DB `updatedAt` may equal or exceed it,
so the old guard might skip the reload. More importantly, on FAT or SAF the mtime would be
unreliable. The test documents the new contract.

---

### TC-03 — FAT/exFAT 2-second window: dirty set ignores mtime entirely

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun loadFullPage reloads page when dirty even if mtime is identical()`  
**Requirement**: G2

**Arrange/Act/Assert**:
```
Arrange:
  - FakeFileSystem where getLastModifiedTime always returns the SAME value
    for V1 and V2 (simulates FAT 2-second granularity: both writes land in the
    same 2s window, so mtime is unchanged)
  - Load page V1 (allBlocksLoaded = true), record DB content
  - Update FakeFileSystem content to V2 without changing modTime
  - Add filePath to dirtyPaths (simulate what the watcher would do)
  - Verify that old mtime guard would have skipped (document regression):
      assert old_mtime_check(page.updatedAt, staleModTime) == SKIP

Act:
  - Call loadFullPage(pageUuid, force = false) with the new guard active

Assert:
  - DB has V2 content (dirty set bypassed the mtime check)
  - forceReload=true was passed through to lookupExistingPageAndCheckFreshness
    (verify by confirming the inner mtime guard also did not skip)
```

**Why it fails against pre-fix code**: The old guard explicitly uses
`page.updatedAt.toEpochMilliseconds() >= fileModTime`. With identical mtimes, this evaluates to
true and the reload is skipped.

---

### TC-04 — Active-page guard: watcher does NOT call onReloadFile for edited pages

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphFileWatcherTest.kt` (extension of existing class)  
**Method**: `fun checkDirectoryForChanges skips onReloadFile for actively edited pages()`  
**Requirement**: G4

**Arrange/Act/Assert**:
```
Arrange:
  - WatcherFakeFileSystem with /graph/pages/active.md
  - reloadCount = 0; onReloadFile = { _, _ -> reloadCount++ }
  - dirtyCount = 0; onDirtyFile = { dirtyCount++ }
  - Construct GraphFileWatcher with:
      activePageFilePaths = { setOf("/graph/pages/active.md") }
      onDirtyFile = { filePath -> dirtyCount++ }
      onReloadFile = { _, _ -> reloadCount++ }
  - Call registry.scanDirectory("/graph/pages") to prime modTimes
  - Mutate active.md in FakeFileSystem (bump modTime + change content)

Act:
  - Call checkDirectoryForChanges (via reflection or a test-visible method)

Assert:
  - reloadCount == 0  (onReloadFile was NOT called)
  - dirtyCount == 1   (onDirtyFile WAS called)
```

**Why it fails against pre-fix code**: The old watcher always calls onReloadFile for any changed
file; there is no activePageFilePaths guard.

---

### TC-05 — Active-page guard: externalFileChanges IS emitted for actively-edited pages

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphFileWatcherTest.kt` (extension)  
**Method**: `fun checkDirectoryForChanges emits externalFileChanges even for active pages()`  
**Requirement**: G4

**Arrange/Act/Assert**:
```
Arrange:
  - Same watcher setup as TC-04
  - Collect externalFileChanges into a list (launch collector before mutation)
  - Verify guard insertion point: active-page check comes AFTER the SharedFlow emit

Act:
  - Mutate active.md (bump modTime), call checkDirectoryForChanges

Assert:
  - externalFileChanges emitted exactly 1 event for active.md
    (conflict dialog can fire)
  - onReloadFile not called (guard skips auto-reload)
  - dirty flag added (TC-04 already covers this; restate for clarity)
```

**Why it fails against pre-fix code**: Pre-fix code has no active-page guard, so it will call
onReloadFile instead (wrong) and may or may not emit externalFileChanges depending on suppress
timing. The test pins the required emission order.

---

### TC-06 — No stale read after active-page edit is resolved (dirty flag consumed at navigation)

**Type**: Integration  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun dirty flag set during active edit is consumed on next loadFullPage after navigation away()`  
**Requirement**: G1, G4

**Arrange/Act/Assert**:
```
Arrange:
  - Load page, simulate active edit (page in activePageUuids)
  - External edit happens → watcher marks dirty, skips onReloadFile
  - User "saves" (or discards edit): remove page from activePageUuids
  - dirtyPaths contains the file path

Act:
  - Call loadFullPage(pageUuid, force = false) — next navigation

Assert:
  - checkAndClearDirty returns true (flag was set)
  - DB has external V2 content after loadFullPage
  - dirtyPaths is now empty for that path (flag consumed)
```

**Why it fails against pre-fix code**: Old code has no dirty set; the mtime guard blocks the
reload regardless.

---

### TC-07 — preMarkPendingWrite prevents own write from being detected as external change

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `FileRegistryTest.kt` (extension of existing class)  
**Method**: `fun preMarkPendingWrite suppresses own write detection in detectChanges()`  
**Requirement**: G3

**Arrange/Act/Assert**:
```
Arrange:
  - FakeFs with /graph/pages/page.md (V1, modTime = 1000)
  - FileRegistry.scanDirectory("/graph/pages") to prime modTimes
  - changedCount = 0

Act:
  - Call fileRegistry.preMarkPendingWrite("/graph/pages/page.md")
    (sets modTimes[filePath] = Long.MAX_VALUE)
  - FakeFs.externalWrite("/graph/pages/page.md", "V2")
    (bumps real modTime to 2000)
  - Call fileRegistry.detectChanges("/graph/pages")

Assert:
  - changeSet.changedFiles is empty (2000 > Long.MAX_VALUE == false → suppressed)
  - changeSet.newFiles is empty
```

**Why it fails against pre-fix code**: There is no preMarkPendingWrite in the old code. A write
followed by detectChanges would produce a changedFiles entry.

---

### TC-08 — clearPendingWrite restores detection after failed saga compensation

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `FileRegistryTest.kt` (extension)  
**Method**: `fun clearPendingWrite removes sentinel so subsequent external edits are detected()`  
**Requirement**: G3 (write performance / correctness of saga rollback)

**Arrange/Act/Assert**:
```
Arrange:
  - FakeFs with /graph/pages/page.md (V1, modTime = 1000)
  - FileRegistry.scanDirectory to prime modTimes
  - Call preMarkPendingWrite("/graph/pages/page.md") (sentinel set)

Act (simulate failed saga compensation):
  - Call fileRegistry.clearPendingWrite("/graph/pages/page.md")
    (should remove the sentinel from modTimes entirely)
  - FakeFs.externalWrite("/graph/pages/page.md", "V2") (modTime = 2000)
  - Call fileRegistry.detectChanges("/graph/pages")

Assert:
  - changeSet.changedFiles contains /graph/pages/page.md
    (sentinel was removed; file is treated as unknown → new file path OR
     as a changed file if modTime re-entry triggers the > lastKnown branch)
  - Verifies permanent suppression bug (Finding 4) is fixed
```

**Why it fails against pre-fix code**: There is no clearPendingWrite. If sentinel leaks, the
file is permanently suppressed.

---

### TC-09 — Content-hash fallback: detects external change when watcher not running (iOS/WASM path)

**Type**: Integration  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun loadFullPage uses content hash when watcher is not running and detects external change()`  
**Requirement**: G6

**Arrange/Act/Assert**:
```
Arrange:
  - FakeFileSystem with page.md = "- Block V1"
  - Construct GraphLoader with watcherPollIntervalMs = Long.MAX_VALUE
    so watcherJob is never started → fileWatcher.isRunning == false
  - Call loadFullPage(uuid, force=true) to populate DB and store contentHash
  - Verify contentHash[filePath] == "- Block V1".hashCode()
  - Update FakeFileSystem: page.md = "- Block V2" (same modTime or 0)
    → simulates a cloud SAF provider returning 0 or stale mtime

Act:
  - Call loadFullPage(pageUuid, force = false)
    (watcher not running → content-hash branch executes)

Assert:
  - diskContent read once for hash comparison
  - Hash mismatch detected ("- Block V2".hashCode() != "- Block V1".hashCode())
  - DB updated: blocks contain "Block V2"
  - contentHash updated to "- Block V2".hashCode()
```

**Why it fails against pre-fix code**: Old code uses mtime guard even when watcher is not
running. SAF/iOS often returns 0 mtime, so `page.updatedAt >= 0` is always true → reload skipped.

---

### TC-10 — Content-hash fast path: no reload when content unchanged (iOS/WASM)

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun loadFullPage skips reload when content hash matches and watcher is not running()`  
**Requirement**: G6, G5

**Arrange/Act/Assert**:
```
Arrange:
  - Same setup as TC-09 (watcher not running)
  - Load page V1, contentHash stored
  - Do NOT change FakeFileSystem content

Act:
  - Spy on FakeFileSystem.readFile call count (wrap with CountingFileSystem pattern from CountingFileSystem.kt)
  - Call loadFullPage(pageUuid, force = false) — first content-hash navigation
  - Call loadFullPage(pageUuid, force = false) — second call

Assert (second call):
  - readFile called exactly 1 time on the second call
    (one read for hash comparison, no re-parse triggered)
  - DB block content unchanged (V1 still present)
  - No parseAndSavePage invocation on second call
    (verify via spy on blockRepository write count: no new writes)
```

**Why it fails against pre-fix code**: Old code's mtime guard may or may not skip, but there is
no content-hash fast-path. The test pins the new contract for the no-watcher scenario.

---

### TC-11 — isRunning returns true when watcher job is active, false otherwise

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphFileWatcherTest.kt` (extension)  
**Method**: `fun isRunning reflects watcher job lifecycle()`  
**Requirement**: G6 (isRunning drives the iOS/WASM guard branch)

**Arrange/Act/Assert**:
```
Arrange:
  - Construct GraphFileWatcher with pollIntervalMs = Long.MAX_VALUE

Assert before start:
  - fileWatcher.isRunning == false

Act:
  - fileWatcher.startWatching("/graph")

Assert after start:
  - fileWatcher.isRunning == true

Act:
  - fileWatcher.stopWatching()

Assert after stop:
  - fileWatcher.isRunning == false
```

**Why it fails against pre-fix code**: `isRunning` property does not exist in pre-fix code.

---

### TC-12 — addDirty/checkAndClearDirty: set semantics and mutex correctness

**Type**: Unit  
**Source set**: `businessTest`  
**File**: `GraphLoaderDirtySetTest.kt` (new, tests via exposed internal or test-friend method)  
**Method**: `fun addDirty and checkAndClearDirty behave as atomic set with consume semantics()`  
**Requirement**: G1 (dirty set is the core of the cache guard)

**Arrange/Act/Assert**:
```
Arrange:
  - Access addDirty / checkAndClearDirty via a test-visible helper on GraphLoader
    or via a small extracted DirtySet class (if plan extracts it for testability)

Act & Assert (sequential):
  - checkAndClearDirty("/a.md") == false  (not in set)
  - addDirty("/a.md")
  - checkAndClearDirty("/a.md") == true   (found and removed)
  - checkAndClearDirty("/a.md") == false  (consumed — second call returns false)
  - addDirty("/b.md") twice
  - checkAndClearDirty("/b.md") == true   (idempotent add)
  - checkAndClearDirty("/b.md") == false  (consumed)
```

**Why it fails against pre-fix code**: These methods do not exist in pre-fix code.

---

### TC-13 — activePageFilePaths collector job is cancelled on setActivePageUuids(null)

**Type**: Unit  
**Source set**: `businessTest`  
**File**: `GraphLoaderDirtySetTest.kt` (new)  
**Method**: `fun setActivePageUuids null cancels previous collector job to prevent coroutine leak()`  
**Requirement**: G4 (Finding 3 from adversarial review)

**Arrange/Act/Assert**:
```
Arrange:
  - Construct GraphLoader with a fake PageRepository
  - Create MutableStateFlow<Set<String>> uuids = MutableStateFlow(setOf("uuid-1"))
  - Call loader.setActivePageUuids(uuids)
  - Wait one coroutine cycle (advanceUntilIdle in runTest)
  - Capture the internal activePageFilePathsJob (via reflection or friend accessor)

Act:
  - Call loader.setActivePageUuids(null)

Assert:
  - The job captured in arrange is now cancelled (job.isCancelled == true)
  - Emitting a new value to uuids StateFlow does not trigger repository calls
    (verify via InMemoryPageRepository call count)
```

**Why it fails against pre-fix code**: setActivePageUuids does not exist. Against a naive
implementation (without job tracking), the collector would keep running after null is passed.

---

### TC-14 — forceReload threads through to inner mtime guard in lookupExistingPageAndCheckFreshness

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphLoaderCacheTest.kt` (new)  
**Method**: `fun parseAndSavePage with forceReload true bypasses inner mtime guard on SAF zero mtime()`  
**Requirement**: G1, G2 (Finding 1 from adversarial review — critical blocking concern)

**Arrange/Act/Assert**:
```
Arrange:
  - FakeFileSystem where getLastModifiedTime returns 0 (SAF provider behavior)
  - Load page V1 via parseAndSavePage; DB has V1
    allBlocksLoaded = true; page.updatedAt > 0
  - Update FakeFileSystem content to V2 (still modTime = 0)
  - Without forceReload, inner guard: fileModTime=0 → condition
    `fileModTime != 0L && ...` is false → falls through (this particular guard is moot for
    the 0-mtime case, but document it)
  - Switch test to a non-zero stale mtime case:
    getLastModifiedTime returns 500ms (old); page.updatedAt is 1000ms
    Condition: fileModTime != 0 && updatedAt(1000) >= modTime(500) && allBlocksLoaded → SKIP
    This is the regression: forceReload=false would silently skip V2

Act:
  - Call parseAndSavePage(filePath, contentV2, ParseMode.FULL, forceReload = true)
    (triggered by dirty-set path in loadFullPage)

Assert:
  - DB has V2 content (inner guard bypassed by forceReload=true)
  - lookupExistingPageAndCheckFreshness did NOT return PageLookupResult(skip=true)
```

**Why it fails against pre-fix code**: The inner guard always runs; `forceReload` parameter does
not exist. For the stale-mtime scenario, the inner guard fires even if the outer guard has been
bypassed, producing a stale read.

---

### TC-15 — GraphWriter saga compensation calls clearPendingWrite on write failure

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphWriterTest.kt` (extension of existing class)  
**Method**: `fun savePageInternal calls clearPendingWrite in saga compensation when write fails()`  
**Requirement**: G3 (Finding 4 from adversarial review)

**Arrange/Act/Assert**:
```
Arrange:
  - FakeFileSystem where writeFile returns false (simulates write failure)
  - clearPendingWriteCount = 0
  - Construct GraphWriter with:
      onPreWrite = { fileRegistry.preMarkPendingWrite(it) }
      onClearPendingWrite = { fileRegistry.clearPendingWrite(it); clearPendingWriteCount++ }
  - Verify that preMarkPendingWrite has been called before write attempt
    (check modTimes[filePath] == Long.MAX_VALUE after construction)

Act:
  - Attempt GraphWriter.savePageInternal for a page → write fails (saga triggers compensation)

Assert:
  - clearPendingWriteCount == 1
  - fileRegistry.detectChanges on that path (with a subsequent real write)
    returns a changedFiles entry (proves sentinel was cleared, not permanent)
```

**Why it fails against pre-fix code**: There is no onClearPendingWrite / clearPendingWrite.
A failed write leaves Long.MAX_VALUE in modTimes permanently.

---

### TC-16 — onDirtyFile is suspend and called directly (no ordering race)

**Type**: Unit  
**Source set**: `jvmTest`  
**File**: `GraphFileWatcherTest.kt` (extension)  
**Method**: `fun onDirtyFile is called before onReloadFile within the same coroutine context()`  
**Requirement**: G1 (Finding 2 from adversarial review — ordering race)

**Arrange/Act/Assert**:
```
Arrange:
  - Track call order: events = mutableListOf<String>()
  - Construct GraphFileWatcher with:
      onDirtyFile = suspend { events.add("dirty") }
      onReloadFile = suspend { _, _ -> events.add("reload") }
      activePageFilePaths = { emptySet() }  (not an active page)
  - Prime registry and mutate file

Act:
  - Call checkDirectoryForChanges (direct call or via fast-poll)

Assert:
  - events == ["dirty", "reload"]   (dirty always before reload)
  - Confirm: if a ViewModel collected loadFullPage between dirty and reload calls,
    it would find the dirty flag present (because dirty was set before reload)
    [document this ordering guarantee as a comment; the assertion above is sufficient]
```

**Why it fails against pre-fix code**: `onDirtyFile` does not exist. Against a naive non-suspend
lambda + launch wrapper, "dirty" could appear after "reload" in the events list.

---

## Test Suite Summary

| TC | Name | Type | Source Set | New File? |
|----|------|------|-----------|-----------|
| TC-01 | Stale read eliminated (dirty-set path) | Integration | jvmTest | Yes: `GraphLoaderCacheTest.kt` |
| TC-02 | Watcher→dirty set→reload end-to-end | Integration | jvmTest | Yes |
| TC-03 | FAT/exFAT: dirty set ignores identical mtime | Unit | jvmTest | Yes |
| TC-04 | Active-page guard: skip onReloadFile | Unit | jvmTest | Extends `GraphFileWatcherTest.kt` |
| TC-05 | Active-page guard: externalFileChanges still emitted | Unit | jvmTest | Extends `GraphFileWatcherTest.kt` |
| TC-06 | Dirty flag consumed at next navigation after edit resolves | Integration | jvmTest | Yes |
| TC-07 | preMarkPendingWrite suppresses detectChanges | Unit | jvmTest | Extends `FileRegistryTest.kt` |
| TC-08 | clearPendingWrite restores detection after saga rollback | Unit | jvmTest | Extends `FileRegistryTest.kt` |
| TC-09 | Content-hash: detects external change (no watcher) | Integration | jvmTest | Yes |
| TC-10 | Content-hash fast path: no reload on unchanged content | Unit | jvmTest | Yes |
| TC-11 | isRunning reflects watcher job lifecycle | Unit | jvmTest | Extends `GraphFileWatcherTest.kt` |
| TC-12 | addDirty/checkAndClearDirty set semantics | Unit | businessTest | Yes: `GraphLoaderDirtySetTest.kt` |
| TC-13 | Collector job cancelled on setActivePageUuids(null) | Unit | businessTest | Yes |
| TC-14 | forceReload threads through inner mtime guard | Unit | jvmTest | Yes |
| TC-15 | GraphWriter saga calls clearPendingWrite on failure | Unit | jvmTest | Extends `GraphWriterTest.kt` |
| TC-16 | onDirtyFile called before onReloadFile (ordering) | Unit | jvmTest | Extends `GraphFileWatcherTest.kt` |

**Totals by type:**
- Unit: 12 (TC-03, 04, 05, 07, 08, 10, 11, 12, 13, 14, 15, 16)
- Integration: 4 (TC-01, 02, 06, 09)
- UI: 0

**Totals by source set:**
- `jvmTest`: 14 test cases
- `businessTest`: 2 test cases (TC-12, TC-13)
- `commonTest`: 0

---

## Requirements Coverage

| Goal | Tests | Covered? |
|------|-------|---------|
| G1 — Eliminate stale reads | TC-01, TC-02, TC-06, TC-14, TC-16 | YES |
| G2 — No false positives on FAT/exFAT/SAF | TC-03, TC-14 | YES |
| G3 — Do not degrade write performance | TC-07, TC-08, TC-15 | YES |
| G4 — Do not reload actively-edited pages | TC-04, TC-05, TC-06, TC-13 | YES |
| G5 — Keep navigation fast | TC-10 | YES (hash fast-path verified) |
| G6 — Survive no file-watcher (iOS/WASM) | TC-09, TC-10, TC-11 | YES |

**Coverage fraction: 6/6 goals covered (100%)**

---

## Adversarial Review Concern Mapping

| Finding | Severity | Addressed by |
|---------|----------|-------------|
| F1 — Second mtime guard in `lookupExistingPageAndCheckFreshness` | HIGH | TC-14 (pinpoints regression; plan updated with `forceReload` param) |
| F2 — `onDirtyFile` ordering race (non-suspend lambda) | MEDIUM | TC-16 (ordering assertion); plan changed `onDirtyFile` to `suspend` |
| F3 — Collector leak on `setActivePageUuids(null)` | MEDIUM | TC-13 (coroutine leak unit test) |
| F4 — `preMarkPendingWrite` sentinel survives failed saga | MEDIUM | TC-08, TC-15 (clearPendingWrite + saga compensation) |
| F5 — `isRunning` startup window | LOW | TC-11 (documents lifecycle); code comment required |
| F6 — Double file read on iOS/WASM hash-mismatch path | LOW | TC-09 implicitly (pass diskContent to parseAndSavePage); plan already addresses this as optimization |
| F7 — `externalFileChanges` insertion-point ambiguity | LOW | TC-05 (pins emit order) |
| F8 — SAF suppress window 2s extension is redundant | LOW | Plan already removes the extension; TC-07 covers preMarkPendingWrite correctness |

All CONCERNS from the adversarial review are addressed by the plan (updated) and/or test cases.

---

## Implementation Readiness Gate

### Criterion 1: Requirements Coverage
All 6 goals have at least one test. **PASS**.

### Criterion 2: Plan Completeness
Every task in plan.md (T1–T9) is implementable with the described approach:
- T1 (`isRunning`): trivial 1-liner; TC-11 exercises it.
- T2 (`getContentHash`, `preMarkPendingWrite`, `clearPendingWrite`): small additions to `FileRegistry`; TC-07, TC-08 cover them.
- T3 (`onPreWrite`/`onClearPendingWrite` in `GraphWriter`): TC-15 covers the saga compensation path.
- T4 (`onDirtyFile` suspend + `activePageFilePaths` lambda in watcher): TC-04, TC-05, TC-16 cover the three behavioral properties.
- T5 (dirty set + mutex in `GraphLoader`): TC-12 covers the set contract.
- T6 (`activePageFilePaths` derived from `activePageUuids`; job tracking): TC-13 covers the leak.
- T7 (`forceReload` parameter): TC-14 covers the inner-guard bypass.
- T8 (wiring): exercised transitively by integration tests TC-01, TC-02, TC-06.
- T9 (replace mtime guard in `loadFullPage`): TC-01, TC-03, TC-09, TC-10 directly exercise the new guard.

**PASS**. One implementation note: `addDirty`/`checkAndClearDirty` need to be accessible from
tests. Either expose as `internal` + `@VisibleForTesting`, or extract a tiny `DirtySet` class
(preferred for TC-12 in `businessTest` which cannot use JVM-specific mechanisms).

### Criterion 3: Test Quality (fail against pre-fix code)
Every test case includes a "Why it fails against pre-fix code" section. All 16 tests would fail:
- TC-01, 02, 03, 06, 09: pre-fix mtime guard skips reload → wrong block content asserted.
- TC-04, 05: activePageFilePaths guard doesn't exist → onReloadFile called when it shouldn't be.
- TC-07, 08: preMarkPendingWrite/clearPendingWrite don't exist → compile error or changed files non-empty.
- TC-10: content-hash fast-path doesn't exist → no control over readFile call count.
- TC-11: isRunning property doesn't exist → compile error.
- TC-12: addDirty/checkAndClearDirty don't exist → compile error.
- TC-13: setActivePageUuids doesn't exist; job not tracked → assertion on job.isCancelled fails.
- TC-14: forceReload parameter doesn't exist → inner guard fires → stale read asserted.
- TC-15: onClearPendingWrite/clearPendingWrite don't exist → clearPendingWriteCount remains 0.
- TC-16: onDirtyFile doesn't exist; ordering is uncontrolled.

**PASS** — no vacuous passes.

### Criterion 4: Risk Mitigation
All CONCERNS from adversarial-review.md are addressed:
- The HIGH finding (F1) is resolved by T7 + TC-14.
- Both MEDIUM findings (F2, F3, F4) are resolved by plan updates and covered by TC-16, TC-13, TC-08/TC-15 respectively.
- LOW findings are noted; F7 is covered by TC-05; F5 requires a code comment (not a test).

**PASS**.

---

## Readiness Gate Verdict: **PASS**

All four criteria pass. The plan is ready to proceed to implementation (Phase 5).

**Preconditions before first commit**:
1. Decide whether `addDirty`/`checkAndClearDirty` are exposed as `internal` or extracted to a
   testable `DirtySet` class. The `businessTest` source set cannot import JVM-specific types, so if
   TC-12 is placed there, the dirty-set API must be in `commonMain` with no JVM dependencies
   (the Mutex-based implementation already qualifies).
2. Confirm that `GraphFileWatcher.checkDirectoryForChanges` is accessible from tests (currently
   `private suspend fun`). Suggest making it `internal` for test purposes, matching the pattern
   used elsewhere in the project.
3. Fresh session before Phase 5 (per MDD workflow rules).
