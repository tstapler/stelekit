# Requirements: Block Operations Performance — Phase 2

## Problem Statement

Android v0.51.0 showed severe editing latency: `editor_input` p50=p99=5000ms, `blocks:select` mean=6224ms. Root-cause analysis identified algorithmic and structural deficiencies in the block operation layer. Phase 1 (already committed) resolved the acute 5-second lock-contention issue. This plan captures the remaining structural, algorithmic, and schema-level improvements.

---

## Phase 1 — Already Committed

These are **done** and must not be re-implemented:

| Fix | Commit | Impact |
|-----|--------|--------|
| `saveBlocks` two-pass wikilink separation | d210cbdc31 | Eliminated write-lock hold during wikilink index phase |
| `analysis_limit=400` Android + JVM | d210cbdc31 | Bounded ANALYZE from O(n rows) to ~400 samples |
| O(n) sibling `position` shift → batch UPDATE | 9a61b31a02 | `shiftRootBlockPositionsFrom` / `shiftChildBlockPositionsFrom` |
| N-call `recomputeBacklinkCountFromIndex` → batch | 9a61b31a02 | `recomputeBacklinkCountsForPages` UPDATE ... WHERE name IN ? |
| Redundant `selectBlockByUuid` after insert in `splitBlock` | 9a61b31a02 | One fewer DB round-trip per Enter keypress |

## Phase 1.5 — Algorithm Fixes (Uncommitted, Pending Review)

Completed by audit agent (in working tree, not yet committed):

| Fix | Method | Before → After |
|-----|--------|----------------|
| `moveBlockUp` | Sibling load | O(n) load all siblings → O(1) `left_uuid` chain |
| `moveBlockDown` | Sibling load | O(n) load all siblings → O(1) `left_uuid` chain |
| `mergeBlocks` | Position counter | N `selectLastChild` calls → 1 + local counter |
| `deleteBlock` subtree wikilinks | BFS collection | 1 query per BFS node → 1 batch `IN ?` query |
| `deleteBulk` subtree wikilinks | BFS collection | 1 query per block/child → 1 batch `IN ?` per subtree |
| New SQL: `selectWikilinkPageNamesForBlocks` | SteleDatabase.sq | `SELECT DISTINCT page_name WHERE block_uuid IN ?` |

---

## Phase 2 — Remaining Work (This Plan)

### R1: Sparse / Fractional-Index Block Positions

**Problem**: The `position INTEGER` column uses dense sequential integers. Every insert into the middle of a page requires a `shiftRootBlockPositionsFrom` / `shiftChildBlockPositionsFrom` UPDATE touching O(n) sibling rows. Even as a single SQL statement, it still locks and writes those rows — on a 500-block page, that's 500 row updates per Enter keypress.

**Requirement**: Replace dense positions with a sparse scheme where new insertions can pick a value between two existing neighbors without shifting anything. Only requires rebalancing when the gap is exhausted (rare). The scheme should support `ORDER BY position` to retain SQL-level ordering without Kotlin-side sorting.

Options to evaluate:
- **Float positions** (e.g. midpoint: `(left.position + right.position) / 2.0`) — simplest; precision degrades with repeated splits; needs occasional rebalance
- **Large-gap integers** (e.g. initial gap 65536, new = `(left + right) / 2 rounded`) — no float precision issues; same rebalance need
- **Fractional indices** (Figma/Logseq pattern: base-36 string midpoint) — rebalance never needed; ORDER BY is lexicographic; works in SQLite text ordering

Acceptance: After schema migration, inserting a new block into the middle of a 500-block page produces zero `UPDATE blocks SET position = ...` statements (verified by SQL trace test).

---

### R2: `WITHOUT ROWID` for `wikilink_references`

**Problem**: `wikilink_references (block_uuid, page_name)` has a composite primary key but no rowid-dependent queries. SQLite allocates a hidden rowid for every row regardless; `WITHOUT ROWID` tables store rows in PK order without the extra rowid B-tree, reducing both insert cost and lookup cost for PK and PK-prefix lookups.

**Requirement**: Declare `wikilink_references` as `WITHOUT ROWID`. The existing FK cascade behavior and `idx_wikilink_refs_page_name` index must be preserved. Must be applied via a migration (cannot be altered in place — requires table recreation).

Acceptance: `EXPLAIN QUERY PLAN SELECT * FROM wikilink_references WHERE block_uuid = ?` shows B-tree lookup, no covering-index scan overhead from rowid. `MigrationRunnerSchemaSyncTest` passes.

---

### R3: `blockType` String → Sealed Class

**Problem**: `block_type TEXT NOT NULL DEFAULT 'bullet'` in the DB and `blockType: String` in the domain model allow arbitrary invalid strings at compile time. Any code comparing block types uses string literals ("bullet", "heading", "paragraph"), which are fragile and not exhaustively checked.

