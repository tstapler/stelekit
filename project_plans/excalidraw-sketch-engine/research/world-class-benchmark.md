# World-class benchmark: v2 sketch editor (whiteboard + measured phone sketches)

Date: 2026-11-24. Method and honesty note first.

## Evidence legend

- **VERIFIED-PAGE**: page fetched and read in this session.
- **VERIFIED-SNIPPET**: stated in a WebSearch result summary that cites the URL; the page itself was not opened by me.
- **INFERRED**: general domain knowledge or extrapolation; no source opened this session. Treat as hypothesis.

Search was breadth-limited (about 14 queries, 3 page fetches). Several requested areas returned nothing usable and are marked as gaps: Miro/FigJam/draw.io editing features, Canvas (Occipital), Planner 5D, Floorplanner, SketchUp Mobile, Onshape/Fusion mobile, Nomad, FluidCAD, Morpholio Trace feature detail, PlantUML/D2 syntax, IFC, SVG metadata round-trip, ASME leading-zero and ft-in rules, scale bars/title blocks. Do not read the capability matrix as complete for those cells.

## 1. Findings by source

### Whiteboard tools
- **tldraw snapping** (VERIFIED-PAGE, https://tldraw.dev/sdk-features/snapping): two systems, bounds snapping (edges, centers, corners, gaps) and handle snapping (`point` and `align` types). Off by default, hold Ctrl/Cmd to snap; a "snap mode" preference inverts that. Threshold `snapThreshold` is 8 screen px scaled by zoom. Gap snapping centers in a gap and repeats an existing gap. Indicators are `points` (alignment lines) and `gaps` (spacing measurements). Grid snap is a separate flag. Per-shape opt-out via `canSnap()`.
  - Lesson: on a phone there is no Ctrl key, so snap must be default-on with a visible toggle, and the threshold must be in dp, not px.
- **tldraw accessibility** (VERIFIED-SNIPPET, https://tldraw.dev/sdk-features/accessibility): claims WCAG 2.2 AA, a live region announcing selection, option to disable shortcuts that conflict with assistive tech, a reduce-motion toggle.
- **tldraw sync** (VERIFIED-SNIPPET, https://tldraw.dev/sdk-features/collaboration): `@tldraw/sync` is a WebSocket room backend with reconnect and conflict resolution; self-host (Cloudflare template). Not CRDT in the Yjs sense per these snippets (INFERRED: server-authoritative record sync).
- **Excalidraw file format** (VERIFIED-SNIPPET, https://docs.excalidraw.com/docs/codebase/json-schema, redirects to https://plus.excalidraw.com/docs/sdk/codebase/json-schema): top-level `elements`, `appState`, `files`; elements have id/type/x/y/width/height/angle; line/arrow need a `points` array. Official schema page is partly incomplete per the search summary.
- **Excalidraw text-to-diagram** (VERIFIED-SNIPPET, https://docs.excalidraw.com/docs/@excalidraw/mermaid-to-excalidraw/api): `parseMermaidToExcalidraw` returns a "skeleton" format; `convertToExcalidrawElements` expands it to full elements. Unsupported Mermaid falls back to an embedded image. The skeleton idea (agent writes minimal fields, engine fills defaults, ids, bindings) is directly reusable.
- **Excalidraw collaboration** (VERIFIED-SNIPPET, https://plus.excalidraw.com/blog/end-to-end-encryption): E2E encrypted rooms; key lives in the URL fragment so the server never sees it.
- **Figma accessibility** (VERIFIED-SNIPPET, https://www.figma.com/blog/building-accessibility-into-a-canvas-based-product/): a hidden DOM/accessibility-tree mirror of canvas nodes keeps selection and screen-reader focus in sync.
- **Miro** (VERIFIED-SNIPPET, https://miro.com/accessibility/): WCAG 2.2 audit covering keyboard, screen reader, contrast, zoom. No editing-feature findings.
- **draw.io format** (VERIFIED-SNIPPET, https://www.drawio.com/doc/faq/ai-drawio-generation): `.drawio` is XML (`mxfile` > `diagram` > `mxGraphModel`); default save is deflate+base64 compressed; draw.io explicitly tells AI generators to emit uncompressed XML. Lesson: a compressed default is hostile to agents; ship an uncompressed canonical form.
- **Text diagram languages** (VERIFIED-SNIPPET, https://dev.to/levi_liu/diagrams-as-code-in-2026-mermaid-plantuml-d2-excalidraw-when-to-use-what-4mo9 and https://d2lang.com/tour/faq/; blog-grade evidence, no benchmarks): Mermaid renders natively on GitHub but auto-layout is reported to degrade past roughly 10 to 15 nodes; D2 has dagre/ELK/TALA layout engines; PlantUML needs Java and Graphviz. None of these express metric geometry; they are topology-only.

### Mobile CAD / sketch
- **Shapr3D** (VERIFIED-PAGE, https://shapr3d.com/product/cad-constraints): constraints Parallel, Perpendicular, Tangent, Coincident, Midpoint, Concentric, Horizontal/Vertical, Equal, Symmetry. Auto-constraining while sketching (H/V, perpendicular, tangent, coincident) can be switched off. Adaptive UI offers only constraints valid for the selection. Dimensional constraints take typed values. Color state: green = fully defined, blue = under-defined, conflicts = over-defined and must be resolved. Live preview while dragging. Pencil optimized but mouse/trackpad and Wacom work. Page lists no touch gestures.
- **Shapr3D solver** (VERIFIED-SNIPPET, https://blogs.sw.siemens.com/plm-components/shapr3d-for-ipad-pro-implements-d-cubed-geometric-constraint-solving/): uses Siemens D-Cubed 2D solver; stylus relation to geometry drives auto-constraints. 2016 coverage says dimensions are selection-driven ("click a line, get a length dimension") (https://fabbaloo.com/2016/12/shapr3d-for-ipad-pro-adds-sketch-constraints-dimensioning-and-more).
- **FreeCAD Sketcher** (VERIFIED-SNIPPET, https://wiki.freecad.org/Sketcher_reference): geometric vs dimensional constraints; driving vs reference (non-driving, display-only) dimensions; DoF accounting (a free line has 4 DoF); diagnostic selectors "Select Under-Constrained / Redundant / Conflicting".
  - Lesson: "reference dimension" (shows a measured value without driving geometry) is the key concept for measured sketches where the user measured a wall and wants it displayed without forcing the solver.
- **Concepts** (VERIFIED-SNIPPET, https://apps.apple.com/au/app/concepts/id560586497): vector strokes; scale and measurement tools; paid tiers unlock shape guides, custom grids, PNG/PSD/SVG/DXF export. Reviews complain guides are fiddly (moving a guide sometimes draws) and shapes are weak for architectural scale. Morpholio Trace: no feature-level data found (GAP).
- **Onshape/Fusion mobile, Nomad, FluidCAD**: not researched (GAP).

### Room / floor-plan capture
- **magicplan** (VERIFIED-SNIPPET, https://apps.apple.com/au/app/magicplan/id427424432): LiDAR/AR scan, auto-detect doors/windows, "import and draw" over an existing plan; exports PDF, JPG, PNG, SVG, CSV, DXF. Paid tiers gate exports.
- **Polycam** (VERIFIED-SNIPPET, https://poly.cam/features/floorplan): LiDAR devices only; layered DXF; metric/imperial toggle before export; DXF is paid.
- **Apple RoomPlan** (VERIFIED-SNIPPET, https://developer.apple.com/documentation/roomplan/capturedroom/usdexportoptions.md and https://developer.apple.com/forums/thread/831648): exposes doors/windows/objects with dimensions; only the parametric USD export keeps door/window size editable; export is one-way (cannot re-import to RoomPlan types). Lesson: capture is an input source; our format must be the system of record.
- Canvas, Floorplanner, Planner 5D, SketchUp Mobile Viewer/Free: not found (GAP).

### AI-assisted sketching
- **Blueprint-Bench** (VERIFIED-SNIPPET, https://arxiv.org/abs/2509.25229): photos to 2D plan; most frontier models at or below a random baseline; iterative agents did not help.
- **Sketch2BIM** (VERIFIED-SNIPPET, https://aitopics.org/doc/arxivorg:659C4DF4): human-in-the-loop on unscaled hand-drawn plans; openings reliable first pass, wall detection starts around 83% and improves with feedback.
- Takeaway (INFERRED from the two above): LLMs are decent at topology, poor at metric dimensions. Never let an AI invent numbers; mark AI-derived dimensions as "unverified" and let the user confirm with typed values.

### Drafting conventions (sources are teaching material, not the standards)
- Aligned text (parallel to dimension line) is typical for architectural/structural; unidirectional (horizontal, read from bottom) is the current ANSI/ASME mechanical preference (VERIFIED-SNIPPET, https://www.mcgill.ca/engineeringdesign/node/639, https://workforce.libretexts.org/Courses/Northeast_Wisconsin_Technical_College/Technical_Sketching_(NWTC)/07%3A_Dimensioning/7.02%3A_Principles_of_Dimensioning).
- Chain vs baseline: mathematically equivalent; baseline when distance to a datum matters, chain when feature spacing matters (same sources). Chains accumulate tolerance (INFERRED).
- Extension lines have a visible gap from the object; at least 10 mm (3/8") from object to first dimension line and 6 mm (1/4") between dimension lines (VERIFIED-SNIPPET, https://learn.vcs.net/pluginfile.php/406146/mod_resource/content/1/Dimensioning%20Guidelines.pdf; course handout, check against ASME Y14.5).
- Arrowhead may be closed/filled arrow, dot, or tick (VERIFIED-SNIPPET, same search). Architectural tick/slash is conventional (INFERRED).
- Architectural: exterior walls often dimensioned to stud face, interior walls to centerline (VERIFIED-SNIPPET, lecture note in the same results). Verify against local practice.
- Not found: ft-in formatting rules, leading-zero rule, scale bars, north arrow, title blocks, line weights, 1/4"=1' scale mechanics. Statements about these below are INFERRED.

### Interop / export
- DXF units live in header `$INSUNITS` (1 = inches, 4 = mm, 5 = cm, 6 = m, 0 = unitless); `$LUNITS`/`$AUNITS` only affect display; real-world importers are inconsistent about honoring it (VERIFIED-SNIPPET, https://ezdxf.readthedocs.io/en/stable/concepts/units.html, https://dev.opencascade.org/doc/mantis-archive/32512/index.html).
- DXF DIMENSION entity, IFC, SVG metadata round-trip: GAP.
- Palm rejection: Android exposes `FLAG_CANCELED` on pointer-up/cancel for API 33+ and expects apps to keep an undoable action history so rejected palm strokes can be rolled back (VERIFIED-SNIPPET, https://developer.android.com/develop/adaptive-apps/cookbook/stylus-palm-rejection). Web: a forum report says browsers may suppress touch while the pen is active and authors cannot control it (anecdotal, https://developer.apple.com/forums/thread/773213).
- CRDT/offline: Yjs is network-agnostic and supports offline editing (VERIFIED-SNIPPET, https://github.com/yjs/yjs/). No evidence found that tldraw or Excalidraw use it.

## 2. Capability matrix

Legend: Y = documented (source above), P = partial/limited, ? = not researched or no evidence, n/a = not applicable. Cells derive from the Findings section only; blanks as "?" are gaps, not "No".

| Capability | Excalidraw | tldraw | Figma/FigJam | draw.io | Miro | Shapr3D | FreeCAD Sketcher | Concepts | magicplan | Polycam | SteleKit v2 target |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Snap to shapes/grid | ? | Y (default off, modifier) | ? | ? | ? | Y (auto-constraint) | Y | P (guides) | ? | ? | default-on, dp threshold |
| Gap/spacing indicators | ? | Y | ? | ? | ? | ? | ? | ? | ? | ? | yes |
| Geometric constraints | no (INFERRED) | no (INFERRED) | no (INFERRED) | no (INFERRED) | no (INFERRED) | Y (9 types) | Y | ? | ? | ? | small subset |
| Typed dimensional constraints | no (INFERRED) | no (INFERRED) | no | no | no | Y | Y (driving) | P (scale/measure) | P | P | Y (pills) |
| Reference (non-driving) dims | ? | ? | ? | ? | ? | ? | Y | ? | ? | ? | yes |
| Under/over-defined state color | n/a | n/a | n/a | n/a | n/a | Y (green/blue) | Y (diagnostics) | n/a | n/a | n/a | yes |
| Real-world scale / units | no (INFERRED) | no (INFERRED) | no | no | no | Y | Y | Y | Y | Y (metric/imperial) | ft-in/in/decimal |
| Stylus-first / pencil | P | ? | ? | ? | ? | Y | n/a (desktop) | Y | ? | ? | touch + stylus |
| Accessibility claims | ? | Y (WCAG 2.2 AA claim) | Y (a11y tree mirror) | ? | Y (WCAG 2.2 audit) | ? | ? | ? | ? | ? | needed |
| Real-time collab | Y (E2E) | Y (sync) | Y (INFERRED) | Y (INFERRED) | Y (INFERRED) | ? | no | ? | P (INFERRED) | ? | later |
| Text-first / agent format | Y (JSON + skeleton) | Y (JSON store, INFERRED details) | no | Y (XML, compress default) | no | no | Y (file is text, INFERRED) | no | no | no | Y (required) |
| Mermaid import | Y (lib) | ? | ? | Y (INFERRED) | ? | no | no | no | no | no | optional |
| Exports | SVG/PNG/JSON (INFERRED) | SVG/PNG/JSON (INFERRED) | many | many (INFERRED) | many | STEP etc. (INFERRED) | DXF/SVG (INFERRED) | PNG/PSD/SVG/DXF | PDF/JPG/PNG/SVG/CSV/DXF | layered DXF | SVG, PNG, PDF-at-scale, DXF |
| Capture (LiDAR/AI) | no | no | no | no | no | no | no | no | Y | Y | out of scope; import-friendly |

## 3. Fifteen behaviours that matter most for phone-first measured sketching

Ranked roughly by impact. Source tag in brackets.

1. **Typed value is the source of truth; dragging is a gesture to propose it.** Shapr3D and FreeCAD both turn a selection into a typed dimension. (Shapr3D VERIFIED-PAGE; FreeCAD VERIFIED-SNIPPET)
2. **Reference vs driving dimension.** A user measures a wall in the field and wants to record it without the solver moving things. (FreeCAD VERIFIED-SNIPPET)
3. **Visible constraint state per shape** (under-defined / fully defined / conflicting) with a one-tap explanation of what is missing. (Shapr3D VERIFIED-PAGE; FreeCAD diagnostics VERIFIED-SNIPPET)
4. **Auto-constraint on draw with a global off switch, and adaptive offering of only valid constraints.** (Shapr3D VERIFIED-PAGE)
5. **Snap is on by default on touch, with gap/spacing indicators and a dp-based threshold.** tldraw's modifier-key model cannot transfer. (tldraw VERIFIED-PAGE)
6. **Loupe / offset touch point** so the finger does not occlude the target (INFERRED; your loupe feature already addresses this; no source opened).
7. **Live preview while dragging** a constrained element. (Shapr3D VERIFIED-PAGE)
8. **Unlimited, reliable undo that survives rejected palm touches.** Android explicitly expects history-based rollback. (Android VERIFIED-SNIPPET)
9. **Stylus and finger as distinct roles** (pen draws/places, finger pans/zooms) with a settings override, since web cannot control browser touch suppression. (Apple forum anecdotal; Android VERIFIED-SNIPPET)
10. **Units are a document property plus a per-dimension display format** (ft-in fractional, decimal in, mm), with exact internal storage (INFERRED; DXF `$INSUNITS` inconsistency shows the cost of ambiguous units (VERIFIED-SNIPPET)).
11. **True-scale view and print**: a stated drawing scale (e.g. 1/4"=1') and a scale bar so a printout can be checked with a physical ruler. Concepts reviewers do exactly this by hand. (Concepts VERIFIED-SNIPPET)
12. **Keep dimensions attached to geometry** (persistent, re-flow when geometry changes) rather than free text. Your elevation-style persistent dimensions cover this (INFERRED need).
13. **Text-first canonical file that agents can read and write without a renderer**, with a minimal "skeleton" authoring form that the engine expands. (Excalidraw VERIFIED-SNIPPET; draw.io compression warning VERIFIED-SNIPPET)
14. **Accessible canvas**: a parallel semantic tree (list of objects, dimensions, constraint state) with keyboard navigation and live-region announcements. (Figma, tldraw VERIFIED-SNIPPET)
15. **Layered, unit-correct vector export** (SVG, PDF at scale, DXF with `$INSUNITS` set and layers for walls/dimensions/text). (Polycam, magicplan, Concepts VERIFIED-SNIPPET)

## 4. Drafting conventions to adopt

Sources are teaching-grade; confirm against ASME Y14.5 / NCS before claiming compliance. Items marked INFERRED were not sourced this session.

- **Dimension anatomy**: extension lines with a visible gap from the object (about 1.5 mm / 1/16"), dimension line between them with arrows or architectural ticks, text centered above the line, not breaking it. Offsets: first dimension line at least 3/8" (10 mm) from the object, subsequent at least 1/4" (6 mm) (VERIFIED-SNIPPET, course handout). Translate to screen-space dp that stay constant when zooming, so pills remain legible (INFERRED).
- **Text orientation**: default aligned (architectural), with an option for unidirectional (mechanical/woodworking shop drawings). Never upside down: flip text that would read from the right edge (INFERRED).
- **Chain vs baseline** as a per-dimension-group choice, with a warning that chains accumulate error. Offer "convert to baseline" (INFERRED UX; concept VERIFIED-SNIPPET).
- **Wall dimension reference**: let the user choose face / centerline per dimension; default exterior = face, interior = centerline per the architectural note (VERIFIED-SNIPPET).
- **Units formatting** (all INFERRED, no source): architectural feet-inches as `12'-6 1/2"` with fractions reduced to a user-set precision (1/16 default, 1/32 option); inches-only as `150 1/2"`; decimal inches with consistent places; metric mm integers. Stored value exact (rational or integer in a base unit such as 1/64" or 0.1 mm), display rounded, and show an indicator when the display is rounded.
- **Line weights** (INFERRED): at least three: heavy for cut/outline walls, medium for objects, light for dimensions/extension/hatch. Weight is semantic (a style role), not a free stroke width, so export maps to DXF layers and print weights.
- **Drawing scale, scale bar, north arrow, title block** (INFERRED): scale is a property of a sheet/view, not of shapes; an elevation and a plan in the same file can have different scales. Scale bar auto-updates; north arrow only for plans; a minimal title block (title, date, scale, units, author) for PDF export.
- **Elevations vs plans** (INFERRED): a view type tag (plan, elevation, section, detail) that controls default orientation, dimension style, and labeling.
- **Over-constraint policy**: warn, do not silently fix; allow a measurement to be demoted to reference (FreeCAD pattern, VERIFIED-SNIPPET).

## 5. Format / interop lessons for an agent-readable format

1. **Two layers**: a minimal "skeleton" authoring format (agents write intent: rooms, walls with lengths, dimensions, labels) and a canonical expanded document (ids, resolved geometry, constraint state). This mirrors Excalidraw's skeleton to elements conversion (VERIFIED-SNIPPET).
2. **Uncompressed, diff-friendly text as canonical** (JSON or line-oriented). draw.io's compressed default is the counterexample and its docs tell AI to avoid it (VERIFIED-SNIPPET). Store in the Logseq-style markdown graph naturally as a fenced block or sidecar (INFERRED fit with SteleKit).
3. **Constraints and dimensions are first-class data**, not drawn text. Mermaid, D2, PlantUML are topology-only (VERIFIED-SNIPPET, blog-grade), so agents using them cannot express "wall is 12'-6"". An agent can emit `dimension(a, b, 12'-6")` and the solver does the layout. This is the main differentiator against every text-diagram format.
4. **Exact units in the file**: declare `units` and store lengths as integers in a base unit; also keep the original text (`"12'-6 1/2\""`) for round-trip fidelity (INFERRED; DXF unit inconsistencies are the cautionary tale, VERIFIED-SNIPPET).
5. **Stable ids and a schema version**, with unknown-field preservation so older/newer agents do not destroy data (Excalidraw carries a version and source field, VERIFIED-SNIPPET; preservation is INFERRED best practice).
6. **Provenance flag per value**: `measured`, `typed`, `derived`, `ai-estimated`. Motivated by LLM weakness on metrics (Blueprint-Bench, Sketch2BIM VERIFIED-SNIPPET).
7. **Validation output an agent can read**: a text report listing under-defined, over-defined and conflicting items (FreeCAD diagnostics concept VERIFIED-SNIPPET). Lets an agent iterate without pixels.
8. **Import**: Mermaid to skeleton for whiteboard topology, with fallback to embedded image like Excalidraw (VERIFIED-SNIPPET). Optional: Excalidraw JSON import.
9. **Export**: SVG with semantic ids/classes and a `<metadata>` or data-attribute payload carrying the canonical document so SVG round-trips (INFERRED, no source; test that editors preserve it). PDF at true scale; DXF with `$INSUNITS`, layers, and either native DIMENSION entities or exploded lines plus text (DIMENSION entity support not researched).
10. **Capture tools are inputs, not formats**: RoomPlan export is one-way (VERIFIED-SNIPPET); import room outlines as geometry marked `ai-estimated` or `scanned`.

## 6. Table stakes vs differentiators (ranked)

### Table stakes (users will bounce without these)
1. Reliable undo/redo including palm-rejection rollback (items 8).
2. Snap with visible indicators, default-on on touch.
3. Typed numeric entry for any length/angle; exact unit parsing (`12'6 1/2`, `150.5in`, `3820mm`).
4. Pan/zoom/rotate gestures that do not conflict with drawing; stylus vs finger roles.
5. Autosave, offline, and non-lossy local file.
6. Export SVG and PNG; PDF with correct page scale.
7. Basic accessibility: labels, keyboard path, contrast, reduced motion (tldraw/Figma/Miro set the bar, VERIFIED-SNIPPET).
8. Text autosize and legible dimension text at any zoom.
9. Grouping, alignment, distribute, duplicate-with-offset (INFERRED standard whiteboard set).
10. Connectors/arrows bound to shapes for diagram mode (INFERRED standard; tldraw arrow bindings VERIFIED-SNIPPET).

### Differentiators (world-class for measured phone sketches)
1. Persistent, solver-backed dimensions with driving and reference modes and a green/blue/red definition state (Shapr3D+FreeCAD capability, absent from whiteboards).
2. Agent-readable skeleton plus canonical format with validation report and provenance flags.
3. True-scale print and scale bar; per-view drawing scale.
4. Elevation-style linked dimensions and plan/elevation view types.
5. Loupe plus snap-assist tuned for fingers; dp-based tolerances.
6. Layered DXF with correct `$INSUNITS` and semantic layers.
7. Accessible semantic tree that lists objects and dimensions (screen-reader users can verify numbers; few canvas tools do more than claim compliance).
8. Furniture/woodworking helpers (INFERRED, no source): cut-list export from dimensioned parts, standard lumber sizes, clearance checks.
9. Import of scans (RoomPlan/magicplan DXF/SVG) as editable constrained geometry, with AI-estimated flags.
10. E2E-encrypted or local-first collab (Excalidraw model VERIFIED-SNIPPET; Yjs for offline merge VERIFIED-SNIPPET), deferred until single-user is excellent.

## 7. Recommended next verification steps

- Open primary pages for the GAP items: Onshape/Fusion mobile sketch UX, Morpholio Trace, SketchUp Mobile, Floorplanner, Planner 5D, Canvas.
- Read ASME Y14.5 and the US National CAD Standard for line weights, title blocks, leading zeros; the sources here are course notes.
- Check DXF DIMENSION entity and SVG metadata preservation by round-tripping through Inkscape and Figma.
- Test web pointer-event behaviour (`pointerType === "pen"`, touch suppression) on Safari/iPad and Chrome/Android hardware; no official spec source was read.
