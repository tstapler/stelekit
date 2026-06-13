# Implementation Plan: SAF Cache Redesign

## Overview

Replace the mtime-based freshness guard in `loadFullPage` with a **watcher-driven dirty set**,
guarded by an `activePageUuids` check, and backed by a **content-hash fallback** for platforms
without a file watcher (iOS/WASM).

The core insight from research: `GraphFileWatcher` already detects external changes correctly.
The bug is that `loadFullPage` ignores what the watcher knows. The fix wires the watcher's
knowledge into the loader's cache guard, without creating a circular dependency.

---

## Architecture

### Current Flow (broken)

```
External edit → watcher detects → onReloadFile → parseAndSavePage
                                                       ↑ only reactive path

loadFullPage(uuid) → getLastModifiedTime (unreliable SAF) → guard → may skip reload
```

### New Flow

```
External edit → watcher detects → activePageUuids guard → if NOT editing:
                                                             dirtyPaths.add(filePath)
                                                             + call onReloadFile (existing)

loadFullPage(uuid) → check dirtyPaths(filePath):
  ├── in dirty set → force reload + remove from set
  └── NOT in dirty set:
        ├── watcher running (JVM/Android) → serve from cache (guard passes)
        └── no watcher (iOS/WASM) → content-hash check:
              read file → hashCode() vs contentHashes[path]
              ├── same → serve from cache
              └── different → reload

GraphWriter.savePageInternal:
  Step 0 (NEW): fileRegistry.preMarkPendingWrite(filePath)   ← before disk write
  Step 1: write file to disk (existing)
  Step 2: onFileWritten → markWrittenByUs (existing)
```

### Dependency Flow (no circular dependencies)

The dirty set is owned by `GraphLoader` and passed into `GraphFileWatcher` as a callback,
preserving the existing dependency direction:

```
GraphLoader → constructs → GraphFileWatcher(
    onReloadFile = { filePath, content → ... },
    onDirtyFile  = { filePath → dirtyPaths.add(filePath) }   ← NEW callback
)
```

`GraphFileWatcher` never imports `GraphLoader`. It only calls back via the two lambdas.
The dirty set itself is a `MutableSet<String>` owned by `GraphLoader`, passed to the watcher
as a lambda closure. No new package-level dependency.

---

## Files to Change

### 1. `GraphFileWatcher.kt` — Add `activePageUuids` guard + `onDirtyFile` callback

**Class**: `GraphFileWatcher`

Changes:
- Add constructor parameter: `private val activePageUuids: () -> Set<String>`
  (a lambda rather than `StateFlow` to avoid importing ViewModel types into the db layer)
- Add constructor parameter: `private val onDirtyFile: suspend (filePath: String) -> Unit`
  (must be `suspend` — the lambda calls `addDirty` which acquires `dirtyMutex`; a non-suspend
  lambda would require a `launch` wrapper that creates an ordering race where `checkAndClearDirty`
  can run before `addDirty` completes)
- In `checkDirectoryForChanges`, inside the `changedFiles` loop, the active-page check must be
  inserted **AFTER** the existing `_externalFileChanges.tryEmit(...)` and `withTimeoutOrNull`
  suppress-window block, so that the conflict dialog always fires even for actively-edited pages.
  The `onReloadFile` call is then conditionally skipped:
  ```kotlin
  // Existing emit + suppress window (UNCHANGED — must run unconditionally):
  val suppressChannel = Channel<Boolean>(1)
  val emitted = _externalFileChanges.tryEmit(ExternalFileChange(...) { suppressChannel.trySend(true) })
  val suppressed = withTimeoutOrNull(200L) { suppressChannel.receive() } == true
  if (suppressed) continue

  // NEW: active-page guard — mark dirty but skip auto-reload for pages being edited
  onDirtyFile(changed.entry.filePath)   // always mark dirty (watcher or no-watcher path)
  val isActivePage = changed.entry.filePath in activePageFilePaths()
  if (isActivePage) {
      logger.debug("Skipping auto-reload for actively-edited page: ${changed.entry.filePath}")
      continue   // skip onReloadFile; dirty flag consumed at next navigation
  }

  // Existing onReloadFile call (UNCHANGED):
  val content = if (changed.entry.filePath.endsWith(".md.stek")) { ... } else { changed.content }
  onReloadFile(changed.entry.filePath, content)
  ```