**Requirement**: Introduce a `BlockType` sealed class (or enum) in the domain model. DB storage remains TEXT for compatibility; parsing happens at the repository boundary (parse, don't validate pattern). All callers of `block.blockType` should work with `BlockType` values, with a compile-time exhaustive when-expression.

Note: This is a type-driven design change — the primary value is correctness and exhaustiveness, with a secondary performance benefit (intern/enum comparison vs. string comparison in hot paths).

Acceptance: `block.blockType` type is `BlockType` (not `String`) in `Block` data class. `BlockType.fromString(s)` smart constructor with graceful fallback for unknown values. All existing callers compile and pass tests. No stringly-typed `blockType` comparisons remain in non-migration code.

---

### R4: Batch Wikilink Inserts (N individual → multi-row VALUES)

**Problem**: `addWikilinkRefs` issues one `INSERT OR IGNORE INTO wikilink_references` per page name. For a block referencing 10 pages, that's 10 SQL statements. During bulk import of thousands of blocks, this multiplies.

**Requirement**: Replace the loop with a single `INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name) VALUES (?, ?), (?, ?)...` using SQLite's multi-row VALUES syntax. SQLDelight generates code per query; a raw execute or dynamic SQL approach is acceptable if SQLDelight cannot template multi-row inserts naturally.

Constraint: Must still use `INSERT OR IGNORE` (not `INSERT OR REPLACE`) to avoid triggering the FTS delete+insert trigger pair on existing rows.

Acceptance: For a block referencing 5 page names, the DB trace shows 1 INSERT statement (not 5). Existing tests pass.

---

### R5: `getBlockHierarchy` — WITH RECURSIVE CTE

**Problem**: `getBlockHierarchy` does BFS traversal in Kotlin: one `selectBlocksByParentUuids(uuids)` call per depth level. For a deeply nested page (depth 10, 20 children per level), this is 10+ round-trips to SQLite. Each call also wakes the driver and allocates cursor/result objects.

**Requirement**: Replace the BFS loop with a single `WITH RECURSIVE subtree AS (SELECT ... UNION ALL ...) SELECT * FROM subtree` query that fetches the entire hierarchy in one SQL call. The returned rows should be sorted `ORDER BY level, position` so Kotlin simply iterates without sorting.

Constraint: SQLite 3.35+ supports recursive CTEs. Requery's bundled SQLite is 3.45 — this is safe.

Acceptance: `getBlockHierarchy` for a 10-level page with 100 total nodes produces exactly 1 SQL query (verified by trace or spy). Existing screenshot and hierarchy tests pass.

---

### R6: Two-Connection Read/Write Split (WAL Concurrency)

**Problem**: Requery's `RequerySQLiteOpenHelperFactory` does not implement Android's `SQLiteOpenHelper.enableWriteAheadLogging()` — the API that activates Android's native connection pool (multiple read connections + 1 write connection). We have `PRAGMA journal_mode=WAL` (the journal strategy) but not the connection pool. All reads and writes share one connection, so a write still blocks all concurrent reads.

**Requirement**: Evaluate and, if viable, implement a two-connection architecture:
- `readDriver`: used by all `SELECT` flows and queries via `PlatformDispatcher.DB`
- `writeDriver`: used exclusively by `DatabaseWriteActor`

Both drivers open the same database file. In WAL mode, a reader never blocks the writer and vice versa (SQLite WAL MVCC guarantee). This eliminates the fundamental read-stall-on-write problem without needing Android's native pool.

Constraint: SQLDelight's `SteleDatabase` is constructed from one driver. Evaluate whether a custom `SqlDriver` wrapper (delegating reads to `readDriver` and writes to `writeDriver`) is viable without forking SQLDelight. Alternatively, evaluate whether Requery 4.x or a different driver factory supports multi-connection.

Acceptance (if implemented): Under a simulated write (bulk import of 1000 blocks), concurrent `getBlockByUuid` calls return within 10ms without waiting for write lock.

---

## Non-Goals

- Porting the database layer off SQLDelight or SQLite
- iOS or WASM performance (Android is the target platform for this work)
- Search/FTS performance (separate concern)
- Compile-time elimination of all stringly-typed fields (only `blockType` in scope)

---

## Success Metrics

| Metric | Current (v0.51.0) | Target |
|--------|-------------------|--------|
| `editor_input` p50 | 5000ms | < 50ms |
| `blocks:select` mean | 6224ms | < 200ms |
| INSERT statements per `splitBlock` (middle of 500-block page) | O(n) updates | 0 position updates |
| SQL round-trips in `getBlockHierarchy` (10 levels) | O(depth) | 1 |
| Wikilink inserts per block save | N | 1 batch |

---

## Constraints

- All changes must pass `./gradlew ciCheck` (detekt + jvmTest + Android unit tests + assembleDebug)
- Schema changes require a migration entry in `MigrationRunner.all` and `MigrationRunnerSchemaSyncTest` must pass
- Write operations must continue to flow through `DatabaseWriteActor` and use `RestrictedDatabaseQueries`
- Arrow `Either<DomainError, T>` return types must be preserved at all repository boundaries
- No regression in `LargeGraphWarmStartCrashTest`, `QueryPlanAuditTest`, `RepositoryFlowResilienceTest`

---

## Key Files

| File | Role |
|------|------|
| `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq` | Schema + all SQL queries |
| `kmp/src/commonMain/kotlin/.../repository/SqlDelightBlockRepository.kt` | Repository impl |
| `kmp/src/commonMain/kotlin/.../db/RestrictedDatabaseQueries.kt` | Write-gating layer |
| `kmp/src/commonMain/kotlin/.../db/MigrationRunner.kt` | Incremental DDL migrations |
| `kmp/src/commonMain/kotlin/.../model/Models.kt` | Domain models (Block, Page) |
| `kmp/src/androidMain/kotlin/.../db/DriverFactory.android.kt` | Android driver setup |
| `kmp/src/jvmTest/kotlin/.../db/QueryPlanAuditTest.kt` | Query plan coverage |
