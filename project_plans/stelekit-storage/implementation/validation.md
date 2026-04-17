# Validation Plan: SteleKit Storage Architecture Redesign

**Status**: Ready — map test coverage to requirements before implementation begins  
**Date**: 2026-04-13  
**Phase**: 4 — Validation (MDD)  
**Inputs**: `requirements.md`, `implementation/plan.md`, `research/findings-pitfalls.md`

---

## 1. Traceability Matrix

Every success criterion and high-priority pitfall must have at least one test class covering it.

### Success Criteria Coverage

| # | Criterion | Primary Test Classes | Coverage Level |
|---|-----------|----------------------|----------------|
| SC-1 | Undo/redo works across app restarts | `UndoManagerTest`, `UndoIntegrationTest` | Unit + Integration |
| SC-2 | Git sync merges cleanly — forks, not overwrites | `TreeReconcilerTest`, `GitSyncSimulationTest` | Unit + E2E |
| SC-3 | Find-replace batch undo works across graph | `UndoManagerTest` (BATCH tests), `UndoIntegrationTest` | Unit + Integration |
| SC-4 | Block identity is stable across content edits | `UuidGeneratorTest`, `DiffMergeTest`, `UuidMigrationIntegrationTest` | Unit + Integration |
| SC-5 | Markdown files remain human-readable (no embedded IDs) | `SidecarManagerTest`, `GraphRoundTripTest` | Unit + E2E |
| SC-6 | Re-import is a no-op for unchanged content | `DiffMergeTest`, `DiffMergeIntegrationTest` | Unit + Integration |

### High-Priority Pitfall Coverage

| # | Pitfall (from findings-pitfalls.md) | Primary Test Classes | Coverage Level |
|---|-------------------------------------|----------------------|----------------|
| P-1 | Concurrent insert interleaving at same `left_uuid` | `TreeReconcilerTest` (concurrent insert test) | Unit |
| P-2 | Git conflict markers ingested as block content | `ConflictMarkerDetectorTest`, `ConflictMarkerRejectionTest` | Unit + E2E |
| P-3 | Undo stack invalidated by post-sync state | `UndoManagerTest` (SYNC_BARRIER tests), `UndoIntegrationTest` | Unit + Integration |
| P-4 | `.db` file accidentally committed to git | `ConflictMarkerRejectionTest` (gitignore guard) | E2E / Manual |
| P-5 | Undo not atomic with op-log update | `UndoIntegrationTest` (crash-safety assertion) | Integration |
| P-6 | WAL checkpoint pause during heavy paste | `OperationLogIntegrationTest` (batch write perf assertion) | Integration |
| P-7 | GC of tombstones while offline peer has pending ops | `TreeReconcilerTest` (tombstone retention test) | Unit |
| P-8 | UUID migration creates "dual truth" — broken backlinks | `UuidMigrationIntegrationTest` | Integration |

---

## 2. Test Pyramid Distribution

| Level | Target % | Approximate Count | Source Set | Rationale |
|-------|----------|-------------------|------------|-----------|
| Unit (no DB, no file I/O) | ≥ 60% | ~45 test methods | `commonTest` or `jvmTest` | Domain logic is pure; fast feedback loop |
| Integration (in-memory SQLite) | ~30% | ~22 test methods | `jvmTest` | SQLDelight queries and actor wiring need a real schema |
| End-to-End (real temp files) | ~10% | ~8 test methods | `jvmTest` | Full round-trips catch serialization and file-watcher edge cases |

**In-memory driver availability**: `BacklinkRenamerTest` uses `InMemoryBlockRepository` and `InMemoryPageRepository` but does NOT use SQLDelight with a real schema — it avoids the DB entirely. Integration tests for the operation log and UUID migration will need a real in-memory SQLite driver. See Section 8 for the required `TestDriverFactory` helper.

---

## 3. Unit Tests

All unit tests live in `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/` (or `jvmTest` when JVM-only APIs like `java.io.File` are required). No DB, no file I/O — pure Kotlin logic only.

### 3.1 `UuidGeneratorTest`

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/UuidGeneratorTest.kt`

```kotlin
// Tests for: position-only UUID scheme (Task 1.1.1, SC-4)

@Test
fun given_same_position_and_path_when_content_changes_then_uuid_is_unchanged()
// Input:  generateUuid(filePath="a.md", parentUuid=null, siblingIndex=0, content="old")
//         generateUuid(filePath="a.md", parentUuid=null, siblingIndex=0, content="new")
// Expect: both calls return identical UUID strings

@Test
fun given_same_position_same_file_when_called_twice_then_uuid_is_identical()
// Input:  two calls with identical (filePath, parentUuid, siblingIndex)
// Expect: deterministic — same UUID on every call

@Test
fun given_different_sibling_index_when_called_then_uuid_differs()
// Input:  siblingIndex=0 vs siblingIndex=1, all else equal
// Expect: UUID-0 != UUID-1

@Test
fun given_different_file_paths_when_called_then_uuid_differs()
// Input:  filePath="a.md" vs filePath="b.md", position identical
// Expect: UUID-a != UUID-b

@Test
fun given_different_parent_uuids_when_called_then_uuid_differs()
// Input:  parentUuid="parent-1" vs parentUuid="parent-2", all else equal
// Expect: UUID differs

