# Pitfalls Research: Risks for Android DB Performance Fixes

## Summary

**Top risk**: Changing `saveBlocks` to use `updateBlockContent` for warm-path blocks will silently fail to update non-content fields (position, parent_uuid, left_uuid, level) when blocks are structurally moved but content is unchanged. The diff must detect structural changes, not just content changes. Use `content_hash` equality AND structural field equality to determine INSERT vs UPDATE vs full-replace.

---

## Risk 1: Partial-Write Visibility During Transaction

**Risk**: Inside a chunked `transaction { }`, if block N fails mid-transaction, blocks 0..N-1 are visible to concurrent readers (on committed prior chunks) but block N and beyond are not.

**SQLite isolation guarantee**: In WAL mode, readers see a snapshot of the database at the point their transaction started. A reader that starts before chunk 2 commits will NOT see chunk 2's blocks. A reader that starts after sees all of chunk 2.

Source: [SQLite isolation documentation](https://sqlite.org/isolation.html)

**For SteleKit**: The chunked-transaction design is intentional (comment in `saveBlocks`: "Trade-off: chunking is NOT all-or-nothing"). The partial-write risk is already accepted — if a chunk fails, the source file is re-parsed on next startup. This is correct.

**New risk from Fix 4 (composite execute)**: If `savePage` succeeds but `saveBlocks` fails inside one `actor.execute { }`, the page row exists with no blocks. The existing `writeActor.saveBlocks` path handles this by having the `flushBatch` retry each request individually. A composite execute lambda must preserve this property — either by calling `blockRepository.saveBlocks()` (which has its own error handling) or by wrapping the lambda in explicit error recovery.

---

## Risk 2: INSERT OR REPLACE → UPDATE Migration

**Risk**: Switching warm-path blocks from `INSERT OR REPLACE` to `updateBlockContent` will fail to update structural fields: `parent_uuid`, `left_uuid`, `position`, `level`.

**Concrete scenario**: A page is edited, blocks reordered. The diff produces `toUpdate` = blocks with changed content AND changed position. If we only call `updateBlockContent(content, ...)` we silently drop the position change.

**Fix**: The `toUpdate` list from `DiffMerge.diff()` must be split further:
- Blocks where only `content` changed → `updateBlockContent`
- Blocks where structural fields (position, parent_uuid, level, left_uuid) changed → use a new `updateBlockFull` UPDATE statement, or keep using `insertBlock` (INSERT OR REPLACE) only for these

Alternatively: add a new `upsertBlockFull` UPDATE statement that updates all fields except `uuid` and `created_at`. This avoids the DELETE+INSERT path while still being complete.

```sql
-- New query to add to SteleDatabase.sq:
updateBlockFull:
UPDATE blocks SET
    page_uuid = ?, parent_uuid = ?, left_uuid = ?, content = ?,
    level = ?, position = ?, updated_at = ?, properties = ?,
    version = version + 1, content_hash = ?, block_type = ?
WHERE uuid = ?;
```

This fires only `blocks_au AFTER UPDATE OF content` (if content changed), saving the FTS5 delete-trigger overhead of INSERT OR REPLACE.

---

## Risk 3: Index Rebuild Cost on Migration (NEW INDEXES)

**Update based on architecture research**: The required indexes already exist. No new indexes need to be added. This risk is moot for the current requirements.

However, if indexes are added in a future migration:
- SQLite `CREATE INDEX IF NOT EXISTS` on a table with N rows = O(N log N) sort + O(N) B-tree inserts. For a 50,000-block table at ~500 bytes/block: ~25MB of data, O(N log N) sort. On Android, this takes 1–5 seconds.
- The migration runs on startup before any repository code. If it takes >2s, the splash screen hangs.
- Mitigation: run index creation in a background coroutine after the DB is first available, not blocking startup.

Source: [SQLite CREATE INDEX documentation](https://sqlite.org/lang_createindex.html), [SQLite migration best practices](https://www.schemalens.tech/blog/sqlite-schema-migration-best-practices.html)

---

## Risk 4: WAL Checkpoint Behavior After Batching

**Risk**: Batching blocks into larger transactions causes WAL file to grow. When the WAL crosses the auto-checkpoint threshold (~1,000 pages = ~4MB), the next COMMIT triggers a synchronous checkpoint, causing one unexpectedly slow commit.

**For SteleKit**: The existing `onBulkImportComplete` hook calls `walCheckpoint()` after bulk import, which runs `PRAGMA wal_checkpoint(TRUNCATE)`. This folds all WAL frames into the main database file.

**New risk from Fix 4**: If `actor.execute { savePage + saveBlocks }` commits a large page (200+ blocks), the WAL may grow past the checkpoint threshold mid-execution, causing the actor to spend extra time on checkpoint. The actor loop does not yield during a single `execute { }` lambda — so a checkpoint during `saveBlocks` will make the HIGH-priority editor appear frozen.

**Mitigation**: Keep composite execute bounded to one page's blocks (the current architecture already does this — one `parseAndSavePage` call = one `actor.execute`). The 50-block chunk limit inside `saveBlocks` keeps each transaction's WAL growth small.

Source: [SQLite WAL checkpoint behavior](https://sqlite.org/wal.html), [WAL checkpoint tuning](https://gist.github.com/phiresky/978d8e204f77feaa0ab5cca08d2d5b27)

---

## Risk 5: Routing Reads Off Actor — Write Enforcement Bypass

**Risk**: Calling `blockRepository.getBlocksForPage()` or `pageRepository.getPageByName()` directly (bypassing the actor) could bypass the `RestrictedDatabaseQueries` write enforcement.

**Assessment**: This risk does NOT apply because these are reads (`SELECT` queries), not writes. The `@DirectSqlWrite` annotation and `RestrictedDatabaseQueries` only restrict mutating operations (`INSERT`, `UPDATE`, `DELETE`, `UPSERT`). Read methods on `SqlDelightBlockRepository` and `SqlDelightPageRepository` are already `@OptIn(DirectRepositoryWrite::class)` at the class level but only the write methods are gated.

The existing code already calls `pageRepository.getPageByName().first()` and `blockRepository.getBlocksForPage().first()` directly from `parseAndSavePage` without the actor. This is correct and already the pattern.

---

## Risk 6: Re-entrancy / Deadlock in Composite Execute

**Risk**: If a composite `actor.execute { }` lambda calls `writeActor.savePage()` or any other `actor.*` method, the actor will deadlock — it cannot process a new request while processing the current one.

**Fix**: Composite execute lambdas must call repository methods directly (`pageRepository.savePage(page)`, `blockRepository.saveBlocks(blocks)`), NOT actor wrapper methods.

The `blockRepository.saveBlocks` method is annotated `@DirectRepositoryWrite` and called directly from within `flushBatch` today — this is the correct pattern to follow.

---

## Risk 7: FTS5 Trigger Behavior During Transaction

**Risk**: Inside a `transaction { }`, each `insertBlock` fires `blocks_ai` trigger immediately (SQLite triggers fire within the statement, inside the transaction). If the transaction rolls back, the FTS5 changes roll back too. But the FTS5 virtual table (`blocks_fts`) is not an external system — it participates in the same SQLite transaction.

**Assessment**: No special handling needed. FTS5 triggers are transactional in SQLite and roll back correctly on transaction abort.

Source: [SQLite FTS5 documentation](https://sqlite.org/fts5.html)

**Warning**: The `blocks_au AFTER UPDATE OF content` trigger fires for EVERY UPDATE of the `content` column, including bulk updates in `updateBlockContentsForRename`. This is already limited in the codebase via the fix_blocks_au_trigger_content_only migration. No new risk here.

---

## Risk 8: Cache Invalidation After Composite Write

**Risk**: After the composite `actor.execute { savePage + saveBlocks }`, the page and block caches in `SqlDelightPageRepository` and `SqlDelightBlockRepository` may contain stale entries.

**Assessment**: `savePage` in `SqlDelightPageRepository` updates `pageByUuidCache` and `pageByNameCache` after a successful write (line 138–140). `saveBlocks` does NOT update the block cache (the cache is populated on read). Cache invalidation for blocks happens via `cacheEvictPage(pageUuid)` called from... need to verify in `GraphLoader`. If `evictHierarchyForPage` is not called after the composite write, the hierarchy cache may serve stale data.

**Mitigation**: After the composite execute completes, call `(blockRepository as? SqlDelightBlockRepository)?.evictHierarchyForPage(pageUuid.value)` to invalidate the hierarchy cache for the affected page.

---

## Risk 9: MigrationRunnerSchemaSyncTest Enforcement

If any `CREATE TABLE IF NOT EXISTS` is added to `SteleDatabase.sq`, `MigrationRunnerSchemaSyncTest` will fail if it's not in `MigrationRunner.all`. The test reads the `.sq` file and asserts coverage.

**Assessment**: The current fixes (transaction batching, read separation, composite execute) do NOT require new tables. If a new `updateBlockFull` query is added to `SteleDatabase.sq`, it is a new SQL query but not a new table — no migration or sync test update needed. Only `CREATE TABLE IF NOT EXISTS` statements trigger the test.

---

## Priority Risk Summary

| Risk | Severity | Likely to Occur | Mitigation |
|---|---|---|---|
| Structural fields dropped in warm-path UPDATE | HIGH | YES — blocks can move position | Use `updateBlockFull` UPDATE statement for all fields except uuid/created_at |
| Composite execute partial failure | MEDIUM | LOW | Call `blockRepository.saveBlocks()` directly which has existing retry logic |
| Re-entrancy deadlock in execute lambda | HIGH | YES if careless | Never call `writeActor.*` from inside `execute { }` |
| Cache stale after composite write | MEDIUM | YES | Call `evictHierarchyForPage` after composite write |
| WAL checkpoint stall during large page | LOW | LOW | Existing chunk size (50) and `walCheckpoint()` after bulk import already handle this |

---

## Citations

- [SQLite isolation documentation](https://sqlite.org/isolation.html)
- [SQLite WAL](https://sqlite.org/wal.html)
- [SQLite CREATE INDEX](https://sqlite.org/lang_createindex.html)
- [SQLite FTS5](https://sqlite.org/fts5.html)
- [SQLite migration best practices](https://www.schemalens.tech/blog/sqlite-schema-migration-best-practices.html)
- [WAL checkpoint tuning — phiresky](https://gist.github.com/phiresky/978d8e204f77feaa0ab5cca08d2d5b27)
- [SQLite concurrent writes and SQLITE_BUSY](https://tenthousandmeters.com/blog/sqlite-concurrent-writes-and-database-is-locked-errors/)
