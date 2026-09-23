# Stack Research: Block Operations Performance — Phase 2

**Codebase facts verified before writing this file:**
- SQLDelight 2.3.2 (`app.cash.sqldelight:runtime:2.3.2`), `generateAsync = true`
- Android driver: `app.cash.sqldelight:android-driver:2.3.2` + `com.github.requery:sqlite-android:3.49.0`
- JVM driver: `app.cash.sqldelight:sqlite-driver:2.3.2` (sqlite-jdbc 3.51.3+)
- Single-connection architecture today; `DatabaseWriteActor` serializes all writes
- `blocks` table has `id INTEGER PRIMARY KEY AUTOINCREMENT` (needed for FTS5 content_rowid)
- `wikilink_references (block_uuid, page_name)` composite PK, no autoincrement rowid
- WAL mode + `PRAGMA mmap_size=67108864` already set in `ANDROID_PRAGMAS`

---

## R5: WITH RECURSIVE CTEs for `getBlockHierarchy`

### Syntax for parent_uuid → children traversal

SQLite has supported `WITH RECURSIVE` since version 3.8.3 (2014). Requery bundles SQLite 3.49.0 on Android; the JVM driver uses sqlite-jdbc 3.51.3+. Both are far ahead of the 3.8.3 minimum. The recursive CTE pattern for a block tree rooted at a given UUID is:

```sql
WITH RECURSIVE subtree(
    uuid, page_uuid, parent_uuid, left_uuid, content,
    level, position, created_at, updated_at, properties,
    version, content_hash, block_type, depth
) AS (
    -- Anchor: the root block itself (depth 0)
    SELECT uuid, page_uuid, parent_uuid, left_uuid, content,
           level, position, created_at, updated_at, properties,
           version, content_hash, block_type, 0
    FROM blocks WHERE uuid = :rootUuid

    UNION ALL

    -- Recursive: children of each previously-found row
    SELECT b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, s.depth + 1
    FROM blocks b
    JOIN subtree s ON b.parent_uuid = s.uuid
)
SELECT * FROM subtree ORDER BY depth, position;
```

The existing index `idx_blocks_parent_position ON blocks(parent_uuid, position)` directly serves the recursive join predicate (`b.parent_uuid = s.uuid`). SQLite's query planner uses this index for each recursive step, so each level costs `O(k log n)` where `k` is the number of children at that level and `n` is the total block count.

### Depth limits

SQLite enforces a default maximum recursion depth of **1000** (`PRAGMA max_page_count` does not apply; the recursion limit is `SQLITE_MAX_EXPR_DEPTH`, configurable via `PRAGMA recursive_triggers` or compile-time flag but in practice the practical limit for WITH RECURSIVE is ~1000 nesting levels). For a note-taking app, 10–20 nesting levels is the realistic maximum. No depth-limit concern exists here.

### Performance vs. round-trip BFS

The current BFS in Kotlin makes one `selectBlocksByParentUuids` call per depth level (N round-trips for N levels). Each round-trip pays: coroutine resume overhead, JNI boundary crossing (Android), cursor allocation, and SQLite query compile/exec overhead. On Android with the Requery driver, each such trip costs ~1–3ms minimum even for trivially small result sets.

A single CTE query eliminates all of that:
- **100 nodes, 10 levels**: single CTE = ~0.5–2ms. BFS at 10 round-trips × 1–3ms/trip = 10–30ms total.
- **500 nodes, 10 levels**: single CTE = ~2–8ms. BFS = 10–30ms (similar, since each level is larger but trips stay the same).
- The CTE overhead scales with node count, but the BFS overhead scales with depth × per-trip fixed cost. For all realistic graphs, the CTE wins because fixed per-trip overhead dominates.

SQLite processes the recursive CTE using an internal queue (breadth-first by default with `UNION ALL`) and scans the index once per level. The `ORDER BY depth, position` in the outer query adds a sort pass over the result set (O(n log n)), but this is cheaper than the round-trip overhead.

