# Implementation Plan: Block Operations Performance — Phase 2

## Overview

Phase 1 (committed) resolved the acute 5-second lock-contention issue. Phase 1.5 (uncommitted, in working tree) applied O(1) algorithm fixes to `moveBlockUp`, `moveBlockDown`, `mergeBlocks`, `deleteBlock`, and `deleteBulk`. This plan covers the six remaining structural improvements: sealed `BlockType`, recursive CTE hierarchy loading, batch wikilink inserts, `WITHOUT ROWID` for the wikilink reference table, fractional string positions, and WAL connection-pool modernization.

**Technology decisions requiring ADRs**: R1 (ADR-013, fractional indexing), R3 (ADR-014, blockType sealed class), R6 (ADR-015, WAL connection pool).

---

## Epic 1: Phase 1.5 Commit + Test Validation (R0 — prerequisite)

Independent from other epics: yes — this must land first; all later epics build on a green baseline.

### Story 1.1: Commit Phase 1.5 algorithm fixes and verify CI green

**Acceptance**: `./gradlew ciCheck` passes with the Phase 1.5 working-tree changes committed. `LargeGraphWarmStartCrashTest`, `QueryPlanAuditTest`, `RepositoryFlowResilienceTest` all green.

#### Task 1.1.1: Stage and commit Phase 1.5 files

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`
File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

Stage only the Phase 1.5 changes (move O(n)→O(1) sibling loads, mergeBlocks counter, deleteBlock/deleteBulk batch wikilink queries, `selectWikilinkPageNamesForBlocks` SQL). Commit with message referencing the algorithm changes from requirements.md §Phase 1.5.

#### Task 1.1.2: Run full CI check

Command: `./gradlew ciCheck`

Verify: zero test failures. If `MigrationRunnerSchemaSyncTest` fails because `selectWikilinkPageNamesForBlocks` introduces a new query shape, check whether `QueryPlanAuditTest` needs a new `AuditQuery` entry for `selectWikilinkPageNamesForBlocks`.

File to update if needed: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt`
Change: add `AuditQuery("selectWikilinkPageNamesForBlocks", "SELECT DISTINCT page_name FROM wikilink_references WHERE block_uuid IN (...)")` to the `QUERIES` list.

---

## Epic 2: `blockType` Sealed Class (R3)

Independent from other epics: yes — no schema change; no dependency on migrations. Do this first among the structural changes to flush out init-throw crashes early.

**ADR-014**: Document decision to introduce `BlockType` sealed class with `Unknown(raw: String)` fallback instead of a validated string. Key point: `ImageAnnotation` is added as a first-class variant to close the gap between `ParsedModels.BlockType` and the domain `Block.blockType`.

### Story 2.1: Introduce domain `BlockType` sealed class

**Acceptance**: `Block.blockType` is `BlockType` (not `String`). `BlockType.fromString("unknown_plugin_type")` returns `BlockType.Unknown("unknown_plugin_type")` without throwing. All existing tests compile and pass.

#### Task 2.1.1: Extend `ParsedModels.BlockType` with `ImageAnnotation` and `Unknown`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt`

Change: add two subtypes to the existing `sealed class BlockType`:
```kotlin
object ImageAnnotation : BlockType()
data class Unknown(val raw: String) : BlockType()
```

Existing subtypes (`Bullet`, `Paragraph`, `Heading`, `CodeFence`, `Blockquote`, `OrderedListItem`, `ThematicBreak`, `Table`, `RawHtml`) remain unchanged. `ParsedModels.BlockType` is reused as the domain model's `BlockType` — no separate domain sealed class needed (the parser's type already covers all cases; the domain just needs `ImageAnnotation` and `Unknown` added).

**Tests to verify**: compile only at this step (no callers yet).

#### Task 2.1.2: Extend `BlockTypeMapper` with `fromString` and `ImageAnnotation` mapping

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BlockTypeMapper.kt`

Change: add `BlockTypes.IMAGE_ANNOTATION = "image_annotation"` constant if not already present. Add `ImageAnnotation` and `Unknown` arms to `toDiscriminatorString()`:
```kotlin
is BlockType.ImageAnnotation -> BlockTypes.IMAGE_ANNOTATION
is BlockType.Unknown -> this.raw
```

Add `fromString()` companion function (or top-level function in the same file):
```kotlin
fun BlockType.Companion.fromString(s: String): BlockType = when (s) {
    BlockTypes.BULLET             -> BlockType.Bullet
    BlockTypes.PARAGRAPH          -> BlockType.Paragraph
    BlockTypes.HEADING            -> BlockType.Heading(0)   // level recovered from content
    BlockTypes.CODE_FENCE         -> BlockType.CodeFence("")
    BlockTypes.BLOCKQUOTE         -> BlockType.Blockquote
    BlockTypes.ORDERED_LIST_ITEM  -> BlockType.OrderedListItem(0)
    BlockTypes.THEMATIC_BREAK     -> BlockType.ThematicBreak
    BlockTypes.TABLE              -> BlockType.Table
    BlockTypes.RAW_HTML           -> BlockType.RawHtml
    BlockTypes.IMAGE_ANNOTATION   -> BlockType.ImageAnnotation
    else                          -> BlockType.Unknown(s)
}
```

Note: `Heading` and `OrderedListItem` have constructor parameters that are not stored in `block_type` TEXT (level/number come from content). The `fromString` path returns sentinel values (0); callers that need the actual level read it from the block content, not the type. Verify this is consistent with all existing `when (block.blockType)` expressions.

**Tests to verify**: unit test `BlockTypeMapperTest` if it exists; add one if not (see Story 2.3).

#### Task 2.1.3: Change `Block.blockType` from `String` to `BlockType`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`

