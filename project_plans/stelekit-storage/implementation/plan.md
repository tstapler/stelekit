# Implementation Plan: SteleKit Storage Architecture Redesign

**Status**: Ready for implementation
**Date**: 2026-04-13
**Project**: stelekit-storage
**ADRs**: See `project_plans/stelekit-storage/decisions/`

---

## Overview

This plan implements Option A (Operation Log + Sidecar) from the research synthesis in three phases. Each phase is independently shippable. The architecture moves SQLite from a disposable cache to the authoritative CRDT state, with markdown files as a human-readable serialization format.

**Success criteria addressed per phase:**

| Criterion | Phase 1 | Phase 2 | Phase 3 |
|-----------|---------|---------|---------|
| 1. Undo across restarts | - | YES | YES |
| 2. Git sync merges cleanly | partial | partial | YES |
| 3. Find-replace batch undo | - | - | YES |
| 4. Stable block identity | YES | YES | YES |
| 5. Markdown stays clean | YES | YES | YES |
| 6. Re-import is no-op | YES | YES | YES |

---

## Architecture Decision Records

All load-bearing decisions are documented in separate ADR files:

- `project_plans/stelekit-storage/decisions/ADR-001-block-uuid-scheme.md`
- `project_plans/stelekit-storage/decisions/ADR-002-sidecar-format.md`
- `project_plans/stelekit-storage/decisions/ADR-003-operation-log-schema.md`
- `project_plans/stelekit-storage/decisions/ADR-004-merge-algorithm.md`
- `project_plans/stelekit-storage/decisions/ADR-005-undo-redo-model.md`
- `project_plans/stelekit-storage/decisions/ADR-006-uuid-migration.md`

---

## Phase 1: Stable Identity + Diff Merge

**Delivers**: Success criteria 4 (stable block identity), 5 (clean markdown), 6 (no-op re-import)

### Story 1.1: Position-Only UUID Scheme

**Scope**: Change UUID derivation to drop `content` from seed. Existing data migrated in one atomic transaction.

**Acceptance criteria**:
- `generateUuid` produces identical output for the same `filePath + parentUuid + siblingIndex` regardless of block content
- Re-parsing an unchanged file produces the same UUIDs as the previous parse
- All existing UUID references (FK columns, backlinks, properties) are updated atomically
- A validation query after migration confirms zero broken FK references

#### Task 1.1.1 — Drop content from `generateUuid` seed

- **Objective**: Remove `parsedBlock.content` from the seed string in `generateUuid`
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (line ~108: change seed to `"$pagePath:${parentUuid ?: "root"}:$blockIndex"`)
- **Implementation steps**:
  1. Change the seed variable: remove `:${parsedBlock.content}` from the concatenation
  2. Update the kdoc comment on `generateUuid` to reflect the new scheme
  3. Add a unit test asserting that editing content does not change the returned UUID
- **Validation**: Run existing `jvmTest` suite; confirm `BacklinkRenamerTest` still passes; add one new test in a new `UuidGeneratorTest` class
- **INVEST**: Independent (no downstream dependencies before migration); testable in isolation

#### Task 1.1.2 — SQLDelight migration script for UUID re-derivation

- **Objective**: Write a Kotlin-side migration that re-derives all block UUIDs from position-only seeds and cascades updates to all FK columns
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UuidMigration.kt` (new file)
  - `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (add `selectBlocksWithPositionData` query)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (call migration on first open of existing DB)
- **Implementation steps**:
  1. Add a `selectBlocksWithPositionData` named query to `SteleDatabase.sq` that returns `uuid, page_uuid, parent_uuid, position, properties` for all blocks (needed to re-derive position-based UUID without content)
  2. Create `UuidMigration.kt` with a `suspend fun run(db: SteleDatabase, graphLoader: GraphLoader)` that:
     a. Opens a transaction
     b. Re-reads every `.md` file in the graph and re-derives UUIDs via the updated `generateUuid`
     c. For each block where old UUID != new UUID: `UPDATE blocks SET uuid = newUuid WHERE uuid = oldUuid`, then cascade to `parent_uuid`, `left_uuid`, `block_references`, `properties`
     d. Runs a validation query to confirm zero orphaned FK references
     e. Commits; on any error, rolls back and throws
  3. Add a `db_schema_version` entry in a new `metadata` table (see Task 1.1.3) to record migration completion
  4. In `GraphManager`, detect when the DB exists but the migration has not run and call `UuidMigration.run(...)` before loading the graph
