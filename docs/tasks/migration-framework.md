# Implementation Plan: SteleKit Migration Framework

## Epic Overview

SteleKit graph files (Markdown-based) drift from a desired structure over time. Contributors write one-off scripts to restructure files — no history, no rollback, no idempotency. The Migration Framework replaces ad-hoc scripts with a versioned, audited, idempotent system for evolving graph content, inspired by Atlas (desired-state diff), Sqitch (dependency DAG), Liquibase (checksum-guarded changelog), and Flyway (versioned naming convention).

**User value**: Contributors and power users can evolve their graph structure reliably, with full audit history, undo support via the existing op log, and protection against accidental re-runs or tampering.

**Success metrics**:
- All SteleKit graph content migrations are expressed as DSL-defined `Migration` objects; zero ad-hoc scripts remain.
- Running `MigrationRunner.runPending()` on an already-migrated graph applies zero changes (structural idempotency).
- Every applied migration is recorded in `migration_changelog` with its checksum; tampered migrations are detected at startup.
- Destructive operations (delete block, delete page) require explicit `allowDestructive = true`; default mode is additive-only.

**Target platform**: JVM/Desktop first (Phase 1). Android follows without changes (shared `commonMain`). iOS and Web are structurally supported but not the priority target.

**New Gradle dependencies**:
- None required. All components are built on `commonMain` primitives already in the project: SQLDelight 2.3.2, `kotlinx.serialization`, `ContentHasher`, `DatabaseWriteActor`, `OperationLogger`, `DiffMerge`.

---

## Architecture Decisions

| ADR | Decision | File |
|-----|----------|------|
| ADR-001 | Kotlin internal lambda-with-receiver DSL; no external SQL parser or ANTLR | `project_plans/migration-framework/decisions/ADR-001-kotlin-internal-dsl.md` |
| ADR-002 | SQLDelight `migration_changelog` table in the per-graph SQLite database; not a JSON sidecar | `project_plans/migration-framework/decisions/ADR-002-sqldelight-changelog-table.md` |
| ADR-003 | Declared registration order is canonical; Kahn's algorithm validates DAG but does not reorder | `project_plans/migration-framework/decisions/ADR-003-declared-order-dag-validation.md` |
| ADR-004 | Normalize (BOM strip + LF + trailing whitespace) before SHA-256; store as `sha256-v1:<hex>` | `project_plans/migration-framework/decisions/ADR-004-checksum-normalization.md` |

---

## Component Overview

```
  App Startup
  MigrationRegistry.register(V001, V002, ...)
           |
           v
  GraphManager.switchGraph(graphId)
    1. RepositoryFactory.createRepositorySet()   [existing]
    2. UuidMigration.runIfNeeded(db)             [existing]
    3. MigrationRunner.runPending(graphId)       [NEW]
         |
         |-- DagValidator.validate()             fail-fast on cycle / missing dep
         |-- ChangelogQueries.appliedIds(graphId)
         |
         |  for each unapplied Migration (declared order):
         |    a. set status = RUNNING in changelog
         |    b. DslEvaluator.evaluate(migration.apply, repoSet)
         |         -> forBlocks(where) / forPages(where)
         |         -> BlockRepository.selectBlocksByPageUuid()
         |         -> compute desired Block states
         |         -> DiffMerge.diff(existing, desired) -> BlockDiff
         |    c. WriteActor.applyChanges(blockDiff)
         |         -> OperationLogger.log*() for undo
         |    d. set status = APPLIED in changelog + changes_applied count
         |
    4. _pendingMigration.complete()
           |
           v
  StelekitViewModel.loadGraph()
```

---

## Story Breakdown

---

### Story 1: Migration Domain Model and DSL

**Goal**: Define the `Migration` data class, the Kotlin internal DSL builders, and `MigrationRegistry`. No I/O or database access in this story — pure domain layer. Tests verify DSL construction and `@DslMarker` scope safety.

---

#### Task 1.1 — `Migration` data class and `MigrationRegistry`