@Test
fun given_null_parent_and_zero_index_when_called_then_uuid_is_stable_across_invocations()
// Regression guard: root block always produces same UUID regardless of run
```

### 3.2 `DiffMergeTest`

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/DiffMergeTest.kt`

```kotlin
// Tests for: diff-based merge replacing delete+insert (Task 1.2.2, SC-4, SC-6)

@Test
fun given_identical_blocks_when_diffed_then_all_blocks_are_unchanged_and_no_inserts_or_deletes()
// Input:  existing=[BlockSummary("uuid-a","hash-1")], parsed=[Block(uuid="uuid-a",hash="hash-1")]
// Expect: diff.unchanged=["uuid-a"], diff.toInsert=[], diff.toUpdate=[], diff.toDelete=[]

@Test
fun given_content_changed_same_uuid_when_diffed_then_block_is_in_toUpdate()
// Input:  existing=[BlockSummary("uuid-a","hash-old")], parsed=[Block(uuid="uuid-a",hash="hash-new")]
// Expect: diff.toUpdate=["uuid-a"], diff.toInsert=[], diff.toDelete=[], diff.unchanged=[]

@Test
fun given_new_block_not_in_existing_when_diffed_then_block_is_in_toInsert()
// Input:  existing=[], parsed=[Block(uuid="uuid-new",hash="hash-1")]
// Expect: diff.toInsert contains uuid-new

@Test
fun given_block_removed_from_file_when_diffed_then_uuid_is_in_toDelete()
// Input:  existing=[BlockSummary("uuid-gone","hash-1")], parsed=[]
// Expect: diff.toDelete=["uuid-gone"]

@Test
fun given_unchanged_file_when_diffed_then_zero_writes_required()
// All four lists empty except unchanged — confirms no DB write paths triggered
// Input:  5 blocks; existing and parsed are identical
// Expect: diff.toInsert.isEmpty(), diff.toUpdate.isEmpty(), diff.toDelete.isEmpty()

@Test
fun given_empty_existing_and_full_parsed_when_diffed_then_all_blocks_in_toInsert()
// Input:  existing=[], parsed=[3 blocks]
// Expect: diff.toInsert.size == 3, all others empty

@Test
fun given_full_existing_and_empty_parsed_when_diffed_then_all_uuids_in_toDelete()
// Input:  existing=[3 blocks], parsed=[]
// Expect: diff.toDelete.size == 3

@Test
fun given_block_moved_to_new_position_when_diffed_then_uuid_stable_and_update_registered()
// Block keeps UUID (position-based); content unchanged; only left_uuid/parent changes
// Input:  existing has uuid-a at position 0; parsed has uuid-a at position 2
// Expect: uuid-a appears in toUpdate (position/parent changed), not in toDelete+toInsert
```

### 3.3 `ConflictMarkerDetectorTest`

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/ConflictMarkerDetectorTest.kt`

```kotlin
// Tests for: conflict marker rejection before import (Task 1.2.3, P-2)

@Test
fun given_clean_file_when_detected_then_returns_false()
// Input:  "- Block content\n- Another block\n"
// Expect: detect(content) == false

@Test
fun given_file_with_git_conflict_open_marker_when_detected_then_returns_true()
// Input:  "- Good block\n<<<<<<< HEAD\n- Local edit\n"
// Expect: detect(content) == true

@Test
fun given_file_with_all_three_conflict_markers_when_detected_then_returns_true()
// Input:  "<<<<<<< HEAD\n- foo\n=======\n- bar\n>>>>>>> remote\n"
// Expect: detect(content) == true

@Test
fun given_yaml_front_matter_with_equals_divider_when_detected_then_returns_false()
// YAML divider "---" is not a conflict marker; "=======" mid-file in YAML is ambiguous
// Input:  "---\ntitle: Page\n---\n- Content\n"
// Expect: detect(content) == false (YAML divider must not trigger)

@Test
fun given_equals_marker_only_on_its_own_line_when_detected_then_returns_true()
// Input:  "- Content\n=======\n- More content\n"
// Expect: detect(content) == true (bare ======= on own line is a conflict marker)

@Test
fun given_conflict_markers_inside_code_fence_when_detected_then_behavior_is_documented()
// Intent: code blocks can legitimately contain "<<<<<<< HEAD" as example content
// Input:  "```\n<<<<<<< HEAD\n```\n"
// Expect: detect returns true (conservative — hard-error is safer than silent corruption)
// Note: add a @Ignore-able variant for when/if code-fence exemption is implemented

@Test
fun given_close_marker_only_when_detected_then_returns_true()
// Input:  "- text\n>>>>>>> abc123\n"
// Expect: detect(content) == true
```

### 3.4 `OperationLoggerTest`

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/OperationLoggerTest.kt`