**SQLDelight integration**: A CTE query can be declared in `SteleDatabase.sq` as a named query returning `blocks`-shaped rows plus a `depth` column. Because `generateAsync = true`, the generated function returns a `Flow` via `asFlow().mapToList()`. For `getBlockHierarchy` the call site wants a one-shot `suspend fun` not a `Flow`, so the `.executeAsList()` path is appropriate — declare the query and call `executeAsList()` inside a `withContext(PlatformDispatcher.DB)` block. The CTE query with a custom result type (adding `depth`) needs to be declared with a custom mapper in SQLDelight; alternatively, `depth` can be dropped from the result and computed from the `level` column that's already in `blocks`.

---

## R2: WITHOUT ROWID for `wikilink_references`

### What WITHOUT ROWID does

A regular SQLite table stores rows in a B-tree keyed by the hidden `rowid` integer, with a separate B-tree (the PK index) pointing to rowids. `WITHOUT ROWID` merges these two B-trees: rows are stored directly in PK order, eliminating the rowid B-tree entirely. For composite PK tables with short, frequently-read keys this yields:
- **Smaller file footprint**: no duplicate storage of PK columns across two B-trees.
- **Faster PK lookups**: one B-tree traversal instead of two (PK index → rowid → data B-tree).
- **Faster PK-prefix range scans**: e.g. `WHERE block_uuid = ?` is a prefix scan on the clustered PK, not a covering-index scan that then fetches rows from a second B-tree.
- **Faster inserts for PK-keyed access patterns**: no rowid B-tree maintenance.

For `wikilink_references (block_uuid TEXT, page_name TEXT)`, where all queries are either PK lookups (`WHERE block_uuid = ?`) or the secondary index `idx_wikilink_refs_page_name` (`WHERE page_name = ?`), WITHOUT ROWID is a strong fit.

### Restrictions that apply to this codebase

1. **All columns must be part of the PRIMARY KEY or explicitly included**: `wikilink_references` has exactly two columns, both in the PK — no issue.

2. **FOREIGN KEY as source (FK referencing other tables)**: `wikilink_references` declares `FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE`. SQLite **supports** FK declarations on WITHOUT ROWID tables as the FK _source_ (i.e., the table declaring the FK). The `ON DELETE CASCADE` enforcement works correctly. What is NOT supported is using a WITHOUT ROWID table as the FK _target_ (i.e., the parent table being referenced), but no other table references `wikilink_references` — it is always the child.

3. **Non-PK indexes**: `CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name)` is valid on WITHOUT ROWID tables. Secondary indexes on WITHOUT ROWID tables store the full PK (block_uuid, page_name) as the row locator instead of a rowid, making them slightly larger, but for this table (two short TEXT columns) the size difference is negligible.

4. **Cannot be altered in place**: `ALTER TABLE … SET WITHOUT ROWID` does not exist in SQLite. Migration requires recreating the table. The pattern for MigrationRunner:
   ```sql
   -- Step 1: rename existing table
   ALTER TABLE wikilink_references RENAME TO wikilink_references_old;
   -- Step 2: recreate WITHOUT ROWID
   CREATE TABLE wikilink_references (
       block_uuid TEXT NOT NULL,
       page_name  TEXT NOT NULL COLLATE NOCASE,
       PRIMARY KEY (block_uuid, page_name),
       FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
   ) WITHOUT ROWID;
   -- Step 3: copy data
   INSERT INTO wikilink_references SELECT block_uuid, page_name FROM wikilink_references_old;
   -- Step 4: recreate index
   CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE);
   -- Step 5: drop old table
   DROP TABLE wikilink_references_old;
   ```
   This migration must be wrapped in a transaction in MigrationRunner for atomicity. `MigrationRunnerSchemaSyncTest` will require updating since the `CREATE TABLE` in `SteleDatabase.sq` must also gain `WITHOUT ROWID` to match.

5. **FTS5 content table restriction**: WITHOUT ROWID tables cannot serve as a content source for FTS5 virtual tables (FTS5 requires rowid). `wikilink_references` is not referenced by any FTS virtual table — no issue.

6. **AUTOINCREMENT**: Not applicable (no autoincrement on this table, nor is it compatible with WITHOUT ROWID).

### Expected performance benefit