Changes:
1. Remove `private val validBlockTypes = setOf(...)`.
2. Change `val blockType: String = "bullet"` to `val blockType: BlockType = BlockType.Bullet`.
3. Remove `require(blockType in validBlockTypes) { "Invalid blockType: $blockType" }` from `init`.
4. Remove `require(position >= 0)` guard — keep it (position is still non-negative integer at this stage).

**Tests to verify**: all tests that construct `Block(...)` must now pass `blockType = BlockType.Bullet` (or the appropriate subtype) instead of `blockType = "bullet"`. This will produce compile errors at all call sites — each must be fixed.

#### Task 2.1.4: Fix `RestrictedDatabaseQueries` write stubs

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

Change: `insertBlock`, `updateBlockForSave`, `updateBlockFull`, and any other stubs that accept `block_type: String` must change to `block_type: BlockType`. The generated `SteleDatabaseQueries` still takes `String` (no `.sq` change); call `block_type.toDiscriminatorString()` at the forwarding site.

Example for `insertBlock`:
```kotlin
@DirectSqlWrite
suspend fun insertBlock(
    ...
    block_type: BlockType,  // was String
): Long = queries.insertBlock(
    ...
    block_type.toDiscriminatorString(),  // convert at boundary
)
```

**Tests to verify**: `ciCheck` compile pass.

#### Task 2.1.5: Fix `SqlDelightBlockRepository` — update `toBlockModel()` and all write call sites

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

Changes:
1. In `toBlockModel()` (or equivalent row-to-model mapper): replace `knownBlockTypeOrDefault(row.block_type, row.uuid)` with `BlockType.fromString(row.block_type)`.
2. Remove the `knownBlockTypeOrDefault()` private function.
3. At every call site that passes `block.blockType` as a string argument to `restricted.*` methods, the type is now `BlockType` — the stub in Task 2.1.4 accepts `BlockType` directly, so no `.toDiscriminatorString()` call needed here.
4. Any `when (block.blockType)` expressions in this file must be updated to use sealed-class arms rather than string literals.

**Tests to verify**: `jvmTest` full run.

#### Task 2.1.6: Fix all remaining call sites (UI, ViewModel, other repositories)

Search: `grep -rn "blockType ==" kmp/src` and `grep -rn "block_type" kmp/src`

Likely files:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/` — any `when (block.blockType)` or `block.blockType == "heading"` comparisons
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — `ParsedBlock.blockType` is already the parser's `BlockType`; verify the assignment from `ParsedBlock.blockType` to `Block.blockType` is type-compatible now that both are `BlockType`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` — any `block.blockType.toDiscriminatorString()` calls for file serialization

**Tests to verify**: `./gradlew ciCheck`.

### Story 2.2: SQLDelight column adapter (optional path if SQLDelight is configured to use the generated type)

**Acceptance**: If the SQLDelight-generated `Blocks` data class is used directly (not converted via a manual `toBlockModel()`), wire a `ColumnAdapter<BlockType, String>` so the generated code uses `BlockType` natively. If `toBlockModel()` is the only boundary (which is the current pattern), this story is skipped — the adapter is not needed.

#### Task 2.2.1: Evaluate whether a `ColumnAdapter` is needed

Check `SqlDelightBlockRepository.toBlockModel()` — if it manually reads `row.block_type: String` and calls `BlockType.fromString()`, no adapter is needed. If the generated `Blocks` type is used by value elsewhere in the codebase, add:

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BlockTypeAdapter.kt` (new file only if needed)
```kotlin
object BlockTypeAdapter : ColumnAdapter<BlockType, String> {
    override fun decode(databaseValue: String) = BlockType.fromString(databaseValue)
    override fun encode(value: BlockType) = value.toDiscriminatorString()
}
```

File: wherever `SteleDatabase(driver)` is constructed (likely `DriverFactory.kt` or `RepositoryFactory.kt`)
Change: pass `blocksAdapter = Blocks.Adapter(blockTypeAdapter = BlockTypeAdapter)`.

### Story 2.3: New tests for `BlockType` sealed class

**Acceptance**: `fromString("unknown_plugin_type")` → `Unknown`; `fromString("image_annotation")` → `ImageAnnotation`; `toDiscriminatorString()` round-trips for all known types; `Block` construction with `BlockType.Unknown("foo")` does not throw.

#### Task 2.3.1: Add `BlockTypeTest` in `businessTest`

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/model/BlockTypeTest.kt` (new)

Tests:
- `fromString` for each known type returns the correct subtype.
- `fromString("image_annotation")` returns `BlockType.ImageAnnotation` (not `Unknown`).
- `fromString("some_future_plugin_type")` returns `BlockType.Unknown("some_future_plugin_type")`.
- `Unknown("foo").toDiscriminatorString()` returns `"foo"` (round-trip fidelity).
- `Block(..., blockType = BlockType.Unknown("foo"))` constructs without throwing.

---

## Epic 3: `getBlockHierarchy` WITH RECURSIVE CTE (R5)

Independent from other epics: yes — read-only query change, no schema migration, no dependency on position type.

### Story 3.1: Add `selectBlockHierarchyRecursive` to SQL schema

