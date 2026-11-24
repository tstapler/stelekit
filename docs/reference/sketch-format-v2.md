# SteleKit sketch format v2: reference for agents

Audience: an AI agent (or script) that works on a graph's markdown and sketch files without the SteleKit app.
Scope: read a sketch, render it to an image, create or change one. Format version: **sheet schema 2**, **authoring spec 1**.
Machine-readable companion: [`sketch-scene-v2.schema.json`](sketch-scene-v2.schema.json) (JSON Schema, draft 2020-12, shape and types only).
Decisions behind the format: `project_plans/excalidraw-sketch-engine/decisions/ADR-001` (storage), `ADR-002` (units), `ADR-006` (layers).

## 1. The short version

- A sketch is a **project bundle** under the graph: `<graph>/assets/stelekit-sketch/<slug>/` with `project-model.json`, `sheets/<uuid>.v2.json` (the drawing) and `preview.svg`. The slug looks like `proj-1a2b3c`.
- A markdown page points at it with one image line: `![sketch](../assets/stelekit-sketch/proj-1a2b3c/preview.svg)`. A text-only reader sees only that line. To understand the drawing, run `describe` on `sheets/<uuid>.v2.json`; to look at it, run `render`.
- The sheet file is JSON. **Units are 1/16 inch, x grows right (east), y grows down (south).** 12 feet = 2304 units.
- Do not hand-write the canonical bytes or any hash. Write a spec (section 7) and run `build`, or write plain JSON and run `canonicalize`. `validate` explains every problem.

```bash
scripts/sketch-cli build spec.json --out sheet.v2.json --render preview.png   # spec -> sheet, plus a picture
scripts/sketch-cli describe sheet.v2.json                                       # read a sheet as text
scripts/sketch-cli render sheet.v2.json --out look.png --dimensions            # draw it with dimension annotations
scripts/sketch-cli validate sheet.v2.json                                       # errors with element id and line
scripts/sketch-cli canonicalize hand-edited.json --in-place                     # exact canonical bytes + sha256
scripts/sketch-cli place sheet.v2.json --graph ~/notes                          # put it in the graph, print the embed line
```

`scripts/sketch-cli` needs a JDK 21 and runs the repo's Gradle wrapper once to build. Without a checkout: `bazel run //kmp:sketch_cli -- <args>` (use absolute paths; `bazel run` changes directory). Command reference: section 9.

## 2. How a sheet appears in markdown

ADR-001 and `StandaloneSketch.kt` define the embed: an image whose target is the bundle's `preview.svg`, relative to the page (pages and journals both live one folder below the graph root, so `../assets/...`). `preview.svg` is rewritten by the app on every save; `place` writes one too. The page never contains the drawing's data or its sheet uuid.

| You have | Find the sheet by |
|---|---|
| a page with `![sketch](../assets/stelekit-sketch/proj-1a2b3c/preview.svg)` | list `assets/stelekit-sketch/proj-1a2b3c/sheets/`; a project may hold several sheets, `project-model.json` lists them (`sheets[].uuid`, `kind`, `file`) |
| a sheet file `sheets/<uuid>.v2.json` | it declares `"schema":2` in its first bytes |
| a `sheets/<uuid>.json` (no `.v2`) | schema 1, the older primitive format; this tooling does not read it |

## 3. Coordinates, units, scale

- Scene unit = **1/16 inch**, a `Double`. 16 units = 1 in, 192 units = 1 ft. Absolute value limit 268435456.
- Origin is arbitrary. **x increases to the right (east), y increases downward (south).** North is up on a plan.
- Angles are radians. Box rotation is clockwise on screen about the box centre. Arc angles sweep toward increasing angle, which is clockwise on screen because y points down.
- There is no separate "sheet scale" on a real-world sheet: the unit is already physical. A photo underlay is brought to that scale by calibrating it (section 6a). Stroke width and font size are in scene units too (a 4 1/2 inch wall is stroke width 72; 96 is a 6 inch tall font).
- **Abstract diagrams** use the same numbers but nothing is physical: `build` with `"mode": "diagram"` reads bare numbers as plain scene units and draws thin strokes and 20-unit text. Imported `.excalidraw` files map 1 Excalidraw pixel to 1 unit (ADR-002 scale addendum), so a 100 px box is 6 1/4 inches.
- `project-model.json` records `scaleSixteenthsPerUnit` (16) and `unit` ("in") per sheet; they only describe this convention.

