# Architecture Research: Disk Change Detection

Branch: `stelekit-disk-load` (post-fix state)

---

## 1. Complete Data Flow: External File Change → UI Refresh

```
External tool writes file on disk
          │
          ▼
SafChangeDetector (Android only)
  ├── FileObserver (inotify on pages/ or journals/) ─────────────────────────┐
  ├── ContentObserver (SAF children URI for pages/ and journals/) ────────────┤
  └── ProcessLifecycleOwner.ON_START (foreground resume) ───────────────────┐ │
                                                                              │ │
          All three paths call onExternalChange() lambda                      │ │
          which sends Unit to externalChangeTrigger Channel<Unit>(CONFLATED) ◄┘ │
          OR (for ProcessLifecycleOwner) direct trigger ◄───────────────────────┘
          │
          ▼
GraphFileWatcher (fast-path coroutine in watcherJob)
  └── for each trigger: checkDirectoryForChanges("$graphPath/pages")
                         checkDirectoryForChanges("$graphPath/journals")

── Parallel path: 5-second poll loop ──
  Every 5 seconds (JVM default; overridable in tests):
  └── checkDirectoryForChanges(pagesDir) + checkDirectoryForChanges(journalsDir)

          │
          ▼
FileRegistry.detectChanges(dirPath)           [guarded by detectMutex]
  ├── Lists current files + mod times via fileSystem.listFilesWithModTimes()
  ├── For each file: compares modTime vs stored modTimes map
  │   ├── modTime == lastKnown (or lastKnown == MAX_VALUE sentinel) → skip
  │   ├── lastKnown == null → NEW FILE
  │   ├── modTime > lastKnown → CHANGED FILE
  │   │   ├── .md.stek: skips content-hash guard; accepts modTime change alone
  │   │   └── .md: reads content, checks hashCode(); skips if hash unchanged (own write)
  │   └── modTime < lastKnown → SYNC BACKWARD TIMESTAMP
  │       └── content-hash check if hash is pre-populated; emits if different
  └── Returns ChangeSet{newFiles, changedFiles, deletedPaths}
          │
          ▼
GraphFileWatcher.checkDirectoryForChanges (for each ChangedFile)
  ├── fileSystem.invalidateShadow(filePath)       [clears stale shadow cache entry]
  ├── git-merge suppression check (sticky set, bypasses if present)
  ├── Emits ExternalFileChange to _externalFileChanges SharedFlow(extraBufferCapacity=8)
  │     ↳ ViewModel / UI collect this to show conflict dialog
  ├── waits 200ms for suppress() callback (capacity-1 channel per file)
  │   └── if suppressed → continue (skip auto-reload)
  ├── onDirtyFile?.invoke(filePath)               [suspend; adds to GraphLoader.dirtyPaths]
  ├── activePageFilePaths guard check
  │   └── if file path ∈ unsavedPageFilePaths → skip auto-reload
  │       (open-but-unedited pages like journals ARE allowed through)
  └── onReloadFile(filePath, content)
        │
        ▼
GraphLoader.parseAndSavePage(filePath, content, FULL, forceReload=true)
        │
        ▼
DatabaseWriteActor → page + block rows updated in SQLDelight DB
        │
        ▼
SQLDelight reactive queries invalidate → Flow emissions
        │
        ▼
Repository Flows (PageRepository, BlockRepository)
        │
        ▼
StelekitViewModel StateFlow collectors
        │
        ▼
Compose recomposition → UI refresh
```

---

## 2. Three Detection Strategies

### Strategy 1: FileObserver (inotify) — fastest, requires MANAGE_EXTERNAL_STORAGE

**When it fires**: When `realGraphPath != null` is passed to `SafChangeDetector`. This requires the `MANAGE_EXTERNAL_STORAGE` permission to be granted, giving the app a real filesystem path to the graph directory.

**Implementation** (`SafChangeDetector.startFileObservers`):
- Creates two `FileObserver` instances, one each for `$graphPath/pages` and `$graphPath/journals`
- Uses mask: `CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO`
- Filters to only directories that actually exist at start time (guards against SAF-only paths with no real path)
- Fires within milliseconds; callbacks run on the main thread via `Handler(Looper.getMainLooper())`

**Fix applied**: Previously watched the graph root directory inode. inotify is not recursive; subdirectory file changes were silently missed. Fixed to watch the two leaf subdirectories explicitly.

### Strategy 2: ContentObserver (SAF) + 30-second polling fallback

**When it fires**: When `realGraphPath == null` (SAF-only mode, no MANAGE_EXTERNAL_STORAGE).

