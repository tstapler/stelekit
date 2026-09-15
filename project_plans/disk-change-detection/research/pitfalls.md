# Pitfalls: Disk Change Detection — Implementation Risks

## 1. FileObserver Reliability

### Directory deletion and recreation

`FileObserver` wraps a single `inotify_add_watch` call on a directory **inode**. If `$graphPath/pages` is deleted and recreated (e.g., a sync client does an atomic replace-by-rename, or the user clears the folder), the old inode is gone. Android does not replace the watch descriptor automatically — the existing `FileObserver` becomes a dangling watch, and subsequent file changes in the new directory are silently missed until `stopWatching()` + `startWatching()` is called.

The current `SafChangeDetector.startFileObservers` does not register for `IN_DELETE_SELF` / `IN_MOVE_SELF` events on the watched directory inodes, so directory deletion is invisible to it. The 30-second polling fallback (SAF path) and the `ProcessLifecycleOwner.onStart` observer both recover from this eventually, but up to 30 seconds of changes can be missed on the MANAGE_EXTERNAL_STORAGE path where there is no ContentObserver fallback.

**Mitigation**: Register `FileObserver.DELETE_SELF` in the mask; on receipt, cancel the existing observer and schedule a restart after the directories reappear (e.g., a retry loop in the scope). Alternatively, also watch the graph root directory for `IN_CREATE` events (subdirectory creation) and re-arm subdirectory observers when `pages/` or `journals/` appears.

### Background state

`FileObserver` uses inotify at the OS level and continues to accumulate events in the kernel ring buffer while the app is in the background. Events are not dropped, but the ring buffer is bounded (typically 16 384 entries per inotify instance on Android). On a large Termux import that creates thousands of files while the app is backgrounded, the ring buffer can overflow (`IN_Q_OVERFLOW` is delivered). The current `onEvent` implementation does not handle `event == FileObserver.Q_OVERFLOW` (value 16384), so the overflow is silently discarded. The `ProcessLifecycleOwner.onStart` observer fires a poll on foreground, so most practical cases recover — but changes that arrived between the overflow and foregrounding are not individually tracked.

**Mitigation**: Handle `event == FileObserver.Q_OVERFLOW` explicitly: log a warning and trigger `onExternalChange()` to force a full scan.

### inotify watch limit

Each `FileObserver` instance consumes one inotify watch descriptor. Android's default `/proc/sys/fs/inotify/max_user_watches` is typically 8192, shared across all processes. With two `FileObserver` instances per graph (pages + journals), the current implementation uses 2 watches per open graph. This is safe for single-graph use, but could interact poorly with third-party apps (Syncthing, Dropbox) that hold large numbers of watches. Not a defect in the current design, but worth noting for multi-graph future work.

---

## 2. ContentObserver SAF URI Matching

### Provider-specific document ID format assumptions

The `ContentObserver` is registered on:
```
buildChildDocumentsUriUsingTree(treeUri, "$treeDocId/pages")
buildChildDocumentsUriUsingTree(treeUri, "$treeDocId/journals")
```

This assumes `ExternalStorageProvider` document IDs follow the `"$volumeId:$relativePath"` + `/`-separator convention (e.g., `"primary:personal-wiki/logseq/pages"`). This is correct for AOSP `ExternalStorageProvider` and the standard OEM variants (Samsung, Pixel, OnePlus), but **it is not guaranteed by the SAF API contract**.

Known failure cases:
- **MicroG / LineageOS custom storage providers**: Some providers use `"msf:"` or UUID-based document IDs instead of volume-relative paths. The concatenated doc ID `"$treeDocId/pages"` does not exist in their namespace, so `contentResolver.query()` on the built URI returns an empty cursor or throws. The registration succeeds (no exception) but never fires.
- **USB OTG / SD card on older AOSP (Android 10–11)**: Removable volume doc IDs use UUID prefixes (`"<uuid>:relative/path"`). The children URI for a sub-document is built correctly, but some OEM storage providers do not call `ContentResolver.notifyChange()` for subdirectory mutations at all — only for root-level changes.
- **Notification with `notifyForDescendants = true`**: The current registration passes `notifyForDescendants = true`. If the provider uses flat document IDs (not path-structured), the prefix matching inside `ContentResolver` may not match child document URIs. The 30-second polling fallback is the safety net for these devices.

### Android version constraints

- **Android < 10 (API < 29)**: `ContentObserver` for SAF subdirectory children URIs is unreliable. On Android 9 and below, `ExternalStorageProvider` does not consistently call `notifyChange` on child document URIs. The polling fallback is essential here.
- **Android 10–12**: Works reliably on Pixel / AOSP reference devices. Samsung One UI 3.x has been observed to batch `notifyChange` calls with a ~2-second delay.

