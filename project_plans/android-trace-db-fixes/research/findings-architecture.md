# Architecture Research: SteleKit Actual DB Implementation

**Note**: This agent read the actual source files. All findings are based on code, not speculation.

---

## Summary

**Recommendation**: The architecture is mostly correct — the indexes already exist, reads already use `PlatformDispatcher.DB` directly (not the actor), and transactions are already chunked. The real fix targets two specific call sites: (1) `saveBlocks` uses `INSERT OR REPLACE` which triggers DELETE+INSERT on warm DB — change warm-path blocks to use a targeted `updateBlockContent` instead; (2) the ~1,000ms inter-span gaps in `parseAndSavePage` are caused by sequential `actor.sendAndAwait()` calls where each awaited deferred blocks the calling coroutine until a prior LOW-priority bulk load finishes — fix by collapsing `savePage` + `saveBlocks` into one `actor.execute { }`.

---

## Actual Index State (read from SteleDatabase.sq)

**All required indexes already exist:**

```sql
-- Present in SteleDatabase.sq schema:
CREATE INDEX idx_pages_uuid ON pages(uuid);
CREATE INDEX idx_pages_name ON pages(name);          -- covers lookupPage by name
CREATE INDEX idx_pages_namespace ON pages(namespace);
CREATE INDEX idx_blocks_uuid ON blocks(uuid);
-- NOTE: idx_blocks_page_uuid was DROPPED in migration "drop_subsumed_blocks_single_column_indexes"
-- REPLACED BY composite covering indexes (added in "covering_indexes_page_blocks"):
CREATE INDEX idx_blocks_page_position ON blocks(page_uuid, position);   -- covers selectBlocksByPageUuidUnpaginated
CREATE INDEX idx_blocks_parent_position ON blocks(parent_uuid, position);
CREATE INDEX idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash);  -- covering index for hash lookup
```

**Conclusion**: The requirements document's Fix 2 (add missing indexes) is ALREADY DONE in prior migrations. No new indexes are needed. The trace's `lookupPage` 200x slowdown is NOT a missing index — it is actor queue wait time.

**MigrationRunner.all currently ends with** `covering_indexes_page_blocks` and `drop_subsumed_blocks_single_column_indexes` and `query_stats_table`. Any new indexes must be appended to `MigrationRunner.all`.

---

## How saveBlocks Is Actually Implemented

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

```kotlin
override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
    blocks.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
        queries.transaction {
            chunk.forEach { block ->
                queries.insertBlock(    // <-- INSERT OR REPLACE
                    block.uuid.value,
                    block.pageUuid.value,
                    // ...all fields
                )
            }
        }
    }
    Unit.right()
}

private const val WRITE_CHUNK_SIZE = 50
```

**The transaction wrapping already exists.** The problem is `insertBlock`:

