# Adversarial Review: Block Operations Performance — Phase 2 Implementation Plan

**Verdict: CONCERNS** *(updated — all three blockers addressed in plan.md patch 2026-06-20)*

~~BLOCKED~~ — three blockers were identified and patched in plan.md before Phase 4 validation:
1. `com.davidarvelo:fractional-indexing` → replaced with inline `FractionalIndexing.kt` implementation (Task 6.1.1)
2. `ROW_NUMBER()` → replaced with `printf('%011d', CAST(position AS INTEGER))` (Task 6.2.2, valid on SQLite 3.8+)
3. CTE `ORDER BY depth, position` → fixed to `ORDER BY depth, parent_uuid, position` (Task 3.1.1)
Additional patches: `getBlockHierarchy` return type preserved as `Flow<...>`, WITHOUT ROWID index naming fixed, batch wikilink binder API corrected, integer position methods explicitly scoped in Task 6.2.3.

The plan had three blockers that must be resolved before implementation begins, plus several serious concerns requiring explicit attention during implementation. The blockers are: (1) the `com.davidarvelo:fractional-indexing` dependency does not exist on Maven Central and is unverifiable, (2) the Epic 6 migration SQL uses `UPDATE ... FROM` CTE syntax that is incompatible with the stated minSdk=26 (Android SQLite 3.18) **without** Requery — and the migration-ordering constraint between Epic 6 and Epic 7 creates a sequencing deadlock the plan does not resolve, and (3) the `blocks_new` self-referential foreign key in the migration is wrong and will break the migration on any non-empty database.

---

## Blocker 1: `com.davidarvelo:fractional-indexing` does not exist on Maven Central

**Epic 6, Story 6.1, Task 6.1.1**

The plan says to add `implementation("com.davidarvelo:fractional-indexing:<latest-version>")` and to "verify it is KMP-compatible." This library does not appear in the codebase (`kmp/build.gradle.kts` has zero matches for `fractional`, `davidarvelo`, or `fractional-indexing`). The project's `settings.gradle.kts` includes Maven Central, JitPack, Sonatype Snapshots, and Clojars as repositories. There is no publicly documented `com.davidarvelo:fractional-indexing` artifact on any of these repositories that can be verified against the codebase.

The rocicorp/fractional-indexing algorithm has several JavaScript/TypeScript ports and a few Rust ports. No confirmed KMP-compatible Kotlin/Multiplatform library for this algorithm is established in the ecosystem as of this review. The Logseq DB version uses a JavaScript implementation; it does not publish a KMP artifact.

**Required fix before Epic 6:** Either confirm the artifact exists (provide `groupId:artifactId:version` coordinates verified against Maven Central search) and that it ships a `commonMain` target, **or** implement the algorithm inline in `commonMain`. The algorithm itself is approximately 100 lines of Kotlin — a direct port from the reference implementation is safer than taking a dependency on an unverified library.

---

## Blocker 2: Epic 6 migration SQL is SQLite 3.35+ (`ROW_NUMBER()`, `UPDATE ... FROM`), but the Epic 7 sequencing constraint creates a deadlock

**Epic 6, Task 6.2.2; Epic 7, Story 7.1, Task 7.1.1**

The `blocks_position_fractional_index` migration uses:

```sql
WITH ranked AS (
    SELECT uuid,
           printf('%011d', CAST(
               (ROW_NUMBER() OVER (PARTITION BY page_uuid, parent_uuid ORDER BY position) - 1)
               AS INTEGER
           )) AS new_pos
    FROM blocks
)
UPDATE blocks
SET position_text = (SELECT new_pos FROM ranked WHERE ranked.uuid = blocks.uuid)
WHERE position_text IS NULL
```

This is a correlated `UPDATE ... FROM` CTE construct. `ROW_NUMBER()` window functions require SQLite 3.25+. The subquery-style `UPDATE ... (SELECT ... FROM cte)` is valid in older SQLite, but the CTE approach with `UPDATE ... FROM` (explicit table reference in the FROM clause) requires SQLite 3.35+.