**Recommendation**: Document explicitly that the fast-path `ContentObserver` is best-effort; the 30-second poller and `onStart` scan are the correctness guarantee. Consider reducing the poll interval to 15 seconds on devices where `ContentObserver` failed to register (track registration success in a flag).

---

## 3. ShadowFlushActor Race: onStart During Flush

**Scenario**: The app is backgrounded while `ShadowFlushActor.flush()` is executing (write-behind drain triggered by a previous `onStart`). The user immediately brings the app back to foreground. `ProcessLifecycleOwner.onStart` fires → `onExternalChange()` → `checkDirectoryForChanges()` → `fileRegistry.detectChanges()` → SAF mtime comparison.

The race window:
1. `flush()` calls `fileSystem.writeFile(safPath, content)` — the SAF write completes.
2. `flush()` has NOT yet called `fileSystem.getLastModifiedTime(safPath)` + `shadowCache.stampMtime()` + `onFlushed?.invoke(safPath)`.
3. The concurrent `detectChanges()` call reads the SAF mtime (which is now newer than what `FileRegistry` recorded before the flush). It sees the file as "changed" and emits an `ExternalFileChange` event.
4. After `detectChanges()` returns, `flush()` finally calls `onFlushed`, recording the new mtime in `FileRegistry`.
5. The emitted event is already in-flight through the `_externalFileChanges` SharedFlow buffer.

**Effect on plaintext `.md` files**: The content-hash guard in `FileRegistry.detectChanges()` compares the file content against the in-memory hash. Since the app just wrote this content, the hash matches and the event is suppressed — **no spurious reload for plaintext files**.

**Effect on encrypted `.md.stek` files**: The content-hash guard is disabled (the ciphertext changes on every re-encryption even if the plaintext is identical). `detectChanges()` has no hash to compare and will emit the change as a genuine external modification. This triggers either a reload or a conflict dialog for content the user just saved.

The race is narrow (the time between `writeFile()` returning and `onFlushed()` being called — typically a single SAF IPC round-trip of ~5–50 ms). The `onStart` scan fires on the main thread via `Handler.post`, so it runs after the current Looper message completes. If `flush()` is running on `Dispatchers.IO`, there is a real concurrent overlap window.

**Mitigation**: Record the SAF path in a "pending flush" set immediately before `writeFile()` is called (not after), and clear it in `onFlushed`. In `detectChanges()`, skip paths present in this set. This shrinks the window to zero for correctly-instrumented paths.

---

## 4. `unsavedPageFilePaths` UUID-to-Path Resolution

### Null UUID (page not yet in DB)

`setUnsavedPageUuids` collects from `pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()`. If the user starts typing in a new page that has been created optimistically in `BlockStateManager` but whose DB write is still in flight (blocked behind a Phase-3 actor queue), `getPageByUuid` returns `null` and `mapNotNull` silently drops the UUID.

The result: the dirty page has no entry in `unsavedPageFilePaths`, so `GraphFileWatcher` does not guard it. If an external change arrives for that page's file path during the write-in-flight window, `onReloadFile` fires and overwrites the just-typed content.

This is a TOCTOU gap: the page guard is consulted at file-change time, but the UUID→path mapping is resolved at edit-start time. If the mapping hasn't materialized yet, the guard is empty.

**Mitigation**: In `setUnsavedPageUuids`, use a `retry { }` or `retryWhen { cause, attempt -> delay(50); attempt < 5 }` around the `first()` call, or alternatively keep the raw UUID set as a second guard and cross-reference it in `GraphFileWatcher.checkDirectoryForChanges` by also checking `activePageUuids` before emitting `onReloadFile`.

### 50 dirty pages (N+1 DB reads)

Each emission of `dirtyPageUuids` triggers a `collect` lambda that does one `pageRepository.getPageByUuid(...).first()` call per UUID in the set. With 50 dirty pages, this is 50 sequential DB reads on `parallelScope`. For typical single-page editing this is irrelevant. For bulk-import or automated edit tools that mark many pages dirty simultaneously, the collector can be running DB reads continuously while GraphLoader is also mid-Phase-3 bulk load. This creates lock contention on the SQLDelight connection pool.

**Mitigation**: Debounce the `collect` emission (e.g., `debounce(200)` before the collector lambda) to avoid the N+1 pattern on rapid burst updates. The debounce already exists in `BlockStateManager.diskWriteDebounce` but not in the UUID collector in `GraphLoader`.

---

## 5. `isFirstAccess` and Multiple `PlatformFileSystem` Instances

### Is `PlatformFileSystem` a singleton?

`PlatformFileSystem` is not a Kotlin `object` (singleton). In `SteleKitApplication`, it is instantiated once and stored:

```kotlin
// SteleKitApplication.kt
val fileSystem = PlatformFileSystem()
```

