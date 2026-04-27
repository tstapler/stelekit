# FileRegistry Refactor

## Problem Statement

`GraphLoader` has four code paths that independently scan directories, register mod times, and manage `knownFilesModTimes`/`knownFilesContentHashes`. This duplication has caused the same bug (unregistered files treated as "new" by the watcher) to recur three times because each loading path was independently responsible for file registration.

The duplicated operations across `loadJournalsImmediate`, `loadRemainingJournals`, `loadDirectory`, and `checkDirectoryForChanges`:

1. `fileSystem.listFiles(dir).filter { it.endsWith(".md") }` -- repeated in all four
2. `knownFilesModTimes[filePath] = modTime` -- repeated in all four (plus `markFileWrittenByUs` and `parseAndSavePage`)
3. `knownFilesContentHashes[filePath] = hash` -- in `checkDirectoryForChanges` and `markFileWrittenByUs`
4. Journal name validation + sort descending -- in both `loadJournalsImmediate` and `loadRemainingJournals`

## Design

### New Class: `FileRegistry`

**Package**: `dev.stapler.stelekit.db`
**File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/db/FileRegistry.kt`

```kotlin
class FileRegistry(private val fileSystem: FileSystem) {

    // --- State (moved from GraphLoader) ---
    private val modTimes = mutableMapOf<String, Long>()       // filePath -> lastModifiedTime
    private val contentHashes = mutableMapOf<String, Int>()    // filePath -> content hashCode

    // --- Scan & Register ---

    /** Scans a directory for .md files, registers all mod times, returns the full file list. */
    fun scanDirectory(dirPath: String): List<FileEntry>

    /** Returns only files whose names pass JournalUtils.isJournalName, sorted descending. */
    fun journalFiles(dirPath: String): List<FileEntry>

    /** Returns all .md files in a directory (no journal filter). */
    fun pageFiles(dirPath: String): List<FileEntry>

    // --- Filtered Views (no re-scan, operates on last scan result) ---

    /** Recent N journals from the last scanDirectory/journalFiles call. */
    fun recentJournals(dirPath: String, count: Int): List<FileEntry>

    /** Journals after skipping the first `skip` entries. */
    fun remainingJournals(dirPath: String, skip: Int, take: Int): List<FileEntry>

    // --- Change Detection ---

    /** Compares current disk state against registered mod times. Returns new + changed files. */
    fun detectChanges(dirPath: String): ChangeSet

    /** Returns files that were in the registry but no longer exist on disk. */
    // (already part of ChangeSet.deleted)

    // --- Write Tracking ---

    /** Marks a file as written by the app so the watcher skips it. */
    fun markWrittenByUs(filePath: String)

    /** Updates mod time after parseAndSavePage completes. */
    fun updateModTime(filePath: String)

    /** Updates content hash for a file (used by change detection content-hash guard). */
    fun updateContentHash(filePath: String, contentHash: Int)

    // --- Cleanup ---

    fun clear()
}

data class FileEntry(
    val fileName: String,   // e.g. "2024_01_15.md"
    val filePath: String,   // e.g. "/path/to/graph/journals/2024_01_15.md"
    val modTime: Long       // milliseconds, 0 if unavailable
)