The actual construct written here — `UPDATE blocks SET ... WHERE position_text IS NULL` with a correlated subquery `(SELECT new_pos FROM ranked WHERE ranked.uuid = blocks.uuid)` — is **not** `UPDATE ... FROM` syntax; it is a correlated subquery `UPDATE`, which IS valid in SQLite 3.8.3+. The `ROW_NUMBER()` window function, however, requires SQLite 3.25+ (released 2018-09-15).

**Android API 26 (minSdk) bundles SQLite 3.18** (released 2017-01-06). SQLite 3.18 does not support `ROW_NUMBER()`. Running this migration on a non-Requery Android installation (i.e., after Epic 7 switches to `FrameworkSQLiteOpenHelperFactory`) on API 26–28 devices will throw `SQLITE_ERROR: no such function: ROW_NUMBER`.

The plan acknowledges this in Task 7.1.1 and says:
> "If R1's migration uses `UPDATE ... FROM`, it requires Requery's bundled 3.45 on any Android version."

And in the Key Pre-Implementation Checks:
> "Does the position migration's `UPDATE ... FROM` CTE syntax require SQLite 3.35? If so, Requery must remain until Epic 7 (which switches away from it) is committed first — or rewrite the migration to avoid `UPDATE ... FROM`."

But the plan then says Epic 7 must come **after** Epic 6, because "do this last — it is the highest-risk change." This creates a sequencing deadlock:

- Epic 6 migration requires Requery (for `ROW_NUMBER()` on API 26-28 devices)
- Epic 7 removes Requery
- Epic 7 must come after Epic 6 (plan explicitly says so)
- But once Epic 7 ships, the Epic 6 migration has already run on all devices that have been updated to the Epic 6 release — it already used Requery when it ran, so that is fine
- **However:** devices that skipped the Epic 6 release and update directly to the Epic 7 release will attempt to run the Epic 6 migration **after** Requery has been removed, on system SQLite 3.18, and `ROW_NUMBER()` will fail

The MigrationRunner swallows errors and marks the migration as applied even on failure (it records the hash on "real error" only after checking `encounteredRealError`). Actually, re-reading the code: if a real error is encountered, `encounteredRealError = true` and the migration is NOT recorded (`if (encounteredRealError) continue` skips the INSERT). So devices that jump from pre-Epic-6 to post-Epic-7 will have the Epic 6 migration fail silently on the `ROW_NUMBER()` step, leave `position_text` as NULL on all rows, then proceed to try to rename `blocks_new` to `blocks` — but `blocks_new.position` column gets `COALESCE(position_text, printf('%011d', position))` which will work for the TEXT coalesce but `position_text` will be NULL for all rows because step 2 failed. The migration will partially apply, leaving `blocks.position` as TEXT but with all positions as `printf('%011d', integer)` values — which is actually fine for ordering. But it depends on whether the `ROW_NUMBER()` failure aborts the entire migration or just that one statement.

Looking at MigrationRunner: each SQL statement in a migration is executed independently. If `ROW_NUMBER()` throws, `encounteredRealError = true`, the loop `break`s, and the migration is NOT recorded. On next startup, the migration retries — but `blocks_new` may already exist (from the `CREATE TABLE IF NOT EXISTS blocks_new` step before the failed `ROW_NUMBER()` step), so the next run skips `CREATE TABLE IF NOT EXISTS` (idempotent), then fails again on `ROW_NUMBER()`, and so on forever. The database is in a partially migrated, perpetually-retrying state.

**Required fix:** Either (a) rewrite the position population step to not use `ROW_NUMBER()` (use a simpler but less perfect scheme like `printf('%011d', position)` which already works and can be done with a plain `UPDATE blocks SET position_text = printf('%011d', CAST(position AS INTEGER)) WHERE position_text IS NULL` — no window functions needed), or (b) gate Epic 7 with an explicit check that the Epic 6 migration has been applied on this device before proceeding. Option (a) is far simpler and the quality difference is negligible (the initial zero-padded decimal assignment is functionally identical to the `ROW_NUMBER()` rank-based approach for normally-ordered data).

