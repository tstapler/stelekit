# Feature landscape: Excalidraw baseline + FreeCAD Sketcher constraints

Date: 2026-11-14. Phase 2 research, question: feature inventory with v1 / later / out triage.

Confidence labels: VERIFIED = fetched from a primary source this session (link given); INFERRED = from general knowledge of the products or from reading the code, not re-checked; GAP = could not be fetched.
Fetch caveats: wiki.freecad.org returned an Anubis access-denied page (GAP for wiki text); the Excalidraw docs page for the file schema is thin. The element-type list below was summarised by a fetch-side model, so treat exact names (e.g. `stickynote`, `magicframe`) as VERIFIED-with-caveat and re-check against the source before coding the importer.

## 0. What SteleKit already has (VERIFIED by reading code)

Under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/{sketch,ui/sketch}`:
- Model: `SketchPrimitive` sealed interface with only `Line(a,b)` and `Rect(a,b)` (axis-aligned), each with a `layer: String` name; coordinates are `Inches` in integer sixteenths (`sketch/SketchScene.kt`). `SketchPoint`, `SketchSegment`, `Aabb`. Scene capacity limit (`SceneError.CapacityExceeded`).
- Commands with undo (`sketch/SketchCommands.kt`, `SketchHistory.kt`): Add/RemovePrimitive, MoveVertex, MoveShape, SetLineLength, SetRectSize, zone add/text/total/remove, layer add/visible/locked/set-primitive-layer, sheet kind, view direction, scale.
- Snap (`sketch/SnapEngine.kt`): ENDPOINT, MIDPOINT, INTERSECTION, GRID, AXIS_LOCK; `hitTest` via AABB tolerance box (candidate counts suggest an index or filter; not verified).
- Constraints: `ZoneConstraints.kt` only (zones with sum/total/equal-style dimension checks). No geometric constraints.
- Tools: `SketchTool { LINE, RECTANGLE, ZONE_SELECT }` (`ui/sketch/SketchViewModel.kt`).
- UI: `SketchCanvas`, `SketchGestures`, `SketchDrag`, `SketchNonDragMode` (non-drag placement), `SketchSemantics` (a11y), `LayerPanel`, `DimensionField`, SVG/CSV export (`sketch/export/`), journal preview/embed, attach-sheets, project list.
- Persistence: `model/SketchModels.kt`, `repository/SqlDelightSketchRepositories.kt`, `projectbridge/ProjectModelJson.kt`, `SidecarReconciler.kt` all reference `SketchPrimitive`, so the schema change touches the fusion path.

Implication (INFERRED): the existing model has no curves, no rotation, no text, no style, no binding, no groups/frames, no z-order beyond layer name. A new element model is needed; `Line`/`Rect` map to a legacy projection.

## 1. Excalidraw element types

Source: [excalidraw element types.ts](https://raw.githubusercontent.com/excalidraw/excalidraw/master/packages/element/src/types.ts) (VERIFIED, summarised).

| Element | Notes | Triage | Reason |
|---|---|---|---|
| rectangle | base fields only; roundness round/sharp | v1 | core; existing Rect maps here |
| diamond | same | v1 | core diagram shape |
| ellipse | same | v1 | core; also circle geometry for constraints |
| line | `points[]`, optional `polygon` flag, arrowheads null | v1 | existing Line maps; polyline/polygon |
| arrow | linear + `startBinding`/`endBinding`, `startArrowhead`/`endArrowhead`, `elbowed` flag | v1 (straight + curved); elbow = later | binding is the headline diagram feature; elbow routing is a rabbit hole |
| text | `text`, `originalText`, `fontSize`, `fontFamily`, `textAlign`, `verticalAlign`, `containerId`, `lineHeight`, `autoResize` | v1 | labels in shapes/arrows required for success metric 2 |
| freedraw | `points`, `pressures`, `simulatePressure` | v1 (no pressure curve tuning) | in tool parity list; smoothing = later |
| image | `fileId`, `status`, `scale`, `crop` | v1 (place + resize); crop = later | needs asset store, file map on import |
| frame | `name`; children via `frameId` | v1 | in requirements (success metric 2) |
| selection | transient marquee | n/a | UI state, not stored |
| embeddable / iframe | URL embed | out | untrusted content, no network (security NFR) |
| magicframe | AI generation | out | AI text-to-diagram is out of scope |
| stickynote (appears in current types) | `baseHeight` | later | new, rare; INFERRED low usage |

Base fields on every element (VERIFIED): `id, x, y, width, height, angle, strokeColor, backgroundColor, fillStyle, strokeWidth, strokeStyle, roundness, roughness, opacity, seed, version, versionNonce, index, updated, created, isDeleted, groupIds, frameId, boundElements, link, locked, customData`.
Triage: keep all except collaboration-only `version`/`versionNonce`/`updated` (retain on import for round-trip, regenerate on export); `seed` needed only if rough style is adopted (Open Question 3); `index` (fractional z-order string) adopted as the z-order key (INFERRED: gives stable merge-friendly order, helps git-friendly diffs); `link` later; `customData` preserved opaquely on round-trip.

Style values (VERIFIED):
- `fillStyle`: hachure, cross-hatch, solid, zigzag. v1 = solid + none (transparent); hachure/cross-hatch/zigzag = later (they are rough.js outputs; only meaningful if rough style opted in; import must still preserve and fall back to solid).
- `strokeStyle`: solid, dashed, dotted. v1.
- `roundness`: null or `{type, value}`. v1 as sharp/round toggle.
- `roughness` (0/1/2): v1 field retained; renderer behaviour depends on Open Question 3 (default recommendation: clean render, store the value for round-trip).
- Arrowheads (VERIFIED list): arrow, bar, circle, circle_outline, triangle, triangle_outline, diamond, diamond_outline; cardinality_* (one, many, one_or_many, exactly_one, zero_or_one, zero_or_many); legacy dot, crowfoot_*. v1: none, arrow, triangle, triangle_outline, circle, circle_outline, diamond, diamond_outline, bar. Cardinality (ER diagram notation) = later; import maps unknown heads to `arrow`.
- Elbow arrow extras (VERIFIED): `fixedSegments {start,end,index}`, `startIsSpecial`, `endIsSpecial`; binding `FixedPointBinding {elementId, fixedPoint [0..1,0..1], mode: inside|orbit|skip}`. Note: current binding uses `fixedPoint` + `mode`, older files use `{elementId, focus, gap}` (INFERRED from Excalidraw history; importer must accept both).

## 2. Tools (INFERRED from Excalidraw UI; toolbar contents also on [excalidraw.com](https://excalidraw.com))

| Tool (shortcut) | Triage | Reason |
|---|---|---|
| Selection (V / 1) with marquee, shift-add, alt-drag duplicate, rotate handle, resize handles, multi-select transform | v1 | foundation |
| Hand / pan (H, space-drag, middle mouse, two-finger) + zoom (wheel, pinch, +/-, fit-to-content shift+1, selection shift+2) | v1 | exists partly (`ViewTransform`) |
| Rectangle (R/2), Diamond (D/3), Ellipse (O/4) | v1 | |
| Arrow (A/5), Line (L/6) | v1 | |
| Freedraw (P/7) | v1 | |
| Text (T/8, double-click to edit, double-click shape to label) | v1 | |
| Image (9) | v1 | |
| Eraser (E/0) | v1 | in requirements metric 1 |
| Frame (F) | v1 | |
| Laser pointer (K) | out | presentation/collab feature; metric 1 says "laser-less subset" |
| Embeddable (web embed) | out | untrusted network content |
| Lock tool (Q, keep tool active) | v1 | cheap; helps touch use |
| Library (reusable shapes) | v1 (local library, `.excalidrawlib` import/export); online library browse = out | in requirements scope |
| Command palette / search in canvas | later | |
| Element Stats panel (numeric x/y/w/h/angle) | v1 | maps to SteleKit's dimension-typing identity |
| Mermaid-to-diagram, text-to-diagram | out | explicitly out of scope |
| Zen / view modes, presentation mode | later | |

Shortcuts worth matching (INFERRED, verify against excalidraw.com shortcuts dialog): Ctrl+Z / Ctrl+Shift+Z (undo/redo), Ctrl+C/V/X, Ctrl+D duplicate, Ctrl+A, Delete, Ctrl+G / Ctrl+Shift+G group/ungroup, `Ctrl+]` / `Ctrl+[` bring forward/back, `Ctrl+Shift+]` / `Ctrl+Shift+[` to front/back, Ctrl+Alt+L/T/R/B align-ish (Ctrl+Shift+arrows align), Alt+S/D distribute, Ctrl+' grid, Ctrl+Shift+E export, Shift constrains (angle snaps 15 degrees, square/circle), Alt/Ctrl centre-out draw, arrow-key nudge (shift = 10 px / grid), Ctrl+Shift+L lock. v1 for all above that do not need a menu; non-drag/keyboard parity required by the accessibility NFR.

## 3. Style panel properties (INFERRED, matches base fields)
Stroke colour, background colour, fill style, stroke width (thin/bold/extra bold), stroke style, sloppiness (roughness), edges (roundness), font family (Hand-drawn/Normal/Code), font size, text align, vertical align, arrowhead start/end, arrow type (sharp/round/elbow), opacity, layers (z-order buttons), duplicate/delete/link/lock, "copy/paste styles" (Ctrl+Alt+C/V). v1: all except font family choice beyond platform-safe set (see edge cases), copy/paste styles v1 (cheap), link later.

## 4. Binding and arrow behaviours (VERIFIED field model; behaviours INFERRED)
- Binding model: arrow `startBinding`/`endBinding` reference a shape id plus `fixedPoint` ratio and `mode`; the shape's `boundElements` lists `{id, type: arrow|text}` back (VERIFIED). Two-way references must stay consistent: failure mode if shape deleted/duplicated/pasted without its arrow (INFERRED: Excalidraw unbinds on delete, rebinds on paste only if both ends are in the paste set).
- Bind on end-drop near shape outline (snap radius), highlight target; hold Ctrl/Alt to disable binding.
- Moving a bound shape moves arrow end; resize/rotate keeps the fixed point; arrow label (text bound with `containerId` = arrow).
- Text-in-shape: text with `containerId`; container grows to fit text (autoResize); deleting container deletes text.
- Multi-point lines: click-add points, double-click/Enter to finish, point editing mode (Ctrl+Enter), midpoint handles to add vertex, curved (round) arrows via 3 points.
- Elbow arrows: orthogonal routing avoiding bound shapes with `fixedSegments`. Triage: later (v1.1), because routing + re-route on move is the largest edge-case surface; v1 ships straight and curved. Import of elbow arrows: preserve as polyline with `elbowed` flag kept for round-trip.
- Triage: v1 binding for straight/curved arrows to rectangle/diamond/ellipse/text/image/frame; `inside` and `orbit` modes; `skip` stored only.

## 5. Groups, frames, z-order, lock
- Groups: `groupIds` is an ordered array (innermost first) so nested groups exist (INFERRED). v1: nested groups, ungroup, select group on click, edit inside via double-click.
- Frames (VERIFIED `frameId`, `name`): children reference frame; clipping at frame bounds on render and export; move frame moves children; frame name label; export frame as image. v1: containment, clipping, rename, export-frame. Auto-membership on drag-in/out: v1 (INFERRED to be required for metric 2).
- Z-order: fractional `index` (VERIFIED field present); bring to front/back/forward/backward. v1.
- Lock: per-element `locked`; v1.
- Layers (SteleKit-specific, not Excalidraw): requirement 5 asks for create/reorder/lock/hide/opacity. Excalidraw has none. Decision needed (Open Question 6); recommendation: keep named layers as a thin grouping layer above z-order (each element carries a layer ref; element z-order is within layer), map to `.excalidraw` by flattening (layer order into `index`, lock/hide into `locked`/dropped) with SteleKit extension in `customData` (INFERRED design, not verified).

## 6. File format: `.excalidraw`
Sources: [JSON schema doc](https://plus.excalidraw.com/docs/sdk/codebase/json-schema) and [data/types.ts](https://raw.githubusercontent.com/excalidraw/excalidraw/master/packages/excalidraw/data/types.ts) (VERIFIED).
- Top level: `type: "excalidraw"`, `version: 2` (number), `source` (URL string), `elements[]`, `appState` (export-safe subset only, e.g. `gridSize`, `viewBackgroundColor`), `files` map.
- `files`: id to `{mimeType, id, dataURL (base64 data URL), created (epoch ms), lastRetrieved}`; referenced by `image.fileId`. Untrusted: size caps, mime allow-list (png/jpeg/gif/webp/svg+xml; SVG needs sanitising or rasterising).
- Element `version`/`versionNonce`/`updated`/`created`: per-element revision counters for collab; `created` may be null in restored older files.
- Import is lenient (`ImportedDataState`, everything optional, legacy keys migrated) so our importer must also tolerate missing fields, unknown `type`s (skip with warning, count them), unknown arrowheads/fills, legacy bindings, and `isDeleted: true` elements (drop).
- Library files: `.excalidrawlib`, `{type: "excalidrawlib", version, source, libraryItems[]}`; v2 items `{id, status, elements, created}`; v1 `library` was array of arrays (INFERRED for item shape, VERIFIED for top-level names).
- Also: Excalidraw can embed the scene in PNG/SVG exports ("embed scene"); worth supporting import of that later (INFERRED).
- Versioning risk: schema `version` is still 2 but element fields evolve without bumping (e.g. new `fixedPoint` binding, `crop`, `polygon`, `autoResize`); importer must be field-tolerant, not version-gated. GAP: no authoritative changelog fetched.
- Triage: v1 import + export of the supported subset; round-trip property test per metric 7; preserve unknown fields via `customData`-like passthrough where cheap (later).

## 7. Export
- SVG: v1 all element kinds (existing writer extends); PNG: v1 (platform rasteriser via Compose `ImageBitmap`, scale 1x/2x/3x, background toggle, "only selected", frame export); `.excalidraw` v1; clipboard copy as PNG/SVG later; PDF out; embed-scene-in-PNG later.

## 8. FreeCAD Sketcher: geometry types
Primary source attempted: wiki.freecad.org (GAP, access denied). VERIFIED instead from [Constraint.h](https://raw.githubusercontent.com/FreeCAD/FreeCAD/main/src/Mod/Sketcher/App/Constraint.h) and [planegcs/Constraints.h](https://raw.githubusercontent.com/FreeCAD/FreeCAD/main/src/Mod/Sketcher/App/planegcs/Constraints.h). Geometry list below is INFERRED from FreeCAD knowledge:

| Geometry | Triage | Reason |
|---|---|---|
| Point | v1 | constraint anchor; scene element `PointElement` (OD-8) |
| Line segment | v1 | |
| Circle | v1 | needs radius/tangent; scene element = `EllipseElement` with equal radii (OD-8) |
| Arc of circle | v1 | rounded profiles; tangent chains; scene element `ArcElement` (OD-8; if declined, arc and line-arc tangency drop out) |
| Polyline / rectangle / slot / regular polygon (composite macros) | v1 for rectangle + polyline; others later | macros of lines + constraints |
| Ellipse, arc of ellipse/hyperbola/parabola | out | conic constraints (ConstraintEllipseTangentLine, Hyperbola, Parabola in planegcs) add much solver surface |
| B-spline (knots, weights, poles) | out | `ConstraintSlopeAtBSplineKnot`, `PointOnBSpline`; heavy |
| Construction geometry (toggle) | v1 | cheap flag; excluded from export/fusion; rendered dashed |
| External geometry (projected from other bodies) | out | no 3D |
| Fillet/trim/extend/split/offset operations | later (trim + fillet first) | editing aids, not solver |

## 9. FreeCAD constraint set
`ConstraintType` enum (VERIFIED, Constraint.h): None, Coincident, Horizontal, Vertical, Parallel, Tangent, Distance, DistanceX, DistanceY, Angle, Perpendicular, Radius, Equal, PointOnObject, Symmetric, InternalAlignment, SnellsLaw, Block, Diameter, Weight, Group, Text. Constraint fields (VERIFIED): element refs, `Value` (double), `isDriving` (driving vs reference/"driven" dimension), `isInVirtualSpace`. Value in solver is a double; planegcs has ~35 residual classes (VERIFIED list), e.g. P2PDistance, P2LDistance, PointOnLine, Parallel, Perpendicular, L2LAngle, MidpointOnLine, TangentCircumf, EqualLineLength, C2CDistance, ArcLength.

| Constraint | Triage | Notes |
|---|---|---|
| Coincident | v1 | point-point; merge or equality of 2 point pairs |
| PointOnObject | v1 | point on line/circle/arc; includes point-on-axis |
| Horizontal / Vertical | v1 | on a line or between two points |
| Parallel / Perpendicular | v1 | |
| Tangent | v1 (line-circle, circle-circle, line-arc endpoint tangency) | most numerically fiddly; endpoint-to-endpoint tangent (coincident + tangent) is the common use |
| Equal | v1 | line length, circle radius |
| Symmetric | v1 | about point or line; 2 equations |
| Block (lock geometry in place) | v1 as "fixed" | metric 3 lists "fixed" |
| Distance (point-point, point-line, line length), DistanceX/Y | v1 | |
| Angle (line-line, line to axis) | v1 | wrap-around/supplement ambiguity, see edge cases |
| Radius, Diameter | v1 | |
| Driven (reference) dimensions (isDriving=false) | v1 | display-only measure; cheap |
| Internal alignment (ellipse/spline foci) | out | only for out-of-scope conics |
| Snell's law, Weight, Group, Text | out | niche or non-geometric |
| Expressions/named parameters in dimensions | v1 link to existing named dimensions | existing pipeline already has named dimensions (INFERRED from requirements) |
| Auto-constraint while drawing (infer coincident/horizontal/vertical on snap) | v1 | this is what makes Sketcher usable; maps to existing SnapEngine kinds |

Solver: FreeCAD planegcs uses DogLeg, Levenberg-Marquardt and BFGS plus QR-based diagnosis (INFERRED; the fetch of GCS.h did not happen, GAP; confirm in the solver research doc). Status reporting in FreeCAD (INFERRED, wiki GAP): degrees of freedom count, "fully constrained", "conflicting constraints" (list), "redundant constraints" (list), "partially redundant", "malformed constraints", "solver failed to converge". v1 must report: DOF count, fully/under/over-constrained, the set of conflicting ids, redundant ids, non-convergence, with per-element colouring (e.g. fully constrained = distinct colour). Reference: metric 3.

## 10. Edge cases and failure modes
Excalidraw side:
- Binding: target deleted, duplicated, grouped, or in a different frame; arrow bound to both ends of same shape; binding to rotated shapes (fixedPoint in local frame); orphaned `boundElements` entries on import; reciprocity mismatch (arrow says bound, shape does not list arrow).
- Text: fonts differ per platform so measured widths differ and wrap differently; `originalText` vs wrapped `text`; container auto-resize loops; RTL/CJK/emoji; IME composition on desktop/Android; soft keyboard hiding canvas on Android; text editing at zoom != 1.
- Freedraw: huge point counts (file size, hit-test cost); simplify on commit; stylus pressure absent on mouse (`simulatePressure`).
- Hit-testing: stroke width and zoom-dependent tolerance, thin lines on touch (use larger touch slop), hit on hollow shapes vs filled, rotated and curved paths, frames (click inside frame selects child, not frame).
- Rotation + resize of groups with mixed element types; flip (negative scale); text and images under non-uniform scale.
- Images: EXIF orientation, huge images (memory on Android/wasm), SVG as image (script), duplicate bytes shared by `fileId` (dedupe by hash), missing `files` entry (`status: error` placeholder).
- Undo: Excalidraw history is per-element-delta; SteleKit command history today is whole-command; need multi-element commands and a coalescing rule for drags/typing; undo must also revert solver-moved geometry in one step.
- Clipboard: paste at cursor, offset on repeated paste, cross-app paste of Excalidraw JSON (text/plain) from the web app — a likely unstated user need (INFERRED).
- Import: untrusted JSON — depth/size caps, NaN/Infinity coordinates, duplicate ids, non-finite width/height, absurd coordinates (>1e7), extremely large arrays (cap 5,000 elements per NFR), `javascript:` links, data URLs of unexpected mime.
- Z-order `index` collisions or missing (older files lack it): fall back to array order.
- Round-trip loss: Excalidraw float coordinates vs sixteenths integers; diagram mode must be floating-point (Open Question 4); lossy conversion only when entering technical-sketch mode.

FreeCAD side:
- Over-constraint on drag: dragging a fully constrained point must not explode; solver must keep last good state and report, not partially apply (atomic solve with rollback).
- Redundant vs conflicting is a distinction users expect (redundant is harmless, conflicting blocks); both must name offending constraints.
- Singular Jacobian near degenerate geometry (zero-length line, zero radius, collinear tangent), flipping solutions (tangent circle on wrong side, angle sign, symmetric about line), solution branch consistency during drag (solve from current geometry, not from scratch).
- Angle constraint ambiguity (0 to 2pi vs signed vs supplementary).
- Numerical tolerance: need an explicit tolerance policy (e.g. residual < 1e-9 relative to sketch scale) to match metric 3's "within tolerance"; sixteenths grid conflicts with solver doubles (rounding of solved coords for the fusion path; exact rational dimensions kept separately).
- Constraint ids stability across undo and migration; deleting geometry removes dependent constraints (cascade) with undo restoring them.
- Constraints applied to Excalidraw-style free-form elements (rotated rect, ellipse, text) have no defined point set; restrict constraints to a defined "sketch geometry" subset (point, line, circle, arc, polyline, rect) per Open Question 2 (recommendation: constraints only on technical-sketch-mode geometry, arrows/text never constrained in v1; a constraint-driven shape can still be styled and bound by arrows).
- Performance: 100-constraint solve < 50 ms (NFR) — sparse Jacobian or small dense systems by connected component (INFERRED); decomposing into independent clusters is standard and bounds cost.

## 11. Unstated user needs (INFERRED; flag for owner confirmation)
1. Paste an Excalidraw selection copied from excalidraw.com (clipboard text/JSON) and get editable shapes.
2. Edit existing diagram images? No; but convert/open `.excalidraw` files stored in the graph assets.
3. Diagram readable in journals and as page embed with fast thumbnail rendering (existing `SketchPreview`/`SketchRenderCache` extend).
4. Dark mode: Excalidraw inverts colours at render time for dark theme; stored colours must stay theme-independent.
5. Git-friendly stored text: stable key order, fractional index ordering, no volatile `updated`/nonce churn on save (otherwise every save is a diff). Directly informs Open Question 1.
6. Search: text inside diagrams indexed in graph search (later).
7. Link element to page `[[Page]]` (later, strong fit for a note app).
8. Phone use: two-finger pan/zoom without drawing, palm rejection, large handles; undo gesture.
9. Autosave and crash recovery for in-progress edits; Android process death mid-edit (persisted draft).
10. Accessible alternative: element list view with semantic labels (extends `SketchSemantics`).
11. Snap to other elements' edges/centres with guide lines (Excalidraw "object snapping"); v1 as it is in the requirements; builds on existing SnapEngine.
12. Align/distribute and equal-spacing; v1.
13. Large coordinate/zoom range (1% to 3000% in Excalidraw, INFERRED).

## 12. Triage summary
- v1: shapes (rect, diamond, ellipse), line/polyline, straight and curved arrows with binding and labels, text, freedraw, image (no crop), frame, nested groups, z-order, lock, eraser, selection/transform/rotate, align/distribute, copy/paste/duplicate, style panel (solid fills only, stroke width/style, opacity, arrowheads, roundness, fonts from platform-safe set), local library, undo/redo, object snapping and grid, element stats panel, SVG/PNG/.excalidraw export, .excalidraw import, point/line/circle/arc + rect/polyline geometry (scene elements: `PointElement`, `ArcElement`, circle = `EllipseElement` with equal radii; owner decision OD-8), construction flag, coincident, point-on-object, horizontal, vertical, parallel, perpendicular, tangent, equal, symmetric, fixed/block, distance (+X/Y), angle, radius, diameter, driven dimensions, auto-constraint on draw, DOF/conflict/redundancy/non-convergence reporting.
- Later: elbow arrows, hachure/cross-hatch/zigzag fills (with rough style), cardinality arrowheads, image crop, pressure tuning, trim/fillet/offset, composite slot/polygon macros, element links, text search, command palette, presentation mode, embed-scene-in-PNG import.
- Out: collaboration/live rooms/encrypted share, laser, embeddables/iframes, AI/Mermaid generation, ellipse/conic and B-spline geometry, external geometry, Snell/weight/group/text constraints, 3D, DXF/STEP, PDF export.

## 13. Open gaps for later phases
- GAP: FreeCAD wiki text (status messages, exact tool list) not fetched; re-fetch via a different mirror or the FreeCAD GitHub `Sketcher` docs.
- GAP: planegcs `GCS.h` (solver algorithm and diagnosis API) not read; belongs in the solver research.
- GAP: Excalidraw keyboard shortcut list and `.excalidrawlib` item schema not verified from source.
- Verify the element-type summary (stickynote, magicframe) directly in `types.ts` before writing the importer.

## 14. Baseline pin and upstream verification (Spike 0.1.5, Tasks 0.1.5a, 0.1.5c, 0.1.5d agent part; 2026-11-16)
**Pin (VERIFIED)**: Excalidraw `v0.18.1`, published 2026-04-21T08:43:20Z (GitHub releases API `releases/latest`), tag commit `a2ec2889babf7d2295469c6d90ebe77fae57df84` (`git/ref/tags/v0.18.1`). `types.ts` was read at `packages/excalidraw/element/types.ts` in that tag (412 lines; `packages/element/src/types.ts` 404s at this tag); the shortcut list was read from `packages/excalidraw/components/HelpDialog.tsx` and `components/shapes.tsx` in the same tag. D-22 holds at 0.18.1.

**Element types in `ExcalidrawElement` (VERIFIED from types.ts)**: selection, rectangle, diamond, ellipse, text, line, arrow (`elbowed`), freedraw, image, frame, magicframe, iframe, embeddable. **There is NO `stickynote` type in v0.18.1** (it was an INFERRED fetch-summary error in section 3 and in the gap list above): the importer treats it as an unknown type like any other. Other findings: `FixedPointBinding` still carries `focus` and `gap` in addition to `fixedPoint`; `ExcalidrawElbowArrowElement` adds `fixedSegments`, `startIsSpecial`, `endIsSpecial`; image elements have `crop` and `scale`; `Arrowhead` has 12 values including `crowfoot_*` and `circle_outline`; `FillStyle` is hachure, cross-hatch, solid, zigzag.

**Fixtures (VERIFIED by `node` JSON parse)**: `kmp/src/commonTest/resources/excalidraw/` holds `shapes-text`, `bound-arrows` (incl. an elbow arrow), `frame-group` (frame, magicframe, embeddable, iframe, group ids), `image` (with a `files` entry, flip scale, crop), `freedraw` (real and simulated pressure) and `legacy-binding` (`{focus,gap}` only), all `.excalidraw`, hand-authored by `scripts/sketch-goldens/author-fixtures.mjs`; every element has all 24 base fields of `_ExcalidrawElementBase` (checked by script). They are NOT real exports: owner Task 0.1.5d (5 real exports into `.../excalidraw/real/`) is still open, so `FixtureRoundTripTest` must print `hand-authored fixtures only, UNMEASURED against real exports` until then.

**U8 shortcut check (partial)**: compared plan D-8 with the `HelpDialog.tsx` SOURCE at v0.18.1, NOT the live dialog (INFERRED for the live site; the owner or a browser run still has to confirm). Matches: V/1 select, R/2 rectangle, D/3 diamond, O/4 ellipse, A/5 arrow, L/6 line, P/7 freedraw, T/8 text, 9 image, E/0 eraser, H hand, F frame, Q tool lock. **Conflict**: Excalidraw binds `K` to the laser tool (and `I` to the eyedropper); plan D-8 uses `K` for the Constrain menu. Because the laser is out of scope the clash is harmless for behaviour, but the shortcuts sheet should not claim K parity; decide before the keymap freeze (Task 3.6.1c). **Decision (Task 3.6.1c)**: D-8 stands, K opens the Constrain menu; the deviation is recorded in `KeymapSpec.excalidrawDeviations` and asserted by `KeymapSpecTest.deviation_should_RecordKAsConstrainWhereExcalidrawBindsLaser`, and the sheet claims no K parity. Also present upstream: Ctrl/Cmd+K link, Shift+1 zoom to fit, Shift+2 zoom to selection, Alt+Z zen, Alt+S snap, Ctrl/Cmd+' grid.

**Upstream licenses (VERIFIED by reading the LICENSE files, all "MIT License", identical 21-line body)**: perfect-freehand (Copyright (c) 2021 Stephen Ruiz Ltd; npm `perfect-freehand@1.2.3` license MIT), rough (Copyright (c) 2019 Preet Shihn; npm `roughjs@4.6.6` license MIT), excalidraw (Copyright (c) 2020 Excalidraw). Recorded in the repo-root `NOTICE`. The plan is not escalated.