data class ChangeSet(
    val newFiles: List<FileEntry>,
    val changedFiles: List<FileEntry>,
    val deletedPaths: List<String>
)
```

### Key Design Decisions

**Single scan, multiple views**: `scanDirectory` does one `listFiles` + `getLastModifiedTime` pass and caches the result. `journalFiles`, `recentJournals`, `remainingJournals` are pure filters on the cached list -- no additional I/O.

**Registry owns all state**: `knownFilesModTimes` and `knownFilesContentHashes` move entirely into `FileRegistry`. GraphLoader no longer touches these maps.

**Content-hash guard stays in change detection**: The `detectChanges` method reads file content for changed files and applies the hash guard internally, returning only genuinely changed files. This keeps the watcher logic out of GraphLoader.

**Not thread-safe by itself**: GraphLoader already serializes watcher access via its coroutine loop. FileRegistry does not add its own synchronization -- the caller (GraphLoader) is responsible for not calling `scanDirectory` concurrently on the same dir. This avoids double-locking.

## Stories

### Story 1: Extract FileRegistry class

**Acceptance Criteria**:
- [ ] `FileRegistry` class exists in `dev.stapler.stelekit.db` with the API above
- [ ] `scanDirectory` calls `fileSystem.listFiles` + `getLastModifiedTime` once and populates `modTimes`
- [ ] `journalFiles` filters by `JournalUtils.isJournalName` and sorts descending
- [ ] `recentJournals` / `remainingJournals` delegate to `journalFiles` with take/drop
- [ ] `detectChanges` compares current disk mod times against `modTimes`, applies content-hash guard, returns `ChangeSet`
- [ ] `markWrittenByUs` updates both `modTimes` and `contentHashes` (reads file via `fileSystem`)
- [ ] Unit tests cover: scan populates registry, filtered views return correct subsets, change detection finds new/changed/deleted files, content-hash guard suppresses false positives

### Story 2: Wire FileRegistry into GraphLoader

**Acceptance Criteria**:
- [ ] `GraphLoader` constructor takes a `FileRegistry` parameter (constructed with the same `FileSystem`)
- [ ] `knownFilesModTimes` and `knownFilesContentHashes` removed from GraphLoader
- [ ] `loadJournalsImmediate` uses `registry.scanDirectory(journalsDir)` then `registry.recentJournals(journalsDir, count)` -- no direct `listFiles` call
- [ ] `loadRemainingJournals` uses `registry.remainingJournals(journalsDir, skip, take)` -- no direct `listFiles` call
- [ ] `loadDirectory` uses `registry.scanDirectory(path)` then applies its own page/journal selection logic on the returned list -- no direct `listFiles` call
- [ ] `checkDirectoryForChanges` replaced by `registry.detectChanges(dirPath)` -- the method body shrinks to iterating the `ChangeSet` and calling `parseAndSavePage`
- [ ] `markFileWrittenByUs` delegates to `registry.markWrittenByUs(filePath)`
- [ ] `parseAndSavePage` line 888-891 (mod time update after save) delegates to `registry.updateModTime(filePath)`
- [ ] All existing tests pass (no public API change to GraphLoader)
- [ ] Manual smoke test: load graph, edit a file externally, confirm watcher picks it up; edit via app, confirm watcher ignores it

## Implementation Sequence

1. Create `FileRegistry.kt` with unit tests (Story 1) -- no changes to GraphLoader yet
2. Add `FileRegistry` parameter to `GraphLoader` constructor
3. Replace `loadJournalsImmediate` internals (smallest, most isolated)
4. Replace `loadRemainingJournals` internals
5. Replace `loadDirectory` internals
6. Replace `checkDirectoryForChanges` with `detectChanges` delegation
7. Remove `knownFilesModTimes` and `knownFilesContentHashes` from GraphLoader
8. Remove `markFileWrittenByUs` body, delegate to registry
9. Run full test suite + manual smoke test

Steps 2-8 can be a single commit since they are all part of Story 2 and the intermediate states would break compilation.

## Known Issues

### Potential Bug: Stale scan cache in progressive loading

**Description**: If `loadJournalsImmediate` calls `scanDirectory(journalsDir)` and caches the file list, then a new journal file is created on disk before `loadRemainingJournals` runs, the new file will not appear in `remainingJournals` because it operates on the cached scan.

**Severity**: Low -- this window is typically < 1 second during app startup, and the watcher will pick up the file within 5 seconds anyway.

**Mitigation**: `loadRemainingJournals` can optionally re-scan if needed, or accept this as existing behavior (the current code has the same race).

### Potential Bug: Concurrent map mutation from watcher and loader

**Description**: `detectChanges` reads `modTimes` while `scanDirectory` or `updateModTime` writes to it. Currently safe because GraphLoader serializes access (watcher runs in a single coroutine, loading completes before watcher starts). If this assumption changes, `FileRegistry` will need internal synchronization.

**Severity**: Low -- the current architecture prevents this, but it is a latent risk if someone adds concurrent directory watching.

**Mitigation**: Document the single-writer assumption. If concurrent access is needed later, add a `Mutex` inside `FileRegistry`.

### Potential Bug: detectChanges reads file content for hash guard

**Description**: `detectChanges` must read file content to compute the hash guard (to suppress false positives from our own writes). This means `detectChanges` does I/O beyond just `listFiles`/`getLastModifiedTime`. If a file is large or the disk is slow, this could slow down the watcher tick.

**Severity**: Low -- content is only read for files whose mod time actually changed, which is typically 0-2 files per tick.

**Mitigation**: Only read content for files where `modTime > lastKnownModTime`. This is already the behavior in `checkDirectoryForChanges` today.

## Files Affected

| File | Change |
|------|--------|
| `kmp/src/commonMain/kotlin/com/logseq/kmp/db/FileRegistry.kt` | **NEW** -- FileRegistry class |
| `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt` | Remove maps, delegate to FileRegistry |
| `kmp/src/commonTest/kotlin/com/logseq/kmp/db/FileRegistryTest.kt` | **NEW** -- unit tests |
| Any file constructing `GraphLoader` (DI / factory) | Pass `FileRegistry` instance |
