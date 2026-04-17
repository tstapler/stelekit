# ADR-002: Track absolute pointer Y position, not accumulated delta Y

**Date**: 2026-04-16  
**Status**: Accepted  
**Feature**: Drag-and-Drop Block Reordering

## Context

The current `BlockGutter.kt` implementation accumulates `deltaY` into an `offsetY` local state and fires `onMoveUp`/`onMoveDown` whenever the accumulated offset exceeds a 48 dp threshold. `BlockList.kt` similarly accumulates delta into `pointerOffsetY`. This threshold-based approach was a quick approximation used before proper drop zone detection existed.

The problem is that accumulated deltas drift: if the user drags slowly in one direction and quickly in the other, the accumulated value diverges from the actual pointer position. Drop zone detection (which block is the target) needs to know where the pointer *is*, not how far it has moved from an arbitrary origin.

## Decision

Replace the delta-accumulation pattern with an **absolute pointer Y coordinate** tracked from drag start. On `onDragStart`, record the initial pointer Y (from the `Offset` parameter of `detectDragGestures`). On each `onDrag`, add `dragAmount.y` to arrive at the current absolute Y *within the `BlockList` coordinate frame* (using `LocalDensity` and `onGloballyPositioned` to convert).

The `blockBounds` map already records `(topY, bottomY)` for each block in parent coordinates. Comparing `pointerY` against these ranges gives a correct drop target at all pointer speeds.

## Rationale

- Absolute position is deterministic and cannot drift.
- The `blockBounds` map is already in the same coordinate frame (parent of `Column` inside `BlockList`).
- Eliminates the 48 dp "tick" heuristic that would fire spurious `onMoveUp`/`onMoveDown` during slow drags.

## Consequences

- **Positive**: Drop target is always the visually correct block, regardless of drag speed.
- **Negative**: Requires converting the `dragAmount` accumulation into an absolute-offset accumulation from a known starting point. The starting Y must be captured from the initial pointer event, which `detectDragGestures`'s `onDragStart(startOffset: Offset)` provides directly.
- **Migration**: Remove the `offsetY`/`dragThreshold` logic from `BlockGutter.kt`. Remove the `onMoveUp`/`onMoveDown` callbacks from the drag path (they remain for keyboard use only).

## Patterns Applied

- **Value Object**: `DragPointerState(startY: Float, currentY: Float)` encapsulates the tracking.
