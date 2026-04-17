# Research: Architecture

**Date**: 2026-04-14
**Dimension**: Design patterns, tradeoffs, integration points

---

## Summary

The migration framework should be modeled as a desired-state diff engine (Atlas-style) layered on top of
Sqitch-style explicit dependency DAGs, with a Liquibase-style SQLDelight changelog table for idempotency
tracking. The framework integrates as a standalone `MigrationRunner` that calls `GraphLoader` and
`GraphWriter` directly — not wired into the live editing path — and runs as a pre-load step inside
`GraphManager.switchGraph()`. The DSL is an internal Kotlin DSL (lambda-with-receiver), not an external SQL
parser, because KMP commonMain cannot link to ANTLR or heavy parser libraries on all targets.

---

## 1. Desired-State Diff Engine

### How Atlas does it

Atlas maintains a typed schema graph (tables, columns, indices, constraints as objects), inspects the live
database to produce a `current` graph, compiles the HCL/SQL spec into a `desired` graph, then walks both
graphs to emit a list of `SchemaChange` objects (AddTable, DropColumn, ModifyIndex, etc.). Each change is
an individual patch instruction. The diff is always minimal — only changed nodes produce instructions.
Critically, Atlas never stores the full desired schema in the changelog; only the *delta* moves forward.

### Generalizing to document trees

SteleKit blocks are a labeled tree: `Page → Block (uuid, content, level, parentUuid, properties)`. The
"desired state" for a migration is a predicate-selected subtree with specified target property values or
content transformations. The diff engine must produce `BlockChange` instructions analogous to Atlas's
`SchemaChange`:

```
sealed class BlockChange {
    data class UpsertProperty(val blockUuid: String, val key: String, val value: String) : BlockChange()
    data class DeleteProperty(val blockUuid: String, val key: String) : BlockChange()
    data class SetContent(val blockUuid: String, val newContent: String) : BlockChange()
    data class DeleteBlock(val blockUuid: String) : BlockChange()
    data class InsertBlock(val block: Block) : BlockChange()
}
```

**Current state** is read from `BlockRepository` (already in memory after `GraphLoader`). **Desired state**
is expressed as the set of `BlockChange` instructions that make the current state satisfy the migration's
postconditions. The engine does not need a full "desired tree" snapshot — it computes changes lazily by
querying the current state against the migration predicates.

### Diff algorithm

The existing `DiffMerge.diff()` in `db/DiffMerge.kt` already handles insert/update/delete at the block
level using UUID matching and `content_hash` comparison. The migration diff engine reuses this foundation:

1. The migration DSL defines a *selector* (which blocks to target) and a *transformer* (what to change).
2. The engine evaluates the selector against `BlockRepository` to get the *affected set*.
3. For each affected block, it applies the transformer to compute the desired `Block` state.
4. `DiffMerge.diff(existing, desired)` produces the concrete `BlockDiff` (toInsert, toUpdate, toDelete).
5. Only non-empty diffs are applied. If the diff is empty, the migration is already satisfied (idempotent).

**Tree-diff vs flat-diff tradeoff**: Myers/LCS tree diffs (GumTree, difftastic) are designed for
source-code where structural identity is position-based. SteleKit blocks have stable UUIDs, so identity
matching is already solved — position-based LCS is not needed. The existing UUID-keyed `DiffMerge` is
sufficient and avoids a heavy tree-diff dependency.

---

## 2. Migration DAG

### Sqitch's model

Sqitch models migrations (called *changes*) as a plan file listing changes in explicit order with
`requires` (positive dependency) and `conflicts` (negative dependency) declarations. Critically, Sqitch
**does not re-sort** the plan file — the declared order is canonical. It validates dependency ordering at
parse time and throws if a change appears before its dependencies. This is deliberate: auto-topological-sort
was removed because it produced non-deterministic orderings across runs.

The lesson for SteleKit: **store migrations in declared order, validate DAG correctness at registration
time, but do not auto-sort**. The author declares the intended order; the framework enforces consistency.

### Recommended DAG structure

```kotlin
data class Migration(
    val id: String,           // e.g. "V001__add-type-property"
    val description: String,
    val requires: List<String>,   // IDs of migrations that must have run first
    val conflicts: List<String>,  // IDs of migrations that must NOT have run
    val checksum: String,         // SHA-256 of the migration's DSL body
    val apply: MigrationDsl.() -> Unit,
    val revert: (MigrationDsl.() -> Unit)? = null,
)
```

### Cycle detection

