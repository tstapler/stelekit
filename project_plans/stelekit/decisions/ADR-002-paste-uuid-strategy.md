# ADR-002: UUID Strategy for Pasted Blocks

**Status**: Accepted  
**Date**: 2026-04-16  
**Feature**: Copy / Cut / Paste Blocks

## Context

When blocks are pasted, two behaviors are desired:
1. **Standard paste** (`Ctrl+V`): creates independent copies — new UUIDs for every block in the pasted tree. This matches Logseq behavior: paste makes copies, not references.
2. **Paste as reference** (`Ctrl+Shift+V`): inserts `((original-uuid))` block reference inline at the cursor position, preserving the source block UUID but not physically duplicating it.

The existing `BlockTreeOperations.duplicateSubtree()` already implements "generate new UUIDs for an in-repo subtree". The clipboard paste path is analogous but the source data comes from `BlockClipboard` rather than from a live repository query.

## Decision

- **Standard paste** calls a new `BlockClipboardService.pasteAsNewBlocks(clipboard, targetPageUuid, afterBlockUuid)` which iterates `clipboard.blocks`, generates fresh UUIDs via `UuidGenerator.generateV7()` for every entry, remaps `parentUuid` pointers using an `oldUuid → newUuid` map, and batch-saves with `BlockRepository.saveBlocks()`.
- **Paste as reference** calls `BlockClipboardService.pasteAsReference(clipboard, targetBlockUuid)` which takes the *first root block* UUID from the clipboard and appends `((uuid))` inline text to the target block's content. No new blocks are created.
- The distinction is encoded as a parameter `pasteMode: PasteMode` (enum: `COPY`, `REFERENCE`) so the service has a single entry point and unit tests can exercise both paths.

## Rationale

- Reusing `UuidGenerator.generateV7()` (already used by `BlockTreeOperations.duplicateSubtree`) ensures UUID consistency across the codebase.
- Encoding paste mode as an enum rather than two separate functions keeps the undo command simpler: `PasteBlocksCommand` can store the mode and delegate to the same service on undo.
- Reference paste is a one-liner append to block content — it reuses `UpdateBlockContentCommand` and requires no new infrastructure beyond the content transformation.

## Consequences

- Paste as reference produces a `((uuid))` inline node. The inline parser already supports `BlockRefNode`, so rendering is free.
- Multiple-block reference paste: if the clipboard has >1 root block, only the first block's UUID is referenced. This matches Logseq's behavior for reference paste when multi-block selection is involved.
- Cross-graph paste (cut from graph A, paste into graph B): the source block UUID will not resolve in graph B. The paste will still succeed (content is copied), but block references will render as `[block ref]` until resolved. This is accepted behavior for v1.

## Patterns Applied

- **Strategy** — `PasteMode` enum switches behavior inside `BlockClipboardService` without subclassing.
- **Command** — `PasteBlocksCommand : Command<Unit>` wraps the operation with undo support (delete inserted blocks on undo, restore cut source on undo of cut+paste).