- **Validation**: Test on a copy of a real graph; verify backlinks resolve correctly after migration; add `UuidMigrationTest` in `jvmTest`
- **INVEST**: Depends on Task 1.1.1 being merged; estimable at 3–4h; not trivially splittable further

**Note**: See `ADR-006-uuid-migration.md` for the chosen migration approach and alternatives rejected.

#### Task 1.1.3 — `metadata` table for schema versioning

- **Objective**: Add a `metadata` key-value table to track DB schema version, session ID, and other graph-level facts
- **Files**:
  - `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- **Implementation steps**:
  1. Add `CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)` to the schema
  2. Add `upsertMetadata`, `selectMetadata` named queries
  3. On DB creation, insert `('schema_version', '1')` and `('session_id', generateV7())`
- **Validation**: Confirm existing tests compile; verify `GraphManager` can read `session_id` on startup

---

### Story 1.2: Diff-Based Merge (Replace DELETE+INSERT)

**Scope**: Replace the `deleteBlocksByPageUuid` + `insertBlock` cycle with a diff that SKIPs unchanged blocks, UPDATEs changed ones, INSERTs new ones, and DELETEs removed ones.

**Acceptance criteria**:
- Re-parsing an unchanged file results in zero DB writes
- A file with one edited line produces exactly one `updateBlockContent` call, not a full page delete+reinsert
- Content-hash comparison determines skip vs. update

#### Task 1.2.1 — Add `content_hash` population to insert/update path

- **Objective**: Ensure `content_hash` is populated on every block write (it exists in the schema but may be null for older records)
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (`parseAndSavePage` and block construction)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/HashUtils.kt` (new file with SHA-256 or FNV-1a content hash)
- **Implementation steps**:
  1. Create `HashUtils.kt` with `fun contentHash(content: String): String` using FNV-1a (already available in `UuidGenerator`) normalized to lowercase-trimmed content
  2. In `GraphLoader`, set `contentHash` on each `Block` before calling `writeActor.saveBlocks`
  3. Verify `insertBlock` in `SteleDatabase.sq` already includes `content_hash` column (it does)
- **Validation**: After one full graph load, `SELECT COUNT(*) FROM blocks WHERE content_hash IS NULL` returns 0

#### Task 1.2.2 — Implement `DiffMerge` in `GraphLoader`

- **Objective**: Replace the delete-all pattern with a position-aware diff merge
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (`parseAndSavePage`)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DiffMerge.kt` (new file)
  - `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (add `selectBlocksByPageUuidWithHash` query returning uuid + content_hash + position for efficient diff)
- **Implementation steps**:
  1. Create `DiffMerge.kt` with:
     ```
     data class BlockDiff(
         val toInsert: List<Block>,
         val toUpdate: List<Block>,   // content changed, UUID stable
         val toDelete: List<String>,  // UUIDs no longer present
         val unchanged: List<String>  // UUIDs with matching content_hash
     )
     fun diff(existing: List<ExistingBlockSummary>, parsed: List<Block>): BlockDiff
     ```
  2. Match blocks by UUID (position-derived, now stable). Blocks present in both lists are compared by `content_hash`; match = SKIP, differ = UPDATE. Blocks only in parsed = INSERT. Blocks only in existing = DELETE.
  3. Replace the `deleteBlocksByPageUuid` call in `GraphLoader.parseAndSavePage` with:
     - Load `ExistingBlockSummary` list from DB via new query
     - Compute `DiffMerge.diff(existing, parsedBlocks)`
     - Execute: `writeActor.saveBlocks(diff.toInsert + diff.toUpdate)`, `diff.toDelete.forEach { writeActor.deleteBlock(it) }`
  4. Keep `DeleteBlocksForPage` in `DatabaseWriteActor` for explicit page deletion only (no longer used on re-parse)
- **Validation**: Unit test `DiffMerge` with all four combinations; integration test in `jvmTest` that parses a file twice and verifies DB write count via a spy

**Note**: See `ADR-004-merge-algorithm.md` for the rationale behind position-based UUID matching in Phase 1.

#### Task 1.2.3 — Conflict-marker detection before import

