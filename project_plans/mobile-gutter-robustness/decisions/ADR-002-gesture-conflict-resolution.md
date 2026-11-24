# ADR-002: Gesture Conflict Resolution via Mode-Conditional Modifier Composition

**Date**: 2026-11-23  
**Status**: Accepted  
**Deciders**: Implementation planning pass

---

## Context

The drag handle on mobile must support two gestures:
- **Tap** (view mode, mobile): opens the quick-action sheet.
- **Long-press + drag** (all modes, mobile): starts drag-to-reorder.

These two gestures need to be disambiguated without interference. Three approaches were considered:

1. **`combinedClickable` + `dragGesture` stacked**: use `Modifier.combinedClickable(onClick = { showSheet = true }, onLongClick = null)` followed by `.dragGesture(...)`.
2. **Mode-conditional modifier composition**: in view mode, attach a `Modifier.clickable { showSheet = true }`; in drag mode (long-press entered), remove the clickable and attach only `dragGesture`.
3. **Single `pointerInput` with `awaitFirstDown` + timeout-based disambiguation**: a custom `pointerInput` that waits for the first down event, then branches on whether a long-press or a tap occurs within a timeout.

## Decision

**Option 2 — mode-conditional modifier composition**.

Concretely:
- The drag-handle outer `Box` receives a `clickable` modifier when `onTapDragHandle != null` (view mode, mobile only), placed *before* `dragGesture` in the modifier chain.
- When a long-press begins, the `dragGesture` recognizer (using `detectDragGesturesAfterLongPress`) fires its `onDragStart` callback. At that point, `isDragging = true` is set in state, which the `clickable`'s gesture recognizer will not fire because the long-press already consumed the pointer event.
- In edit mode, `onTapDragHandle` is passed as `null`, so the `clickable` modifier is absent entirely.

## Rationale

**Against Option 1 (`combinedClickable` + stacking)**: `combinedClickable` registers its own `pointerInput` node. The order of pointer event dispatch in Compose's `PointerEventPass` pipeline means that if `combinedClickable` is placed before `dragGesture`, the tap fires before the drag can claim the event — but if placed after, the drag consumes the down event and `combinedClickable` never fires. This creates a fragile ordering dependency that is hard to verify statically and can silently break when unrelated modifier changes are made. The pitfalls research rated this HIGH risk.

**Against Option 3 (custom `pointerInput` with timeout)**: This requires re-implementing gesture disambiguation logic that `detectDragGesturesAfterLongPress` already implements correctly and battle-tests. Custom timing logic is a source of platform-specific timing bugs (the existing `useLongPressForDrag()` platform split already exists because platform timing norms differ). It also makes the code harder to read.

**For Option 2 (mode-conditional composition)**: This is the simplest model: view mode and drag mode are mutually exclusive states in the application. When the block is not being dragged, the modifier chain includes `clickable`. When a long-press starts (which is the only way to begin a drag on mobile), `isDragging` becomes true and the `clickable` modifier's key changes on the next recomposition, effectively resetting the gesture state. The two recognizers never contend for the same event stream.

An important invariant: `detectDragGesturesAfterLongPress` requires a *held* pointer for the long-press threshold before entering drag. A short tap (down + up < long-press threshold) is not consumed by the drag recognizer, so the `clickable`'s `onClick` fires normally.

## Implementation Note

The `clickable` modifier is added to the outer `Box` of the drag handle, keyed on `isEditing` and `isMobile`, so the pointer input scope is re-created when the mode changes. The `dragGesture` modifier remains keyed on `useLongPress` as before. This ensures no stale-closure bugs.

## Consequences

- Two distinct modifier chains exist for the drag handle: view-mode (clickable + dragGesture) and edit-mode (dragGesture only). Both are testable independently.
- If a future requirement needs a triple-gesture disambiguation (tap vs. long-press vs. double-tap), this approach still scales: each gesture gets its own conditional modifier, and the mode flag determines which are active.
- The added `clickable` on the bullet dot (secondary path to the same sheet) uses the same conditional pattern.
