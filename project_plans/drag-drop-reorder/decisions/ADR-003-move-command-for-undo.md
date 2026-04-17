# ADR-003: Wrap drag-drop reorder in a snapshot-based undo entry via BlockStateManager

**Date**: 2026-04-16  
**Status**: Accepted  
**Feature**: Drag-and-Drop Block Reordering

## Context

SteleKit's undo system has two layers:
1. `command/UndoManager.kt` — a generic command stack used by `BlockStateManager` for snapshot-based undo.
2. `command/BlockCommands.kt` — specific `Command<Unit>` implementations (`MoveBlockUpCommand`, `MoveBlockDownCommand`, etc.).

`BlockStateManager.moveSelectedBlocks()` already wraps the entire operation in a snapshot undo entry using `record(undo = ..., redo = ...)`. This mechanism captures a full page snapshot before and after the move, making it trivially undoable without needing a bespoke `MoveBlockCommand`.

The alternative — adding a `MoveBlockCommand` to `BlockCommands.kt` — would require tracking the old parent UUID, old position, and all sibling positions for each moved block. With multi-block moves this becomes a complex list of interdependent reversals.

## Decision

Drag-and-drop reorder calls `BlockStateManager.moveSelectedBlocks(newParentUuid, insertAfterUuid)` at drag end, identical to how keyboard-driven moves already work. No new `Command` subclass is added. The snapshot-based undo already in `moveSelectedBlocks` provides full undo/redo support.

## Rationale

- **DRY**: One move path, one undo path. No duplicated position-restore logic.
- **Correctness**: Snapshot captures the exact tree state; no risk of position drift from linked-list partial restores.
- **Simplicity**: `moveSelectedBlocks` is already battle-tested by the multi-block selection feature.

## Consequences

- **Positive**: Undo/redo works for drag-and-drop immediately with zero additional undo infrastructure.
- **Negative**: Snapshot-based undo stores a full copy of all page blocks, which is larger than a diff-based approach. Acceptable for typical page sizes (< 5000 blocks).
- **Constraint**: The drag gesture must ensure the dragged block(s) are in `selectedBlockUuids` before calling `moveSelectedBlocks`. `BlockList` already handles this via `onAutoSelectForDrag`.

## Patterns Applied

- **Command Pattern** (snapshot variant): `record(undo, redo)` in `BlockStateManager`.
- **Memento Pattern**: Page snapshot stores full tree state for rollback.