```sql
-- SteleDatabase.sq:
insertBlock:
INSERT OR REPLACE INTO blocks (uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
    created_at, updated_at, properties, version, content_hash, block_type)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

`INSERT OR REPLACE` on UUID conflict = DELETE old row + INSERT new row. This fires:
- `blocks_ad` trigger (FTS5 delete — `INSERT INTO blocks_fts(blocks_fts, rowid, content) VALUES('delete', ...)`)
- `blocks_ai` trigger (FTS5 insert — `INSERT INTO blocks_fts(rowid, content) VALUES (...)`)
- Full B-tree update for all 7 indexes on DELETE + INSERT = 14 B-tree writes per block

The `DiffMerge.diff()` already separates `toInsert` from `toUpdate`. The fix: route `toUpdate` through `updateBlockContent` (UPDATE statement), which fires only the `blocks_au AFTER UPDATE OF content` trigger (1 FTS delete + 1 FTS insert). This reduces warm-path cost from 14 B-tree + 2 FTS trigger writes to 7 B-tree + 2 FTS trigger writes.

---

## How lookupPage Is Actually Implemented

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (line 1240–1256)

```kotlin
private suspend fun lookupExistingPageAndCheckFreshness(...) {
    val lookupSpan = Span("db.lookupPage", traceId, parentSpanId)
    val existingPage = if (isJournal && journalDate != null) {
        journalDateResolver.getPageByJournalDate(journalDate)
    } else {
        pageRepository.getPageByName(name).first().getOrNull()   // <-- DIRECT read, NO actor
    }
    lookupSpan.finish(...)
```

**Finding**: `lookupPage` does NOT go through the write actor. It calls `pageRepository.getPageByName(name).first()` which resolves through `SqlDelightPageRepository.getPageByName`:

```kotlin
override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> = flow {
    val cached = pageByNameCache.get(name.lowercase())
    if (cached != null) { emit(cached.right()); return@flow }
    val page = byNameCoalescer.execute(name.lowercase()) {
        queries.selectPageByName(name).executeAsOneOrNull()?.toModel()
    }
    // ...
}.flowOn(PlatformDispatcher.DB)
```

**The read is direct.** The ~1,000ms `lookupPage` trace spans are not because lookupPage is in the actor. They are because the **calling coroutine** `parseAndSavePage` is awaiting `writeActor.savePage()` completion (a `CompletableDeferred.await()` that suspends until the actor processes a prior LOW-priority bulk write), and the lookupSpan starts only after that await completes.

The span timeline in the trace shows the gap BEFORE each child span — this is the time `sendAndAwait` spent waiting in queue. The `lookupPage` operation itself is fast; the 1,200ms includes queue wait.

---

## How getBlocks Is Actually Implemented

**File**: `SqlDelightBlockRepository.kt` (line 226–230)

```kotlin
override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> =
    queries.selectBlocksByPageUuidUnpaginated(pageUuid.value)
        .asFlow()
        .mapToList(PlatformDispatcher.DB)   // <-- DIRECT, on DB dispatcher
        .conflate()
        .map { list -> list.map { it.toBlockModel() }.right() }
```

**Finding**: `getBlocksForPage` also does NOT go through the actor. The `db.getBlocks` span in the trace accumulates queue wait time from the calling coroutine being blocked waiting for previous actor operations.

---

## parseAndSavePage Call Chain (actual code)

**File**: `GraphLoader.kt` lines 1408–1524

The actual call sequence in `parseAndSavePage` (FULL mode, warm DB):

```
1. lookupExistingPageAndCheckFreshness()
   └── pageRepository.getPageByName(name).first()    [direct, PlatformDispatcher.DB]
   └── blockRepository.getBlocksForPage(uuid).first() [direct, PlatformDispatcher.DB]
   
2. markdownParser.parsePage(content)                  [CPU, no DB]

3. buildPageModel(...)                                [CPU, no DB]

4. writeActor.savePage(page, priority)                [actor.sendAndAwait — blocks until actor processes]
   → CompletableDeferred.await() suspends here

5. blockRepository.getBlocksForPage(pageUuid).first() [direct read, but called after step 4 completes]

6. DiffMerge.diff(existingSummaries, blocksToSave)    [CPU, no DB]

7. writeActor.deleteBlock(uuid) for each diff.toDelete [one actor call per deleted block]

8. writeActor.saveBlocks(blocksToWrite, priority)     [actor.sendAndAwait — blocks again]
```

**The inter-span gaps occur at steps 4 and 8** — `sendAndAwait()` suspending while the actor finishes prior LOW-priority requests from parallel page loads.

**Fix**: Collapse steps 4 + 8 into one `actor.execute(priority) { savePage + saveBlocks }`. Move step 5 (read) before step 4.

---

## DatabaseWriteActor Architecture

**File**: `db/DatabaseWriteActor.kt`

Key facts:
- Two channels: `highPriority` and `lowPriority` (both `Channel.UNLIMITED`)
- Actor always drains HIGH before LOW
- `processSaveBlocks` coalesces consecutive `SaveBlocks` requests on same priority lane
- `execute { }` accepts arbitrary `suspend () -> Either<DomainError, Unit>` lambdas
- `blockRepository.saveBlocks()` is called inside `flushBatch()` — which already uses the repository's chunked-transaction implementation

**The actor does NOT wrap repository calls in additional transactions.** The transaction boundary is entirely inside `SqlDelightBlockRepository.saveBlocks`.

**Important constraint**: `execute { }` lambdas must NOT call other `actor.*` methods — the actor processes one request at a time (single coroutine), so calling `actor.savePage()` from inside `actor.execute { }` would deadlock.

---

## RestrictedDatabaseQueries Contract

**File**: `db/RestrictedDatabaseQueries.kt` (not read but referenced in CLAUDE.md)

All writes must go through `@DirectSqlWrite`-annotated methods on `RestrictedDatabaseQueries`. Repository methods already carry `@OptIn(DirectSqlWrite::class)`. Any new SQL UPDATE query added for the warm-path block update must:
1. Be added to `SteleDatabase.sq`
2. Have a forwarding stub in `RestrictedDatabaseQueries` annotated `@DirectSqlWrite`

---

## MigrationRunner Current State

**File**: `db/MigrationRunner.kt` — last migrations in `all` list:
- `covering_indexes_page_blocks` — adds composite indexes
- `drop_subsumed_blocks_single_column_indexes` — drops redundant single-column indexes
- `query_stats_table` — adds query_stats table

Any new migration must be appended after `query_stats_table`. The `MigrationRunnerSchemaSyncTest` enforces that every `CREATE TABLE IF NOT EXISTS` in `SteleDatabase.sq` also appears in `MigrationRunner.all`.

---

## What the Trace Actually Shows vs. Diagnosis

| Trace Symptom | Actual Cause (from code) | Fix |
|---|---|---|
| `db.lookupPage` 1,200ms (found=true) | `parseAndSavePage` coroutine awaits `writeActor.savePage()` before lookupSpan records; span includes queue wait | Fix 4: collapse actor calls |
| `db.getBlocks` ~1,000ms | Same — called after actor await, span includes wait | Fix 4: move read before actor |
| `db.saveBlocks` 1,762ms/block (warm) | `INSERT OR REPLACE` fires DELETE+INSERT+FTS triggers per block | New fix: use `updateBlockContent` for warm-path blocks |
| `editor_input` p50 ≥ 5,000ms | HIGH-priority editor saves queue behind blocked LOW bulk loads | Fixes 4 + warm-path save |