**Acceptance**: `getBlockHierarchy` for a 10-level, 100-node page produces exactly 1 SQL query (verified by trace or existing hierarchy tests). Existing screenshot and block-tree tests pass.

#### Task 3.1.1: Add the CTE query to `SteleDatabase.sq`

File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

Add after the existing `selectBlocksByParentUuids` query:

```sql
selectBlockHierarchyRecursive:
WITH RECURSIVE subtree(
    id, uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
    created_at, updated_at, properties, version, content_hash, block_type, depth
) AS (
    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, 0 AS depth
    FROM blocks b
    WHERE b.uuid = ?
    UNION ALL
    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
           b.level, b.position, b.created_at, b.updated_at, b.properties,
           b.version, b.content_hash, b.block_type, s.depth + 1
    FROM blocks b
    INNER JOIN subtree s ON b.parent_uuid = s.uuid
    WHERE s.depth < 100
)
SELECT * FROM subtree
ORDER BY depth, parent_uuid, position;
```

The `WHERE s.depth < 100` guard prevents runaway recursion on pathologically deep trees or corrupt `parent_uuid` cycles. This matches the existing BFS loop limit of 100 levels.

**Tests to verify**: SQLDelight code generation succeeds (`./gradlew generateSqlDelightInterface` or the equivalent KMP task).

#### Task 3.1.2: Add a `toBlockFromCteRow()` mapper in `SqlDelightBlockRepository`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

The CTE query generates a distinct result type `SelectBlockHierarchyRecursive` (SQLDelight creates one per named query). Add a private extension function:

```kotlin
private fun SelectBlockHierarchyRecursive.toBlockWithDepth(): BlockWithDepth =
    BlockWithDepth(
        block = Block(
            uuid = BlockUuid(uuid),
            pageUuid = PageUuid(page_uuid),
            parentUuid = parent_uuid,
            leftUuid = left_uuid,
            content = content,
            level = level.toInt(),
            position = position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            properties = properties?.let { parseProperties(it) } ?: emptyMap(),
            version = version,
            contentHash = content_hash,
            blockType = BlockType.fromString(block_type),  // after Epic 2; "bullet" string pre-Epic 2
        ),
        depth = depth.toInt()
    )
```

If Epic 2 is not yet merged, use `knownBlockTypeOrDefault(block_type, uuid)` here instead of `BlockType.fromString`.

#### Task 3.1.3: Replace BFS loop in `getBlockHierarchy` with single CTE call

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

Current implementation (approximately lines 130–182): BFS loop with `selectBlocksByParentUuids`, wrapped in a `flow { ... }.flowOn(PlatformDispatcher.DB)` block.

`getBlockHierarchy` returns `Flow<Either<DomainError, List<BlockWithDepth>>>` in the `BlockRepository` interface — it is NOT a `suspend fun`. The new implementation must preserve this contract:

```kotlin
override fun getBlockHierarchy(rootUuid: BlockUuid): Flow<Either<DomainError, List<BlockWithDepth>>> = flow {
    try {
        val rows = queries.selectBlockHierarchyRecursive(rootUuid.value).executeAsList()
        emit(rows.map { it.toBlockWithDepth() }.right())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        emit(DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left())
    }
}.flowOn(PlatformDispatcher.DB)
```

The `hierarchyCache` (if present) is keyed by `rootUuid` and still valid — cache the CTE result the same way the BFS result was cached. The `hierarchyPageIndex` eviction logic is unchanged.

**Tests to verify**: All existing tests that exercise `getBlockHierarchy` (`HierarchyTest`, screenshot tests that render nested blocks, `LargeGraphWarmStartCrashTest`).

#### Task 3.1.4: Add `selectBlockHierarchyRecursive` to `QueryPlanAuditTest`

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt`

Add to the `QUERIES` list:
```kotlin
AuditQuery(
    "selectBlockHierarchyRecursive",
    "WITH RECURSIVE subtree(...) AS (SELECT ... FROM blocks WHERE uuid = 'test-uuid' UNION ALL ...) SELECT * FROM subtree ORDER BY depth, position"
)
```

Use a simplified representative SQL in the audit (not the full multi-line CTE) since the audit checks plan output, not exact SQL. Verify the plan shows `SEARCH blocks USING INDEX idx_blocks_parent_position` in the recursive step.

### Story 3.2: Deep hierarchy regression test

**Acceptance**: A 50-level hierarchy with 1 block per level loads in `getBlockHierarchy` without error. The result contains exactly 51 blocks (root + 50 children). The `depth` values are 0..50.

#### Task 3.2.1: Add `BlockHierarchyCteTest` in `businessTest`

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/BlockHierarchyCteTest.kt` (new)

