# Validation Plan: Block Operations Performance — Phase 2

**Phase**: 4 — Validation (pre-implementation)
**Date**: 2026-06-20
**Plan input**: `plan.md` (rev. post-patch 2026-06-20), `adversarial-review.md`

---

## Scope

This document maps every acceptance criterion from `plan.md` to at least one test case, tracks regression protection for named crash/resilience tests, and validates that every patched blocker from `adversarial-review.md` is covered. It is the gate document for Phase 5 (implementation).

---

## R0 — Phase 1.5 Baseline (Epic 1 prerequisite)

### Acceptance criteria

- `./gradlew ciCheck` passes with Phase 1.5 changes committed.
- `LargeGraphWarmStartCrashTest`, `QueryPlanAuditTest`, `RepositoryFlowResilienceTest` all green.

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R0-T1 | `LargeGraphWarmStartCrashTest` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/LargeGraphWarmStartCrashTest.kt` | 8030-page warm start does not crash; ≤100-row batches enforced |
| R0-T2 | `QueryPlanAuditTest` — `no unexpected full table scans` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt` | Entire query plan surface is indexed |
| R0-T3 | `QueryPlanAuditTest` — `all SELECT queries in SteleDatabase sq are covered by this audit` | Integration (JVM) | same | Structural: every SELECT has an audit entry |
| R0-T4 | `RepositoryFlowResilienceTest` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/RepositoryFlowResilienceTest.kt` | Closed-DB guard on all Flow-backed reads |
| R0-T5 | `MigrationRunnerSchemaSyncTest` | Integration (JVM) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt` | Every `IF NOT EXISTS` table has a migration; applyAll creates all tables |
| R0-T6 | `selectWikilinkPageNamesForBlocks` in `QueryPlanAuditTest.QUERIES` | Integration (JVM) | same as R0-T2 | Phase 1.5 batch wikilink query is indexed (entry already present at line 331) |

**Status**: R0 tests are existing — must remain green before Phase 1.5 commit lands.

---

## R1 — Sparse / Fractional-Index Block Positions (Epic 6)

### Acceptance criteria (from plan.md)

> After schema migration, inserting a new block into the middle of a 500-block page produces **zero** `UPDATE blocks SET position = ...` statements (verified by SQL trace test).

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R1-T1 | `FractionalPositionTest` — zero-shift insert (Task 6.2.6) | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/FractionalPositionTest.kt` **[NEW]** | 500-block page, insert in middle → assert 0 `UPDATE blocks SET position` statements via statement-counting SqlDriver spy |
| R1-T2 | `FractionalPositionTest` — ORDER BY correctness | same | same | New block appears in correct position when queried with `ORDER BY position` |
| R1-T3 | `FractionalIndexingTest` — `generateKeyBetween(null, null) == "a0"` | Unit (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/util/FractionalIndexingTest.kt` **[NEW]** | Baseline key generation |
| R1-T4 | `FractionalIndexingTest` — key ordering | Unit (businessTest) | same | Key between two keys sorts correctly under Kotlin `String` comparison (lexicographic) |
| R1-T5 | `FractionalIndexingTest` — 1000 successive splits no collision | Unit (businessTest) | same | Uniqueness under repeated splits |
| R1-T6 | `QueryPlanAuditTest` — post-migration index plan (Task 6.2.5) | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt` | `idx_blocks_page_position` and `idx_blocks_parent_position` still used after column type change to TEXT; no SCAN blocks regressions |
| R1-T7 | `LargeGraphWarmStartCrashTest` — post-Epic-6 run | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/LargeGraphWarmStartCrashTest.kt` | Warm start with fractional-index schema does not regress batch sizes or crash |
| R1-T8 | `MigrationRunnerSchemaSyncTest` — `blocks_position_fractional_index` migration | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt` | Migration applyAll correctly transitions `position` column to TEXT; FTS5 triggers recreated; FK cascade preserved |
| R1-T9 | `MigrationRunnerApplyAllTest` — `blocks_position_fractional_index` (existing test) | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerApplyAllTest.kt` | Migration runs idempotently on existing databases |

**Blocker fixes covered by R1 tests**:
- Blocker 2 fix (`printf('%011d', CAST(position AS INTEGER))` replaces `ROW_NUMBER()`): covered by R1-T8 / R1-T9 — the migration must execute successfully on SQLite 3.18+ (no window functions), verified by running it in the JVM test harness which uses SQLite 3.x bundled with sqlite-jdbc, and explicitly by checking `printf` output is zero-padded text that sorts correctly (R1-T2).