- **Objective**: Reject any import of a file that contains git conflict markers; emit a `WriteError` instead of silently ingesting broken content
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (`parseAndSavePage`)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/ConflictMarkerDetector.kt` (new file)
- **Implementation steps**:
  1. Create `ConflictMarkerDetector.kt` with `fun detect(content: String): Boolean` — checks for `<<<<<<<`, `=======`, `>>>>>>>` on their own lines
  2. At the top of `parseAndSavePage`, call `ConflictMarkerDetector.detect(content)`. If true: log error, emit `_writeErrors` with a `WriteError.ConflictMarkersDetected(filePath)` variant, and return early without touching the DB
  3. Add `ConflictMarkersDetected` variant to the `WriteError` sealed class
- **Validation**: Unit test with a synthetic conflicted file; verify existing pages are untouched

#### Task 1.2.4 — `.gitignore` guard for `.db` files

- **Objective**: Prevent the SQLite WAL files from being committed to git
- **Files**:
  - Documentation / user-facing check only — `GraphManager.kt` logs a warning if `.db` is tracked
- **Implementation steps**:
  1. On `GraphManager.addGraph(path)`, check whether `$graphPath/.gitignore` contains `*.db`. If not, log a `WARN` advising the user to add it.
  2. Optionally, offer to append the entry automatically (platform-specific `PlatformFileSystem.appendFile`)
  3. Document the requirement in `CLAUDE.md` under "Graph Setup"
- **Validation**: Manual verification; no automated test needed for the gitignore check

---

## Phase 2: Operation Log + Undo

**Delivers**: Success criterion 1 (undo across restarts), progress toward criterion 2

### Story 2.1: Operation Log Schema

**Scope**: Add `operations` and `logical_clock` tables to the SQLDelight schema. All DB mutations routed through `DatabaseWriteActor` begin writing a log entry as a side effect.

**Acceptance criteria**:
- Every block insert, update, delete, and move is recorded in the `operations` table with a monotonically increasing `seq`
- `logical_clock` stores one row per session with the current `seq` counter
- Operations can be queried by `session_id`, by `page_uuid`, or by `seq` range

**Note**: See `ADR-003-operation-log-schema.md` for the full column specification and compaction strategy.

#### Task 2.1.1 — Add `operations` and `logical_clock` tables

- **Objective**: Extend the SQLDelight schema with the two new tables
- **Files**:
  - `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- **Implementation steps**:
  1. Add `operations` table:
     ```sql
     CREATE TABLE operations (
         op_id       TEXT NOT NULL PRIMARY KEY,  -- UUID v7 (time-ordered)
         session_id  TEXT NOT NULL,
         seq         INTEGER NOT NULL,            -- Lamport clock value
         op_type     TEXT NOT NULL,               -- INSERT_BLOCK | UPDATE_BLOCK | DELETE_BLOCK | MOVE_BLOCK | BATCH_START | BATCH_END | SYNC_BARRIER
         entity_uuid TEXT,                        -- block UUID affected (NULL for BATCH/SYNC)
         page_uuid   TEXT,
         payload     TEXT NOT NULL,               -- JSON: {"before": {...}, "after": {...}}
         created_at  INTEGER NOT NULL
     );
     ```
  2. Add `logical_clock` table:
     ```sql
     CREATE TABLE logical_clock (
         session_id  TEXT NOT NULL PRIMARY KEY,
         seq         INTEGER NOT NULL DEFAULT 0
     );
     ```
  3. Add indices: `idx_operations_session_seq ON operations(session_id, seq)`, `idx_operations_page ON operations(page_uuid)`, `idx_operations_entity ON operations(entity_uuid)`
  4. Add named queries: `insertOperation`, `selectOperationsBySession`, `selectOperationsByPageUuid`, `selectOperationsSince`, `incrementClock`, `selectClock`
  5. Bump `metadata` `schema_version` to `'2'`
- **Validation**: Compile `jvmTest`; verify SQLDelight code generation succeeds

#### Task 2.1.2 — `OperationLogger` service