Test: insert a chain of 50 blocks each parented to the previous. Call `getBlockHierarchy(root)`. Assert: 51 results, `depth` 0–50, `ORDER BY depth, position` maintained. Also assert exactly 1 SQL statement was issued (use a statement-counting `SqlDriver` spy or check the repository's query counter if one exists).

---

## Epic 4: Batch Wikilink Inserts (R4)

Independent from other epics: yes — no schema migration, no dependency on position type or sealed class.

### Story 4.1: Replace per-name `insertWikilinkReference` loop with single multi-row INSERT

**Acceptance**: For a block referencing 5 page names, the DB trace shows 1 INSERT statement (not 5). Existing tests pass. Chunk size ≤ 499 pairs enforced.

#### Task 4.1.1: Add `insertWikilinkReferencesBatch` to `RestrictedDatabaseQueries`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

Add after `insertWikilinkReference`:

```kotlin
// RestrictedDatabaseQueries needs direct SqlDriver access for raw execute.
// Pass driver through the constructor — SqlDelightBlockRepository already has it.
// Change RestrictedDatabaseQueries constructor from:
//   class RestrictedDatabaseQueries(private val queries: SteleDatabaseQueries)
// to:
//   class RestrictedDatabaseQueries(private val queries: SteleDatabaseQueries, private val driver: SqlDriver)
// Update the constructor call in SqlDelightBlockRepository accordingly.

@DirectSqlWrite
suspend fun insertWikilinkReferencesBatch(blockUuid: String, pageNames: Collection<String>) {
    if (pageNames.isEmpty()) return
    // Chunk at 499 pairs: each pair uses 2 bind params; SQLITE_MAX_VARIABLE_NUMBER = 999 (Requery).
    pageNames.chunked(MAX_WIKILINK_BATCH_SIZE).forEach { chunk ->
        val placeholders = chunk.joinToString(", ") { "(?, ?)" }
        val sql = "INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name) VALUES $placeholders"
        // SQLDelight 2.x binder: SqlPreparedStatement.() -> Unit, bindString is 0-based index.
        // identifier = null disables statement caching (required for variable-length SQL).
        driver.execute(null, sql, chunk.size * 2) {
            chunk.forEachIndexed { i, name ->
                bindString(i * 2, blockUuid)
                bindString(i * 2 + 1, name)
            }
        }.await()
    }
}

companion object {
    private const val MAX_WIKILINK_BATCH_SIZE = 499  // floor(999 / 2)
}
```

Note: `bindString` indices are 0-based in SQLDelight 2.x's `SqlPreparedStatement`. The binder lambda is receiver-based (no `idx` parameter) — `bindString(0, ...)`, `bindString(1, ...)` etc. Verify against both `AndroidSqliteDriver` and `JdbcSqliteDriver` before shipping.

#### Task 4.1.2: Replace the `addWikilinkRefs` loop in `SqlDelightBlockRepository`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

Change `addWikilinkRefs`:
```kotlin
@OptIn(DirectSqlWrite::class)
private suspend fun addWikilinkRefs(blockUuid: String, pageNames: Set<String>) {
    restricted.insertWikilinkReferencesBatch(blockUuid, pageNames)
}
```

Remove the `for (name in pageNames)` loop. The old `insertWikilinkReference` stub in `RestrictedDatabaseQueries` can remain for use in tests or single-insert paths, but `addWikilinkRefs` must use the batch path.

**Tests to verify**: `MigrationRunnerSchemaSyncTest`, `RepositoryFlowResilienceTest`, existing wikilink tests.

### Story 4.2: SQL trace test for batch insert count

**Acceptance**: Test asserts exactly 1 INSERT statement is issued when saving a block with 5 wikilinks. Test asserts chunking fires 2 INSERT statements for a block with 500 wikilinks (ceil(500/499) = 2 chunks).

#### Task 4.2.1: Add `WikilinkBatchInsertTest` in `businessTest`

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/WikilinkBatchInsertTest.kt` (new)

Use an in-memory `JdbcSqliteDriver` (available in `businessTest` via `sqlite-driver`) with a statement-counting wrapper. Insert a block with 5 page references. Assert INSERT count = 1. Insert a block with 500 page references. Assert INSERT count = 2. Assert `INSERT OR IGNORE` is used (not `INSERT OR REPLACE`).

---

## Epic 5: `WITHOUT ROWID` for `wikilink_references` (R2)

Independent from other epics: no dependency on position change or sealed class, but depends on Epic 1 being committed (baseline). Can run in parallel with Epics 2–4 after Epic 1 lands.

### Story 5.1: Migrate `wikilink_references` to `WITHOUT ROWID`

**Acceptance**: `EXPLAIN QUERY PLAN SELECT * FROM wikilink_references WHERE block_uuid = ?` shows a single B-tree traversal (no separate rowid lookup). `MigrationRunnerSchemaSyncTest` passes. `QueryPlanAuditTest` passes. FK cascade (`ON DELETE CASCADE`) still fires when a block is deleted.

#### Task 5.1.1: Update `SteleDatabase.sq` — add `WITHOUT ROWID` to `wikilink_references`

File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

Change the `wikilink_references` table declaration to add `WITHOUT ROWID` at the end:
```sql
CREATE TABLE wikilink_references (
    block_uuid TEXT NOT NULL,
    page_name  TEXT NOT NULL COLLATE NOCASE,
    PRIMARY KEY (block_uuid, page_name),
    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
) WITHOUT ROWID;
```

This is the target state for fresh installs (via `SteleDatabase.Schema.create(driver)`). The index declaration immediately after it remains:
```sql
CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE);
```

**Tests to verify**: SQLDelight code generation (`generateSqlDelightInterface`).

#### Task 5.1.2: Add `wikilink_references_without_rowid` migration to `MigrationRunner.all`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

Append after the existing `wikilink_references_table` migration:

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
        "INSERT OR IGNORE INTO wikilink_references_new SELECT block_uuid, page_name FROM wikilink_references",
        "DROP TABLE IF EXISTS wikilink_references",
        "ALTER TABLE wikilink_references_new RENAME TO wikilink_references",
        // Recreate the secondary index with the canonical name AFTER renaming the table.
        // SQLite renames indexes along with the table only in some versions; explicitly
        // drop the _new-suffixed index (if it survived the rename) and recreate canonical.
        "DROP INDEX IF EXISTS idx_wikilink_refs_page_name_new",
        "CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)",
        "PRAGMA foreign_keys=ON",
    )
)
```