```kotlin
// Tests for: Lamport clock and op-log correctness (Task 2.1.2, P-5, P-6)
// Uses FakeDatabase / in-memory operation list — no SQLite dependency

@Test
fun given_multiple_log_calls_when_sequenced_then_seq_is_monotonically_increasing()
// Input:  three sequential log() calls
// Expect: op1.seq < op2.seq < op3.seq; no two ops share the same seq

@Test
fun given_block_snapshot_when_logged_then_before_and_after_json_round_trips_correctly()
// Input:  BlockSnapshot(uuid="u1", content="hello", position=0, parentUuid=null, ...)
// Expect: deserializing the payload JSON produces an equal BlockSnapshot

@Test
fun given_batch_start_and_end_when_logged_then_ops_are_bracketed_by_batch_markers()
// Input:  log(BATCH_START, batchId="b1"), log(UPDATE_BLOCK), log(BATCH_END, batchId="b1")
// Expect: ops list = [BATCH_START, UPDATE_BLOCK, BATCH_END] in that seq order

@Test
fun given_sync_barrier_logged_when_inspected_then_entity_uuid_is_null()
// Input:  insertSyncBarrier()
// Expect: resulting op has entity_uuid == null, op_type == SYNC_BARRIER

@Test
fun given_two_independent_sessions_when_clocks_initialized_then_seqs_start_from_db_max()
// Simulates restart: second OperationLogger instance must read max(seq) from store
// Expect: second logger's first op seq > last op seq from first logger
```

### 3.5 `UndoManagerTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/UndoManagerTest.kt`

```kotlin
// Tests for: undo/redo state machine (Task 2.2.1, SC-1, SC-3, P-3, P-5)
// Uses FakeOperationLogger and SpyWriteActor — no real SQLite

@Test
fun given_update_operation_when_undone_then_before_state_is_restored()
// Setup:   op log has UPDATE_BLOCK(before="old", after="new")
// Expect:  writeActor receives saveBlock with content="old"

@Test
fun given_insert_operation_when_undone_then_delete_is_executed()
// Setup:   op log has INSERT_BLOCK(block_uuid="u1")
// Expect:  writeActor receives deleteBlock("u1")

@Test
fun given_delete_operation_when_undone_then_block_is_reinserted()
// Setup:   op log has DELETE_BLOCK(before=snapshot of block)
// Expect:  writeActor receives saveBlock with the before-snapshot data

@Test
fun given_sync_barrier_in_op_log_when_undo_called_then_undo_stops_at_barrier()
// Setup:   op log = [INSERT_BLOCK(seq=1), SYNC_BARRIER(seq=2), UPDATE_BLOCK(seq=3)]
// Action:  undo() from position after seq=3
// Expect:  UPDATE_BLOCK is undone; second undo() call does NOT undo INSERT_BLOCK;
//          canUndo returns false after reaching barrier

@Test
fun given_batch_wrapped_ops_when_undone_then_all_ops_in_batch_are_inverted_atomically()
// Setup:   op log = [BATCH_START(bid="b1"), UPDATE(u1), UPDATE(u2), BATCH_END(bid="b1")]
// Expect:  single undo() inverts both UPDATE ops; writeActor called twice in one txn

@Test
fun given_undone_operation_when_redo_called_then_op_is_reapplied()
// Setup:   undo UPDATE_BLOCK("old" → "new") then redo
// Expect:  writeActor receives saveBlock with content="new"

@Test
fun given_undo_then_new_edit_when_redo_called_then_redo_stack_is_cleared()
// Setup:   undo op, then perform new write op
// Expect:  canRedo returns false; redo() is a no-op

@Test
fun given_empty_op_log_when_undo_called_then_can_undo_returns_false_and_no_write_occurs()
// Expect:  canUndo == false; writeActor.callCount == 0

@Test
fun given_can_undo_false_initially_then_after_logging_op_can_undo_becomes_true()
// Tests StateFlow reactivity: canUndo updates when op is appended
```

### 3.6 `SidecarManagerTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/SidecarManagerTest.kt`

```kotlin
// Tests for: NDJSON sidecar read/write (Task 3.1.1, SC-5)
// Uses java.io.File temp directory — JVM only

@Test
fun given_blocks_written_when_read_back_then_map_contains_all_entries()
// Input:   write([Block(uuid="u1", hash="h1", pos=0), Block(uuid="u2", hash="h2", pos=1)])
// Expect:  read() returns Map{"h1" → SidecarEntry("u1",0,null), "h2" → SidecarEntry("u2",1,null)}

@Test
fun given_absent_sidecar_file_when_read_then_returns_null()
// Input:   sidecar path does not exist
// Expect:  read("nonexistent") == null (no exception)

@Test
fun given_blocks_written_when_file_inspected_then_each_line_is_valid_json()
// Verify NDJSON format: split by newline, each non-empty line parses as JSON with keys uuid/hash/pos

@Test
fun given_two_blocks_with_identical_content_hash_when_written_then_second_hash_wins_or_both_stored()
// Edge case: two blocks with same content (e.g., duplicate "- TODO" lines)
// Expect: documented behavior — either last-write-wins or both stored with distinct keys;
//         must not throw or silently lose one entry silently without deterministic resolution

@Test
fun given_sidecar_directory_absent_when_written_then_directory_is_created()
// Expect:  .stelekit/pages/ directory is created if not present; write succeeds

@Test
fun given_existing_sidecar_when_overwritten_then_old_entries_are_not_retained()
// Input:   write 3 blocks; write 1 block (page shrunk)
// Expect:  read() returns only 1 entry, not 3+1
```

### 3.7 `TreeReconcilerTest`