**Files** (4):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/Migration.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationRegistry.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationStatus.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationChecksumComputer.kt` (new)

**Implementation steps**:

1. `MigrationStatus.kt`:
   ```kotlin
   enum class MigrationStatus { PENDING, RUNNING, APPLIED, FAILED, REVERTED }
   ```

2. `Migration.kt` — the core domain object:
   ```kotlin
   data class Migration(
       val id: String,                          // e.g. "V001__add-type-property"
       val description: String,
       val checksumBody: String,                // canonical body string; normalized before hashing
       val requires: List<String> = emptyList(),
       val conflicts: List<String> = emptyList(),
       val allowDestructive: Boolean = false,   // must be true to allow DeleteBlock / DeletePage ops
       val apply: MigrationScope.() -> Unit,
       val revert: (MigrationScope.() -> Unit)? = null,
   )
   ```

3. `MigrationChecksumComputer.kt` — normalization + SHA-256:
   - Strip UTF-8 BOM (`\uFEFF`) from start of string.
   - Replace all `\r\n` and standalone `\r` with `\n`.
   - Strip trailing whitespace from each line.
   - Trim leading/trailing blank lines.
   - SHA-256 the normalized UTF-8 bytes via `ContentHasher.sha256(normalizedString)`.
   - Return `"sha256-v1:${hex}"`.
   - Add a `@Test` in `businessTest` that verifies identical output for BOM-prefixed, CRLF-terminated, and trailing-whitespace inputs against the same canonical string.

4. `MigrationRegistry.kt`:
   ```kotlin
   object MigrationRegistry {
       private val _migrations = mutableListOf<Migration>()
       fun register(migration: Migration) { _migrations.add(migration) }
       fun registerAll(vararg migrations: Migration) { _migrations.addAll(migrations) }
       fun all(): List<Migration> = _migrations.toList()
       fun clear() { _migrations.clear() }  // for test isolation
   }
   ```

**Validation criteria**:
- `Migration` construction with no `requires` compiles and properties are accessible.
- `MigrationChecksumComputer.compute("hello\r\n")` equals `MigrationChecksumComputer.compute("hello\n")`.
- `MigrationChecksumComputer.compute("\uFEFFbody")` equals `MigrationChecksumComputer.compute("body")`.
- `MigrationRegistry.all()` returns migrations in registration order.

---

#### Task 1.2 — DSL builders (`MigrationScope`, `BlockScope`, `PageScope`)

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationDsl.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationBuilder.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/MigrationDslTest.kt` (new)

**Implementation steps**:

1. `MigrationDsl.kt` — `@DslMarker` and scope interfaces:
   ```kotlin
   @DslMarker
   annotation class MigrationDslMarker

   @MigrationDslMarker
   interface MigrationScope {
       fun forBlocks(where: (Block) -> Boolean, transform: BlockScope.() -> Unit)
       fun forPages(where: (Page) -> Boolean, transform: PageScope.() -> Unit)
   }

   @MigrationDslMarker
   interface BlockScope {
       val block: Block
       fun setProperty(key: String, value: String)
       fun deleteProperty(key: String)
       fun setContent(newContent: String)
       fun deleteBlock()   // only valid if migration.allowDestructive = true
   }

   @MigrationDslMarker
   interface PageScope {
       val page: Page
       fun setProperty(key: String, value: String)
       fun deleteProperty(key: String)
       fun renamePage(newName: String)  // triggers graph-wide wikilink rewrite
       fun deletePage()   // only valid if migration.allowDestructive = true
   }
   ```

2. `MigrationBuilder.kt` — top-level `migration()` builder function and `ApplyAccumulator` that records intended operations as a `List<MigrationIntent>` (deferred evaluation; actual DB writes happen in `DslEvaluator`):
   ```kotlin
   fun migration(id: String, block: MigrationBuilder.() -> Unit): Migration
   ```
   `MigrationBuilder` collects `apply` and `revert` lambdas, `requires`, `conflicts`, `description`, `checksumBody`, and `allowDestructive`.

3. `MigrationDslTest.kt` — verify:
   - DSL scope leakage is prevented (`@DslMarker` blocks calling `setProperty` from within `forPages` on a `BlockScope`).
   - `migration("id") { ... }` produces a `Migration` with the declared `id` and `requires` list.
   - The `revert` lambda is optional; `migration.revert == null` when not declared.

**Validation criteria**:
- `migration("V001") { ... }` compiles and produces a `Migration` with correct `id`.
- Calling `block.forBlocks(...)` inside a `PageScope` block produces a compile error (scope leakage prevention).
- `MigrationDslTest` passes with zero test infrastructure beyond `businessTest`.

---

### Story 2: Diff Engine and BlockChange Model

**Goal**: The diff engine evaluates a migration's `apply` lambda against the current `RepositorySet` and produces a `List<BlockChange>` — the minimal set of mutations needed to satisfy the migration's postconditions. This story does not write to the database; it only computes the delta.

---

