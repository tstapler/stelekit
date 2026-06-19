# Stack Research: SQLite Transaction Batching in WAL Mode — Android DB Performance

## Summary

**Recommendation**: The primary stack fix is not chunk size — the current `WRITE_CHUNK_SIZE = 50` in `SqlDelightBlockRepository` is correct for WAL mode. The root cause of the 300–2,000x warm-DB regression is `INSERT OR REPLACE` semantics: on conflict, SQLite does DELETE + INSERT, firing the `blocks_ad` FTS5 delete trigger + `blocks_ai` insert trigger + 7 B-tree index updates × 2 = 28 B-tree operations per block update. Fix: use targeted UPDATE for changed blocks and INSERT for new blocks, driven by the existing `DiffMerge.diff()` output.

---

## SQLite WAL Mode Transaction Batching

In WAL mode, each implicit (no explicit `transaction { }`) write pays a full `fsync` + WAL-page-lock/unlock cycle — 5–50ms per write on Android flash storage. Explicit transactions amortize this cost: N writes inside one transaction = 1 fsync instead of N. Documented speedup: 2–20x.

Source: [SQLite WAL documentation](https://sqlite.org/wal.html), [Android SQLite best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)

### Optimal batch size
WAL degrades for transactions >100MB. For SteleKit: 50 blocks × ~500 bytes ≈ 25KB per chunk — well below the 4MB WAL auto-checkpoint threshold. `WRITE_CHUNK_SIZE = 50` is correct.

Source: [Turso batches in SQLite](https://turso.tech/blog/batches-in-sqlite-838e0961), [SQLite WAL](https://sqlite.org/wal.html)

### WAL checkpoint after bulk
After a large batch, the WAL file grows until auto-checkpoint fires (~4MB). The codebase already calls `pragmaWalCheckpointTruncate` after bulk import — correct.

---

## The Real Stack Problem: INSERT OR REPLACE on Warm DB

`insertBlock` uses `INSERT OR REPLACE INTO blocks (...)`. On a warm DB with a UUID conflict:
1. SQLite deletes the existing row → fires `blocks_ad` FTS5 trigger (FTS delete write)
2. SQLite inserts the new row → fires `blocks_ai` FTS5 trigger (FTS insert write)  
3. All 7 B-tree indexes updated for DELETE + INSERT = 14 index writes per block

Total: ~16 write operations per block update vs. ~7 for a plain UPDATE.

**Fix**: In `dispatchFullBlockWrites`, the diff already separates `toInsert` from `toUpdate`. Route `toUpdate` through a targeted SQL UPDATE (which fires only `blocks_au AFTER UPDATE OF content` — a single FTS delete+insert, not a full row delete). Route `toInsert` through `insertBlock`.

---

## SQLDelight 2.3.2 Transaction API

```kotlin
// Inside withContext(PlatformDispatcher.DB):
queries.transaction {
    blocks.forEach { block -> queries.insertBlock(...) }
}

// For return value:
val result = queries.transactionWithResult { ... }
```

Coroutines extension requires explicit dispatcher:
```kotlin
queries.selectBlocksByPageUuidUnpaginated(uuid)
    .asFlow()
    .mapToList(PlatformDispatcher.DB)  // dispatcher required in 2.x
```

Sources: [SQLDelight Transactions](https://sqldelight.github.io/sqldelight/2.0.2/jvm_mysql/transactions/), [SQLDelight Coroutines](https://sqldelight.github.io/sqldelight/2.0.2/android_sqlite/coroutines/), [SQLDelight 2.0 upgrade](https://sqldelight.github.io/sqldelight/2.0.2/upgrading-2.0/)

---

## EXPLAIN QUERY PLAN — Existing Indexes Already Cover These Queries

From `SteleDatabase.sq` schema + `covering_indexes_page_blocks` migration (already applied):
- `idx_pages_name ON pages(name)` — covers `selectPageByName WHERE name = ?`
- `idx_blocks_page_position ON blocks(page_uuid, position)` — covers `selectBlocksByPageUuidUnpaginated WHERE page_uuid = ? ORDER BY position`
- `idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash)` — covering index for hash lookup

**The trace `lookupPage` 200x slowdown on found=true is NOT a missing index — it is actor queue wait time.** Run `EXPLAIN QUERY PLAN SELECT * FROM pages WHERE name = ? LIMIT 1` → expected: `SEARCH pages USING INDEX idx_pages_name`.

Source: [SQLite EXPLAIN QUERY PLAN](https://sqlite.org/eqp.html), [SQLite index tuning](https://medium.com/@JasonWyatt/squeezing-performance-from-sqlite-indexes-indexes-c4e175f3c346)

---

## Citations

- [SQLite WAL documentation](https://sqlite.org/wal.html)
- [Android SQLite best practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- [SQLite insertions performance — JasonWyatt](https://medium.com/@JasonWyatt/squeezing-performance-from-sqlite-insertions-971aff98eef2)
- [Turso: Batches in SQLite](https://turso.tech/blog/batches-in-sqlite-838e0961)
- [SQLite performance tuning — phiresky](https://gist.github.com/phiresky/978d8e204f77feaa0ab5cca08d2d5b27)
- [SQLite EXPLAIN QUERY PLAN](https://sqlite.org/eqp.html)
- [SQLDelight 2.x Transactions](https://sqldelight.github.io/sqldelight/2.0.2/jvm_mysql/transactions/)
- [SQLDelight Coroutines](https://sqldelight.github.io/sqldelight/2.0.2/android_sqlite/coroutines/)