For `WHERE block_uuid = ?` (the most common lookup, executed on every block save/delete):
- Currently: PK index lookup (one B-tree) + rowid fetch from data B-tree (second B-tree). Two B-tree traversals.
- After WITHOUT ROWID: single B-tree traversal directly to the data rows.
- Reduction is roughly 30–50% fewer B-tree page reads for this access pattern. On an 8000-page graph with dense wikilinks, `wikilink_references` can have 50 000+ rows; the B-tree is 3–4 levels deep, so saving one traversal per lookup is meaningful.

---

## R1: Fractional / Sparse Position Indexing

### Current problem

`position INTEGER` with sequential integers requires `shiftRootBlockPositionsFrom` / `shiftChildBlockPositionsFrom` on every insertion in the middle of a sibling list. Even as a single batch UPDATE, it touches and dirtifies O(n) rows per insertion — on a 500-block page, 500 row writes per Enter keypress, each triggering WAL frame writes.

### Option A: Float midpoint

Store `position REAL`. New insert between siblings with positions `L` and `R` uses `(L + R) / 2.0`.

**Precision limits**: SQLite REAL is IEEE 754 double (64-bit). A double has 52 bits of mantissa (15–17 significant decimal digits). Starting from integers `L=0, R=65536`:
- After 52 bisections, the gap between adjacent values is `65536 / 2^52 ≈ 1.45e-11` — smaller than the double epsilon for values of that magnitude.
- **Practical limit**: starting from `L=0, R=1` with pure bisection, you exhaust distinguishable midpoints after ~52 splits at a single gap. With `L=0, R=65536`, you get ~52 + 16 = ~68 splits before collision.
- For a 500-block page where the worst case is 500 consecutive insertions at the same point, you need ≥500 distinguishable midpoints. With gap 65536, you get ~68 before needing rebalance. For 500 consecutive splits at the same gap, you need rebalance well before exhaustion.
- **Rebalance cost**: when gap exhausted (adjacent positions are equal after rounding), renumber all siblings — O(n) writes. Rare but not negligible for power users who insert hundreds of consecutive blocks at the same point.
- **`ORDER BY position`**: numeric ordering, correct.
- **Migration**: existing positions (dense integers 0, 1, 2, …) are valid REAL values; migrate by multiplying by a large constant (e.g., multiply all positions by 65536). Schema change to REAL column requires ALTER TABLE or table recreation.

**Verdict**: Simple to implement but the precision cliff is a real correctness risk. Not recommended as the primary scheme unless combined with a gap multiplier starting at ≥65536.

### Option B: Large-gap integers

Store `position INTEGER`. Initialize with gap 65536 between siblings (positions 65536, 131072, 196608, …). New insert between positions `L` and `R` uses `(L + R) / 2` (integer division, rounds down).

**Gap analysis**: with initial gap 65536, pure binary bisection at the same point gives `log2(65536) = 16` splits before the integer midpoint equals one of its neighbors. For a 500-block page with insertions distributed across different positions, collisions are extremely unlikely. For the degenerate case (always insert at the same point): needs rebalance after 16 insertions at that gap.

**Rebalance cost**: same as Float — O(n) writes to renumber siblings. Triggered earlier than Float (16 splits vs. ~68), but more predictable.

**`ORDER BY position`**: integer ordering, correct and fast.

**Migration**: multiply all existing positions by 65536. Fits in INTEGER (SQLite INTEGER is up to 8 bytes/64-bit signed, max ~9.2×10^18; 500 × 65536 = 32,768,000 — far from overflow).

**Verdict**: Better than float (no precision cliff, predictable behavior, no schema type change needed if existing column is already INTEGER). But rebalance is triggered more aggressively than string fractional indices.

### Option C: Lexicographic string fractional index (Figma/Logseq approach)

Store `position TEXT`. Use a base-26 or base-36 string where lexicographic ordering (`ORDER BY position`) gives correct sort order, and a midpoint function inserts a string between any two existing strings without rebalancing.

**Key properties**:
- Strings like `"a"`, `"m"`, `"z"` are ordered correctly by SQLite's default text comparison.
- Midpoint between `"a"` and `"b"` is `"an"` (conceptually: `a` + half of alphabet → `am` or `an`). Between `"a"` and `"an"` is `"ag"`. Strings grow by one character per bisection.
- **Rebalance never needed**: you can always find a lexicographically intermediate string. The strings grow longer with deep nesting (50 insertions at the same point produces strings of length ~50/log2(26) ≈ ~11 characters), but never collide.
- Logseq uses this approach with a specific base-26 encoding (`"aaaa"` … `"zzzz"`). Figma's `fractionalIndexing` npm package uses base-26 as well.