- **Objective**: Encapsulate all operation-log writes behind a single service that manages the Lamport clock
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/OperationLogger.kt` (new file)
- **Implementation steps**:
  1. `OperationLogger(db: SteleDatabase, sessionId: String)` — reads current `seq` from `logical_clock` on init
  2. `suspend fun log(opType: OpType, entityUuid: String?, pageUuid: String?, before: BlockSnapshot?, after: BlockSnapshot?)` — increments `seq`, inserts one `operations` row using `UuidGenerator.generateV7()` as `op_id`
  3. `suspend fun insertSyncBarrier()` — logs `SYNC_BARRIER` with no entity; used by Phase 3
  4. `BlockSnapshot` data class: `uuid, content, position, parentUuid, leftUuid, properties`
- **Validation**: Unit test covering clock monotonicity; test that `before`/`after` JSON round-trips correctly

#### Task 2.1.3 — Wire `OperationLogger` into `DatabaseWriteActor`

- **Objective**: Every write through the actor also writes an op-log entry, without exposing the op-log to callers
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (pass `OperationLogger` to actor)
- **Implementation steps**:
  1. Add `private val opLogger: OperationLogger?` to `DatabaseWriteActor` constructor (nullable so tests can omit it)
  2. In `processRequest(SavePage)`: no op-log entry (page saves are not undoable at block granularity)
  3. In `flushBatch(SaveBlocks)`: before the batch insert, fetch existing snapshots for modified UUIDs; after insert, log `INSERT_BLOCK` or `UPDATE_BLOCK` for each block based on whether it existed before
  4. In `processRequest(DeleteBlocksForPage)`: fetch all block snapshots before delete; log `DELETE_BLOCK` for each
  5. In `execute(DeleteBlock)`: same — fetch before-snapshot, then log
  6. In `execute(SaveBlock / updateBlockContent)`: log `UPDATE_BLOCK` with before/after
- **Validation**: Integration test confirming that parsing a two-block page generates two `INSERT_BLOCK` entries; editing one block generates one `UPDATE_BLOCK` entry

---

### Story 2.2: UndoManager

**Scope**: Implement undo/redo driven by the operation log. Undo inverts the most recent operation for the current session (within the undo boundary). Redo re-applies it.

**Acceptance criteria**:
- `UndoManager.undo()` inverts the last undoable operation for the current session
- Undo does not cross a `SYNC_BARRIER` boundary
- `UndoManager.redo()` re-applies a previously undone operation
- Undo stack is reconstructed from the DB on app restart (not held in memory only)
- `BATCH_OPERATION` pairs are inverted as a unit

**Note**: See `ADR-005-undo-redo-model.md` for undo depth bounds, barrier semantics, and BATCH wrapper design.

#### Task 2.2.1 — `UndoManager` core

- **Objective**: Implement the undo/redo state machine
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UndoManager.kt` (new file)
- **Implementation steps**:
  1. Constructor: `UndoManager(db: SteleDatabase, writeActor: DatabaseWriteActor, sessionId: String)`
  2. `suspend fun undo()`:
     a. Query `selectOperationsBySession` in reverse `seq` order, skipping already-undone ops (track in-memory `undoneOpIds: Set<String>`)
     b. Stop at `SYNC_BARRIER` or if no more ops
     c. If op is `BATCH_END`, find matching `BATCH_START` and invert all ops between them as a unit
     d. Invert op: `UPDATE_BLOCK` swaps `before`/`after`; `INSERT_BLOCK` becomes `deleteBlock`; `DELETE_BLOCK` becomes `saveBlock`
     e. Execute inversion via `writeActor.execute {...}`
     f. Add `op_id` to `undoneOpIds`
  3. `suspend fun redo()`: replay the most recently undone op from `undoneOpIds` in forward order
  4. `val canUndo: StateFlow<Boolean>` and `val canRedo: StateFlow<Boolean>` for UI binding
  5. Undo depth guard: skip ops older than 90 days or if total payload size exceeds 100 MB (query sum of `length(payload)`)
- **Validation**: Unit tests for: undo INSERT, undo UPDATE, undo DELETE, undo BATCH, undo stops at SYNC_BARRIER, redo after undo

#### Task 2.2.2 — `BATCH_OPERATION` wrapper in `DatabaseWriteActor`

- **Objective**: Allow callers to group multiple writes into a single undoable unit
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
- **Implementation steps**:
  1. Add `WriteRequest.BeginBatch` and `WriteRequest.EndBatch` subtypes, each carrying a `batchId: String`
  2. Add `suspend fun executeBatch(batchId: String, block: suspend DatabaseWriteActor.() -> Unit)` that sends `BeginBatch`, executes the lambda, sends `EndBatch`
  3. In `OperationLogger`, handle `BATCH_START` / `BATCH_END` op types with matching `batchId` in payload
- **Validation**: Test that a batch of three writes appears as three ops bracketed by BATCH_START/END; undo collapses them

#### Task 2.2.3 — Expose undo/redo in `StelekitViewModel`

- **Objective**: Wire `UndoManager` into the VM so the UI can trigger undo/redo
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (expose `UndoManager` per graph)
- **Implementation steps**:
  1. Add `undoManager: UndoManager` field to `RepositorySet` (created in `GraphManager`)
  2. Add `fun undo()` and `fun redo()` to `StelekitViewModel` delegating to the active graph's `UndoManager`
  3. Expose `canUndo` and `canRedo` as `StateFlow<Boolean>` from the VM
  4. Add keyboard shortcut handling in `App.kt` for `Ctrl+Z` / `Ctrl+Shift+Z`