**Implementation** (`SafChangeDetector.startContentObserversAndPoller`):
- Derives `treeDocId` from the SAF tree URI
- Builds children URIs for `"$treeDocId/pages"` and `"$treeDocId/journals"` via `DocumentsContract.buildChildDocumentsUriUsingTree`
- Registers two `ContentObserver` instances, one per subdirectory children URI, with `notifyForDescendants = true`
- Wraps registration in `try/catch` (some providers reject registration; logged, not thrown)
- 30-second polling coroutine as a fallback for providers that don't deliver ContentObserver reliably

**Fix applied**: Previously registered a single ContentObserver on the root children URI. SAF `notifyForDescendants` uses URI string-prefix matching; root children URIs don't prefix-match file URIs inside `pages/` or `journals/` subdirectories. Fixed to register on the subdirectory children URIs directly.

### Strategy 3: ProcessLifecycleOwner foreground observer

**When it fires**: Every time the Android app comes to the foreground (`ON_START` lifecycle event). Platform-agnostic complement to the two detection strategies above.

**Implementation** (`SafChangeDetector.startForegroundObserver`):
- Registers a `DefaultLifecycleObserver` on `ProcessLifecycleOwner` (process-level, not Activity-level)
- `onStart` fires immediately on resume
- This triggers a full directory scan in the same way as a FileObserver or ContentObserver event

**Rationale**: Without this, an external edit made while the app is backgrounded (e.g. from Termux) would not appear until the next poll cycle — up to 5 seconds on JVM, 30 seconds on SAF-only Android.

---

## 3. Own-Write Suppression: Three-Phase Saga

The saga prevents the watcher from treating the app's own writes as external changes. It runs inside `GraphWriter.savePageInternal` via a DSL that sequences action + compensation blocks.

### Phase 0: `preMarkPendingWrite` — close the race window before writing

```
FileRegistry.preMarkPendingWrite(filePath)
  → modTimes[filePath] = Long.MAX_VALUE   [sentinel]
```

This is called as **Saga Step 0** immediately before the file write. Because `detectChanges` checks `modTime > lastKnown`, and no real filesystem mtime can exceed `Long.MAX_VALUE`, the file cannot be detected as changed during the write window regardless of how long the write takes.

**Compensation**: If the saga rolls back (write fails), `onClearPendingWrite` is called which removes the `Long.MAX_VALUE` sentinel from `modTimes`. Without this, the file would be permanently suppressed for the lifetime of the `FileRegistry` instance — any future external edit would be silently ignored.

### Phase 1: File write to disk

```
GraphWriter saga Step 1: fileSystem.markDirty(filePath, content)   [write-behind]
                       OR fileSystem.writeFile(filePath, content)   [direct SAF]
```

For plaintext `.md` files on Android, write-behind is attempted first (zero Binder IPC: updates shadow and enqueues in `WriteBehindQueue`). Direct SAF write is the fallback.

For encrypted `.md.stek` files, `writeFileBytes` is called directly (encrypted content cannot be cached in plaintext shadow).

### Phase 2: `markWrittenByUs` / flush callback — record post-write mtime

```
GraphWriter saga Step 2: onFileWritten?.invoke(filePath)
  → GraphLoader.markFileWrittenByUs(filePath)
  → FileRegistry.markWrittenByUs(filePath)
      → detectMutex.withLock {
            modTimes[filePath] = fileSystem.getLastModifiedTime(filePath)   [real post-write mtime]
            contentHashes[filePath] = content.hashCode()                   [for .md files only]
        }
```

This replaces the `Long.MAX_VALUE` sentinel with the actual post-write mtime. On the next `detectChanges` poll, `modTime == lastKnown` and the file is skipped.

**Flush callback path** (write-behind only): When the file was written to shadow (write-behind), `markWrittenByUs` is called on `flushPendingWrites` completion via the `onFlushed` callback. The sequence is:

```
ShadowFlushActor.flush()
  → fileSystem.writeFile(safPath, content)   [SAF write succeeds]
  → queue.dequeue(safPath)
  → shadowCache.stampMtime(relativePath, mtime)   [sync shadow mtime to SAF mtime]
  → onFlushed?.invoke(safPath)
      → GraphLoader.markFileWrittenByUs(safPath)
          → FileRegistry.markWrittenByUs(safPath)
```

This is critical for `.md.stek` files: the content-hash guard is disabled for encrypted files (they are binary; hash comparison is meaningless on ciphertext). Without `markWrittenByUs` after flush, the next 5-second poll would see `SAF mtime > stored mtime` and emit a spurious external-change event.

