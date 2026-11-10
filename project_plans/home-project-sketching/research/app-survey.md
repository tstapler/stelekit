# App Survey: home-project sketching, dimensioning, annotation, interchange

**Date**: 2026-11-09 | **Method**: WebSearch (standard), one pass per app; secondary sources and conflicts are flagged. Claims without a URL are marked INFERRED. Prices drift: re-verify before any buy decision.

## 1. Per-app findings

### Magicplan (mobile scan/sketch, floor plans)
- **Does well**: room/floor-plan capture and reports; 2D/3D exports. Its help site documents sketch PDF export in SVG/JPG/PNG, plus statistics export in PDF/CSV and estimate export in PDF/XLS ([export formats](https://help.magicplan.app/export-formats)). The subscription page lists PDF, JPG, PNG, SVG, CSV, DXF, OBJ, USDZ, IFC ([subscriptions](https://help.magicplan.app/magicplan-pro-subscriptions)).
- **Dimension model**: measured/scanned rooms with dimension labels (INFERRED from the sketch-plan docs, [sketch plan](https://help.magicplan.app/sketch-plan)); no documented constraint solver.
- **Offline**: vendor says offline mode stores changes locally and syncs later ([export your projects](https://help.magicplan.app/export-your-projects) region; claim surfaced via search from magicplan help); a competitor page claims otherwise ([Plan7Architect](https://plan7architect.com/?p=65750)), so treat as vendor-claimed.
- **Price**: UNVERIFIED. Old App Store one-off DXF purchases ($4.49 per plan) are listed on [App Store](https://apps.apple.com/au/app/magicplan/id427424432); which tier unlocks DXF is unconfirmed.
- **SteleKit lacks**: room/floor-plan model, DXF/IFC export, CSV of dimensions, scan capture.

### Moasure (motion-based measuring hardware plus app)
- **Does well**: free export of PDF, DXF (2D flattened or 3D with elevation), CSV (raw x,y,z coordinates), and images (PNG/JPG/SVG) ([export a measurement](https://docs.moasure.com/en/moasure-app/export-a-measurement), [no more subscriptions](https://www.moasure.com/blogs/news/no-more-subscriptions-its-now-free-to-export-measurements)). Scaled PDFs exist ([blog](https://moasure.com/blogs/news/scaled-pdfs-improved-exports-enhanced-moasure-coach-1)).
- **Dimension model**: point/polyline coordinates from a hardware device; the app is a companion to the device ([App Store](https://apps.apple.com/us/app/-/id1561726410)).
- **Price**: app free; hardware extra. A UK product page still describes an optional subscription for export ([Moasure ONE](https://uk.moasure.com/products/moasure-one)); likely outdated, date unconfirmed.
- **Relevance to a closet**: low (needs hardware, outdoor/large-area oriented, INFERRED). Useful as a model for CSV-of-coordinates export.
- **SteleKit lacks**: polyline-in-real-units model, DXF/CSV export.

### Shapr3D (iPad/desktop parametric solid CAD)
- **Does well**: direct modeling with STEP/IGES/Parasolid interchange. The official pricing page lists free-tier import of XT/STEP/IGES/DWG/STL with only low-res STL/3MF export; Pro lists STEP, DXF, DWG, PDF, SVG and more ([pricing](https://shapr3d.com/pricing)).
- **Price**: Pro $299/yr per seat per the pricing page snippet; a third-party site gives a different monthly figure ([softwarefinder](https://softwarefinder.com/design-software/shapr3d)).
- **Offline/local-first**: cloud storage in the free plan per pricing; offline behaviour UNVERIFIED.
- **Relevance**: overkill for closet elevations; useful as a downstream consumer of DXF/STEP.
- **SteleKit lacks**: solid modeling (out of scope), STEP, parametric constraints.

### SketchUp (Free web, Go, Pro)
- **Does well**: fast 3D push-pull modeling, large model warehouse (INFERRED).
- **Export**: forum reports say DXF/DWG export on web needs a paid tier; free-tier STL export is contradictory across sources ([forum: DXF on web](https://forums.sketchup.com/t/creating-a-dxf-using-web/184471), [forum: STL on free](https://forums.sketchup.com/t/how-can-i-import-export-stl-files-in-the-free-web-sketchup-version/102467.md)). Free is non-commercial, web-only, no offline ([review](https://machow2.com/?p=15711)); official per-tier format list not found.
- **SteleKit lacks**: 3D, DXF/DWG.

### Floorplanner (web floor-plan designer)
- Free "Basic" projects give SD, watermarked 960x540 images only ([pricing](https://floorplanner.com/pricing), via search summary); PDF/FML/DXF reportedly paid via credits ([review](https://floorplancreator.net/pricing) is a different product; evidence for Floorplanner DXF is a single third-party source, so INFERRED). A DesignFiles comparison says no DXF/DWG ([comparison](https://blog.designfiles.co/planner-5d-versus-floorplanner/)); conflict unresolved.
- Cloud-only (INFERRED). Low fit.

### Planner 5D (consumer design, 2D/3D)
- Permanent free plan with half the catalog and limited renders; Professional (~$33/mo annual, $49.99 monthly) lists "Export to CAD"; Premium turns uploaded plans into 3D ([pricing](https://planner5d.com/pricing), [Capterra](https://www.capterra.com/p/164022/Planner-5D/pricing/)). Free-tier CAD export is unverified/conflicting. Cloud-synced projects. Low fit.

### Sweet Home 3D (free, GPL, desktop Java)
- Open source GPL ([sweethome3d.com](https://www.sweethome3d.com/?p=420)); exports 2D plan to SVG and 3D to OBJ; imports OBJ/DAE/KMZ/3DS models and image blueprints, but not DXF/DWG per its author ([forum thread](https://www.sweethome3d.com/support/forum/viewtopic.php?p=15271), [features](https://test.sweethome3d.eu/features.jsp)).
- Fully local, free: the closest local-first analogue. Dimension lines are drawn objects; no constraint solver (INFERRED).
- **SteleKit lacks**: room/wall primitives with auto dimension lines, plan-to-SVG.

### Apple Measure / RoomPlan (LiDAR)
- RoomPlan: Swift API producing a parametric room model with dimensions of walls/openings/furniture, exportable to USD/USDA/USDZ only; JSON via `Encodable` `CapturedRoom` ([Apple RoomPlan](https://developer.apple.com/augmented-reality/roomplan), [forum on export formats](https://developer.apple.com/forums/thread/708791)). iOS-only and out of scope per requirements; the measurement-semantic JSON idea is reusable. Apple's Measure app itself: not researched (UNVERIFIED).

### Google ARCore "Measure"-type apps
- Google retired its ARCore Measure app in June 2021, citing instability and inaccurate measurements ([9to5Google](https://9to5google.com/2021/06/08/google-measure-ar-app-sunset/), [Engadget](https://www.engadget.com/google-ends-support-augmented-reality-measure-app-075038678.html)). Lesson: AR tape measures are low-trust inputs; keep manual typed dimensions authoritative and tag AR-sourced values with lower confidence (recommendation, INFERRED).

### FreeCAD (Sketcher + TechDraw) and CadQuery
- FreeCAD Sketcher offers dimensional constraints (distance, horizontal/vertical distance, radius, diameter, angle, lock) and geometric ones ([Sketcher Dimension](https://wiki.freecad.org/Sketcher_Dimension)); TechDraw exports pages to SVG, DXF, PDF ([Generating 2D drawings](https://wiki.freecad.org/Manual:Generating_2D_drawings)). Free/open source, fully local. Whether STEP preserves sketch constraints: not established (UNVERIFIED; STEP is geometry-only, INFERRED).
- CadQuery (the owner's pipeline): `cq.exporters.export` handles SVG, DXF (via ezdxf), STEP, STL, 3MF, glTF; SVG is a wireframe-style view ([CadQuery import/export](https://cadquery.readthedocs.io/en/stable/importexport.html), [dxf exporter source](https://cadquery-ja.readthedocs.io/ja/latest/_modules/cadquery/occ_impl/exporters/dxf.html)). Implication: CadQuery SVG has no semantic dimension labels unless the owner's script adds them (matches the requirements' feasibility risk).

### Excalidraw
- MIT, open source, local-first PWA; `.excalidraw` is plain JSON; exports PNG/SVG ([JSON schema](https://docs.excalidraw.com/docs/codebase/json-schema), [Wikipedia](https://en.wikipedia.org/wiki/Excalidraw), [repo](https://github.com/excalidraw/excalidraw/)). No units or scale; not dimension-aware. Useful as a format precedent (JSON scene + SVG with embedded scene).

### tldraw
- `.tldr` JSON files with snapshot save/load ([TLDRAW_FILE_EXTENSION](https://tldraw.dev/reference/tldraw/TLDRAW_FILE_EXTENSION), [CLI](https://npmjs.com/package/@kitschpatrol/tldraw-cli)); SDK production use needs a license key (commercial license or free hobby license) ([license key](https://tldraw.dev/sdk-features/license-key)). Web/React SDK: not embeddable in Compose without a WebView (INFERRED). No dimension model.

### Obsidian Canvas / JSON Canvas and Logseq whiteboards
- JSON Canvas 1.0: MIT open spec, nodes (text/file/link/group) plus edges; intentionally conservative ([jsoncanvas.org](https://jsoncanvas.org/), [Obsidian blog](https://obsidian.md/blog/json-canvas/)). Positions only; no units.
- Logseq whiteboards are built on tldraw; a forum thread requests JSON Canvas for them ([discuss.logseq.com](https://discuss.logseq.com/t/use-json-canvas-format-for-whiteboards/25692/3)). On-disk format not confirmed by this search (UNVERIFIED).

### Concepts and Morpholio Trace (tablet sketching)
- Concepts: infinite canvas with scale/measure tools and snap/grid; free Basics tier; PDF/SVG/PNG/DXF export, with PDF/DXF-class export gated behind a purchase or subscription ([subscription](https://concepts.app/subscription), [architects](https://concepts.app/en/architects/), [illustrarch](https://illustrarch.com/?p=107650)). Closest UX match to "annotate a drawing to scale".
- Morpholio Trace: tracing-paper sketching over imported plans, scale set per sheet, PDF/image/DXF export reported; DXF and scale reportedly subscription-gated, price sources conflict ([illustrarch review](https://illustrarch.com/articles/design-softwares/93700-morpholio-trace-review.html), [SoftwareSuggest](https://www.softwaresuggest.com/morpholio-trace/pricing)).

### Onshape (cloud CAD, mobile)
- Exports STEP, and sketches/planar faces to DWG/DXF ([exporting files](https://cad.onshape.com/help/Content/exporting-files.htm)); free tier documents are public and non-commercial ([pricing](https://onshape.com/pricing)); mobile app requires internet ([App Store](https://apps.apple.com/be/app/onshape-3d-cad/id923421284)). Fails the confidential/local-first constraint.

### CAD Sketcher (Blender add-on)
- Constraint-based 2D sketcher on SolveSpace solver (tangent, distance, angle, equal); GPL v3 ([repo](https://github.com/hlorus/CAD_Sketcher), [LinuxLinks](https://www.linuxlinks.com/cad-sketcher-constraint-based-geometry-sketcher/)). Reference design for the "tiny solver" UX: constraints are first-class objects shown on-canvas, solved incrementally.

## 2. Cross-cutting observations
1. Only Magicplan, Moasure, Concepts, Trace and Shapr3D sell DXF; most gate it behind paid tiers (sources above). Sweet Home 3D, Excalidraw, FreeCAD, CadQuery are free/local.
2. No surveyed consumer tool reconciles a dimension across a table, a drawing, and prose. That gap is SteleKit's differentiator (INFERRED from absence in the sources above; not proven).
3. Constraint-solving UIs (FreeCAD Sketcher, CAD Sketcher) show named dimensions and driven/driving constraints; the requirements' "sum-to-total/equal/offset" subset matches this model.
4. Everything geometric interoperates through DXF (2D) or STEP (3D); everything diagram-like through SVG or JSON scene files.

## 3. Ranked missing features (solo DIY closet remodel)
Value: 5 high. Cost: relative to existing SteleKit annotation/asset stack (S/M/L).

| Rank | Feature | Value | Cost | Notes |
|---|---|---|---|---|
| 1 | Named dimensions + provenance + conflict detection across table/drawing/YAML/prose | 5 | M | Unique; no surveyed app does it; core of success metric 1 |
| 2 | Exact-fraction rational dimension type (1/16", FEET_INCHES) | 5 | S | Prerequisite for everything; Rabbit Hole already noted |
| 3 | Blank-canvas plan/elevation/section sheet with snapped lines/rects and typed dimensions | 5 | M-L | Concepts/Sweet Home 3D parity |
| 4 | Sum/equal/offset constraints (tiny solver) | 4 | M | FreeCAD/CAD Sketcher model, restricted subset |
| 5 | SVG export with `data-*` dimension attributes | 4 | S | Cheap, self-describing, round-trips CadQuery |
| 6 | Per-sheet scale + calibration from a known dimension on imported drawing | 4 | M | Concepts/Trace pattern; extends current calibration |
| 7 | JSON model export (LLM-facing, schema-gated) | 4 | S | Required by metric 3 |
| 8 | CSV dimension table export/import | 3 | S | Moasure/Magicplan precedent |
| 9 | DXF export (2D lines, dimensions as text) | 3 | M | Needed only if owner hands to a CAD/CNC/vendor tool; via ezdxf-like writer |
| 10 | Layers per sheet | 3 | S-M | Concepts has 5 free layers |
| 11 | PDF export at scale | 3 | M | Print for shop use; platform PDF APIs differ |
| 12 | OCR of dimension text in PNG | 2 | L | Rabbit hole per requirements |
| 13 | AR/LiDAR capture (RoomPlan, ARCore) | 2 | L | Platform-specific, iOS out of scope, Google retired Measure |
| 14 | STEP / 3D | 1 | L | Out of scope for a closet elevation |

## 4. Interchange-format recommendation
1. **Primary: SVG with `data-*` dimension attributes** (e.g. `data-dim-id`, `data-value`, `data-unit`, `data-source`). Viewable everywhere, diffable text, stores nicely beside wiki assets, and CadQuery already emits SVG ([CadQuery docs](https://cadquery.readthedocs.io/en/stable/importexport.html)); consider a small metadata block carrying the full model so the SVG round-trips (precedent: Excalidraw embeds scene data in exports; INFERRED).
2. **Sidecar JSON model** (rational values as numerator/denominator strings, provenance, tolerance) as the canonical fused model and LLM artifact. Format precedent: `.excalidraw`, `.tldr`, JSON Canvas are all JSON ([Excalidraw](https://docs.excalidraw.com/docs/codebase/json-schema), [JSON Canvas](https://jsoncanvas.org/)).
3. **CSV** for the dimension table (cheap, spreadsheet/wiki friendly; Moasure and Magicplan export CSV: [Moasure](https://docs.moasure.com/en/moasure-app/export-a-measurement), [Magicplan](https://help.magicplan.app/export-formats)).
4. **DXF as optional later export only** (2D, flatten, units in inches). Magicplan, Moasure, Concepts, FreeCAD TechDraw all export it, so it is the de-facto 2D CAD handoff ([FreeCAD](https://wiki.freecad.org/Manual:Generating_2D_drawings), [Concepts](https://illustrarch.com/?p=107650)). Do not accept DXF import first: Sweet Home 3D, a mature local tool, skips it ([forum](https://www.sweethome3d.com/support/forum/viewtopic.php?p=15271)).
5. **Skip**: STEP, IFC, USDZ, `.tldr`, JSON Canvas for dimensioned content (no units). JSON Canvas/`.tldr` could only serve as a loose-layout export for whiteboard-style pages.
6. **Import**: CadQuery SVG (calibrate from named dimensions) and `closet.yaml`; Magicplan/Moasure CSV optional follow-ups.

Answer to the requirements open question: **both SVG (primary, human) and JSON (canonical, machine)**; DXF later and export-only.

## 5. Gaps in this survey
- Apple Measure app, Floorplanner and Planner 5D free-tier export details, Magicplan current prices, SketchUp official per-tier export matrix, and Logseq whiteboard on-disk format were not confirmed from primary sources.