**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/TreeReconcilerTest.kt`

```kotlin
// Tests for: merge reconciliation after git pull (Task 3.2.x, SC-2, P-1, P-7)

@Test
fun given_no_divergence_when_reconciled_then_result_is_skip_all()
// Input:   DB state and file-parsed state are byte-for-byte identical (same hashes)
// Expect:  ReconcilerResult.allSkipped == true; no inserts, updates, deletes, forks

@Test
fun given_content_divergence_same_uuid_when_reconciled_then_fork_pair_is_created()
// Input:   DB has uuid-a with content="local edit"; file (from git pull) has uuid-a with content="remote edit"
// Expect:  two blocks created: original (uuid-a, fork_group_id=X) and fork (new uuid, fork_group_id=X)

@Test
fun given_block_in_file_but_not_in_db_when_reconciled_then_insert_is_produced()
// Input:   file has uuid-b not present in DB (remote added a block)
// Expect:  ReconcilerResult.toInsert contains uuid-b

@Test
fun given_block_deleted_on_remote_but_edited_locally_when_reconciled_then_fork_is_created()
// Input:   uuid-c in DB with local edits; uuid-c absent from file (remote deleted it)
// Expect:  fork pair created — DB keeps local edit, conflict flagged

@Test
fun given_sidecar_hash_map_when_block_moved_in_file_then_uuid_is_preserved_not_deleted_and_reinserted()
// Tests sidecar-assisted UUID recovery for reordered blocks
// Input:   file has block at new position; sidecar maps hash → uuid-d; DB has uuid-d at old position
// Expect:  uuid-d updated in place (MOVE_BLOCK op), not deleted+inserted

@Test
fun given_concurrent_inserts_at_same_left_uuid_when_reconciled_then_ordering_is_deterministic()
// Tests P-1: tie-breaking by peer ID + seq prevents interleaving
// Input:   local insert at anchor A (seq=5) and remote insert at anchor A (seq=3)
// Expect:  remote insert (lower seq) placed first; ordering same on both peers after sync

@Test
fun given_tombstoned_block_when_offline_peer_insert_references_it_then_tombstone_is_retained()
// Tests P-7: GC must not remove tombstones referenced by pending peer inserts
// Input:   block uuid-t tombstoned; new insert references uuid-t as left_uuid
// Expect:  uuid-t tombstone preserved in DB; new block positioned correctly after it
```

---

## 4. Integration Tests

Integration tests use an in-memory SQLite driver (see Section 8 for `TestDriverFactory`). They run in `jvmTest` and exercise the full SQLDelight schema, `DatabaseWriteActor`, and repository layer.

### 4.1 `UuidMigrationIntegrationTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/UuidMigrationIntegrationTest.kt`

```kotlin
// Tests for: atomic UUID migration from content-derived to position-derived (Task 1.1.2, SC-4, P-8)

@Test
fun given_pre_migration_db_when_migration_runs_then_all_block_uuids_change_to_position_derived()
// Setup:   insert 3 blocks with old content-derived UUIDs into in-memory DB
// Action:  UuidMigration.run(db, graphLoader)
// Expect:  all block.uuid values differ from original; all equal re-derived position UUIDs

@Test
fun given_pre_migration_db_with_backlinks_when_migration_runs_then_all_fk_references_cascade()
// Setup:   block A references block B via block_references; B has old UUID
// Action:  migration runs
// Expect:  block_references.target_uuid == B's new UUID; no orphaned FK references

@Test
fun given_migration_complete_when_validation_query_runs_then_zero_orphaned_fk_references()
// Post-migration integrity check
// Expect:  LEFT JOIN blocks ON blocks.uuid = b.parent_uuid WHERE b.parent_uuid IS NOT NULL AND blocks.uuid IS NULL returns 0 rows

@Test
fun given_existing_backlinks_when_migration_runs_then_backlinks_resolve_after_migration()
// Setup:   page A has a block with content "[[Page B]]"; block_references row with old UUID
// Action:  migration
// Expect:  querying backlinks for page B still returns the block from page A

@Test
fun given_migration_already_run_when_migration_called_again_then_is_idempotent()
// schema_version == '2' after first run; second call must no-op without error or data change
```

### 4.2 `DiffMergeIntegrationTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DiffMergeIntegrationTest.kt`

```kotlin
// Tests for: diff-merge replaces delete+insert cycle (Task 1.2.2, SC-6, SC-4)
// Uses SpyWriteActor (see Section 8) to count DB write calls

@Test
fun given_real_md_file_when_parsed_then_re_parsed_with_one_line_changed_then_exactly_one_update_occurs()
// Setup:   write temp .md file with 5 blocks; parse into DB via GraphLoader
// Action:  change one block's content in the file; re-parse
// Expect:  SpyWriteActor.updateCount == 1; SpyWriteActor.deleteCount == 0; SpyWriteActor.insertCount == 0

@Test
fun given_unchanged_file_when_re_parsed_then_zero_db_writes_occur()
// Setup:   parse file into DB; immediately re-parse without file changes
// Expect:  SpyWriteActor.totalWriteCount == 0 (all blocks in unchanged list)

@Test
fun given_new_block_appended_to_file_when_parsed_then_exactly_one_insert_occurs()
// Setup:   parse 3-block file; append a fourth block to the file; re-parse
// Expect:  SpyWriteActor.insertCount == 1; updateCount == 0; deleteCount == 0