**Note on UUID resolution**: `GraphFileWatcher` does not have access to `PageRepository`.
Rather than introducing that dependency, the `activePageUuids` guard operates on file paths:
`GraphLoader` maintains a `activePageFilePaths: StateFlow<Set<String>>` derived from `activePageUuids`
(mapping UUIDs → file paths on set change). This derived set is passed to the watcher as the lambda,
keeping all UUID-to-path resolution inside `GraphLoader`.

**Complexity**: M

---

### 2. `GraphLoader.kt` — Dirty set, activePageFilePaths, and new `loadFullPage` guard

**Class**: `GraphLoader`

Changes:

#### 2a. Add dirty set field
```kotlin
// Thread-safety: accessed only from coroutines under detectMutex (via FileRegistry)
// or from loadFullPage which is called on a single coroutine context.
// Use ConcurrentHashMap.newKeySet() on JVM for safety since watcher coroutine and
// loadFullPage coroutine run on different threads.
private val dirtyPaths: MutableSet<String> = 
    java.util.Collections.synchronizedSet(mutableSetOf())
// Note: on iOS/WASM use a Mutex-guarded set — see platform note below.
```

On Kotlin Multiplatform, `java.util.Collections.synchronizedSet` is JVM-only.
Use `@ThreadLocal` or a `Mutex`-guarded set in `commonMain`. The correct approach:

```kotlin
// In commonMain — simple mutex-guarded set (two accesses: watcher add, loadFullPage remove)
private val dirtyPaths = mutableSetOf<String>()
private val dirtyMutex = Mutex()

suspend fun addDirty(path: String) = dirtyMutex.withLock { dirtyPaths.add(path) }
suspend fun checkAndClearDirty(path: String): Boolean = dirtyMutex.withLock {
    dirtyPaths.remove(path)  // returns true if was present
}
```

These are `suspend` functions, so they are safe to call from coroutines on all platforms.

#### 2b. Derive `activePageFilePaths` from `activePageUuids`
```kotlin
private var activePageFilePaths: Set<String> = emptySet()
// Job for the activePageFilePaths collector. Cancelled when setActivePageUuids is called
// again (with null or a new flow) to prevent coroutine leaks on graph close / vault lock.
private var activePageFilePathsJob: Job? = null

override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {
    activePageUuids = uuids
    // Cancel any existing collector before starting a new one.
    // Without this, the previous collector keeps running after graph close or vault lock,
    // leaking coroutines and calling pageRepository on a closed scope.
    activePageFilePathsJob?.cancel()
    activePageFilePathsJob = null
    if (uuids != null) {
        activePageFilePathsJob = parallelScope.launch {
            uuids.collect { uuidSet ->
                activePageFilePaths = uuidSet.mapNotNull { uuid ->
                    pageRepository.getPageByUuid(uuid).first().getOrNull()?.filePath
                }.toSet()
            }
        }
    } else {
        activePageFilePaths = emptySet()
    }
}
```

#### 2c. Wire dirty set and `activePageFilePaths` into `GraphFileWatcher` construction

Replace the existing `fileWatcher` initialization:
```kotlin
private val fileWatcher = GraphFileWatcher(
    fileSystem = fileSystem,
    fileRegistry = fileRegistry,
    readFile = ::readFileDecrypted,
    onReloadFile = { filePath, content -> parseAndSavePage(filePath, content, ParseMode.FULL) },
    onDirtyFile = { filePath -> parallelScope.launch { addDirty(filePath) } },
    activePageFilePaths = { activePageFilePaths },
    pollIntervalMs = watcherPollIntervalMs,
)
```

#### 2d. Replace `loadFullPage` mtime guard

