# UX research: excalidraw-sketch-engine

Date: 2026-11-14. Scope: comparable patterns, mental models, accessibility, error states, jobs-to-be-done, and gaps in the current editor.

Confidence labels: **VERIFIED** = I opened the repo file in this session (path and line cited). **INFERRED** = recalled from prior knowledge of the named products or standards; no page was fetched in this session, so links are pointers to check, not evidence. Treat every INFERRED claim as a hypothesis for Phase 3 to confirm.

## 1. Comparable UX patterns

### 1.1 Toolbars and tool model

| Product | Pattern | Takeaway for SteleKit | Label / source |
|---|---|---|---|
| Excalidraw | Single horizontal tool strip, one active tool, digit and letter shortcuts (1-9, V, R, D, O, A, L, P, T, E, H); "tool lock" (Q) keeps the tool after one draw; selection auto-returns to Select after one shape by default | Copy the shortcut map and tool-lock; it is the user's stated baseline | INFERRED, https://docs.excalidraw.com/ and https://github.com/excalidraw/excalidraw |
| tldraw | Same strip, but bottom-docked on mobile; style panel is a floating card; "tool lock" and radial-free design; strongest touch ergonomics of the web tools | Bottom-docked tools suit phone and foldable; reach zone is the thumb | INFERRED, https://tldraw.dev/ |
| Miro | Left rail of tool groups with flyouts; contextual toolbar floats above the selection | Contextual selection toolbar avoids a far-away property panel on large canvases | INFERRED, https://miro.com |
| Apple Freeform | Bottom toolbar, pencil-first; finger pans, Pencil draws; shape recognition on hold | Input-device split (finger = navigate, pen = mark) is the dominant stylus convention | INFERRED, https://support.apple.com/guide/freeform |
| Concepts | Infinite canvas, brush-centric tool wheel, per-layer opacity and blend, two-finger tap = undo, three-finger tap = redo | Layers with opacity are expected in sketch tools; gesture undo is a stylus convention | INFERRED, https://concepts.app |
| FreeCAD Sketcher | Tools in a docked task panel with a constraint list, a solver-messages area and a DOF count; constraints are separate tools, applied to selected geometry | The constraint list plus a persistent DOF and status line is the reference for "constraint visualisation" | INFERRED, https://wiki.freecad.org/Sketcher_Workbench |
| Shapr3D | Direct-manipulation 2D sketch; constraints are auto-inferred while drawing and shown as small glyphs; manual constraint set is contextual to the selection | Auto-infer on draw and show glyphs; do not demand a "constrain" step for the common cases | INFERRED, https://support.shapr3d.com |

Current SteleKit state (VERIFIED): the toolbar is a horizontally scrolling `FilterChip` row with only three tools (`SketchTool { LINE, RECTANGLE, ZONE_SELECT }`, `SketchViewModel.kt:55`), a pinned Delete/Undo/Redo group, and a text status string (`SketchScreen.kt:71-93`). Expanding to roughly 12 tools in a chip row will not fit; a grouped strip with flyouts or a bottom-docked bar on compact windows is needed.

### 1.2 Property panels

- Excalidraw and tldraw show a style panel only when something is selected or a drawing tool is active, and it edits the selection and the default for next shapes at once (INFERRED). Recommendation: one `StylePanel` bound to "selection, else tool defaults".
- Miro and Figma-like tools use a floating contextual toolbar anchored to the selection (INFERRED). On phones, anchor to the bottom sheet instead (the repo already anchors `SelectionBar` below the canvas "so it never covers the shape being edited", VERIFIED `SketchScreen.kt:113`). Keep that rule.
- Typed dimensions are SteleKit's differentiator: `DimensionField` for Width/Height/Length (VERIFIED `SketchScreen.kt:119-122`). Keep this as the properties panel's first section for technical elements.

### 1.3 Layers UX