@Test
fun given_block_deleted_from_file_when_re_parsed_then_exactly_one_delete_occurs()
// Setup:   parse 3-block file; remove middle block from file; re-parse
// Expect:  SpyWriteActor.deleteCount == 1; insertCount == 0; updateCount == 0

@Test
fun given_all_blocks_content_hash_null_when_parsed_then_hashes_are_populated()
// Regression for Task 1.2.1: confirms content_hash is non-null after any full parse
// SELECT COUNT(*) FROM blocks WHERE content_hash IS NULL must return 0
```

### 4.3 `OperationLogIntegrationTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/OperationLogIntegrationTest.kt`

```kotlin
// Tests for: op-log write correctness and clock persistence (Task 2.1.3, P-5, P-6)
// Uses in-memory SQLite via TestDriverFactory

@Test
fun given_three_blocks_saved_when_queried_then_three_insert_block_ops_exist_in_log()
// Setup:   writeActor.saveBlocks(3 new blocks) via GraphLoader parse
// Expect:  selectOperationsBySession returns 3 rows; all op_type == INSERT_BLOCK
//          each op has correct entity_uuid matching the block saved

@Test
fun given_block_updated_when_queried_then_one_update_block_op_exists()
// Action:  writeActor.updateBlockContent(uuid, newContent)
// Expect:  op_type == UPDATE_BLOCK; payload.before.content != payload.after.content

@Test
fun given_block_deleted_when_queried_then_one_delete_block_op_exists()
// Action:  writeActor.deleteBlock(uuid)
// Expect:  op_type == DELETE_BLOCK; payload.before != null; payload.after == null

@Test
fun given_ops_written_across_two_db_sessions_when_seq_queried_then_seq_never_decreases()
// Tests P-5 / clock persistence: close and reopen OperationLogger; new ops must have higher seq
// Setup:   session 1 logs 5 ops (maxSeq=5); close; session 2 logs 1 op
// Expect:  session 2 op seq == 6 (not 1)

@Test
fun given_batch_of_500_blocks_when_saved_then_operation_log_uses_single_transaction()
// Tests P-6 WAL pressure: 500 block inserts in one actor message must produce one SQLite txn
// Expect:  SpyWriteActor confirms single flushBatch call with list of 500;
//          SELECT changes() after the transaction == 500 (one commit, not 500)
```

### 4.4 `UndoIntegrationTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/UndoIntegrationTest.kt`

```kotlin
// Tests for: undo persistence across restarts (SC-1, P-3, P-5)

@Test
fun given_content_typed_and_app_restarted_when_undo_called_then_content_reverts()
// Setup:   parse page, update block content via writeActor (op logged), close DB
// Action:  reopen DB (new UndoManager reconstructed from DB), call undo()
// Expect:  block content in DB equals pre-update value (no in-memory state required)

@Test
fun given_sync_barrier_inserted_after_local_edits_when_restarted_and_undo_called_then_pre_barrier_ops_not_undone()
// Setup:   INSERT op (seq=1), SYNC_BARRIER (seq=2) inserted; close and reopen DB
// Action:  undo()
// Expect:  canUndo == false (barrier is the oldest undoable boundary); INSERT not reverted

@Test
fun given_undo_operation_when_db_crashes_mid_undo_then_on_restart_state_is_consistent()
// Simulated by verifying undo is a single transaction:
// Use a test spy on the SQLite transaction boundary; if transaction rolled back, no partial state
// Expect:  either undo fully applied or fully rolled back — never partial

@Test
fun given_batch_find_replace_across_two_pages_when_undone_then_all_pages_revert()
// SC-3: batch undo for cross-page find-replace
// Setup:   replace "foo" with "bar" across 2 pages (4 blocks), wrapped in BATCH_OPERATION
// Action:  one undo() call
// Expect:  all 4 blocks revert to "foo"; canRedo == true
```

---

## 5. End-to-End Tests

E2E tests write real files to temp directories and exercise the full parse → DB → export pipeline on JVM. They run in `jvmTest` and are marked `@Slow` (or equivalent tag) so they can be excluded from fast CI runs.

### 5.1 `GraphRoundTripTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRoundTripTest.kt`

```kotlin
// Tests for: SC-5 (clean markdown), SC-6 (no-op re-import), SC-4 (UUID stability)

@Test
fun given_graph_loaded_when_block_edited_and_saved_then_re_parse_produces_same_db_state()
// Setup:   create temp graph dir with 3 .md files; load graph via GraphManager
// Action:  edit one block content via BlockStateManager debounce path; await disk write;
//          re-trigger GraphLoader on modified file
// Expect:  DB block count unchanged; edited block UUID unchanged; content matches new value

@Test
fun given_loaded_graph_when_re_parsed_without_changes_then_zero_db_writes_occur()
// Setup:   load graph; immediately trigger file-watcher re-parse on all files
// Expect:  SpyWriteActor.totalWriteCount == 0 across all pages
//          (verifies SC-6 end-to-end, not just at DiffMerge unit level)