Replace the current mtime guard block (lines 699–709):
```kotlin
// OLD:
if (!force && allBlocksLoaded) {
    val fileModTime = fileSystem.getLastModifiedTime(filePath) ?: 0L
    if (fileModTime != 0L && page.updatedAt.toEpochMilliseconds() >= fileModTime) {
        logger.debug("Skipping loadFullPage, already up to date: $filePath")
        return
    }
}

// NEW:
var forceReload = force
if (!forceReload && allBlocksLoaded) {
    val isDirty = checkAndClearDirty(filePath)
    if (!isDirty) {
        if (fileWatcher.isRunning) {
            // Watcher is running: trust its dirty set. No dirty entry = no external change.
            // NOTE: isRunning is false during the short window between onPhase1Complete and
            // startWatching completing on JVM/Android — in that window we fall through to
            // the content-hash path, which is correct (adds ~10-30ms, not incorrect).
            logger.debug("Skipping loadFullPage, watcher reports no change: $filePath")
            return
        } else {
            // No watcher (iOS/WASM): fall back to content-hash check
            val storedHash = fileRegistry.getContentHash(filePath)
            if (storedHash != null) {
                val diskContent = fileSystem.readFile(filePath)
                if (diskContent != null && diskContent.hashCode() == storedHash) {
                    logger.debug("Skipping loadFullPage, content hash unchanged: $filePath")
                    return
                }
                // hash mismatch — pass diskContent directly to parseAndSavePage to avoid
                // a second file read (optimization: halves disk I/O on iOS/WASM reload path)
                if (diskContent != null) {
                    fileSystem.invalidateShadow(filePath)
                    parseAndSavePage(filePath, diskContent, ParseMode.FULL, forceReload = true)
                    return
                }
                // diskContent == null (read failed) → fall through to reload via readFileDecrypted
            }
            // No stored hash → fall through (first load)
        }
    }
    // isDirty == true → set forceReload so inner guards are also bypassed
    forceReload = true
}
```

**Note on `forceReload` threading**: `parseAndSavePage` currently does not accept a `forceReload`
parameter. Add one (defaulting to `false`) and thread it through to
`lookupExistingPageAndCheckFreshness`. When `forceReload = true`, skip the inner mtime guard
(lines 1159–1162 in current code):

```kotlin
// In lookupExistingPageAndCheckFreshness, replace:
if (fileModTime != 0L &&
    existingPage.updatedAt.toEpochMilliseconds() >= fileModTime &&
    allBlocksLoaded) {
    return PageLookupResult(skip = true)
}
// With:
if (!forceReload &&
    fileModTime != 0L &&
    existingPage.updatedAt.toEpochMilliseconds() >= fileModTime &&
    allBlocksLoaded) {
    return PageLookupResult(skip = true)
}
```

This ensures both guards are bypassed together when a dirty-set hit triggers a reload.

**Complexity**: M

---

### 3. `FileRegistry.kt` — Add `getContentHash`, `preMarkPendingWrite`

**Class**: `FileRegistry`

Changes:

#### 3a. Add `getContentHash` accessor
```kotlin
/** Returns the stored content hash for [filePath], or null if not yet hashed. */
suspend fun getContentHash(filePath: String): Int? = detectMutex.withLock {
    contentHashes[filePath]
}
```

#### 3b. Add `preMarkPendingWrite` and `clearPendingWrite`
```kotlin
/**
 * Marks [filePath] as a pending own-write. Prevents the watcher from treating
 * the file as an external change during the window between this call and
 * [markWrittenByUs]. Call this immediately before writing to disk.
 *
 * Implementation: stores sentinel value Long.MAX_VALUE in modTimes so that
 * detectChanges' `modTime > lastKnown` check can never be true for any real mtime.
 * [markWrittenByUs] replaces it with the real post-write mtime.
 *
 * IMPORTANT: If the write fails (saga compensation runs), call [clearPendingWrite]
 * to remove the sentinel. Without this, the file is permanently suppressed from
 * external-change detection for the lifetime of this FileRegistry instance.
 */
suspend fun preMarkPendingWrite(filePath: String) = detectMutex.withLock {
    modTimes[filePath] = Long.MAX_VALUE
}

/**
 * Removes the pending-write sentinel for [filePath]. Must be called from the saga
 * compensation if the file write fails after [preMarkPendingWrite] was called.
 * Restores the file to the "unknown" state so the next [detectChanges] treats it
 * as a new/unknown file and re-scans it.
 */
suspend fun clearPendingWrite(filePath: String) = detectMutex.withLock {
    // Only clear if it's still our sentinel; markWrittenByUs may have already replaced it
    if (modTimes[filePath] == Long.MAX_VALUE) {
        modTimes.remove(filePath)
    }
}
```

**Complexity**: S

---

### 4. `GraphWriter.kt` — Add `preMarkPendingWrite` call

**Class**: `GraphWriter`, method: `savePageInternal`

