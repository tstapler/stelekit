---
name: sketch-from-photo
description: Turn a photo of a floor plan, elevation, whiteboard diagram or hand sketch into a SteleKit sketch, put it in the graph and embed it in a page; also read, render and edit existing SteleKit sketches. Use when the owner supplies an image to sketch, asks what a sketch contains, or wants a sketch changed. Works on markdown files and the sketch CLI only, no app or GUI.
---

# Sketch from a photo (SteleKit v2 sketches)

You can see images and edit files; you cannot open the app. `stelekit sketch` closes the loop: you write a small JSON **spec**, the CLI turns it into a valid sheet and draws it, and you **look at the drawing next to the photo** until they agree. 

## Setup

- The tool is `stelekit sketch`, part of the installed SteleKit desktop app. Check: `stelekit sketch help`. If that opens the app instead, the install is too old: upgrade it. From a repo checkout, `scripts/sketch-cli` is the same tool (JDK 21).
- Format details: `stelekit sketch format` (units are 1/16 inch, y grows down).
- Use absolute paths or run from your scratch folder.
- The graph is the notes folder (it has `pages/` and `journals/`). Sketches live in `<graph>/assets/stelekit-sketch/<slug>/`.
- Work in a scratch folder, not in the graph, until the last step. Name files `<topic>.spec.json`, `<topic>.v2.json`, `<topic>.png`.
- `stelekit sketch grid-script > grid.py` gives you a helper (needs Pillow) that overlays a labelled pixel grid on a photo.

## Workflow: photo to sketch

