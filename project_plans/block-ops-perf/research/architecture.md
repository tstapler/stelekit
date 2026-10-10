# Architecture Research: Block Operations Performance Phase 2

## 1. WITHOUT ROWID Migration for `wikilink_references`

### SQLite Constraints

SQLite's `ALTER TABLE` cannot change ROWID/WITHOUT ROWID status on an existing table. The only path is a table recreation sequence:

```sql
BEGIN IMMEDIATE;
PRAGMA foreign_keys=OFF;

CREATE TABLE wikilink_references_new (
    block_uuid TEXT NOT NULL,
    page_name  TEXT NOT NULL COLLATE NOCASE,
    PRIMARY KEY (block_uuid, page_name),
    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name_new ON wikilink_references_new(page_name COLLATE NOCASE);

INSERT INTO wikilink_references_new SELECT block_uuid, page_name FROM wikilink_references;

DROP TABLE wikilink_references;
ALTER TABLE wikilink_references_new RENAME TO wikilink_references;

PRAGMA foreign_keys=ON;
COMMIT;
```

### Foreign Key Interaction

`PRAGMA foreign_keys=OFF` must be issued before DROP TABLE, or SQLite will refuse to drop `wikilink_references` while the FK constraint on `block_uuid REFERENCES blocks(uuid)` exists. `PRAGMA foreign_keys=ON` must be restored before `COMMIT`. **Critical**: the MigrationRunner calls `driver.execute()` per statement — it does not wrap multiple statements in a single transaction automatically. The entire recreation sequence must either be wrapped in a `MigrationRunner.Migration` whose statements are executed in order, or issued via a single raw `driver.execute` with a begin/commit wrapping each logical group.

**Problem with MigrationRunner's approach**: Each statement in `Migration.statements` is executed individually via `driver.execute(null, sql.trimIndent(), 0).await()`. `PRAGMA foreign_keys=OFF` is a session-level pragma — if the migration executes statements sequentially without an enclosing transaction, the pragma takes effect, but the `DROP TABLE`/`RENAME` sequence is not atomic from SQLite's perspective. On Android with `RequerySQLiteOpenHelperFactory`, each `execute()` call goes through the Android SQLite connection. Because `PRAGMA foreign_keys` is a per-connection setting (not per-transaction), the sequence is safe as long as all statements run on the same connection — which is guaranteed by the single `SqlDriver` and the fact that `MigrationRunner.applyAll` runs on a single coroutine.

**Recommended**: Add `PRAGMA foreign_keys=OFF` as the first statement and `PRAGMA foreign_keys=ON` as the last statement in a single Migration entry. The table rename makes this effectively atomic from the schema perspective.

### FTS5 Trigger Interaction

`wikilink_references` has no FTS table or triggers pointing at it — the FTS tables (`blocks_fts`, `pages_fts`) reference `blocks` and `pages` respectively. The blocks FTS triggers (`blocks_ai`, `blocks_ad`, `blocks_au`) and the pages FTS triggers (`pages_ai`, `pages_ad`, `pages_au`) are unaffected by the `wikilink_references` recreation. The only trigger referencing blocks is via the FK cascade: when a block is deleted, SQLite's `ON DELETE CASCADE` on `wikilink_references.block_uuid` deletes the corresponding wikilink rows. This cascade behavior is preserved in `WITHOUT ROWID` tables — SQLite FK cascade logic is independent of storage format.

**Important caveat**: `WITHOUT ROWID` tables require that every column referenced by a FK constraint must be part of the primary key or indexed. The `block_uuid` column is part of the PK of `wikilink_references`, so the FK to `blocks(uuid)` is fine. The `idx_wikilink_refs_page_name` index is needed for the `page_name`-based lookups (`selectWikilinkPageNamesForPage`, `recomputeBacklinkCountsForPages`) and must be recreated on the new table before the old one is dropped.

### SQLDelight Migration Framework Interaction

MigrationRunner uses hash-based idempotency, not sequential version numbers. The new migration entry must:

1. Be appended to `MigrationRunner.all` after the existing `wikilink_references_table` migration.
2. Use `allowContentUpdate = false` (no reason to allow hash modification here).
3. The `MigrationRunnerSchemaSyncTest` extracts `CREATE TABLE IF NOT EXISTS` names from `SteleDatabase.sq` and checks each is present in `MigrationRunner.all`. Since the final table is still named `wikilink_references`, the existing entry in `MigrationRunner.all` satisfies that check. No changes needed in `SteleDatabase.sq` — the schema definition there is the target state (what fresh installs get), and fresh installs never see `wikilink_references` without `WITHOUT ROWID` because the schema file would be updated to include `WITHOUT ROWID`. Existing installs get the migration.

**Schema file update required**: `SteleDatabase.sq` must also be updated to declare `wikilink_references ... WITHOUT ROWID` so fresh installs (via `SteleDatabase.Schema.create(driver)`) get the correct schema. This is the source of truth for SQLDelight code generation and for the sync test.

### Migration SQL Template

```kotlin
Migration(
    name = "wikilink_references_without_rowid",
    statements = listOf(
        "PRAGMA foreign_keys=OFF",
        """
        CREATE TABLE IF NOT EXISTS wikilink_references_new (
            block_uuid TEXT NOT NULL,
            page_name  TEXT NOT NULL COLLATE NOCASE,
            PRIMARY KEY (block_uuid, page_name),
            FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
        ) WITHOUT ROWID
        """,
        "CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name_new ON wikilink_references_new(page_name COLLATE NOCASE)",
        "INSERT OR IGNORE INTO wikilink_references_new SELECT block_uuid, page_name FROM wikilink_references",
        "DROP TABLE IF EXISTS wikilink_references",
        "ALTER TABLE wikilink_references_new RENAME TO wikilink_references",
        "PRAGMA foreign_keys=ON",
    )
)
```

`INSERT OR IGNORE` handles the edge case where a database somehow has duplicate (block_uuid, page_name) pairs that violate the PK constraint.

---

## 2. `blockType` Sealed Class Migration

### Current State

In `Models.kt`, the `Block` data class has:

```kotlin
val blockType: String = "bullet"
```

With a private `validBlockTypes` set and a `require(blockType in validBlockTypes)` check in `init`. The repository boundary (`toBlockModel()` in `SqlDelightBlockRepository`) uses `knownBlockTypeOrDefault()` for graceful fallback:

```kotlin
private fun knownBlockTypeOrDefault(blockType: String, uuid: String): String {
    if (blockType in knownBlockTypes) return blockType
    logger.warn("Unknown block_type '$blockType' for block $uuid — falling back to 'bullet'")
    return "bullet"
}
```

The DB stores `block_type TEXT NOT NULL DEFAULT 'bullet'`.

### Sealed Class vs Sealed Interface

**Recommendation: sealed class with `companion object`** rather than a sealed interface. Kotlin sealed interfaces cannot have state or a companion object `fromString` factory by default, but both work. The decisive factor is that the `BlockType` needs a `fromString()` smart constructor with graceful fallback — a `companion object` on a sealed class is idiomatic:

```kotlin
sealed class BlockType {
    data object Bullet : BlockType()
    data object Paragraph : BlockType()
    data object Heading : BlockType()
    data object CodeFence : BlockType()
    data object Blockquote : BlockType()
    data object OrderedListItem : BlockType()
    data object ThematicBreak : BlockType()
    data object Table : BlockType()
    data object RawHtml : BlockType()
    data object ImageAnnotation : BlockType()
    data class Unknown(val raw: String) : BlockType()  // graceful fallback

    fun toDbString(): String = when (this) {
        is Bullet -> "bullet"
        is Paragraph -> "paragraph"
        is Heading -> "heading"
        is CodeFence -> "code_fence"
        is Blockquote -> "blockquote"
        is OrderedListItem -> "ordered_list_item"
        is ThematicBreak -> "thematic_break"
        is Table -> "table"
        is RawHtml -> "raw_html"
        is ImageAnnotation -> "image_annotation"
        is Unknown -> raw
    }

    companion object {
        fun fromString(s: String): BlockType = when (s) {
            "bullet" -> Bullet
            "paragraph" -> Paragraph
            "heading" -> Heading
            "code_fence" -> CodeFence
            "blockquote" -> Blockquote
            "ordered_list_item" -> OrderedListItem
            "thematic_break" -> ThematicBreak
            "table" -> Table
            "raw_html" -> RawHtml
            "image_annotation" -> ImageAnnotation
            else -> Unknown(s)  // graceful fallback — no crash, no warning noise
        }
    }
}
```