**Wire-up in App.kt**:
```kotlin
// Direct writes (GraphWriter)
GraphWriter(
    onFileWritten = graphLoader::markFileWrittenByUs,        // Step 2
    onPreWrite = { filePath -> graphLoader.fileRegistry.preMarkPendingWrite(...) },   // Step 0
    onClearPendingWrite = { filePath -> graphLoader.fileRegistry.clearPendingWrite(...) },  // compensation
    ...
)

// Write-behind flush (SAF background flush)
fileSystem.setOnFlushComplete(graphLoader::markFileWrittenByUs)
```

---

## 4. `dirtyPageUuids` Flow: BlockStateManager → GraphLoader → GraphFileWatcher

```
User types in a block
          │
          ▼
BlockStateManager._dirtyBlocks: MutableStateFlow<Map<blockUuid, Long>>
  (updated via content change; cleared when DB confirms save)
          │
          ▼ combine()
BlockStateManager.dirtyPageUuids: StateFlow<Set<pageUuid>>
  = combine(_dirtyBlocks, _blocks) { dirtyBlocks, blocks ->
      blocks.entries
        .filter { (_, blockList) -> blockList.any { it.uuid.value in dirtyBlocks.keys } }
        .map { it.key }.toSet()
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())
  [yields only page UUIDs that have at least one dirty block — not all observed pages]
          │
          ▼ StelekitViewModel.init()
graphLoader.setUnsavedPageUuids(blockStateManager.dirtyPageUuids)
          │
          ▼ GraphLoader.setUnsavedPageUuids()
  unsavedPageFilePathsJob = parallelScope.launch {
      uuids.collect { uuidSet ->
          unsavedPageFilePaths = uuidSet.mapNotNull { uuid ->
              pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()
                  ?.filePath?.let { FilePath(it) }
          }.toSet()
      }
  }
  [UUID → file path resolution happens here; GraphFileWatcher never touches PageRepository]
          │
          ▼ GraphFileWatcher constructor
activePageFilePaths = { unsavedPageFilePaths.map { it.value }.toSet() }
  [lambda read on every changed file in checkDirectoryForChanges]
          │
          ▼ checkDirectoryForChanges (per changed file)
val isActivePage = activePageFilePaths?.invoke()?.contains(changed.entry.filePath) == true
if (isActivePage) continue   // skip auto-reload; dirty flag already set; conflict emitted
```

**Critical distinction**: `GraphLoader` maintains two separate sets:
- `activePageFilePaths`: derived from `BlockStateManager.activePageUuids` — all open pages (used by background indexer to skip in-progress edits)
- `unsavedPageFilePaths`: derived from `BlockStateManager.dirtyPageUuids` — only pages with dirty blocks (used by the watcher guard)

The watcher guard uses `unsavedPageFilePaths`. This means an open-but-unedited journal page is NOT in the guard set and WILL be auto-reloaded when an external change arrives — fixing the G1 requirement.

---

## 5. Potential Race Conditions and Edge Cases

### 5.1 Graph switch mid-flush (write-behind queue orphaning)

**Scenario**: User switches graph while `WriteBehindQueue` has pending dirty pages for Graph A. `PlatformFileSystem` is graph-scoped; switching reinitializes `shadowCache` and `writeBehindQueue`. The in-flight `flushPendingWrites()` coroutine for Graph A may still be running when the new cache is set.