Before Step 1 (the existing file write saga step), add:
```kotlin
// Step 0: pre-mark pending write to close the watcher race window.
// Must be called before the file write so detectChanges never sees the
// new mtime without knowing we wrote it.
saga(
    action = { onPreWrite?.invoke(filePath) },
    compensation = { _ ->
        // IMPORTANT: clear the sentinel so the file is not permanently suppressed.
        // Without this, a failed write leaves Long.MAX_VALUE in modTimes and the file
        // is never detected as externally changed again for this FileRegistry lifetime.
        onClearPendingWrite?.invoke(filePath)
    }
)
```

Add both `onPreWrite: (suspend (filePath: String) -> Unit)? = null` and
`onClearPendingWrite: (suspend (filePath: String) -> Unit)? = null` constructor parameters.

Wire in `GraphLoader.kt`:
- `onPreWrite = { filePath -> fileRegistry.preMarkPendingWrite(filePath) }`
- `onClearPendingWrite = { filePath -> fileRegistry.clearPendingWrite(filePath) }`

Add `onPreWrite: (suspend (filePath: String) -> Unit)? = null` constructor parameter (parallel
to existing `onFileWritten`).

Wire in `GraphLoader.kt` where `GraphWriter` is constructed, passing a lambda that calls
`fileRegistry.preMarkPendingWrite(filePath)`.

**Complexity**: S

---

### 5. `GraphFileWatcher.kt` — Expose `isRunning` flag

Add property:
```kotlin
val isRunning: Boolean get() = watcherJob?.isActive == true
```

**Complexity**: S (1-liner)

---

### 6. SAF Suppress Window — NOT EXTENDED (see rationale)

The original plan proposed extending the 200ms suppress window to 2 seconds for SAF paths.
This has been removed: with `preMarkPendingWrite` + saga compensation `clearPendingWrite`,
the watcher race window is closed at the source. Extending the suppress window would add
unnecessary latency to the conflict-dialog path for genuine external changes on SAF.
The `FileSystem.isSafPath` interface addition is also no longer needed.

---

## Summary Task List

| # | Task | File(s) | Complexity | Story |
|---|------|---------|------------|-------|
| T1 | Add `isRunning` to `GraphFileWatcher` | GraphFileWatcher.kt | S | Expose watcher state |
| T2 | Add `getContentHash`, `preMarkPendingWrite`, `clearPendingWrite` to `FileRegistry` | FileRegistry.kt | S | Registry API expansion |
| T3 | Add `onPreWrite` + `onClearPendingWrite` params to `GraphWriter`; call `preMarkPendingWrite` before disk write, `clearPendingWrite` in saga compensation | GraphWriter.kt | S | Close own-write race |
| T4 | Add `onDirtyFile` (suspend) + `activePageFilePaths` lambda to `GraphFileWatcher`; insert active-page guard AFTER suppress-window block | GraphFileWatcher.kt | M | Active-page guard + dirty set population |
| T5 | Add dirty set + `dirtyMutex` to `GraphLoader`; add `addDirty`/`checkAndClearDirty` suspend helpers | GraphLoader.kt | S | Dirty set storage |
| T6 | Derive `activePageFilePaths` from `activePageUuids` in `setActivePageUuids`; track collector job to prevent leaks | GraphLoader.kt | M | UUID→path mapping |
| T7 | Add `forceReload: Boolean` parameter to `parseAndSavePage` and `lookupExistingPageAndCheckFreshness`; bypass inner mtime guard when true | GraphLoader.kt | S | Fix inner mtime guard bypass |
| T8 | Wire `onDirtyFile`, `activePageFilePaths`, `onPreWrite`, `onClearPendingWrite` into `GraphFileWatcher`/`GraphWriter` construction in `GraphLoader` | GraphLoader.kt | S | Wiring |
| T9 | Replace mtime guard in `loadFullPage` with dirty-set + content-hash fallback logic; pass `forceReload=true` to `parseAndSavePage` on dirty-set hit | GraphLoader.kt | M | Core cache guard fix |
| T10 | Write unit tests (see Testing section) | jvmTest/, businessTest/ | M | Test coverage |

**Note**: The SAF-specific 2-second suppress window (originally T10) is **removed**. With
`preMarkPendingWrite` + `clearPendingWrite` in place, the existing 200ms window is sufficient.
Extending it would add unnecessary latency to the conflict-dialog path for genuine external changes.