#### Task 2.1 — `BlockChange` sealed class and `DslEvaluator`

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/BlockChange.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/DslEvaluator.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/DslEvaluatorTest.kt` (new)

**Implementation steps**:

1. `BlockChange.kt`:
   ```kotlin
   sealed class BlockChange {
       data class UpsertProperty(val blockUuid: String, val key: String, val value: String) : BlockChange()
       data class DeleteProperty(val blockUuid: String, val key: String) : BlockChange()
       data class SetContent(val blockUuid: String, val newContent: String) : BlockChange()
       data class DeleteBlock(val blockUuid: String) : BlockChange()
       data class InsertBlock(val block: Block) : BlockChange()
       data class UpsertPageProperty(val pageUuid: String, val key: String, val value: String) : BlockChange()
       data class DeletePageProperty(val pageUuid: String, val key: String) : BlockChange()
       data class RenamePage(val pageUuid: String, val oldName: String, val newName: String) : BlockChange()
       data class DeletePage(val pageUuid: String) : BlockChange()
   }
   ```

2. `DslEvaluator.kt` — evaluates a `MigrationScope.() -> Unit` lambda against live repo state:
   - Implements `MigrationScope`, `BlockScope`, `PageScope` (internal, package-private).
   - `forBlocks(where, transform)`: pages all blocks from `BlockRepository.selectAllBlocks()` in batches of 500; for each block where `where(block)` is true, applies `transform` to a `BlockScopeImpl` that accumulates `BlockChange` instances. Does NOT write to the DB.
   - `forPages(where, transform)`: pages all pages from `PageRepository.selectAllPages()` in batches of 200; same pattern.
   - `evaluate(lambda: MigrationScope.() -> Unit): List<BlockChange>` — runs the lambda and returns accumulated changes.
   - **Safety guard**: if `migration.allowDestructive == false` and any `DeleteBlock` or `DeletePage` change is accumulated, throw `DestructiveOperationException` naming the migration ID and the blocked change type.
   - **Idempotency**: before accumulating `UpsertProperty(uuid, key, value)`, check if `block.properties[key] == value` already. If so, skip the change. Same for `SetContent`. This ensures re-running the evaluator on an already-migrated graph produces zero changes.

3. `DslEvaluatorTest.kt`:
   - Seeds an in-memory `RepositorySet` with known blocks.
   - Evaluates a `forBlocks(where) { setProperty("x", "y") }` lambda.
   - Asserts that only blocks satisfying `where` appear in the returned `BlockChange` list.
   - Asserts idempotency: running the same lambda on the same post-migration state returns an empty list.
   - Asserts that `DeleteBlock` with `allowDestructive = false` throws.

**Validation criteria**:
- `DslEvaluator.evaluate(lambda)` returns only changes for blocks matching the predicate.
- Running evaluator twice on already-transformed state returns empty list.
- `DestructiveOperationException` is thrown for `deleteBlock()` when `allowDestructive = false`.

---

#### Task 2.2 — `ChangeApplier` (writes `BlockChange` list to DB via `DatabaseWriteActor`)

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/ChangeApplier.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/PostMigrationFlusher.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/ChangeApplierTest.kt` (new)

**Implementation steps**:

1. `ChangeApplier.kt` — takes a `List<BlockChange>` and applies each change through `DatabaseWriteActor`:
   - All writes go through `writeActor.sendAndAwait(...)` — never direct repository calls — to preserve `SQLITE_BUSY` serialization guarantees.
   - Every applied change is logged via `OperationLogger.log*()` (INSERT_BLOCK, UPDATE_BLOCK, DELETE_BLOCK) so migration changes appear in the undo stack and can be reverted via `UndoManager`.
   - `RenamePage` changes trigger a graph-wide wikilink rewrite: after renaming the page in the DB, query `selectBlocksWithWikilink(oldName)` and batch-update all content occurrences of `[[OldName]]` → `[[NewName]]`. This must be atomic with the page rename — both the rename and the wikilink rewrites are logged as a single `BATCH_START`/`BATCH_END` operation group.
   - Returns `ChangeSummary(applied: Int, skipped: Int, failed: List<String>)`.

2. `PostMigrationFlusher.kt` — after all migrations for a graph have run, calls `GraphWriter.savePage()` for each page touched by any migration. This syncs the DB-applied changes back to Markdown files on disk. Batched by page UUID to avoid redundant saves.

3. `ChangeApplierTest.kt`:
   - Seeds in-memory `RepositorySet`, applies `UpsertProperty` changes, asserts block state in repo.
   - Verifies `OperationLogger` received corresponding log entries.
   - Verifies `RenamePage` updates both the page name and all `[[OldName]]` wikilinks in other blocks.

**Validation criteria**:
- Applied `UpsertProperty` changes are reflected in `BlockRepository.getBlockByUuid()`.
- `OperationLogger` has entries for each applied change.
- `RenamePage` rewriting covers blocks on other pages that reference `[[OldName]]`.

---

### Story 3: Changelog, DAG Validation, and `MigrationRunner`

**Goal**: Persist migration history in SQLDelight, validate dependency ordering at startup, and wire `MigrationRunner` into `GraphManager.switchGraph()` as the pre-load step.

---

#### Task 3.1 — `migration_changelog` SQLDelight schema and queries