Using `data object` (Kotlin 1.9+) gives correct `equals`/`hashCode`/`toString` for singleton subtypes. The `Unknown(val raw: String)` subtype preserves round-trip fidelity: an unknown block type can be stored back to the DB unchanged instead of being silently coerced to `"bullet"`.

### SQLDelight `columnAdapter` Pattern

SQLDelight 2.x supports custom column adapters via the `adapter` parameter on generated `Database` constructor. For KMP, add to `kmp/build.gradle.kts` in the `sqldelight {}` block:

```kotlin
// In SteleDatabase.sq, block_type is TEXT — no .sq change needed.
// Adapter wires TEXT ↔ BlockType at the generated-code boundary.
```

The adapter class:

```kotlin
object BlockTypeAdapter : ColumnAdapter<BlockType, String> {
    override fun decode(databaseValue: String): BlockType = BlockType.fromString(databaseValue)
    override fun encode(value: BlockType): String = value.toDbString()
}
```

Then in `DriverFactory` / database construction:

```kotlin
SteleDatabase(
    driver = driver,
    blocksAdapter = Blocks.Adapter(blockTypeAdapter = BlockTypeAdapter)
)
```

SQLDelight generates a `Blocks.Adapter` data class with a field for each custom-typed column. With this in place, the generated `Blocks` data class changes `block_type: String` to `block_type: BlockType`, and all `toBlockModel()` calls get the already-decoded type — no manual `knownBlockTypeOrDefault()` call needed.

**Callers impacted**: Every place that currently passes a `String` as `block_type` to `insertBlock()`, `updateBlockForSave()`, `updateBlockFull()`, etc. must instead pass `BlockType`. The `RestrictedDatabaseQueries` stubs and `SqlDelightBlockRepository` methods (`insertBlockRow`, `saveBlock`, etc.) all pass `block.blockType` — changing `Block.blockType` from `String` to `BlockType` propagates the type change automatically at those call sites.

**`Block.init` validation change**: Remove the `validBlockTypes` set and `require(blockType in validBlockTypes)` check. The sealed class makes invalid types structurally impossible (they become `Unknown`), so the init guard is both redundant and too strict (it would reject `Unknown` instances). The `Unknown` case should be logged at the repository boundary and allowed through.

### DB Storage

No schema change needed. `block_type TEXT NOT NULL DEFAULT 'bullet'` remains TEXT in SQLite. The column adapter handles conversion at read/write time. The `DEFAULT 'bullet'` SQLite default is irrelevant since every insert provides an explicit value.

---

## 3. Two-Connection Read/Write Split Driver

### SqlDriver Interface

`app.cash.sqldelight.db.SqlDriver` (SQLDelight 2.3.2) is an interface with four key methods:

```kotlin
interface SqlDriver : Closeable {
    fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long>
    fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R>
    fun newTransaction(): QueryResult<Transacter.Transaction>
    fun currentTransaction(): Transacter.Transaction?
}
```

Both `execute()` and `executeQuery()` are used for both reads and writes — `executeQuery()` is not exclusively for SELECT. SQLDelight uses `executeQuery()` for any statement that returns a cursor (which includes SELECT), and `execute()` for statements that return a rowcount (INSERT/UPDATE/DELETE). However, there is no semantic flag at the `SqlDriver` interface level that distinguishes a SELECT from a DML statement. The distinction must be inferred from the SQL string or from a higher-level routing decision.

### Write Path Analysis