**Totals**: 1 epic, 3 stories (active-page guard, dirty-set cache, own-write race), 10 tasks
(6 S, 4 M, 0 L)

---

## Platform Behavior Matrix

| Platform | Watcher Available | `isRunning` | Cache Guard Behavior |
|----------|------------------|-------------|----------------------|
| JVM (Desktop) | Yes (polling + inotify) | true | Dirty set — no mtime |
| Android (SAF) | Yes (polling + ContentObserver) | true | Dirty set — no mtime |
| iOS | No | false | Content-hash fallback at navigation time |
| WASM/JS | No | false | Content-hash fallback at navigation time |

For iOS/WASM: cold-start `invalidateStaleShadow` + content-hash check on each `loadFullPage`
call provides correctness. The ~10–30ms additional latency per navigation is within the
<100ms budget from requirements.

---

## How to Handle No File Watcher (iOS/WASM)

When `fileWatcher.isRunning == false`:
1. At `loadFullPage` time, if `allBlocksLoaded` and `!force`:
   - Call `fileRegistry.getContentHash(filePath)`.
   - If `null` (no hash stored yet): fall through to reload (first load, or registry cleared by cold-start purge).
   - If non-null: read the file (`fileSystem.readFile(filePath)`) and compare `diskContent.hashCode()` to stored hash.
   - If equal: skip reload. If different: reload.
2. After `parseAndSavePage` succeeds, `fileRegistry.updateContentHash(filePath, content.hashCode())` is already called via the existing path in `detectChanges`. For iOS/WASM where `detectChanges` doesn't run, we add an explicit `fileRegistry.updateContentHash(filePath, content.hashCode())` call inside `parseAndSavePage` after a successful parse.

This ensures the stored hash is always fresh after a load, so the next navigation can compare correctly.

---

## Conflict with `DiskConflictDialog` (Existing System)

The `externalFileChanges` SharedFlow and `DiskConflictDialog` are **orthogonal** to this change.
The dirty-set approach does not replace conflict detection; it layers under it:

- If a page is in `activePageUuids`: watcher emits `ExternalFileChange` (conflict dialog path) AND marks dirty. The dirty flag is consumed on the next navigation after the user resolves the conflict or navigates away.
- If a page is NOT in `activePageUuids`: watcher marks dirty AND calls `onReloadFile` (auto-reload path, existing behavior). The `DiskConflictDialog` is not involved.

No change to the conflict dialog flow.

---

## Testing Approach

### Unit Tests (businessTest / jvmTest)

**T11a — Stale read eliminated** (`GraphLoaderCacheTest.kt`, new):
1. Load a page via `parseAndSavePage`, confirm DB has content V1.
2. Simulate external write: call `fileRegistry.detectChanges()` on a directory where a test
   file has been updated (in-memory fake filesystem). Confirm `changedFiles` is non-empty.
3. Call `loadFullPage(uuid, force=false)`. Verify DB now has content V2.
4. Precondition: watcher marks path dirty before step 3.

**T11b — Active-page guard** (`GraphFileWatcherTest.kt`, new):
1. Construct `GraphFileWatcher` with `activePageFilePaths = { setOf("/test/page.md") }`.
2. Simulate a file change for `/test/page.md` via `detectChanges`.
3. Verify `onReloadFile` is NOT called.
4. Verify `onDirtyFile` IS called.
5. Verify `externalFileChanges` IS emitted (conflict dialog gets a chance to fire).

**T11c — No false positive after own write** (`GraphFileWatcherTest.kt`, extension):
1. Call `fileRegistry.preMarkPendingWrite("/test/page.md")`.
2. Simulate a polling tick (call `checkDirectoryForChanges` directly).
3. Verify `onReloadFile` is NOT called (pre-mark suppresses it).
4. Call `fileRegistry.markWrittenByUs("/test/page.md")`.
5. Simulate another polling tick.
6. Verify `onReloadFile` is NOT called (normal own-write suppression).

**T11d — Content-hash fallback (no watcher)** (`GraphLoaderCacheTest.kt`):
1. Construct `GraphLoader` with `watcherPollIntervalMs = Long.MAX_VALUE` (watcher effectively not running).
2. Load page V1, confirm hash stored.
3. Mutate the test file to V2 without updating registry (simulate external edit).
4. Call `loadFullPage(uuid, force=false)`.
5. Verify DB has V2 (content-hash mismatch triggered reload).

