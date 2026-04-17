# ADR-001: Use Compose pointerInput for drag; do not use platform DnD APIs

**Date**: 2026-04-16  
**Status**: Accepted  
**Feature**: Drag-and-Drop Block Reordering

## Context

Compose Multiplatform (CMP) does not provide a built-in drag-and-drop API for arbitrary list reordering that works across Desktop (JVM), Android, and iOS from a single code path. Each platform has native DnD APIs (Java AWT DnD, Android View DnD, UIKit drag) but they are inaccessible from common Compose code without substantial `expect/actual` plumbing.

The project's existing code in `BlockGutter.kt` already uses `detectDragGestures` from `foundation.gestures`, and `BlockList.kt` already maintains a `BlockDragState` with pointer tracking. This gives a clear precedent.

## Decision

Implement the entire drag interaction using `androidx.compose.foundation.gestures.detectDragGestures` inside `pointerInput` modifiers. A floating `BlockDragGhost` composable tracks pointer position via an `Offset` state hoisted into `BlockList`. Drop zones are computed by comparing the live pointer Y-coordinate against a `Map<String, Pair<Float, Float>>` of block bounds collected via `onGloballyPositioned`.

No platform-native DnD APIs are used. No new Gradle dependencies are introduced.

## Rationale

- **Single code path**: commonMain handles 100% of the logic; no expect/actual required.
- **Consistent UX**: gesture thresholds and visual feedback are identical on all platforms.
- **Precedent already exists**: `BlockGutter.kt` uses `detectDragGestures`; `BlockList.kt` already scaffolds the drag state machine. The decision to NOT use platform DnD was implicitly made by the previous author.
- **No dependency risk**: no new Gradle artifacts needed.

## Consequences

- **Positive**: No need to reconcile platform-specific accessibility DnD APIs.
- **Negative**: Pointer position tracking via `onGloballyPositioned` is coarse — bounds are stale if the list scrolls during a drag. Mitigation: disable scroll-while-dragging or recompute bounds on each scroll event.
- **Negative**: Long-press threshold for mobile must be detected manually (500 ms delay before drag begins) since `detectDragGestures` does not have a built-in long-press guard. Use `detectDragGesturesAfterLongPress` on mobile.

## Patterns Applied

- **Strategy Pattern**: `DragGestureStrategy` (mouse vs long-press) selected per platform.
- **Observer Pattern**: `BlockList` observes `blockBounds` map mutations to recompute drop target.