**Files** (2):
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (modify — add table + queries)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/ChangelogRepository.kt` (new)

**Implementation steps**:

1. Add to `SteleDatabase.sq` after the `operations` table:

   ```sql
   -- Migration framework changelog
   CREATE TABLE migration_changelog (
       id              TEXT NOT NULL,
       graph_id        TEXT NOT NULL,
       description     TEXT NOT NULL,
       checksum        TEXT NOT NULL,
       applied_at      INTEGER NOT NULL,
       execution_ms    INTEGER NOT NULL DEFAULT 0,
       status          TEXT NOT NULL DEFAULT 'APPLIED',
       applied_by      TEXT NOT NULL DEFAULT '',
       execution_order INTEGER NOT NULL DEFAULT 0,
       changes_applied INTEGER NOT NULL DEFAULT 0,
       error_message   TEXT,
       PRIMARY KEY (id, graph_id)
   );

   CREATE INDEX idx_changelog_graph_status ON migration_changelog(graph_id, status);
   CREATE INDEX idx_changelog_applied_at ON migration_changelog(graph_id, applied_at);

   -- Named queries
   insertMigrationRecord:
   INSERT INTO migration_changelog
       (id, graph_id, description, checksum, applied_at, execution_ms, status, applied_by, execution_order, changes_applied, error_message)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

   selectAppliedMigrations:
   SELECT id, checksum, status FROM migration_changelog WHERE graph_id = ? AND status = 'APPLIED' ORDER BY execution_order;

   selectMigrationById:
   SELECT * FROM migration_changelog WHERE id = ? AND graph_id = ?;

   updateMigrationStatus:
   UPDATE migration_changelog SET status = ?, error_message = ?, execution_ms = ?, changes_applied = ?
   WHERE id = ? AND graph_id = ?;

   selectRunningMigrations:
   SELECT * FROM migration_changelog WHERE graph_id = ? AND status = 'RUNNING';

   deleteMigrationRecord:
   DELETE FROM migration_changelog WHERE id = ? AND graph_id = ?;
   ```

2. `ChangelogRepository.kt` — wraps the generated SQLDelight queries:
   - `suspend fun appliedIds(graphId: String): Map<String, String>` — returns `id → checksum` for all APPLIED migrations.
   - `suspend fun markRunning(id: String, graphId: String, order: Int, checksum: String)`.
   - `suspend fun markApplied(id: String, graphId: String, executionMs: Long, changesApplied: Int)`.
   - `suspend fun markFailed(id: String, graphId: String, errorMessage: String)`.
   - `suspend fun runningMigrations(graphId: String): List<*>` — detects interrupted migrations.
   - `suspend fun deleteRecord(id: String, graphId: String)` — for `repair` command.

**Validation criteria**:
- SQLDelight generates without errors after schema addition.
- `ChangelogRepository.appliedIds()` returns empty map for a new graph.
- `markRunning` + `markApplied` sequence produces a row with `status = 'APPLIED'`.

---

#### Task 3.2 — `DagValidator` (Kahn's algorithm)

**Files** (2):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/DagValidator.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/DagValidatorTest.kt` (new)

**Implementation steps**:

1. `DagValidator.kt`:
   ```kotlin
   object DagValidator {
       sealed class DagError {
           data class CycleDetected(val cyclePath: List<String>) : DagError()
           data class UnresolvedDependency(val migrationId: String, val missingDep: String) : DagError()
           data class OutOfOrder(val migrationId: String, val requiredId: String) : DagError()
           data class ConflictViolation(val migrationId: String, val conflictId: String) : DagError()
       }

       fun validate(migrations: List<Migration>, appliedIds: Set<String> = emptySet()): List<DagError>
   }
   ```

   `validate()` logic:
   - Build a `knownIds: Set<String>` from the full `migrations` list.
   - For each migration at index `i`: verify all `requires` IDs are in `knownIds` (UnresolvedDependency) and appear at an index < `i` in the list (OutOfOrder).
   - Run Kahn's BFS over the declared dependency edges to detect cycles (CycleDetected) — collect any node not processed (remaining in-degree > 0) into the cycle path.
   - For each migration: verify none of its `conflicts` IDs are in `appliedIds` (ConflictViolation).
   - Return all errors found (collect-all, not fail-fast, so the author gets every issue at once).

2. `DagValidatorTest.kt`:
   - Linear chain: `V001 → V002 → V003` passes with no errors.
   - Out-of-order: `V002` registered before `V001` (which V002 requires) produces `OutOfOrder`.
   - Direct cycle: `V001 requires V002`, `V002 requires V001` produces `CycleDetected` with both IDs in path.
   - Missing dep: `V001 requires "ghost-migration"` produces `UnresolvedDependency`.
   - Conflict violation: migration with `conflicts = ["V001"]` applied when V001 is in `appliedIds` produces `ConflictViolation`.

**Validation criteria**:
- All five test cases above pass.
- `validate()` returns empty list for a valid linear chain.
- `CycleDetected.cyclePath` contains the IDs forming the cycle (not just one ID).

---