1. **Look first.** Open the photo. Decide what it is (plan viewed from above, elevation of one wall, diagram) and whether it faces the camera squarely. Note the image size in pixels (`python3 grid.py photo.jpg photo.grid.png` prints it). A photo taken at an angle gives distorted lengths: say so to the owner and prefer references close to what you measure.
2. **Find reference lengths.** Best: a dimension written on the drawing, or a tape measure in frame. Otherwise known objects: interior door 80" tall and 30 to 36" wide, kitchen counter 36" high, base cabinet 24" deep, standard stud 3 1/2" wide, outlet plate 4 1/2" tall, A4 sheet 8.27 x 11.69". Pick **at least two references on different axes** and write down each as two pixel points plus the real length. Never invent a dimension: if a length has no reference, mark it as an estimate in the label and tell the owner.
3. **Read pixel coordinates from a grid.** Run `python3 grid.py photo.jpg photo.grid.png --step 50`, open `photo.grid.png`, and read coordinates off the labelled lines (they are original-image pixels). Write down corners of rooms, door and window spans, furniture boxes. Round to the nearest 10 px; precision beyond that is noise.
4. **Write the spec** (`space: "photo"`, `calibration.refs`, shapes in pixels). Start with the outer walls (they are wall centre lines), then openings, furniture, labels, dimensions. Use the owner's names for rooms. See "Spec skeleton" below. Lengths can be written in the unit the source uses: `"12' 6\""`, `"3 1/2\""`, or metric with a unit (`"25.4 mm"`, `"2,5 cm"`, `"1.2 m"`). Metric values are kept to 0.1 mm, not rounded to a sixteenth of an inch. A bare number is still pixels (photo space) or inches (plan).
5. **Build and render.** Rebuilding an edited spec keeps the same sheet uuid (it comes from `title`, or set `sheetUuid`), so `place` replaces the sheet; give different sketches different titles.
   ```bash
   stelekit sketch build kitchen.spec.json --out kitchen.v2.json --render kitchen.png
   ```
   To see measured sizes next to your authored ones run `render --dimensions` separately. Read the printed `INFO` and `WARNING` lines: the calibration scale (inches per pixel), disagreement between references, labels too big for their shape.
6. **Look and compare.** Open `kitchen.png` and the original photo, one after the other. Check shape, proportions, positions of doors and windows, which wall each is on, labels. The `--dimensions` overlay prints the measured width and height of every shape: compare each with any length written on the photo or known from the owner. Fix the spec, rebuild, look again. Two or three rounds is normal.
6a. **Keep the photo under the plan (optional).** Add `"underlay": {"image": "photo.jpg", "opacity": 40}` to the spec: `build` stores the photo by hash on a locked bottom layer at the calibrated scale, so the owner sees the traced plan over their photo in the app. `render` leaves it out of the picture unless you pass `--underlay photo.jpg`; `place --underlay-image photo.jpg` copies the photo into the graph's `assets/`. One scale covers the whole photo, so a tilted photo is only right near the reference (`describe` reports the disagreement of a second reference).
7. **Read it as text.** `stelekit sketch describe kitchen.v2.json` lists every element in feet-inches, which room contains or touches which, and which wall each opening is on. Use it to catch what pictures hide (a window on the south wall that you meant north, a `MISMATCH` in a dimension).
8. **Validate.** `stelekit sketch validate kitchen.v2.json` must end `RESULT: OK` with no warnings you cannot explain.
9. **Place it in the graph.**
   ```bash
   stelekit sketch place kitchen.v2.json --graph ~/notes --name "Kitchen"
   ```
   It writes the bundle (`project-model.json` with the right hash, the sheet, `preview.svg`) and prints one line such as `![sketch](../assets/stelekit-sketch/proj-1a2b3c/preview.svg)`. Add that line as (or inside) a block of the page the owner named. Pages and journals both use `../assets/...`.
10. **Report.** Tell the owner the scale you derived and from which references, anything you estimated, what you could not read from the photo, and where the page and bundle are. Attach the render.

## Spec skeleton

```json
{
  "spec": 1, "mode": "plan", "title": "Kitchen", "space": "photo",
  "calibration": { "refs": [ {"a": [700, 700], "b": [850, 700], "length": "3'"},
                             {"a": [1010, 200], "b": [1010, 560], "length": "7'"} ] },
  "shapes": [
    {"id": "kitchen", "x": 200, "y": 150, "w": 800, "h": 550, "label": "Kitchen"},
    {"id": "island", "role": "furniture", "x": 480, "y": 560, "w": 240, "h": 90, "label": "Island"}
  ],
  "openings": [
    {"id": "door1", "type": "door", "on": "kitchen", "wall": "south", "offset": 500, "width": 150},
    {"id": "win1", "type": "window", "on": "kitchen", "wall": "north", "offset": 220, "width": 200}
  ],
  "dimensions": [ {"shape": "kitchen", "side": "north"}, {"shape": "kitchen", "side": "east"} ]
}
```

Rules that matter: numbers are photo pixels, text lengths (`"3'"`) are real; `offset` is from the west end (north/south walls) or north end (east/west walls) to the **start** of the opening; `wall` is where the opening sits on that room (`north` is up in the photo); the room rectangle is wall centre lines; give every entry a short id. A bare shape that has `role: "furniture"` draws thin; anything else gets wall thickness (set `"thickness": "1\""` for a thin non-furniture box). For an L-shaped room use `lines` with `"closed": true`. For a diagram instead of a plan use `"mode": "diagram"`, shapes with `rightOf`/`below`, and `arrows` with `from`/`to`; no calibration is needed.

## Working on an existing sketch

Use `edit`, not a rebuild: the owner may have drawn on the sheet since, and a rebuild from a guess would lose that.

1. Find it: the page has `![sketch](../assets/stelekit-sketch/<slug>/preview.svg)`. List `<graph>/assets/stelekit-sketch/<slug>/sheets/` and take the `*.v2.json` (several sheets: `project-model.json` names them).
2. `stelekit sketch describe <sheet>` and `stelekit sketch render <sheet> --out look.png`; look at it. Never edit blind. Copy the sheet to the scratch folder first.
3. Write an edits file (`{"agent": "<your name>", "edits": [...]}`; ops are `add`, `move`, `resize`, `relabel`, `delete`, `set-dimension`, `add-wall`, `add-opening`, `set-layer`; see the format doc, section 7a). Name elements by the ids or names `describe` printed. Put `confidence` and `note` on anything you read off a photo.
   ```json
   {"agent": "claude", "edits": [
     {"op": "move", "id": "sofa", "dx": "5'", "dy": 0},
     {"op": "relabel", "id": "sofa", "text": "Couch", "confidence": 90, "note": "owner's word"}
   ]}
   ```
4. Try it: `stelekit sketch edit copy.v2.json --edits edits.json --dry-run --audit audit.json`. Read the `ok` / `REFUSED` lines and the diff. A refused edit (it would break a constraint, a door does not fit its wall, a name is wrong) writes nothing: fix that edit and run again.
5. Apply it: `stelekit sketch edit copy.v2.json --edits edits.json --in-place --audit audit.json`, then `validate` and render again; compare with the render from step 2. `stelekit sketch diff before.v2.json copy.v2.json` shows what changed in words.
6. `place` the result with the same `--slug`: it replaces the sheet with that uuid and refreshes `preview.svg`. Check `git diff --stat` in the graph: only that bundle should change.
7. If the owner may have the app open on that graph, say the file changed outside the app so it should reload before editing.

### Review pass (provenance)

Everything `edit` writes is marked `extras.provenance` (`source: agent`, your `confidence` and `note`). In the app the owner sees those elements with a dashed outline and an "AI" badge and can **Accept** the selected ones or **Accept all**; `describe` lists them as `[agent, needs review (70%): note]`. You are weak at dimensions read off a photo, so before you report: list every dimension you wrote, say which reference it came from, and give a low `confidence` to any you estimated. Tell the owner to review the badged elements, and never present a marked value as measured.

## Verification checklist (before you say it is done)

- [ ] I opened the render and the photo and compared them, not just the numbers.
- [ ] Calibration: at least two references, the build printed no "references disagree" warning (or I explained it).
- [ ] Every length I can check (written on the photo, known by the owner) matches `--dimensions` or `describe` within about 2 inches.
- [ ] Each door and window is on the wall it is on in the photo; hinge side and swing are plausible.
- [ ] Room names and labels are the owner's words; nothing was guessed silently.
- [ ] `validate` ends `RESULT: OK`; `describe` shows no `MISMATCH`.
- [ ] The embed line is in the right page, with `../assets/` and the printed slug.
- [ ] Only the bundle folder and that page changed in the graph (`git status`).
- [ ] For an existing sheet I used `edit` (not a rebuild), read the dry-run diff, and kept the audit file; every value I was unsure of carries a low `confidence` and a `note`.
- [ ] I told the owner what was estimated and what was not visible.

## Pitfalls

- **Reading pixels by eye** from a photo is the main error source. Use the grid; re-check corners by drawing and looking.
- **Perspective.** A tilted photo of a floor makes far walls look short. If a reference near the camera and one far away disagree by more than 5 %, the build warns. Either use references at similar depth or tell the owner the plan is approximate.
- **Which way is north.** The image's up is north on the sketch. If the owner says the photo is rotated, rotate the photo first (`magick in.jpg -rotate 90 out.jpg`) and rebuild the grid.
- **Wall thickness.** Spec rectangles are wall centre lines; the drawn wall is 4 1/2" by default and the inside is `w` minus one thickness. If the photo shows measured inside dimensions, add a wall thickness to each.
- **Rebuilding an owner-edited sheet.** `build` starts from the spec and discards what the owner drew; use `edit` on the sheet itself.
- **Hand-written JSON.** Do not type canonical bytes or `fileSha256` by hand; use `build`, `canonicalize` and `place`.
- **Overlapping labels.** A label longer than its box gives a build warning; shorten it or lower `fontSize`.
- **Reusing ids.** Spec ids must be unique, including derived ones such as `<id>.label`.
- **Images inside sketches** are not supported by the CLI (it draws a placeholder). To keep the photo with the page, put it in the page as its own image block beside the sketch.
- **Never write into the graph before step 9**, and never edit `project-model.json` by hand.
