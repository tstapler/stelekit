# Mobile sketch editor survey (owner feedback, 2026-11)

Trigger: the owner reported the sketch editor is hard to use on a phone, tap-tap "crosshair" placement is unintuitive, and the Crosshair button overlaps the bottom nav / mic button.

Confidence: VERIFIED = read in the linked source this session. UNVERIFIED = common practice from general knowledge; a search did not confirm it (several searches returned nothing on gestures, handle sizes or loupes). Treat UNVERIFIED rows as design heuristics, not citations.

## Findings by topic

| Topic | What mature apps do | Confidence | Source |
|---|---|---|---|
| Drawing model | Press-drag-release draws a stroke or shape; the live measure shows while drawing. magicplan's freehand "Define corners" is tap-per-corner but is followed by drag-to-adjust and typed dimensions. | VERIFIED (Concepts live labels; magicplan) | [Concepts precision tools](https://concepts.app/manual/precision-tools), [magicplan define corners](https://help.magicplan.app/create-a-room-with-the-define-corners-feature) |
| Snapping | Two modes: snapping while drawing, and snapping while editing a selection. Snap targets: line endpoints, corners, centre, grid lines; "auto-complete" shows target circles at endpoints. | VERIFIED | [Concepts](https://concepts.app/manual/precision-tools) |
| Angle snap | 45 degree steps by default, with a toggle to turn it off (Measure Mobile); Concepts shows a target every 45 degrees. | VERIFIED | [Measure Mobile](https://support.measureflooring.com/hc/en-us/articles/360050777973), [Concepts](https://concepts.app/manual/precision-tools) |
| Typed dimensions | Tap the live length label / status bar to type width, height, length; chain toggle locks aspect; magicplan lets you drag walls then edit the measurement. | VERIFIED | [Concepts](https://concepts.app/manual/precision-tools), [magicplan change dimensions](https://help.magicplan.app/change-dimensions-of-your-floor-plan) |
| Edit by moving corners | magicplan makes angled walls by moving corner points; Concepts edits a selected stroke with snapping still active. | VERIFIED | [magicplan angled walls](https://help.magicplan.app/how-to-create-angled-walls), [Concepts](https://concepts.app/manual/precision-tools) |
| Two-finger pan/zoom | tldraw tracks two touches for pinch zoom and defers any second-touch modifier until drawing has begun to avoid clashing with pinch. Standard convention: one finger acts on content, two fingers navigate. | VERIFIED (tldraw), convention UNVERIFIED | [tldraw touchy subject](https://tldraw.dev/blog/a-touchy-subject), [tldraw gestures](https://cdn.jsdelivr.net/npm/@tldraw/editor@4.5.9/src/lib/hooks/useGestureEvents.ts) |
| Palm rejection / stylus | Once a stylus touches, the OS may stop sending finger events; tldraw notes cancelled pointers (edge swipe, palm rejection) must clear stuck gestures. | VERIFIED (tldraw) | [tldraw touchy subject](https://tldraw.dev/blog/a-touchy-subject) |
| Selection handles | Visible round handles on the selection, touch area larger than the dot (about 48 dp, Material guidance); body drag moves the whole shape; contextual delete. | UNVERIFIED | none found |
| Loupe / finger occlusion | Draw the readout offset above the finger; a magnifier loupe near the touch for precision. | UNVERIFIED | none found |
| Long-press / context menu | Long press selects or opens a context menu (copy, delete). | UNVERIFIED | none found |
| Undo | Always-visible undo/redo; Shapr3D and Concepts keep them on the primary toolbar. | UNVERIFIED | [Shapr3D](https://apps.apple.com/TR/app/id1091675654) |

## Conclusion

The common mobile pattern is: one finger acts on content (drag to draw, drag handles to edit), two fingers pan/zoom, snap targets are visible while dragging, a live length readout is shown, and exact lengths are typed into a field attached to the shape. SteleKit does the opposite for the first point: one finger pans, and placement is tap-tap or a nudge pad, with no handles and no snap feedback.

## Gaps in the current editor, mapped to code

| # | Gap | Code | Priority |
|---|---|---|---|
| 1 | One-finger drag pans; no drag-to-draw | `ui/sketch/SketchCanvas.kt` gesture loop | P0 |
| 2 | No visible handles; no drag-to-move vertex or shape | `SketchCanvas.kt`; `MoveVertex` exists in `sketch/SketchCommands.kt`, no whole-shape move | P0 |
| 3 | No snap indicator or live length | `sketch/SnapEngine.kt` already returns `SnapKind` but the canvas discards it | P0 |
| 4 | Crosshair chip and nudge pad at BottomCenter collide with the docked mic button | `SketchNonDragMode.kt` | P0 |
| 5 | Delete and Redo scroll off-screen in the toolbar | `SketchScreen.kt` | P0 |
| 6 | Typed length works only for axis-aligned lines, and only if the user selects the line first; no rectangle width/height | `SetLineLength`, `DimensionField.kt` | P1 |
| 7 | Second finger landing mid-draw | gesture loop | P1 |
| 8 | Loupe / offset cursor, stylus palm rejection, long-press menu, angle-snap step setting | not present | deferred |