Note: `INSERT OR IGNORE` handles any hypothetical duplicate PK rows that violate the new strict WITHOUT ROWID constraint. `DROP TABLE IF EXISTS` (not `DROP TABLE`) allows the migration to be safely retried if a previous run was interrupted after CREATE but before DROP.

**Tests to verify**: `MigrationRunnerSchemaSyncTest` — the test checks that every `CREATE TABLE IF NOT EXISTS` name in `SteleDatabase.sq` appears in `MigrationRunner.all`. The new `wikilink_references_new` table in the migration is a transient rename; the final name `wikilink_references` is already covered by the existing `wikilink_references_table` migration entry. No change needed to the sync test.

#### Task 5.1.3: Add FK cascade assertion to migration integration tests

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/WithoutRowidMigrationTest.kt` (new)

Test: run `MigrationRunner.applyAll()` on a fresh in-memory DB with seed data (1 block, 2 wikilink_references rows). Delete the block. Assert both wikilink_references rows are deleted (FK cascade fired). Assert `EXPLAIN QUERY PLAN SELECT * FROM wikilink_references WHERE block_uuid = ?` output does not contain `SCAN wikilink_references` (i.e., uses the PK B-tree). Assert `QueryPlanAuditTest.QUERIES` coverage for `selectWikilinkPageNamesForBlocks` still passes.

#### Task 5.1.4: Run `ANALYZE` after migration on existing databases

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

The `wikilink_references_without_rowid` migration recreates the table, which resets SQLite statistics for `idx_wikilink_refs_page_name`. Add an `ANALYZE wikilink_references` call to the end of `MigrationRunner.applyAll()` (the post-migration unconditional ANALYZE section), or add it as a separate migration entry:

```kotlin
Migration(
    name = "analyze_wikilink_references_post_without_rowid",
    statements = listOf("ANALYZE wikilink_references")
)
```

---

## Epic 6: Fractional String Positions (R1)

Independent from other epics: no dependency on Epics 2–5 (those are purely logical/query changes). This is the largest schema migration; do last among the schema changes.

**ADR-013**: Document choice of `rocicorp/fractional-indexing` (KMP library `com.davidarvelo:fractional-indexing`) over large-gap integers and float midpoints. Key rationale: Logseq DB version uses the same algorithm; never rebalances; column type change from INTEGER to TEXT is required but `ORDER BY position` and all existing indexes are preserved post-migration. `COLLATE NOCASE` must NOT be applied to the position column.

### Story 6.1: Implement `FractionalIndexing` inline utility

**Acceptance**: `FractionalIndexing.generateKeyBetween(null, null)` compiles and returns `"a0"` in `commonMain` code. Unit test verifies key ordering and midpoint generation.

#### Task 6.1.1: Implement `FractionalIndexing` utility in `commonMain`

No KMP-compatible library artifact for the `rocicorp/fractional-indexing` algorithm has been confirmed on Maven Central. Implement the ~100-line algorithm inline — the algorithm is well-specified, CC0-licensed, and small enough to maintain directly.

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/FractionalIndexing.kt` (new)

Implement the standard `rocicorp/fractional-indexing` algorithm (base-62 lexicographic midpoint). Key operations:
- `generateKeyBetween(a: String?, b: String?): String` — returns a key that sorts between a and b; a=null means start of list, b=null means end of list
- Keys use digits `0-9A-Za-z` in that order; an 'a' prefix followed by base-62 mantissa
- Initial key (null, null) → `"a0"`

Reference implementation: https://github.com/rocicorp/fractional-indexing (JS, ~150 lines; the algorithm is purely string manipulation and has no platform dependencies)

**Tests to verify**: Add `FractionalIndexingTest` in `businessTest` covering: `generateKeyBetween(null, null) == "a0"`, key between two keys sorts correctly under Kotlin string comparison, 1000 successive splits never collide.

### Story 6.2: Migrate `blocks.position` from `INTEGER` to `TEXT`

**Acceptance**: After migration, `SELECT position FROM blocks ORDER BY position` returns rows in the same relative order as before (block ordering preserved). Zero `UPDATE blocks SET position = ?` statements fired during a block insert in the middle of a 500-block page (no more shift). `QueryPlanAuditTest` passes (existing indexes still used — only column type changes, index structure unchanged).

#### Task 6.2.1: Update `SteleDatabase.sq` — change `position INTEGER` to `position TEXT`

File: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

In the `blocks` table declaration, change:
```sql
position INTEGER NOT NULL,
```
to:
```sql
position TEXT NOT NULL,
```

This is the target state for fresh installs. All `ORDER BY position` queries, `idx_blocks_page_position`, and `idx_blocks_parent_position` remain unchanged — SQLite TEXT comparison is used instead of INTEGER comparison. Both orderings are correct for base-62 fractional index strings under BINARY collation (default; no `COLLATE` clause on the column).

**No changes needed to**: any query that reads `position`, any index definition, any `ORDER BY position` clause. The generated Kotlin type for `position` changes from `Long` to `String` — this will produce compile errors at all Kotlin call sites. Fix those in Task 6.2.3.

#### Task 6.2.2: Add `blocks_position_fractional_index` migration to `MigrationRunner.all`

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