Lengths in specs are written as people write them: `12'`, `12' 6"`, `3 1/2"`, `36"`, or a number (inches in plan mode, scene units in diagram mode, photo pixels in photo space). The grammar is the app's own (`units/LengthParser.kt`).

## 4. The bundle and what not to hand-write

```
<graph>/assets/stelekit-sketch/<slug>/
  project-model.json        pretty JSON; names each sheet and its sha256
  sheets/<uuid>.v2.json     the scene (this document, section 5)
  preview.svg               the image the page embeds
```

| Never hand-write | Why | Get it right with |
|---|---|---|
| `fileSha256` in `project-model.json` | a sheet whose bytes do not hash to it is treated as corrupt and the whole project becomes unreadable | `place` (uses the app's `SidecarCodec`) |
| the byte layout of the sheet | the file is *canonical*: `schema` then `sheetUuid` first, every other key sorted, one element per line, shortest-round-trip numbers, no spaces. Equal drawings must give equal bytes so hashes and git diffs agree | `canonicalize` or `build` |
| `sheetUuid` | must be unique per sheet and equals the file name stem | `build` derives one from the spec; or copy an existing one |
| reciprocal `bound` entries on shapes | `reconcile` recreates them on load from each label's `container` and each arrow's bindings | just set `container` / `sb` / `eb` |
| `ver`, `ord`, `nameCounters` | bookkeeping: change counter, auto-name ordinal ("Rectangle 3"), highest ordinal issued per kind | leave out; `canonicalize` keeps what you wrote, `build` assigns them |
| `z` order keys | fractional keys compared as plain strings; ties fall back to array order | leave out (array order then applies) or let `build` assign |

Hand-written JSON may omit every default, use any key order and any whitespace. `validate` reports it as valid with a "not canonical" warning; `canonicalize` produces the exact bytes and prints their sha256.

## 5. The scene file

Top level (all keys but `schema` and `sheetUuid` optional):

| key | meaning |
|---|---|
| `schema` | the number `2` |
| `sheetUuid` | string, the sheet's uuid |
| `elements` | list, **paint order**: later entries draw over earlier ones (within a layer by `z`, then array order) |
| `layers` | list of `{id, name, visible, locked, opacity 0..1, order}`; layer `default` always exists; lower `order` paints first; hidden layers are not drawn or exported |
| `constraints` | list of solver constraints (section 6) |
| `extras` | free JSON kept as written. Known keys: `sheetKind: "technical"` (marks a real-world sheet: constraints on by default), `dimensionsOn: true|false` (optional; whether the sheet shows dimension annotations, see section 6; absent until someone chooses, so existing sheets keep their exact bytes), `agent: {mode, title, spec}` written by `build` |
| `nameCounters` | `{kind: highest ordinal}` |

Every element has `id` (unique, non-empty) and `type`, plus optional base fields:

| field | default | meaning |
|---|---|---|
| `layer` | `default` | layer id; an unknown id moves the element to `default` on load |
| `groups` | none | group ids; a group of one member is removed on load |
| `frame` | none | id of a `frame` element that clips this one |
| `z` | `a0` | paint-order key within the layer |
| `locked` | false | |
| `opacity` | 100 | percent, multiplies with the layer's |
| `style` | see below | |
| `seed` | 0 | seeds the hand-drawn wobble |
| `name` | none | user-visible name, at most 80 characters, unique; otherwise "Kind n" from `ord` |

`style`: `stroke` (`#rrggbb`, default `#1e1e1e`), `bg` (fill colour or `transparent`), `fill` (`Solid` default, `Hachure`, `CrossHatch`, `Zigzag`), `sw` stroke width in units (default 2), `ss` (`Solid`, `Dashed`, `Dotted`), `rough` (0 = clean lines; 1 = hand-drawn wobble in Sketchy style), `round` `{kind, value}`.

| `type` | geometry keys | notes |
|---|---|---|
| `rect`, `diamond` | `x y w h angle` | x,y top-left. Outline is stroked centred on the edge. |
| `ellipse` | `cx cy rx ry angle` | centre and radii; `rx == ry` is a circle |
| `line` | `pts` `[[x,y],...]`, `poly` | `poly: true` closes it and fills with `style.bg` |
| `arrow` | `pts`, `sb`, `eb`, `sah`, `eah`, `elbow` | points are absolute; `sb`/`eb` `{id, fx, fy, mode}` record the shape each end is attached to (`fx`,`fy` are fractions of its box). `sah` default `None`, `eah` default `Arrow`. |
| `text` | `x y w h angle text orig fs ff ta va container lh auto` | see below |
| `frame` | `x y w h` | clipping frame; title is `name` |
| `freedraw`, `image`, `point`, `arc` | see the schema | `image.fileId` is the sha256 of bytes held in the graph's asset store; the CLI draws a dashed placeholder instead |

**Text is drawn from its stored box and line breaks, never re-measured.** Set `w` about `0.55 * fs` per character and `h` = `lines * fs * lh` (default `lh` 1.25), put newlines in `text` where it wraps, and keep the unwrapped string in `orig`. `ta` (`Left`/`Center`/`Right`) anchors horizontally in the box, `va` vertically. A text with `"container": "<shape id>"` labels that shape (rect, diamond, ellipse or arrow); centre it in the shape with `ta: Center`, `va: Middle`.

**Arrows**: the `pts` are what is drawn; `sb`/`eb` only record attachment so the app can re-follow a moved shape. Put the end points on the shape outlines yourself (`build` does).

## 6. Constraints and dimensions

- **Constraints** (`constraints[]`: `{id, kind, targets:[{e, part}], value, ref}`) are persisted rules the app's solver keeps true when shapes move: `Distance`, `DistanceX`, `DistanceY`, `Angle` (radians), `Radius`, `Diameter` carry a `value` in scene units; `Coincident`, `Horizontal`, `Vertical`, `Parallel`, `Perpendicular`, `Equal`, `Tangent`, `Symmetric`, `Fixed` do not. `part` is `whole`, `start`, `end`, `centre`, `arcStart`, `arcEnd`, `vertex:N` or `edge:N`. Only rectangles, lines, points, arcs and circles are constrainable. A constraint naming a missing element, or a part the element lacks, is dropped on load. Writing constraints by hand is possible but easy to get wrong (an unsatisfiable set makes the solver move geometry on the next edit); prefer to draw to the right size. `describe` lists them.
- **Dimension annotations** the app shows on a technical sheet (a dimension line with extension lines, ticks and the value for each shape) are computed from the shapes at draw time and are not stored as elements. The same layout code draws them in the editor, in SVG and PNG export, in the `.excalidraw` export (as lines and text on a `dimensions` layer), in page-embed previews and in `sketch-cli render`, so every picture shows the same values. Whether a sheet shows them is the optional sheet flag `extras.dimensionsOn` (boolean): when it is absent, a technical sheet shows them and a diagram does not. The editor writes it when the Dimensions chip is toggled. Exports follow the flag; the export dialog and the CLI (`--dimensions`, `--no-dimensions`) override it for one export. In an export the annotations are laid out on a fixed fit of the sheet, not the editor viewport, so panning or zooming never changes them; at most 200 shapes are annotated, and a shape too small to carry its text at that fit is left out. For a dimension that must survive in the file and in `preview.svg`, `build` writes plain elements on the layer `dimensions`: two extension lines, a two-headed arrow and a value text, all in one group with ids `<id>.ext1`, `.ext2`, `.line`, `.text`. `describe` reads them back and flags a label that disagrees with its line (`MISMATCH`). `render --dimensions` overlays the measured width and height of every shape without saving anything.

### 6a. Photo underlay and calibration

A **photo or plan underlay** is a picture to trace over. It is not a new element type: it is the `image` element with id `underlay` on the layer `underlay`, so a reader that knows images already reads it.

- The layer `underlay` is created below every other layer (`order` lower than all), **locked**, with layer `opacity` 0.5 by default. A sheet holds one underlay. Lock, visibility and opacity are the layer's own (`locked`, `visible`, `opacity`), so the Layers panel and any layer-aware reader agree.
- `fileId` is the sha256 of the photo bytes (ADR-001: never inline base64). The bytes live in the graph's asset store as `assets/<sha256>.<ext>`.
- The element's `extras.underlay` is optional and holds the calibration. A sheet without an underlay contains none of it, so every existing sheet keeps its exact canonical bytes.

```json
{"id": "underlay", "type": "image", "layer": "underlay", "name": "Underlay photo", "x": 0, "y": 0, "w": 3840, "h": 2880, "fileId": "<sha256>",
 "extras": {"underlay": {"px": [4000, 3000], "refs": [{"a": [1000, 1500], "b": [3000, 1500], "len": 1920}]}}}
```

| key | meaning |
|---|---|
| `px` | the photo's pixel size after EXIF orientation: `[width, height]` |
| `refs[].a`, `refs[].b` | two points on the photo in **photo pixels** (origin top-left, v down) |
| `refs[].len` | the real distance between them in scene units (1/16 inch, a whole number), so `1920` is 10 feet |

**Calibration** is a single scale for the whole photo. One reference sets it (`len` divided by the pixel distance); with several references the scale is their mean and the largest disagreement is the *residual*. The app and `describe` call the photo calibrated and print `calibrated: 10' 0" ref`; a residual over 5 % is reported as perspective. The editor also turns the photo so the first reference runs along the nearest axis when it is within 20 degrees of one. A calibrated photo's scale is kept by the move gesture (it can be moved and turned, not pinched to another size), and if the photo is resized by other means the note says the scale changed and asks for a recalibration.

**Perspective is not corrected.** A photo taken at an angle is not a plan: one scale is exact only near the reference. A homography (four-point rectification) is out of scope; measure on the plane you trace and add a second reference on another axis to see how far off the photo is.

**Display size.** A photo within the asset store's limits (16 million pixels, 8192 px a side, 60 MB) is stored as picked. A larger one, such as a 50 megapixel phone photo, is reduced once on import to 3840 px on the longer side, upright, and the reduced copy is what the sheet references. Either way the editor never decodes the stored file whole: it draws from a copy reduced to **at most 2048 px on the longer side** (about 16 MiB as ARGB at worst), so a phone photo cannot exhaust an Android heap. `px` and the calibration are in the stored file's pixels, so the display cap never changes a measurement.

**Exports leave the underlay out.** SVG, PNG, `.excalidraw`, the page-embed preview and `preview.svg` draw the sheet without the layer `underlay`, so a traced plan stays clean. The export dialog has an "Include photo underlay" option (off by default); the CLI draws it with `render --underlay <photo>`. The editor always shows it.

## 7. Authoring spec (what `build` reads)

A small JSON document, version 1. `build` expands it into a valid sheet, so you write rooms and openings, not coordinates of door arcs. Unknown keys produce a warning; every error names its JSON path.

```json
{
  "spec": 1,
  "mode": "plan",
  "title": "Living room",
  "shapes": [
    {"id": "living", "x": "0", "y": "0", "w": "14'", "h": "12'", "label": "Living room"},
    {"id": "sofa", "role": "furniture", "x": "1'", "y": "8'", "w": "7'", "h": "3'", "label": "Sofa"}
  ],
  "openings": [
    {"id": "door1", "type": "door", "on": "living", "wall": "south", "offset": "10'", "width": "3'"},
    {"id": "win1", "type": "window", "on": "living", "wall": "north", "offset": "4'", "width": "5'"}
  ],
  "dimensions": [
    {"shape": "living", "side": "north", "offset": "30\""},
    {"shape": "living", "side": "east", "offset": "30\""}
  ]
}
```

Top level: `spec` (1), `mode` (`plan` default: real-world; `diagram`: abstract), `title`, `space` (`real` default or `photo`), `calibration` (photo space), `underlay` (photo space, see below), `wall` (default wall thickness, default `4 1/2"`), `sheetUuid` (optional; default derived from `title`, so rebuilding an edited spec with the same title yields the same sheet and `place` replaces it; give each sketch its own title), and the lists below. Ids are letters, digits, `-`, `_`, `.`, at most 64 characters, unique across the spec (derived ids such as `<id>.label` count).

| list | keys |
|---|---|
| `shapes` | `id`, `kind` (`rect` default, `ellipse`, `diamond`), `x`,`y` (top-left) or `rightOf` / `below` another shape with `gap`, `w`,`h`, `label`, `fontSize`, `fill`, `stroke`, `thickness` (line width), `dashed`, `role` (`room` default: wall-thick outline; `furniture`: 1 inch grey outline) |
| `lines` | `id`, `points` `[[x,y],...]`, `closed` (polygon, e.g. an L-shaped room), `thickness`, `stroke`, `fill`, `dashed`, `label` |
| `openings` | `id`, `type` (`door`/`window`), `on` (a rect shape), `wall` (`north`/`south`/`east`/`west`), `offset` (from the west end of a north/south wall, or the north end of an east/west wall), `width` (default 3' door, 4' window), door only: `swing` (`in` default / `out`), `hinge` (`start` default / `end`) |
| `arrows` | `id`, `from`,`to` shape ids (ends are placed on the outlines and recorded as bindings) or `points`; `via` bend points; `label`; `head` (`end` default, `start`, `both`, `none`); `dashed`; `stroke` |
| `labels` | `text`, `at` `[x,y]` (the centre of the text), `size`, `align`, `color`, optional `id` |
| `dimensions` | `shape` + `side`, or `from` + `to`; `offset` (default 24" in plan mode; for a free `from`/`to`, positive moves the line south for a mostly horizontal span and east for a mostly vertical one); `text` overrides the measured value |

Colours: `#rrggbb`, `transparent`, or `black white gray red blue green yellow orange lightblue lightgreen lightyellow`. In plan mode **shape coordinates are wall centre lines**: a room `w` of `14'` is 14 feet between the centres of its walls. Thicknesses, font sizes, gaps and dimension offsets are always real lengths, even in photo space; positions, sizes and opening `offset`/`width` are pixels there when written as bare numbers. Room labels are centred in their shape, so keep furniture out of the centre or leave its label to a `labels` entry. `rightOf` aligns the new shape's vertical centre with the reference, `below` its horizontal centre, unless you give `x`/`y`. `labels.at` is the text centre for `align: center`, the left edge for `left`, the right edge for `right`.

### Photo space and calibration

To trace a photo, write positions and sizes as **photo pixels** and give `build` references of known real length:

```json
{
  "spec": 1, "mode": "plan", "space": "photo",
  "calibration": {
    "refs": [ {"a": [700, 700], "b": [850, 700], "length": "3'"} ],
    "snap": "1\""
  },
  "shapes": [ {"id": "room", "x": 200, "y": 150, "w": 800, "h": 550} ]
}
```

`build` computes inches per pixel from each reference (`length` / distance between `a` and `b`), averages them, **warns when the references disagree by more than 5 %** (perspective or a misread point), converts every bare number from pixels to real length, and snaps converted values to `snap` (default 1 inch; `"none"` to keep exact values). Text-form lengths such as `"3'"` pass through unchanged. Use at least two references on different axes when you can. A photo taken at an angle is not a plan: calibration assumes the measured surface faces the camera.

To keep the photo under the traced plan, add `"underlay": {"image": "kitchen.jpg", "opacity": 50}` (photo space only; `image` is a path relative to the spec file, `opacity` is a percent, default 50). `build` stores the photo's sha256 as `fileId`, places it at the sheet origin at the calibrated scale (so shapes traced in photo pixels land on the photo), writes the `calibration.refs` as `extras.underlay.refs` and puts it on the locked `underlay` layer. `build` does not copy the photo into a graph: `place --underlay-image <photo>` stores it in the graph's asset store. `describe` lists the underlay and its calibration; `render` hides it unless `--underlay <photo>` is given (section 6a).

## 8. Worked examples

Files in [`sketch-examples/`](sketch-examples/): `room.spec.json` (the spec above), `diagram.spec.json` (labelled boxes and arrows), `photo.spec.json` (calibration), `handwritten.v2.json` (raw scene, deliberately not canonical).

**Room, as `describe` reads it back**

```
SKETCH SHEET 33f4892a-45c6-4f65-8720-cf98945e0f99 (schema 2), title "Living room"
Units: 1 scene unit = 1/16 inch (192 units per foot). x grows east (right), y grows south (down).
Extent (shapes, lines and text; not openings or dimension marks): 14' 4 1/2" wide x 12' 4 1/2" tall, ...
- living: rectangle 14' 0" x 12' 0", top-left (0", 0"), centre (7' 0", 6' 0"), label "Living room", line 4 1/2"
- sofa: rectangle 7' 0" x 3' 0", top-left (1' 0", 8' 0"), centre (4' 6", 9' 6"), label "Sofa", line 1"
OPENINGS
- door1: door 3' 0" wide, on the south wall of living, starting 10' 0" from the west end
- win1: window 5' 0" wide, on the north wall of living, starting 4' 0" from the west end
DIMENSIONS (authored annotations)
- dim1: "14' 0"" between (0", -2' 6") and (14' 0", -2' 6"), measures 14' 0"
RELATIONSHIPS
- living contains sofa
```

`render` of the same sheet is a plan with thick walls, a window drawn as a double line in the north wall, a door gap in the south wall with its swing arc, the sofa outline and two dimension lines.

**Labelled boxes and arrows** (`diagram.spec.json`): four shapes (`web`, `api`, `db` as an ellipse, `cache`), `rightOf`/`below` placement, three arrows with labels; `describe` ends with `connects web -> api`, `connects api -> db`, `connects api -> cache`.

**Raw scene** (what `build` ultimately writes, shortened; any key order and omitted defaults are accepted):

```json
{"schema": 2, "sheetUuid": "11111111-1111-4111-8111-111111111111",
 "elements": [
  {"id": "web", "type": "rect", "x": 0, "y": 0, "w": 160, "h": 70, "style": {"bg": "#a5d8ff"}},
  {"id": "web.label", "type": "text", "x": 40, "y": 22, "w": 80, "h": 25, "text": "Web", "orig": "Web", "fs": 20, "ta": "Center", "va": "Middle", "container": "web"},
  {"id": "api", "type": "rect", "x": 280, "y": 0, "w": 160, "h": 70},
  {"id": "a1", "type": "arrow", "pts": [[160, 35], [280, 35]], "sb": {"id": "web", "fx": 1, "fy": 0.5}, "eb": {"id": "api", "fx": 0, "fy": 0.5}}
 ]}
```

## 9. Command reference

| command | does | exit |
|---|---|---|
| `build <spec.json> --out <sheet.v2.json> [--render x.svg\|x.png] [--dimensions] [--width N]` | expand a spec; prints calibration, warnings, element count, sha256 | 1 on spec errors |
| `render <sheet.v2.json\|.excalidraw\|spec.json> --out <x.svg\|x.png> [--dimensions\|--no-dimensions] [--underlay <photo>] [--width N] [--style clean\|sketchy] [--background #rrggbb\|transparent]` | SVG through the app's own SVG writer; PNG through its Compose painter. Defaults: PNG 400 to 1600 px wide, white background, Clean style. `--dimensions` draws the dimension annotations, `--no-dimensions` hides them, `--underlay <photo>` draws the sheet's photo underlay from that file (its sha256 must equal the underlay's `fileId`; without the flag the underlay is left out), and with neither the sheet's own `dimensionsOn` flag decides (the editor's own layout, not saved) | 1 when the input is invalid |
| `validate <file>` | sheet, spec or `.excalidraw`. Reports syntax errors with line and column, then per-element problems (id, line, expected keys, "did you mean", valid enum values), dangling references, then what the real codec says, then canonical form. Ends with `RESULT: OK` or `RESULT: FAILED (n errors)` | 1 on errors |
| `canonicalize <sheet> [--out f \| --in-place]` | decode and re-encode: exact canonical bytes (stdout when neither flag); sha256 and "was already canonical" go to stderr | 1 if it does not load |
| `describe <file>` | text: extent, layers, every element with sizes in feet-inches, labels, openings, dimensions, contains/touches/overlaps/connects relationships, constraints, a character map. Accepts a sheet, spec or `.excalidraw` | 1 if it does not load |
| `from-excalidraw <in.excalidraw> --out <sheet.v2.json>` | the app's importer (pinned to Excalidraw v0.18.1) applied to an empty sheet (ids are kept); images are not carried over | 1 on refusal |
| `to-excalidraw <sheet.v2.json> --out <x.excalidraw>` | the app's exporter; layers flatten, constraints are not carried | 1 on failure |
| `place <sheet.v2.json> --graph <dir> [--slug proj-xxxxxx] [--name T] [--underlay-image <photo>]` | write `sheets/<uuid>.v2.json`, `project-model.json` (with sha256) and `preview.svg` into the bundle, creating it or replacing the same sheet uuid; prints the embed line; `--underlay-image` also stores the photo in `<graph>/assets/<sha256>.<ext>` and refuses a photo whose sha256 differs from the sheet's underlay `fileId` | 1 on failure |

Exit code 2 is a usage problem or missing file. PNG needs no external rasterizer; if you only have an SVG, `rsvg-convert -w 1200 in.svg -o out.png` works too.

## 10. Limits and compatibility

- At most 5000 elements, 200 constraints, 64 layers, 2000 points per element, 10000 text characters, 80 character names, 16 MiB per sheet, JSON nested at most 64 deep (`SceneLimits`).
- A reader that meets `schema` greater than 2 must refuse the file. A new schema number means a new file suffix and an ADR; this document and the JSON Schema are then versioned together. The spec's `spec` number is independent and bumps only when an existing spec stops meaning the same thing.
- Unknown keys inside an element are dropped on load; unknown top-level keys are dropped; `extras` (sheet and element) are kept.
- The CLI is a thin shell over the app's code (`SheetCodecV2`, `SvgExporter`, `PngExporter`'s painter, `ExcalidrawImporter`/`Exporter`, `SidecarCodec`); behaviour changes there change the CLI.

## 11. Pitfalls

- Reading a coordinate off a screenshot by eye is imprecise; overlay a labelled grid first (`docs/agents/sketch-from-photo/grid.py`).
- Walls are centre lines. The inside clear width of a room is `w` minus one wall thickness.
- A door `offset` is measured from the west (or north) end of its wall to the *start of the opening*, not its centre.
- `fill`/`bg` on a shape paints over what is beneath; list backgrounds first.
- A label longer than its shape produces a build warning; shorten it or set `fontSize`.
- Do not put two sheets with the same `sheetUuid` in a bundle; `place` replaces by uuid.
- The app rewrites a sheet in canonical form on its next save, so a hand-edit that is not canonical shows up as a whole-file git diff after the owner edits in the app. Always finish with `canonicalize`.