- **Validation**: Manual test: type text, undo, verify content reverts; restart app, undo again, verify content reverts

---

## Phase 3: Sidecar + Git Sync Merge

**Delivers**: Success criteria 2 (git sync merges cleanly), 3 (find-replace batch undo)

### Story 3.1: Sidecar File Read/Write

**Scope**: Implement `.stelekit/pages/<slug>.meta.json` NDJSON sidecar files. The sidecar maps block UUIDs to content hashes and positions, enabling UUID recovery after a git pull that changes block content.

**Acceptance criteria**:
- Every page export (`GraphWriter.savePageInternal`) also writes the sidecar
- Every page import (`GraphLoader.parseAndSavePage`) reads the sidecar if present
- Sidecar contains one JSON line per block: `{"uuid":"...","hash":"...","pos":N}`
- The sidecar file diffs cleanly in `git diff` (one line changed per edited block)

**Note**: See `ADR-002-sidecar-format.md` for the NDJSON choice and per-line format.

#### Task 3.1.1 — `SidecarManager` read/write

- **Objective**: Implement read and write of the NDJSON sidecar file
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/SidecarManager.kt` (new file)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (ensure `ensureDirectoryExists` is available)
- **Implementation steps**:
  1. `SidecarManager(fileSystem: PlatformFileSystem, graphPath: String)`
  2. `fun sidecarPath(pageSlug: String): String` = `"$graphPath/.stelekit/pages/$pageSlug.meta.json"`
  3. `suspend fun write(pageSlug: String, blocks: List<Block>)`: serialize each block as one JSON line `{"uuid":"...","hash":"...","pos":N,"parent":"..."}`, write NDJSON file. Create `.stelekit/pages/` directory if absent.
  4. `suspend fun read(pageSlug: String): Map<String, SidecarEntry>?`: parse NDJSON; return map keyed by `hash` (content hash → UUID). Return null if file absent.
  5. `data class SidecarEntry(val uuid: String, val pos: Int, val parentUuid: String?)`
- **Validation**: Round-trip test: write then read; confirm map is consistent

#### Task 3.1.2 — Wire `SidecarManager` into `GraphWriter`

- **Objective**: After every page file write, atomically write the sidecar
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` (`savePageInternal`)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (pass `SidecarManager` to `GraphWriter`)
- **Implementation steps**:
  1. Add `sidecarManager: SidecarManager?` to `GraphWriter` constructor
  2. At the end of `savePageInternal` (after successful `fileSystem.writeFile`), call `sidecarManager?.write(pageSlug, blocks)`
  3. The page slug is derived from `FileUtils.sanitizeFileName(page.name)`
- **Validation**: After saving a page, verify `.stelekit/pages/<slug>.meta.json` exists with correct content

#### Task 3.1.3 — Wire `SidecarManager` into `GraphLoader`

- **Objective**: On import, use the sidecar UUID map to assign stable UUIDs to blocks matched by content hash
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (`generateUuid`, `parseAndSavePage`)
- **Implementation steps**:
  1. Pass `SidecarManager` into `GraphLoader` constructor
  2. In `parseAndSavePage`, before UUID assignment, call `sidecarManager?.read(pageSlug)` to get the hash-to-UUID map
  3. Extend `generateUuid` signature to accept `sidecarMap: Map<String, SidecarEntry>?`. If a block's `contentHash` exists in the map, return the sidecar UUID. Else fall back to position-derived UUID.
  4. This means a block moved to a new position but with identical content retains its UUID after a git pull
- **Validation**: Simulate a git pull that reorders two blocks; verify both retain their UUIDs; verify DB diff produces two MOVE_BLOCK ops (not DELETE+INSERT)

---

### Story 3.2: Block Tree Reconciliation

**Scope**: On import after a git pull, reconcile the file's block tree against both the sidecar UUID map and the operation log. Detect forks when the same UUID has diverging histories on two machines.

**Acceptance criteria**:
- A git pull that adds a block on one machine merges cleanly with a content edit on the other machine
- A git pull that modifies the same block on both machines creates a `fork_group_id` pair
- Forked blocks are visible in the UI as a conflict requiring resolution
- `BATCH_OPERATION` wraps find-replace across all pages for atomic undo

**Note**: See `ADR-004-merge-algorithm.md` for the Kleppmann 2021 move algorithm and fork detection.