**SQLite ordering**: `ORDER BY position` where position is TEXT uses SQLite's default byte-order comparison (lexicographic over UTF-8 bytes). For pure ASCII lowercase strings this is identical to lexicographic order. The implementation must guarantee no uppercase characters or characters outside the chosen alphabet leak into position values, or use `ORDER BY position COLLATE NOCASE` (which would still be lexicographic for ASCII).

**Performance impact on ORDER BY**: TEXT comparison is slightly slower than INTEGER comparison per row, but in practice for `ORDER BY` over a bounded sibling list (≤500 rows), the difference is imperceptible.

**Migration cost**: existing integer positions can be converted to their equivalent string representation: `position 0` → `"a"`, `position 1` → `"b"`, etc. (mapping integers to base-26 strings). Or more practically: convert positions `0, 1, 2, …, n` to strings `"n0001"`, `"n0002"`, … using a fixed-width numeric string, which gives correct lexicographic ordering for the initial set without needing base-26 arithmetic.

**Schema change**: column type changes from INTEGER to TEXT, requiring table recreation (cannot use `ALTER TABLE … ALTER COLUMN` in SQLite). This is a significant migration — the entire `blocks` table must be recreated since `blocks` also has FTS5 triggers referencing it. The migration must drop and recreate the FTS triggers as well.

**Complexity cost**: requires a pure Kotlin/KMP implementation of the midpoint algorithm (no platform library available). The algorithm itself is ~50 lines; well-understood and tested in Logseq's codebase (Clojure, easily ported).

### Recommendation for this codebase

**Large-gap integers (Option B)** with initial gap 65536 is the pragmatic choice for Phase 2:

1. No schema type change required — `position INTEGER` stays INTEGER.
2. Migration is a single `UPDATE blocks SET position = position * 65536` (can be done in MigrationRunner as one statement, no table recreation).
3. `ORDER BY position` is already used in all block queries — no query changes.
4. `shiftRootBlockPositionsFrom` / `shiftChildBlockPositionsFrom` are still in the schema for the rare rebalance case; they become near-zero-frequency instead of O(1)-per-edit.
5. No new Kotlin algorithm to implement or test — midpoint is `(left.position + right.position) / 2`.
6. The rebalance threshold (16 bisections at the same gap) is adequate for realistic editing patterns. A user inserting 17+ consecutive blocks at the exact same position without navigating away is an extreme edge case.

**String fractional indices** (Option C) would be the long-term best choice for correctness (never rebalance), but the schema migration cost — recreating the `blocks` table with its FTS5 triggers, indexes, and foreign keys — is high-risk and expensive to validate. Defer to a future phase.

---

## R4: Batch Wikilink Inserts — SQLDelight Multi-Row VALUES

### Does SQLDelight 2.3.2 support multi-row VALUES in generated code?

No. SQLDelight's code generator produces one bind/execute call per named query. The `insertWikilinkReference` query:
```sql
insertWikilinkReference:
INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name) VALUES (?, ?);
```
generates a function accepting exactly two parameters. There is no SQLDelight DSL or annotation for generating variadic `VALUES (?, ?), (?, ?), …` queries.

### Options for multi-row batch insert

**Option 1: Raw `SqlDriver.execute()` with dynamic SQL** (recommended)

Construct the SQL string at call time and execute it via the underlying `SqlDriver`:
```kotlin
// In RestrictedDatabaseQueries or via DatabaseWriteActor:
@OptIn(DirectSqlWrite::class)
fun insertWikilinkReferencesBatch(blockUuid: String, pageNames: List<String>) {
    if (pageNames.isEmpty()) return
    val placeholders = pageNames.joinToString(", ") { "(?, ?)" }
    val sql = "INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name) VALUES $placeholders"
    val params = pageNames.flatMap { listOf(blockUuid, it) }
    driver.execute(null, sql, params.size) { index ->
        bindString(index, params[index])
    }
}
```
This produces exactly 1 SQL statement for N page names. `driver.execute()` with `identifier = null` disables statement caching (no risk of reusing a stale cached statement with a different parameter count).

