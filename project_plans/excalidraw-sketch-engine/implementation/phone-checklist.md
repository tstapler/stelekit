# Phone checklist: v2 sketch editor (E10, owner run)

Status: NO ITEM HAS BEEN RUN. Every line is UNMEASURED until you tick it. Write results in the Result column
(PASS / FAIL / note). This covers what CI cannot: touch, soft keyboard, stylus, TalkBack, printing, two-device sync.
For the think-aloud sessions with other people, use `usability-protocol.md`; this file is the solo pass.

Setup (5 min): update EVERY device to the same release (0.125.0 or later) before opening a v2 project. Older builds
refuse a project that has walls, an underlay or agent-written values. Turn the new editor on (Settings). Note the
version, phone model and font scale below.

Version: ______  Phone: ______  Font scale: ______

## A. Layout (do this first; the last screenshot showed overlapping chips)

| # | Do | Pass when | Result |
|---|---|---|---|
| A1 | Open a sheet in portrait | Bottom chip row and tool row never draw over each other; no ghost chips | |
| A2 | Rotate to landscape | Same as A1; canvas is still usable | |
| A3 | Set Android font size to the largest, reopen | Every control reachable by scrolling; no clipped label hides a control | |
| A4 | Open the keyboard (type a dimension) | Keypad and field stay visible above the keyboard | |
| A5 | Rename the sheet (when the rename fix has shipped) | Name shows in the header and survives a restart | |

## B. Draw by touch

| # | Do | Pass when | Result |
|---|---|---|---|
| B1 | Draw a rectangle, line, arrow, ellipse with one finger | Each tool does what it says; no stray strokes | |
| B2 | Pinch zoom and two-finger pan while Finger is On | View moves; no shape is created or moved | |
| B3 | Drag-select several shapes, move them, undo, redo | State returns exactly each time | |
| B4 | Rotate a selection by 90 and by a typed angle | Shape rotates about its centre; dimensions update | |
| B5 | Draw a shape with an elbow connector between two boxes, then move one box | Connector re-routes and stays attached | |
| B6 | Reach every tool using only the tool strip scroll arrows | Nothing is unreachable | |

## C. Precision and snapping

| # | Do | Pass when | Result |
|---|---|---|---|
| C1 | Drag a line end near another end | Snap marker appears and it joins exactly | |
| C2 | Snap to a midpoint, an intersection, a perpendicular | Each kind snaps; marker says which | |
| C3 | Hold a point (loupe) while placing | Loupe shows under your finger; you can see the target | |
| C4 | Tap a dimension pill, type 36 then 91.4 cm | Both accepted; length changes to the typed value | |
| C5 | Switch units to metric and back | Every label changes; no value drifts after the round trip | |
| C6 | Turn the grid on and pick the 1 ft preset | Points land on grid; off again restores free placement | |

## D. Photo to sketch (the main goal)

| # | Do | Pass when | Result |
|---|---|---|---|
| D1 | Add a photo underlay from the gallery with a 12 MP photo | Loads in a few seconds, no crash | |
| D2 | Same with the largest photo you have (50 MP if available) | No crash or freeze; if it fails, note the size | |
| D3 | Calibrate: tap two points, type the real distance | A third known length measures within 1 percent | |
| D4 | Trace a wall, a door and a window with the wall tool | Openings sit in the wall; room area label appears | |
| D5 | Hide the underlay; export SVG | Photo is absent unless you turned it on | |
| D6 | Let the wiki agent run `sketch-cli place`/`edit` on the sheet, then sync | Agent values show a dashed AI badge; Accept and Accept all work | |

## E. Print and export at scale

| # | Do | Pass when | Result |
|---|---|---|---|
| E1 | Set paper A4/Letter, scale 1:10, scale bar on; export PDF | PDF opens; title block and scale bar present | |
| E2 | Print at 100 percent (no fit to page) and measure the scale bar with a ruler | Bar length within 1 mm of its label | |
| E3 | Print a sheet bigger than one page | Tiles line up with overlap marks | |

## F. Stylus and accessibility

| # | Do | Pass when | Result |
|---|---|---|---|
| F1 | Rest your palm and draw with a stylus (Finger Off) | No palm strokes | |
| F2 | TalkBack on: open Sketch outline, move through elements | Each element is announced with its type and size | |
| F3 | TalkBack on: "Add shape at...", add a box at typed coordinates | Box appears and is announced | |
| F4 | With TalkBack, reach every toolbar control | All have a spoken name; focus order matches visual order | |
| F5 | Switch Access or a keyboard: Tab through the editor | Every control is reachable; Enter activates | |

## G. Two devices over git sync

| # | Do | Pass when | Result |
|---|---|---|---|
| G1 | Edit different shapes on phone and desktop, then sync both | One sheet with both edits; "Merged changes" notice shows | |
| G2 | Edit the same dimension on both, sync | One value wins; no crash; note which won (it is the larger JSON, not the later edit) | |
| G3 | Delete a shape on one, edit it on the other | The edit is kept | |
| G4 | Check undo after a merge | Undo history is empty after adopting (known); nothing else breaks | |

## H. Performance (Pixel_Fold or your phone)

| # | Do | Pass when | Result |
|---|---|---|---|
| H1 | Open a sheet with about 500 elements, pan and zoom | Smooth, no visible stutter | |
| H2 | Same with about 5000 elements (use `sketch-cli build`) | Usable; record the worst stutter | |
| H3 | Draw continuously for 2 minutes on the 500-element sheet | No memory warning or crash | |

## Decide

v2 becomes the default only if A, B, C, D3 and E2 pass and nothing in F2 to F4 is a blocker. Anything that fails:
write the item number and a one-line description and hand the list back; each failure becomes one fix.