#### Task 3.2.1 — Add `fork_group_id` to `blocks` table

- **Objective**: Add the column that represents diverging concurrent edits to the same block
- **Files**:
  - `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (add `forkGroupId: String?` to `Block`)
- **Implementation steps**:
  1. Add `fork_group_id TEXT` column to `blocks` table (nullable, default null)
  2. Add index `idx_blocks_fork_group ON blocks(fork_group_id)` (sparse — most rows null)
  3. Add named queries: `selectForkedBlocks`, `updateBlockForkGroup`, `resolveForkedBlock`
  4. Bump `schema_version` to `'3'`
  5. Add `forkGroupId: String?` to the `Block` domain model in `Models.kt`
- **Validation**: Compile and confirm SQLDelight generation; existing tests unaffected (new column nullable)

#### Task 3.2.2 — `TreeReconciler` for post-git-pull merge

- **Objective**: Implement the merge logic that runs when an external file change is detected after a git pull
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/TreeReconciler.kt` (new file)
- **Implementation steps**:
  1. `TreeReconciler(db: SteleDatabase, opLogger: OperationLogger, writeActor: DatabaseWriteActor)`
  2. `suspend fun reconcile(pageUuid: String, parsedBlocks: List<Block>, sidecarMap: Map<String, SidecarEntry>?)`:
     a. Load current DB blocks for the page
     b. For each parsed block: if UUID from sidecar matches a DB block with identical `seq` history — SKIP. If DB block exists but op histories diverge (same UUID, different `seq` on remote and local) — create fork pair: insert duplicate block with new UUID, set `fork_group_id` on both.
     c. New blocks (UUID not in DB) — INSERT with `INSERT_BLOCK` op-log entry
     d. Blocks in DB but not in parsed file — DELETE with `DELETE_BLOCK` op-log entry
     e. After all merges, call `opLogger.insertSyncBarrier()`
  3. The fork detection condition: a block UUID is present in both the sidecar and the DB, the sidecar's `hash` differs from the DB's `content_hash`, and the DB's operation log shows local edits to that block since the last `SYNC_BARRIER`
- **Validation**: Integration test simulating a two-machine scenario with diverging edits

#### Task 3.2.3 — Fork resolution UI

- **Objective**: Surface forked blocks in the block editor with a "resolve conflict" affordance
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt` (or equivalent block rendering component)
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- **Implementation steps**:
  1. In `StelekitViewModel`, expose `forkedBlocks: StateFlow<List<ForkGroup>>` queried from `selectForkedBlocks`
  2. In the block renderer, when `block.forkGroupId != null`, render a conflict banner showing both versions side by side
  3. "Keep this version" action: delete the other fork, clear `fork_group_id` on the kept block, log `RESOLVE_FORK` op
  4. "Keep other version" action: same but reversed
- **Validation**: Manual test; screenshot test (Roborazzi) for the conflict banner

#### Task 3.2.4 — `BATCH_OPERATION` for find-replace

- **Objective**: Wrap graph-wide find-replace in a `BATCH_OPERATION` so the entire rename is one undo unit
- **Files**:
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
- **Implementation steps**:
  1. Wrap the block update loop in `BacklinkRenamer.renamePageReferences` with `writeActor.executeBatch(batchId) { ... }` (added in Task 2.2.2)
  2. Test that a full rename generates BATCH_START + N × UPDATE_BLOCK + BATCH_END in the op log
  3. Test that `UndoManager.undo()` inverts the entire batch in one gesture
- **Validation**: `BacklinkRenamerTest` extended to verify batch op-log entries

---

## Dependency Diagram

Tasks must be executed in this order. Independent tasks at the same level can run in parallel.

```
Phase 1
═══════
1.1.3 (metadata table)
    │
    ├──► 1.1.2 (UUID migration) ──── depends on 1.1.1 AND 1.1.3
    │
1.1.1 (drop content from seed)
    │
    ├──► 1.2.1 (content_hash population)
    │         │
    │         └──► 1.2.2 (DiffMerge)  ◄─── also needs 1.1.1 (stable UUIDs)
    │
    └──► 1.2.3 (conflict-marker detection) ── parallel with 1.2.1
    
1.2.4 (gitignore guard) ── fully independent, any time in Phase 1

Sequential gate: 1.1.1 + 1.1.2 must complete before Phase 1 ships (UUID
migration is destructive; shipping stable UUIDs without migrating existing
data would break all existing backlinks)

