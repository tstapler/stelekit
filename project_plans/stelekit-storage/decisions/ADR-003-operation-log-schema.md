# ADR-003: Operation Log Schema

**Status**: Proposed
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

## Context

SteleKit needs a persistent operation log to support:
1. **Undo across restarts** — the user's undo history must survive app close/open
2. **Sync barrier semantics** — a marker that separates "locally authored" operations from "remotely merged" operations, preventing undo from crossing a git sync boundary
3. **Find-replace atomicity** — a batch rename across N pages should appear as one undoable unit
4. **Fork detection** — determining whether the same block UUID has diverging op histories on two machines (Phase 3)

Choices to make:
- **Normalized vs. JSON payload**: normalize before/after state into separate columns, or store as a JSON blob
- **Lamport clock vs. wall-clock seq**: use a logical (Lamport) sequence counter or a wall-clock timestamp
- **Separate table vs. `version` column on `blocks`**: append-only log table vs. incrementing version on the entity
- **Compaction strategy**: when and how to prune old operations

## Decision

Use an **append-only `operations` table** with:
- `op_id TEXT PRIMARY KEY` — UUID v7 (time-ordered, globally unique, sortable)
- `session_id TEXT NOT NULL` — per-device session identifier (from `metadata` table)
- `seq INTEGER NOT NULL` — Lamport clock value (monotonically increasing per session)
- `op_type TEXT NOT NULL` — one of: `INSERT_BLOCK`, `UPDATE_BLOCK`, `DELETE_BLOCK`, `MOVE_BLOCK`, `BATCH_START`, `BATCH_END`, `SYNC_BARRIER`
- `entity_uuid TEXT` — the block UUID affected (NULL for `BATCH_*` and `SYNC_BARRIER`)
- `page_uuid TEXT` — the page UUID (NULL for `BATCH_*` and `SYNC_BARRIER`)
- `payload TEXT NOT NULL` — JSON blob: `{"before": <BlockSnapshot|null>, "after": <BlockSnapshot|null>}`
- `created_at INTEGER NOT NULL` — Unix milliseconds

A companion **`logical_clock` table** stores one row per `session_id` with the current `seq` value, providing O(1) clock read and increment without scanning the `operations` table.

`BlockSnapshot` is the JSON representation of a block's full state at a point in time: `{uuid, content, position, level, parentUuid, leftUuid, propertiesJson}`.

## Rationale

**JSON payload over normalization.** The before/after snapshot is a variable-length record. Normalizing it (one column per block field) would require schema changes every time `Block` adds a field, and would produce sparse columns for operations that only affect content. A JSON blob decouples the op log schema from the block schema. The `payload` column is append-only and never updated, so JSON parsing cost is paid only on undo, not on write.

**Lamport clock over wall-clock.** Wall-clock timestamps are not monotonic across devices (clock drift, NTP corrections, system sleep). Lamport `seq` is guaranteed to increase within a session regardless of wall time, which is the property needed for undo ordering. UUID v7 `op_id` provides a time-ordered globally unique identifier that is sortable across sessions without coordinating clocks — used for cross-device op ordering in Phase 3.

**Append-only table over `version` column.** A `version` integer on `blocks` tracks the count of edits but not the content of each edit. Undo requires knowing the previous content, which a version counter cannot provide. The append-only log is the standard event-sourcing pattern for this class of problem and directly implements the "operation log" success criterion.

**`session_id` per device.** Multi-device use (the target scenario) requires distinguishing "operations from this machine" from "operations pulled from another machine". `session_id` is generated once per DB file (on first open) and stored in the `metadata` table. It serves as the device identity for both undo scoping and Phase 3 fork detection.

**Compaction deferred.** For personal-scale use (one user, one graph), the operations table grows at roughly one row per keystroke-debounced save. At 500ms debounce with 2 active editing hours per day, that is ~14,400 rows/day or ~5M rows/year. With an average payload of 200 bytes, this is ~1 GB/year — non-trivial but manageable. Compaction (delete ops older than 90 days with payload exceeding 100 MB total) is specified as a future task and enforced as a soft bound in `UndoManager` without deleting rows.

## Consequences

### Positive
- Persistent undo across restarts: load ops by `session_id` ordered by `seq DESC`, invert until SYNC_BARRIER
- Fault-tolerant: the log is append-only; no update or delete during normal operation (only compaction)
- `BATCH_START` / `BATCH_END` pairs enable atomic undo of multi-block operations (find-replace)
- `SYNC_BARRIER` cleanly separates local from remote history without complex clock algebra
- UUID v7 `op_id` is time-ordered: useful for cross-device ordering and debugging

### Negative (accepted costs)
- JSON payload requires parsing on undo (paid at undo time only, not write time)
- Unbounded log growth in Phase 2 (compaction deferred to future iteration)
- `payload` column is a TEXT column storing JSON — not type-checked by SQLDelight; requires careful serialization in `OperationLogger`

### Risks
- JSON serialization bugs in `BlockSnapshot` could produce undeserializable payloads. Mitigation: add a `BlockSnapshot` round-trip unit test in `jvmTest`.
- If a `BATCH_START` is written but the app crashes before `BATCH_END`, the undo mechanism encounters an unclosed batch. Mitigation: `UndoManager` treats an unclosed `BATCH_START` (no matching `BATCH_END` within session) as a boundary marker that terminates undo traversal, same as `SYNC_BARRIER`.

## Alternatives Rejected

- **Normalized before/after columns**: Rejected because schema changes to `Block` would require corresponding schema changes to the op log. The JSON blob approach is more resilient to model evolution.
- **`version` + `block_history` table (normalized snapshots)**: Rejected because it requires a separate full-block snapshot table. The append-only `operations` table with JSON payload achieves the same result with a simpler schema.
- **Wall-clock `seq`**: Rejected due to non-monotonicity across devices. Lamport `seq` is the correct primitive for logical time in a distributed system without synchronized clocks.
- **Single-device session assumption (no `session_id`)**: Rejected because the requirements explicitly include multi-device use. `session_id` costs one TEXT column and is required for Phase 3 fork detection.