Kahn's algorithm (BFS-based topological sort) is the standard O(V+E) approach. Run it once at
`MigrationRunner` startup over the registered migration set:

```
1. Build adjacency list: for each migration M, add edges M → each of M.requires
2. Compute in-degree for every node
3. Start queue with all zero-in-degree nodes
4. Drain queue, decrementing in-degrees; if at end not all nodes processed → cycle exists
```

If a cycle is detected, `MigrationRunner` throws at startup — fail fast before touching any data.
Kahn's is available in pure Kotlin, no library needed, and is trivial in commonMain.

### Execution order

After topological validation, execution order follows the registration order of migrations (Sqitch style).
The topological sort is used only for *validation*, not for reordering. This means migration authors must
declare migrations in a topologically valid order — a constraint the framework enforces with a clear error
message at startup rather than silently reordering.

---

## 3. Changelog Schema

### Liquibase's DATABASECHANGELOG model

Liquibase's tracking table stores: `ID`, `AUTHOR`, `FILENAME`, `DATEEXECUTED`, `ORDEREXECUTED`, `MD5SUM`,
`DESCRIPTION`, `COMMENTS`, `EXECTYPE`, `CONTEXTS`, `LABELS`, `LIQUIBASE`, `DEPLOYMENT_ID`. The checksum
detects tampering — if a previously-run changeset's content changes, Liquibase refuses to run.

### Recommended SQLDelight schema

```sql
CREATE TABLE migration_changelog (
    id              TEXT NOT NULL PRIMARY KEY,   -- e.g. "V001__add-type-property"
    description     TEXT NOT NULL,
    checksum        TEXT NOT NULL,               -- SHA-256 of DSL body (detects tampering)
    applied_at      INTEGER NOT NULL,            -- epoch millis
    execution_ms    INTEGER NOT NULL DEFAULT 0,  -- duration for performance tracking
    status          TEXT NOT NULL DEFAULT 'APPLIED',  -- APPLIED | FAILED | REVERTED
    applied_by      TEXT NOT NULL DEFAULT '',    -- session_id or user hint
    graph_id        TEXT NOT NULL,               -- which graph this was applied to
    changes_applied INTEGER NOT NULL DEFAULT 0,  -- count of BlockChange instructions executed
    error_message   TEXT                         -- non-null on FAILED status
);

CREATE INDEX idx_changelog_graph_id ON migration_changelog(graph_id);
CREATE INDEX idx_changelog_applied_at ON migration_changelog(applied_at);
```

**Key design decisions**:
- `graph_id` scopes the changelog per-graph (each graph has its own `RepositorySet`; migrations applied to
  Graph A do not appear as applied for Graph B).
- `checksum` uses the same `ContentHasher.sha256()` already in SteleKit, so no new hashing dependency.
- `status` allows `FAILED` rows to survive for diagnostics rather than being silently deleted.
- `changes_applied` is a useful audit field — a migration that ran but changed 0 blocks is a signal that
  the graph was already in the desired state.

The changelog table lives in the existing per-graph SQLite database (same `.db` file managed by
`DriverFactory`). No separate migration database is needed.

---

## 4. DSL Design

### External SQL parser vs internal Kotlin DSL

An external SQL-like DSL (e.g. parsing `SELECT blocks WHERE property('type') IS NULL`) would require an
ANTLR grammar or a custom recursive descent parser. ANTLR does not compile to Kotlin/Native or Kotlin/JS
without heavy shims. A custom parser is feasible but represents weeks of work for a constrained feature set.

The requirements explicitly scope out a "full SQL parser/runtime" — a constrained DSL is sufficient. A
**Kotlin internal DSL** (lambda-with-receiver) gives SQL-like expressiveness, full IDE support, compile-time
safety, and works on all KMP targets with zero parser infrastructure.

### Recommended DSL shape

```kotlin
// Migration declaration (lives in a .kt file, compiled into the app)
migration("V001__add-missing-type-property") {
    description = "Add type:: property to all blocks that lack one"
    requires("V000__baseline")

    apply {
        forBlocks(
            where = { block -> !block.properties.containsKey("type") && !block.isPageProperty }
        ) {
            setProperty("type", "note")
        }
    }

    revert {
        forBlocks(
            where = { block -> block.properties["type"] == "note" && block.wasAddedByMigration("V001") }
        ) {
            deleteProperty("type")
        }
    }
}
```

**DSL primitives**:

| Primitive | Description |
|---|---|
| `forBlocks(where) { ... }` | Select blocks by predicate, apply transformation to each |
| `forPages(where) { ... }` | Select pages by predicate |
| `setProperty(key, value)` | Upsert a block or page property |
| `deleteProperty(key)` | Remove a property |
| `setContent(newContent)` | Replace a block's content string |
| `deleteBlock()` | Delete the current block |
| `insertBlock(after = uuid) { ... }` | Insert a new block |
| `rename(oldName, newName)` | Rename a page |

**Why not WHERE/UPDATE SQL syntax**: The block model's properties are stored as a JSON map in SQLite, not
as first-class columns. SQL predicates like `WHERE properties->>'type' IS NULL` would require SQLite JSON
functions, which are not universally available across all SQLite versions bundled with Android/iOS. The
Kotlin lambda predicate compiles to a simple `block.properties["type"] == null` — no SQL generation needed.

### Idempotency contract

Each `forBlocks` call checks the current state before writing. The engine only emits `BlockChange`
instructions for blocks where the desired state differs from the current state (via `DiffMerge`). This means
running the same migration twice produces zero changes on the second run — idempotency is structural, not
reliant on changelog guards alone.

The changelog guard is a second layer: if `id` is in `migration_changelog` with `status = APPLIED` and the
checksum matches, the migration is skipped entirely without evaluating any predicates.

---

## 5. Integration Points with SteleKit

### Where the runner hooks in

The migration engine should be a standalone `MigrationRunner` that is **not** wired into the live editing
path (`BlockEditor` → `BlockStateManager` → `GraphWriter`). It operates on the repository layer directly,
before the ViewModel activates the loaded graph.

Recommended integration point: **`GraphManager.switchGraph()`**, after `UuidMigration.runIfNeeded()`
completes and before `awaitPendingMigration()` returns.

```
GraphManager.switchGraph(id)
  └── UuidMigration.runIfNeeded(db)          [existing one-shot migration]
  └── MigrationRunner.runPending(db, repoSet, graphId)  [NEW: run all unapplied content migrations]
  └── _pendingMigration.complete(Unit)        [signals callers graph is ready]
```

### `MigrationRunner` interface

```kotlin
class MigrationRunner(
    private val registry: MigrationRegistry,      // holds all registered Migration objects
    private val writeActor: DatabaseWriteActor,   // serialized writes via existing actor
    private val operationLogger: OperationLogger, // log all changes for undo
    private val db: SteleDatabase,                // direct access for changelog table
) {
    suspend fun runPending(graphId: String, repoSet: RepositorySet)
    suspend fun revert(migrationId: String, graphId: String, repoSet: RepositorySet)
    fun validate(): List<DagError>               // cycle check, dependency resolution
}
```

**Key integration facts**:
- `MigrationRunner` uses `writeActor` (the existing `DatabaseWriteActor`) to serialize writes. This
  prevents `SQLITE_BUSY` conflicts, exactly the same constraint that led to the actor in `GraphLoader`.
- All `BlockChange` instructions go through `operationLogger.logInsert/logUpdate/logDelete()`, so
  migration-applied changes appear in the op log and can be undone via `UndoManager`.
- `MigrationRunner` does **not** call `GraphWriter.savePage()` directly — it writes to the DB repository
  only. `GraphWriter` is the live-edit save path; migration writes go DB-first. The markdown files on disk
  are updated by the existing `GraphLoader` → file-watch pipeline, or explicitly flushed after migration
  via `GraphWriter.savePage()` for each modified page (a single post-migration flush pass).

### `MigrationRegistry`

```kotlin
object MigrationRegistry {
    private val migrations = mutableListOf<Migration>()

    fun register(migration: Migration) { migrations.add(migration) }
    fun all(): List<Migration> = migrations.toList()
}
```

Migrations are registered at app startup (in `StelekitApp` or a dedicated `Migrations.kt` file) before
`GraphManager.switchGraph()` is called. This is the Flyway model: migration objects are code, not files
discovered at runtime.

---

## Proposed Component Diagram