Phase 2
═══════
2.1.1 (schema: operations + logical_clock)
    │
    └──► 2.1.2 (OperationLogger)
              │
              └──► 2.1.3 (wire into DatabaseWriteActor)
                        │
                        ├──► 2.2.1 (UndoManager core)
                        │         │
                        │         └──► 2.2.3 (VM integration) ◄── parallel with 2.2.2
                        │
                        └──► 2.2.2 (BATCH_OPERATION wrapper) ── parallel with 2.2.1

Phase 3
═══════
3.1.1 (SidecarManager)
    │
    ├──► 3.1.2 (wire into GraphWriter) ── parallel with 3.1.3
    │
    └──► 3.1.3 (wire into GraphLoader)
              │
              └──► 3.2.1 (fork_group_id column)
                        │
                        └──► 3.2.2 (TreeReconciler)
                                  │
                                  ├──► 3.2.3 (fork resolution UI) ── parallel with 3.2.4
                                  │
                                  └──► 3.2.4 (BATCH find-replace) ◄── also needs 2.2.2
```

Cross-phase dependencies:
- Phase 3 (Task 3.2.4) depends on Phase 2 Task 2.2.2 (`BATCH_OPERATION` wrapper)
- Phase 3 (Task 3.2.2) depends on Phase 2 Task 2.1.3 (`OperationLogger` in actor)
- Phase 2 must ship before Phase 3 begins

---

## Known Issues

### Potential Bugs Identified During Planning

#### Concurrency Risk: UUID Migration and Active Watcher [SEVERITY: High]

**Description**: If the file watcher fires during the UUID migration transaction, `parseAndSavePage` may attempt to re-derive UUIDs using the new position-only scheme while the migration transaction is in progress, producing duplicate-key violations if new UUIDs collide with old ones mid-migration.

**Mitigation**:
- Stop the file watcher before running the migration (`GraphLoader.stopWatching()`)
- Run migration inside a single SQLite transaction with `EXCLUSIVE` lock
- Resume watcher only after migration commits successfully
- Add a migration-in-progress flag in `GraphManager` that causes `parseAndSavePage` to return early

**Files likely affected**: `GraphManager.kt`, `UuidMigration.kt`, `GraphLoader.kt`

**Prevention strategy**: Migration runs synchronously before `loadGraph` is called; watcher only starts after `loadGraph` completes.

---

#### Data Integrity: FK Cascade Ordering in UUID Migration [SEVERITY: High]

**Description**: The `blocks` table has self-referential FKs on `parent_uuid` and `left_uuid`. Updating UUIDs in arbitrary row order can violate the FK constraints mid-transaction if a parent UUID is updated before all its children reference the new parent UUID.

**Mitigation**:
- Run UUID updates in topological order: root blocks first (parent_uuid IS NULL), then depth-1, depth-2, etc.
- Or disable FK checks temporarily (`PRAGMA foreign_keys = OFF`) for the migration transaction, then re-enable and run a validation query before commit
- Validate with `SELECT COUNT(*) FROM blocks b WHERE b.parent_uuid IS NOT NULL AND NOT EXISTS (SELECT 1 FROM blocks p WHERE p.uuid = b.parent_uuid)` — must return 0 before commit

**Files likely affected**: `UuidMigration.kt`

**Prevention strategy**: Documented in ADR-006. Validated by post-migration query before commit.

---

#### Race Condition: Diff Merge and Concurrent Block Edits [SEVERITY: Medium]

**Description**: The diff merge in `DiffMerge.kt` loads existing block summaries, computes a diff, then applies writes. If `BlockStateManager` has a dirty (un-flushed) edit for a block on the same page while a watcher-triggered re-parse runs, the watcher's diff may overwrite the unsaved edit.

**Mitigation**:
- The existing B3 guard in `StelekitViewModel` suppresses external file changes when blocks are dirty
- Reinforce: `DiffMerge` should be a no-op if the page is currently open with dirty state (check `BlockStateManager.isDirty(pageUuid)` before applying updates)
- Log a warning when a re-parse is suppressed so it's visible in diagnostics

**Files likely affected**: `GraphLoader.kt`, `DiffMerge.kt`, `StelekitViewModel.kt`

**Prevention strategy**: The B3 guard is already in place. Reinforce at the `DiffMerge` call site.

---

#### Operation Log: Unbounded Growth [SEVERITY: Low (Phase 2), Medium (long-term)]

**Description**: The `operations` table has no compaction mechanism in Phase 2. For a user with years of notes and frequent edits, this table could grow to hundreds of MB, degrading query performance.

**Mitigation**:
- Phase 2 ships without compaction (acceptable for personal scale)
- Add a compaction job in a future iteration: delete ops older than 90 days (the undo depth bound from ADR-005) or exceeding 100 MB total payload
- Add a periodic `VACUUM` after compaction

**Files likely affected**: `OperationLogger.kt`, `GraphManager.kt`

**Prevention strategy**: `UndoManager` already enforces the 90-day / 100 MB soft bound when traversing the log. Hard-bound enforcement deferred.

---

#### Sidecar Desync: Sidecar Written After File [SEVERITY: Medium]

**Description**: `GraphWriter.savePageInternal` writes the `.md` file first, then the sidecar. If the process crashes between these two writes, the sidecar is stale or missing. On next import, block UUIDs may be regenerated from position seeds rather than the sidecar, breaking UUID continuity.

**Mitigation**:
- Treat missing sidecar as recoverable: fall back to position-derived UUIDs (same as pre-Phase 3 behavior)
- Write the sidecar to a temp file first, then atomically rename over the previous sidecar (where supported by `PlatformFileSystem`)
- Log a warning when import falls back to position-derived UUIDs due to missing sidecar

**Files likely affected**: `GraphWriter.kt`, `SidecarManager.kt`, `GraphLoader.kt`

**Prevention strategy**: Graceful fallback is the primary mitigation; atomic rename is a best-effort hardening.

---

#### Fork Detection: False Positive on First Sync [SEVERITY: Medium]

**Description**: On the very first git pull to a new machine, there is no op log. All blocks will appear to have diverging histories (local: no ops; remote: N edits in sidecar). This could produce false fork pairs for every block on every page.

**Mitigation**:
- The fork detection condition in `TreeReconciler` must require "local op log has at least one entry for this block since the last SYNC_BARRIER" before declaring a fork. No local op history = not a fork = treat as initial import.
- Insert a SYNC_BARRIER on first DB creation so the condition is well-defined

**Files likely affected**: `TreeReconciler.kt`, `GraphManager.kt`

**Prevention strategy**: First-run detection inserts SYNC_BARRIER before the first `loadGraph`. `TreeReconciler` checks op history existence before forking.

---

#### FTS5 Trigger Behavior During Block UUID Updates [SEVERITY: Low]

**Description**: The `blocks_au` (after-update) trigger maintains the FTS5 index. During the UUID migration, if block UUIDs are updated via a raw `UPDATE blocks SET uuid = ?` statement, the trigger fires once per row. With thousands of blocks, this generates thousands of FTS5 writes inside the migration transaction, significantly increasing migration time.

**Mitigation**:
- Disable FTS5 triggers during migration: `INSERT INTO blocks_fts(blocks_fts) VALUES('delete-all')` then rebuild after migration with `INSERT INTO blocks_fts(rowid, content) SELECT id, content FROM blocks`
- Alternatively, accept the performance hit for a one-time migration (personal-scale graph typically has <50,000 blocks)

**Files likely affected**: `UuidMigration.kt`

**Prevention strategy**: Measure migration time on a representative graph before deciding whether to optimize.

---

## File Index

New files created by this plan:

| File | Phase | Purpose |
|------|-------|---------|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UuidMigration.kt` | 1 | One-shot UUID migration |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/HashUtils.kt` | 1 | Content hash utility |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DiffMerge.kt` | 1 | Diff-based block merge |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/ConflictMarkerDetector.kt` | 1 | Git conflict-marker guard |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/OperationLogger.kt` | 2 | Op-log write service |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UndoManager.kt` | 2 | Undo/redo from op log |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/SidecarManager.kt` | 3 | NDJSON sidecar read/write |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/TreeReconciler.kt` | 3 | Post-git-pull merge |

Modified files:

| File | Phases | Changes |
|------|--------|---------|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` | 1, 3 | `generateUuid` seed change; diff-merge call site; conflict-marker check; sidecar integration |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` | 3 | Sidecar write after page save |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt` | 2 | `OperationLogger` integration; `BATCH_OPERATION` support |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` | 1, 2, 3 | Migration call; `OperationLogger` init; `SidecarManager` init |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt` | 3 | Wrap rename in `executeBatch` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` | 2, 3 | Undo/redo wiring; forked blocks state |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` | 3 | Add `forkGroupId` to `Block` |
| `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` | 1, 2, 3 | `metadata` table; `operations`; `logical_clock`; `fork_group_id`; new named queries |