**Current risk**: If `flushPendingWrites()` completes after the graph switch, `onFlushed` fires `graphLoader.markFileWrittenByUs`. At this point, `graphLoader` may already be associated with Graph B. The `FileRegistry` for Graph A is still in memory (referenced by Graph A's `RepositorySet`), but if that set has been torn down, the registry is a dead reference.

**Mitigation**: `GraphLoader.setUnsavedPageUuids(null)` is called on graph unload, which cancels `unsavedPageFilePathsJob` and clears `unsavedPageFilePaths`. This does not directly protect `markFileWrittenByUs`, but the path mismatch (Graph A path vs Graph B registry) would be harmless — `markWrittenByUs` would update modTimes for a path that no longer exists in the active registry.

**Verdict**: Low severity; orphaned entries in a dead registry cause no observable harm. However, if `onFlushComplete` callback is not cleared on graph switch (`fileSystem.setOnFlushComplete(null)` is not called), the callback references the old `graphLoader` and keeps it alive. This is a minor memory leak worth noting.

### 5.2 `detectMutex` contention: polling loop + ContentObserver + `markWrittenByUs` triple contention

**Scenario**: The 5-second poll and a ContentObserver callback fire simultaneously, and `markWrittenByUs` (from a concurrent `flushPendingWrites`) also needs the mutex. All three compete for `detectMutex`.

**Current protection**: `detectMutex` serializes all three. `detectChanges` (called by both the poll loop and the fast-path coroutine) is fully serialized against `markWrittenByUs`/`preMarkPendingWrite`/`clearPendingWrite`. No double-emit of the same change is possible.

**Verdict**: Correctly handled. The `CONFLATED` channel on the fast-path also prevents callback storm queuing.

### 5.3 `unsavedPageFilePaths` staleness after UUID→path resolution

**Scenario**: A page is renamed or moved on disk between when `dirtyPageUuids` emits the UUID and when `pageRepository.getPageByUuid()` resolves it to a path. The `unsavedPageFilePaths` collector runs on `parallelScope` and may lag behind.

**Current risk**: `unsavedPageFilePaths` could hold a stale file path while the watcher observes the new path. The file at the new path would NOT be in the guard set, so auto-reload would proceed even though the user has edits. For the new path to be in `unsavedPageFilePaths`, the UUID must still map to a dirty block AND the path resolution must have re-executed.

**Verdict**: Acceptable. Renaming while editing is a rare concurrent operation. The worst case is an unexpected auto-reload overwriting in-progress edits — this is surfaced through the conflict dialog (externalFileChanges is still emitted), so the user can recover.

### 5.4 `externalFileChanges` SharedFlow buffer overflow (8 slots)

**Scenario**: A bulk external operation (e.g. `git pull` touching 20 pages at once) triggers a directory scan that finds many changed files. The `_externalFileChanges` SharedFlow has `extraBufferCapacity = 8`. If 9+ changes arrive and the ViewModel collector is slow (e.g. suspended on a database write), subsequent `tryEmit` calls return false and events are dropped.

**Current protection**: Dropped events are logged (`logger.warn`). The auto-reload path (`onReloadFile`) still proceeds — only the conflict-dialog emission is skipped. The next 5-second poll cycle will re-detect any files that were not reloaded.

**Verdict**: Survivable. The auto-reload is not gated on the SharedFlow emit — only the suppress() window and the conflict dialog are affected.

### 5.5 `preMarkPendingWrite` sentinel left permanently if process is killed mid-write

**Scenario**: The app is killed (OOM, user force-stop) after `preMarkPendingWrite` sets `Long.MAX_VALUE` in `modTimes`, but before `markWrittenByUs` replaces it with the real mtime.

**Current protection**: `FileRegistry` is in-memory only; no persistence to disk. On the next process start, `modTimes` is empty and the next `detectChanges` treats the file as newly seen (falls into the `lastKnown == null` branch), correctly loading the current on-disk content as a "new file" event. No permanent suppression.

**Verdict**: No issue. In-memory state is inherently ephemeral.

### 5.6 `ShadowFileCache.isFirstAccess()` — graph switch triggers full purge correctly

**Scenario**: User switches graphs in the same session. Graph B's `ShadowFileCache` is a new instance (created when Graph B's `PlatformFileSystem` is initialized). `isFirstAccess()` uses a per-instance `AtomicBoolean` initialized to `true`.

**Fix applied**: Previously `freshProcess` was a process-wide `AtomicBoolean` in `PlatformFileSystem.Companion`. On graph switch, the companion field was already `false`, so Graph B's shadow was never purged — stale external writes from the backgrounded period could be silently served from shadow.

**Current behavior**: Each new `ShadowFileCache` instance sets `freshInstance = AtomicBoolean(true)`. The first call to `invalidateStaleShadow` for Graph B calls `cache.isFirstAccess()` which returns `true`, triggering `cache.deleteAll()`. Correct freshness guaranteed per graph per session.

### 5.7 200ms suppress window race in `checkDirectoryForChanges`

**Scenario**: The suppress() callback is delivered by a Compose `LaunchedEffect` on the main thread. If the main thread is busy (e.g. during a heavy recomposition), the 200ms window may expire before the callback fires.

**Current behavior**: `withTimeoutOrNull(200L) { suppressChannel.receive() }` returns null; `suppressed = false`. The file is auto-reloaded even though the user intended to suppress it. The conflict dialog was already emitted, so the user can still discard or accept the reload.

**Verdict**: Acceptable UX trade-off. The capacity-1 channel (not RENDEZVOUS) helps by allowing `trySend` to succeed before the receiver wakes up, reducing the probability of a missed suppress under light load.