**T11e — Content-hash fast path (no change)** (`GraphLoaderCacheTest.kt`):
1. Same setup as T11d but file is NOT changed after initial load.
2. Spy on `fileSystem.readFile` call count.
3. Call `loadFullPage(uuid, force=false)` twice.
4. On second call: content-hash matches → no `parseAndSavePage`. Verify readFile called exactly once (for hash comparison), not twice.

**T11f — FAT/exFAT 2-second window** (`GraphLoaderCacheTest.kt`):
1. Set up fake filesystem where `getLastModifiedTime` returns a stale value
   (same mtime for V1 and V2 writes, simulating FAT granularity).
2. Load V1, then load V2 (external edit, same mtime).
3. Confirm new design loads V2 (dirty set / content-hash path, not mtime path).
4. Confirm old design would have skipped V2 (document regression prevention).

### Integration / Manual Tests

- Android: open graph on external SD card (FAT32). Edit a page in Logseq desktop via Syncthing.
  Navigate to the page in SteleKit within 5s (before next poll) — should show updated content after dirty-set path fires.
- Android SAF cloud: open a Nextcloud SAF graph. Edit externally. Open page — should show
  updated content (content-hash path, since cloud provider may lag mtime).
- iOS: edit page in Files app. Open SteleKit, navigate to page — content-hash fallback fires.

### Existing Tests

Run `./gradlew jvmTest` and `./gradlew testDebugUnitTest` after each task to ensure no
regressions. Key existing tests to watch:
- `GraphLoaderTest` — all mtime-based tests that pass `force=true` remain valid.
- `GraphFileWatcherTest` — existing suppression tests must still pass.
- `FileRegistryTest` — `markWrittenByUs` and `detectChanges` tests must pass.

---

## Key Architectural Decisions

1. **Dirty set in GraphLoader, not GraphFileWatcher**: Keeps change-detection logic in the
   watcher and cache-invalidation logic in the loader. No new cross-layer dependency.

2. **Lambda-based `activePageFilePaths` in watcher**: Avoids passing `PageRepository` or
   `StateFlow<Set<String>>` into `GraphFileWatcher`. The watcher calls back into `GraphLoader`
   implicitly; `GraphLoader` owns the UUID→path resolution.

3. **`onDirtyFile` is `suspend`**: The dirty-set `Mutex` requires suspension. Using a non-suspend
   lambda would require a `launch` wrapper that creates an ordering race where `checkAndClearDirty`
   could run before `addDirty` completes. Direct `suspend` call eliminates the race.

4. **Active-page guard placed AFTER `externalFileChanges` emit**: The conflict dialog must fire
   even for pages being actively edited. The `onReloadFile` skip happens after the 200ms suppress
   window — emit and wait first, then check active-page status.

5. **`forceReload` threaded through both guards**: There are two mtime guards — one in
   `loadFullPage` and one in `lookupExistingPageAndCheckFreshness`. Both must be bypassed together
   when a dirty-set hit triggers a reload. The `forceReload: Boolean` parameter threads through
   `parseAndSavePage` → `lookupExistingPageAndCheckFreshness` to ensure both are skipped.

6. **`preMarkPendingWrite` + `clearPendingWrite` pair**: `Long.MAX_VALUE` sentinel closes the race
   window before disk write. The compensation `clearPendingWrite` removes the sentinel on write
   failure, preventing permanent suppression of the file from external-change detection.

7. **Content-hash fallback only when watcher is NOT running**: On JVM/Android the watcher is
   always running, so no SAF read is needed at navigation time. The content-hash path is only
   taken on iOS/WASM where no watcher exists. The already-read `diskContent` is passed directly
   to `parseAndSavePage` on hash mismatch to avoid a second file read.

8. **No new external dependencies**: All hashing uses `String.hashCode()` which is already
   used by `FileRegistry`. No new Kotlin library needed.

9. **Dirty set is not persisted**: On process kill, Android's cold-start `invalidateStaleShadow`
   purge provides the recovery path. Persisting the dirty set to SharedPreferences or DB adds
   complexity without meaningful benefit.

10. **SAF suppress window stays at 200ms**: With `preMarkPendingWrite` closing the own-write race
    at source, extending the window is unnecessary and would slow conflict-dialog response on SAF.