`DatabaseWriteActor` does not call `SqlDriver` directly — it calls `BlockRepository` and `PageRepository` methods, which call `SteleDatabaseQueries` (SQLDelight-generated), which calls the `SqlDriver`. The actor routes all writes to the same repository instance that also handles reads. There is no injection point in `DatabaseWriteActor` or `RestrictedDatabaseQueries` to swap to a different driver for writes.

`SqlDelightBlockRepository` holds `private val queries = database.steleDatabaseQueries` — a single `SteleDatabaseQueries` instance backed by one `SteleDatabase` backed by one `SqlDriver`. There is no support in SQLDelight 2.x for a read/write-split driver pattern at the `SteleDatabase` construction level.

### Two-Connection Wrapper Viability

A custom `SqlDriver` wrapper is technically feasible:

```kotlin
class ReadWriteSplitDriver(
    private val readDriver: SqlDriver,
    private val writeDriver: SqlDriver,
) : SqlDriver {
    override fun execute(identifier: Int?, sql: String, ...): QueryResult<Long> =
        writeDriver.execute(identifier, sql, ...)  // INSERT/UPDATE/DELETE/DDL

    override fun <R> executeQuery(identifier: Int?, sql: String, ...): QueryResult<R> {
        // Heuristic: route SELECT to readDriver, everything else to writeDriver
        return if (sql.trimStart().startsWith("SELECT", ignoreCase = true) ||
                   sql.trimStart().startsWith("WITH", ignoreCase = true)) {
            readDriver.executeQuery(identifier, sql, ...)
        } else {
            writeDriver.executeQuery(identifier, sql, ...)
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        writeDriver.newTransaction()  // Transactions always go to writeDriver

    override fun currentTransaction(): Transacter.Transaction? =
        writeDriver.currentTransaction()

    override fun close() { readDriver.close(); writeDriver.close() }
}
```

**Problems with this approach**:

1. **SQLDelight uses statement identifier caching** (`identifier: Int?`): cached prepared statement identifiers are per-driver. The same `identifier` may refer to different prepared statements on `readDriver` vs `writeDriver`. Routing the same identifier to different drivers will cause wrong statements to execute or `IllegalStateException`. The identifier cache is managed inside each `AndroidSqliteDriver` independently.

2. **Transaction boundary confusion**: SQLDelight's `Transacter` calls `currentTransaction()` to check if a transaction is active. If a SELECT runs on `readDriver` (no active transaction) while a transaction is open on `writeDriver`, `currentTransaction()` returns null from `readDriver`'s perspective. SQLDelight's query result caching and notification system assumes a single driver — the `notifyQueries` mechanism in `SteleDatabaseQueries` would not work correctly across two drivers.

3. **Requery's `RequerySQLiteOpenHelperFactory`** does not implement `enableWriteAheadLogging()` anyway — this is the root problem. Opening two `AndroidSqliteDriver` instances pointing at the same database file in WAL mode would give two separate Android `SQLiteDatabase` instances. Android's native `SQLiteDatabase` manages a per-instance read connection pool, but two separate instances may not correctly share the WAL file state, and Android's `SQLiteDatabase` is not designed to be used this way. Specifically, a writer on `writeDriver`'s `SQLiteDatabase` and a reader on `readDriver`'s `SQLiteDatabase` in WAL mode: SQLite itself guarantees WAL MVCC (reader never blocks writer), but Android's `SQLiteDatabase` connection pool logic and locking may not behave correctly when two independent `SQLiteDatabase` instances open the same file.

4. **Alternative — Requery 4.x or `SupportSQLiteOpenHelper.Factory`**: Requery's `RequerySQLiteOpenHelperFactory` is a `SupportSQLiteOpenHelper.Factory` wrapper. There is no public API to call `enableWriteAheadLogging()` on the factory or the resulting helper. This is not a Requery bug — Android's `SQLiteOpenHelper.enableWriteAheadLogging()` enables Android's native multi-connection pool, but `SQLiteOpenHelper` is an Android framework class that Requery wraps via `SupportSQLiteOpenHelper`. The `SupportSQLiteOpenHelper` interface does not expose `enableWriteAheadLogging()`. This is the fundamental architectural gap.

