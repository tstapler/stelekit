# Features / Comparable Products Research
# Android Block Insert Performance

## 1. Existing Write Path Analysis

### Write Pipeline (end to end for a block insert)

1. **User action** (Enter key / paste / `[[` link insert) fires in a Compose composable.
2. `BlockStateManager` (commonMain) immediately applies an optimistic update to `_blocks` (MutableStateFlow) — UI updates synchronously on the calling coroutine without waiting for any I/O.
3. `writeActor.saveBlock(block)` or `writeActor.updateBlockContentOnly(...)` is dispatched through `DatabaseWriteActor.execute(Priority.HIGH)` — the call **suspends** (`.await()`) until the actor processes it.
4. `DatabaseWriteActor` runs in its own `CoroutineScope(SupervisorJob() + PlatformDispatcher.Default)`. It serializes all writes through two `Channel.UNLIMITED` channels (HIGH and LOW). One coroutine drains high-priority first, then low-priority via `select {}`.
5. For the block write the actor calls `blockRepository.updateBlockContentOnly(...)` which hits `SqlDelightBlockRepository` under `withContext(PlatformDispatcher.DB)`. On Android, `PlatformDispatcher.DB = Dispatchers.IO`.
6. **Separately**, `BlockStateManager.queueDiskSave(pageUuid)` launches a 300 ms debounced task via `DebounceManager`. After the debounce fires, it calls `graphWriter.queueSave(page, blocks, graphPath)` which triggers a second 500 ms debounce inside `GraphWriter` itself (per-page).
7. When the GraphWriter debounce fires, `savePageInternal()` runs the Arrow Saga: reads the existing file (safety check), writes the new markdown, notifies the file-watcher registry, writes the sidecar, and updates the DB filePath for new pages.

### Key Latency Suspects

**The DB write (step 3–5) is synchronous from `BlockStateManager`'s perspective.** The `addBlockToPage` and `applyContentChange` paths both `await()` the actor result before returning. Any slowness in the actor queue (HIGH channel) or in `SqlDelightBlockRepository` directly delays the function that runs in `scope.launch {}` off the main thread — but if the calling composable observes any of those StateFlow values, recomposition may stall waiting for state to stabilize. More critically, `splitBlock` and `blockRepository.splitBlock()` are called inline and also `await()` actor results, meaning UI focus changes can wait on the full DB round-trip.

**The file write (steps 6–7) is fire-and-forget** (debounced, not awaited by the caller) — this is already correct and should not cause perceived latency.

**SAF overhead concentrates in step 7**, specifically `fileSystem.writeFile()` which calls `contentResolver.openOutputStream(docUri, "wt")`. Each SAF write makes at least two Binder IPC hops: one to a system ContentProvider proxy, which itself calls the DocumentsProvider. This is the root cause documented in the XDA/AOSP literature as 25–50x slower than direct filesystem for directory operations, and ~10–200 ms per write for individual file writes depending on provider and device.

**A `ShadowFileCache` already exists** on Android (written to `context.filesDir/graphs/<id>/shadow`) to accelerate *reads* during Phase 3 background indexing. It is updated via `updateShadow()` after each SAF write. This shadow is not currently used to defer or replace the SAF write itself.

### Double-Debounce Timing

There are two debounce layers stacked:
- `DebounceManager` in `BlockStateManager`: 300 ms
- `GraphWriter.queueSave()`: 500 ms (per-page, cancels the previous job on new edits)

Net effect: a disk write fires 500 ms after the last edit on a page (the `DebounceManager` fires at 300 ms and calls `graphWriter.queueSave()`, which resets the 500 ms timer). The stacking means an aggressive typist could delay the disk write indefinitely (correct behavior), but for a single block insert the file hits disk ~500 ms after the insert.

---

## 2. How Comparable Apps Handle the Write Path

### Pattern: Write-Behind with DB as Source of Truth (most common in production note apps)

The dominant pattern used by Obsidian Mobile, Joplin, and Bear is:
1. Write optimistically to an in-memory structure or local SQLite DB immediately (sub-millisecond).
2. Return control to the UI.
3. Flush to the filesystem on a timer (100–500 ms debounce) or on `onPause`/`onStop`.
4. The DB (or in-memory state) is authoritative; the file is a durable backup. On crash after a DB write but before a file write, the next launch regenerates the file from DB.

This is exactly the architecture SteleKit already uses for the *file* write. The issue is that SteleKit's *DB write* is also synchronous from the caller's perspective (the actor's `.await()` pattern).

### Pattern: Optimistic-Insert Fast Path (used by Notion, Bear, Apple Notes)

For structural inserts (new block, split, indent), apps do:
- Assign UUID client-side immediately (no round-trip needed for ID generation).
- Insert into local state + display immediately.
- Queue DB write fire-and-forget — caller does NOT await the result.
- If the DB write fails, show an error banner and retry (rather than rolling back the UI).

SteleKit's `addBlockToPage` already does this correctly for the `+` button path (optimistic insert into `_blocks`, then `writeBlock(newBlock)` result only used for rollback). However, `splitBlock`, `indentBlock`, `outdentBlock`, `mergeBlock`, and `handleBackspace` all call `blockRepository.*` methods directly (bypassing the actor's queue) and then `refreshBlocksForPage` which does a synchronous `getBlocksForPage(...).first()` DB read — this round-trip (write + read) is likely a significant contributor to perceived latency on Android.

### Pattern: Actor Queue Fast Path for Structural Ops