#### Task 3.3 — `MigrationRunner` and `GraphManager` integration

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationRunner.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (modify — call `MigrationRunner.runPending()`)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/MigrationRunnerTest.kt` (new)

**Implementation steps**:

1. `MigrationRunner.kt`:
   ```kotlin
   class MigrationRunner(
       private val registry: MigrationRegistry,
       private val changelogRepo: ChangelogRepository,
       private val evaluator: DslEvaluator,
       private val applier: ChangeApplier,
       private val dagValidator: DagValidator = DagValidator,
   ) {
       suspend fun runPending(graphId: String, repoSet: RepositorySet): RunResult
       suspend fun revert(migrationId: String, graphId: String, repoSet: RepositorySet): RevertResult
       suspend fun validate(): List<DagValidator.DagError>
       suspend fun recalculateChecksums(graphId: String)  // repair command
   }
   ```

   `runPending()` logic:
   1. Load `appliedIds` from `ChangelogRepository`.
   2. Detect any `RUNNING` migrations (interrupted previous run): throw `InterruptedMigrationException` naming the migration ID. Callers should surface a "repair required" error to the user.
   3. Validate the DAG against both the full registry and the applied IDs (for conflict checks). If errors exist, throw `MigrationDagException`.
   4. Recompute checksums for all APPLIED migrations; throw `MigrationTamperedError` if any mismatch.
   5. For each migration in registry order where ID is not in `appliedIds`:
      - Mark status `RUNNING` in changelog (with current timestamp).
      - Call `DslEvaluator.evaluate(migration.apply, repoSet)` → `List<BlockChange>`.
      - If dry-run mode: log changes and continue to next migration without writing.
      - Call `ChangeApplier.apply(changes, writeActor, operationLogger)` → `ChangeSummary`.
      - Mark status `APPLIED` with `execution_ms` and `changes_applied`.
   6. On exception during step 5: mark status `FAILED` with `error_message`; re-throw.
   7. Call `PostMigrationFlusher.flush(repoSet, graphPath)` after all migrations complete.

2. `GraphManager.switchGraph()` modification — after the `UuidMigration` block, add:
   ```kotlin
   graphScope.launch {
       try {
           UuidMigration(writeActor).runIfNeeded(db)
           MigrationRunner(
               MigrationRegistry,
               ChangelogRepository(db),
               DslEvaluator(repoSet),
               ChangeApplier(writeActor, operationLogger),
           ).runPending(id, repoSet)
       } finally {
           deferred.complete(Unit)
       }
   }
   ```
   The `MigrationRunner` runs inside the same `graphScope.launch` coroutine as `UuidMigration`, serialized behind the same `writeActor`, and the `_pendingMigration` deferred resolves only after both complete.

3. `MigrationRunnerTest.kt`:
   - Happy path: register two migrations, run on fresh in-memory graph, assert both APPLIED in changelog.
   - Idempotency: run again on same graph, assert zero changes applied on second run.
   - Tampered migration: change `checksumBody` of an already-applied migration, assert `MigrationTamperedError`.
   - Interrupted migration: seed `RUNNING` row in changelog, assert `InterruptedMigrationException`.

**Validation criteria**:
- `MigrationRunner.runPending()` on a fresh graph applies all pending migrations in order.
- Second run on same graph produces `changesApplied = 0` for all migrations.
- `MigrationTamperedError` thrown when checksum changes.
- `GraphManager.awaitPendingMigration()` does not resolve until migrations complete.

---

### Story 4: Repair Commands and Dry-Run Mode

**Goal**: Operational commands for the development inner loop — `repair`, `validate`, `baseline`, `recalculateChecksums`, and `--dry-run` mode. These make the framework usable in real development scenarios without data loss risk.

---

#### Task 4.1 — Dry-run mode and `MigrationPlan` output

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationPlan.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationRunner.kt` (modify — add `dryRun` parameter)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/DryRunTest.kt` (new)

**Implementation steps**:

1. `MigrationPlan.kt` — the output of a dry run:
   ```kotlin
   data class MigrationPlan(
       val pendingMigrations: List<MigrationPlanEntry>,
       val alreadyApplied: Int,
       val wouldApply: Int,
       val wouldSkip: Int,
   )

   data class MigrationPlanEntry(
       val migrationId: String,
       val description: String,
       val plannedChanges: List<BlockChange>,
       val isDestructive: Boolean,
   )
   ```

2. `MigrationRunner.dryRun(graphId, repoSet): MigrationPlan` — runs the full evaluation pipeline (DAG validation, checksum verification, `DslEvaluator.evaluate()`) but calls no write operations. Returns a `MigrationPlan` describing what would be applied.

3. `DryRunTest.kt` — verifies that calling `dryRun()` leaves the `RepositorySet` unchanged.

**Validation criteria**:
- `dryRun()` returns a plan with the correct `pendingMigrations` count.
- Repo state is identical before and after `dryRun()`.
- A plan with destructive changes sets `isDestructive = true` on the relevant `MigrationPlanEntry`.

---

#### Task 4.2 — `repair` and `baseline` commands

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/MigrationRunner.kt` (modify — add repair/baseline)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/RepairTest.kt` (new)
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/MigrationCli.kt` (new)

**Implementation steps**:

1. `MigrationRunner.repair(graphId)`:
   - Delete all `FAILED` rows from `migration_changelog` for `graphId`.
   - Returns a count of deleted rows and a list of deleted IDs.
   - Throws if any `RUNNING` rows exist (a `RUNNING` state indicates a crash, not a normal failure — the developer must investigate before repair).

