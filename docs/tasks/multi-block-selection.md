# Multi-Block Selection with Drag-and-Drop Reparenting and Deletion — Implementation Plan

**Feature**: Multi-block selection, drag-to-reparent, bulk delete, keyboard + mobile UX  
**Status**: Planning  
**Target**: JVM Desktop + Android (KMP Compose Multiplatform)

---

## Table of Contents

1. [Epic Overview](#epic-overview)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Architecture Overview](#architecture-overview)
5. [Architecture Decisions](#architecture-decisions)
6. [Dependency Graph](#dependency-graph)
7. [Story Breakdown](#story-breakdown)
   - [Story 1: Selection State Foundation](#story-1-selection-state-foundation)
   - [Story 2: Keyboard Selection (Desktop)](#story-2-keyboard-selection-desktop)
   - [Story 3: Mobile Selection UX](#story-3-mobile-selection-ux)
   - [Story 4: Multi-Block Drag-and-Drop Reparenting](#story-4-multi-block-drag-and-drop-reparenting)
8. [Known Issues](#known-issues)
9. [Test Strategy](#test-strategy)

---

## Epic Overview

### User Value

Users editing large outlines need to reorganize groups of blocks — moving a cluster of notes under a new parent, or deleting a section of obsolete entries — without repeating the same operation one block at a time. On desktop this means keyboard-driven range selection followed by drag or delete. On mobile it means long-press to enter selection mode, tap to add blocks, then toolbar actions.

### Success Metrics

- A user can select 5 non-contiguous blocks, drag them as a unit onto a new parent, and see the correct order preserved, all in one gesture.
- Shift+click and Shift+Arrow correctly extend a contiguous selection without deselecting on every keypress.
- Long-press on mobile enters selection mode; a follow-on toolbar appears with Delete and Move options.
- Bulk delete removes all selected blocks (and their subtrees) in a single undo-able step.
- No existing single-block operations (indent, outdent, move up/down, collapse) regress.

### Scope

In scope:
- Selection state model: selected block UUIDs held in `BlockStateManager`
- Keyboard selection on JVM: Shift+click, Shift+ArrowUp/Down, Ctrl+A (select all visible)
- Mobile selection: long-press gesture enters selection mode; tap toggles membership; toolbar actions Delete + Move
- Bulk delete with children, wrapped in a single undo entry
- Drag-to-reparent of the selected set: drag any selected block, the whole set moves atomically
- Visual feedback: highlight row, checkbox in gutter, selection count badge

Out of scope (future iterations):
- Cross-page drag between two open pages
- Cut/paste to clipboard as text
- Selection persistence across navigation

### Constraints

- All operations must work through `BlockRepository` (which wraps SQLDelight and `InMemoryBlockRepository`); no direct SQL from UI.
- The linked-list ordering scheme (`left_uuid`) must be maintained correctly for every block moved; the repository's `moveBlock` already does this for single blocks, and that implementation must be the building block for bulk moves.
- Compose Multiplatform drag-and-drop has no built-in multi-item support: the drag ghost and drop target logic must be written from scratch using `pointerInput`.
- Platform-specific gestures (long-press on Android vs mouse hover on JVM) must be handled with `expect/actual` or a platform detection flag already available in the project.
- No new third-party dependencies may be added; use only what is already in `kmp/build.gradle.kts`.

---

## Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| MB-01 | `BlockStateManager` exposes a `selectedBlockUuids: StateFlow<Set<String>>` | Must |
| MB-02 | Clicking a block bullet/gutter area in selection mode toggles that block in the set | Must |
| MB-03 | Shift+click extends the selection from the anchor to the clicked block (contiguous range) | Must |
| MB-04 | Shift+ArrowUp / Shift+ArrowDown extend the selection one block in the visual order | Must |
| MB-05 | Ctrl+A selects all visible (non-collapsed) blocks on the current page | Must |
| MB-06 | Escape clears the selection and returns to normal editing mode | Must |
| MB-07 | Long-press (>500 ms) on any block on mobile enters selection mode and selects that block | Must |
| MB-08 | When selection mode is active on mobile, tapping a block toggles its membership | Must |
| MB-09 | `MobileBlockToolbar` gains a selection-mode row with Delete and (future) Move actions | Must |
| MB-10 | Bulk delete removes all selected blocks and their subtrees in one atomic repository call | Must |
| MB-11 | Bulk delete is wrapped in a single undo entry in `BlockStateManager` | Must |
| MB-12 | Dragging any block in the selected set initiates a multi-block drag of the whole set | Must |
| MB-13 | Drop target indicator renders between blocks and as a child drop zone below a block | Must |
| MB-14 | On drop, all selected blocks are reparented under the target parent in their relative order | Must |
| MB-15 | Reparenting preserves the original relative ordering among the dragged blocks | Must |
| MB-16 | Visual selection highlight (background tint + checkbox) renders on selected blocks | Must |
| MB-17 | A selection count badge is shown in the toolbar / top area when blocks are selected | Should |
| MB-18 | Blocks that are ancestors of other selected blocks are not moved twice (subtree detection) | Must |

---

## Non-Functional Requirements

| Attribute | Target |
|-----------|--------|
| Atomicity | Bulk move and bulk delete operate inside a single repository transaction (or sequential calls behind `Mutex`) so partial failures leave no half-moved state |
| Undo granularity | One undo step reverses the entire bulk operation |
| Rendering cost | Selection overlay adds at most one `Box` with `background` modifier per block row; no extra `LazyColumn` recomposition passes |
| Gesture latency | Long-press fires at 500 ms per Material Design guidelines; drag feedback renders within one frame after pointer move |
| Test coverage | `BlockStateManager` selection and bulk-delete covered by `businessTest`; `moveBlock` ordering covered by existing `SqlDelightBlockRepository` tests extended with multi-block scenarios |

---

## Architecture Overview

### Current State

```
PageView
  └── BlockList (Column, non-lazy render)
        └── BlockItem* (one per visible block)
              ├── BlockGutter  (drag handle, bullet, collapse toggle)
              └── BlockEditor / BlockViewer
```

`BlockStateManager` holds:
- `blocks: StateFlow<Map<pageUuid, List<Block>>>`
- `editingBlockUuid: StateFlow<String?>`
- `collapsedBlockUuids: StateFlow<Set<String>>`

`BlockRepository.moveBlock(uuid, newParentUuid, newPosition)` handles single-block reparenting with linked-list chain repair. It exists in `SqlDelightBlockRepository`, `InMemoryBlockRepository`, and `DatascriptBlockRepository`.

`BlockRepository.deleteBlock(uuid, deleteChildren)` handles single-block deletion with chain repair.

### Target State

```
PageView
  └── BlockList (receives selectionState)
        └── BlockItem* (receives isSelected, onToggleSelect)
              ├── BlockGutter  (adds checkbox when selectionMode=true)
              └── DragDropOverlay  (NEW: drop indicator between items)
```

`BlockStateManager` gains:
- `selectedBlockUuids: StateFlow<Set<String>>`
- `selectionAnchorUuid: String?`  (private, for range select)
- `fun enterSelectionMode(firstUuid: String)`
- `fun toggleBlockSelection(uuid: String)`
- `fun extendSelectionTo(uuid: String)`
- `fun selectAll(pageUuid: String)`
- `fun clearSelection()`
- `fun deleteSelectedBlocks(): Job`
- `fun moveSelectedBlocks(newParentUuid: String?, insertAfterUuid: String?): Job`

`BlockRepository` gains:
- `suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit>`
  - Implementations: SQLDelight (one transaction), InMemory (loop behind mutex), Datascript (loop behind mutex)

No new interface additions are needed for bulk move — `moveBlock` is called in a loop inside `moveSelectedBlocks` in `BlockStateManager`, which already serializes operations via coroutine scope and the repository's internal `Mutex`.

### Data Flow: Bulk Move

```
User drops selected blocks over target
        |
BlockList.onDrop(targetUuid, insertAfter)
        |
BlockStateManager.moveSelectedBlocks(targetParentUuid, insertAfterUuid)
        |
  1. Snapshot page state (undo)
  2. Compute sorted order of selected UUIDs (by current visual position)
  3. Filter out descendants of other selected blocks (avoid double-move)
  4. For each uuid in order:
       blockRepository.moveBlock(uuid, targetParentUuid, nextPosition++)
  5. Refresh blocks for page
  6. Snapshot page state (redo)
  7. Record undo entry
  8. clearSelection()
```

### Data Flow: Bulk Delete

```
User clicks Delete in toolbar (or presses Backspace/Delete key)
        |
BlockStateManager.deleteSelectedBlocks()
        |
  1. Snapshot page state (undo)
  2. Filter top-most selected blocks (subtree deduplication)
  3. blockRepository.deleteBulk(topMostUuids, deleteChildren=true)
  4. Refresh blocks for page
  5. Snapshot page state (redo)
  6. Record undo entry
  7. clearSelection()
```

---

## Architecture Decisions

### Decision 1: Selection state lives in `BlockStateManager`, not `AppState`

`AppState` is a global app-level state for navigation, sidebar visibility, and graph metadata. Selection is scoped to the currently viewed page and is tightly coupled to the block tree operations already owned by `BlockStateManager`. Adding `selectedBlockUuids` to `AppState` would require routing every selection change through `StelekitViewModel`, which is an unnecessary indirection. `BlockStateManager` is the right boundary: it already holds `editingBlockUuid`, `collapsedBlockUuids`, and the undo stack.

Consequence: `PageView` and `JournalsView` read `selectedBlockUuids` from `blockStateManager.selectedBlockUuids`, not from `AppState`.

### Decision 2: `deleteBulk` is a new `BlockRepository` operation; bulk move is NOT

Bulk delete needs atomicity that cannot be guaranteed by N sequential `deleteBlock` calls (intermediate chain states would be invalid if interrupted). A new `deleteBulk` repository method wraps all deletions in a single SQLite transaction.

Bulk move does not get a dedicated repository method. The single-block `moveBlock` already does full chain repair per call. Calling it N times sequentially in the same coroutine, with the existing per-repository `Mutex`, is safe and correct because the linked-list is repaired after each step. Adding a `moveBulk` repository method would duplicate significant chain-repair logic for marginal gain.

### Decision 3: Drag ghost is a floating `Box` composable, not a system drag-and-drop session

Compose Multiplatform's `dragAndDropSource` / `dragAndDropTarget` API (available as of Compose 1.6) is limited to single-item payloads and uses the platform's native DnD, which differs in capability between JVM and Android. For multi-block drag we need full control over the ghost rendering (show a badge with block count) and drop-target highlighting. A custom `pointerInput` implementation that tracks pointer position and renders a translucent ghost via `graphicsLayer` is the correct approach and is consistent with how the existing `BlockGutter` already uses `pointerInput`.

---

## Dependency Graph

```
Story 1: Selection State Foundation
    |
    +-- MB-01 selectedBlockUuids in BlockStateManager
    +-- MB-16 Visual highlight in BlockItem / BlockGutter
    +-- MB-06 Escape to clear
    |
Story 2: Keyboard Selection (Desktop)        Story 3: Mobile Selection UX
    depends on Story 1                           depends on Story 1
    |                                            |
    +-- MB-03 Shift+click                        +-- MB-07 Long-press enters mode
    +-- MB-04 Shift+Arrow                        +-- MB-08 Tap toggles
    +-- MB-05 Ctrl+A                             +-- MB-09 Toolbar actions
    +-- MB-02 Gutter toggle click                +-- MB-10/11 Bulk delete (shared)
    |                                            |
Story 4: Drag-and-Drop Reparenting
    depends on Story 1 (selection set)
    depends on Story 2 / 3 (for keyboard-initiated move)
    |
    +-- MB-12 Drag initiates multi-block drag
    +-- MB-13 Drop target indicator
    +-- MB-14/15 Atomic ordered reparent
    +-- MB-18 Subtree deduplication
```

---

## Story Breakdown

---

### Story 1: Selection State Foundation

**As a** user,  
**I want** blocks I click (or long-press on mobile) to show a highlighted state with a visible checkbox,  
**so that** I can build a selection set before acting on it.

**Acceptance Criteria:**
- Given no blocks are selected, when I click the bullet/gutter of a block, that block becomes selected and shows a blue-tinted background and a checked checkbox in the gutter.
- Given one block is selected, when I click the gutter of a different block, the first is deselected and the second is selected (single-toggle mode without modifier keys).
- Given one or more blocks are selected, when I press Escape, all blocks deselect.
- Given no blocks are selected, the `BlockGutter` renders identically to today (no regression).
- `BlockStateManager.selectedBlockUuids` emits the correct set on every change.

#### Task 1.1 — `BlockStateManager`: add selection state and selection API

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Work:**  
Add `private val _selectedBlockUuids = MutableStateFlow<Set<String>>(emptySet())` and its public `StateFlow`. Add `private var selectionAnchorUuid: String? = null`. Implement `enterSelectionMode(uuid)`, `toggleBlockSelection(uuid)`, `extendSelectionTo(uuid, visibleBlocks)`, `selectAll(pageUuid)`, and `clearSelection()`. `clearSelection()` also nulls the anchor. Expose a derived `val isInSelectionMode: StateFlow<Boolean>` computed from `selectedBlockUuids.map { it.isNotEmpty() }`.

**Validation:** Unit tests in `businessTest` covering toggle, extend-to, select-all, clear.

---

#### Task 1.2 — `BlockStateManager`: `deleteSelectedBlocks` + `deleteBulk` repository method

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

**Work:**  
Add `suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean): Result<Unit>` to the `BlockRepository` interface in `GraphRepository.kt`. Implement in `SqlDelightBlockRepository` as a single `queries.transaction { ... }` that calls `deleteBlockByUuid` for each UUID and repairs chains. Implement in `InMemoryRepositories` as a loop inside `writeMutex`. In `BlockStateManager`, add `fun deleteSelectedBlocks(): Job` that snapshots before, calls `subtreeDedup(selectedBlockUuids.value)`, calls `deleteBulk`, refreshes, snapshots after, records undo entry, clears selection.

**Subtree deduplication helper** (private in `BlockStateManager`): given a set of UUIDs, remove any UUID whose ancestor is also in the set. Iterate the current page block list; for each UUID in the set, walk up `parentUuid` chain; if any ancestor is in the set, exclude it.

**Validation:** Test `deleteBulk` in the `SqlDelightBlockRepository` test suite with overlapping parent/child pairs.

---

#### Task 1.3 — `BlockItem` / `BlockGutter`: visual selection overlay and toggle callback

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`

**Work:**  
Add `isSelected: Boolean` and `onToggleSelect: () -> Unit` parameters to `BlockItem`. In `BlockItem`, wrap the existing `Row` in a `Box` that conditionally applies `Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))` when `isSelected`. Pass `isSelected` and `onToggleSelect` down to `BlockGutter`. In `BlockGutter`, when `isSelected || isInSelectionMode`, render a `Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })` in place of the drag handle icon (or alongside it). In `BlockList`, read `selectedBlockUuids` (passed as a parameter) and wire `onToggleSelect = { blockStateManager.toggleBlockSelection(block.uuid) }`.

**Context for next task:** `BlockList` signature change propagates to `PageView` and `JournalsView`.

---

#### Task 1.4 — `PageView` + `JournalsView`: wire selection state into `BlockList`

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`

**Work:**  
In `PageView`, collect `blockStateManager.selectedBlockUuids` with `collectAsState()`. Pass `selectedBlockUuids` and `isInSelectionMode` to `BlockList`. Add a `KeyEventInterceptor` composable at the `Box` level to intercept Escape and call `blockStateManager.clearSelection()` when `isInSelectionMode` is true. Repeat for `JournalsView`.

**Context preparation:** Read `JournalsView.kt` for the `BlockList` call site before starting.

---

### Story 2: Keyboard Selection (Desktop)

**As a** desktop user,  
**I want** to extend my selection using Shift+click and Shift+Arrow keys, and select all with Ctrl+A,  
**so that** I can build large selections without touching the mouse for each block.

**Acceptance Criteria:**
- Given block A is the selection anchor, when I Shift+click block D, blocks A through D (in visual order) are all selected.
- Given blocks A–D are selected, when I Shift+ArrowDown, block E is added to the selection.
- Given blocks A–D are selected, when I Shift+ArrowUp, block D is removed (shrinks selection back toward anchor).
- Ctrl+A selects all visible (non-hidden) blocks on the current page.
- Ctrl+A followed by Delete invokes `deleteSelectedBlocks`.
- None of the above interfere with normal typing when no selection mode is active.

#### Task 2.1 — Keyboard handler in `BlockList` for Shift+click and Shift+Arrow

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

**Work:**  
Add `onShiftClick: (uuid: String) -> Unit` callback on `BlockItem`. In `BlockItem`'s `BlockViewer` click handler (`onStartEditing`), detect `isShiftPressed` using `LocalWindowInfo` or by threading a `isShiftDown: Boolean` parameter derived from a `pointerInput` modifier placed in `BlockList`. On Shift+click, call `onShiftClick` instead of `onStartEditing`. In `BlockStateManager`, `extendSelectionTo(uuid, visibleBlocks)` walks the ordered `visibleBlocks` list from the anchor to `uuid` and replaces the current selection set with that range.

For Shift+Arrow: add a `KeyEventInterceptor` in `BlockList` listening on `Key.DirectionUp`/`Key.DirectionDown` with `isShiftDown`. When fired, call `blockStateManager.extendSelectionByOne(direction, visibleBlocks)`.

---

#### Task 2.2 — Ctrl+A and Delete/Backspace keyboard shortcuts in selection mode

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

**Work:**  
In `PageView`, the top-level `Box` already has `pointerInput` for tap-to-unfocus. Extend it with `onKeyEvent` (or a `Modifier.onKeyEvent`) that:
- On `Ctrl+A`: calls `blockStateManager.selectAll(page.uuid)`.
- On `Key.Delete` or `Key.Backspace` when `isInSelectionMode`: calls `blockStateManager.deleteSelectedBlocks()`.
- On `Escape` when `isInSelectionMode`: calls `blockStateManager.clearSelection()`.

Only intercept these events when a `BasicTextField` is not currently focused (check `editingBlockUuid == null`).

---

### Story 3: Mobile Selection UX

**As a** mobile user,  
**I want** to long-press a block to enter selection mode and then use the toolbar to delete or move selected blocks,  
**so that** I can reorganize content with a touch-friendly flow.

**Acceptance Criteria:**
- Long-pressing (>500 ms) a block enters selection mode and selects that block. Haptic feedback fires if the device supports it.
- While in selection mode, tapping any block gutter toggles its selection (without opening the editor).
- The `MobileBlockToolbar` gains a second mode: when `isInSelectionMode` is true, it shows a Delete button and a selection count label instead of the formatting row.
- Tapping Delete in the selection toolbar calls `blockStateManager.deleteSelectedBlocks()`.
- Tapping outside any block gutter (on the page background) does not accidentally deselect — only Escape / the X button in the toolbar clears selection.

#### Task 3.1 — Long-press gesture in `BlockItem` to enter selection mode

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

**Work:**  
Add `isInSelectionMode: Boolean` and `onEnterSelectionMode: () -> Unit` parameters to `BlockItem`. Wrap the existing `Row` with an additional `pointerInput(Unit)` that calls `detectTapGestures(onLongPress = { onEnterSelectionMode() })`. When `isInSelectionMode` is true, the existing tap-to-edit gesture (`onStartEditing`) is suppressed and `onToggleSelect` fires instead. Use `HapticFeedback` (available via `LocalHapticFeedback`) on long-press.

---

#### Task 3.2 — `MobileBlockToolbar` selection mode

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

**Work:**  
Add `isInSelectionMode: Boolean`, `selectedCount: Int`, `onDeleteSelected: () -> Unit`, and `onClearSelection: () -> Unit` parameters to `MobileBlockToolbar`. When `isInSelectionMode` is true, replace both existing rows with a single row containing: a Text showing "$selectedCount selected", a Delete `IconButton`, and a Clear/X `IconButton`. When `isInSelectionMode` is false, the toolbar renders exactly as today.

In `PageView`, collect `blockStateManager.selectedBlockUuids.collectAsState()` and pass `isInSelectionMode`, `selectedCount`, `onDeleteSelected`, and `onClearSelection` to `MobileBlockToolbar`.

---

#### Task 3.3 — Wire selection callbacks through `BlockList` to mobile gestures

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

**Work:**  
`BlockList` now passes `isInSelectionMode`, `onEnterSelectionMode = { uuid -> blockStateManager.enterSelectionMode(uuid) }`, and `onToggleSelect` down to each `BlockItem`. When `isInSelectionMode` is true, `BlockList` intercepts normal block tap events to call `toggleBlockSelection` instead of `requestEditBlock`. `PageView` wires the callbacks from `blockStateManager`.

---

### Story 4: Multi-Block Drag-and-Drop Reparenting

**As a** user,  
**I want** to drag any selected block (or a single block if none are selected) and drop it onto a new parent,  
**so that** I can reorganize my outline hierarchy in a single drag gesture.

**Acceptance Criteria:**
- Dragging a block that is part of the selected set drags the whole set as a unit.
- Dragging a block that is NOT in the selected set selects only that block and drags it alone (consistent with single-block drag behavior).
- A translucent drag ghost shows a stack icon with the count of dragged blocks.
- A horizontal line indicator renders between blocks to show where the drop will land.
- A nested indicator renders below a block to show that dropping will make it a child.
- On drop, all dragged blocks are reparented in their original relative order.
- On drop, the dragged blocks' subtrees move with them (the repository's `moveBlock` already propagates level changes; verify this holds).
- Dragging a block into its own descendant is rejected (existing `BlockOperationValidator.validateMoveOperation` logic).

#### Task 4.1 — `BlockStateManager`: `moveSelectedBlocks` operation

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Work:**  
Implement `fun moveSelectedBlocks(newParentUuid: String?, insertAfterUuid: String?): Job`. Steps:
1. Snapshot page state (before).
2. Get the current page's sorted block list.
3. Collect selected UUIDs sorted by their index in the sorted block list.
4. Apply subtree deduplication (same helper used in `deleteSelectedBlocks`).
5. Validate each block is not being moved into its own subtree (call `BlockOperationValidator.validateMoveOperation` per block; skip invalid ones and emit a warning log).
6. Compute `startPosition`: the position immediately after `insertAfterUuid` in the new parent's children, or 0 if dropping at top.
7. Call `blockRepository.moveBlock(uuid, newParentUuid, startPosition + index)` in a loop.
8. Refresh blocks.
9. Snapshot after, record undo, clear selection.

---

#### Task 4.2 — Drag state model and drag ghost composable

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (new drag state holder)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockDragGhost.kt` (new file)

**Work:**  
Define a local `data class BlockDragState(val draggedUuids: Set<String>, val offsetY: Float, val isDragging: Boolean)` inside `BlockList` held in a `remember { mutableStateOf<BlockDragState?>(null) }`. Create `BlockDragGhost.kt` as a simple `Box` with `graphicsLayer(alpha = 0.75f)` containing a `Card` with a block-stack icon and a `Text("N blocks")`. The ghost is rendered as an overlay in `BlockList` using a `Box { ... }` wrapper at the top level, positioned at `offsetY` using `Modifier.offset(y = dragOffsetDp)`.

---

#### Task 4.3 — Drag initiation in `BlockGutter` and pointer tracking in `BlockList`

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`

**Work:**  
Add `onDragStart: (uuid: String) -> Unit` and `onDragEnd: () -> Unit` parameters to `BlockItem`. In `BlockGutter`, add a `pointerInput` on the drag handle icon that calls `detectDragGestures(onDragStart = { onDragStart(blockUuid) }, onDrag = { ... offset ... }, onDragEnd = { onDragEnd() })`. In `BlockList`, when `onDragStart` fires: if the dragged UUID is in `selectedBlockUuids`, set `dragState` with the full selected set; otherwise select only that UUID and set `dragState`. Track pointer `offsetY` via `onDrag` callback to update `dragState.offsetY` for the ghost position. On `onDragEnd`, compute the drop target from the final `offsetY` and call `blockStateManager.moveSelectedBlocks(...)`.

**Drop target calculation:** maintain a `List<Pair<String, IntRange>>` that maps each rendered block UUID to its vertical pixel range (populated via `Modifier.onGloballyPositioned`). On drag end, find the block whose range contains `offsetY` and determine if the pointer is in the top third (drop above), middle third (drop as child), or bottom third (drop below).

---

#### Task 4.4 — Drop indicator UI in `BlockList`

**Files:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`

**Work:**  
During an active drag, compute the current `dropTarget: DropTargetState?` (sealed class with `Above(uuid)`, `Child(uuid)`, `Below(uuid)`) from the pointer position and block bounds. Pass `dropTarget` to each `BlockItem` via an `isDropTarget: Boolean` and `dropPosition: DropPosition?` parameter. In `BlockItem`, when `isDropTarget` is true, render a `HorizontalDivider` with `Modifier.background(MaterialTheme.colorScheme.primary)` above or below the block row, or a left-indented indicator for the child case.

---

## Known Issues

### Concurrency Risk: Race Condition in `moveSelectedBlocks` Position Calculation [SEVERITY: High]

**Description:** When N blocks are moved in a loop, each `moveBlock` call reorders the sibling list. Position indices computed before the loop begins are stale by the second call. For example, if blocks at positions 2, 4, 6 are moved to positions 0, 1, 2 under a new parent, the second call must account for the sibling that was just inserted at position 0.

**Mitigation:**
- In `moveSelectedBlocks`, pass `startPosition + index` where index is the loop counter, not a pre-computed absolute position. This works because each subsequent block is inserted immediately after the previous one.
- Add an integration test in `SqlDelightBlockRepositoryTest` that moves three non-contiguous blocks to a new parent and asserts their resulting positions are 0, 1, 2 in the correct relative order.

**Files Likely Affected:**
- `BlockStateManager.kt` (`moveSelectedBlocks`)
- `SqlDelightBlockRepository.kt` (`moveBlock` chain repair on new-position side)

---

### Data Integrity Risk: `deleteBulk` Does Not Repair Chains for the Middle Blocks [SEVERITY: High]

**Description:** Deleting a contiguous span of siblings leaves the linked-list (`left_uuid`) chain broken unless each deletion repairs the chain of its right-neighbor. The existing `deleteBlock` does this repair. A naive `deleteBulk` that simply iterates UUID-by-UUID without reprocessing the updated chain state will leave orphaned `left_uuid` pointers.

**Mitigation:**
- Implement `deleteBulk` as a sequential loop of the existing `deleteBlock` logic (re-read the sibling chain from the DB after each deletion), all within a single SQLite transaction.
- The `InMemory` implementation should similarly call the existing `deleteBlock` logic in sequence rather than mass-removing from the map.
- Add a test: delete blocks at positions 1, 2, 3 from a 5-sibling list; verify positions 0 and 4 now have a correct linked relationship and positions recompact to 0, 1.

**Files Likely Affected:**
- `SqlDelightBlockRepository.kt` (`deleteBulk`)
- `InMemoryRepositories.kt` (`deleteBulk`)

---

### UI Risk: `BlockList` Column Recompose on Every Drag Frame [SEVERITY: Medium]

**Description:** `BlockList` is a `Column` (not `LazyColumn`) that iterates `blocks.forEach`. Any `MutableState` change inside `BlockList` (e.g., `dragState.offsetY`) that causes a recompose will re-run the entire `forEach`. With 200+ blocks, this causes frame drops during drag.

**Mitigation:**
- Hoist `dragState` into a `derivedStateOf` so that the ghost's `offsetY` changes do not trigger recompose of the full column.
- Render the drag ghost as a sibling `Box` overlay outside the `Column`, so its position update only recomposes that `Box`, not the block list.
- Add a `key(block.uuid)` on each `BlockItem` call inside `BlockList` so that non-dragged blocks skip recomposition.

**Files Likely Affected:**
- `BlockList.kt`
- `BlockDragGhost.kt`

---

### UX Risk: Drag Gesture Conflicts with Scroll on Mobile [SEVERITY: Medium]

**Description:** The `LazyColumn` in `PageView` handles vertical scroll via the same pointer input channel as the custom drag logic. On Android, a vertical drag on a block will compete with the scroll gesture, causing erratic behavior: either the drag starts but the list also scrolls, or the drag never initiates because the scroll consumes the event first.

**Mitigation:**
- Use `PointerInputScope.awaitPointerEventScope` with an explicit `awaitFirstDown` + `drag` loop rather than `detectDragGestures`, so the drag can use `consume()` on events once the horizontal or vertical intent is clear.
- Alternatively, wrap the `LazyColumn` in a `nestedScroll` connection that suspends scrolling when `isDragging` is true.
- Test on a physical Android device; emulator scroll behavior differs.

**Files Likely Affected:**
- `PageView.kt` (LazyColumn + nestedScroll)
- `BlockGutter.kt` / `BlockItem.kt` (drag gesture detection)

---

### Edge Case: Ancestor Block in Selected Set Causes Double-Move [SEVERITY: High]

**Description:** If blocks A and A's child B are both selected, moving the set will first move A (which carries B as a child) and then attempt to move B again. B's new parent after the first move is now under A in the new location; the second `moveBlock` may move it somewhere unexpected.

**Mitigation:**
- The subtree deduplication step in `moveSelectedBlocks` (and `deleteSelectedBlocks`) must filter out any UUID whose ancestor is also in the selected set before executing the loop.
- This logic is already described in Task 1.2 (the `subtreeDedup` helper). Ensure it is reused in Task 4.1, not duplicated.
- Test: select parent block and two of its children; drag to new location; verify children are NOT moved a second time.

**Files Likely Affected:**
- `BlockStateManager.kt` (`subtreeDedup` helper, `moveSelectedBlocks`)

---

### Integration Risk: `BlockViewer` Tap Intercepted in Selection Mode [SEVERITY: Low]

**Description:** `BlockViewer` uses `clickable { onStartEditing() }`. When `isInSelectionMode` is true, a tap on the block content (not the gutter) should toggle selection, not open the editor. If this is not handled, the user will accidentally enter edit mode when trying to multi-select.

**Mitigation:**
- Add an `isInSelectionMode: Boolean` parameter to `BlockViewer`. When true, replace `clickable { onStartEditing() }` with `clickable { onToggleSelect() }`.
- Ensure this is wired through `BlockItem` alongside the other selection parameters.

**Files Likely Affected:**
- `BlockItem.kt` (the `BlockViewer` call site)

---

## Test Strategy

### Unit Tests (`businessTest`)

| Test Class | Coverage |
|-----------|----------|
| `BlockStateManagerSelectionTest` | `enterSelectionMode`, `toggleBlockSelection`, `extendSelectionTo`, `selectAll`, `clearSelection`, `isInSelectionMode` derived state |
| `BlockStateManagerDeleteBulkTest` | `deleteSelectedBlocks` with ancestor deduplication, undo/redo correctness |
| `BlockStateManagerMoveSelectedTest` | `moveSelectedBlocks` relative ordering, subtree deduplication, single-block fallback |

### Repository Tests (`jvmTest` / `businessTest`)

| Test Class | Coverage |
|-----------|----------|
| `SqlDelightBlockRepositoryTest` (extended) | `deleteBulk` chain repair for middle deletions; `moveBlock` called sequentially for 3 blocks maintains correct linked-list state |
| `InMemoryBlockRepositoryTest` (extended) | Same scenarios as above on the InMemory backend |

### Integration / Screenshot Tests (`jvmTest` with Roborazzi)

| Scenario | Assertion |
|---------|-----------|
| Two blocks selected | Screenshot shows blue highlight on both rows, checkboxes checked |
| Drag in progress | Ghost overlay visible; drop indicator line rendered between target blocks |
| Mobile selection toolbar | Toolbar shows "2 selected" + Delete button when `isInSelectionMode=true` |

### Manual Regression Checklist

- [ ] Single-block indent / outdent / move up / move down unaffected when no blocks selected
- [ ] Collapse/expand unaffected by presence of selected blocks
- [ ] Autocomplete wiki-link `[[` not triggered in selection mode
- [ ] Undo of bulk delete restores all blocks in correct order
- [ ] Undo of bulk move restores all blocks to original parents and positions
- [ ] Ctrl+Z after Ctrl+A + Delete restores the entire page

---

## Integration Checkpoints

After Story 1 is merged: verify `BlockList` still renders correctly on Desktop and Android with `selectedBlockUuids = emptySet()` (no visible change from current behavior). Run `jvmTest`.

After Story 2 is merged: manually test Shift+click, Shift+Arrow, Ctrl+A, Delete on Desktop JVM. Verify typing in a `BlockEditor` is unaffected.

After Story 3 is merged: install on Android device. Long-press a block, verify haptic fires and mode activates. Tap two more blocks. Tap Delete in toolbar. Verify deletion and undo.

After Story 4 is merged: drag a group of 3 blocks from one parent to another on Desktop. Check order in the outline. Drag a parent+child pair; verify child is not double-moved.
