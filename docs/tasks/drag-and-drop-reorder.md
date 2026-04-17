# Drag-and-Drop Block Reordering — Implementation Plan

**Feature**: Drag-and-Drop Block Reordering  
**Status**: Planning  
**Target**: JVM Desktop + Android (KMP Compose Multiplatform)

---

## Table of Contents

1. [Epic Overview](#epic-overview)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Current State Assessment](#current-state-assessment)
5. [Architecture Overview](#architecture-overview)
6. [Architecture Decisions](#architecture-decisions)
7. [Dependency Graph](#dependency-graph)
8. [Story Breakdown](#story-breakdown)
   - [Story 1: Fix Pointer Tracking — Absolute Y Coordinate](#story-1-fix-pointer-tracking)
   - [Story 2: Drop Zone Visual Feedback](#story-2-drop-zone-visual-feedback)
   - [Story 3: Ghost Overlay Positioned at Pointer](#story-3-ghost-overlay-at-pointer)
   - [Story 4: Mobile Long-Press Drag Entry](#story-4-mobile-long-press-drag)
   - [Story 5: Undo/Redo Integration & Edge Case Hardening](#story-5-undo-redo-and-edge-cases)
9. [Known Issues](#known-issues)
10. [Test Strategy](#test-strategy)
11. [Integration Checkpoints](#integration-checkpoints)
12. [Context Preparation Guide](#context-preparation-guide)
13. [Success Criteria](#success-criteria)

---

## Epic Overview

### User Value

Outliners live and die by their reorganization speed. Every user expects to grab a block's handle, drag it to a new position, and release. Without mouse-driven drag-and-drop, SteleKit forces users into keyboard-only reordering (Alt+Up/Down one step at a time), which breaks the flow of thought and is invisible to new users. This is a launch-critical parity gap with Logseq.

### Success Metrics

- A user can drag a block's handle icon, see a ghost preview follow the pointer, see a drop indicator line between target blocks, and release to reorder — all in under 300 ms of perceived latency.
- Dragging a collapsed parent moves the entire subtree intact.
- Dropping a block *onto* a block (center zone) reparents it as a child.
- Dropping between blocks (top/bottom zone) inserts it as a sibling.
- Undo (Ctrl+Z) reverses the drag-drop move in one step.
- Works on JVM desktop (mouse) and Android (finger drag after long-press selection).
- No regression in keyboard move (Alt+Up/Alt+Down) or block editing.

### Scope

**In scope:**
- Fix absolute pointer Y tracking in `BlockGutter.kt` (replace drift-prone delta accumulation)
- Correct `BlockDragGhost` position — ghost must follow the actual pointer, not a Y offset from block top
- Drop zone indicator line shown in `BlockItem` (above/below/child) during active drag
- Mobile: use `detectDragGesturesAfterLongPress` so accidental swipes don't trigger drag
- Undo/redo via existing `BlockStateManager.moveSelectedBlocks` snapshot mechanism
- Hover-reveal of drag handle (opacity: 0 by default, 1 on hover/focus)
- Single-block drag with the block's children (subtree) following automatically

**Out of scope (future):**
- Cross-page drag between two simultaneously open pages
- Drag-and-drop in the sidebar / page list
- Animated block reorder transitions (CSS/Compose spring animations)
- Touch-screen scroll-while-dragging (requires scroll-lock during drag)

### Constraints

- No new Gradle dependencies: use only `androidx.compose.foundation.gestures.*` already in scope.
- All tree mutations go through `BlockStateManager.moveSelectedBlocks` → `blockRepository.moveBlock`.
- `pointerInput` is the only cross-platform gesture API available in commonMain.
- The `blockBounds` map in `BlockList` is in the coordinate frame of the `Column`'s parent `Box`; all Y comparisons must use the same frame.

---

## Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| DD-01 | Hovering over a block's gutter reveals the drag handle icon (opacity 1) | Must |
| DD-02 | Pressing and holding the drag handle begins a drag gesture | Must |
| DD-03 | A ghost card follows the pointer throughout the drag, showing block count | Must |
| DD-04 | A 2 dp horizontal divider line appears above/below the nearest drop target block | Must |
| DD-05 | Hovering over the center 33% of a block shows a "make child" indicator (indented line) | Must |
| DD-06 | Releasing the pointer over a valid drop target commits the move | Must |
| DD-07 | The entire subtree of the dragged block moves with it | Must |
| DD-08 | Undo reverses the drag move in a single Ctrl+Z step | Must |
| DD-09 | On mobile, long-press (500 ms) on a block enters selection mode; dragging from the gutter then works | Should |
| DD-10 | Pressing Escape during a drag cancels the operation and restores original position visually | Should |
| DD-11 | Dragging a block onto itself or its own descendants is rejected (no-op with visual cue) | Must |
| DD-12 | The drag handle is only visible on the block row being hovered (desktop) or selected (mobile) | Could |

---

## Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Performance** | Ghost composable recomposition on each pointer event must be < 1 ms; use `derivedStateOf` to limit recompositions |
| **Reliability** | If the drag ends outside the block list bounds, the operation must be cancelled cleanly (no orphaned drag state) |
| **Maintainability** | Drag state machine (`BlockDragState`) must be self-contained in `BlockList.kt`; no drag state leaks into `BlockStateManager` |
| **Usability** | Drop zone visual must be clearly visible in both light and dark themes |
| **Portability** | No `jvmMain`/`androidMain` source sets touched; all changes in `commonMain` |

---

## Current State Assessment

The drag scaffolding was partially built as part of the multi-block selection feature. Here is what exists and what is broken:

### What Exists (Correctly)

| Component | File | Status |
|-----------|------|--------|
| `BlockDragGhost` composable | `ui/components/BlockDragGhost.kt` | Correct visual; position is caller's responsibility |
| `BlockDragState` data class | `ui/components/BlockList.kt:35` | Correct fields: `draggedUuids`, `pointerOffsetY`, `isDragging` |
| `blockBounds` map | `ui/components/BlockList.kt:121` | Populated via `onGloballyPositioned` |
| `computeDropTarget()` | `ui/components/BlockList.kt:125` | Correct 3-zone logic (ABOVE/CHILD/BELOW) |
| Drop dividers in `BlockItem` | `ui/components/BlockItem.kt:268,404` | `dropAbove`/`dropBelow`/`dropAsChild` parameters wired up |
| `onDragStart`/`onDrag`/`onDragEnd` chain | `BlockList` → `BlockRenderer` → `BlockItem` → `BlockGutter` | Wired end-to-end |
| `moveSelectedBlocks` + undo | `ui/state/BlockStateManager.kt:214` | Correct; called at drag end |

### What Is Broken / Incomplete

| Issue | File | Line | Problem |
|-------|------|------|---------|
| Delta drift in `BlockGutter` | `BlockGutter.kt:79` | Accumulates `deltaY` locally; fires `onMoveUp`/`onMoveDown` at 48 dp threshold instead of reporting absolute Y | 
| `pointerOffsetY` initialized to block's top Y, not pointer Y | `BlockList.kt:228` | Ghost spawns at block top, not under the user's finger |
| Ghost positioned by block-relative Y offset | `BlockList.kt:289` | `offset(y = pointerOffsetY.toDp())` — this is block-list-frame Y, which works only if the list starts at y=0 |
| `onDrag` in `BlockGutter` passes `dragAmount.y` (delta) | `BlockGutter.kt:77` | `BlockList` accumulates this delta into `pointerOffsetY`; correct only if starting value is the pointer's starting Y, which it is NOT (it's set to `blockBounds[uuid]?.first ?: 0f`, the block top) |
| No `detectDragGesturesAfterLongPress` on mobile | `BlockGutter.kt:60` | Finger swipe on Android immediately triggers drag, conflicting with scroll |
| Drag handle always visible (no hover reveal) | `BlockGutter.kt:54` | Icon always shown; Logseq hides it until hover |
| No Escape-to-cancel | `BlockList.kt` | `onKeyEvent` does not handle `Key.Escape` during active drag |

---

## Architecture Overview

```
User Gesture (pointerInput in BlockGutter)
        │
        ▼
BlockGutter.onDragStart(uuid, startPointerY: Float)   ← NEW: pass absolute start Y
BlockGutter.onDrag(deltaY: Float)                     ← unchanged
BlockGutter.onDragEnd()                               ← unchanged
        │
        ▼
BlockList (drag state machine)
  - BlockDragState.pointerY: Float   ← absolute Y in BlockList Box frame
  - blockBounds: Map<uuid, (top,bottom)>
  - computeDropTarget() → (uuid, DropZone)
        │
        ├── BlockItem.dropAbove/dropBelow/dropAsChild  ← visual dividers
        │
        └── BlockDragGhost(offset = Offset(x=0, y=pointerY))  ← ghost at pointer
        │
        ▼  (on drag end)
BlockStateManager.moveSelectedBlocks(newParentUuid, insertAfterUuid)
        │
        ▼
BlockRepository.moveBlock(uuid, newParentUuid, position)  [× N blocks]
        │
        ▼
record(undo, redo) in BlockStateManager  ← snapshot undo
        │
        ▼
GraphWriter.savePageToDisk(pageUuid)  ← persist to markdown file
```

---

## Architecture Decisions

| # | File | Decision |
|---|------|----------|
| ADR-001 | `project_plans/drag-drop-reorder/decisions/ADR-001-pointer-input-drag-no-platform-dnd.md` | Use Compose `pointerInput` / `detectDragGestures` for all platforms; no native DnD APIs |
| ADR-002 | `project_plans/drag-drop-reorder/decisions/ADR-002-absolute-pointer-y-not-delta.md` | Track absolute pointer Y (accumulated from start), not threshold-based delta ticks |
| ADR-003 | `project_plans/drag-drop-reorder/decisions/ADR-003-move-command-for-undo.md` | Reuse `moveSelectedBlocks` snapshot undo; do not add a new `MoveBlockCommand` |

---

## Dependency Graph

```
Story 1 (Fix pointer tracking)
    │
    ├─→ Story 2 (Drop zone visuals) — depends on correct pointerY for zone calculation
    │
    └─→ Story 3 (Ghost at pointer) — depends on correct pointerY for ghost offset

Story 2 ──────────────────────┐
Story 3 ──────────────────────┤─→ Story 5 (Undo/redo + edge cases)
Story 4 (Mobile long-press) ──┘

Stories 2 and 3 can proceed in parallel after Story 1.
Story 4 is independent of Stories 2 and 3 (different gesture entry point).
Story 5 integrates everything.
```

---

## Story Breakdown

---

### Story 1: Fix Pointer Tracking — Absolute Y Coordinate {#story-1-fix-pointer-tracking}

**User value**: Drag ghost and drop target track the actual pointer position rather than drifting over time.  
**Estimate**: 1 week  
**INVEST**: Independent (self-contained gesture fix), Valuable (unblocks Stories 2 & 3), Testable (unit-testable state machine)

#### Acceptance Criteria

- `BlockGutter`'s `onDragStart` callback receives the pointer's starting absolute Y coordinate in addition to the block UUID.
- `BlockList`'s `BlockDragState.pointerOffsetY` equals the pointer's current absolute Y in the `Box` coordinate frame.
- `computeDropTarget` selects the block whose bounding box center is closest to the current absolute Y.
- The threshold-based `onMoveUp`/`onMoveDown` firing during drag is removed from `BlockGutter`.

---

#### Task 1.1 — Refactor `BlockGutter` drag callbacks to pass absolute start Y [2h] {#task-1-1}

**Objective**: Change `onDragStart(uuid: String)` to `onDragStart(uuid: String, startY: Float)` and remove the `offsetY`/`dragThreshold` accumulation that fires `onMoveUp`/`onMoveDown`.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt` (~127 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` (call site, ~505 lines — focus on lines 280–297)

**Prerequisites**:
- Understanding of `detectDragGestures` API: `onDragStart(startOffset: Offset)` provides the pointer's starting position.
- `onMoveUp`/`onMoveDown` on `BlockGutter` are keyboard-driven; they must remain as parameters but must NOT be called from the drag gesture handler.

**Implementation Approach**:
1. In `BlockGutter`, change `onDragStart: (uuid: String) -> Unit` to `onDragStart: (uuid: String, startY: Float) -> Unit`.
2. In `detectDragGestures`, use `onDragStart = { startOffset -> isDragging = true; onDragStart(blockUuid, startOffset.y) }`.
3. Remove `var offsetY by remember { mutableStateOf(0f) }`, `val dragThreshold`, and the `abs(offsetY) > dragThreshold` branch from `onDrag`.
4. `onDrag` now only calls `onDrag(dragAmount.y)` and `change.consume()`.
5. Update `BlockItem.kt` call site: `onDragStart = { uuid, startY -> onDragStart(uuid, startY) }`.

**Validation Strategy**:
- Unit test: construct a `BlockGutter` test harness that records callback arguments. Simulate `onDragStart(Offset(0f, 150f))` and assert `onDragStart` was called with `startY = 150f`.
- Manual: drag a block handle slowly left-right; confirm `onMoveUp`/`onMoveDown` are NOT fired.
- Manual: drag up/down; confirm `onDrag(deltaY)` fires continuously.

**INVEST Check**:
- Independent: no other story requires this to complete first (it enables others)
- Negotiable: the `startY` parameter could instead be computed in `BlockList`, but passing it from `BlockGutter` keeps the gesture source authoritative
- Valuable: unblocks Stories 2 and 3
- Estimable: 2h — two small signature changes and one block of logic removal
- Small: single responsibility — fix gesture callback signatures
- Testable: callback argument assertions are straightforward

---

#### Task 1.2 — Fix `BlockList` drag state initialization [2h] {#task-1-2}

**Objective**: Use the `startY` from `onDragStart` (received from `BlockGutter`) as the initial `pointerOffsetY` value in `BlockDragState`, replacing the current use of `blockBounds[uuid]?.first`.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (~295 lines)
- Supporting: `BlockDragState` data class defined at line 35 of the same file

**Prerequisites**:
- Task 1.1 complete (new `onDragStart(uuid, startY)` signature available)
- Understanding that `blockBounds[uuid]?.first` is the block's *top* Y, which is close to the drag handle position but not the pointer position

**Implementation Approach**:
1. Update the `onDragStart` lambda in `BlockList` (line ~221) to accept `(uuid, startY)`.
2. Initialize `BlockDragState` with `pointerOffsetY = startY` instead of `blockBounds[uuid]?.first ?: 0f`.
3. The `onDrag { deltaY -> }` lambda already does `pointerOffsetY + deltaY`; this is now correct because start is the pointer's actual Y.
4. Confirm `computeDropTarget` uses `state.pointerOffsetY` to find the nearest block — no changes needed there.

**Validation Strategy**:
- Unit test: mock `blockBounds = mapOf("block-A" to (0f,50f), "block-B" to (50f,100f))`. Simulate `onDragStart("block-A", 25f)` then `onDrag(40f)`. Assert `pointerOffsetY == 65f` and `dropTargetUuid == "block-B"`.
- Manual: drag block A downward; confirm the drop indicator appears on block B when the pointer crosses the halfway point, not when the offset exceeds 48 dp.

**INVEST Check**:
- Independent: requires Task 1.1
- Valuable: makes drop zone computation correct
- Estimable: 2h — localized change inside one lambda block
- Small: one data initialization change
- Testable: pure state machine logic, easily unit-tested

---

### Story 2: Drop Zone Visual Feedback {#story-2-drop-zone-visual-feedback}

**User value**: Users see an unambiguous horizontal line indicator showing exactly where the block will land, with indentation matching the target level.  
**Estimate**: 1 week  
**Dependency**: Story 1 must be complete.

#### Acceptance Criteria

- A 2 dp `HorizontalDivider` in the primary theme color appears *above* the target block when the pointer is in the top 33% zone.
- A 2 dp `HorizontalDivider` appears *below* the target block when the pointer is in the bottom 33% zone.
- Both dividers are indented to `(targetBlock.level + 1) * 24 dp` when zone is CHILD, and to `targetBlock.level * 24 dp` for sibling drops.
- No divider appears when `dragState == null` or `dropTargetUuid == null`.
- The divider disappears immediately on drag end or cancel.

---

#### Task 2.1 — Fix drop zone indentation to match target block level [2h] {#task-2-1}

**Objective**: `BlockItem` already renders `dropAbove`/`dropBelow`/`dropAsChild` dividers but does not apply level-based indentation to sibling drops. Fix the `indent` calculation to use `block.level * 24 dp` for sibling drops and `(block.level + 1) * 24 dp` for child drops.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` (lines 266–411)

**Prerequisites**:
- Story 1 complete so `dropAbove`/`dropBelow`/`dropAsChild` are reliably set
- Understanding of `block.level` (0 = root, 1 = first child, etc.)

**Implementation Approach**:
1. In `BlockItem.kt` at line 267, change:
   ```kotlin
   val indent = if (dropAsChild) ((block.level + 1) * 24).dp else 0.dp
   ```
   to:
   ```kotlin
   val indent = when {
       dropAsChild -> ((block.level + 1) * 24).dp
       dropAbove || dropBelow -> (block.level * 24).dp
       else -> 0.dp
   }
   ```
2. Verify the `ABOVE` divider (line 268) and `BELOW`/CHILD divider (line 404) both use `Modifier.padding(start = indent)` — they already do.

**Validation Strategy**:
- Screenshot test (Roborazzi): drag state with `dropAbove=true` on a level-2 block shows divider at 48 dp left inset.
- Manual: drag a root block (level 0) above a level-1 block; divider aligns with the level-1 block's bullet.

**INVEST Check**:
- Small: a 3-line `when` expression change
- Testable: screenshot-diffable
- Estimable: 2h including Roborazzi baseline update

---

#### Task 2.2 — Ensure drag-cancel clears drop target visual immediately [1h] {#task-2-2}

**Objective**: When Escape is pressed or a drag ends outside the list bounds, `dropTargetUuid` and `currentDropZone` are cleared atomically so no stale divider remains.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (lines 144–165, onKeyEvent block; lines 241–295, onDragEnd block)

**Prerequisites**: Task 1.2 complete.

**Implementation Approach**:
1. In `BlockList.kt`'s `onKeyEvent`, add a branch for `Key.Escape` when `dragState != null`:
   ```kotlin
   event.key == Key.Escape && dragState != null -> {
       dragState = null
       dropTargetUuid = null
       currentDropZone = null
       true
   }
   ```
2. Verify `onDragEnd` (line 241) already sets all three to null — it does, but only after attempting the move. Add an early-exit path for cancels (when `BlockGutter.onDragCancel` fires, which calls `onDragEnd`).

**Validation Strategy**:
- Manual: start a drag, press Escape, verify no divider remains.
- Manual: start a drag, release pointer outside the `BlockList` Box; verify the drag state is cleared by `onDragCancel` → `onDragEnd`.

---

### Story 3: Ghost Overlay Positioned at Pointer {#story-3-ghost-overlay-at-pointer}

**User value**: The drag ghost card follows the user's pointer/finger, not a fixed offset from the block's original position.  
**Estimate**: 1 week  
**Dependency**: Story 1 must be complete.

#### Acceptance Criteria

- The `BlockDragGhost` Card is centered horizontally on the pointer X coordinate and has its top edge at the pointer Y coordinate.
- On mobile, the ghost is offset 24 dp above the finger to avoid occlusion.
- The ghost renders above all block content (`zIndex` or separate `Box` layer).

---

#### Task 3.1 — Hoist drag pointer position to `Offset` and position ghost correctly [2h] {#task-3-1}

**Objective**: Replace `pointerOffsetY: Float` in `BlockDragState` with `pointerOffset: Offset` (adding X tracking) and position the `BlockDragGhost` at the actual pointer coordinates.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (full file, ~295 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockDragGhost.kt` (45 lines)

**Prerequisites**: Task 1.2 complete (correct `pointerOffsetY` baseline).

**Implementation Approach**:
1. Change `BlockDragState` field `pointerOffsetY: Float` to `pointerOffset: Offset` (import `androidx.compose.ui.geometry.Offset`).
2. In `onDragStart`, set `pointerOffset = Offset(0f, startY)` (X is 0 for now; can improve with pointer X if needed).
3. In `onDrag`, update `pointerOffset += Offset(0f, deltaY)`. To track X: change `BlockGutter.onDrag` to pass `Offset(dragAmount.x, dragAmount.y)`.
4. In the ghost rendering block (lines 283–293), replace:
   ```kotlin
   .offset(y = with(density) { currentDragState.pointerOffsetY.toDp() })
   ```
   with:
   ```kotlin
   .offset(
       x = with(density) { currentDragState.pointerOffset.x.toDp() },
       y = with(density) { currentDragState.pointerOffset.y.toDp() } - 24.dp // lift above finger on touch
   )
   ```
5. Add `.zIndex(10f)` to the ghost `Modifier` to ensure it renders above block rows.

**Validation Strategy**:
- Manual: drag a block; confirm the ghost card tracks the pointer precisely.
- Manual (Android): drag a block with a finger; confirm the ghost is visible above the finger, not under it.

---

#### Task 3.2 — Add hover-reveal to drag handle icon [1h] {#task-3-2}

**Objective**: The drag handle icon in `BlockGutter` should be invisible (alpha = 0) by default and visible (alpha = 1) when the block row is hovered (desktop) or selected (mobile).

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt` (~127 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` (may need to pass `isHovered` state)

**Prerequisites**: None (independent within Story 3).

**Implementation Approach**:
1. In `BlockGutter`, track hover state using `var isHovered by remember { mutableStateOf(false) }`.
2. Apply `.onPointerEvent(PointerEventType.Enter) { isHovered = true }.onPointerEvent(PointerEventType.Exit) { isHovered = false }` to the outer Row/Box wrapping the gutter (requires `import androidx.compose.ui.input.pointer.onPointerEvent` and `PointerEventType`).
3. Apply `Modifier.graphicsLayer(alpha = if (isHovered || isDragging || isInSelectionMode) 1f else 0.15f)` to the drag handle Icon.
4. On Android, always show the handle at 0.5f alpha (hover does not apply to touch).

**Note**: `onPointerEvent` is a desktop-only API in some CMP versions. If unavailable in commonMain, use `.pointerInput(Unit) { awaitPointerEventScope { ... } }` to detect enter/exit.

**Validation Strategy**:
- Manual (desktop): hover over a block row; handle appears. Move away; handle fades.
- Manual (Android): handle is always faintly visible.

---

### Story 4: Mobile Long-Press Drag Entry {#story-4-mobile-long-press-drag}

**User value**: On Android, users can initiate a block drag without accidentally triggering it during normal scroll gestures.  
**Estimate**: 1 week  
**Dependency**: None (can develop in parallel with Stories 2 & 3).

#### Acceptance Criteria

- On mobile, a drag only begins after a long-press (500 ms hold) on the drag handle, preventing scroll interference.
- After long-press triggers, subsequent finger movement reorders the block.
- On desktop (JVM), drag begins immediately on `mouseDown` + move (no long-press required).

---

#### Task 4.1 — Use `detectDragGesturesAfterLongPress` on Android [2h] {#task-4-1}

**Objective**: Replace `detectDragGestures` with `detectDragGesturesAfterLongPress` in `BlockGutter` for the Android platform, or use a platform flag to select the gesture detector.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt` (~127 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (check for `isAndroid` or platform detection pattern)

**Prerequisites**: None.

**Implementation Approach**:
1. Check existing platform detection: search for `isAndroid` or `PlatformType` in `AppState.kt` / `PlatformUtils.kt`.
2. If a platform flag exists, pass it as a `useLongPress: Boolean` parameter to `BlockGutter`.
3. In `BlockGutter`'s `pointerInput`, branch:
   ```kotlin
   if (useLongPress) {
       detectDragGesturesAfterLongPress(onDragStart = ..., onDrag = ..., onDragEnd = ..., onDragCancel = ...)
   } else {
       detectDragGestures(onDragStart = ..., onDrag = ..., onDragEnd = ..., onDragCancel = ...)
   }
   ```
4. Both branches use the same callback signatures from Task 1.1.

**Validation Strategy**:
- Manual (Android): swipe quickly across a block row; confirm no drag triggers.
- Manual (Android): long-press the handle for 500+ ms; confirm drag begins.
- Manual (JVM): click and drag immediately; confirm drag begins without delay.

---

### Story 5: Undo/Redo Integration & Edge Case Hardening {#story-5-undo-redo-and-edge-cases}

**User value**: Users can undo an accidental drag-drop with Ctrl+Z, and the system handles edge cases (drag-to-own-subtree, empty selection, cancelled drags) without crashing or corrupting state.  
**Estimate**: 1 week  
**Dependency**: Stories 1, 2, 3 complete; Story 4 optional parallel.

#### Acceptance Criteria

- Ctrl+Z after a drag-drop move restores the original tree structure.
- Dragging a block onto one of its own descendants is rejected silently (no move, visual cancel feedback).
- If drag ends with no valid `dropTargetUuid`, the move is cancelled (no call to `moveSelectedBlocks`).
- The `BlockStateManager.selectedBlockUuids` state is cleared after every drag (success or cancel).
- Drag state is fully reset if the composable leaves composition during a drag (e.g., page navigation).

---

#### Task 5.1 — Validate against own-subtree drop and handle null drop target [2h] {#task-5-1}

**Objective**: In `BlockList.onDragEnd`, add guards: (a) reject drops where `targetUuid` is a descendant of any dragged block, and (b) no-op on null `dropTargetUuid`.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (lines 241–295)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/blocks/BlockTreeOperations.kt` (`BlockOperationValidator.validateMoveOperation`)

**Prerequisites**: Story 1 complete.

**Implementation Approach**:
1. In `onDragEnd` (line 241), before calling `onMoveSelectedBlocks`, check:
   ```kotlin
   val draggedUuids = state.draggedUuids
   val targetBlock = blocks.find { it.uuid == targetUuid } ?: return@onDragEnd
   // Build ancestor set of targetBlock to detect own-subtree drop
   val allDescendantUuids = buildDescendantSet(draggedUuids, blocks)
   if (targetUuid in allDescendantUuids) {
       // Cancel: target is inside dragged subtree
       dragState = null; dropTargetUuid = null; currentDropZone = null
       return@onDragEnd
   }
   ```
2. Add a local `buildDescendantSet(roots, blocks)` helper that BFS-traverses `blocks` using `parentUuid`.

**Validation Strategy**:
- Unit test: `buildDescendantSet(setOf("parent"), blocks)` where `blocks` contains parent → child → grandchild; assert all three UUIDs are in the result.
- Manual: drag a parent block onto one of its children; confirm no move occurs and drag state is cleared.

---

#### Task 5.2 — Verify undo round-trip for drag moves [2h] {#task-5-2}

**Objective**: Write a `businessTest` that performs a drag-equivalent `moveSelectedBlocks` call and verifies that `undo()` restores the original tree, and `redo()` re-applies the move.

**Context Boundary**:
- Primary: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/` (new test file: `DragDropUndoTest.kt`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` (lines 200–267)

**Prerequisites**: No code changes required; this is a pure test task.

**Implementation Approach**:
1. Set up `BlockStateManager` with `InMemoryBlockRepository` and 3 blocks: A (root), B (child of A), C (sibling of A).
2. Call `blockStateManager.selectBlock("C")` then `blockStateManager.moveSelectedBlocks(newParentUuid = "A", insertAfterUuid = "B")`.
3. Assert `C.parentUuid == "A"` and `C.position > B.position`.
4. Call `blockStateManager.undo()`.
5. Assert `C.parentUuid == null` (original root-level position).
6. Call `blockStateManager.redo()`.
7. Assert `C.parentUuid == "A"` again.

**Validation Strategy**:
- `./gradlew jvmTest --tests "dev.stapler.stelekit.DragDropUndoTest"` passes.

---

#### Task 5.3 — Clean up drag state on page navigation [1h] {#task-5-3}

**Objective**: Ensure that if the user navigates to a different page while a drag is in progress, the `BlockList`'s local `dragState` is reset and no move is committed.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (add `DisposableEffect`)

**Prerequisites**: None.

**Implementation Approach**:
1. Add at the top of the `BlockList` composable body:
   ```kotlin
   DisposableEffect(Unit) {
       onDispose {
           // If composition leaves during an active drag, reset without committing
           dragState = null
           dropTargetUuid = null
           currentDropZone = null
       }
   }
   ```
   Note: `dragState` etc. are `remember` vars; they are owned by this composition instance, so `onDispose` fires when the `BlockList` leaves composition (e.g., page change).

**Validation Strategy**:
- Manual: start a drag, then navigate to a different page via sidebar; confirm no crash and no move is committed.

---

## Known Issues

### Bug 001: Ghost rendered behind block rows [SEVERITY: Medium]

**Description**: `BlockDragGhost` is rendered inside the same `Column` as block rows, so its `zIndex` competes with sibling composables. Without explicit `zIndex`, the ghost may render below some blocks visually.

**Mitigation**:
- Use a separate `Box` layer for the ghost (it is already rendered after the `Column` in the outer `Box`, which should place it on top in z-order).
- Add `.zIndex(10f)` to the ghost Modifier to guarantee ordering.

**Files Likely Affected**: `ui/components/BlockList.kt` (lines 283–293), `ui/components/BlockDragGhost.kt`

**Prevention**: Task 3.1 addresses this explicitly.

---

### Bug 002: `blockBounds` stale during scroll [SEVERITY: Medium]

**Description**: `onGloballyPositioned` updates `blockBounds` when each block is laid out, but does not re-fire during scroll. If the `LazyColumn` (in `PageView`) scrolls while a drag is in progress, the bounds map will be stale and `computeDropTarget` will return incorrect results.

**Note**: `BlockList` uses a regular `Column` (not `LazyColumn`) internally — `PageView` wraps it in `LazyColumn`. The bounds are in the `Column`'s parent frame, which does not shift on scroll because `BlockList` is a single item in `LazyColumn`.

**Mitigation**: Disable scroll on the `LazyColumn` in `PageView` while a drag is in progress. Pass `dragInProgress: Boolean` from `BlockList` up via a callback.

**Files Likely Affected**: `ui/screens/PageView.kt`, `ui/components/BlockList.kt`

**Prevention**: Addressed as a follow-up if scroll-during-drag is observed in testing.

---

### Bug 003: Multi-block drag order preservation [SEVERITY: High]

**Description**: When multiple blocks are selected and dragged, `moveSelectedBlocks` moves them in visual order (`sortedSelected`). If any selected block has a selected descendant, `subtreeDedup` removes the descendant from the move list. However, if the user drags the *descendant's* handle (not the ancestor's), `onAutoSelectForDrag` will select only that descendant, not the ancestor. The ancestor will not move.

**Mitigation**: `onAutoSelectForDrag` in `BlockList` (line 222) should promote the selection to include the full subtree root. When a drag starts on UUID X: if X is already selected, drag the whole selection; if X is not selected, auto-select X and drag only X (current behavior). This is correct — the issue only occurs if a user manually selects an ancestor AND a descendant, then drags the descendant.

**Files Likely Affected**: `ui/components/BlockList.kt` (lines 221–231), `ui/state/BlockStateManager.kt`

**Prevention**: Task 5.1 adds descendant validation; Task 5.2 tests the round-trip.

---

### Bug 004: Delta drift for fast drags [SEVERITY: Low — Fixed by Story 1]

**Description**: The current `BlockGutter` accumulates `deltaY` with a threshold, causing the ghost and drop zone to "lag" behind the pointer during fast directional changes. This is the root cause addressed in Story 1 / Tasks 1.1 and 1.2.

**Files Likely Affected**: `ui/components/BlockGutter.kt` (lines 41–88), `ui/components/BlockList.kt` (lines 221–240)

**Prevention**: ADR-002 mandates absolute Y tracking.

---

## Test Strategy

### Unit Tests (businessTest)

| Test | Location | Validates |
|------|----------|-----------|
| `DragDropUndoTest.undoRestoresTree` | `businessTest/DragDropUndoTest.kt` | Undo after `moveSelectedBlocks` |
| `DragDropUndoTest.redoReappliesMove` | `businessTest/DragDropUndoTest.kt` | Redo after undo |
| `BlockListDropZoneTest.computeDropTarget_above` | `jvmTest/BlockListDropZoneTest.kt` | ABOVE zone when pointer in top 33% |
| `BlockListDropZoneTest.computeDropTarget_child` | `jvmTest/BlockListDropZoneTest.kt` | CHILD zone when pointer in center 33% |
| `BlockListDropZoneTest.computeDropTarget_below` | `jvmTest/BlockListDropZoneTest.kt` | BELOW zone when pointer in bottom 33% |
| `BlockListDropZoneTest.ownSubtreeDrop_rejected` | `jvmTest/BlockListDropZoneTest.kt` | Self-drop rejected |
| `buildDescendantSet_includesGrandchildren` | `jvmTest/BlockListDropZoneTest.kt` | BFS traversal correctness |

### Screenshot Tests (Roborazzi, jvmTest)

| Test | Validates |
|------|-----------|
| `BlockItemDropAboveTest` | `dropAbove=true` renders 2dp divider at correct indent |
| `BlockItemDropBelowTest` | `dropBelow=true` renders divider below block |
| `BlockItemDropAsChildTest` | `dropAsChild=true` renders indented divider |
| `BlockDragGhostTest` | Ghost renders at correct position with count label |

### Manual Verification Checklist

- [ ] Drag root block to become child of another root block
- [ ] Drag child block to root level (outdent via drag)
- [ ] Drag block with 3 levels of children; subtree follows
- [ ] Drag onto own child: operation cancelled
- [ ] Undo drag move: tree restored
- [ ] Redo after undo: move re-applied
- [ ] Escape during drag: no move committed
- [ ] Navigate away during drag: no crash, no move committed
- [ ] Android: swipe does not trigger drag; long-press does

---

## Integration Checkpoints

**After Story 1**: The drag state machine has correct pointer Y values. Drop zone detection (logged or debugged) selects the correct block for all drag speeds. Ghost is positioned correctly relative to pointer. No regressions in keyboard move (Alt+Up/Down).

**After Stories 2 + 3**: Full visual feedback loop works. User can perform an end-to-end drag-and-drop and see the ghost, the divider, and the committed result. Undo reverses the move.

**After Story 4**: Android users can drag blocks without triggering spurious drags on scroll.

**After Story 5 (Final)**: All edge cases covered by tests. Undo/redo round-trip verified. The feature is production-ready.

---

## Context Preparation Guide

### Task 1.1 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt` — full file (~127 lines)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` — lines 280–300 (drag callback call site)
- Compose API: `detectDragGestures(onDragStart: (Offset) -> Unit, onDrag: (PointerInputChange, Offset) -> Unit, ...)`

### Task 1.2 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` — lines 35–145 (state declarations + `computeDropTarget`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` — lines 220–295 (drag callbacks + ghost rendering)

### Task 2.1 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` — lines 256–415

### Task 3.1 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` — full file
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockDragGhost.kt` — full file (45 lines)

### Task 4.1 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt` — full file
- Search for `isAndroid` or `PlatformType` in `AppState.kt` and `PlatformUtils.kt`

### Task 5.1 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` — lines 241–295
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/blocks/BlockTreeOperations.kt` — `BlockOperationValidator` (lines 445–521)

### Task 5.2 — Context to load
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` — lines 200–267
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/` — any existing test for setup pattern

---

## Success Criteria

- [ ] All atomic tasks (1.1, 1.2, 2.1, 2.2, 3.1, 3.2, 4.1, 5.1, 5.2, 5.3) completed and self-validated
- [ ] All acceptance criteria per story met
- [ ] `DragDropUndoTest` passes in `businessTest`
- [ ] `BlockListDropZoneTest` passes in `jvmTest`
- [ ] Roborazzi screenshot baselines updated for drop zone dividers
- [ ] Manual verification checklist above is fully checked
- [ ] No regressions in existing `jvmTest` suite (`./gradlew jvmTest` green)
- [ ] Code review approved (check: no platform-specific code in `commonMain`, no new Gradle deps, drag state not leaked into `BlockStateManager`)