- Excalidraw has no layers, only z-order, groups and frames (INFERRED). tldraw has no layers either. Concepts, Miro (frames), Photoshop-style tools have a layer list with eye, lock, opacity, drag-reorder (INFERRED).
- Current SteleKit layers (VERIFIED `LayerPanel.kt:25-48`): per-name rows with two checkboxes ("Show", "Lock"), a "Move selection here" text button per layer, an add-by-name field. Missing: reorder, opacity, rename, delete, layer colour, per-layer element count, drag-to-assign, "isolate layer", and selection-follows-layer highlighting.
- Recommendation for Open Question 6: keep Excalidraw-style flat z-order, groups and frames as the primary organisation for diagrams; keep named layers as a second, optional axis whose panel is hidden until the user adds a second layer. Rationale: matches the stated Excalidraw baseline and avoids the Photoshop-style cognitive load for the diagram persona. INFERRED (design judgement; validate with the owner).
- Layer rows need real controls (icon toggle with `Role.Switch` or `toggleable` semantics) and a drag handle plus Move up/down buttons as the non-drag alternative.

### 1.4 Mobile, foldable and stylus patterns

- Finger pans and pinch-zooms; stylus draws; palm rejection by pointer type (INFERRED, Android `MotionEvent.TOOL_TYPE_STYLUS`; Compose `PointerType.Stylus`, https://developer.android.com/develop/ui/compose/touch-input/pointer-input).
- Hover preview (stylus hover, mouse move) shows the snap target before commit; only available on some devices and never on touch (INFERRED).
- Two-finger tap for undo is a Concepts and Procreate convention (INFERRED); risky as the only path, so keep the visible Undo button.
- Foldable (Pixel_Fold): posture and window-size classes. Expanded width should show the tool strip left or the property panel as a persistent side pane; compact should use a bottom sheet. Use `WindowSizeClass`-style breakpoints (INFERRED, https://developer.android.com/develop/adaptive-apps). The repo already treats Pixel_Fold as the verification device (CLAUDE.md).
- Current gesture machine (VERIFIED `SketchCanvas.kt:44,89`): one `pointerInput`, tap vs drag by touch slop, one finger drags the view, two pointers pan/zoom (`SketchGestures.kt:38-78`). Consequence: **one-finger drag pans the view**, so freehand, move-element and marquee need a mode or pointer-type split that does not exist yet. Stylus-vs-finger distinction is not present (no `PointerType` use found in `SketchCanvas.kt`; grep for `PointerType` not run, so treat as INFERRED from the lines read).

### 1.5 Constraint visualisation

FreeCAD colours geometry by solve state (INFERRED, wiki link above): unconstrained = white/default, fully constrained = green, conflicting = red, redundant = orange/yellow, plus DOF count in the status bar, constraint glyphs next to geometry and a list panel where selecting a row highlights the geometry. Dimension constraints render as dimension lines with editable value labels. Shapr3D shows small constraint glyphs (parallel, perpendicular) near the geometry (INFERRED).

Recommendations:
1. Never encode state by colour alone (WCAG 1.4.1); add a glyph or dash style plus text in the list.
2. Constraint list panel with: icon, type, participants, value, state; row tap highlights geometry; per-row delete and "convert to reference".
3. Status line: "Fully constrained", "N degrees of freedom", "Over-constrained: 2 conflicts" as a live region.
4. Existing zones panel already shows a "Not a length. Try 12 3/16\"" supporting text (VERIFIED `LayerPanel.kt:76`), a model to follow for per-constraint errors.

## 2. Mental models: diagram mode vs technical-sketch mode

Two user intents with different defaults (INFERRED synthesis; the requirement doc's Open Question 2 asks for exactly this decision):

| Axis | Diagram mode (Excalidraw-like) | Technical sketch mode (FreeCAD/dimension-like) |
|---|---|---|
| Unit of work | Boxes, arrows, text, loose positions | Exact lengths and relations |
| Coordinates | Free float, snap to grid optional | Integer sixteenths, snap on by default (existing) |
| Truth | What it looks like | What it measures; dimensions feed fusion/project-bridge |
| Style | Stroke, fill, hand-drawn optional | Line weights, dimension lines |
| Constraints | Binding (arrow follows shape), alignment guides | Geometric and dimensional constraints |
| Error model | Rarely wrong | Can be over-constrained, unsolvable |

Unification options:
- **A. One scene, per-element "constrainable" opt-in (recommended).** Same canvas, same tools, same layers. An element becomes "technical" when it receives a dimension or geometric constraint, or when the sheet has a scale set; technical elements show a small badge and use the exact-integer fields. Bindings (arrow-to-shape) are a lightweight constraint class that runs without the solver UI. Pro: no mode switch, one mental model, matches Shapr3D's auto-infer. Con: the model must carry both coordinate kinds (see Open Question 4).
- **B. Sheet-level mode switch** ("Diagram" or "Technical"), chosen at creation, changes default toolset and panels. Pro: simple defaults. Con: awkward when a floor plan needs a text callout; conversion between modes is a trap.
- **C. Two separate editors.** Rejected by the "one engine" requirement.

Proposed UX under A: a sheet property "Units and scale" (already exists as `SheetPropertiesPanel`, VERIFIED listed under `ui/sketch`) turns on technical affordances (typed-dimension fields, snap to sixteenth, dimension tools). Without a scale the sheet behaves as a diagram. Progressive disclosure: constraint tools appear in a "Constrain" flyout, hidden until the user selects two or more elements.

## 3. Accessibility

### 3.1 Standards (INFERRED; fetch before citing in the ADR)
WCAG 2.2 (https://www.w3.org/TR/WCAG22/) criteria that bite a canvas editor:
- 2.1.1 Keyboard and 2.1.2 No Keyboard Trap: every tool, selection, move, resize, z-order, layer, constraint operation reachable by keyboard.
- 2.4.7 Focus Visible and 2.4.11 Focus Not Obscured (new in 2.2): focus rings on canvas elements must not be hidden by the floating panels or the nudge pad.
- 2.5.7 Dragging Movements (new in 2.2): every drag has a single-pointer non-drag alternative. SteleKit's crosshair mode already addresses this for placement.
- 2.5.8 Target Size Minimum (new in 2.2): 24x24 CSS px minimum; the existing pad uses 56dp cells (VERIFIED `SketchNonDragMode.kt:137`), well above.
- 1.4.1 Use of Color, 1.4.11 Non-text Contrast (3:1) for strokes, handles, glyphs.
- 4.1.2 Name, Role, Value and 4.1.3 Status Messages for the solver state and save status.

### 3.2 What the repo already does (VERIFIED)
- Non-drag crosshair mode: `SketchPlacementSession` toggled by a "Crosshair" chip, nudge pad with 1 px / 10 px / 1 percent steps, "Place point" / "Apply" confirm, "Cancel", haptic on commit (`SketchNonDragMode.kt:98-198`). A move targets a vertex via `startMove`.
- Spoken lengths: `spokenLength` renders "1 ft 3 and 3/16 in" rather than the written form (`SketchSemantics.kt:25-41`).
- Canvas semantics: one `contentDescription` for the whole scene, built by `SketchScene.describe()`, plus `CustomAccessibilityAction("Move vertex N")` for the selected primitive's vertices (`SketchSemantics.kt:47-90`).
- Nudge buttons carry descriptions like "Nudge left, 10 pixels step" (`SketchSemantics.kt:78`).
- Delete or Backspace deletes the selection (`SketchScreen.kt:66-69`); delete icon has a content description (`SketchScreen.kt:111`).

### 3.3 Gaps for the new engine (VERIFIED where cited, else INFERRED)
1. **Scene is one semantics node.** Elements are not individually focusable or traversable; TalkBack cannot swipe element by element, and a 500-element scene yields one very long `contentDescription` (`SketchSemantics.kt:59-60`). Needed: a virtual child node per element (Compose `semantics { }` on child modifiers, or a `Modifier.semantics` tree built from the scene) with role, label, bounds, selected state, and actions. Cap or paginate for large scenes (see section 4.3).
2. **Element descriptions only know Line and Rect** (`SketchSemantics.kt:48-57`); the `when` is exhaustive so new element kinds will fail compilation, which is helpful. Each new kind needs a description template: "Arrow from <label A> to <label B>", "Text: <content>", "Ellipse, 3 ft by 2 ft, labelled <text>", "Frame <name>, contains 4 elements".
3. **Keyboard**: only Delete is bound (`SketchScreen.kt:66-69`). Needed: Tab/Shift+Tab to cycle elements, arrow keys to move (Shift = big step), Enter to edit text, Esc to cancel/deselect, Ctrl+Z/Y, Ctrl+C/V/D, Ctrl+G group, [ ] z-order, tool letter shortcuts, a shortcut cheat sheet. Mirrors Excalidraw (INFERRED).
4. **Non-drag alternatives for new gestures**: resize handles, rotation, arrow endpoint binding, marquee select, freehand (inherently a drag; offer "draw polyline by points" fallback), reorder layers, pan (arrow keys plus buttons). Existing nudge pad only moves a crosshair for Place/Move vertex.
5. **Undo/redo are text buttons** with no keyboard shortcut and no announced result (`SketchScreen.kt:91-92`). Add live-region announcements ("Undid: move rectangle").
6. **Status is text without a live region** (`statusText()`, `SketchScreen.kt:154-160`); solver and save outcomes should be announced (WCAG 4.1.3).
7. **Layer controls** use checkboxes with sibling `Text` labels, not labelled toggles (`LayerPanel.kt:38-41`); TalkBack reads "checkbox, checked" without the layer name. Merge semantics so each reads "Layer Walls, visible, switch".
8. **Colour-only encodings**: stroke colours in the style panel need names; constraint state needs non-colour cues.
9. **Reduced motion and high contrast**: no handling found (not searched exhaustively; INFERRED).
10. **Text editing**: IME composition, multi-line, and screen-reader reading of in-canvas text need a real `BasicTextField` overlay rather than painted text (INFERRED; see requirements Rabbit Hole on text).
11. **Hover and focus ring on canvas elements** (WCAG 2.4.7) not implemented; a focus ring drawn in the canvas must be at least 3:1 against both element and background.

## 4. Error states and edge cases

For each: what the user sees, and the recovery. Existing error handling is a single `error: String?` shown as "Not applied: <msg>" in the toolbar, or a centred full-screen message when `phase == FAILED` (VERIFIED `SketchScreen.kt:101,155`; `SketchViewModel.kt:101-102,184,279`). That is too coarse for a constraint solver and importer.

### 4.1 Over-, under- and non-converging constraint systems
- Under-constrained: not an error. Show "N DOF" and colour/glyph the movable geometry; offer "show free points". FreeCAD behaves this way (INFERRED).
- Over-constrained / conflicting / redundant: reject the **offending new constraint**, not the whole sketch, and keep the previous solved geometry. Message names the constraint and its conflict partners ("Parallel conflicts with Horizontal on Line 3"); highlight both; offer "Remove new constraint", "Make it a reference (driven) dimension", "Show conflicts". Redundant constraints (consistent but unnecessary) are warnings, not blocks.
- Non-convergence during drag: freeze at the last valid pose, show a transient "Can't satisfy constraints here" toast, never leave geometry NaN or half-solved. Do not apply a partial result.
- Solve time budget (<50 ms per 100 constraints, requirements) exceeded: show a progress affordance after about 100 ms and allow cancel; keep the UI thread free.
- Undo must restore both the constraint set and the geometry atomically (requirements Rabbit Hole "Undo semantics").
- Announce via a status live region (WCAG 4.1.3).

### 4.2 Import failures (`.excalidraw`, images)
Imported files are untrusted (requirements NFR). UX:
- Parse errors: "Couldn't read this file: not valid Excalidraw JSON" with reason code, never a stack trace; nothing is created.
- Partial support: import what is supported and show a summary dialog "Imported 41 of 44 elements. Skipped: 2 embeddables, 1 unsupported arrowhead", with "Copy details". Skipped elements are optionally preserved as grey placeholders so a round-trip does not silently drop data (decision for ADR).
- Version mismatch: "Created by a newer Excalidraw (v2 schema); some elements may differ".
- Size caps: reject or warn above the cap before parsing, with the cap stated ("Files over 20 MB aren't imported"), no external image fetch (state "Linked images were not downloaded").
- Missing image binary in the file map: show a broken-image placeholder element with a label, not a crash.
- Never block the whole editor: import runs off the main thread with cancel.

### 4.3 Huge scenes
- Upper bound is 5,000 elements and 200 constraints (requirements). At or near the cap: non-blocking banner "Large drawing: some effects are simplified"; degrade in order: disable hand-drawn rendering, skip shadows, cull off-screen, lower-resolution thumbnails, stop live constraint glyphs.
- Semantics: do not emit 5,000 child nodes; emit nodes for the viewport plus selected only (INFERRED design).
- Over the hard cap on paste/import: refuse with a count and offer "import first 5,000" or "import into a new sketch".
- Autosave state ("Saving", "Save failed", VERIFIED `SketchScreen.kt:157-158`) needs a retry action and must never discard in-memory work after a failed save.
- Memory pressure on Android: surface a recoverable message, rely on the existing persisted logs (CLAUDE.md "Android debugging").

### 4.4 Other edge cases
- Bound arrow whose target is deleted: arrow becomes free-ended at its last position (Excalidraw behaviour, INFERRED) with an undo that restores the binding.
- Locked layer and selection: attempting to edit a locked element shows "Layer 'Walls' is locked" with an "Unlock" action, not a silent no-op.
- Hidden layer with selection: selection is cleared on hide; announce it.
- Migration: opening a v1 sketch under `sketch.engine.v2` shows a one-time "Upgraded; backup saved" notice, and a failed migration offers "Open in legacy editor" (requirements Risk Control).
- Text overflow, empty text elements (auto-delete on blur), and zero-length lines.
- Concurrent external edit of the sidecar file: reuse the existing conflict pattern (`DiskConflict` in CLAUDE.md data flow).
- Stylus not available or palm touches: tool falls back to touch with a visible "Finger drawing" toggle (INFERRED).

## 5. Jobs-to-be-done

Persona is the owner (requirements: desktop, Android phone/foldable, web, occasional iOS). Statements are INFERRED from requirements and the existing home-project-sketching feature; confirm with the owner.

1. When I am planning a home project (room, shelf, wall), I want to sketch exact dimensions and see whether zones add up, so I can buy materials without remeasuring. (Existing; keep intact.)
2. When I am designing software, I want to draw a labelled architecture diagram in my notes, with arrows that follow boxes, so the diagram stays correct as I rearrange it, without leaving SteleKit or round-tripping through Excalidraw.
3. When I have an Excalidraw file from elsewhere, I want to import, tweak and export it without loss, so my diagrams are not stuck in two tools.
4. When I am on my phone or foldable, I want to place and adjust things precisely without a mouse, so I can sketch at the site. (Existing crosshair mode.)
5. When I rough something out with a pen, I want my strokes kept as strokes and not mistaken for pans, so quick ideation is fluid.
6. When I constrain a sketch, I want to know what is still free and what conflicts, in plain words, so I can fix it without learning solver internals.
7. When I embed a sketch in a journal page, I want it to render accurately at small size and stay in sync, so notes stay trustworthy. (Existing embed path.)
8. When I use a screen reader or keyboard only, I want to build and review the same drawings, so the tool is usable with limited vision or motor control. (NFR Accessibility.)

## 6. Concrete UX gaps in the current editor (VERIFIED unless noted)

| # | Gap | Evidence |
|---|---|---|
| G1 | Only 3 tools; no select/move/transform, no pan tool, no eraser, no text, arrow, ellipse, freehand, image, frame | `SketchViewModel.kt:55`, `SketchScreen.kt:148-152` |
| G2 | "Select" tool is named `ZONE_SELECT` and labelled "Select"; it picks primitives for zones/deletion, not move/resize | `SketchGestures.kt:23-36`, `SketchScreen.kt:151` |
| G3 | One-finger drag always pans; there is no drag-to-move element, marquee or resize handles | `SketchGestures.kt:38-78`, `SketchCanvas.kt:44` |
| G4 | No keyboard shortcuts beyond Delete/Backspace; no Ctrl+Z, tool keys, arrow-move, Tab traversal | `SketchScreen.kt:66-69` |
| G5 | Undo/Redo are text buttons without shortcut or announcement | `SketchScreen.kt:91-92` |
| G6 | Toolbar is a scrolling chip row; will overflow with more tools; no grouping, no tool lock, no bottom-dock on compact windows | `SketchScreen.kt:71-93` |
| G7 | Selection editing is only typed Width/Height or Length; no style panel (stroke, fill, opacity, dash, font, arrowheads) | `SketchScreen.kt:115-127` |
| G8 | Layers panel: no reorder, opacity, rename, delete, drag-assign; checkboxes with detached labels; hidden behind a "Sheet panels" toggle | `LayerPanel.kt:25-48`, `SketchScreen.kt:82,94` |
| G9 | Scene exposed to TalkBack as one aggregated description; no per-element focus; description grows linearly with elements | `SketchSemantics.kt:59-60,81-82` |
| G10 | Errors collapse to one string ("Not applied: ...") or a full-screen failure; no per-constraint or per-import messages; status is not a live region | `SketchScreen.kt:101,154-160` |
| G11 | No constraint UI at all (list, glyphs, DOF, conflicts); only zone sum text | `LayerPanel.kt:51-79` |
| G12 | Non-drag mode only covers place point and move vertex; new gestures (resize, rotate, bind, marquee, reorder) have no non-drag path yet | `SketchNonDragMode.kt:92-135` |
| G13 | Crosshair chip is always visible, owns the bottom-end corner and shifts for the shell's mic button; adding a floating style panel and tool dock will collide | `SketchNonDragMode.kt:167-171` |
| G14 | No stylus or hover handling evident (INFERRED from lines read) | `SketchCanvas.kt` not fully read |
| G15 | No zoom controls, fit-to-content, grid toggle or snap toggle visible in the toolbar (INFERRED; only the chips, export, panels toggle read) | `SketchScreen.kt:71-93` |
| G16 | Panels capped at 240 dp with their own scroll; small for a style plus layers plus constraints panel on a phone | `SketchScreen.kt:139` |

## 7. Recommendations (for Phase 3)

1. Adopt option A (single scene, opt-in technical/constraint behaviour) and make Open Question 2 answer: constraints apply to any element; bindings are always on.
2. Define a `ToolStrip` with groups and flyouts; bottom-dock on compact, left rail on expanded; keep Undo/Redo/Delete pinned.
3. Build a `StylePanel` and `ConstraintPanel` as adaptive surfaces (side pane expanded, bottom sheet compact); reuse `DimensionField`.
4. Split pointer handling by `PointerType` (touch = navigate, stylus/mouse = act), with a visible override toggle; define tool-lock.
5. Per-element semantics tree with a viewport cap; extend `describe()` per new kind; add keyboard map and a shortcut sheet.
6. Extend the crosshair mode into a generic "keyboard/non-drag transform" layer so resize, rotate and binding have non-drag paths (WCAG 2.5.7).
7. Replace `error: String?` with a typed, per-area diagnostics list (solver, import, save) surfaced through a live region.
8. Add the checks above to the manual device-test list the owner must run (stylus, hover, TalkBack on Pixel_Fold), since CI cannot cover them (requirements Wall-clock blockers).

## 8. Gaps in this research
- No web pages were fetched this session; product behaviours in section 1 and WCAG criteria in 3.1 come from prior knowledge. Before an ADR cites them, fetch the linked pages.
- `SketchCanvas.kt` was only grepped, not read in full; stylus/zoom-control claims (G14, G15) are therefore INFERRED.
- No user testing; the jobs-to-be-done are reasoned from the requirements, not interviewed.
