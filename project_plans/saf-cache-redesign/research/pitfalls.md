# Agent 4 — Risks & Pitfalls Research

## Subject
Known failure modes of content hashing for cache invalidation, dirty-set survival under low memory,
own-write false-positives from the file watcher, races between DatabaseWriteActor and page reloads,
and the "edit in progress, external change arrives" race in KMP apps.

---

## 1. Content Hashing Failure Modes

### False negatives (missed changes)
A false negative occurs when the hash of the new content equals the hash of the old content despite
different content. This is a hash collision.

**`String.hashCode()` (32-bit)**:
- Collision probability: 1 in 2^32 (~1 in 4.3 billion) per comparison.
- At a graph size of 1,000 pages checked once per navigation, the expected collision rate over a
  year (~365,000 checks) is ~0.000085 collisions/year. Practically negligible.
- Known weakness: `String.hashCode()` is not a cryptographic hash. Two strings that differ only
  in character arrangement may share a hash. For short strings (e.g. a page containing only
  `"- hello"` vs `"- eohlll"` — contrived), Java's polynomial hash has known fixed points. For
  typical Logseq markdown content (structured text with UUIDs, timestamps, varied content), the
  practical collision probability is consistent with the theoretical 1/4B figure.

**CRC32**:
- Same 32-bit space as `String.hashCode()`, similar practical collision probability.
- CRC32 is designed for error detection, not hash distribution; it is slightly more vulnerable
  to adversarial collision than `hashCode()`, but for benign content changes (external editor
  saves) this distinction is irrelevant.

**xxHash32**:
- Same 32-bit space. Better avalanche behaviour than CRC32 for similar-content inputs.
- No new dependency if using `java.util.zip.Adler32` as a substitute; xxHash requires
  `lz4-java` or similar. For SteleKit's KMP constraint, a pure-Kotlin implementation would
  be needed for iOS/WASM — this is a non-trivial dependency addition.

**Recommendation**: Remain with `String.hashCode()` for the content-hash guard. The existing
`FileRegistry.contentHashes` map already uses this. Adding a new hash algorithm adds a dependency
without meaningfully reducing the already-negligible false-negative rate.

### False positives (spurious reload)
A false positive occurs when the hash indicates the content changed but it hasn't (our own write
was detected as external). SteleKit already mitigates this via `markWrittenByUs()` which updates
the stored hash after each own-write.

**Remaining false-positive risk**: Race between `parseAndSavePage` completing and
`markWrittenByUs` running. In `parseAndSavePage`, the sequence is:
1. Parse content, build blocks
2. Save page to DB via `writeActor.savePage()`
3. Save blocks to DB via `writeActor.saveBlocks()`
4. `fileRegistry.updateModTime(filePath, updatedModTime)` (at end of `parseAndSavePage`)

`markWrittenByUs` is called from `GraphWriter.savePageInternal` via `onFileWritten` callback after
the file is written to disk. If a watcher tick occurs between the file-write and the
`markWrittenByUs` callback, the watcher may see the new mtime and trigger a spurious re-import.

The existing `suppressChannel` mechanism (200ms window) mitigates this: the watcher emits an
`ExternalFileChange` and waits 200ms for a `suppress()` call. The ViewModel can suppress if the
page is the one we just saved. But this window is fragile under heavy I/O — a slow SAF write
could exceed 200ms before `markWrittenByUs` runs.

**Recommended fix**: Extend the suppression window (currently 200ms) to 2 seconds for SAF paths,
since SAF Binder IPC for `getLastModifiedTime` after a write can take 100–500ms on some devices.
Or, more robustly: call `fileRegistry.markWrittenByUs()` *before* the file write begins (using the
expected new mtime range), not after.

---

## 2. Dirty-Set Survival Under Low Memory

### The `onTrimMemory` scenario
Android calls `Activity.onTrimMemory(level)` when the system needs memory. At levels
`TRIM_MEMORY_RUNNING_CRITICAL` and above, the process may be killed. An in-memory `Set<String>`
of dirty file paths does not survive a process kill.

**Current SteleKit behaviour**:
- `GraphLoader.backgroundIndexJob?.cancel()` is the only memory-pressure response (cancels
  background parsing).
- No dirty set exists in the current design; the watcher-triggered reloads run immediately
  (`onReloadFile` → `parseAndSavePage`).
- After a process kill and restart, `invalidateStaleShadow` purges the shadow cache, causing all
  pages to be re-read from SAF on first access. This is the correct recovery path.

**For any proposed dirty set**:
- An in-memory dirty set is lost on process kill. After restart, the reconcile loop in
  `loadGraphProgressive` reruns `invalidateStaleShadow` + full directory scan, which effectively
  re-computes the dirty set by comparing disk state to DB state. No persistent dirty set is needed.
- If a dirty set is introduced for the warm-path (between cold starts), it must survive
  `onTrimMemory` at lower levels (e.g. `TRIM_MEMORY_UI_HIDDEN`) where the process is not killed
  but memory is reduced. At these levels, the dirty set is safe (process is alive, set is retained).
