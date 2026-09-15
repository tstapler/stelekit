# Requirements: Block Reorder, Bulk Delete & Copy-Paste

## Context

SteleKit is a Kotlin Multiplatform Logseq migration. Blocks on a page are stored with:
- `position`: fractional-index String (e.g. "a0", "a1") — determines sibling order
- `leftUuid`: linked-list predecessor pointer — maintained for efficient chain traversal
- `parentUuid`: tree parent

The `BlockStateManager` manages in-memory block state and writes to SQLite via `DatabaseWriteActor`.
Pages are also persisted to markdown files on disk via `GraphWriter.queueSave`.
On app restart, markdown files are re-read by `GraphLoader` and saved back to SQLite.

## Confirmed Bugs (Investigation Results)

### Bug 1 [CRITICAL]: Reordering not persisted to disk
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
**Functions**: `moveBlockUp` (~L979), `moveBlockDown` (~L991), `indentBlock` (~L955), `outdentBlock` (~L967)
**Root cause**: These functions call `writeMoveBlockUp/Down` (updates SQLite) and `refreshBlocksForPage`
(updates in-memory `_blocks`) but do NOT call `queueDiskSave(pageUuid)`.
**Effect**: Reordering persists in SQLite during a session, but is lost on app restart because
`GraphLoader` re-reads the unchanged markdown file and overwrites the DB.
**Contrast**: `deleteSelectedBlocks` and `moveSelectedBlocks` (drag-drop) DO call `queueDiskSave`.
**Fix**: Add `queueDiskSave(pageUuid)` after `refreshBlocksForPage` in all 4 functions.

### Bug 2 [MEDIUM]: Optimistic block position uses string concatenation
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
**Lines**: `addNewBlock` (~L1018), `splitBlock` (~L1069)
**Root cause**: `position = sourceBlock.position + 1` — since `position` is a `String`, Kotlin does
string concatenation, producing e.g. "a0" + 1 = "a01", not a valid FractionalIndexing key.
**Effect**: Optimistic blocks (shown immediately before DB write returns) have invalid positions,
causing brief visual reordering glitches. After the DB write completes and the page is re-queried,
blocks snap to their correct positions.
**Fix**: Use `FractionalIndexing.generateKeyBetween(sourceBlock.position, nextSiblingPosition)`.

### Bug 3 [FEATURE GAP]: Block copy-paste not implemented
**State**: `BlockClipboard` / `ClipboardBlock` models exist in `clipboard/ClipboardModels.kt`
but are never populated or used. There is no `copySelectedBlocks()` or `pasteBlocks()` in
`BlockStateManager`. No keyboard shortcut wires Ctrl+C / Ctrl+V for block-level operations.
The toolbar's selection mode only shows "Delete" (no Copy button).

## Requirements

### R1: Structural reordering must persist to disk
- After `moveBlockUp`, `moveBlockDown`, `indentBlock`, `outdentBlock`: the page's markdown
  file on disk must be updated (within the existing 300ms+500ms debounced save window).
- Verified by: closing and reopening the page confirms the reordered state.

### R2: Optimistic block position must be a valid fractional key
- When creating a new block (Enter) or splitting a block, the optimistic in-memory block
  must use `FractionalIndexing.generateKeyBetween(sourceBlock.position, nextSiblingPosition)`
  for its `position` field.
- `nextSiblingPosition`: find the sibling immediately after `sourceBlock` in the current page's
  in-memory block list (same parentUuid, sorted by position, first block after source).
- This ensures `BlockSorter.sort()` places the optimistic block in the correct visual position
  from the moment it appears, with no post-DB-write snapping.

### R3: Block copy-paste must work in selection mode
- **Copy (Ctrl+C / toolbar button)**:
  - Available when `isInSelectionMode && selectedBlockUuids.isNotEmpty()`
  - Stores the selected blocks + their subtrees (subtree-deduped, in visual order) in an
    in-memory `BlockClipboard` state on `BlockStateManager`
  - Shows a "Copied N blocks" transient notification
- **Paste (Ctrl+V)**:
  - Available when `blockClipboard` is non-empty and a block is focused (editingBlockUuid != null)
  - Inserts the clipboard blocks AFTER the currently focused block
  - Each pasted block gets a new UUID (v7) to avoid conflicts
  - Pasted blocks retain: content, level (adjusted relative to insertion point), properties, blockType
  - Pasted block trees maintain their internal parent/child relationships
  - Triggers `queueDiskSave` so paste is persisted to disk
  - Clears clipboard if the original operation was CUT (not COPY)
- **Toolbar**: Add a "Copy" icon button next to "Delete" in selection mode in `MobileBlockToolbar`
- **Keyboard**: Wire Ctrl+C to copy in `PageView.kt` when in selection mode

### R4: Bulk delete already works — verify and preserve
- `deleteSelectedBlocks()` in `BlockStateManager` correctly:
  - Removes selected blocks + subtrees from DB
  - Repairs linked-list chain
  - Calls `queueDiskSave`
  - Records undo entry
- No functional changes needed; add a regression test.

## Out of Scope
- Cross-graph paste (pasting between different graphs) — future work
- Text-level paste (`TextOperations.paste()`) — dead code, Compose's BasicTextField handles text paste natively
- Drag-drop reordering fixes — the core DB logic is correct; drag-drop already calls `queueDiskSave`

## Files to Modify

| File | Change |
|------|--------|
| `ui/state/BlockStateManager.kt` | R1: add `queueDiskSave`; R2: fix position; R3: add clipboard methods |
| `ui/components/MobileBlockToolbar.kt` | R3: add Copy button in selection mode |
| `ui/screens/PageView.kt` | R3: wire Ctrl+C keyboard shortcut |
| `ui/state/BlockEditorPorts.kt` | R3: add `copySelectedBlocks`, `pasteBlocks` to interface |
| `clipboard/ClipboardModels.kt` | R3: extend `BlockClipboard.withBlocks(...)` for multi-block |

## Success Criteria
- [ ] Pressing Alt+Up/Alt+Down to reorder, closing the page, reopening → order preserved
- [ ] Pressing Tab/Shift+Tab to indent/outdent, closing the page, reopening → hierarchy preserved
- [ ] Pressing Enter on a block: new block appears immediately in correct position
- [ ] Selecting blocks, Ctrl+C, clicking elsewhere, Ctrl+V → blocks pasted after focused block
- [ ] Ctrl+A to select all, Delete → all blocks deleted (regression: still works)
- [ ] Drag-drop reorder → order preserved on reload (regression: still works)