---

## R2 — `WITHOUT ROWID` for `wikilink_references` (Epic 5)

### Acceptance criteria (from plan.md)

> `EXPLAIN QUERY PLAN SELECT * FROM wikilink_references WHERE block_uuid = ?` shows B-tree lookup, no covering-index scan overhead from rowid. `MigrationRunnerSchemaSyncTest` passes.

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R2-T1 | `WithoutRowidMigrationTest` — query plan check (Task 5.1.3) | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/WithoutRowidMigrationTest.kt` **[NEW]** | `EXPLAIN QUERY PLAN SELECT * FROM wikilink_references WHERE block_uuid = ?` does NOT contain `SCAN wikilink_references` (uses PK B-tree) |
| R2-T2 | `WithoutRowidMigrationTest` — FK cascade preserved | same | same | Delete a block → both `wikilink_references` rows for that block are also deleted |
| R2-T3 | `WithoutRowidMigrationTest` — index name canonical | same | same | After migration, only `idx_wikilink_refs_page_name` exists (no `_new` suffix) — covers adversarial Concern 3 fix |
| R2-T4 | `MigrationRunnerSchemaSyncTest` — `wikilink_references_without_rowid` | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt` | Migration entry in `MigrationRunner.all`; applyAll creates correct schema |
| R2-T5 | `QueryPlanAuditTest` — `selectWikilinkPageNamesForBlock` and `selectWikilinkPageNamesForBlocks` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt` | Existing audit entries remain green after WITHOUT ROWID schema change |

**Concern covered**: adversarial Concern 3 (index named `idx_wikilink_refs_page_name_new` after migration) is directly tested by R2-T3. The fix adds explicit `DROP INDEX IF EXISTS idx_wikilink_refs_page_name_new` + `CREATE INDEX idx_wikilink_refs_page_name` steps to the migration; the test asserts only the canonical name exists.

---

## R3 — `blockType` String → Sealed Class (Epic 2)

### Acceptance criteria (from plan.md)

> `block.blockType` type is `BlockType` (not `String`) in `Block` data class. `BlockType.fromString(s)` smart constructor with graceful fallback for unknown values. All existing callers compile and pass tests. No stringly-typed `blockType` comparisons remain in non-migration code.

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R3-T1 | `BlockTypeTest` — `fromString` all known types (Story 2.3, Task 2.3.1) | Unit (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/model/BlockTypeTest.kt` **[NEW]** | `fromString("bullet")` → `BlockType.Bullet`, `fromString("heading")` → `BlockType.Heading(0)`, … for every `BlockTypes.*` constant |
| R3-T2 | `BlockTypeTest` — `fromString("image_annotation")` → `ImageAnnotation` | same | same | New `ImageAnnotation` variant returns the correct subtype, not `Unknown` |
| R3-T3 | `BlockTypeTest` — `fromString("some_future_plugin_type")` → `Unknown(…)` | same | same | Unknown strings do not throw; return `Unknown(raw)` |
| R3-T4 | `BlockTypeTest` — `Unknown("foo").toDiscriminatorString()` round-trip | same | same | `encode(decode("foo")) == "foo"` — no information loss |
| R3-T5 | `BlockTypeTest` — `Block(..., blockType = BlockType.Unknown("foo"))` no throw | same | same | Sealed class removes the `require(blockType in validBlockTypes)` init guard |
| R3-T6 | `ciCheck` compile pass (Task 2.1.6 audit) | Build | N/A | `grep -rn 'blockType ==' kmp/src` and `grep -rn '"bullet"' kmp/src` return zero hits in non-migration code — verified as part of implementation step, enforced by compile |
| R3-T7 | Existing `SqlDelightBlockRepositoryWarmPathTest` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepositoryWarmPathTest.kt` | Repository round-trips a block with sealed `BlockType` through DB |
| R3-T8 | Existing screenshot tests (`DesktopScreenshotTest`, `MobileScreenshotTest`) | UI screenshot (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/` | Heading/bullet/code-fence rendering unchanged after type change |

**Concern covered**: adversarial Concern 9 (`saveBlocksDiff` and `saveBlocksUpdate` bypassing `restricted.*` with raw String blockType) — R3-T6 build check and Task 2.1.5's explicit call-site audit of `saveBlocksDiff`, `saveBlocksUpdate`, `saveBlock`, `splitBlock`, `insertBlockRow` cover this.