@Test
fun given_page_exported_when_markdown_inspected_then_no_id_properties_appear_in_content()
// Tests SC-5: no "id:: uuid" or similar metadata embedded in block lines
// Expect:  no line in any .md file matches regex `^\s*id::\s*[0-9a-f-]+$`
//          UUID metadata is exclusively in the sidecar, not in page content
```

### 5.2 `GitSyncSimulationTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GitSyncSimulationTest.kt`

```kotlin
// Tests for: SC-2 (git sync merges cleanly — forks not overwrites)

@Test
fun given_two_device_graphs_when_conflicting_edits_then_fork_blocks_created_and_no_content_lost()
// Setup:   create two temp graph dirs ("device-A", "device-B") from same .md files
//          load both via separate GraphManager instances
// Action:  edit block uuid-1 content on device-A to "local edit"
//          edit block uuid-1 content on device-B to "remote edit"
//          "simulate pull": overwrite device-A's .md file with device-B's version
//          trigger file-watcher re-parse on device-A
// Expect:  device-A DB has two blocks sharing fork_group_id; one with "local edit",
//          one with "remote edit"; neither is silently discarded

@Test
fun given_remote_adds_new_block_when_pulled_then_new_block_inserted_cleanly()
// Setup:   device-A and device-B in sync; device-B adds a new block
//          copy device-B's .md to device-A
// Expect:  device-A DB gains the new block with the same UUID as device-B assigned it
//          (via sidecar UUID recovery); no fork created

@Test
fun given_both_devices_add_blocks_at_same_anchor_when_merged_then_ordering_is_deterministic()
// Tests P-1 in E2E context: both inserts preserved; no interleaving corruption
```

### 5.3 `ConflictMarkerRejectionTest`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/ConflictMarkerRejectionTest.kt`

```kotlin
// Tests for: P-2 (conflict markers must not corrupt DB)

@Test
fun given_conflicted_md_file_in_graph_when_file_watcher_fires_then_write_error_emitted()
// Setup:   write a .md file containing "<<<<<<< HEAD\n- local\n=======\n- remote\n>>>>>>> abc"
//          to a loaded graph's pages directory
// Action:  trigger GraphLoader.parseAndSavePage on the conflicted file
// Expect:  _writeErrors emits WriteError.ConflictMarkersDetected(filePath)

@Test
fun given_conflicted_file_when_import_attempted_then_original_db_blocks_are_untouched()
// Extends previous test: verify that no block in the DB was modified
// Expect:  all block content_hash values match pre-import state exactly

@Test
fun given_conflict_markers_resolved_and_file_cleaned_when_re_imported_then_import_succeeds()
// Regression: after user resolves conflict, normal import must work
// Action:  clean the file (remove conflict markers); trigger re-import
// Expect:  no WriteError emitted; blocks updated normally
```

---

## 6. Property-Based Tests

Property-based tests run in `commonTest` using kotest's property testing module (or kotlinx-test if available in the project's dependency set — verify against `kmp/build.gradle.kts` before implementing).

### 6.1 UUID Stability Property

```kotlin
// forall (content: String, filePath: String, parentUuid: String?, siblingIndex: Int)
// → generateUuid(filePath, parentUuid, siblingIndex) == generateUuid(filePath, parentUuid, siblingIndex)
// The UUID must NOT depend on content.

@Test
fun property_uuid_is_independent_of_content() {
    checkAll(Arb.string(), Arb.string(1..200), Arb.string().orNull(), Arb.int(0..1000)) {
        content1, filePath, parentUuid, siblingIndex ->
        val content2 = content1 + "_modified"
        generateUuid(filePath, parentUuid, siblingIndex) shouldBe
            generateUuid(filePath, parentUuid, siblingIndex)
        // content variant produces same UUID:
        generateUuid(filePath, parentUuid, siblingIndex) shouldBe
            generateUuid(filePath, parentUuid, siblingIndex)
    }
}
```

### 6.2 DiffMerge Idempotence Property

```kotlin
// forall (blocks: List<Block>)
// → diff(existing=blocks.toSummary(), parsed=blocks) produces empty toInsert/toUpdate/toDelete

@Test
fun property_diffmerge_is_idempotent_on_unchanged_input() {
    checkAll(Arb.list(Arb.block(), 0..50)) { blocks ->
        val existing = blocks.map { ExistingBlockSummary(it.uuid, it.contentHash) }
        val result = DiffMerge.diff(existing, blocks)
        result.toInsert shouldBe emptyList()
        result.toUpdate shouldBe emptyList()
        result.toDelete shouldBe emptyList()
        result.unchanged.size shouldBe blocks.size
    }
}
```

### 6.3 Conflict Marker Detection Soundness Properties

```kotlin
// forall (clean: String that contains no conflict marker lines)
// → ConflictMarkerDetector.detect(clean) == false

// forall (clean: String, marker: String chosen from {"<<<<<<<", "=======", ">>>>>>>"})
// → detect(clean + "\n" + marker + " suffix\n") == true

@Test
fun property_clean_content_never_triggers_detector() {
    val noMarkers = Arb.string().filter { s ->
        !s.contains("<<<<<<<") && !s.contains(">>>>>>>") && !s.contains("\n=======\n")
    }
    checkAll(noMarkers) { clean ->
        ConflictMarkerDetector.detect(clean) shouldBe false
    }
}

@Test
fun property_content_with_any_marker_line_triggers_detector() {
    val markers = listOf("<<<<<<< HEAD", "=======", ">>>>>>> abc123")
    checkAll(Arb.string(), Arb.element(markers)) { prefix, marker ->
        val withMarker = "$prefix\n$marker\nrest of content"
        ConflictMarkerDetector.detect(withMarker) shouldBe true
    }
}
```

