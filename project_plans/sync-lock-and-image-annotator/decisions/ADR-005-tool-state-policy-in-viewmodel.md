# ADR-005: Tool-state policy is a pure function in the ViewModel layer

**Status**: Accepted pending owner review (Phase 4 patch, 2026-11-14; was Proposed)
**Date**: 2026-11-14
**Project**: sync-lock-and-image-annotator (Epic 2)

## Context

`AnnotationEditorViewModel.commitAnnotation` is the single success point for DISTANCE, AREA, ANGLE and LABEL; tools never return to Select today and a fresh editor opens in `DISTANCE` (`AnnotationEditorViewModel.kt:108`). `AnnotationGestureLayer` uses `pointerInput(currentTool)`, so a tool change restarts the pointer coroutine and must not happen mid-gesture (`research/stack.md` 2.2). The 1510-line ViewModel is a known God class; the plan isolates new logic behind pure collaborators (`research/architecture.md` B.4). `AnnotationEditorState` is the `UndoHistory` payload, and `restoredOver` (`ui/annotate/UndoHistory.kt:43-53`) keeps `currentTool` from the live state while restoring everything else from the snapshot.

## Decision

1. `ToolStickiness { ONE_SHOT, LOCKED }` in `AnnotationEditorState` and a pure `nextToolAfterCommit(tool, stickiness, autoReturnEnabled): AnnotationTool` (and `nextToolAfterRollback`) in a new `ToolPolicy.kt`. The ViewModel calls it once, after the optimistic append in `commitAnnotation`, never from the gesture layer or toolbar.
2. Default one-shot with auto-return to Select; Settings `annotator_auto_return_to_select` (default true) and `annotator_sticky_tools` (default false, starts tools locked), both with a Settings row (Task 2.2c) because the toggle is the rollback for the default flip. Fresh editor opens in Select. Lock resets to one-shot on leaving the editor.
3. **Lock affordance (replaces "lock icon on the active chip")**: a separate 48 dp **Keep tool** toggle in the action row, visible while a placement tool is armed, with long-press or double-tap on a tool chip as shortcuts. A tap on the already-active chip means disarm (back to Select), so a nested 48 dp lock target on the chip would overlap it. Desktop: a keyboard shortcut toggles Keep tool (Task 2.6c).
4. **`stickiness` is not part of the undoable state.** `restoredOver` copies `stickiness` (and `pendingKnownDistance`) from the live state exactly as it does `currentTool`, so undo never silently drops a lock the user just set; `UndoHistory.kt` is in the Story 2.2 file list and the undo AC asserts it.
5. Multi-step sessions (GRID_REF ruler, plane, AREA) return to Select only on their own confirm. A commit selects the new measurement (so its value is editable) and pushes no extra undo entry: one undo entry per measurement; redo never re-arms a tool.
6. **Gesture semantics are decided in ADR-007**, not here: the gesture layer emits `AimPlace(point)` or `DragMeasure(start, end)` from a pure classifier and carries no tool policy; a tool change always happens after the gesture's `awaitEachGesture` iteration ends. Tap-tap placement and the existing crosshair nudge pad remain first-class alternatives to dragging.
7. Disarm paths (Story 2.3; Back order, triad repair 2: with a tool armed the first Back disarms and does not leave the editor, a second Back in Select leaves through the existing leave check, `EditorBackPolicy`, ux.md 3.5): tapping the active chip, Select and Back/Esc disarm and discard a pending first point (triad repair 1, OC-9: an off-canvas drag is **no longer** a disarm path; every gesture cancel keeps the tool armed, ADR-007 decision 4). **A second finger is not a disarm path (Phase 4 patch, consistency BLOCKER; ADR-007 decision 4 governs):** it cancels only the in-flight placement (the press that is down), hands over to pinch/pan, keeps any already-placed first point, and the tool stays armed.
8. **Terminology (consistency CONCERN 2).** One concept, three spellings: the UI label is **"Keep tool"**; the state is `ToolStickiness.LOCKED` (default `ONE_SHOT`); the Settings key is `annotator_sticky_tools` (default false, "Keep tools active" row). Prose elsewhere in the plan says "Keep tool" for the control and `LOCKED` for the state; "tool lock" and "sticky" survive only inside the key name and requirements.md quotations.

## Consequences

- Existing tests that assert a `DISTANCE` default or rely on it must change. The real set (found by grep this pass, the audit in Task 2.1a must re-run it): `AnnotationEditorCrashGuardTest.kt:147` and `UndoHistoryTest.kt:76` (assert DISTANCE after commit/undo), `AnnotationGeometryTest`, `MeasurementInjectionTest`, `AnnotationEditorScreenKeyboardTest` (call `addPoint` without `selectTool`), `AnnotationToolbarShortcutTest`, and every test that does one `selectTool(DISTANCE)` and then commits more than one measurement (for example `AnnotationEditorUnitTest.kt:84-191`, `AnnotationEditorHeightOffsetTest`).
- Policy is unit-testable without Compose.

## Alternatives Considered

- **Sticky tools with a Done button**: forces an extra tap per measurement; contradicts the owner's flow. Rejected.
- **Policy inside the gesture layer or toolbar**: untestable without Compose, duplicates per entry point. Rejected.
- **Hold the one-shot flag in `remember`**: lost on rotation; rejected, state lives in the ViewModel.
- **Lock icon nested inside the active chip**: overlapping targets. Rejected (decision 3).
- **Stickiness restored by undo**: surprising. Rejected (decision 4).