Append after `wikilink_references_without_rowid` (or the last existing migration):

```kotlin
Migration(
    name = "blocks_position_fractional_index",
    statements = listOf(
        // Step 1: add a new TEXT column alongside the existing INTEGER column
        "ALTER TABLE blocks ADD COLUMN position_text TEXT",
        // Step 2: populate position_text from existing integer positions in relative order.
        // Uses printf('%011d', ...) to zero-pad integer positions into 11-digit strings —
        // valid on SQLite 3.8+ (system SQLite on Android API 26 is 3.18; ROW_NUMBER() requires
        // 3.25+ and is NOT available on API 26 system SQLite, so we avoid it here).
        // Zero-padded decimals sort correctly under BINARY lex order for positions up to 99999999999.
        // FractionalIndexing.generateKeyBetween() produces keys that sort correctly before/after
        // these initial values since base-62 'a'-prefix keys sort after '9' in ASCII order.
        // The Kotlin FractionalIndexing utility must be aware of this initial key space.
        "UPDATE blocks SET position_text = printf('%011d', CAST(position AS INTEGER)) WHERE position_text IS NULL",
        // Step 3: recreate blocks table with position as TEXT (SQLite cannot ALTER COLUMN type).
        // NOTE: This recreation must preserve: id AUTOINCREMENT (FTS5 content_rowid),
        // all FOREIGN KEY constraints, FTS5 triggers.
        "PRAGMA foreign_keys=OFF",
        """
        CREATE TABLE IF NOT EXISTS blocks_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT NOT NULL UNIQUE,
            page_uuid TEXT NOT NULL,
            parent_uuid TEXT,
            left_uuid TEXT,
            content TEXT NOT NULL,
            level INTEGER NOT NULL DEFAULT 0,
            position TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            properties TEXT,
            version INTEGER NOT NULL DEFAULT 0,
            content_hash TEXT,
            block_type TEXT NOT NULL DEFAULT 'bullet',
            FOREIGN KEY (page_uuid) REFERENCES pages(uuid) ON DELETE CASCADE,
            FOREIGN KEY (parent_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE,
            FOREIGN KEY (left_uuid) REFERENCES blocks(uuid) ON DELETE SET NULL
        )
        """,
        "INSERT INTO blocks_new SELECT id, uuid, page_uuid, parent_uuid, left_uuid, content, level, COALESCE(position_text, printf('%011d', position)), created_at, updated_at, properties, version, content_hash, block_type FROM blocks",
        "DROP TABLE blocks",
        "ALTER TABLE blocks_new RENAME TO blocks",
        // Step 4: recreate all indexes (dropped when the table was dropped)
        "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)",
        "CREATE INDEX IF NOT EXISTS idx_blocks_parent_position ON blocks(parent_uuid, position)",
        "CREATE INDEX IF NOT EXISTS idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash)",
        "CREATE INDEX IF NOT EXISTS idx_blocks_left_uuid ON blocks(left_uuid)",
        "CREATE INDEX IF NOT EXISTS idx_blocks_content_hash ON blocks(content_hash)",
        // Step 5: recreate FTS5 triggers (dropped with the blocks table)
        // blocks_fts virtual table itself is NOT dropped (it has its own B-tree).
        // Recreate triggers that link blocks → blocks_fts.
        "DROP TRIGGER IF EXISTS blocks_ai",
        "DROP TRIGGER IF EXISTS blocks_ad",
        "DROP TRIGGER IF EXISTS blocks_au",
        """
        CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks BEGIN
            INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks BEGIN
            INSERT INTO blocks_fts(blocks_fts, rowid, content) VALUES ('delete', old.id, old.content);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE OF content ON blocks BEGIN
            INSERT INTO blocks_fts(blocks_fts, rowid, content) VALUES ('delete', old.id, old.content);
            INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
        END
        """,
        "PRAGMA foreign_keys=ON",
        // Step 6: refresh statistics for the renamed table
        "ANALYZE blocks",
    )
)
```

**Critical**: verify the FTS5 trigger SQL matches the exact form used in the `fix_blocks_au_trigger_content_only` migration (UPDATE OF content only). The triggers above follow that pattern.

**Critical**: the `blocks_fts` virtual table has `content_rowid=id` in its definition. After table recreation, the `id` column is still AUTOINCREMENT and the same rowid values are preserved (INSERT INTO blocks_new SELECTs the existing `id` column). FTS5 content table consistency is maintained.

**Tests to verify**: `MigrationRunnerSchemaSyncTest` — the sync test reads `CREATE TABLE IF NOT EXISTS` names; `blocks_new` is transient. The existing `blocks` entry is covered by the initial `SteleDatabase.Schema.create()`. No change needed to the sync test.

#### Task 6.2.3: Fix Kotlin call sites — `position` type changes from `Long`/`Int` to `String`

Files to update after SQLDelight regeneration:

1. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`: `Block.position` changes from `Int` to `String`. Remove `require(position >= 0)` guard. Add a `require(position.isNotBlank())` guard.

2. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`: `insertBlock` and `updateBlockForSave` stubs — change `position: Long` to `position: String`.

3. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`: every call site that passes a position (block construction, insert calls, move/indent operations). This is the most impactful change.

4. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`: block construction during file parse assigns initial position values. Replace integer position assignment with `FractionalIndexing.generateKeyBetween(prevKey, null)` or equivalent.

5. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`: file serialization — `position` is not written to the `.md` file (Logseq files use `left_uuid` for ordering). No change needed to write path.

Search comprehensively: `grep -rn "\.position" kmp/src/commonMain/kotlin/` to find all read sites. `grep -rn "position = " kmp/src/commonMain/kotlin/` to find all write sites.

**Explicitly in scope** — these methods all contain integer position arithmetic that must be converted to `String` keys. Each currently computes `block.position + 1`, `leftNeighbor.position + 1`, or similar. Replace with `FractionalIndexing.generateKeyBetween(leftKey, rightKey)` where appropriate:
- `indentBlock` — assigns new position to an indented block
- `outdentBlock` — reassigns position after unindenting
- `moveBlock` / `moveBlockUp` / `moveBlockDown` — swaps positions or inserts between neighbors
- `mergeBlocks` — assigns position to merged children
- `splitBlock` — assigns position to the newly created block after the split point

#### Task 6.2.4: Replace position integer arithmetic with `FractionalIndexing` library calls

File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

Replace all usages of `(left.position + right.position) / 2` and similar arithmetic with:
```kotlin
FractionalIndexing.generateKeyBetween(leftKey, rightKey)
```
where `leftKey` and `rightKey` are the `String` position values of the left and right neighbors (or `null` for start/end of list).

Replace `shiftRootBlockPositionsFrom` / `shiftChildBlockPositionsFrom` usages: these SQL `UPDATE` statements that shift integer positions are now obsolete. Remove calls to them in `insertBlock`-related code paths. Keep the SQL queries in `SteleDatabase.sq` temporarily (they can be deleted in a follow-up cleanup once confirmed unused).

**Note on `left_uuid` vs `position` for determining neighbors**: The `left_uuid` chain gives the linked-list predecessor. For computing the fractional midpoint of a new block, query `ORDER BY position` to find the immediate left and right neighbors of the insertion point. Alternatively, use `left_uuid` to find the left neighbor and the block whose `left_uuid = newBlock.uuid` (would be the right neighbor). The `ORDER BY position` approach is simpler and uses the index.

#### Task 6.2.5: Update `QueryPlanAuditTest` for TEXT position column

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt`

After migration, `idx_blocks_page_position` and `idx_blocks_parent_position` still exist with the same name, but the `position` column is now TEXT. The query plans for `selectBlocksByPageUuidUnpaginated`, `selectBlocksByParentUuidOrdered`, `selectBlockChildren`, `selectLastChild` etc. must still show index usage (not SCAN blocks). Re-run `QueryPlanAuditTest` and update any `ALLOWLIST` entries if query plans change unexpectedly. Most plans should be identical since the index structure is unchanged.

#### Task 6.2.6: Add zero-shift assertion test

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/FractionalPositionTest.kt` (new)

Test: insert 500 blocks sequentially into a page. Then insert 1 block in the middle of the list. Assert 0 `UPDATE blocks SET position = ?` statements were issued during the last insert (use a statement-counting driver spy). Assert the new block appears in the correct position when queried with `ORDER BY position`. Assert `FractionalIndexing.generateKeyBetween` was called once and its result sorts correctly between the two neighbors.

---

## Epic 7: WAL Connection Pool / `FrameworkSQLiteOpenHelperFactory` (R6)

Independent from schema changes: yes — this is an Android driver change only. However, do this last because it is the highest-risk change and the other epics may reduce write latency enough that R6 is no longer the bottleneck.

**ADR-015**: Document the decision to replace `RequerySQLiteOpenHelperFactory` with `FrameworkSQLiteOpenHelperFactory` + `enableWriteAheadLogging()`. Key tradeoffs: gain true multi-connection read concurrency; risk losing Requery-specific SQLite features (bundled 3.49 → system SQLite). Audit required before switching.

### Story 7.1: Audit Requery-specific features before switching

**Acceptance**: A written audit (as a comment in the ADR or in this story's notes) confirms which Requery-specific features are or are not used. Decision: proceed only if no Requery-specific extensions are relied upon.

#### Task 7.1.1: Audit Requery-specific SQLite features

File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`

Check each of the following:
1. `ANDROID_PRAGMAS` list — all pragmas use standard SQLite syntax; verify each is supported by the Android system SQLite (API 26+, SQLite 3.18+). `WITHOUT ROWID` requires 3.8.2; `WITH RECURSIVE` requires 3.8.3; `UPDATE ... FROM` requires 3.35 — system SQLite on API 26 is 3.18, which DOES NOT support `UPDATE ... FROM`. If R1's migration uses `UPDATE ... FROM`, it requires Requery's bundled 3.45 on any Android version.
2. `WalConfiguredCallback` — any use of `SupportSQLiteDatabase` methods specific to Requery (e.g. `rawQuery` path for PRAGMAs that Requery requires). With `FrameworkSQLiteOpenHelperFactory`, `SupportSQLiteDatabase` wraps `android.database.sqlite.SQLiteDatabase` — the `rawQuery` path should work identically.
3. `REAL_ONLY_CURSORS` or other Requery connection properties — check `DriverFactory.android.kt` for any Requery-specific configuration.

**If `UPDATE ... FROM` (SQLite 3.35) is used in R1's migration**: the `blocks_position_fractional_index` migration must not switch to `FrameworkSQLiteOpenHelperFactory` until either (a) the migration SQL is rewritten to avoid `UPDATE ... FROM`, or (b) minSdkVersion is bumped to an API level that bundles SQLite 3.35+. API 34 (Android 14) bundles SQLite 3.42; API 33 (Android 13) bundles 3.39. If minSdk is 26, Requery must be kept or the migration SQL must be rewritten.