**Option 2: SQLDelight `transaction {}` with N individual inserts**

This does not reduce statement count but batches the N inserts into one transaction, reducing commit overhead. Already done for other insert paths; does NOT satisfy R4's requirement of 1 SQL statement per `saveBlock`.

**Option 3: Prepared statement with max-N parameters**

Pre-construct a statement for the maximum realistic page-name count (e.g., 20 `VALUES` pairs) and pass nulls for unused slots with a WHERE filter. Awkward and no real benefit over Option 1.

**Constraint from R4**: must use `INSERT OR IGNORE` (not `INSERT OR REPLACE`) to avoid triggering the FTS delete+insert trigger pair on existing rows. The dynamic SQL approach in Option 1 correctly uses `INSERT OR IGNORE`.

**SQLDriver capability in SQLDelight 2.3.2**: `SqlDriver.execute(identifier, sql, parameters, binders)` is part of the public API and available on all platform drivers including `AndroidSqliteDriver` (Requery-backed) and `JdbcSqliteDriver`. The `identifier = null` path compiles the SQL fresh each call (appropriate for dynamic SQL with varying parameter counts).

---

## R6: Two-Connection Read/Write Split (WAL Concurrency)

### SQLite WAL MVCC guarantees

In WAL mode, SQLite provides snapshot isolation: each reader sees a consistent snapshot of the database as of the moment its transaction began, and readers never block writers, writers never block readers. Specifically:

- A reader opens a **read transaction** implicitly on first `SELECT` in the connection. That read transaction takes a shared "SHARED" lock on the WAL index, which does not prevent writers.
- A writer obtains an exclusive lock on the write-ahead log (`RESERVED` → `PENDING` → `EXCLUSIVE` lock sequence) for the duration of its write transaction. This locks other writers but not readers.
- Two connections to the same WAL database file: one can `SELECT` while the other runs `INSERT/UPDATE` without any lock contention. This is the fundamental WAL promise.
- **Exception**: WAL checkpoint (folding WAL pages back into the main db file) requires an `EXCLUSIVE` lock that blocks readers briefly. With `wal_autocheckpoint=1000` (already set in `ANDROID_PRAGMAS`), checkpoints are passive — they run only when no active readers exist, so they don't block reads.

### Current architecture (single connection)

The Android driver uses a single `RequerySQLiteOpenHelperFactory` connection. Even with WAL mode, a long write transaction on this connection blocks all reads issued through the same connection object (SQLite serializes operations within a connection). The `DatabaseWriteActor` correctly serializes writes, but because reads and writes share the same connection, reads effectively queue behind writes.

### Two-connection architecture viability

**Principle**: create two `SqlDriver` instances pointing to the same WAL database file:
- `writeDriver`: used exclusively by `DatabaseWriteActor`
- `readDriver`: used by all `SELECT` flows and suspend queries

Because SQLite WAL allows concurrent readers and writers across connections, reads via `readDriver` are never blocked by writes via `writeDriver` (and vice versa), even though both connections are to the same file.

**SQLDelight 2.3.2 and `SqlDriver` wrapping**:

`SteleDatabase` is constructed from one `SqlDriver` — `SteleDatabase(driver)`. The `SqlDriver` interface has three methods: `execute`, `executeQuery`, and `addListener/removeListener`. A custom routing wrapper is viable:

```kotlin
class ReadWriteSplitDriver(
    private val readDriver: SqlDriver,
    private val writeDriver: SqlDriver,
) : SqlDriver {
    // Route read queries (SELECT) to readDriver, writes to writeDriver
    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        val isWrite = sql.trimStart().uppercase().let {
            it.startsWith("INSERT") || it.startsWith("UPDATE") ||
            it.startsWith("DELETE") || it.startsWith("CREATE") ||
            it.startsWith("DROP") || it.startsWith("ALTER") ||
            it.startsWith("PRAGMA") && !it.startsWith("PRAGMA SELECT")
        }
        return if (isWrite) writeDriver.execute(identifier, sql, parameters, binders)
        else readDriver.execute(identifier, sql, parameters, binders)
    }

    override fun executeQuery(...) = readDriver.executeQuery(...)
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) { ... }
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) { ... }
    override fun notifyListeners(vararg queryKeys: String) { ... }
    override fun close() { readDriver.close(); writeDriver.close() }
}
```