2. `MigrationRunner.baseline(graphId, baselineId)`:
   - Marks all migrations up to and including `baselineId` as APPLIED in the changelog with `changes_applied = 0` and `status = 'APPLIED'`.
   - Used for existing graphs that pre-date the migration framework. Registers their current state as "already done" so subsequent migrations run cleanly.
   - Throws if any of the baseline IDs are already in the changelog.

3. `MigrationRunner.recalculateChecksums(graphId)`:
   - For each registered migration that has an APPLIED row in `migration_changelog`, recompute the checksum from the current `migration.checksumBody` and update the stored value.
   - Returns a list of updated IDs. Used when a migration's `checksumBody` has been legitimately changed (e.g. comment update).

4. `MigrationCli.kt` — a minimal JVM-only CLI entry point that accepts `migrate`, `dry-run`, `repair`, `baseline`, `validate` commands and calls the corresponding `MigrationRunner` methods. Intended for developer tooling, not end-user UI. Reads `MigrationRegistry` populated by the calling site.

**Validation criteria**:
- `repair()` deletes FAILED rows and returns their IDs.
- `repair()` throws when RUNNING rows exist.
- `baseline("V001")` marks V001 as APPLIED with `changes_applied = 0`.
- `recalculateChecksums()` updates the stored checksum to match the current `checksumBody`.

---

### Story 5: Testing Infrastructure

**Goal**: Three test tiers from the architecture research — per-migration unit tests, DAG integration tests, and golden-file regression test against the demo graph. These validate correctness and catch regressions in the migration engine itself.

---

#### Task 5.1 — Migration test fixtures and harness

**Files** (3):
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/MigrationTestHarness.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/fixtures/GraphFixtures.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/fixtures/SyntheticMigrations.kt` (new)

**Implementation steps**:

1. `MigrationTestHarness.kt` — creates an isolated test environment:
   ```kotlin
   class MigrationTestHarness {
       val repoSet = RepositoryFactory.createRepositorySet(GraphBackend.IN_MEMORY, testScope)
       val changelog = ChangelogRepository(inMemoryDb)
       fun buildRunner(vararg migrations: Migration): MigrationRunner
       suspend fun runAll(graphId: String = "test-graph"): RunResult
       suspend fun assertBlockProperty(blockUuid: String, key: String, expected: String)
       suspend fun assertChangelog(migrationId: String, expectedStatus: MigrationStatus)
   }
   ```

2. `GraphFixtures.kt` — factory methods for seeding known block/page states:
   - `blocksWithoutTypeProperty(count: Int)` — blocks missing the `type::` property.
   - `pagesWithTag(tag: String, count: Int)` — pages with a specific tag in properties.
   - `graphWithWikilinks(pageNames: List<String>)` — cross-referencing pages for rename testing.

3. `SyntheticMigrations.kt` — a suite of test migrations that exercise each DSL primitive:
   - `V001_AddTypeProperty` — `forBlocks(where) { setProperty("type", "note") }`
   - `V002_RenameStatusProperty` — `forBlocks(where) { setProperty("status", "active"); deleteProperty("old-status") }`
   - `V003_RenameTestPage` — `forPages(where) { renamePage("NewTestPage") }`
   - `V004_DeleteOrphanBlocks` — `forBlocks(where) { deleteBlock() }` with `allowDestructive = true`

**Validation criteria**:
- `MigrationTestHarness.buildRunner(V001_AddTypeProperty).runAll()` succeeds on `blocksWithoutTypeProperty(10)`.
- All four synthetic migrations pass without errors.
- Second run of all four migrations on post-migration state produces zero changes.

---

#### Task 5.2 — Golden-file regression test

**Files** (3):
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/GoldenFileMigrationTest.kt` (new)
- `kmp/src/jvmTest/resources/migration-golden/pre-migration-graph.json` (new)
- `kmp/src/jvmTest/resources/migration-golden/post-migration-graph.json` (new)

**Implementation steps**:

1. The golden test seeds an in-memory `RepositorySet` from `pre-migration-graph.json` (a serialized snapshot of the demo graph block tree — deterministic, no timestamps).

2. Runs all four synthetic migrations from Task 5.1 via `MigrationTestHarness`.

3. Serializes the resulting block tree (content + properties, UUID-keyed, sorted by UUID for determinism, no `created_at`/`updated_at` timestamps) to JSON via `kotlinx.serialization`.

4. Compares against `post-migration-graph.json`. A mismatch is a test failure with a diff output.

5. The golden files are committed to the repo. When migrations intentionally change, the author updates the golden file and commits both.

This is the same pattern used by Roborazzi for screenshot tests — fail on unexpected change, force explicit golden update for intentional changes.

**Validation criteria**:
- Test passes on first run (golden files created from current output).
- Changing a migration body without updating the golden file causes the test to fail.
- The serialized JSON is deterministic across JVM runs (no non-deterministic map ordering).

---

### Story 6: First Real Migration and Integration Validation

**Goal**: Write one real production migration against SteleKit's demo graph to validate the full pipeline end-to-end in a non-synthetic environment.

---

#### Task 6.1 — `V001__baseline` migration and `Migrations.kt` entry point