The `ROW_NUMBER() OVER (PARTITION BY page_uuid, parent_uuid ORDER BY position)` approach is strictly better (handles gaps in the original integer positions), but using `printf('%011d', CAST(position AS INTEGER))` directly loses nothing in practice because Logseq generates dense sequential integers anyway. The simpler version is:

```sql
UPDATE blocks SET position_text = printf('%011d', CAST(position AS INTEGER)) WHERE position_text IS NULL
```

This removes the only SQLite 3.25+ requirement from the migration.

---

## Blocker 3: `blocks_new` self-referential foreign key references `blocks` (the old table) which is dropped before rename

**Epic 6, Task 6.2.2**

The migration creates `blocks_new` with:

```sql
FOREIGN KEY (parent_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE,
FOREIGN KEY (left_uuid) REFERENCES blocks(uuid) ON DELETE SET NULL
```

These reference `blocks`, not `blocks_new`. After:
```sql
INSERT INTO blocks_new SELECT ...
DROP TABLE blocks
ALTER TABLE blocks_new RENAME TO blocks
```

At the moment `DROP TABLE blocks` executes, `PRAGMA foreign_keys` is OFF (correctly), so SQLite will not raise an error during the migration. After `ALTER TABLE blocks_new RENAME TO blocks` and `PRAGMA foreign_keys=ON`, SQLite's FK resolver will look up `REFERENCES blocks(uuid)` — which now resolves to the renamed table. **This is actually fine in SQLite**: after the rename, `REFERENCES blocks(uuid)` in the renamed table's schema text still says `blocks`, and `blocks` now refers to itself. SQLite does not validate FK constraint text during DDL; it resolves names at query time.