This single instance is passed throughout the app via DI. A second `PlatformFileSystem()` is not created in normal app flow. So in practice, there is only one `ShadowFileCache` per graph ID at runtime.

However, `shadowCache` inside `PlatformFileSystem` is replaced (not appended) on each call to `pickDirectoryAsync()` or on `init()` when restoring a persisted SAF URI. Each replacement creates a `new ShadowFileCache(ctx, graphIdFor(docId))` — a fresh instance with `freshInstance = AtomicBoolean(true)`. This is intentional and correct: switching graphs should reset the first-access flag.

**The real risk**: Two `PlatformFileSystem` instances could co-exist in tests or in a hypothetical multi-graph scenario. Each would have its own `shadowCache` with `freshInstance = true`, and both would race to purge the same on-disk shadow directory (`context.filesDir/graphs/$graphId/shadow/`). The second `deleteAll()` call would succeed but find nothing to delete — harmless but wasteful. The `syncFromSaf` call that follows would populate the directory correctly from SAF. **No data corruption risk**, but the double-purge is wasted I/O.

**The remaining real-world risk**: If the user performs a graph switch while a `syncFromSaf` write is in progress on the old `ShadowFileCache`, and the new `ShadowFileCache.deleteAll()` runs concurrently, the partial sync files are deleted mid-write. The shadow file for the old graph could be left in a 0-byte state. `ShadowFileCache.resolve()` guards against this (`f.length() > 0` check), so a 0-byte shadow falls through to SAF — correct behavior.

---

## 6. `combine(_dirtyBlocks, _blocks)` vs. `_dirtyBlocks` Alone

### Why `_blocks` is needed

`_dirtyBlocks` is a `Map<String, Long>` keyed by **block UUID** → version number. It does not contain page UUIDs. `dirtyPageUuids` must emit **page** UUIDs (required by `setUnsavedPageUuids`). To map block UUID → page UUID, the implementation cross-references `_blocks: Map<String, List<Block>>` (keyed by page UUID), looking for pages whose block list contains at least one block UUID in `_dirtyBlocks.keys`.

Without `_blocks` in the combine, the only alternative would be:
- Calling `blockRepository.getBlockByUuid(blockUuid).first()` for each dirty block — a DB read per keystroke, far worse.
- Maintaining a separate `blockUuid → pageUuid` index — additional complexity and duplication.

### Could `_blocks` be simplified out?

Partially: if `_dirtyBlocks` were changed to `Map<String, String>` (blockUuid → pageUuid) instead of (blockUuid → version), the page UUID could be derived directly from `_dirtyBlocks.values.toSet()` without needing `_blocks`. However, `_dirtyBlocks`'s version number is load-bearing: in `mergeBlocks()`, `dirtyVersion > incoming.version` determines whether to keep the local edit vs. accept the DB version. Removing the version would break the optimistic-update merge logic.

**Alternative**: Add a secondary `_dirtyBlockToPage: MutableStateFlow<Map<String, String>>` populated alongside `_dirtyBlocks`. The `combine` could then use just `_dirtyBlockToPage` and avoid iterating `_blocks` entirely. This would also make `dirtyPageUuids` independent of `_blocks` emissions — currently, every block load (any page) triggers a recompute of `dirtyPageUuids` even if the dirty set is empty. The `if (dirtyBlocks.isEmpty()) emptySet()` short-circuit handles the common case, but when editing is active, every reactive block emission from any page triggers a full re-scan of all page entries in `_blocks`.

**Recommendation**: The current `combine` is correct. For graphs with hundreds of observed pages (e.g., left-panel sidebar pinning many pages), the re-scan on every `_blocks` emission while editing could be noticeable. Adding `distinctUntilChanged()` after the `combine` (before `stateIn`) would prevent downstream recomposition if the resulting set doesn't change, at the cost of one extra equality check per emission. This is low-risk and worth adding.

---

## Summary

- **`FileObserver` directory-deletion blindness** is the most severe gap: a Termux `mv pages pages.bak && mkdir pages` leaves the watcher watching a dead inode with no recovery until the next `onStart` scan. The fix (add `DELETE_SELF` to the mask and re-arm on receipt) is straightforward.
- **The `ShadowFlushActor` mtime race** is narrow (~5–50 ms) but deterministic for encrypted graphs: a concurrent `onStart` scan always wins the race against `onFlushed` for `.md.stek` files and will emit a spurious conflict dialog. Recording the path in a "flush-pending" set before the SAF write closes this window.
- **UUID→path resolution misses pages with in-flight DB writes**: a new page created optimistically while the actor queue is congested has no entry in `unsavedPageFilePaths`, leaving the dirty-page guard empty for that page during the write window. Retrying the `getPageByUuid` call or keeping the raw UUID set as a fallback guard eliminates the gap.