5. **Simpler alternative**: The `PRAGMA journal_mode=WAL` is already set (via `ANDROID_PRAGMAS`). In WAL mode, SQLite at the C library level does allow concurrent readers while a writer holds the write lock — but only when the readers use separate database connections. `RequerySQLiteOpenHelperFactory` internally uses a single connection. The practical fix is to switch to a factory that supports multi-connection: **FrameworkSQLiteOpenHelperFactory** (the default AndroidX factory, not Requery) with `SQLiteDatabase.enableWriteAheadLogging()`. This would require evaluating whether Requery's additional SQLite features (e.g., `REAL_ONLY_CURSORS`, extended pragmas) are still needed.

### Summary Assessment

A wrapper `SqlDriver` routing by SQL prefix is possible as a prototype but fragile in production due to the statement identifier caching issue. The cleaner path is: evaluate switching from `RequerySQLiteOpenHelperFactory` to the default AndroidX factory (`FrameworkSQLiteOpenHelperFactory`) and calling `enableWriteAheadLogging()` on the `SQLiteOpenHelper`. This is a driver-factory change, not a SQLDelight change, and does not require forking SQLDelight. The risk is losing any Requery-specific SQLite features.

---

## 4. Sparse Position Migration SQL

### Migration SQL

For a single-pass migration that converts dense positions (0, 1, 2, ...) to sparse positions (0, 65536, 131072, ...):

```sql
-- Option A: Update in-place using ROW_NUMBER window function (SQLite 3.25+, Requery bundles 3.45)
UPDATE blocks
SET position = (
    ROW_NUMBER() OVER (
        PARTITION BY page_uuid, parent_uuid
        ORDER BY position
    ) - 1
) * 65536
WHERE 1;
```

However, SQLite does not support using window functions in `UPDATE ... SET col = (subquery with window function)` — this is a known SQLite limitation. Window functions are only supported in SELECT.

**Correct approach** for SQLite:

```sql
-- Create a CTE that computes the new positions, then UPDATE via JOIN (SQLite 3.35+)
WITH ranked AS (
    SELECT
        uuid,
        (ROW_NUMBER() OVER (
            PARTITION BY page_uuid, parent_uuid
            ORDER BY position
        ) - 1) * 65536 AS new_position
    FROM blocks
)
UPDATE blocks
SET position = ranked.new_position
FROM ranked
WHERE blocks.uuid = ranked.uuid;
```

SQLite 3.35.0 introduced `UPDATE ... FROM` syntax. Requery bundles SQLite 3.45 — this is safe.

**Transaction safety at 8000+ blocks**: A single `UPDATE blocks SET position = ...` touches every block in the table (one write per row). At 8000+ blocks this is ~8000 row updates in one transaction. SQLite handles this safely — WAL mode keeps the transaction log in the WAL file, and the commit is atomic from a durability perspective. The transaction will hold the write lock for the duration of the UPDATE. On Android with a single connection and WAL mode, this blocks all reads during the migration. Duration estimate: 8000 rows × ~0.5ms/row ≈ 4 seconds. This is acceptable in a migration context (runs at startup before the UI is shown), but should be noted.

**Important**: The migration must use `parent_uuid IS NULL` vs `parent_uuid IS NOT NULL` partition logic correctly. The `PARTITION BY page_uuid, parent_uuid` partitions null parent_uuid separately per page (root blocks) and each non-null parent_uuid separately (children of each parent). This is the correct behavior.

---

## 5. `getBlockHierarchy` WITH RECURSIVE CTE

### Current BFS Implementation

`SqlDelightBlockRepository.getBlockHierarchy()` (lines 130–182) does:

1. `queries.selectBlockByUuid(rootUuid.value).executeAsOneOrNull()` — 1 query for root
2. BFS loop: `queries.selectBlocksByParentUuids(nextLevelUuids).executeAsList()` — 1 query per depth level
3. Loop limit: 100 levels deep