However, there is a subtler issue: the `blocks_new` DDL as written says `FOREIGN KEY (parent_uuid) REFERENCES blocks(uuid)`. During the time window between `CREATE TABLE IF NOT EXISTS blocks_new` and `DROP TABLE blocks`, there are **two tables named `blocks` and `blocks_new`**, and the FK in `blocks_new` references the original `blocks`. The `INSERT INTO blocks_new SELECT ...` populates `blocks_new` with rows whose `parent_uuid` values reference `blocks.uuid`. With `PRAGMA foreign_keys=OFF`, this is fine. After the rename, the FK in the schema text (`blocks_new`'s DDL) says `REFERENCES blocks(uuid)` — once renamed to `blocks`, this becomes a self-referential FK, which is correct and is how the original table was declared.

This is NOT a blocker in practice because `PRAGMA foreign_keys=OFF` during the migration prevents any FK check failures, and the rename correctly produces a self-referential FK. Removing this from blocker status — see it under Concerns instead.

**Revised: This is a CONCERN, not a blocker.** The FK reference during migration is safe because FK enforcement is disabled. After rename, it correctly becomes self-referential.

---

## Serious Concerns (Addressable During Implementation)

### Concern 1: `PRAGMA foreign_keys=OFF` is connection-scoped and races with concurrent connections on JVM

**Epic 5 Task 5.1.2; Epic 6 Task 6.2.2**

Both migrations use `PRAGMA foreign_keys=OFF` / `ON` framing. On the JVM, `DriverFactory` creates a `PooledJdbcSqliteDriver` with 8 connections. `PRAGMA foreign_keys=OFF` is connection-scoped in SQLite: it affects only the connection that issues it, not other connections in the pool.

The MigrationRunner executes via `driver.execute(null, sql, 0)`. On the pooled JVM driver, this may dispatch each statement to a different connection from the pool. If `PRAGMA foreign_keys=OFF` runs on connection A, then `DROP TABLE blocks` runs on connection B (which still has FKs enabled), the DROP may fail due to FK constraint violations if any other table references `blocks` — and `wikilink_references` does reference `blocks.uuid` with ON DELETE CASCADE.

`PRAGMA foreign_keys=OFF` before `DROP TABLE blocks` (the original `blocks` table) is necessary because `wikilink_references.block_uuid REFERENCES blocks(uuid)`. If FK enforcement is ON and `blocks` is dropped, SQLite checks whether any table references it — and it would need to cascade. But `DROP TABLE` with FKs enabled throws `SQLITE_ERROR: FOREIGN KEY constraint failed` for referenced tables (this is SQLite's behavior: you cannot drop a table that is referenced by an FK in another table while FKs are on).

**Fix:** The MigrationRunner should wrap both migrations in an explicit `BEGIN EXCLUSIVE / COMMIT` and issue `PRAGMA foreign_keys=OFF` via a raw JDBC connection (for JVM) or use the SQLite `PRAGMA foreign_keys=OFF` inside a transaction. Alternatively, use a single JDBC connection from `sqliteDriver.getConnection()` for migration DDL, not the pooled dispatcher.

Looking at `MigrationRunner.applyAll`, all SQL is dispatched via `driver.execute(null, sql.trimIndent(), 0).await()`. The pooled JVM driver may or may not guarantee connection affinity across calls in a single coroutine. If the `PooledJdbcSqliteDriver` dispatches each `.execute()` call to a different connection, `PRAGMA foreign_keys=OFF` issued on one connection does not affect another.

**This is a real risk for the JVM tests (QueryPlanAuditTest, businessTest) which use the JVM driver.** Android uses a single-connection driver and is unaffected. The plan does not mention this at all.

### Concern 2: `blocks_new` foreign key still points to `blocks` during `INSERT INTO blocks_new SELECT ... FROM blocks`

When `blocks_new` is created with `FOREIGN KEY (parent_uuid) REFERENCES blocks(uuid)`, and then `INSERT INTO blocks_new SELECT ... FROM blocks` is executed with `PRAGMA foreign_keys=OFF`, this is fine. But if FK enforcement is still ON on the connection executing the INSERT (due to the pooled-connection race from Concern 1), and `parent_uuid` values in `blocks` reference `blocks.uuid` (they do — it is self-referential), the FK check would verify that every `parent_uuid` in `blocks_new` exists in `blocks.uuid`. Since we're copying all of `blocks` into `blocks_new`, all parent_uuid values that exist in `blocks.uuid` will satisfy this check. This is not a failure case.

After `DROP TABLE blocks` and rename, `blocks_new`'s FK text `REFERENCES blocks(uuid)` correctly refers to the renamed table itself. **This is not a blocker** — it is expected SQLite self-referential FK behavior.

### Concern 3: `WITHOUT ROWID` migration index name collision

**Epic 5, Task 5.1.2**

The migration creates `idx_wikilink_refs_page_name_new` as a temporary name for the new index. After `DROP TABLE wikilink_references` (which drops `idx_wikilink_refs_page_name`), the migration renames the table but does NOT rename the index. After `ALTER TABLE wikilink_references_new RENAME TO wikilink_references`, the index `idx_wikilink_refs_page_name_new` still has the `_new` suffix.

The `SteleDatabase.sq` schema declares `CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)`. On fresh installs, `SteleDatabase.Schema.create(driver)` creates `idx_wikilink_refs_page_name`. On migrated databases, the migration creates `idx_wikilink_refs_page_name_new` and leaves it named that way. The original `idx_wikilink_refs_page_name` name was dropped with the original table.

`QueryPlanAuditTest` for `selectWikilinkPageNamesForPage` and `selectWikilinkPageNamesForBlock` checks that `idx_wikilink_refs_page_name` is used in the query plan. On a migrated database, the planner would use `idx_wikilink_refs_page_name_new` — which works correctly (it has the same definition), but the test verifies the index name. If the test checks index name strings, this discrepancy will cause a false failure. The `QueryPlanAuditTest` checks for SCAN without USING — not the index name — so this may not fail the test. But the inconsistency between fresh and migrated schemas is a latent bug.

**Fix:** Add an additional step after the rename:
```sql
DROP INDEX IF EXISTS idx_wikilink_refs_page_name_new
CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)
```

Or use the final index name from the start in the migration and drop the old name after the table rename.

### Concern 4: CTE `ORDER BY depth, position` is incorrect for the recursive hierarchy — ordering is wrong

**Epic 3, Task 3.1.1**

The CTE query:

```sql
SELECT * FROM subtree
ORDER BY depth, position;
```

This sorts the entire result set by `(depth, position)` — meaning all depth-0 blocks come first (root only), then all depth-1 blocks, then all depth-2 blocks. Within each depth, blocks are sorted by their `position` among **all blocks at that depth across all parents**, not by their position within their respective parent.

For a tree:
```
A (position=0)
  A1 (position=0)
  A2 (position=1)
B (position=1)
  B1 (position=0)
  B2 (position=1)
```

`ORDER BY depth, position` produces: `A, B, A1, A2, B1, B2` — which is correct by position within each depth level only if positions are unique across all siblings at that depth. But the `position` column is relative within a parent group (two different parents can both have a child at position=0). The sort `ORDER BY depth, position` will interleave children of different parents in an unpredictable order.

The correct sort for a hierarchical display is `ORDER BY depth, parent_uuid, position`. Even better, the requirements doc says `ORDER BY level, position` — same issue.

The existing BFS loop in `getBlockHierarchy` uses `selectBlocksByParentUuids` which returns results `ORDER BY parent_uuid, position`, and the BFS processes them level by level with depth tracking. The CTE's `ORDER BY depth, position` does not replicate this — it sorts position globally across all parents at a given depth, not per-parent.

**This is a correctness bug**: the CTE will return blocks in a different order than the BFS loop for any page with more than one parent at a given depth level. Since the result is used to build a tree display, wrong ordering will scramble block display order.

**Fix:** Use `ORDER BY depth, parent_uuid, position` in the CTE final SELECT. This sorts by parent group within each depth level, which correctly sequences siblings within their parent.

### Concern 5: `getBlockHierarchy` is currently a `Flow`, but the plan's CTE replacement returns a `suspend fun` — API change

**Epic 3, Task 3.1.3**

The current `getBlockHierarchy` implementation wraps a `flow { ... }.flowOn(PlatformDispatcher.DB)` — it is a `Flow<Either<DomainError, List<BlockWithDepth>>>`. The plan's replacement uses `withContext(PlatformDispatcher.DB) { queries.selectBlockHierarchyRecursive(...).executeAsList() }` — which is a single-shot suspend call, not a reactive Flow.

The `BlockRepository` interface declares `getBlockHierarchy` as a `Flow`. The replacement must continue to return a `Flow`, not a `suspend fun`. The plan's implementation snippet:

```kotlin
override suspend fun getBlockHierarchy(rootUuid: BlockUuid): Either<DomainError, List<BlockWithDepth>> =
    withContext(PlatformDispatcher.DB) { ... }
```

This changes the function signature from `Flow<Either<...>>` to `suspend fun Either<...>` — a breaking API change. If the interface declares it as a `Flow`, this will not compile. The plan does not address this signature conflict.

**Fix:** The implementation must stay as `Flow { ... }.flowOn(DB)` wrapping the `executeAsList()` call, or the `BlockRepository` interface must be changed to `suspend fun` — which requires updating all callers (ViewModels, etc.).

### Concern 6: `binders` index in `insertWikilinkReferencesBatch` is wrong — starts at 0 but code adds `idx`

**Epic 4, Task 4.1.1**

The plan's batch insert code:

```kotlin
driver.execute(null, sql, chunk.size * 2) { idx ->
    chunk.forEachIndexed { i, name ->
        bindString(idx + i * 2, blockUuid)
        bindString(idx + i * 2 + 1, name)
    }
}
```

The `binders` lambda in SQLDelight's `SqlDriver.execute` receives a `SqlPreparedStatement`. The binder lambda receives the statement, not an index. The `idx` here is actually the lambda parameter name for the `SqlPreparedStatement` receiver — but looking at the SQLDelight driver API, the binder block is typed as `(SqlPreparedStatement) -> Unit`, and `bindString(index, value)` takes a 0-based index.

The binder lambda signature in `driver.execute(identifier, sql, parameters, binders)` is `binders: (SqlPreparedStatement.() -> Unit)?`. The lambda body binds using `bindString(0, ...)`, `bindString(1, ...)`, etc. There is no `idx` parameter passed to the lambda — the lambda is an extension function on `SqlPreparedStatement`.

The plan's code `{ idx -> chunk.forEachIndexed { i, name -> bindString(idx + i * 2, ...) } }` would not compile as written — `idx` would be a `SqlPreparedStatement` receiver, not an int. The correct form is:

```kotlin
driver.execute(null, sql, chunk.size * 2) {
    chunk.forEachIndexed { i, name ->
        bindString(i * 2, blockUuid)
        bindString(i * 2 + 1, name)
    }
}
```

**This is a compile error in the plan's sample code.** The implementation team needs to know the correct SQLDelight binder API before attempting this.

### Concern 7: `position` type change (INTEGER → TEXT) breaks multiple existing call sites not listed in the plan

**Epic 6, Task 6.2.3**

The plan lists files to update but is incomplete. The actual codebase has these additional call sites that use `position` as an integer:

1. **`SqlDelightBlockRepository.kt` line 700**: `otherSiblings.filter { it.uuid != block.uuid }.sortedBy { it.position }` in `moveBlock` — `it.position` is `Long` currently. After SQLDelight regeneration, it becomes `String`. `sortedBy { it.position }` on `String` produces lexicographic sort, which is correct for fractional index strings but must be verified to not break the `moveBlock` algorithm that passes `newPosition: Int` as an integer to `updateBlockHierarchy`.

2. **`SqlDelightBlockRepository.kt` line 762**: `val newPosition = (lastChildOfNewParent?.position ?: -1L) + 1L` in `indentBlock` — arithmetic on a `Long` position. After Epic 6, `position` is `String`. This line will not compile and must be replaced with `FractionalIndexing.generateKeyBetween(lastChildOfNewParent?.position, null)`.

3. **`SqlDelightBlockRepository.kt` line 806**: `val newPosition = currentParent.position + 1L` in `outdentBlock` — same issue.

4. **`SqlDelightBlockRepository.kt` line 852, 855**: `queries.updateBlockHierarchy(... prevSibling.position ..., block.position ...)` in `moveBlockUp` — passes `Long` position values to `updateBlockHierarchy`. After Epic 6, `updateBlockHierarchy` takes `String position`.

5. **`SqlDelightBlockRepository.kt` line 890, 893**: Same in `moveBlockDown`.

6. **`SqlDelightBlockRepository.kt` line 939-943**: `var nextPosition = (initialLastChild?.position ?: -1L) + 1L` and `nextPosition++` in `mergeBlocks` — integer arithmetic on position.

7. **`SqlDelightBlockRepository.kt` line 990, 1033**: `val newPosition = block.position + 1L` and `position = newPosition.toInt()` in `splitBlock` — integer arithmetic.

8. **`RestrictedDatabaseQueries.kt`**: `updateBlockParentPositionAndLevel(position: Long, ...)`, `updateBlockHierarchy(position: Long, ...)`, `updateBlockPositionOnly(position: Long, ...)`, `shiftRootBlockPositionsFrom(fromPosition: Long)`, `shiftChildBlockPositionsFrom(fromPosition: Long)` — all take `Long` positions and will need to change to `String`. The plan mentions `insertBlock` and `updateBlockForSave` but not these five additional stubs.

9. **`Block.position: Int`** — the plan says to change to `String`, but `Block` is a `data class` with `val position: Int`. All places that construct `Block(...)` with `position = someInt` (including tests) need updating.

The plan's Task 6.2.3 says "Search comprehensively" but does not actually list these concrete locations from the real code. The omission of `indentBlock`, `outdentBlock`, `moveBlock`, `moveBlockUp`, `moveBlockDown`, `mergeBlocks`, and `splitBlock` position arithmetic is a gap — these are the hot-path operations that fractional indexing is specifically meant to improve, and they all have non-trivial position logic that must be rewritten.

### Concern 8: `MigrationRunnerSchemaSyncTest` and `blocks_new` transient table name

**Epic 6, Task 6.2.2**

The plan claims:
> "The sync test reads `CREATE TABLE IF NOT EXISTS` names; `blocks_new` is transient. The existing `blocks` entry is covered by the initial `SteleDatabase.Schema.create()`. No change needed to the sync test."

This is incorrect. `MigrationRunnerSchemaSyncTest` (per `CLAUDE.md`) reads all `CREATE TABLE IF NOT EXISTS <name>` in `SteleDatabase.sq` and asserts each appears in `MigrationRunner.all`. The `blocks_new` table does NOT appear in `SteleDatabase.sq` (it is only in the migration), so the sync test will NOT fail for it. The plan's claim is correct for this direction.

However, the check also reads `MigrationRunner.all` and looks for table names. If the test checks the inverse direction (every table in `MigrationRunner.all` must be in `SteleDatabase.sq`), `blocks_new` appearing in the migration but not in `SteleDatabase.sq` would cause a test failure. This depends on the exact implementation of `MigrationRunnerSchemaSyncTest`.

Given that the CLAUDE.md description says "it reads `SteleDatabase.sq`, extracts all `IF NOT EXISTS` table names, and asserts each appears in `MigrationRunner.all`" — it is unidirectional, so `blocks_new` in the migration is harmless. The plan's claim is correct. **This is not a concern.**

### Concern 9: `save_blocks_diff` bypass — `queries.updateBlockForSave(... block.blockType ...)` passes String directly after Epic 2

**Epic 2 + interaction with existing `saveBlocksDiff`**

In `SqlDelightBlockRepository.kt` line 382:
```kotlin
block.blockType,  // currently String
```

After Epic 2, `block.blockType` is `BlockType` (sealed class). This call site passes it directly to `queries.updateBlockForSave(... block_type: String ...)`. The generated `SteleDatabaseQueries` still expects a `String`. Without the fix from Task 2.1.5, this call site will fail to compile. The plan identifies this in Task 2.1.5 but does not list `saveBlocksDiff` specifically — it says "every call site that passes `block.blockType` as a string argument to `restricted.*` methods." Since `saveBlocksDiff` calls `queries.updateBlockForSave` directly (not via `restricted.*`), it may be missed.

Similarly, `saveBlocksUpdate` (line 460), `saveBlock` (line 487), `splitBlock` (line 1018, 1039), and `insertBlockRow` (line 411) all pass `block.blockType` or `block.block_type` as a String. These are not all clearly captured in the plan's "search" instruction.

### Concern 10: Epic 1 Phase 1.5 `selectWikilinkPageNamesForBlocks` is already in the working tree and in `QueryPlanAuditTest`

**Epic 1, Task 1.1.2**

The working tree already has `selectWikilinkPageNamesForBlocks` in `SteleDatabase.sq` (confirmed: line 712 in the actual file). It is also already in `QueryPlanAuditTest.kt` (confirmed: line 331). The plan's Task 1.1.2 says "If `MigrationRunnerSchemaSyncTest` fails because `selectWikilinkPageNamesForBlocks` introduces a new query shape, check whether `QueryPlanAuditTest` needs a new `AuditQuery` entry" — but this is already present. Epic 1 should not trigger either test failure. This is accurate as stated.

### Concern 11: `WITHOUT ROWID` and `UPDATE OR IGNORE wikilink_references` in Epic 5

**Epic 5 interaction with `updateWikilinkPageNameForRename`**

`SteleDatabase.sq` line 735:
```sql
UPDATE OR IGNORE wikilink_references SET page_name = :newName WHERE page_name = :oldName COLLATE NOCASE;
```

`WITHOUT ROWID` tables support `UPDATE OR IGNORE`. This is valid. No issue.

However, `WITHOUT ROWID` tables impose a restriction: if a column update would violate a UNIQUE constraint (i.e., the PK), SQLite must handle it differently for WITHOUT ROWID tables because there is no rowid-based rollback. `UPDATE OR IGNORE` on a WITHOUT ROWID table is correctly supported in SQLite 3.8.2+. No issue.

### Concern 12: Requery-bundled SQLite vs `driver.execute` in `insertWikilinkReferencesBatch`

**Epic 4, Task 4.1.1**

The plan calls `driver.execute(null, sql, chunk.size * 2)` in `RestrictedDatabaseQueries`. The `driver` field must be exposed in `RestrictedDatabaseQueries`. Currently, `RestrictedDatabaseQueries` only holds `private val queries: SteleDatabaseQueries` — the `SqlDriver` is NOT accessible from it. The plan says "verify that `queries.driver` (or equivalent) is accessible" — it is not. The `driver` field is held by `SqlDelightBlockRepository` as a nullable `private val driver: SqlDriver? = null`.

To call `driver.execute()` from `RestrictedDatabaseQueries`, either:
- Pass the `SqlDriver` to `RestrictedDatabaseQueries` constructor, or
- Access it via `queries.driver` — but `SteleDatabaseQueries` does not publicly expose `driver`

The SQLDelight-generated `SteleDatabaseQueries` extends `SqlQuery.Queries` which holds a `driver: SqlDriver` — but it is not a public property in the generated code. It is accessible via the `driver` field if the generated `SteleDatabaseQueries` class exposes it (this is version-dependent in SQLDelight 2.x).

**If `queries.driver` is not accessible, the entire Epic 4 batch insert approach requires constructor changes to `RestrictedDatabaseQueries` and `SqlDelightBlockRepository`.**

---

## Summary of Findings

| Finding | Severity | Epic | Resolution |
|---|---|---|---|
| `com.davidarvelo:fractional-indexing` does not exist/unverifiable | BLOCKER | 6 | Port algorithm inline or confirm artifact |
| `ROW_NUMBER()` requires SQLite 3.25+, incompatible with minSdk=26 system SQLite; creates Epic 6/7 sequencing deadlock | BLOCKER | 6, 7 | Rewrite migration step to `printf('%011d', CAST(position AS INTEGER))` without window functions |
| CTE `ORDER BY depth, position` sorts globally, not per-parent — wrong block order | BLOCKER | 3 | Change to `ORDER BY depth, parent_uuid, position` |
| `PRAGMA foreign_keys=OFF` is connection-scoped; JVM pooled driver may not honor it across statements | CONCERN | 5, 6 | Ensure migration DDL runs on single connection |
| `WITHOUT ROWID` migration leaves index named `idx_wikilink_refs_page_name_new` | CONCERN | 5 | Rename index in migration final step |
| `binders` lambda API in `insertWikilinkReferencesBatch` sample code is wrong — `idx` is not an int | CONCERN | 4 | Fix binder indexing to `bindString(i * 2, ...)` |
| `getBlockHierarchy` interface returns `Flow<...>`, plan's replacement is `suspend fun` — compile error | CONCERN | 3 | Keep `flow { ... }.flowOn(DB)` wrapper |
| `indentBlock`, `outdentBlock`, `moveBlockUp`, `moveBlockDown`, `mergeBlocks`, `splitBlock` position arithmetic not listed | CONCERN | 6 | Add these to Task 6.2.3 scope |
| `RestrictedDatabaseQueries` does not have access to `SqlDriver` for raw execute | CONCERN | 4 | Add `driver: SqlDriver` constructor parameter |
| `saveBlocksDiff` and `saveBlocksUpdate` call sites pass `block.blockType` as String after Epic 2 | CONCERN | 2 | Include in Task 2.1.5 call-site audit |

**Verdict: BLOCKED** — the CTE ordering bug (Blocker 3) is a correctness failure that would silently scramble block display order, the `ROW_NUMBER()` compatibility issue (Blocker 2) creates a migration failure on a large population of minSdk=26 devices, and the fractional-indexing dependency (Blocker 1) is unresolvable without either confirming the artifact or writing the algorithm inline.