**Transaction handling complication**: SQLDelight's `transaction { }` DSL calls `execute` with `BEGIN TRANSACTION` and `COMMIT`/`ROLLBACK` SQL. The wrapper must route these to `writeDriver` and track that subsequent statements within a transaction should also go to `writeDriver`. This requires thread-local or coroutine-context state tracking, which adds complexity.

**`DatabaseWriteActor` already isolates writes**: because all writes flow through `DatabaseWriteActor.execute { ... }`, and the actor runs on a single coroutine, the routing problem is manageable: the actor can use `writeDriver` directly, while the SQLDelight-generated Flow-based queries use `readDriver`. This avoids the need for SQL-string-level routing.

**Requery-specific behavior**: `RequerySQLiteOpenHelperFactory` does not expose Android's native multi-connection pool (`enableWriteAheadLogging()`), but that's only the Android connection pool abstraction. Creating two separate `AndroidSqliteDriver` instances pointing to the same file is a lower-level approach that works with any SQLite driver — Requery is not an obstacle here.

**Schema sync issue**: SQLDelight's query listener/invalidation system (`notifyListeners` / `addListener`) uses driver-level notifications keyed by table name. With two separate drivers, writes via `writeDriver` will not automatically invalidate `Flow`s registered on `readDriver`. The notification must be bridged manually: after each `writeDriver` write, call `readDriver.notifyListeners(*affectedTables)`. The `DatabaseWriteActor` already knows which tables it writes to for each operation type; it can call this bridge method post-commit.

**Risk assessment**: The two-connection architecture is viable but non-trivial:
- Requires listener bridging between write and read drivers (~50 lines of plumbing).
- `transaction {}` routing must be correct to avoid partial writes going to the wrong driver.
- Needs regression tests under concurrent read+write load (new `QueryPlanAuditTest` cases).
- JVM sqlite-jdbc driver (`JdbcSqliteDriver`) also supports WAL and multiple connections to the same file, so this works on desktop too.

**Simpler alternative first**: before implementing the two-connection split, verify that the bottleneck is actually read-stall-on-write (not write-queue depth). After Phase 1.5 fixes + R1 (sparse positions) + R4 (batch inserts), the write duration per `saveBlock` may drop below 5ms, making the concurrent-read stall negligible. Implement R6 only if profiling still shows reads queuing behind writes after other Phase 2 changes.

---

## Summary Bullets

1. **CTE viability (R5)**: WITH RECURSIVE CTEs are fully viable in SQLite 3.49 (Requery) and 3.51.3 (JVM). The `idx_blocks_parent_position` index directly serves the recursive join. A single CTE query replaces O(depth) round-trips with 1 SQL call, expected to reduce `getBlockHierarchy` latency from 10–30ms to ~2–8ms for a 100-node tree. Declare in `SteleDatabase.sq` with a custom result mapper (adding `depth` column); call via `executeAsList()` inside `withContext(PlatformDispatcher.DB)`.

2. **WITHOUT ROWID verdict (R2)**: Fully supported for `wikilink_references`. The FK source constraint (`FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE`) works correctly on WITHOUT ROWID tables. The secondary index `idx_wikilink_refs_page_name` is valid. Migration requires table recreation (RENAME → CREATE WITHOUT ROWID → INSERT SELECT → DROP old). Expected ~30–50% fewer B-tree page reads per `WHERE block_uuid = ?` lookup. Safe to ship as a MigrationRunner step; `MigrationRunnerSchemaSyncTest` and `SteleDatabase.sq` CREATE TABLE declaration must both gain `WITHOUT ROWID`.

3. **Best fractional index scheme (R1)**: Large-gap integers (gap = 65536, integer midpoint) are the correct choice for Phase 2. No schema type change (position stays INTEGER), migration is a single `UPDATE blocks SET position = position * 65536`, and no new algorithm is needed beyond `(left + right) / 2`. Rebalance occurs after 16 consecutive bisections at the same gap — adequate for realistic editing patterns. String fractional indices (base-26 lexicographic, never-rebalance) are theoretically superior but require full `blocks` table recreation including FTS5 trigger reconstruction — defer to a future phase.
