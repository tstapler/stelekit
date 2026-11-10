# ADR-008: Sketch editor: pure scene model + single pointer state machine + shared PlacementController + snapshot undo + feature flag

Status: ACCEPTED (amended by plan review patches 1 and 2) | Date: 2026-11-09 | Epic: E3

## Decision
- `SketchScene` (immutable list of primitives in sixteenths) and `ViewTransform(scale, offset)` are pure commonMain under `sketch/`, tested in commonTest (registered for Bazel per rule R-REG). Compose code and the `SketchViewModel` live under `ui/sketch/` next to the other screens.
- One `Modifier.pointerInput` with a tool state machine `Idle -> Placing -> Dragging`; `detectTransformGestures` only for two-pointer pan/zoom; tap versus drag by `touchSlop` (pitfall 12).
- Undo = bounded stack (N=100) of persistent snapshots; a drag coalesces to one entry on pointer-up; constraints re-solve per committed command only.
- Rendering: cached `Path` per primitive keyed on geometry version, cached `TextLayout` via `rememberTextMeasurer`, in-progress shape read only in the draw lambda, grid as one `drawPoints` skipped under a zoom threshold (pitfall 7).
- Controllers in `remember { }` own their scope (CLAUDE.md rule).
- Non-drag mode: Story 3.0 BUILDS a pure, Compose-free `PlacementController<P>` and `NudgeStepper` in `ui/placement/`, new code with the sketch as its first consumer. It is NOT an extraction: `AnnotationEditorViewModel` (1114 lines) holds only `pendingLabelPoint`/`pendingGridRefPoints` and no nudge, step or crosshair state (grep of `nudge|placement|crosshair` is empty), and C.8 is unshipped. The shared contract test starts with the sketch consumer; Story 6.5a later makes the annotate editor a second consumer. The loupe is reused only if 6.3 made it coordinate-agnostic; otherwise v1 has no sketch loupe.
- Feature flag `homeSketch`, a `Settings` boolean default off, registered in Story 5.0 and enforced in `ScreenRouter`/`AppState`; graduation needs a Pixel_Fold device pass and owner UX review.
- Compose gesture tests live in `androidUnitTest` (Robolectric); jvmTest only for pixel tests.

## Consequences
Story 3.6 (accessibility) depends on Story 3.0, not on the carry-over lane, so the sketch is off the C-lane critical path; 6.5a depends on 3.0 instead of the reverse.