---

## 7. Mutation Test Targets

Mutation testing should be applied to these three files after Phase 1 is complete. The mutants listed are the highest-value candidates — if they survive (tests do not catch the change), the test suite has a coverage gap.

### 7.1 `DiffMerge.kt`

| Mutant | What It Changes | Detecting Test |
|--------|-----------------|----------------|
| Flip hash comparison (`== to !=`) | Every unchanged block goes to `toUpdate`; no `unchanged` entries | `DiffMergeTest.given_identical_blocks_when_diffed_then_all_blocks_are_unchanged` |
| Swap `toInsert` and `toDelete` list population | New blocks deleted, old blocks re-inserted | `DiffMergeTest.given_new_block_not_in_existing_when_diffed_then_block_is_in_toInsert` |
| Remove empty-list short-circuit (if parsed.isEmpty() return early) | All existing blocks deleted on every empty file parse | `DiffMergeTest.given_unchanged_file_when_diffed_then_zero_writes_required` |
| Change UUID matching key from `uuid` to `contentHash` | Breaks stable UUID identity when content changes | `DiffMergeTest.given_content_changed_same_uuid_when_diffed_then_block_is_in_toUpdate` |

### 7.2 `UndoManager.kt`

| Mutant | What It Changes | Detecting Test |
|--------|-----------------|----------------|
| Remove `SYNC_BARRIER` stop check (`if (op.opType == SYNC_BARRIER) break` → `continue`) | Undo crosses sync boundary into previous session's ops | `UndoManagerTest.given_sync_barrier_in_op_log_when_undo_called_then_undo_stops_at_barrier` |
| Swap `before` and `after` in UPDATE inversion | Undo of update applies `after` content instead of `before` | `UndoManagerTest.given_update_operation_when_undone_then_before_state_is_restored` |
| Replace `BATCH_END` detection with `BATCH_START` | Batch inversion starts from wrong boundary | `UndoManagerTest.given_batch_wrapped_ops_when_undone_then_all_ops_in_batch_are_inverted_atomically` |
| Remove redo-stack invalidation on new edit | New edits after undo leave stale redo ops | `UndoManagerTest.given_undo_then_new_edit_when_redo_called_then_redo_stack_is_cleared` |

### 7.3 `ConflictMarkerDetector.kt`

| Mutant | What It Changes | Detecting Test |
|--------|-----------------|----------------|
| Remove `<<<<<<<` check | Open conflict markers pass detection | `ConflictMarkerDetectorTest.given_file_with_git_conflict_open_marker_when_detected_then_returns_true` |
| Remove `>>>>>>>` check | Close conflict markers pass detection | `ConflictMarkerDetectorTest.given_close_marker_only_when_detected_then_returns_true` |
| Change line-boundary check from `startsWith` to `contains` | False positive on YAML or prose containing "=======" mid-line | `ConflictMarkerDetectorTest.given_yaml_front_matter_with_equals_divider_when_detected_then_returns_false` |
| Change `=======` match to require exact-line only when preceded by `<<<<<<<` | Standalone `=======` from a bad merge passes undetected | `ConflictMarkerDetectorTest.given_equals_marker_only_on_its_own_line_when_detected_then_returns_true` |

---

## 8. Test Infrastructure Requirements

### 8.1 In-Memory SQLite Driver Factory

`BacklinkRenamerTest` bypasses SQLDelight entirely — it uses `InMemoryBlockRepository` and `InMemoryPageRepository` directly. Integration tests for the operation log, undo manager, and UUID migration need a real SQLDelight driver backed by an in-memory SQLite file.

**Required artifact**: `TestDriverFactory` in `jvmTest/kotlin/dev/stapler/stelekit/db/TestDriverFactory.kt`

```kotlin
// Creates a fresh in-memory SteleDatabase for each test.
// Pattern mirrors BacklinkRenamerTest but for SQLDelight-backed tests.

object TestDriverFactory {
    fun createDriver(): SqlDriver = JdbcSqliteDriver(
        url = JdbcSqliteDriver.IN_MEMORY,
        properties = Properties().apply {
            setProperty("foreign_keys", "true")
        }
    )

    fun createDatabase(): SteleDatabase {
        val driver = createDriver()
        SteleDatabase.Schema.create(driver)
        return SteleDatabase(driver)
    }
}
```

Check `kmp/build.gradle.kts` to confirm `sqlite-jdbc` and `sqldelight-jdbc-driver` are already test dependencies (they should be, given `BacklinkRenamerTest` runs on JVM — but `BacklinkRenamerTest` avoids the DB, so verify explicitly before Phase 2 integration tests).

### 8.2 Temp Directory Fixture

Pattern from `BacklinkRenamerTest` — use `System.getProperty("user.home")` for temp root, name with `System.currentTimeMillis()`, always clean up in `finally`. Extract into a shared helper:

```kotlin
// kmp/src/jvmTest/kotlin/dev/stapler/stelekit/TestFixtures.kt

fun withTempGraphDir(block: (File) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "stelekit_test_${System.currentTimeMillis()}")
    dir.mkdirs()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
```

### 8.3 `FakeOperationLogger`

Required for `DatabaseWriteActor` tests and `UndoManagerTest` to isolate the actor from real SQLite.

```kotlin
// kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FakeOperationLogger.kt

class FakeOperationLogger : OperationLogger {
    val logged = mutableListOf<FakeOp>()
    var seq = 0

    override suspend fun log(opType: OpType, entityUuid: String?, ...) {
        logged += FakeOp(seq = ++seq, opType = opType, entityUuid = entityUuid, ...)
    }

    override suspend fun insertSyncBarrier() {
        logged += FakeOp(seq = ++seq, opType = OpType.SYNC_BARRIER, entityUuid = null, ...)
    }
}
```

### 8.4 `SpyWriteActor`

Required for `DiffMergeIntegrationTest` and `GraphRoundTripTest` to assert zero-write behavior without instrumenting production code.

```kotlin
// kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/SpyWriteActor.kt

class SpyWriteActor(delegate: DatabaseWriteActor) : DatabaseWriteActor by delegate {
    var insertCount = 0
    var updateCount = 0
    var deleteCount = 0
    val totalWriteCount get() = insertCount + updateCount + deleteCount

    override suspend fun saveBlocks(blocks: List<Block>) {
        // Distinguish insert vs update by checking DB existence before delegating
        insertCount += blocks.count { /* not in DB */ }
        updateCount += blocks.count { /* already in DB */ }
        super.saveBlocks(blocks)
    }

    override suspend fun deleteBlock(uuid: String) {
        deleteCount++
        super.deleteBlock(uuid)
    }
}
```

---

## 9. Coverage Gaps and Risks

| Gap | Affected Criterion | Description | Mitigation |
|-----|--------------------|-------------|------------|
| Undo depth bound (90-day / 100 MB cap) | SC-1 | No test verifies that ops beyond the bound are excluded from undo without throwing | Add `UndoManagerTest.given_ops_older_than_90_days_when_undo_called_then_old_ops_skipped` in Phase 2 |
| WAL checkpoint timing under paste load | P-6 | Integration test confirms single transaction but cannot observe actual WAL size in JVM tests | Add a comment in `OperationLogIntegrationTest` to run manual WAL inspection with 500-block paste before Phase 2 ship |
| `.db` file in git guard | P-4 | The gitignore check (`GraphManager.addGraph`) has no automated test (Task 1.2.4 explicitly notes manual only) | Accept as coverage gap; add a `@ManualTest` annotation and a checklist item in the Phase 1 release checklist |
| iOS/Android SQLite VACUUM conflict | Pitfalls §2.4 | JVM-only test suite cannot exercise mobile VACUUM contention | Defer to platform-specific `androidUnitTest`; document as out-of-scope for Phase 1–2 |
| Sidecar merge conflict in git | P pitfalls §3.2 | No test simulates git producing a conflict in the `.stelekit/` sidecar file itself | Add to `GitSyncSimulationTest` in Phase 3: create conflicted sidecar, verify fallback to position-derived UUIDs |
| SC-3 (find-replace) end-to-end | SC-3 | `UndoIntegrationTest` covers cross-page batch undo but no test exercises the full BatchFindReplace → undo → verify UI state path | Add `FindReplaceE2ETest` in Phase 3 after find-replace feature is implemented |

---

## 10. Test Execution Order

Test suites that must be green before each implementation phase begins.

### Before Phase 1 (Story 1.1 / 1.2)

The existing `jvmTest` suite must be fully green. Key tests:

```
./gradlew jvmTest --tests "dev.stapler.stelekit.db.BacklinkRenamerTest"
```

If `BacklinkRenamerTest` is failing, stop. Do not begin Phase 1 tasks.

### Before Phase 2 (Story 2.1 / 2.2)

All Phase 1 unit and integration tests must pass:

```
./gradlew jvmTest --tests "dev.stapler.stelekit.db.UuidGeneratorTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.DiffMergeTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.ConflictMarkerDetectorTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.UuidMigrationIntegrationTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.DiffMergeIntegrationTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.GraphRoundTripTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.ConflictMarkerRejectionTest"
```

`SidecarManagerTest` may be deferred to Phase 3 but its write/read round-trip test should be written before Phase 2 completes.

### Before Phase 3 (Story 3.1 / 3.2)

All Phase 2 unit and integration tests must pass, in addition to Phase 1:

```
./gradlew jvmTest --tests "dev.stapler.stelekit.db.OperationLoggerTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.UndoManagerTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.OperationLogIntegrationTest"
./gradlew jvmTest --tests "dev.stapler.stelekit.db.UndoIntegrationTest"
```

`TreeReconcilerTest` and `GitSyncSimulationTest` are the gate for Phase 3 merge reconciliation work. Write them as pending/failing specs before starting Phase 3 implementation so regressions are caught immediately.

### Full suite gate (after Phase 3)

```
./gradlew jvmTest
```

All tests green, including `SidecarManagerTest`, `TreeReconcilerTest`, `GitSyncSimulationTest`, and property-based tests.