This audit may conclude that R6 is blocked by the R1 migration SQL dependency. Document the finding and defer R6 if necessary.

#### Task 7.1.2: Check system SQLite version on minSdk

File: `androidApp/build.gradle.kts` or `kmp/build.gradle.kts`

Read `minSdk`. Cross-reference against the [SQLite version bundled per Android API level table](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase). Document in ADR-015.

### Story 7.2: Implement `FrameworkSQLiteOpenHelperFactory` + `enableWriteAheadLogging`

Proceed only if Story 7.1 confirms no blockers.

**Acceptance**: Under a simulated write (bulk import of 1000 blocks), concurrent `getBlockByUuid` calls return within 50ms without waiting for the write lock. WAL file does not grow unboundedly during a 30-second bulk-import test.

#### Task 7.2.1: Replace `RequerySQLiteOpenHelperFactory` with `FrameworkSQLiteOpenHelperFactory`

File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`

Change import and factory construction:
```kotlin
// Remove:
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
// Add:
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
```

In `AndroidSqliteDriver(...)` calls, change:
```kotlin
factory = RequerySQLiteOpenHelperFactory()
```
to:
```kotlin
factory = FrameworkSQLiteOpenHelperFactory()
```

#### Task 7.2.2: Enable write-ahead logging on the `SQLiteOpenHelper`

File: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`

`AndroidSqliteDriver` accepts a callback that fires in `onConfigure`. The existing `WalConfiguredCallback` already issues `PRAGMA journal_mode=WAL` via `rawQuery`. With `FrameworkSQLiteOpenHelperFactory`, additionally call `enableWriteAheadLogging()` on the `SupportSQLiteDatabase` in `onConfigure`:

```kotlin
class WalConfiguredCallback(schema: SqlSchema) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        db.enableWriteAheadLogging()  // Activates Android's native multi-connection pool
        super.onConfigure(db)
        ANDROID_PRAGMAS.forEach { pragma ->
            db.query(pragma)  // rawQuery path for PRAGMAs
        }
    }
}
```

Note: `enableWriteAheadLogging()` implicitly sets `PRAGMA journal_mode=WAL`, making the explicit PRAGMA redundant — but keeping it is harmless and preserves the intent for other drivers.

#### Task 7.2.3: Remove or replace Requery-specific dependency

File: `kmp/build.gradle.kts`

Only remove `com.github.requery:sqlite-android` if Story 7.1 confirms it is no longer needed anywhere. If any part of the codebase still references Requery types, keep the dependency.

#### Task 7.2.4: Add WAL concurrency integration test

File: `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/WalConcurrencyTest.kt` (new instrumented test)

Test: start a long-running bulk insert of 1000 blocks on a background coroutine. Concurrently issue 10 `getBlockByUuid` calls on a different coroutine. Assert each read completes in < 100ms. Assert no `SQLITE_BUSY` or `SQLITE_BUSY_SNAPSHOT` errors are logged.

**Note**: this test requires an instrumented (on-device) test — it cannot run in `jvmTest` because the Android-specific connection pool is not available in the JVM driver.

---

## Summary

| Epic | Stories | Tasks | ADR | Risk |
|---|---|---|---|---|
| 1: Phase 1.5 commit | 1 | 2 | — | Low |
| 2: BlockType sealed class | 3 | 9 | ADR-014 | Medium |
| 3: getBlockHierarchy CTE | 2 | 5 | — | Low |
| 4: Batch wikilink inserts | 2 | 3 | — | Low |
| 5: WITHOUT ROWID | 1 | 4 | — | Medium |
| 6: Fractional positions | 2 | 7 | ADR-013 | High |
| 7: WAL connection pool | 2 | 5 | ADR-015 | High |

**Total**: 7 epics, 13 stories, 35 tasks.

**ADRs to write**: ADR-013 (fractional indexing), ADR-014 (blockType sealed class), ADR-015 (WAL connection pool).

## Key Pre-Implementation Checks

Before starting Epic 6, confirm:
- Does the position migration's `UPDATE ... FROM` CTE syntax require SQLite 3.35? If so, Requery must remain until Epic 7 (which switches away from it) is committed first — or rewrite the migration to avoid `UPDATE ... FROM`.
- Verify `com.davidarvelo:fractional-indexing` is available on Maven Central and its KMP target includes `commonMain`.

Before starting Epic 7, confirm:
- Story 7.1 audit is complete and documented in ADR-015.
- minSdk Android API level supports system SQLite ≥ 3.35 OR the R1 migration SQL has been rewritten.

## Regression Test Coverage Required Before Each Epic Ships

| Epic | Required green tests |
|---|---|
| 1 | `ciCheck` full run |
| 2 | `BlockTypeTest`, `ciCheck`, `LargeGraphWarmStartCrashTest` |
| 3 | `BlockHierarchyCteTest`, `QueryPlanAuditTest`, existing hierarchy/screenshot tests |
| 4 | `WikilinkBatchInsertTest`, `RepositoryFlowResilienceTest` |
| 5 | `WithoutRowidMigrationTest`, `QueryPlanAuditTest`, `MigrationRunnerSchemaSyncTest` |
| 6 | `FractionalPositionTest`, `QueryPlanAuditTest`, `LargeGraphWarmStartCrashTest` |
| 7 | `WalConcurrencyTest` (instrumented), `ciCheck` |