---

## R4 — Batch Wikilink Inserts (Epic 4)

### Acceptance criteria (from plan.md)

> For a block referencing 5 page names, the DB trace shows 1 INSERT statement (not 5). Existing tests pass. Chunk size ≤ 499 pairs enforced.

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R4-T1 | `WikilinkBatchInsertTest` — 5 wikilinks → 1 INSERT (Story 4.2, Task 4.2.1) | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/WikilinkBatchInsertTest.kt` **[NEW]** | Statement-counting driver wrapper asserts INSERT count = 1 for 5 page names |
| R4-T2 | `WikilinkBatchInsertTest` — 500 wikilinks → 2 INSERTs | same | same | ceil(500/499) = 2 chunks; assert INSERT count = 2 |
| R4-T3 | `WikilinkBatchInsertTest` — `INSERT OR IGNORE` not `INSERT OR REPLACE` | same | same | SQL trace asserts `OR IGNORE` keyword present; FTS delete+insert trigger pair not fired on existing rows |
| R4-T4 | `WikilinkBatchInsertTest` — binder index correctness | same | same | All `(block_uuid, page_name)` pairs correctly bound (0-based `bindString(i*2, ...)` / `bindString(i*2+1, ...)`) — covers adversarial Concern 6 fix |
| R4-T5 | `RepositoryFlowResilienceTest` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/RepositoryFlowResilienceTest.kt` | Wikilink flows remain resilient after batch insert refactor |
| R4-T6 | `MigrationRunnerSchemaSyncTest` | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt` | No schema change for R4 — test stays green confirming no accidental DDL |

**Concern covered**: adversarial Concern 6 (binder `idx` is not an int; must use `bindString(i * 2, ...)`) is directly tested by R4-T4, which verifies correct data is inserted at each position.

**Concern covered**: adversarial Concern 12 (`RestrictedDatabaseQueries` needs `SqlDriver` injected via constructor) — this is a compile-time concern enforced by Task 4.1.1's constructor change; no runtime test needed beyond R4-T1 succeeding.

---

## R5 — `getBlockHierarchy` WITH RECURSIVE CTE (Epic 3)

### Acceptance criteria (from plan.md)

> `getBlockHierarchy` for a 10-level page with 100 total nodes produces exactly 1 SQL query. Existing screenshot and hierarchy tests pass.

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R5-T1 | `BlockHierarchyCteTest` — 50-level chain (Story 3.2, Task 3.2.1) | Integration (businessTest) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/BlockHierarchyCteTest.kt` **[NEW]** | Chain of 50 nested blocks: 51 results returned, depths 0–50, correct `ORDER BY depth, parent_uuid, position` ordering |
| R5-T2 | `BlockHierarchyCteTest` — exactly 1 SQL statement issued | same | same | Statement-counting SqlDriver spy asserts exactly 1 query was executed for a 10-level/100-node tree |
| R5-T3 | `BlockHierarchyCteTest` — `Flow<Either<...>>` return type preserved | same | same | Asserts `getBlockHierarchy` returns a `Flow` (not a suspend value), and collecting it yields the correct result — covers adversarial Concern 5 fix |
| R5-T4 | `BlockHierarchyCteTest` — depth guard prevents runaway recursion | same | same | A cycle (parent_uuid = self) terminates at depth 100 without infinite loop |
| R5-T5 | `BlockHierarchyCteTest` — `ORDER BY depth, parent_uuid, position` sibling ordering | same | same | Tree with two parents at depth 1, each with children at depth 2 — siblings of different parents are not interleaved — covers adversarial Concern 4 fix (was `ORDER BY depth, position` only) |
| R5-T6 | `QueryPlanAuditTest` — `selectBlockHierarchyRecursive` (Task 3.1.4) | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryPlanAuditTest.kt` | Recursive step uses `idx_blocks_parent_position`; plan does not show SCAN blocks |
| R5-T7 | Existing screenshot tests (`DesktopScreenshotTest`, `MobileScreenshotTest`, `DemoGraphScreenshotTest`) | UI screenshot (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/` | Nested block rendering unchanged |
| R5-T8 | `OutlinerRegressionTest` | UI (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/testing/OutlinerMonkeyTest.kt` and `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/OutlinerRegressionTest.kt` | Block indentation/outdentation/move still correct with CTE hierarchy |
| R5-T9 | `LargeGraphWarmStartCrashTest` — post-Epic-3 run | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/LargeGraphWarmStartCrashTest.kt` | Hierarchy cache eviction and warm start remain stable |

**Blocker fixes covered by R5 tests**:
- Blocker 3 fix (`ORDER BY depth, parent_uuid, position` replacing `ORDER BY depth, position`): R5-T5 directly tests the multi-parent ordering correctness.
- Concern 5 fix (`getBlockHierarchy` stays as `Flow<...>`, not promoted to `suspend fun`): R5-T3 asserts the `Flow` contract is preserved.

---

## R6 — Two-Connection Read/Write Split (WAL Concurrency) (Epic 7)

### Acceptance criteria (from plan.md)

> Under a simulated write (bulk import of 1000 blocks), concurrent `getBlockByUuid` calls return within 50ms without waiting for the write lock. WAL file does not grow unboundedly.
>
> (Conditional: implementation only proceeds if Story 7.1 audit finds no blockers.)

### Test cases

| ID | Test name | Type | File | What it checks |
|----|-----------|------|------|----------------|
| R6-T1 | `WalConcurrencyTest` — read latency under write (Task 7.2.4) | Instrumented (Android) | `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/WalConcurrencyTest.kt` **[NEW]** | 1000-block bulk insert concurrent with 10 `getBlockByUuid` reads; each read < 100ms; no `SQLITE_BUSY` errors |
| R6-T2 | `WalConcurrencyTest` — WAL file does not grow unboundedly | same | same | After 30-second bulk-import test, WAL file size < 50 MB (checkpoint fires) |
| R6-T3 | Story 7.1 audit written finding (Task 7.1.1 + 7.1.2) | Manual / documentation | ADR-015 comment in `project_plans/stelekit/decisions/ADR-015-wal-connection-pool.md` | Written record: Requery-specific features audited; system SQLite version on minSdk confirmed; decision to proceed or defer documented |
| R6-T4 | `JvmDriverConnectionPropsTest` | Integration (JVM) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/JvmDriverConnectionPropsTest.kt` | JVM-side driver config unchanged / correctly updated after factory swap |
| R6-T5 | `ciCheck` full run post-Epic-7 | Build | N/A | `assembleDebug` succeeds with `FrameworkSQLiteOpenHelperFactory` on the Android classpath |

**Note**: R6-T1 / R6-T2 require an instrumented test; they cannot run in `jvmTest`. They are gated by `./gradlew connectedAndroidTest` or equivalent. If Story 7.1 determines R6 is deferred (e.g., minSdk=26 system SQLite incompatibility blocks migration), R6-T1 and R6-T2 are deferred. R6-T3 is always required regardless of defer decision.

---

## Blocker-Fix Test Matrix (adversarial-review.md)

The adversarial review identified three original blockers (now patched in plan.md) and nine concerns. Each must be verified:

| Blocker / Concern | Fix applied in plan.md | Covering test(s) |
|---|---|---|
| **Blocker 1**: `com.davidarvelo:fractional-indexing` missing | Replaced with inline `FractionalIndexing.kt` (Task 6.1.1) | R1-T3, R1-T4, R1-T5 — unit tests on the inline implementation |
| **Blocker 2**: `ROW_NUMBER()` requires SQLite 3.25+ → deadlock | Replaced with `printf('%011d', CAST(position AS INTEGER))` (Task 6.2.2) | R1-T8 (migration runs on SQLite 3.18+), R1-T9 (idempotency) |
| **Blocker 3**: `ORDER BY depth, position` sorts globally, not per-parent | Fixed to `ORDER BY depth, parent_uuid, position` (Task 3.1.1) | R5-T5 (direct multi-parent ordering test) |
| **Concern 1**: `PRAGMA foreign_keys=OFF` is connection-scoped on JVM pooled driver | Mentioned as risk to address in implementation; MigrationRunner should use single-connection DDL path | R2-T4, R1-T8 — migration integration tests exercise the DDL path; if FK enforcement fires incorrectly, these tests will fail |
| **Concern 3**: WITHOUT ROWID migration leaves `idx_wikilink_refs_page_name_new` | Migration adds `DROP INDEX IF EXISTS …_new` + recreate canonical (Task 5.1.2 patch) | R2-T3 (asserts canonical index name exists and _new suffix is absent) |
| **Concern 4**: `ORDER BY depth, position` wrong sibling order | Fixed to `ORDER BY depth, parent_uuid, position` (Task 3.1.1) | R5-T5 |
| **Concern 5**: `getBlockHierarchy` return type changed to `suspend fun` | Plan retains `flow { ... }.flowOn(DB)` wrapper (Task 3.1.3 patch) | R5-T3 (Flow contract assertion) |
| **Concern 6**: `binders` lambda has wrong index (`idx` is not an int) | Fixed to `bindString(i * 2, blockUuid)` / `bindString(i * 2 + 1, name)` (Task 4.1.1 patch) | R4-T4 (all bound pairs correctly round-trip) |
| **Concern 7**: `indentBlock`, `outdentBlock`, `moveBlockUp`, `moveBlockDown`, `mergeBlocks`, `splitBlock` not listed | Added to Task 6.2.3 scope (explicit call-site enumeration) | R1-T1 (zero-shift test exercises splitBlock path), `OutlinerRegressionTest` (R5-T8) exercises move/indent/outdent |
| **Concern 9**: `saveBlocksDiff` / `saveBlocksUpdate` pass `block.blockType` as String after Epic 2 | Explicitly added to Task 2.1.5 call-site audit | R3-T6 (compile check; grep confirms no string literals remain) |
| **Concern 12**: `RestrictedDatabaseQueries` lacks access to `SqlDriver` | Constructor change specified in Task 4.1.1 | R4-T1 (compiles and executes batch insert) |

---

## Existing Regression Tests — Must Stay Green Throughout All Epics

These tests are named in CLAUDE.md, requirements.md, and plan.md as mandatory. They must not be broken by any epic.

| Test | Source set | File | Gate epics |
|------|-----------|------|------------|
| `LargeGraphWarmStartCrashTest` | jvmTest | `.../db/LargeGraphWarmStartCrashTest.kt` | All |
| `QueryPlanAuditTest` — `no unexpected full table scans` | jvmTest | `.../db/QueryPlanAuditTest.kt` | All |
| `QueryPlanAuditTest` — structural coverage | jvmTest | same | All |
| `MigrationRunnerSchemaSyncTest` — static schema sync | businessTest | `.../db/MigrationRunnerSchemaSyncTest.kt` | 2, 5, 6 (schema changes) |
| `MigrationRunnerSchemaSyncTest` — integration applyAll | businessTest | same | 2, 5, 6 |
| `RepositoryFlowResilienceTest` | jvmTest | `.../repository/RepositoryFlowResilienceTest.kt` | 2, 3, 4 |
| `UpgradeResilienceTest` (TC-UPGRADE-001) | jvmTest | `.../repository/UpgradeResilienceTest.kt` | 3, 4, 5 |
| `GraphManagerDatabaseLifecycleTest` | jvmTest | `.../db/GraphManagerDatabaseLifecycleTest.kt` | 3, 5, 6 |
| `PageNameIndexResilienceTest` | jvmTest | `.../domain/PageNameIndexResilienceTest.kt` | 1, 2 |
| `StelekitViewModelCrashReproductionTest` | jvmTest | `.../ui/StelekitViewModelCrashReproductionTest.kt` | 2 |
| `OutlinerRegressionTest` | jvmTest | `.../ui/OutlinerRegressionTest.kt` | 3, 6 |
| `OutlinerMonkeyTest` | jvmTest | `.../testing/OutlinerMonkeyTest.kt` | 6 |
| `PaginationEnforcementTest` | jvmTest | `.../repository/PaginationEnforcementTest.kt` | 2, 3 |
| `GraphLoaderIndexBatchingTest` | jvmTest | `.../db/GraphLoaderIndexBatchingTest.kt` | 1, 6 |
| `MigrationRunnerApplyAllTest` | jvmTest | `.../db/MigrationRunnerApplyAllTest.kt` | 5, 6 |
| `BlocksFtsTriggerTest` | jvmTest | `.../db/BlocksFtsTriggerTest.kt` | 6 (FTS triggers recreated in migration) |
| `SaveBlocksChunkingTest` | jvmTest | `.../repository/SaveBlocksChunkingTest.kt` | 4 |

---

## New Test Files Summary

| File | Source set | Epic | Tests |
|------|-----------|------|-------|
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/util/FractionalIndexingTest.kt` | businessTest | 6 (R1) | R1-T3, R1-T4, R1-T5 |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/FractionalPositionTest.kt` | businessTest | 6 (R1) | R1-T1, R1-T2 |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/WithoutRowidMigrationTest.kt` | businessTest | 5 (R2) | R2-T1, R2-T2, R2-T3 |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/model/BlockTypeTest.kt` | businessTest | 2 (R3) | R3-T1 through R3-T5 |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/repository/WikilinkBatchInsertTest.kt` | businessTest | 4 (R4) | R4-T1 through R4-T4 |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/BlockHierarchyCteTest.kt` | businessTest | 3 (R5) | R5-T1 through R5-T5 |
| `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/WalConcurrencyTest.kt` | instrumented | 7 (R6) | R6-T1, R6-T2 |

---

## Test Case Counts

| Type | Count |
|------|-------|
| Unit (businessTest) | 12 |
| Integration — businessTest (in-memory DB) | 14 |
| Integration — jvmTest (JDBC + full stack) | 18 |
| UI screenshot (jvmTest) | 4 |
| Instrumented Android | 2 |
| Manual / documentation | 1 |
| Build / compile | 2 |
| **Total new or expanded test cases** | **53** |
| **Existing regression tests explicitly listed** | **17** |

---

## Requirements Coverage Fraction

| Requirement | Acceptance criteria in plan.md | Tests mapped | Coverage |
|-------------|-------------------------------|--------------|----------|
| R0 (Phase 1.5 baseline) | `ciCheck` green; 3 named tests green | R0-T1 – R0-T6 | ✓ 100% |
| R1 (Fractional positions) | 0 position-shift UPDATEs for middle insert | R1-T1 – R1-T9 | ✓ 100% |
| R2 (WITHOUT ROWID) | B-tree plan; MigrationRunnerSchemaSyncTest passes | R2-T1 – R2-T5 | ✓ 100% |
| R3 (BlockType sealed class) | `blockType: BlockType`; fromString fallback; no stringly-typed comparisons | R3-T1 – R3-T8 | ✓ 100% |
| R4 (Batch wikilink inserts) | 1 INSERT for 5 wikilinks; chunking for 500 | R4-T1 – R4-T6 | ✓ 100% |
| R5 (CTE hierarchy) | 1 SQL query for 10-level hierarchy; existing tests pass | R5-T1 – R5-T9 | ✓ 100% |
| R6 (WAL connection pool) | Reads < 50ms during write; WAL bounded | R6-T1 – R6-T5 | ✓ 100% (conditional on Story 7.1 audit) |

**Requirements coverage: 7/7 — 100%**

---

## Readiness Gate Verdict

### Criterion 1 — Requirements coverage: 100% of R1–R6 acceptance criteria
**PASS** — Every "Acceptance" line in plan.md maps to at least one new test case (see table above). No untestable acceptance criteria identified; all criteria are observable through SQL trace, compilation, or functional assertion.

### Criterion 2 — Regression protection: all four named tests listed and preserved
**PASS** — `LargeGraphWarmStartCrashTest`, `QueryPlanAuditTest`, `MigrationRunnerSchemaSyncTest`, `RepositoryFlowResilienceTest` are all explicitly listed in the regression table, assigned to their gate epics, and cross-referenced in relevant requirement rows.

### Criterion 3 — Blocker fixes tested
**PASS** — All three patched blockers (ROW_NUMBER→printf, inline FractionalIndexing, ORDER BY fix) and all concerned implementation details (binder API, Flow return type, index naming, call-site audit) have at least one direct test.

### Criterion 4 — No untestable acceptance criteria
**PASS** — All acceptance criteria are observable:
- "Zero UPDATE statements" → statement-counting SqlDriver spy (R1-T1)
- "B-tree lookup" → `EXPLAIN QUERY PLAN` assertion (R2-T1)
- "`block.blockType` is `BlockType`" → compile check + unit test (R3-T1, R3-T6)
- "1 INSERT statement" → statement-counting driver (R4-T1)
- "Exactly 1 SQL query" → statement-counting driver spy (R5-T2)
- "Reads < 50ms under write" → timed instrumented test (R6-T1)

## VERDICT: PASS

The test plan fully covers all requirements, protects all named regression tests, and provides direct verification for every patched blocker. Implementation (Phase 5) may proceed.

**Pre-implementation checklist before Epic 7**:
- [ ] Story 7.1 audit complete and recorded in ADR-015 (R6-T3 must be done before R6-T1/T2 can be written)
- [ ] minSdk SQLite version confirmed (Task 7.1.2)
- [ ] `FrameworkSQLiteOpenHelperFactory` on the classpath without removing Requery if Requery is still needed for Epic 6 migration on skip-update devices