- **Do not persist the dirty set to SharedPreferences or DB** — on restart, the full reconcile
  (which already happens) is the correct mechanism.

### The `AnkiDroid` pattern
AnkiDroid issue #2264 shows the standard pattern: on `onTrimMemory`, call `cache.evictAll()` to
release in-memory LRU caches. The equivalent for SteleKit's `backgroundIndexJob.cancel()` is
already implemented. A dirty set would follow the same pattern: on low memory, cancel background
work but keep the dirty set intact (it's small — a few dozen string paths at most).

---

## 3. File Watcher Double-Reload from SteleKit-Originated Writes

### The race
When `GraphWriter.savePageInternal` writes a file:
1. File is written to disk (SAF IPC, 20–200ms)
2. `onFileWritten?.invoke(filePath)` → `GraphLoader.markFileWrittenByUs(filePath)` →
   `fileRegistry.markWrittenByUs(filePath)` (queries `getLastModifiedTime`, updates `modTimes` map)
3. The 5-second polling loop in `GraphFileWatcher.checkDirectoryForChanges` next runs

The race: if the polling loop fires *between steps 1 and 2*, it sees a new mtime (from step 1) but
the `modTimes` map still has the old mtime (step 2 hasn't updated it). Result: the file is treated
as externally changed and `onReloadFile` is called, triggering a spurious re-parse.

**Current mitigation**: The `suppressChannel` / 200ms suppress window in `GraphFileWatcher`. But
for Android's ContentObserver fast path, the notification fires *immediately* after the SAF write
completes — well within the window where `markWrittenByUs` hasn't run yet.

**Additional mitigation already in place**: `detectChanges()` reads the file and checks its
`contentHashes` — if the content hash matches (own-write just finished), it updates `modTimes`
and skips emitting the change. But for `.md.stek` encrypted files, the content-hash guard is
disabled (line 119–123 in `FileRegistry.detectChanges`), so encrypted files rely solely on mtime
and `markWrittenByUs`.

**Recommended fix**: In `GraphWriter.savePageInternal`, call a "pre-mark" before the actual write:
```kotlin
fileRegistry.preMarkPendingWrite(filePath)  // mark that we are about to write
// ... write to disk ...
fileRegistry.markWrittenByUs(filePath)       // update with real post-write mtime
```
The `preMarkPendingWrite` would add `filePath` to a temporary suppression set that
`detectChanges` respects, eliminating the race window entirely.

---

## 4. Reloading While DatabaseWriteActor Has a Pending Write

### The race scenario
`DatabaseWriteActor` serializes all DB writes. Consider this sequence:
1. User edits page A; `GraphWriter.queueSave()` enqueues a debounced save (500ms delay).
2. The 500ms delay expires; `savePageInternal` begins writing `.md` to disk (SAF IPC: ~100ms).
3. External change watcher fires (Syncthing just wrote the same `.md` file).
4. `onReloadFile` → `parseAndSavePage(filePath, newContent, FULL)` is called.
5. `parseAndSavePage` calls `writeActor.savePage(page)` — this queues behind the in-flight write
   from step 2 in the actor queue.
6. Meanwhile, `savePageInternal` completes, writing *old* content to disk.
7. The actor eventually processes step 5's `savePage` with the *new* content from the external
   change — correct.

But there is a window (steps 2–6) where the DB has the user's unsaved edit state and the disk has
the old content, while the external file has newer content. The correct final state (new external
content) is eventually written to DB, but during the window the UI may render stale content.

**This race is benign** in most cases because:
- `DatabaseWriteActor.Priority.HIGH` is used for user-triggered saves; watcher reloads use
  `Priority.LOW` or default. HIGH writes preempt LOW writes in the actor queue.
- The `activePageUuids` guard prevents reloading a page the user is actively editing.
- If the user is not actively editing, the 500ms debounce means this race requires a Syncthing
  write to arrive within the 500ms window — unlikely but possible.

**Recommended mitigation**: In `GraphFileWatcher.checkDirectoryForChanges`, before calling
`onReloadFile`, check whether the file's page is in `activePageUuids`. If yes, emit to
`externalFileChanges` but do NOT call `onReloadFile` — wait for the user to save or navigate
away. This check already exists for the watcher's own logic but should be made explicit.

### Blank-file guard interaction
`dispatchFullBlockWrites` in `GraphLoader` already has a "blank-file guard" that refuses to
replace non-empty DB block state with a blank-file parse result. If the external change produces
an empty file (race with a partially-written Syncthing temp file), the guard prevents data loss.
This is an existing safeguard that must be preserved.

---

## 5. "Edit in Progress, External Change Arrives" Race in KMP

### The invariant
`activePageUuids: StateFlow<Set<String>>` is set by `StelekitViewModel` (via
`GraphLoader.setActivePageUuids`) to the set of page UUIDs currently open in an active
`BlockStateManager` session.

**Current behaviour**:
- `indexRemainingPages` (background Phase 3): skips pages in `activePageUuids`.
- `GraphFileWatcher` / `onReloadFile`: does NOT currently check `activePageUuids` before calling
  `parseAndSavePage`. The watcher proceeds with the reload, which writes new blocks to the DB.
  If `BlockStateManager` still holds unsaved edits for those blocks, the next debounced save from
  `BlockStateManager` will overwrite the reloaded blocks with the stale edit state.

**This is a real data-loss path**:
1. User edits page A in SteleKit (blocks in `BlockStateManager`).
2. Logseq desktop writes a new version of page A (e.g. adds a journal entry).
3. Watcher detects the change, calls `onReloadFile` → `parseAndSavePage` → overwrites blocks.
4. 500ms later, `BlockStateManager`'s debounced save fires → `GraphWriter.queueSave` → writes the
   *old edit state* back to disk, clobbering Logseq's changes.

**Required fix**: In `GraphFileWatcher.checkDirectoryForChanges`, before calling `onReloadFile`:
```kotlin
val pageUuid = pageRepository.getPageByFilePath(filePath)?.uuid
if (pageUuid != null && pageUuid in (activePageUuids?.value ?: emptySet())) {
    // emit conflict event; do NOT reload
    _externalFileChanges.tryEmit(ExternalFileChange(filePath, content) { suppressed = true })
    continue
}
```
This is analogous to what `indexRemainingPages` already does. The watcher must use the same guard.

**Note on iOS/WASM**: These platforms have no file watcher. External changes are only discovered
during `loadGraphProgressive` at startup. The `activePageUuids` guard in `indexRemainingPages`
correctly handles this case for background re-indexing.

---

## 6. Specific KMP/Coroutine Pitfalls

### `Mutex` contention in `FileRegistry`
`FileRegistry.detectMutex` serializes both `scanDirectory` and `detectChanges`. If `detectChanges`
is called frequently (e.g. every 5 seconds for both pages/ and journals/ = 2 calls/cycle), and a
`scanDirectory` call happens concurrently (e.g. during warm reconcile), the two will contend.
On JVM this is fine (lock is uncontended most of the time). On Kotlin/Native, mutex semantics
differ slightly; the `Mutex` is still coroutine-based and should be fine.

### `parallelScope` lifetime
`GraphLoader.parallelScope` uses `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and is
never cancelled in the current codebase (no matching `scope.cancel()` in `stopWatching`). This
means if a `GraphLoader` instance is discarded without calling `stopWatching()`, launched coroutines
keep running. In the multi-graph scenario (`GraphManager`), this could leak coroutines per closed
graph. This is a pre-existing issue unrelated to the cache redesign, but any new coroutines launched
in `parallelScope` should be aware of this.

### `FileRegistry` shared between scan and detect
`FileRegistry.modTimes` is a `mutableMapOf` (non-thread-safe `LinkedHashMap`) accessed only under
`detectMutex`. This is correct. New code that reads or writes `modTimes` or `contentHashes` outside
the mutex would introduce a race on JVM (concurrent map reads are unsafe even without concurrent
writes on `HashMap` due to internal rehashing).

---

## 7. Summary of Recommended Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Content hash false negative (same hash, different content) | Very low (1/4B per check) | Accept; use `String.hashCode()` already in use |
| Dirty set lost on process kill | Medium | Accept; cold-start shadow purge is the recovery path |
| Own-write detected as external change | Medium | Extend suppress window for SAF; add `preMarkPendingWrite` |
| Reload while DatabaseWriteActor has pending write | Low | Benign; active-edit guard prevents worst case |
| External change during active edit → data loss | High | Add `activePageUuids` check to `onReloadFile` call site in `GraphFileWatcher` |
| `FileRegistry` mutex contention | Low | Acceptable; mutex uncontended in steady state |
| `parallelScope` leak on graph close | Low | Pre-existing; unrelated to this redesign |

---

## Sources

- [Remove caches when receiving onTrimMemory — AnkiDroid #2264](https://github.com/ankidroid/Anki-Android/issues/2264)
- [Android Code Memory Optimization — androidperformance.com](https://androidperformance.com/en/2015/07/20/Android-Performance-Memory-onTrimMemory/)
- [Caching and Cache Invalidation — bumptech/glide wiki](https://github.com/bumptech/glide/wiki/Caching-and-Cache-Invalidation)
- [Solving race conditions in Kotlin coroutines — medium.com](https://medium.com/@1mailanton/solving-problem-of-race-condition-in-kotlin-coroutines-958abfceab37)
- [Concurrency and coroutines — Kotlin Multiplatform docs](https://kotlinlang.org/docs/multiplatform-mobile-concurrency-and-coroutines.html)
- [Joplin sync conflicts — ctrl.blog](https://www.ctrl.blog/entry/joplin-notes-sync.html)
- [Nextcloud SAF quick succession file corruption — nextcloud/android #5806](https://github.com/nextcloud/android/issues/5806)
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt`
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (`loadFullPage`, `indexRemainingPages`, `dispatchFullBlockWrites`)
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`