Notably, `splitBlock` calls `blockRepository.splitBlock(blockUuid, cursorPosition).onRight { newBlock -> requestEditBlock(newBlock.uuid) }`. The `splitBlock` implementation in `SqlDelightBlockRepository` does multiple DB operations (update existing, insert new, reorder siblings) under a single `withContext(PlatformDispatcher.DB)`. On Android this is `Dispatchers.IO`, which is fine, but the call completes in the `scope.launch {}` block before `requestEditBlock` fires — meaning keyboard focus does not move to the new block until all those DB operations complete. On a slow SQLite path this is the 1–2 second freeze users see.

### SAF vs Direct File Access

Published data from the AOSP blog and independent benchmarks:
- **Directory listing**: SAF is ~25–50x slower than `java.io.File` on Android 10–12.
- **Single file read**: SAF (openInputStream) adds ~5–20 ms per call due to Binder IPC on commodity hardware.
- **Single file write**: SAF (openOutputStream "wt") adds ~10–100 ms per call; worst case 200+ ms on FUSE-backed external storage.
- Android 11 FUSE tuning closed part of the gap for sequential access but per-call IPC overhead remains.
- **Critical**: every SAF call (`fileExists`, `openOutputStream`, `createDocument`, `queryDocumentMimeType`) is a separate Binder IPC. The `savePageInternal` saga issues at least 3–4 SAF calls per save: `fileExists` → `readFile` (safety check) → `writeFile`. Under the 500 ms debounce this is acceptable, but if anything causes synchronous SAF calls in the insert hot path it will cause the observed lag.

### The Shadow Cache Write Opportunity

The existing `ShadowFileCache` writes to `context.filesDir` (direct `java.io.File` I/O). If the SAF write were made async and non-blocking, the shadow could immediately reflect the new state for subsequent reads, and the SAF write could happen in the background. This is the standard "write to fast local store, flush to slow remote store" pattern.

---

## 3. Recommended Fix Patterns

### Fix A: Decouple structural DB writes from UI focus path (highest impact, lowest risk)

For `splitBlock`, `indentBlock`, `outdentBlock`, `mergeBlock`: move the DB write and subsequent `refreshBlocksForPage` call into a fire-and-forget coroutine. Apply the structural change to `_blocks` optimistically before the DB call, so `requestEditBlock` fires immediately. Use the DB result only to confirm/correct (not to unblock focus).

This is the same pattern already used in `addBlockToPage` — extend it to all structural ops.

### Fix B: Async SAF file write with shadow as interim source of truth (medium impact, medium risk)

Modify `GraphWriter.savePageInternal` (Android-specific via `expect/actual` on `FileSystem`) to:
1. Update the shadow cache synchronously (direct `java.io.File` write, ~1 ms).
2. Dispatch the SAF write (`contentResolver.openOutputStream`) to a background coroutine — fire-and-forget from the perspective of the save pipeline.
3. Mark the shadow as "pending SAF flush" so if the process is killed, the next startup's `syncFromSaf` (already implemented) detects the stale shadow and re-writes to SAF.

The data integrity invariant (NFR-2) is already partially satisfied by the shadow cache's `syncFromSaf` on startup. Making the SAF write async does not introduce new data loss risk as long as the shadow is written first.

### Fix C: Reduce extra SAF calls in `savePageInternal` (low impact, easy win)

The current saga reads the existing file before writing (safety check for large deletions). On Android this is an extra `fileExists()` + `readFile()` = 2 Binder IPC calls per save. The large-deletion guard could be tracked in-memory via `BlockStateManager`'s existing block count rather than re-reading from disk, eliminating these calls.

### Fix D: Use `PlatformDispatcher.DB` for actor scope (minor, correctness)

`DatabaseWriteActor`'s internal scope uses `PlatformDispatcher.Default`, not `PlatformDispatcher.DB`. The actor's lambda then switches to DB via `withContext(PlatformDispatcher.DB)` inside each repository call. This is correct but means there is a dispatcher-switch overhead on every write. On Android both are backed by `Dispatchers.IO` thread pool, so this is a no-op in practice — no change needed.

---

## 4. CI-Runnable Benchmark Strategy

The existing benchmark infrastructure (`GraphLoadTimingTest`, `RepositoryBenchmark`, `SyntheticGraphDbBuilder` in `jvmTest`) uses in-memory SQLite (via `IN_MEMORY` backend) and direct `java.io.File` access. To simulate SAF latency:

- Create a `LatencyShimFileSystem` that wraps `JvmFileSystem` and injects a configurable `Thread.sleep()` (or `delay()`) before each write operation.
- The shim should support P50=10ms, P99=200ms latency profiles (matching real-device measurements).
- Wire it into a new `BlockInsertBenchmark` that measures wall-clock from `addBlockToPage` call to `requestEditBlock` completion (DB write confirmed) and separately to "file write dispatched".
- Use `assertThat(p99LatencyMs).isLessThan(200)` to enforce the budget.

The `SyntheticGraphDbBuilder` already has helpers for setting up a realistic DB state — reuse it. The benchmark can run in `./gradlew jvmTest` without any Android device.

---

## Summary

- **Root cause hypothesis**: The 1–2 second lag is most likely a combination of (a) synchronous DB round-trips in structural block operations (`splitBlock`, `indentBlock`) before UI focus moves, and (b) SAF Binder IPC overhead in the file write path (10–200 ms per call, 3–4 calls per save). The DB write is the more likely culprit for instant-feedback operations; SAF is the culprit for saves that block the save pipeline.
- **Quickest win**: Make structural operations optimistic (Fix A) — parallels the pattern already used for `addBlockToPage`.
- **Largest SAF improvement**: Async SAF write with shadow as interim truth (Fix B) — the infrastructure for this (`ShadowFileCache`, `syncFromSaf`) already exists.
- **Performance budget enforcement**: Add a `LatencyShimFileSystem` benchmark to `jvmTest` that fails CI if P99 insert latency (DB committed + file dispatched) exceeds 200 ms under simulated 50 ms SAF latency.