```
  ┌─────────────────────────────────────────────────────────────────────┐
  │  App Startup (StelekitApp / Main.kt)                                │
  │  MigrationRegistry.register(V001, V002, ...)                        │
  └──────────────────────────┬──────────────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  GraphManager.switchGraph(graphId)                                   │
  │    1. RepositoryFactory.createRepositorySet()                        │
  │    2. UuidMigration.runIfNeeded(db)        [existing]                │
  │    3. MigrationRunner.runPending(graphId)  [NEW]                     │
  │         │                                                            │
  │         ├── MigrationRegistry.all()  → List<Migration>              │
  │         ├── DagValidator.validate()  → fail fast on cycle            │
  │         ├── ChangelogReader.applied(graphId) → Set<String>           │
  │         │                                                            │
  │         │   for each unapplied migration (in declared order):        │
  │         │     ├── DslEvaluator.evaluate(migration.apply)             │
  │         │     │     ├── BlockRepository.selectBlocksByPageUuid()     │
  │         │     │     ├── DiffEngine.computeChanges()  ← DiffMerge     │
  │         │     │     └── BlockChange list                             │
  │         │     ├── WriteActor.applyChanges(BlockChange list)          │
  │         │     │     └── OperationLogger.logInsert/Update/Delete()    │
  │         │     └── ChangelogWriter.record(id, checksum, status)       │
  │         │                                                            │
  │    4. _pendingMigration.complete()                                   │
  └──────────────────────────────────────────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  StelekitViewModel.loadGraph()                                       │
  │    GraphLoader.loadGraph() — reads from already-migrated DB          │
  └──────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────┐     ┌────────────────────────────┐
  │  MigrationRunner    │────▶│  DatabaseWriteActor        │
  │  - runPending()     │     │  (existing, serialized)    │
  │  - revert()         │     └────────────┬───────────────┘
  │  - validate()       │                  │
  └─────────────────────┘                  ▼
                                ┌──────────────────────────┐
  ┌─────────────────────┐       │  OperationLogger         │
  │  MigrationRegistry  │       │  (existing, for undo)    │
  │  register(migration)│       └──────────────────────────┘
  └─────────────────────┘
  ┌─────────────────────┐
  │  DslEvaluator       │       ┌──────────────────────────┐
  │  forBlocks(where){} │──────▶│  DiffMerge (existing)    │
  │  setProperty()      │       └──────────────────────────┘
  │  deleteBlock()      │
  └─────────────────────┘
  ┌─────────────────────┐
  │  migration_changelog│  (NEW SQLDelight table in SteleDatabase.sq)
  │  id, checksum,      │
  │  status, graph_id,  │
  │  applied_at         │
  └─────────────────────┘
```

---

## 6. Testing Architecture

### How Flyway/Liquibase test migration engines

Flyway's canonical test strategy uses three tiers:

1. **Unit tests on individual migration objects**: Each Java migration class is tested in isolation against
   an in-memory H2/SQLite database seeded to the pre-migration state. The migration's `migrate()` method
   is called, then assertions verify the post-migration state. Spring Boot test docs recommend
   `spring.flyway.target=V{N-1}` to stop just before the migration under test.

2. **Integration tests with Testcontainers**: The full migration sequence is run against a real database
   in a Docker container. These verify that migrations compose correctly — migration N does not break
   migration N+1.

3. **Snapshot/regression tests**: After the full migration sequence runs on a known fixture graph, the
   resulting DB state is serialized and compared to a stored snapshot. Unexpected diffs are test failures.

### Recommended test strategy for SteleKit

SteleKit has no Docker/Testcontainers (KMP, JVM-first). Instead, three analogous tiers:

**Tier 1 — Unit tests per migration (jvmTest / businessTest)**

```kotlin
class V001AddTypePropertyTest {
    private val repoSet = RepositoryFactory.createRepositorySet(GraphBackend.IN_MEMORY, scope)

    @Test
    fun `adds type property to blocks that lack it`() {
        // Arrange: seed blocks WITHOUT type property
        repoSet.blockRepository.insertBlock(blockWithoutType)

        // Act: run just this migration
        val runner = MigrationRunner(registryOf(V001), writeActor, operationLogger, db)
        runner.runPending(graphId = "test", repoSet)

        // Assert: block now has type:: note
        val updated = repoSet.blockRepository.getBlock(blockWithoutType.uuid)
        assertEquals("note", updated?.properties?.get("type"))
    }

    @Test
    fun `is idempotent - running twice produces same result`() {
        repoSet.blockRepository.insertBlock(blockWithoutType)
        runner.runPending(graphId, repoSet)
        val changesFirst = runner.lastChangeCount
        runner.runPending(graphId, repoSet)  // second run: migration already in changelog
        assertEquals(0, runner.lastChangeCount)
    }
}
```

The `GraphBackend.IN_MEMORY` backend already exists in SteleKit for tests — this is the exact analog of
Flyway's in-memory H2. No new infrastructure needed.

**Tier 2 — DAG integration tests**

