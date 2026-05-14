# BUG-008: Add block at bottom of journal is slow on Android (several seconds)

**Status**: Fixed  
**Platform**: Android  
**Reported**: 2026-05-11

## Symptom

Clicking below the last block in the journal to create a new bullet takes several
seconds on Android before the cursor appears in the new block.

## Root Cause

Three compounding issues:

### 1. Unnecessary DB read in `addBlockToPage` (BlockStateManager.kt:800)

`addBlockToPage` called `blockRepository.getBlocksForPage(pageUuid).first()` to
find the last block's position even though `blocksForPage(pageUuid)` already
returns the in-memory state. On Android this adds a `Dispatchers.IO` context
switch + full SQL query before every new-block insertion.

### 2. SQLite write-lock contention during Phase-3 background loading

`addBlockToPage` called `blockRepository.saveBlock()` directly, bypassing the
`DatabaseWriteActor`. During Phase-3 background indexing the actor coalesces
hundreds of blocks into a single large transaction (e.g. 2000+ blocks for a
medium graph). While that transaction holds the SQLite write lock, any direct
`saveBlock()` call must wait — up to the full `busy_timeout` of 10 seconds.

`saveBlocks()` had no batch-size limit, so a coalesced batch of 2000 blocks
held the write lock for the entire duration.

### 3. FTS5 trigger on ALL column updates (SteleDatabase.sq)

The `blocks_au` trigger fired on `AFTER UPDATE ON blocks`, meaning every
`updateBlockPositionOnly` and `updateBlockHierarchy` call during `splitBlock`'s
sibling shift emitted two FTS5 writes (delete + insert). For a page with N blocks,
pressing Enter triggered O(N) FTS5 write pairs in addition to the split itself.

## Fix

**`BlockStateManager.addBlockToPage`** — replace the DB read with `blocksForPage()`
(in-memory), add an optimistic `_blocks` update + `pendingNewBlockUuids` tracking
so the block appears in the UI immediately without waiting for the DB write.

**`SqlDelightBlockRepository.saveBlocks`** — chunk large lists into 50-block
transactions. Each chunk holds the write lock for ~25–75ms on mid-range Android
hardware, so concurrent `addBlockToPage` / `splitBlock` writes wait at most one
chunk before acquiring the lock.

**`MigrationRunner` + `SteleDatabase.sq`** — limit `blocks_au` to
`AFTER UPDATE OF content ON blocks`. Structural-only mutations (position shifts,
hierarchy updates, left_uuid repairs) no longer trigger FTS5.

**`AndroidGraphBenchmark`** — add `editingOperationsLatency` test asserting
p95 < 500ms for `addBlockToPage` and `splitBlock` in fully-loaded state.

## Files Changed

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- `kmp/src/androidInstrumentedTest/kotlin/dev/stapler/stelekit/benchmark/AndroidGraphBenchmark.kt`
