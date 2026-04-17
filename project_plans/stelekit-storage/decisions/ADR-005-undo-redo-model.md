# ADR-005: Undo/Redo Model

**Status**: Proposed
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

## Context

SteleKit's first success criterion is: "the user can undo a change made in a previous session". No existing comparable tool (Logseq, Obsidian, Notion, Roam) provides cross-restart undo. The operation log (ADR-003) is the enabling primitive; this ADR specifies how `UndoManager` reads that log and inverts operations.

Key design questions:
1. **Undo scope**: Should undo be per-page, per-session, or global?
2. **Undo barrier**: What prevents undo from "undoing" a git sync that merged another machine's changes?
3. **Batch atomicity**: How does find-replace (N blocks across M pages) become one undo unit?
4. **Undo depth bounds**: How old can undoable operations be? How much storage is acceptable?
5. **Redo after undo**: Standard undo/redo stack semantics vs. linear history

## Decision

**Undo scope**: Global undo within `session_id`. Undo traverses the operation log for the current device's session in reverse `seq` order, regardless of which page was edited.

**Undo barrier**: `SYNC_BARRIER` entries in the op log are non-undoable boundaries. `UndoManager.undo()` stops traversal when it encounters a `SYNC_BARRIER` and reports `canUndo = false`. Operations before the barrier are considered "committed to shared history" and cannot be undone unilaterally.

**Batch atomicity**: A `BATCH_START` / `BATCH_END` pair in the op log marks a group of operations that must be undone as a unit. `UndoManager.undo()` detects a `BATCH_END` entry and walks backward to the matching `BATCH_START`, inverting all operations between them in reverse order as a single `writeActor.executeBatch` call.

**Undo depth bounds (soft)**:
- Maximum undo depth: 90 days of operations
- Maximum payload storage: 100 MB of `payload` column content per session
- These are soft bounds enforced at traversal time (skip older ops) rather than deletion bounds

**Redo model**: Standard linear redo stack. Each undo pushes the inverted op onto a `redoStack: MutableList<Op>`. Any new user edit (not from undo/redo) clears the redo stack. Redo re-applies the top of the redo stack.

## Rationale

**Global session undo over per-page undo.** Users expect `Ctrl+Z` to undo the last thing they did, regardless of which page it was on. Per-page undo would require the user to navigate back to the edited page before undoing, which is counter-intuitive. Global session undo matches the mental model established by word processors and code editors.

**`SYNC_BARRIER` as the undo boundary.** A git sync merges another machine's changes into the local DB. Undoing past a sync barrier would silently discard the remote machine's edits, which is the exact data loss scenario the architecture is designed to prevent. The barrier is the correct semantic: "everything before this point is shared history and cannot be undone without coordination".

**BATCH_START/END over undo group stack.** An in-memory undo group stack (like ProseMirror's `tr.addToHistory(false)`) is lost on app restart. The op log persists across restarts; `BATCH_START`/`BATCH_END` marker entries in the log provide the same grouping semantics with durability. The `batchId` field in the payload links the start and end entries for efficient lookup.

**90-day / 100 MB soft bounds.** These are deliberately soft (enforced at traversal, not at write time) to avoid a write path that blocks on measuring the current op log size. The user experience degradation from approaching the bound (older edits become non-undoable) is acceptable. A hard bound via compaction is deferred to a future iteration.

**Redo stack cleared on new edit.** The standard text editor convention. Maintaining redo across arbitrary non-sequential new edits (the "persistent undo" model in Vim) is significantly more complex and is not required by the success criteria.

**In-memory `undoneOpIds` set.** The set of op IDs that have been undone is not persisted to the DB in Phase 2. On app restart, the redo stack is empty. This means "undo, restart, redo" does not work. This is an accepted limitation for Phase 2; full redo durability can be added by persisting `undoneOpIds` in the `metadata` table.

## Consequences

### Positive
- Cross-restart undo works for `undo()` — the op log is re-queried from DB on each undo gesture
- `SYNC_BARRIER` provides a principled, user-comprehensible reason why undo stops ("changes before this point were synced with another device")
- `BATCH_OPERATION` enables atomic undo of find-replace across the entire graph (success criterion 3)
- Undo is invertible at the block granularity: INSERT↔DELETE, UPDATE swaps before/after
- No in-memory undo stack that can be corrupted by app lifecycle events

### Negative (accepted costs)
- Cross-restart redo is not supported in Phase 2 (redo stack is in-memory only)
- `UndoManager` queries the op log on every undo gesture (acceptable: the query is indexed by `session_id` + `seq`)
- Unclosed `BATCH_START` (crash mid-batch) acts as a SYNC_BARRIER — all ops before it become non-undoable in that session. Mitigation: `executeBatch` uses a `try/finally` to guarantee `BATCH_END` is written even on exception.

### Risks
- **Stack invalidation by concurrent op log writes**: If the file watcher imports external changes while the user is mid-undo, the op log grows with new entries between the user's undo gestures. Mitigation: `UndoManager` always re-queries from DB rather than caching the op list. New entries are simply skipped (they have higher `seq` than the ops being undone).
- **Undo of a block that no longer exists**: `INSERT_BLOCK` inversion calls `deleteBlock`. If the block was already deleted by a subsequent `DELETE_BLOCK` op (e.g., user deleted then tried to undo the original insert), the `deleteBlock` call is a no-op (block not found). `UndoManager` should handle `Result.failure` from missing-block deletes gracefully rather than halting.
- **Large batch undo performance**: A find-replace across 5,000 blocks produces a batch of 5,000 `UPDATE_BLOCK` ops. Undoing it requires loading and inverting all 5,000 payloads. Mitigation: read payload in a single bulk query ordered by `seq`, process in-memory. At ~200 bytes per payload this is ~1 MB in memory — acceptable.

## Alternatives Rejected

- **Per-page undo**: Rejected because it violates user expectations. `Ctrl+Z` should undo the last action, not "the last action on this specific page".
- **Undo boundary at page navigation**: Rejected because page navigation is not a semantically significant event in an outliner. A user navigating between journal entries and a reference note expects to be able to undo edits on both.
- **Persistent undo (Vim-style: never clears redo on new edit)**: Rejected as out of scope. The complexity of maintaining a DAG of undo history is not justified by the requirements. Standard linear redo is sufficient.
- **Hard undo depth limit (stop writing ops after N)**: Rejected because it causes unpredictable data loss if the user crosses the limit mid-session. Soft bounds (skip old ops at traversal time) preserve all data while making the trade-off explicit.
- **In-memory-only undo stack (no op log)**: Explicitly ruled out by success criterion 1 ("undo a change made in a previous session"). The op log is required.