For a 10-level tree this is 11 SQL round-trips.

### WITH RECURSIVE CTE Equivalent

```sql
-- To be added to SteleDatabase.sq

selectBlockHierarchyRecursive:
WITH RECURSIVE subtree(
    id, uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
    created_at, updated_at, properties, version, content_hash, block_type, depth
) AS (
    -- Anchor: the root block
    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, 0 AS depth
    FROM blocks b
    WHERE b.uuid = ?
    UNION ALL
    -- Recursive: children of current level
    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, s.depth + 1
    FROM blocks b
    INNER JOIN subtree s ON b.parent_uuid = s.uuid
    WHERE s.depth < 100  -- guard against pathological cycles (data corruption)
)
SELECT * FROM subtree
ORDER BY depth, position;
```

**Notes on SQLDelight integration**:

- The query name `selectBlockHierarchyRecursive` generates a `selectBlockHierarchyRecursive(uuid: String)` function on `SteleDatabaseQueries`.
- The CTE result includes `depth` as an extra column not present in the `blocks` table. SQLDelight will generate a new result type (data class) for this query, distinct from the `Blocks` type used elsewhere. The `toBlockModel()` extension will not apply directly — a new mapping function is needed.
- The `BlockWithDepth` domain type maps naturally: `BlockWithDepth(block = row.toBlockFromCteRow(), depth = row.depth.toInt())`.
- SQLDelight 2.x supports `WITH RECURSIVE` in `.sq` files — the SQL is passed verbatim to SQLite, so the CTE syntax is valid.
- **Index usage**: The recursive step `JOIN blocks ON b.parent_uuid = s.uuid` benefits from `idx_blocks_parent_position(parent_uuid, position)` which already exists. Each recursive iteration looks up children by `parent_uuid = s.uuid ORDER BY position` — this is the exact index key order.

**`RestrictedDatabaseQueries` stub**: `selectBlockHierarchyRecursive` is a SELECT (read-only), so it does not need a `@DirectSqlWrite` stub. It is called via `queries.selectBlockHierarchyRecursive(uuid)` directly on `SteleDatabaseQueries`.

**Cache compatibility**: The existing `hierarchyCache` keyed by `rootUuid` still works — replace the BFS result with the CTE result and cache it identically. The `hierarchyPageIndex` eviction logic is unchanged.

---

## Summary

- **WITHOUT ROWID migration complexity**: High — requires a 7-statement table recreation sequence with `PRAGMA foreign_keys=OFF/ON` bracketing, `INSERT OR IGNORE` for safety, and coordinated updates to both `SteleDatabase.sq` (for fresh installs) and `MigrationRunner.all` (for existing installs). The MigrationRunner's single-statement-at-a-time execution model works here because the FK pragma is session-scoped and the statements execute sequentially on one connection. The FTS triggers are unaffected. `MigrationRunnerSchemaSyncTest` passes without changes as long as the final table name is `wikilink_references`.

- **Sealed class + columnAdapter feasibility**: Straightforward. SQLDelight 2.x `ColumnAdapter<BlockType, String>` cleanly handles TEXT ↔ sealed class conversion. The `Unknown(raw: String)` fallback subtype preserves graceful degradation without a crash. The `Block.init` validation guard must be removed (the sealed class makes it redundant). All callers that pass `block.blockType` as a string to generated queries automatically get the new type once `Block.blockType` changes from `String` to `BlockType`. No SQL schema change required.

- **Two-connection driver viability**: Not viable as a SQLDelight-transparent wrapper due to per-driver statement identifier caching. The correct path is switching from `RequerySQLiteOpenHelperFactory` to the default AndroidX `FrameworkSQLiteOpenHelperFactory` and calling `SQLiteOpenHelper.enableWriteAheadLogging()`, which activates Android's native multi-connection pool (N read connections + 1 write connection) without SQLDelight changes. This requires evaluating whether any Requery-specific behavior is relied upon — check extended pragma support and cursor behavior differences.