**Files** (3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/migrations/V001Baseline.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/Migrations.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitApp.kt` (modify — register `Migrations.all`)

**Implementation steps**:

1. `V001Baseline.kt` — the no-op baseline migration. Applies zero changes. Its presence establishes the starting point for all future migrations:
   ```kotlin
   val V001Baseline = migration("V001__baseline") {
       description = "Establish migration baseline — no content changes"
       checksumBody = "V001__baseline"
       apply { /* no-op: baseline records current state as starting point */ }
   }
   ```

2. `Migrations.kt` — the registry entry point for all app migrations:
   ```kotlin
   object Migrations {
       fun register() {
           MigrationRegistry.registerAll(
               V001Baseline,
               // future migrations appended here
           )
       }
   }
   ```

3. `StelekitApp.kt` modification — call `Migrations.register()` before the first `GraphManager.switchGraph()`.

4. Integration smoke test: open the demo graph on desktop. Verify `migration_changelog` has one APPLIED row for `V001__baseline`. Verify `awaitPendingMigration()` resolves. Verify no UI-visible regression.

**Validation criteria**:
- Demo graph loads successfully after `Migrations.register()` is called.
- `migration_changelog` contains exactly one row for the demo graph after first open.
- Opening the graph a second time produces no additional rows and no errors.
- `MigrationRunner.runPending()` on the second open completes with zero changes applied.

---

## Dependency Visualization

```
Story 1: Migration Domain Model and DSL
  Task 1.1 (Migration + Registry + Checksum)
  Task 1.2 (DSL Builders)
       |
       v
Story 2: Diff Engine and BlockChange Model
  Task 2.1 (BlockChange + DslEvaluator)
  Task 2.2 (ChangeApplier + PostMigrationFlusher)
       |
       v
Story 3: Changelog, DAG, MigrationRunner
  Task 3.1 (SQLDelight schema)   ----+
  Task 3.2 (DagValidator)        ----|
                                     |
                                     v
  Task 3.3 (MigrationRunner + GraphManager wiring)
       |
       +-----> Story 4: Repair Commands
       |         Task 4.1 (DryRun)
       |         Task 4.2 (Repair / Baseline)
       |
       +-----> Story 5: Testing Infrastructure
       |         Task 5.1 (Harness + Fixtures)
       |         Task 5.2 (Golden-file regression)
       |
       v
Story 6: First Real Migration
  Task 6.1 (V001 Baseline + Migrations.kt + App registration)
```

Tasks 3.1 and 3.2 are independent and can proceed in parallel once Story 2 is complete.
Tasks 4.1 and 5.1 are independent and can proceed in parallel once Story 3 is complete.

---

## Integration Checkpoints

**After Task 3.3**: The full pipeline runs in the development build. Open the demo graph. Observe that `MigrationRunner.runPending()` runs, `V001__baseline` appears in `migration_changelog` with `status = APPLIED`, and the graph loads normally. Regression check: all existing tests pass.

**After Task 4.1**: Dry-run mode produces a `MigrationPlan` without modifying any data. Verify by adding a second synthetic migration to the registry, running `dryRun()`, and asserting the plan lists the migration but the repo is unchanged.

**After Task 5.2**: The golden-file test passes. Any future change to a migration body that affects graph content will cause this test to fail, surfacing regressions before they reach production.

**After Task 6.1**: The demo graph opens cleanly on desktop with zero test regressions. The migration framework is production-ready for the first real migration author.

---

## Known Issues

### Partial Apply — Interrupted Migration Leaves `RUNNING` Status [SEVERITY: High]

**Description**: If the process is killed between `markRunning()` and `markApplied()`, the changelog row remains `RUNNING`. The next startup detects this and throws `InterruptedMigrationException`, blocking graph load. The user must call `repair()` to clear the RUNNING row, then re-run.

**Mitigation**:
- Detect RUNNING rows at startup and surface a clear error with the migration ID.
- `repair()` command clears RUNNING rows only after the operator confirms (to avoid masking a real crash mid-write).
- All `ChangeApplier` writes go through `OperationLogger` so the op log can roll back the partial change set via `UndoManager.undoTo(migrationBatchStart)` as part of the repair flow.
- Add a chaos test to `MigrationRunnerTest`: inject a failure after 50% of `BlockChange` writes and verify RUNNING detection on restart.

**Files likely affected**:
- `migration/MigrationRunner.kt`
- `migration/ChangeApplier.kt`
- `migration/ChangelogRepository.kt`

---

### Checksum Drift from `checksumBody` Mutation [SEVERITY: High]

**Description**: A contributor edits the `checksumBody` field of an already-applied migration (even a comment change), causing `MigrationTamperedError` on the next graph load. This blocks graph load entirely until `recalculateChecksums()` is called.

**Mitigation**:
- Enforce a lint rule (or code review checklist item): `checksumBody` must never change for a migration that has been committed to the main branch.
- `recalculateChecksums()` is the documented recovery path and should be surfaced in the UI as a "fix migration checksum" action, not buried in a CLI.
- The `sha256-v1:` prefix in stored checksums allows the `recalculateChecksums()` command to target only migrations using the current algorithm version.

**Files likely affected**:
- `migration/MigrationChecksumComputer.kt`
- `migration/MigrationRunner.kt`

---

### Wikilink Rewrite Scope — `RenamePage` May Miss Embedded Wikilinks [SEVERITY: High]

**Description**: `ChangeApplier` rewrites `[[OldName]]` links using `selectBlocksWithWikilink(oldName)` (a `LIKE` query). This query misses: (a) wikilinks inside fenced code blocks (which should NOT be rewritten), (b) aliased wikilinks like `[[OldName|display text]]`, and (c) namespace-qualified links like `[[Namespace/OldName]]`.

**Mitigation**:
- Use `OutlinerPipeline`/`InlineParser` AST traversal to identify wikilink nodes, not raw `LIKE` queries. This ensures code block content is never modified.
- Aliased wikilinks (`[[target|alias]]`) must be handled by `InlineParser`'s `WikiLinkNode.alias` field.
- Namespace-qualified pages (`[[Namespace/OldName]]`) require a separate query: `selectBlocksWithWikilink("Namespace/OldName")`.
- Add a test case with a fenced code block containing `[[OldName]]` — verify the link inside the code block is NOT rewritten.

**Files likely affected**:
- `migration/ChangeApplier.kt`
- `db/SteleDatabase.sq` (potential new query for namespace-qualified links)

---

### Destructive Default Safety — `DeleteBlock` Without `allowDestructive` [SEVERITY: High]

**Description**: If a migration's `where` predicate is accidentally too broad (e.g., `block -> true`), it would delete every block in the graph. The `allowDestructive = false` default prevents this at evaluation time, but the error message may not be informative enough for the author to diagnose the intent.

**Mitigation**:
- `DestructiveOperationException` must include: the migration ID, the number of blocks the delete would affect, and the first five affected block UUIDs and their content snippets.
- Dry-run mode should be run before setting `allowDestructive = true`. Enforce this in documentation and the CLI: `--allow-destructive` requires `--dry-run` to have been run first in the same session.
- Add a regression test: a migration with `allowDestructive = false` and a broad predicate throws with the correct count in the exception message.

**Files likely affected**:
- `migration/DslEvaluator.kt`
- `migration/MigrationRunner.kt`

---

### Kotlin/JS Long Precision — `applied_at` in `migration_changelog` [SEVERITY: Medium]

**Description**: `applied_at` is stored as `INTEGER` (epoch milliseconds). If `ChangelogRepository` ever deserializes this value into a Kotlin `Long` on the JS target via `kotlinx.serialization`, values after year 2255 will lose precision (exceeds `Number.MAX_SAFE_INTEGER`). In practice the value is read back only for display in CLI/UI, not for comparison logic.

**Mitigation**:
- `ChangelogRepository` reads `applied_at` as a raw SQLDelight `Long`. It is never passed to `kotlinx.serialization` JSON encoding.
- If a JSON export of the changelog is ever needed (e.g. portable log), use `kotlinx-datetime`'s `Instant.fromEpochMilliseconds(appliedAt).toString()` to produce an ISO-8601 string.
- Add a note in `ChangelogRepository` where `applied_at` is read: "Do not serialize this value as Long via kotlinx.serialization on JS targets."

**Files likely affected**:
- `migration/ChangelogRepository.kt`

---

### DAG Validation Eagerness — Missing `requires` Not Caught Until Startup [SEVERITY: Medium]

**Description**: If `Migrations.register()` registers a migration that references a `requires` ID that was accidentally omitted from the registry (e.g., the dependency migration was deleted), `DagValidator.validate()` throws `UnresolvedDependency` at app startup, preventing the graph from loading at all.

**Mitigation**:
- `DagValidator.validate()` runs before any migrations are applied and surfaces all errors at once (not just the first).
- The `UnresolvedDependency` error message must name the referencing migration ID and the missing dependency ID.
- Add a CI check: run `DagValidator.validate(MigrationRegistry.all())` as a unit test in `jvmTest` so missing dependencies are caught before the app is built.

**Files likely affected**:
- `migration/DagValidator.kt`
- `migration/migrations/Migrations.kt`

---

## Context Preparation Guide

Before starting implementation, read these files:

| File | Why |
|------|-----|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` | Integration point: `switchGraph()` where `MigrationRunner` hooks in |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt` | All migration writes must go through this actor |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/OperationLogger.kt` | Every `BlockChange` must be logged here for undo support |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DiffMerge.kt` | `DslEvaluator` reuses `DiffMerge.diff()` for idempotency checking |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/ContentHasher.kt` | `sha256()` is used by `MigrationChecksumComputer` |
| `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` | Schema to extend with `migration_changelog` table |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` | `Block` and `Page` field layout used by DSL predicates |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UuidMigration.kt` | Pattern to follow for integration inside `switchGraph()` |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/` | Existing test patterns (businessTest, IN_MEMORY backend usage) |