Tests that register a sequence of migrations with explicit dependencies and verify:
- Correct execution order (declared order respected)
- Cycle detection throws `MigrationCycleException` with the cycle path in the message
- `conflicts` declarations cause `MigrationConflictException` when both sides are applied
- Checksum mismatch on a previously-applied migration throws `MigrationTamperedError`

**Tier 3 — Snapshot/regression tests**

Use an existing fixture graph (e.g. the `demo graph` from `@HelpPage`) as the input. Run all registered
migrations. Serialize the resulting block tree (content + properties, no timestamps) as a deterministic
JSON snapshot. Compare against a checked-in expected snapshot. This is a golden-file test: any migration
that silently changes more than intended will fail this test.

SteleKit already has Roborazzi for screenshot snapshot tests in `jvmTest`. The same golden-file pattern
applies here for DB state snapshots — no new test library required, just `kotlinx.serialization.json`
with deterministic serialization.

**Property-based tests (optional, high value)**

The `forBlocks(where) { setProperty(...) }` primitive has a clean invariant: for every block B where
`where(B) == true`, after the migration `B.properties[key] == value`. This is testable with
`kotest-property` (Arbitrary generators for Block) without needing a full graph. Add to `businessTest`.

---

## Integration with SteleKit

### Specific classes and integration points

| Existing class | Role in migration framework |
|---|---|
| `GraphManager.switchGraph()` | Trigger point: run migrations after `UuidMigration`, before graph load |
| `DatabaseWriteActor` | All migration writes go through the actor (serialized, avoids SQLITE_BUSY) |
| `OperationLogger` | Log every `BlockChange` for undo support via existing `UndoManager` |
| `DiffMerge.diff()` | Reuse for computing which blocks actually need changes (idempotency core) |
| `ContentHasher.sha256()` | Compute migration checksums for the changelog |
| `RepositorySet.blockRepository` | Read current block state during diff computation |
| `SteleDatabase.sq` | Add `migration_changelog` table definition |
| `GraphBackend.IN_MEMORY` | Unit test backend for migration tests (no files needed) |
| `UndoManager` | Revert migration changes via existing undo infrastructure (for `revert()`) |

### What does NOT need to change

- `GraphLoader` — migrations run against the DB, not the markdown files. `GraphLoader` sees the already-
  migrated DB on the next load.
- `GraphWriter` — live editing path is untouched. A post-migration flush (optional) would call
  `GraphWriter.savePage()` for modified pages to keep markdown files in sync.
- `OutlinerPipeline` / `MarkdownParser` — migrations work at the Block model level, not the parse level.
- `StelekitViewModel` / UI — migrations are transparent to the UI. They complete before `loadGraph()`.

---

## Recommendations

Prioritized architectural decisions in order:

1. **Internal Kotlin DSL over external SQL parser.** KMP commonMain cannot host ANTLR on all targets.
   Lambda-with-receiver gives equivalent expressiveness with compile-time safety and zero parser
   infrastructure. This is the highest-leverage decision — it unblocks all other work.

2. **`migration_changelog` table in the existing per-graph SQLite database.** Reuse `ContentHasher` for
   checksums, `SteleDatabase.sq` for schema, and `DriverFactory` for connection. No separate migration DB.
   Scope by `graph_id` to handle multi-graph. Add 6 fields: id, description, checksum, applied_at,
   status, graph_id.

3. **`MigrationRunner` as a standalone component, not integrated into GraphLoader.** It runs as a
   pre-load step in `GraphManager.switchGraph()` alongside the existing `UuidMigration`. This keeps the
   live editing path (BlockEditor → GraphWriter) clean and makes migrations a distinct operational phase.

4. **Reuse `DatabaseWriteActor` and `OperationLogger` for all migration writes.** This gives undo support
   for free via `UndoManager` and prevents `SQLITE_BUSY` races with no new synchronization code.

5. **Explicit DAG ordering (Sqitch model) with Kahn's algorithm for validation only.** Declare migrations
   in topologically valid order; validate at startup; do not auto-sort. This matches how Sqitch evolved
   after removing auto-topological-sort: deterministic, predictable, fails fast.

6. **Two-layer idempotency: `DiffMerge`-based structural check + changelog guard.** Structural idempotency
   means the migration body itself only emits changes when the current state differs from desired.
   The changelog guard prevents re-evaluation entirely for already-applied migrations. Belt and suspenders.

7. **Test with `GraphBackend.IN_MEMORY` + golden-file snapshots.** No Testcontainers, no Docker. Seed
   known fixture state → run migrations → assert post-state. Add golden-file regression test against the
   demo graph. Property-based tests for the `forBlocks` predicate invariant as a bonus.
