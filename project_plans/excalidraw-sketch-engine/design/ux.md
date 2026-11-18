# UX design: excalidraw-sketch-engine

Date: 2026-11-14. Phase 3 UX artifact. Inputs: `requirements.md`, `research/ux.md`, `implementation/plan.md` (story numbers cited as S<n>), ADR-001..007 titles.

Conventions: `UI2/` = the v2 editor package named in the plan. Width and height classes, and every other layout figure, are defined once in the chrome table of section 1. Evidence labels follow research/ux.md: statements about current code are VERIFIED there; Excalidraw/FreeCAD behaviours are INFERRED and need a live check before the ADR cites them.

## 0. Surface inventory

| # | Surface | Kind | Section |
|---|---|---|---|
| 1 | Editor shell (adaptive layout, router, read-only banner) | interactive | 1 |
| 2 | Tool strip, flyouts, tool lock, pinned actions | interactive | 2 |
| 3 | Canvas input (pointer-kind split, finger drawing, hover, view controls) | interactive | 3 |
| 4 | Selection overlay and transform (handles, marquee, locked feedback) | interactive | 4 |
| 5 | Shape/line/arrow drawing and arrow binding | interactive | 5 |
| 6 | In-place text editor | interactive | 6 |
| 7 | Style panel and element stats | interactive | 7 |
| 8 | Layers panel | interactive | 8 |
| 9 | Constraint panel, glyphs, status line | interactive | 9 |
| 10 | Non-drag transform mode (crosshair generalised) | interactive | 10 |
| 11 | Import flow and import summary dialog | interactive | 11 |
| 12 | Export dialog | interactive | 12 |
| 13 | Element library | interactive | 13 |
| 14 | v2 enablement, migration notice, first-v2-write confirmation, old-build warning | interactive | 14 |
| 15 | Large-drawing banner and degradation | interactive | 15 |
| 16 | Diagnostics host (live region) | interactive | 16 |
| 17 | Keyboard map and shortcuts sheet | interactive | 17 |
| 18 | Embed preview and cross-graph copy | condensed | 18 |
| 19 | Log lines (observability) | non-interactive, condensed | 19 |
| 20 | Visual accessibility, touch help, discoverability, loading states (cross-cutting additions) | interactive | 22 |
| 21 | Constraint target picking, dimension editing and glyph declutter | interactive | 9 |
| 22 | Element names and the Elements list | interactive | 8 |
| 23 | Upgrade offer (opening never converts) | interactive | 14 |
| 24 | No-editor screen (newer schema, storage unavailable) | interactive | 14 |

Cross-cutting principles (apply to every surface):
- P1 No dead ends: every error/blocked state has at least one of Retry, Undo, Dismiss, Open legacy editor, or an explanatory exit; Esc always cancels the current transient state.
- P2 Never lose in-memory work: a failed save/import/solve leaves the last good scene on screen and in memory.
- P3 State is never colour-only (glyph or dash or text accompanies colour).
- P4 Every drag has a single-pointer alternative (WCAG 2.5.7), with ONE recorded exemption: the Freedraw tool's own stroke, where dragging along a path is essential. Its result is still reachable without dragging through "Draw polyline by points" (section 10), and the help page states the exemption.
- P5 Messages name the user's objects ("Line 3", "Layer Walls") and never echo element text into logs.

---

## 1. Editor shell

### Layout

Dock layout (Compact and Medium width at regular height, for example a portrait phone): tools bottom-docked, panels as bottom sheets, canvas takes the rest.

```
+--------------------------------------+
| <- Kitchen v2 [Finger: On][Saved]  ⋮ |  top bar: back, name, Finger drawing chip, save status, overflow
+--------------------------------------+
| [banner slot: one occupant]          |  one row: text, 1 primary, More
|                                      |
|            CANVAS                    |
| [chips: Square][Done]    [-][100%][+] |  action chip row (bottom-start), zoom cluster (bottom-end)
|                         [grid][snap] |
+--------------------------------------+
| selection bar / hint (when selected) |
| [Undo][Redo][Del] | tools ▸ ▸ ▸ ▸ ▸ ▸ |  dock: pinned left, tool groups scroll
+--------------------------------------+
```

Rail layout with a persistent side pane (Expanded width at regular height: foldable open, desktop):

```
+----+---------------------------------+-----------+
|Tool| top bar                         | Side pane |
|rail|                                 | [Style]   |
| V  |                                 | [Layers]  |
| R  |         CANVAS                  | [Constr.] |
| D  |                                 | [Elements]|
| ...|  [zoom][grid][snap]             | [Library] |
|----|                                 |           |
|U R |   status line (live region)     |           |
+----+---------------------------------+-----------+
```

Medium width has no layout of its own: at regular height it is the dock layout above (bottom dock, bottom sheet); at compact height (a landscape phone) it is the rail with a side sheet and no persistent pane (the layout rule below).

Rules:
- The never-overlap list (action chip row, view cluster, mic button, pan pad, an active edge strip's span) is in the chrome rules below; the dock reserves the bottom-end corner and the Crosshair chip is a chip of the action chip row, not of the cluster (fixes G13); the pad's hit areas are never inside an active edge strip.
- Panels are tabs in one container, not stacked cards; only one is expanded on compact. **A bottom sheet never needs a drag (UX-120; WCAG 2.5.7)**: its drag handle is also a 44 dp button ("Panel size") whose tap cycles half height, full height and half; the header carries explicit [Expand] / [Collapse] and [Close] buttons; with keyboard focus on the handle Up and Down arrows resize it and Esc closes it; Back closes it. Swiping stays as a shortcut, never the only way.
- Layers tab is hidden until a second layer exists (S3.5.2). The Constraints tab is ALWAYS present (UX-84): with no constraints and nothing selected it shows "No constraints yet. Select a line, circle or point, then choose Constrain." so the feature is discoverable without a selection; its Constrain trigger is disabled with the reason "Select an element to constrain" (reason also reachable by touch help, section 22).
- Panel tabs, ONE list: Style (which holds the Stats section), Layers (hidden until a second layer exists), Constraints (always), Elements (always, section 8) and Library (section 13): at most five. In the side pane they are a tab column; in a bottom or side sheet they are a tab row that scrolls with a visible chevron when the labels do not fit (font scale 200%), and every panel is also in the overflow "Panels" menu.

### Size classes and the chrome budget (UX-119; plan Story 3.1.4)

**Single source.** Every layout figure is defined ONCE, in the table below. No other place in ux.md, plan.md, requirements.md or validation.md states a derived figure by hand: `check-plan.py` computes the flat and grouped extents, the stacks, the minimum canvas, every worked canvas height, the pan-pad variant, the toast lane and the banner text budget from the table, fails if the "Computed from the table" block below differs from its output, and fails if any prose figure it recognises (extents, stacks, canvas heights) differs from the computed one. Elsewhere this document and the other three refer to a figure by name (`flatExtent`, `groupedExtent`, the canvas minimum) and to the table.

<!-- chrome-table:start -->
```text
# Layout and chrome: the ONLY place a layout figure is defined (dp unless stated). check-plan.py computes everything derived.
[params]
gutter = 16
toolTarget = 44
splitTargets = 2
deleteGap = 16
topBarReserve = 48
railWidth = 56
sidePaneWidth = 320
compactWidth = 600
expandedWidth = 840
compactHeight = 480
minWindowWidth = 320
minWindowHeight = 360
minCanvasMaxDp = 200
minCanvasPct = 55
minCanvasWidth = 240
mouseFloor = 24
padCell = 44
padSide = 3
padGridCells = 5
padRow = 4
padMaxPct = 25
handleFull = 3
handleCorners = 2
toastRow = 56
toastRow2 = 88
toastPrimary = 104
toastGap = 8
toastMaxPct = 40
bannerLines = 2
bannerCharDp = 7
bannerPrimary = 88
bannerMore = 44
bannerGap = 8
bannerIcon = 24
btnPad = 4
bannerLabelLine = 20
chipPad = 11
dynMax = 12
gestureInset = 32
clusterGap = 4
viewTargets = 7
chipMin = 96
overlayMaxPct = 70
[pinned]
Undo | 48
Redo | 48
Delete | 48
[strip]
Constrain | 44
ToolLock | 44
[tools]
select | -
hand | -
rectangle | Shapes
diamond | Shapes
ellipse | Shapes
arrow | Lines
line | Lines
freedraw | Lines
text | -
image | -
frame | -
eraser | -
point | Geometry
arc | Geometry
[groups]
Shapes | all
Lines | all
Geometry | technical
[topbar]
Back | 48 | 48 | - | -
FingerDrawing | 88 | 56 | Finger: Off | Off
SaveStatus | 72 | 44 | Unsaved | -
SelectionMenu | 48 | 48 | - | -
Overflow | 48 | 48 | - | -
NameMin | 64 | 64 | - | -
[rows]
TopBar | 48 | 56 | never | all
Dock | 56 | 56 | never | dock
BannerSlot | 56 | 96 | 2 | all
HintStatus | 24 | 48 | 1 | all
SelectionBar | 48 | 56 | 3 | all
[permanent]
rail+side sheet | HintStatus SelectionBar
[windows]
phone | 360 | 640 | 1.0 | yes
phone | 360 | 640 | 2.0 | yes
small phone | 320 | 480 | 1.0 | yes
small phone | 320 | 480 | 2.0 | yes
landscape phone | 600 | 360 | 1.0 | yes
landscape phone | 600 | 360 | 2.0 | yes
landscape phone | 800 | 360 | 1.0 | yes
landscape phone | 800 | 360 | 2.0 | yes
laptop | 1280 | 800 | 1.0 | yes
laptop | 1280 | 800 | 2.0 | yes
desktop | 1920 | 1080 | 1.0 | yes
desktop | 1920 | 1080 | 2.0 | yes
web 400% zoom | 320 | 256 | 1.0 | degraded
web 400% zoom | 320 | 256 | 2.0 | degraded
[banner]
# rank | id | tier | summary (320 dp, scale 1.0) | short title (scale 2.0; "-" = consent row, a sheet replaces the row) | primary label | More items | primary accessible name
1 | SaveFailed | Blocking | Couldn't save. Keep tab open. | Save failed | Retry | Details; Export copy | Retry save
2 | SaveConflict | Blocking | Sketch changed on disk. | Disk changed | Pick one | Details | Pick one version to keep
3 | ReadOnly | State | Read-only: new editor needed. | Read-only | Use new | Details; Restore original; Export SVG; Close; Dismiss | Use new editor
4 | LeaseConflict | State | Open in the other editor. | Other editor | Open there | Details; Close | Open there, in the other editor
5 | WarningNotice | Warning | Constraint conflict. | Clash | Show | Details; Dismiss | Show the constraint conflict
6 | PenDetected | Warning | Pen found. Finger off. | Pen found | Back on | Details; Got it | Back on for finger drawing
7 | LargeDrawing | Info | Large drawing: effects off. | Large drawing | Learn more | Dismiss | Learn more about large drawings
8 | PostConversion | Info | Upgraded. Backup saved. | Upgrade done | Learn more | Dismiss | Learn more about the upgrade
9 | EdgePanStopped | Onboarding | Edge pan stopped. | Edge pan off | Got it | Details | Got it, edge pan notice
10 | FingerHintOff | Onboarding | Finger drags move the view. | Finger pans | Draw | Details; Got it | Draw with one finger
11 | FingerHintOn | Onboarding | Finger draws. 2 fingers pan. | Finger draws | Turn off | Details; Got it | Turn off finger drawing
12 | UpgradeChip | Onboarding | Upgrade blocks other devices. | - | Start | Details; Not now; Keep this sketch classic | Start the upgrade
13 | CoachMark | Onboarding | New here? Quick tips. | Tips | Show me | Got it | Show me the tips
14 | SingleKeyChip | Onboarding | Single keys are off. | Keys off | Turn on | Details; Got it | Turn on single-key shortcuts
[toast]
# id | summary (320 dp, scale 1.0; {name} is cut to dynMax characters, {n:3} to 3) | short title (scale 2.0) | primary label | More items | primary accessible name
AddedConstraint | Constraint added. | Added | Undo | Details; Dismiss | Undo adding the constraint
RemovedConstraints | Removed {n:3} constraints. | Removed | Undo | Details; Dismiss | Undo removing constraints
ConstraintLimit | Constraint limit reached. | Limit | Review | Details; Dismiss | Review the constraints
ArrowUnbound | Arrow unbound from shape. | Unbound | Undo | Details; Dismiss | Undo unbinding the arrow
LayerLocked | Locked layer: {name}. | Layer locked | Unlock | Switch layer; Details; Dismiss | Unlock the layer
LockedSkipped | Locked items skipped. | Skipped | Unlock all | Details; Dismiss | Unlock all locked items
CantSatisfy | Can't satisfy constraints here. | Can't solve | - | Details; Dismiss | -
EngineChange | Applies next time you open it. | On reopen | Reopen | Dismiss | Reopen this sketch now
LibraryDeleted | Library item deleted. | Deleted | Undo | Dismiss | Undo deleting the library item
LibraryAdded | Added to library. | Added | Undo | Details; Dismiss | Undo adding to the library
LibraryInserted | Inserted from library. | Inserted | Undo | Details; Dismiss | Undo inserting the items
ExportSaved | File exported. | Exported | Open | Copy path; Details; Dismiss | Open the exported file
ExportSavedWeb | File downloaded. | Download | - | Details; Dismiss | -
CopiedToGraph | Copied to {name}. | Copied | Open | Details; Dismiss | Open the copied page
Restored | Restored. Copy kept. | Restored | Copy path | Details; Dismiss | Copy path of the kept copy
ClassicEditor | Created as classic. | Classic | Change | Details; Dismiss | Change the editor choice
ClassicEditorFixed | Created as classic. | Classic | Learn more | Dismiss | Learn more about why this sketch is classic
LowMemory | Low memory. | Low memory | Save now | Dismiss | Save now to disk
StillSaving | Still saving to this browser. | Still saving | - | Details; Dismiss | -
```

- **Layout is chosen on both axes.** Width classes come from `compactWidth` and `expandedWidth`; the one height class is compact height (`compactHeight`; a landscape phone is Medium width and compact height). The rule, in order: compact height uses the LEFT RAIL and a SIDE sheet (vertical extent is the scarce resource, so no bottom dock and no bottom sheet); otherwise Expanded width uses the rail and the persistent side pane; otherwise (Compact and Medium width at regular height) the bottom dock and the bottom sheet. Medium width has no layout of its own: it follows the height rule. The side sheet is at most `window width - rail - 240 dp` wide, never more than 320 dp and never less than 240 dp; where the room is under 240 dp the panel opens as a full-width overlay with a [Close] button instead (transient, it may cover the canvas). The rail is `railWidth` wide in every case: a split button's two targets lie ALONG the strip axis (side by side in the dock, stacked in the rail), so grouping never widens the rail.
- **Smallest window and the degraded layout.** The minimum-canvas guarantee holds for every window at least `minWindowWidth` wide AND `minWindowHeight` tall (the table's windows marked "yes" are all inside that range, and the script checks it). Below it, in either axis (the smallest case that must work is a web page at 400% zoom, 320 x 256 CSS px, WCAG 1.4.10) the SAME rules apply with no guarantee claimed: the rail layout, the banner slot may collapse to its badge ONLY while its occupant is not Blocking (a Blocking banner never collapses, here too: it keeps its row and the canvas shrinks, which the table's degraded rows show as "not claimed"), panels open as full-width overlays with [Close], nothing clips, no page-level horizontal scroll, and every control stays reachable by a scroll path. The table lists these windows as "degraded"; `ChromeBudgetPolicyTest` and `WebReflowTest` assert no-clipping and reachability there instead of the minimum.
- **Minimum canvas.** The visible canvas, with every row counted (top bar, banner slot, hint/status row, selection bar, dock, closed sheets), is at least `min(minCanvasMaxDp, minCanvasPct % of the window height)` tall and `minCanvasWidth` wide, in every guaranteed window of the table, at font scales 1.0 and 2.0, and also in the WORST case of a Blocking banner occupant (which never collapses). The script fails if any guaranteed window misses it. A half-height bottom sheet is a transient overlay and may cover the canvas, but the selected element stays visible while a sheet is open (section 7, UX-125).
- **ONE transient-chrome rule (UX-119).** Permanent rows: the top bar and, in the dock layout, the dock. Transient rows: at most THREE, each a SLOT with one occupant: the **banner slot**, the **hint/status row** (text only: tool hints, the DOF status, "Solving..."; never a control) and the **selection bar**. Rows collapse only until the minimum canvas holds, in the order of the `rows` table (1, 2, 3), and return in reverse order. In the rail layout at compact height the hint/status row and the selection bar are PERMANENTLY collapsed (table section `permanent`): their text goes to the live region, the touch-help popup and the Constraints tab header, and their actions to the top-bar **Selection menu** (table `topbar`, `SelectionMenu`, one 48 dp button shown only in this layout, its semantics naming the selection count): one menu of Duplicate, Group, Ungroup, Align, Order, Transform and Lock or Unlock, plus "Bind end..." when one arrow is selected (the non-drag binding picker of section 5 and Story 3.6.3, so it is reachable where the selection bar is collapsed) (Delete stays pinned). "Transform" holds Resize, Rotate, Move and Flip, and is also the handle fallback of small selections (UX-127). In the degraded window the same menu is an item of Overflow, because the top bar cannot give the name `NameMin` otherwise (the table's "Top bar" column). Controls that touch users need are never in a row (next bullet).
- **Overlays on the canvas are not rows** and never collapse into one: the view cluster (bottom-end), the **action chip row** (bottom-start, after the shell mic button), the pan pad (bottom-end, above the cluster) and toasts. There is no other hint chip: the Finger drawing hints are banner rows. The **action chip row** holds every control that a touch or pen user needs while a tool or operation is active: the modifier toggle chips ("Square", "From centre", "Don't bind", "15 degrees"), Line's [Done], the picker's [Cancel], the "Solving..." [Cancel], the smart-dimension chip, the one-shot "Free draw" chip, the Crosshair chip and a "Tool lock" chip while tool lock is on, the persistent durability cue chip while a private window is suspected or the graph is temporary and `HintStatus` is absent (UX-153; its label form is the longest that fits the chip row of that window, `hint-cue` block), (so the lock state is visible and switchable by touch where the hint row is absent, as well as in the hint row text and the tool button's lock glyph). Its chips are at the 44 dp floor, it scrolls with a visible chevron when it overflows, it exists only while something is active, except for the one persistent, non-dismissible durability cue chip, which is its first chip for as long as the cue applies, it is not part of any row (so no collapse removes it), and it is therefore available at every window size including the compact-height rail. The keyboard hint row carries hints only. The never-overlap list: action chip row, view cluster, mic button, pan pad and an active edge strip's protected span.
- **Overlay horizontal budget (computed: table columns "View cluster, chip row, overlay height" and "Drawing width").** From the canvas width subtract the edge-strip insets (`gestureInset` plus `toolTarget` per active side: one side below `compactWidth`, two from it; the table assumes the strips active, the worst case) to get the overlay width, in which the mic button, the chip row (at least `chipMin` visible, it scrolls) and the view cluster must sit side by side. The cluster is ONE row of `viewTargets` 44 dp targets (zoom out, zoom level, zoom in, Grid, Snap, Finger drawing, Pan; the one-row width is in the computed block) where that leaves room for the mic button and `chipMin`, otherwise TWO rows (the three zoom targets above the four toggles). Where the chip row still cannot sit beside the cluster it sits ABOVE it, and together they cover at most `overlayMaxPct` % of the collapsed canvas (the script fails otherwise). The drawing width, the canvas width minus one `toolTarget` per active edge strip (the strip is pan-only), stays at least `minCanvasWidth` in every guaranteed window.
- **Toast lane (UX-91, WCAG 2.4.11).** Toasts stack downward from the top-start corner of the canvas directly under the banner slot, each `toastRow` tall (`toastRow2` from font scale 1.5) with a `toastGap` gap, at most three, and at most `toastMaxPct` % of the canvas height (always at least one; the table shows how many fit in each window); the rest collapse into "+N more" and the diagnostics list. A toast never takes focus on arrival, Esc dismisses the newest, and when keyboard focus moves to a control that a toast covers, that toast moves to the diagnostics list at once, so no focused control is ever hidden by a toast (`ChromeBudgetTest` audits every focusable node with the maximum number of toasts showing). **Toast content fit.** A toast is ONE row: text (at most `bannerLines` lines), at most ONE visible action button (`toastPrimary` wide) and a 44 dp More button that holds the second action (a toast with two persistent actions, for example Unlock and Switch layer, shows Unlock and More; Details, the full text with the names and counts, is an item of More wherever the full text carries any; **Dismiss is the last item of every toast's More**, so every persistent toast has a visible 44 dp way out for pointer and touch users, since Esc is keyboard-only and a swipe would be a drag (WCAG 2.5.7)). **Lane of one.** Where the lane holds a single toast (the table's Toasts column: 1 on small windows and at large text), a newer toast does not wait behind a persistent one: the displayed toast moves to the diagnostics list, where its actions stay, and the newer one is shown, so a persistent toast can neither block the lane nor sit unseen forever and nothing is lost. Every row of the `toast` table has a summary that wraps by word within two lines at 320 dp and font scale 1.0 (placeholders expanded to their maximum) and a short title that does so at 2.0; at font scale 1.5 and above the row is `toastRow2` tall (two lines of double-size text) and shows the short title when the summary does not fit. A dynamic value enters a toast only as a `{name}` placeholder shown at most `dynMax` characters long with an ellipsis (a count is `{n:3}`, at most three digits); the whole text is in the diagnostics list. **Keyboard path.** Toasts sit under the banner slot in one notice region that F6 reaches (section 17) and Tab walks, newest first, so Undo, Unlock and Switch layer need no pointer; the diagnostics list repeats every action and is in the tab order (section 10).
- **Pan pad variants (UX-120).** A plus-shaped grid (the five cells up, left, Fit, right, down of a 3 x 3 box; the four corner cells are NOT part of the pad: they are not drawn or hit-tested, so touches there reach the canvas) of 44 dp cells while it covers at most `padMaxPct` % of the canvas, otherwise a single row of four arrow buttons (the "Fit" button moves to the 100% menu); the table gives the variant and coverage per window. The pad is opaque, has NO auto-collapse timer (a timer would remove a control the user is still reading, WCAG 2.2.1): it closes when the Pan button is activated again, on Esc and on a tap on the canvas.
- **Pinned and top-bar controls.** Pinned Undo, Redo and Delete (with the Delete gap) always fit and never scroll. The top bar (table `topbar`) holds Back, the sketch name, the Finger drawing chip, the save status, the Selection menu (compact-height rail only) and Overflow; it never wraps and keeps the `TopBar` row height. **The Finger drawing state stays visible text at every font scale (UX-80)**: below font scale 1.5 the chip reads "Finger: On" or "Finger: Off" with its glyph, from 1.5 up the shorter "On" or "Off" with the same glyph (widths per scale are in the table and the script checks each label against its width); semantics "Finger drawing, on, switch", and the long-press or hover tooltip (UX-90) carries "Finger drawing: On". **Save status** reads Saved, Saving, Unsaved or Failed below font scale 1.5 and is a glyph-only 44 dp button from 1.5 up; its full state (Saved, Saving, Unsaved changes, Save failed) is the semantics, the tooltip and the live-region text, and a failed save is also the Blocking `SaveFailed` banner, so the state never rests on a glyph alone. When the name would drop under `NameMin` the top bar uses the short forms first, then moves the Selection menu into Overflow; every guaranteed window fits the full forms of its font scale, which the script checks (computed: the name keeps 64 dp at 320 dp wide, font scale 1.0, and the width in the block below at 2.0). The name truncates with an ellipsis and its full text is in semantics. The view cluster also offers the Finger drawing toggle.
- **Flat versus grouped is ONE rule at every width class.** Tools are shown FLAT only when `flatExtent` fits the strip extent, otherwise GROUPED. The strip extent is the window width for the dock and `window height - topBarReserve` for the rail. A group is a SPLIT BUTTON: a main target (activates the group's last-used tool) and a separate flyout target (the chevron), each `toolTarget`, together `splitTargets x toolTarget` along the strip axis, never one button with a corner mark. Singles (`-` in the table) stay single buttons. The Geometry group exists only on technical and converted sheets; on a diagram sheet Point and Arc are in the overflow menu under "More tools" when grouped and are flat tools when flat. Every non-pinned control scrolls along the strip axis with edge fades AND a visible chevron while more exist, and each is also in the overflow menu and has a shortcut, so nothing depends on discovering that the strip scrolls; at 320 dp wide at least three non-pinned targets are visible beside the pinned trio (computed below).
- **The touch-floor switch never reflows.** The strip, the rows and the panels are laid out at the sizes of the table in EVERY pointer class; the floor of section 2 (44 dp with a touch or pen pointer present, `mouseFloor` mouse-only) governs only hit areas of controls whose visible size is smaller (handles, glyphs, markers), which grow invisibly around their centre. A first touch event on a mouse machine therefore changes no position under the pointer.
- **Handles on small elements.** On-canvas handles each have a hit area at the floor, so they are shown by the size of the selection on screen (tiers in the computed block: `handleFull` hit areas fit along a side, `handleCorners` only the two corners): all eight resize handles and the rotation handle only when their hit areas cannot overlap, the four corner handles and the rotation handle below that, and below that none: the selection shows its outline only, and Resize, Rotate and Move are reached from the selection bar's "Transform" menu, the Stats fields and the Crosshair mode (section 10), with the status line saying "Zoom in to use handles". `HandleLayoutTest` asserts over generated sizes and both floors that no two shown hit areas overlap.
- **Banner slot: one occupant, in the ONE order of the banner table.** The `banner` table lists every candidate in rank order (rank 1 wins): Blocking notices, then state banners (read-only, lease), then Warning notices (constraint conflict, pen detected), then Info banners, then onboarding items in the order of section 16 (that order is the table's rank, the single place it is defined). A candidate is a `SketchNoticeQueue` entry; the `BannerSlot` host renders the head of the queue and nothing else; producers only emit entries through `DiagnosticsPort` and never touch the slot (plan Story 3.6.7). The slot never grows to hold two items; a waiting item stays in the queue and the diagnostics list and appears when the occupant is dismissed or acted on. Success confirmations and undoable-edit confirmations are toasts (the 19 rows of the `toast` table: constraint added (`AddedConstraint`) or removed (`RemovedConstraints`), constraint limit (`ConstraintLimit`), arrow unbound (`ArrowUnbound`), locked layer (`LayerLocked`), locked items skipped (`LockedSkipped`), constraints that cannot be satisfied (`CantSatisfy`), the engine-change notice (`EngineChange`), library item deleted, added or inserted (`LibraryDeleted`, `LibraryAdded`, `LibraryInserted`), file exported (`ExportSaved`, and `ExportSavedWeb` on the web build, which has no [Open]), copied to a graph (`CopiedToGraph`), restored (`Restored`), classic-editor notice (`ClassicEditor`, and `ClassicEditorFixed` where Settings cannot change the outcome), low memory (`LowMemory`), still saving to the browser (`StillSaving`)), not banner candidates.
- **Banner content fit.** A banner is ONE row: a severity icon (`bannerIcon`), the summary text (at most `bannerLines` lines), ONE primary button (44 dp tall, `bannerPrimary` wide) and, when the entry has other actions or a dismissal, ONE 44 dp "More" button whose menu holds all of them (including Dismiss and, for consent items, Details). The text width is the window width minus two gutters, the icon, the primary button, More and three gaps (108 dp at 320 dp wide; the character budgets and the width of one line at each width and scale are in the computed block). **Rows wrap by word, so the check is by word, not by character count.** Every row of the `banner` table has a fixed `summary` that, word-wrapped greedily at the line width of 320 dp and font scale 1.0, fits the row's two lines, and a short title that fits at scale 2.0 (a word breaks only after a hyphen); the row shows the summary when it fits the current width and scale and otherwise the short title, and the full copy is always one tap away in Details (the first item of More; a row whose primary is "Learn more" opens Details itself and does not repeat it). **A consent row never shrinks to a short title.** A row whose short title is "-" (`UpgradeChip`) states a consequence the user must see before acting; where its summary does not fit the current width and scale it is replaced by a one-time bottom sheet that shows the consequence in full (section 14.2), so "Upgrade?" alone is never shown. **Names and labels (WCAG 2.5.3, 2.4.6, 4.1.2; UX-126).** A primary label is a verb of one or two words that the button can hold; its accessible name is the label plus its object ("Turn on single-key shortcuts") and always contains the visible label; names are unique; a visible label is not reused for different meanings (the four former "Turn on" buttons are "Use new", "Back on", "Draw" and "Turn on"), and the ONLY labels shared by more than one row are Undo, Got it, Open, Learn more and Show, each with one fixed meaning (Undo undoes the named edit, Got it dismisses the notice, Open opens the named object, Learn more opens Details, Show reveals the named object; the script's allow-list is exactly this list); the consent button stays "Start", not "Upgrade": "Upgrade" is 7 characters against the 5 that a word of the `bannerPrimary` button can hold at font scale 2.0, and the button that would hold it is wider than every other row's, which the computed block prices (it cuts the summary line and pushes banner summaries past 2 lines); the live region announces the FULL summary at every font scale (the short title is visual only); and Details is an item of More, never a second primary. A primary label has at most the computed number of characters at scale 1.0 and, at 2.0, wraps only between words (at most two words, each within the computed word length); the script checks every banner and toast label, summary, short title and accessible name. **Dynamic values never enter the row**: a banner summary is fixed text chosen by row id, so names ("Parallel conflicts with Horizontal on Line 3"), counts and file names live in Details and the diagnostics list, and the `BannerCandidate` constructor takes a row id and a Details string and has no free summary parameter (a toast takes `{name}` placeholders cut to `dynMax` characters). Long copy (the consent sentence, the large-drawing explanation, the read-only reason, the pen-detected explanation) lives in Details, a sheet or dialog, never in the row. Blocking entries have no Dismiss. The script checks every row of the `banner` and `toast` tables against these budgets, and `ChromeBudgetTest` checks the rendered row (no clipped text, at most two lines, buttons at the floor, inside the slot height) at 320 and 360 dp, font scales 1.0 and 2.0.
- **Font scales above 2.0.** The guarantee, the table and the tests cover font scales 1.0 to 2.0 (WCAG 1.4.4 asks for 200%). Above 2.0 (iOS accessibility sizes reach about 3.1) the chrome text (top bar, banner, toast, strip labels) is held at 2.0 so rows keep their height, while panels, sheets, dialogs and the consent sheet follow the system scale and scroll; the live region still announces the full text, and the no-clipped-node audit of `ChromeBudgetTest` also runs those surfaces at 3.0.
- **Free drawing height in the worst window (stated, not raised).** In the small phone at font scale 2.0 the canvas is 216 dp and the overlays may cover up to 140 dp (65%), so about 76 dp of free drawing height remains above the overlay; the floor is not raised because the overlays exist while something is active (the cluster and chip row are the controls that keep that window usable) or, for the one persistent durability cue chip, while the cue applies, and the overlay height of the table already reserves that chip row and the computed block below keeps the minimum canvas at 200 dp; `ChromeBudgetTest` draws a stroke in that free region.
- **Overlay share on the smallest phone.** The computed block states the worst share of the collapsed canvas that the cluster and chip row cover and in which window; it stays under `overlayMaxPct`, and where the chips sit above the cluster the strips and the drawing area between them stay reachable (`ChromeBudgetTest` draws a stroke there).
- **Tested.** `ChromeBudgetPolicyTest` (pure; generated grid plus the table's windows) and `ChromeBudgetTest` (Robolectric) run every window of the table at both font scales with every banner candidate pending and exactly one visible, in rank order; the pinned trio is fully visible; all 14 tools are reachable (flat, grouped, scrolled or from overflow); and no node is clipped without a scroll path.

**Computed from the table** (regenerate with `python3 check-plan.py <dir> --chrome`; the script compares this block to its own output and fails on any difference).

<!-- chrome-computed:start -->
- flatExtent = pinned 160 + 14 tools x 44 + strip controls 88 = 864 dp
- groupedExtent = 776 dp on technical sheets (3 split groups x 88 + 6 singles x 44), 688 dp on diagram sheets (Geometry group absent)
- flat needs a dock window at least 864 dp wide or a rail window at least 912 dp tall; 8 tools are in 3 split groups and 6 are singles; at 320 dp wide 3 non-pinned targets are visible; the top bar leaves the name 64 dp at 320 dp and font scale 1.0 and 124 dp at font scale 2.0
- view cluster: one row of 7 targets is 332 dp wide, two rows are 188 dp wide
- handle tiers (shorter side of the selection bounds on screen): all eight resize handles and rotation from 132 dp (mouse-only 72 dp), the four corner handles and rotation from 88 dp (mouse-only 48 dp), below that no on-canvas handles
- banner text budget (2 lines, severity icon included): 30 chars at 320 dp wide and font scale 1.0, 14 chars at 320 dp wide and font scale 2.0, 42 chars at 360 dp wide and font scale 1.0, 20 chars at 360 dp wide and font scale 2.0
- toast text budget (2 lines, no icon): 34 chars at 320 dp wide and font scale 1.0, 16 chars at 320 dp wide and font scale 2.0, 46 chars at 360 dp wide and font scale 1.0, 22 chars at 360 dp wide and font scale 2.0
- primary button label (banner 88 dp wide, toast 104 dp wide): at most 11 (toast 13) chars at font scale 1.0 and words of at most 5 (toast 6) chars at font scale 2.0; a label wraps only between words, at most two words
- line width in characters (summary at font scale 1.0, short title at 2.0): banner 15 and 7 at 320 dp wide, 21 and 10 at 360 dp; toast 17 and 8 at 320 dp, 23 and 11 at 360 dp; every summary and short title is word-wrapped greedily at that width (a word breaks only after a hyphen) and must fit the row's 2 lines
- banner candidates: 14 (2 Blocking, 2 State, 2 Warning, 2 Info, 6 Onboarding), of which 1 consent (UpgradeChip: a sheet replaces the row when its wrapped summary exceeds the row's lines); toast catalogue: 19
- consent row UpgradeChip (summary "Upgrade blocks other devices."): lines at font scale 1.0 / 1.15 / 1.30 / 1.50 / 2.0: 320 dp 2 / 3 / 4 / 4 / 5+, 360 dp 2 / 2 / 2 / 2 / 4; the sheet replaces the row from font scale 1.11 at 320 dp wide and from 1.52 at 360 dp wide (5+ means a word is wider than the line)
- a two-word primary label at font scale 2.0 is 88 dp tall (2 lines x 20 dp x 2.0 + 2 x 4 dp padding), inside the toast row (88 dp) and the banner slot (96 dp)
- widening the primary button to 106 dp so that a 7-character word such as "Upgrade" fits at font scale 2.0 would leave 12 characters per line at 320 dp wide and font scale 1.0 (from 15) and push 9 of the 14 banner summaries past 2 lines
- overlay share: at most 65% of the collapsed canvas (small phone 320 x 480 at font scale 2.0; limit 70%)
- windows without HintStatus (the Rows collapsed column and the permanent section of the layout): small phone 320 x 480 at font scale 2.0 (collapsed); landscape phone 600 x 360 at font scale 1.0 (permanent); landscape phone 600 x 360 at font scale 2.0 (permanent); landscape phone 800 x 360 at font scale 1.0 (permanent); landscape phone 800 x 360 at font scale 2.0 (permanent); web 400% zoom 320 x 256 at font scale 1.0 (permanent); web 400% zoom 320 x 256 at font scale 2.0 (permanent)
- cue chip, the first chip of the action chip row, form chosen per window (chip row dp; PrivateWindow, SessionOnly): small phone 320 x 480 at 2.0: 196 dp, PrivateWindow "Private", SessionOnly "Temporary"; landscape phone 600 x 360 at 1.0: 152 dp, PrivateWindow "Private: may clear.", SessionOnly "Temporary: not kept."; landscape phone 600 x 360 at 2.0: 152 dp, PrivateWindow "Private", SessionOnly "Temporary"; landscape phone 800 x 360 at 1.0: 208 dp, PrivateWindow "Private window: may clear.", SessionOnly "Temporary: lost on close."; landscape phone 800 x 360 at 2.0: 208 dp, PrivateWindow "Private", SessionOnly "Temporary"; web 400% zoom 320 x 256 at 1.0: 140 dp, PrivateWindow "Private", SessionOnly "Temporary"; web 400% zoom 320 x 256 at 2.0: 140 dp, PrivateWindow "Private", SessionOnly "Temporary"

| Window | Scale | Layout | Tools | Strip extent | Stack with every row | Canvas with every row | Rows collapsed | Canvas (collapsed) | Canvas with a Blocking banner | Minimum | Canvas width | Pan pad (share) | Toasts | Top bar (name dp) | View cluster, chip row, overlay height | Drawing width |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| phone 360 x 640 | 1.0 | dock+bottom sheet | grouped (scrolls) | 360 | 232 | 408 | none | 408 (63%) | 408 | 200 | 360 | grid 6.5% | 2 | full, 104 | two rows, chips above, 140 | 316 (1 edge strip) |
| phone 360 x 640 | 2.0 | dock+bottom sheet | grouped (scrolls) | 360 | 312 | 328 | none | 328 (51%) | 328 | 200 | 360 | grid 8.1% | 1 | short, 164 | two rows, chips above, 140 | 316 (1 edge strip) |
| small phone 320 x 480 | 1.0 | dock+bottom sheet | grouped (scrolls) | 320 | 232 | 248 | none | 248 (51%) | 248 | 200 | 320 | grid 12.1% | 1 | full, 64 | two rows, chips above, 140 | 276 (1 edge strip) |
| small phone 320 x 480 | 2.0 | dock+bottom sheet | grouped (scrolls) | 320 | 312 | 168 | HintStatus | 216 (45%) | 216 | 200 | 320 | grid 14.0% | 1 | short, 124 | two rows, chips above, 140 | 276 (1 edge strip) |
| landscape phone 600 x 360 | 1.0 | rail+side sheet | grouped (scrolls) | 312 | 104 | 256 | none | 256 (71%) | 256 | 198 | 544 | grid 6.9% | 1 | full, 240 | two rows, chips beside, 92 | 456 (2 edge strips) |
| landscape phone 600 x 360 | 2.0 | rail+side sheet | grouped (scrolls) | 312 | 152 | 208 | none | 208 (57%) | 208 | 198 | 544 | grid 8.5% | 1 | short, 300 | two rows, chips beside, 92 | 456 (2 edge strips) |
| landscape phone 800 x 360 | 1.0 | rail+side sheet | grouped (scrolls) | 312 | 104 | 256 | none | 256 (71%) | 256 | 198 | 744 | grid 5.0% | 1 | full, 440 | one row, chips beside, 44 | 656 (2 edge strips) |
| landscape phone 800 x 360 | 2.0 | rail+side sheet | grouped (scrolls) | 312 | 152 | 208 | none | 208 (57%) | 208 | 198 | 744 | grid 6.2% | 1 | short, 500 | one row, chips beside, 44 | 656 (2 edge strips) |
| laptop 1280 x 800 | 1.0 | rail+side pane | grouped (scrolls on technical sheets) | 752 | 176 | 624 | none | 624 (78%) | 624 | 200 | 904 | grid 1.7% | 3 | full, 648 | one row, chips beside, 44 | 816 (2 edge strips) |
| laptop 1280 x 800 | 2.0 | rail+side pane | grouped (scrolls on technical sheets) | 752 | 256 | 544 | none | 544 (68%) | 544 | 200 | 904 | grid 1.9% | 2 | short, 708 | one row, chips beside, 44 | 816 (2 edge strips) |
| desktop 1920 x 1080 | 1.0 | rail+side pane | flat (no scroll) | 1032 | 176 | 904 | none | 904 (83%) | 904 | 200 | 1544 | grid 0.6% | 3 | full, 1288 | one row, chips beside, 44 | 1456 (2 edge strips) |
| desktop 1920 x 1080 | 2.0 | rail+side pane | flat (no scroll) | 1032 | 256 | 824 | none | 824 (76%) | 824 | 200 | 1544 | grid 0.7% | 3 | short, 1348 | one row, chips beside, 44 | 1456 (2 edge strips) |
| web 400% zoom 320 x 256 (degraded, not claimed) | 1.0 | rail+side sheet | grouped (scrolls) | 208 | 104 | 152 | none | 152 (59%) | 152 (not claimed) | 140 | 264 | grid 24.1% | 1 | short, Selection in overflow, 68 | two rows, chips above, 140 | 220 (1 edge strip) |
| web 400% zoom 320 x 256 (degraded, not claimed) | 2.0 | rail+side sheet | grouped (scrolls) | 208 | 152 | 104 | BannerSlot | 200 (78%) | 104 (not claimed) | 140 | 264 | grid 18.3% | 1 | short, Selection in overflow, 68 | two rows, chips above, 140 | 220 (1 edge strip) |
<!-- chrome-computed:end -->

**Accepted deviation (D-118; owner-confirmable).** The two `web 400% zoom 320 x 256` rows are degraded and NOT claimed, and that is recorded as an explicit accepted deviation from WCAG 2.1 SC 1.4.10 Reflow for the first release: at that zoom the canvas (152 dp at font scale 1.0, 104 dp at 2.0) is below the minimum canvas height, so no AA claim is made for the editor at 400% browser zoom, although every function stays reachable by scrolling the tool strip and the panels. Browser zoom is a user-chosen adaptation; the owner accepts the deviation until a user needs it.

### Editor routing and read-only banner (matrix from S1.4.3)

| Mode (`V2Mode`, effective after the build cap) | Sheet schema | Result | User-visible |
|---|---|---|---|
| any, while `V2StorageAvailable` is false only because the web storage status is `Checking` (the browser's OPFS verdict or its preload has not finished, at most 10 s) | 2 or newer by the DB mirror | pending; EVALUATED FIRST, before the row below | the `Pending` state: "Checking this browser's storage..." with [Close], no banner and never the no-editor screen; it resolves to a row below when the status settles, and after 10 s the status is `Unavailable(CheckTimedOut)` (UX-141); its focus and announcement rows are the table `pending-contract` of 14.8 (UX-146) |
| any, while `V2StorageAvailable` is false (iOS, a browser whose status is `Unavailable`, no sidecar store) | 2 or newer by the DB mirror (the file cannot be read there) | no editor; EVALUATED SECOND: this row beats every row below, `Off`, 2 included | the no-editor screen (section 14.7), heading "This sketch can't be opened here" (no final period, as in 14.7), the reason of the case, [Copy details], [How to open it] and [Close]; no banner (UX-133, UX-134, UX-136) |
| Off | 1 | legacy editor, editable | unchanged |
| NewProjectsOnly | 1 | classic editor, editable, NO Upgrade chip (conversion is unreachable in this mode) | unchanged |
| On | 1 (not yet upgraded) | classic editor, editable, WITH an Upgrade offer unless `ConversionPolicy.refusal` is non-null (D-98: then NO chip and a disabled "Upgrade to the new editor" entry with the reason); v2 only after the user chooses Upgrade (merely opening never converts) | Upgrade chip, then the one-time confirmation (section 14) |
| Off | 2 (where `V2StorageAvailable` is true) | v2 read-only | the `ReadOnly` banner (banner table): primary [Use new]; More: Details, Restore original, Export SVG, Close, Dismiss; Details: "This sketch uses the new editor. Turn on the new editor to edit." |
| NewProjectsOnly or On | 2 | v2 editable | none |
| any | newer than supported (a schema this build does not know, never schema 2) | no editor | "Update SteleKit to view this sketch." with [Copy details], [Check for updates], [Export last backup] (hidden when the project has no backup) and [Close] (sections 14.5, 14.7) |

**Precedence (UX-136; D-117)**: the rows are evaluated top to bottom and the first match wins. The storage-unavailable row is first because nothing can be read there: the read-only banner needs a readable sheet, turning the mode on cannot help, so its Details "Turn on the new editor to edit.", its [Use new] and the Off radio's clause "Sketches already in the new editor open read-only" never appear on such a platform. The newer-schema row comes next (any mode, any platform where the file can be read).

A NEW sketch is created as v2 when the mode is `NewProjectsOnly` and the project is a `NewSketchProject` (the canonical sentence of requirements.md "Flag modes": a project created by this very action, or an all-v2 project whose sheet list is complete), or the mode is `On` (and the project holds no technical data that refuses v2); otherwise it is classic and the `ClassicEditor` toast says why. When the build cannot convert (`ConversionOffered` false after an A4 NO-GO) a stored `On` behaves as `NewProjectsOnly`; when it cannot read v2 safely (`ConversionReady` false) every mode behaves as `Off`, and so does every mode while `V2StorageAvailable` is false (iOS, a browser without OPFS atomic replace, a demo graph or no open graph: D-104, D-116; the mode control is disabled with the reason of its case, section 14.1, UX-133) (plan Task 1.4.3d, `FlagModeMatrixTest`).

Read-only banner wireframe (the `ReadOnly` row of the banner table, a state occupant of the one banner slot; the same one-row form at every width):
```
+------------------------------------------------------+
| (i) Read-only: new editor needed.  [Use new] [⋮] |
+------------------------------------------------------+
   ⋮ = Details (the full reason), Restore original, Export SVG, Close, Dismiss
```
**[Use new] (U3; ADR-005 items 2 and 3; UX-69)** opens the banner variant of the flag-enable confirmation of 14.1 (copy that says reopening unlocks editing THIS sketch, not the `NewProjectsOnly` copy about existing projects) and nothing else: where the build cannot read v2 safely (`ConversionReady` false) it is disabled with the reason "Not available in this build yet", never hidden; the one-row banner has no room for that reason inline, so the disabled button stays focusable and tapping, long-pressing or focusing it opens the persistent help popup of UX-90 with the reason as visible text, and the first line of Details then reads "This version of SteleKit can't edit new-editor sketches yet." (the row's fixed summary names the need, not a cause, so it is true whether the new editor is off or the build cannot read it). The dialog's buttons are [Cancel] and [Turn on and reopen]: the mode `NewProjectsOnly` is stored (the smallest mode that makes the sheet editable and starts no conversion) and the sketch is reopened at once by the same explicit action as the `EngineChange` toast's [Reopen] (it saves pending edits, closes the sketch and opens it again in the editor the router now chooses), so the path is two steps (the banner's [Use new], then the dialog's button), not three, and no editor is ever swapped without an explicit activation; on Cancel nothing changes. The `EngineChange` toast ("Applies next time you open it." with [Reopen]) is for a mode changed in Settings while a sketch is open.
[Restore original] appears only where the build contains the restore action (Story 6.2.6) and the sheet has a `backup/<uuid>.v1.json`; a build without Story 6.2.6 (no separate reader-only release ships since 2026-11-17) creates no backup, so there More lists Details, Export SVG, Close and Dismiss. Where it appears it is the same confirmed action as section 14.6 (it first states that new-editor changes since the upgrade are discarded and that a copy is kept). The banner is dismissible: Dismiss hides it for the session (the edit tools stay disabled with their reason and the entry stays in the diagnostics list) and it returns on the next open. The routing table, this wireframe and the banner table list the same actions.
Read-only mode keeps pan, zoom, select (for inspection), copy, export. Edit tools are disabled with the reason as tooltip/semantic description.

Lease conflict (S1.4.3, the `LeaseConflict` row of the banner table): if the sheet is open in the other editor, show "This sketch is open in the other editor." with [Open there] and, under More, Details and Close. **A second browser tab (UX-142, UX-149; D-123, D-144; defence in depth, a persistent web graph refuses a second tab at boot first, 14.9, D-149).** On the web build, where the driver lock is not taken, a graph whose sheet writes are held by another tab uses the variant row `LeaseConflict:OtherTab` of 14.8 (the same rank 4 and State tier as `LeaseConflict`, never the summary "Open in the other editor.", which would read as classic versus new editor and offers nothing to do): the sheet opens read-only, the summary is "Edited in another tab." (the cause is in the summary, not only in Details), there is NO primary action (there is nothing to do here until the other tab closes, D-99), More holds Details and Close, and its Details read "Another tab is editing sketches in this graph. Close that tab to edit here."; when the other tab closes the banner clears by itself after this tab has reloaded the saved sketches, and a polite status-line message says "Editing is on again."

### Flow: open sketch
1. User opens a sketch from a project/page. Router decides (table above). Loading state: skeleton canvas + indeterminate progress, cancel via back.
2. Success: scene appears fit-to-content; focus lands on the canvas (keyboard focus ring visible).
3. Failure (corrupt sheet, `Corrupt` read): full-panel error "Couldn't open this sketch (file damaged)." Actions: [Open backup copy] if `backup/<uuid>.v1.json` exists, [Open legacy editor], [Close]. Details in an expandable "Technical details" with reason code.

### Error states

| State | User sees | Exit |
|---|---|---|
| Load fails (corrupt) | panel above | Open backup / legacy / Close |
| Sheet busy (lease) | "Open in the other editor" | Switch / Close |
| Newer schema | "Update SteleKit to view this sketch" | [Copy details] [Check for updates] [Export last backup] [Close] (sections 14.5, 14.7) |
| Save failed | top-bar status "Failed" (full text "Save failed" in the tooltip and semantics) + the Blocking `SaveFailed` banner with Retry | Retry; work kept in memory; leaving the screen asks "Discard unsaved changes?" with Retry first |
| Flag flipped off mid-session | no editor swap (S1.4.3); the `EngineChange` toast "Applies next time you open it." | [Reopen]; Dismiss is in its More menu (every toast has one, section 1) |

---

## 2. Tool strip

### Tool inventory and grouping

| # | Tool | Strip group (chrome table) | Shortcut | Hint line shown while the tool is active (keyboard variant) |
|---|---|---|---|---|
| 1 | Select | single | V, 1 | "Click to select, drag to move, Shift adds. Tab cycles elements." |
| 2 | Hand | single | H | "Drag to move the view." |
| 3 | Rectangle | Shapes | R, 2 | "Drag to draw. Shift = square. From centre: the chip." |
| 4 | Diamond | Shapes | D, 3 | "Drag to draw. Shift = equal sides. From centre: the chip." |
| 5 | Ellipse | Shapes | O, 4 | "Drag to draw. Shift = circle. From centre: the chip." |
| 6 | Arrow | Lines | A, 5 | "Drag or click points. Start or end on a shape to bind. Ctrl (Cmd on macOS) = don't bind." |
| 7 | Line | Lines | L, 6 | "Click points; double-click or Enter to finish. Shift = 15 degree steps." |
| 8 | Freedraw | Lines | P, 7 | "Drag to draw. Pen pressure is kept." |
| 9 | Text | single | T, 8 | "Click to type. Esc to finish." |
| 10 | Image | single | 9 | "Choose a file, then click to place it." |
| 11 | Frame | single | F | "Drag around elements to frame them." |
| 12 | Eraser | single | E, 0 | "Drag across elements to erase; tap one on touch. Esc cancels." |
| 13 | Point | Geometry (technical sheets) | X | "Click to place a point. It snaps to endpoints and lines." |
| 14 | Arc | Geometry (technical sheets) | C | "Click the start, click the end, then click a point on the arc." |
| - | Constrain (a menu, not a tool; it does not change the active tool) | Constrain | K, Ctrl+Shift+K (web: Ctrl+K) | opens when 1+ elements are selected (section 9); items that need two elements are disabled with a reason |

All 14 tools of requirements metric 1 are in this table (UX-100). The digits are Excalidraw's tool digits in Excalidraw's order: Select 1, Rectangle 2, Diamond 3, Ellipse 4, Arrow 5, Line 6, Freedraw 7, Text 8, Image 9, Eraser 0 (ten tools; Task 0.1.5d re-checks the order against the live shortcuts dialog, U8); Hand (H), Frame (F), Point (X) and Arc (C) have letters only, every tool has a letter, and the shortcuts sheet (section 17) lists all 14. Point and Arc exist only when OD-8 is accepted; if it is declined the table has 12 rows and the Geometry group disappears.

**Alt (UX-109).** No function is Alt-only. Alt is an optional accelerator on Windows and macOS, native or in a browser (From centre = Alt+drag, Duplicate = Alt+drag), and is NOT used on Linux, native or in a browser, because Linux window managers take Alt+drag whichever app has focus: the web build detects Linux from the user agent, and Linux hints never name Alt and lead with the equivalent (the From centre chip, Ctrl+D). `HintTextAuditTest` fails on a Linux hint (native or web) that names Alt and on any Alt-only function.

Pinned (never scroll off): Undo, Redo, Delete. When the strip is grouped it shows one representative per group (the group icon shows the last-used tool). A group is a SPLIT BUTTON (section 1): a main target that activates the last-used tool and a separate flyout target (the chevron), each `toolTarget` in every pointer class (the strip never reflows), side by side in the dock and stacked in the rail. Its flyout opens by tapping or clicking the flyout target, by long-press (touch), by right-click or the stylus barrel button (pointer), or by the Down arrow or context-menu key on the focused group button (keyboard). Alt+Click and Alt+drag are NOT used (UX-109): Alt+drag is the window-move gesture in KDE and other Linux window managers, so the click would never reach the app.

```
Grouped dock (bottom), technical sheet; the Finger drawing chip is in the top bar, not here:
 [↶][↷][🗑] | [↖] [✋] [▭|▾] [↗|▾] [T] [🖼] [▢ frame] [⌫] [⌖|▾ Geom] | [Constrain] [Q lock]
              flyout opens upward:
              +----------------+
              | ▭ Rectangle  R |
              | ◇ Diamond    D |
              | ◯ Ellipse    O |
              +----------------+
```
(Icons shown for layout only; each button has a text label in semantics and a visible label in flyouts. `|▾` is the separate flyout target of a split button. The six singles are Select, Hand, Text, Image, Frame and Eraser; the three split groups are Shapes, Lines and Geometry, which exists on technical sheets only. The counts and extents are computed from the chrome table.)

**Constrain and Geometry at compact width (UX-118; D-8).** The dock shows a [Constrain] button whenever one or more elements are selected (disabled with its reason "Select an element to constrain" otherwise) and the overflow menu always lists "Constrain (K)", so constraints are reachable by touch alone. On technical and converted sheets a [Geom ▾] flyout group holds Point (X) and Arc (C); on diagram sheets Point and Arc stay in the overflow menu under "More tools". When the strip is flat (section 1) Point and Arc are flat tools (UX-97).

**One touch-target floor (UX-46, UX-92; D-17).** Every interactive hit area (controls, selection handles, constraint glyphs, flyout targets, picker markers) is at least 44 dp whenever a touch or pen pointer is present (a touch-primary device, or a touch or pen event has been observed), and 24 dp only on a mouse-only device (WCAG 2.5.8 minimum). The pinned Undo, Redo and Delete are 48 dp (chrome table) in every pointer class; the strip and rows are laid out at the table's sizes in every pointer class, so the floor changes hit areas only and never moves a control under the pointer (section 1). The visible handle (8 dp) or glyph (24 dp) may be smaller than its hit area. Earlier mixed statements (24 dp here, 44-48 dp there) are replaced by this one rule.

Discoverability (UX-97, UX-109, plan Story 3.6.8): ALL 14 tools are shown flat whenever `flatExtent` fits the strip extent (section 1, ONE rule for every width class; the table says which windows are flat); otherwise every group is a split button whose separate flyout target (a chevron at the touch-target floor) carries the semantics hint "more tools"; the flyout opens by tapping the flyout target, long-press, right-click or barrel button, or the keyboard (Enter/Space on the focused group button activates the group's last-used tool; Down arrow or the context-menu key opens the flyout; arrow keys inside, Enter picks, Esc closes). The first time the editor opens on a device a one-time coach mark (the `CoachMark` banner row: "New here? Quick tips." with [Show me] and [Got it]) offers the three-step walkthrough specified ONCE in section 22 (that section owns every step text, so none is repeated here); the row is dismissible, keyboard-operable, never repeats, and retires when [Show me] or [Got it] is used or when the walkthrough is started from the overflow entry "Show tips". There is no "?" control.

### Interaction
0. **Hint text follows the input device (UX-124).** The hint strings in the table above are the KEYBOARD variants. When the last pointer was touch or pen and no hardware keyboard is attached, the hint line drops keyboard-only phrases (Shift, Alt, Ctrl, Esc, Enter, Tab, double-click) and shows the touch variant from the same string table: for example "Drag to draw." with the modifiers as toggle chips in the action chip row of section 1 ("Square", "From centre", "Don't bind", "15 degrees"), and "Tap points; tap Done to finish" for Line with a [Done] chip in the same row (the hint row is text only, so a touch user never needs a control that may be collapsed). A hardware keyboard attached (or a key pressed) switches back to the keyboard variant. No hint ever tells a touch user to press a key they do not have.
1. Tap/click a tool: becomes active, shows `selected` state plus a hint line ("Drag to draw. Shift = square. From centre: the chip.").
2. After one shape the tool returns to Select, unless tool lock (Q or lock button) is on; lock state shown by a filled lock glyph on the strip button (which can scroll out of view), by "Tool lock on" as the start of the hint line and the live region, and by a "Tool lock" chip in the action chip row while it is on (tapping it turns the lock off), so it is visible and switchable in every layout, including the compact-height rail that has no hint row.
3. Esc or tapping Select leaves the tool; mid-draw Esc cancels the preview with no command (S3.1.1).
4. Switching tool with an in-progress text edit commits the text first.

### Error/edge states
- Tool disabled because sheet read-only or layer locked: button stays focusable, announces "Rectangle, unavailable: read-only sketch" (not hidden; exit: the read-only banner's [Use new], which opens the flag-enable confirmation and applies after reopening).
- Active drawing layer is locked: drawing refused with the `LayerLocked` toast "Locked layer: Walls." [Unlock] and, under More, Switch layer and Details (exit paths for both). [Switch layer] makes the nearest unlocked, visible layer active and announces "Active layer: Base"; if none exists it opens the layer list (plan Task 3.5.2d, UX-86).
- Strip overflow on very narrow windows: scroll with edge fade and a chevron; keyboard users reach every tool by shortcut, or by arrow keys inside the strip. The strip is ONE Tab stop with roving focus (arrow keys move within it, Enter activates, Down opens a group's flyout); Tab and Shift+Tab leave it, and cycle ELEMENTS only while the canvas has focus (section 17).

---

## 3. Canvas input

### Pointer model (S3.1.2)

| Pointer | Idle tool = Select | Drawing tool active |
|---|---|---|
| Mouse | acts (select, drag) | acts (draw) |
| Stylus | acts | acts |
| Stylus, eraser end | acts as the Eraser for that stroke, previous tool restored at pen-up | same (UX-101) |
| Stylus, barrel button held | secondary click (context menu at the pen) | same (UX-101) |
| Touch, one finger | pans view | pans view (no element created) when Finger drawing is OFF; draws when ON |
| Touch, two fingers | pinch zoom + pan | pinch zoom + pan (also while Finger drawing is ON) |
| Wheel | zoom about cursor (Ctrl) / scroll | same |

"Finger drawing" is the ONE name of the touch-draw setting everywhere (view cluster, overflow, hints, semantics; the old name "Pen mode" is retired). When ON, touch acts like a stylus: one finger draws, two fingers pan and pinch. Defaults (UX-80; owner-confirmable): on a device on which no stylus has ever been detected, Finger drawing is ON, so a finger-only phone works without hunting for a setting, with a hint (twice-or-acted, section 16: the `FingerHintOn` row "Finger draws. 2 fingers pan." with [Turn off]; its Details: "Finger drawing is on. Drag to draw; use two fingers to move the view."; [Got it] under More). Once a stylus has been detected and the user has not chosen, the default becomes OFF (finger pans, stylus draws) and the first one-finger drag with a drawing tool shows the `FingerHintOff` row "Finger drags move the view." with [Draw] (its Details: "Turn on Finger drawing to draw with touch."; [Got it] under More). An explicit choice is stored and never overridden.

**The ON-to-OFF flip is announced (UX-102; D-9).** If the user never chose and Finger drawing is at its default ON, the first completed stylus stroke (at pen-up, never mid-gesture) flips the effective setting to OFF and shows, through the notice queue, the `PenDetected` Warning banner of the banner table ("Pen found. Finger off." with [Back on]; Details and Got it under More), announced politely, once per device. Its Details sheet carries the full text: "A pen was detected, so Finger drawing is now off. One finger moves the view; the pen draws." with [Turn Finger drawing back on] and [Got it]. An explicit user choice is never flipped and shows no notice.

**Losing one-finger pan while Finger drawing is ON (UX-102).** With a drawing tool active and Finger drawing ON, one finger draws, so the view moves by: (a) two fingers (drag, always), (b) the Hand tool (H, dock), (c) the edge-pan strip, ONLY where the input spike marked the target Supported (UX-121; geometry below): a one-finger drag that starts in the strip pans instead of drawing, (d) with the Select tool, a one-finger drag on empty canvas pans (marquee is by pen, mouse or the Crosshair marquee), and (e) the pan arrows (UX-120): a pad of four on-screen arrow buttons in the view cluster, each tap pans a quarter of the visible canvas, no drag needed, shown by default (Auto) only while Finger drawing is ON and a touch or pen pointer is present, as one 44 dp "Pan" button that opens the pad. The think-aloud pass (plan Story 6.2.8, task 5; assumption A5) checks that people find one of these.

**The Finger drawing state is always visible (UX-80, UX-102).** The state is a labelled chip "Finger: On" or "Finger: Off" PINNED in the top bar (chrome table, `topbar`), which never scrolls or collapses at any width or layout, so it is on screen even when the tool strip scrolls; the view cluster offers the same toggle with the full label "Finger drawing: On". Text plus icon (never colour alone), semantics "Finger drawing, on, switch"; activating it toggles the setting; it is never only in the overflow menu.

**Edge-pan strip: geometry, system gestures and the safe default (UX-102, UX-121; D-18; measured by plan Task 0.1.4d).** One geometry, replacing the earlier "outer 24 dp" and "inside the inset" statements, which contradicted each other and the 44 dp touch floor:
- **Placement**: measured from the screen edge, the first `g` dp are the system gesture inset (`WindowInsets.systemGestures`; the system owns drags that start there and the app does not hit-test it). The strip starts at the inset line and is 44 dp wide (the touch floor is the HIT area); its outer 24 dp, next to the inset, are the VISIBLE band, the inner 20 dp are hit-only. Where `g` is 0 (button navigation, desktop touch) the strip starts at the edge. The spike (Task 0.1.4d) measures drags that START inside the 44 dp strip at the requested span, never drags that start inside the inset (the system's zone, not measured as pass or fail), and records `g` per device. A one-finger drag that starts inside the 44 dp pans and creates nothing; one that starts further in draws.
- **Visible**: the band is low-emphasis but its edge line and hatch meet 3:1 non-text contrast against the canvas in light, dark and high-contrast themes (not merely "faint"), carries the semantics "Edge pan area" and the label "Pan" appears on first use.
- **Protected span, not the whole edge**: Android caps the total excluded height per edge (documented at 200 dp since API 29; the spike re-verifies it). The strip is therefore a SPAN, not a full-height edge: a segment of at most the granted height (200 dp by default), vertically centred on that edge and drawn and hit-tested ONLY over that segment, so what the user sees is exactly what works and the rest of the edge keeps drawing. Outside the span a drag draws.
- **Other targets**: iOS defers screen-edge gestures (`preferredScreenEdgesDeferringSystemGestures`; compile-only in v1, device behaviour UNVERIFIED so the default there is Off); web sets `touch-action: none` and `overscroll-behavior: none` (a Safari iOS edge swipe cannot be blocked: documented, default Off there).
- **SAFE DEFAULT (proactive, not only reactive)**: the strip is OFF unless Task 0.1.4d recorded the target class as Supported (exclusion honoured in a real drag). `EdgePanPolicy.defaultSide(target)` returns Off for every target the spike did not mark Supported, for Android below API 29, and for any device where exclusion could not be requested; with Off, the ways to pan are two fingers, the Hand tool (pinned in the compact dock) and the pan arrows (UX-120), and the Finger drawing hint names them. A negative spike result therefore ships a working app with the strip simply absent.
- **Reactive backstop** (only where Supported): if the system cancels a one-finger drag that starts in the strip twice in one session, the strip hides for the session, the device is persistently marked `ExclusionFailed` so the NEXT session starts with Off, and a one-time notice says "Edge pan stopped working here. Use two fingers, the Hand tool or the pan arrows.". The setting "Edge pan side: Both / Left / Right / Off" lets the user choose; its default where Supported is ONE side, Right, on Compact width (two 44 dp strips would cost 88 of 360 dp of drawing width) and Both from 600 dp, and Off elsewhere. Two-finger pan, the Hand tool and the pan arrows never depend on the strip.

**Palm rejection on touch laptops and tablets (UX-102).** While a pen or mouse stroke is active every touch is ignored; with Finger drawing ON and no pen, a touch the platform classifies as a palm (`TOOL_TYPE_PALM`, or a palm `ACTION_CANCEL`) is ignored. The app has no palm detector of its own (no size threshold): where the OS or driver rejects palms the app relies on that, and where neither does the user turns Finger drawing off or uses the Hand tool; the help page says so. Device behaviour is a manual row (OD-2).

**Pan without dragging: pan arrows (UX-120; WCAG 2.5.7).** Every drag-based way to pan (one-finger drag, two-finger drag, the Hand tool, the edge strip) needs a single-pointer alternative that is not a drag. The pan pad is four arrow buttons ("Pan up", "Pan down", "Pan left", "Pan right", each at the touch floor) around a centre "Fit" button. **Footprint**: collapsed it is ONE 44 dp "Pan" button in the view cluster; activating it opens the pad above the zoom cluster at the bottom-end, opaque (no translucency): a plus-shaped grid of five 44 dp cells (corner cells of its 3 x 3 box are not part of the pad) where that covers at most the table's share of the canvas, otherwise a single row of four arrows (chrome table, "Pan pad variants"; the computed table gives the variant and the coverage per window, so the pad never covers most of a minimum canvas). It has no auto-collapse timer: it closes when the Pan button is activated again, on Esc or on a tap on the canvas. **When shown (Auto)**: the "Pan" button is shown only while Finger drawing is ON and a touch or pen pointer is present (a touch-primary device, or a touch or pen event has been observed); on a mouse- or keyboard-only desktop it is NOT shown by default and is reached from the 100% menu and the overflow menu ("Pan arrows"); "Pan arrows: Auto / Always / Never" overrides it, and whether the edge strip is on does not matter. **Never overlaps**: on a side where the edge strip is active the view cluster and the open pad are inset by the strip's gesture inset plus 44 dp from that edge, so the protected span (even when vertically centred) and the pad cannot overlap. **Step**: a tap pans a quarter of the visible canvas (instantly under reduced motion); press-and-hold starts repeating after 400 ms and then repeats every 150 ms (a hold is not a path-based drag; under reduced motion each repeat is an instant jump of the same step, never an animation); a double-tap pans three quarters. The pad is a canvas overlay counted in the chrome budget (section 1).

**Keyboard panning (UX-105).** With the canvas focused and nothing selected (or the Hand tool active): arrow keys pan 40 dp per press (Shift 200 dp), PageUp/PageDown one screen, Home fits to content; Ctrl+arrow (Cmd on macOS) pans even with a selection. Announced only as the settled zoom and centre.

```
View cluster (bottom-end), one row of seven where the overlay budget allows, else two rows (zoom trio above the four toggles; section 1):
                               [ - ] [ 100% ▾ ] [ + ]   [⊞ grid] [🧲 snap] [✋ Finger drawing: On] [Pan]
100% menu: Fit to content | Zoom to selection | 50% | 100% | 200%
```

### Flow: draw a box with touch on a phone
1. Select Rectangle (dock). On a finger-only phone Finger drawing is already ON, so a one-finger drag draws and two fingers pan; with Finger drawing OFF the hint reads "Drag with a pen or mouse. With touch, turn on Finger drawing, or use Crosshair."
2. With Finger drawing OFF: a one-finger drag pans the view and no element is created (the hint follows the recurrence table of section 16: up to twice, ended early by any action).
3. User taps the [Crosshair] chip (action chip row): enters non-drag placement (section 10): tap places corner 1, nudge pad adjusts, [Place point], second corner, [Apply].
4. Result: rectangle committed as one undo step.

### Error/edge states
- Palm touch while stylus drawing: touch events during an active stylus stroke are ignored.
- Second pointer during a draw: preview cancelled, no command, tool back to Idle (S3.1.1). Visible cue: preview fades out; status "Drawing cancelled".
- Hover (mouse move, stylus hover): shows snap target ring and guide lines; absent on touch, so nothing depends on it.
- Zoom limits: at 1% or 3000% the +/- buttons disable and announce "Minimum/Maximum zoom".
- Empty scene: canvas shows a centred empty state "Pick a tool and draw, or import an .excalidraw file" with [Import] and [Keyboard shortcuts] (opens the shortcuts sheet; the earlier "Take a tour of shortcuts" is dropped, there is no tour); disappears on first element.

---

## 4. Selection overlay and transform

```
        rotate ○
   ┌──□──────□──────□──┐   8 resize handles (hit area >= 44 dp with touch or pen, 24 dp mouse-only;
   □                    □    visible 8 dp; fewer on small selections, below), rotation handle,
   │   selected shapes  │    bounding box dashed for groups
   □                    □
   └──□──────□──────□──┘
 Selection bar (below canvas, never over the shape): [Duplicate][Group][Align ▾][Order ▾][Transform ▾][Lock][Delete]
```

### Flow: resize two elements
1. Select-tool marquee over empty area or Shift+click to add: both selected, group bounds shown.
2. Drag right handle: both scale about the left edge; live dimension readout near the handle ("w 240").
3. Release: one undo step (S3.2.2). Undo restores both.

### Selection semantics
- Click on a grouped element selects the outermost group; double-click descends one level; Ctrl+Shift+G ungroups one level (S3.5.1).
- Shift+click toggles membership; Esc deselects; Duplicate is Ctrl+D or the selection-bar button (Alt+drag is an optional extra on Windows and macOS only, native or web, section 2); "From centre" is a chip in the action chip row.
- **Handles on small elements (section 1, computed tiers).** The handles shown depend on the shorter side of the selection on screen: all eight resize handles and the rotation handle on large selections, the four corner handles and the rotation handle on medium ones, none on small ones; on a small selection the outline shows and the status line says "Zoom in to use handles", while the selection bar's "Transform" menu, the Stats fields and Crosshair mode (section 10) still resize, rotate and move it. No two shown hit areas ever overlap (`HandleLayoutTest`).
- **ONE selectability rule (UX-12).** The canvas (pointer, marquee, Select all, Tab traversal) never selects a locked element or an element on a hidden layer. Two exceptions, both so a locked thing can be found and fixed: the Elements list and Go to element (section 8) select a locked element (it shows [Unlock] and can be renamed), and Lock itself leaves the just-locked elements selected until the selection changes. An element of a hidden layer is not selectable even from the list: its row says "(hidden)" and offers [Show layer].

### Error/edge states
| State | User sees | Exit |
|---|---|---|
| Edit on locked layer | the `LayerLocked` toast (or the same text inline) "Locked layer: Walls." | [Unlock] (one tap), dismiss |
| Hide layer that holds the selection | selection cleared; announcement "Selection cleared: layer Walls hidden" | Undo (hide is a command) |
| Resize to zero | clamps to 1 unit; no error | n/a |
| Drag off-canvas | view auto-pans at edge, capped speed | release or Esc cancels (element returns) |
| Selection includes locked and unlocked items (reachable only by the exceptions above: the Elements list added a locked element, or Lock was just applied) | operation applies to unlocked only; the `LockedSkipped` toast "Locked items skipped." (Details: "2 locked elements were not changed") | [Unlock all] unlocks exactly those 2 elements as one undo step and re-applies nothing automatically (the user repeats the action); or dismiss (plan Task 3.5.2d, UX-86) |

---

## 5. Drawing shapes, lines, arrows and binding

### Interaction table

| Tool | Gesture | Modifiers |
|---|---|---|
| Rectangle/Diamond/Ellipse | drag corner to corner | Shift square/circle; From centre by the chip (Alt on Windows and macOS only) |
| Line | click points, double-click or Enter to finish; or drag for two-point | Shift 15 degree steps |
| Arrow | drag, or click points; Round type for curves | Ctrl (Cmd on macOS) disables binding on drop |
| Freedraw | drag; stylus pressure stored | |
| Frame | drag; adopts elements fully inside | |
| Point | click (or Crosshair place); snaps to endpoints and lines; a snap to a line endpoint adds Coincident when auto-constrain is on | Ctrl suppresses the snap constraint |
| Arc | click start, click end, click a point on the arc (three points); collinear points commit nothing ("Nothing drawn"); Esc mid-way cancels | Ctrl suppresses snap constraints |
| Eraser | drag across elements; touched elements preview at 30% opacity with a dashed outline and are deleted on release in one undo step; on touch with Finger drawing OFF, tap one element to erase it; non-drag: select then Delete, or Crosshair > Erase > Apply | Esc or a second pointer cancels; locked elements are skipped ("2 locked elements were not erased") |
| Hand | drag moves the view (any pointer) | |
| Select | click, Shift+click, marquee drag, Ctrl+D or the selection-bar Duplicate (Alt+drag on Windows and macOS only) | |
| Text / Image | click to place; Image opens a file picker first | |

### Arrow binding flow
1. Arrow tool, drag from near Box A: a highlight outline appears on A within snap radius (8 px at zoom 1) and a dot on the nearest edge/fixed point.
2. Drag to Box B: B highlights; release binds both ends (`Orbit`, fixed point).
3. Moving A or B later: the arrow end follows in the same undo step as the move.
4. Delete B: arrow becomes free-ended at its last position; the `ArrowUnbound` toast "Arrow unbound from shape." [Undo] (the deleted shape's name is in Details).
5. Non-drag: select the arrow, choose "Bind end..." in the selection bar (in the compact-height rail, where the selection bar is collapsed, in the top-bar Selection menu; in Crosshair mode it is the Bind end action): a list of nearby shapes by label ("Box: API", "Box: DB") picks the target (S3.6.3).

### Error/edge states
- Target out of reach / none under pointer: arrow is committed free-ended; no error.
- Binding to a locked shape: allowed to bind visually; if the user tries to edit the locked shape's position, section 4 message applies. (Decision for Phase 4: whether a locked target should refuse binding; default permit.)
- Elbow arrows (import only): rendered as polyline with a tooltip "Elbow routing isn't supported; drawn as a polyline" (S3.3.1b). Exit: dismiss; no data loss on export (the `elbowed` flag is kept).
- Zero-length line/arrow: auto-removed on release with status "Nothing drawn".
- Over the 5,000-element cap: see section 15.

---

## 6. In-place text editor

```
   ┌────────────────────┐
   │ Authentication     │   <- BasicTextField overlay at the transformed rect,
   │ Service|           │      grows with content; IME composition visible
   └────────────────────┘
   [font ▾][size ▾][align ◐]  mini bar above overlay on compact
```

### Flow
1. Text tool, tap empty canvas (or double-click a shape for container text, or Enter on a selected shape): overlay opens, keyboard appears; canvas auto-scrolls so the overlay stays above the IME (soft-keyboard inset).
2. Typing; Enter = new line; Ctrl+Enter / tap outside / Esc commits (Esc on empty cancels). Hint "Esc to finish" on a keyboard; on touch without a hardware keyboard the mini bar carries a [Done] button, the hint reads "Tap Done or tap outside to finish", tapping outside the overlay commits, and no hint mentions Esc, Ctrl or Enter (UX-124). [Done] commits exactly like Esc on a non-empty text, and an empty text is removed as before.
3. Container text: the container grows to fit; deleting the container deletes its text in one step.
4. Arrow label: double-click an arrow adds a midpoint label.

### Error/edge states
- Empty text on blur: element auto-deleted, nothing created, no message.
- Very long text (> 10,000 chars, matching the plan's size sanity): truncation is NOT done silently; paste is refused with "Text too long (max 10,000 characters)" [OK], existing text kept.
- Missing font on a platform: falls back to the bundled family; no user-visible error (logged).
- Zoom 2.5x: overlay is positioned by the same transform; text scale matches canvas.
- Keyboard shortcuts are suspended while the overlay has focus (T/R/V letters type text).

---

## 7. Style panel and element stats

```
+ Style ----------------------------+
| Stroke   ● ● ● ● ● [+]  (named)   |
| Fill     ○ ● ● ● ● ● [+]          |
| Fill style  [Solid][Hatch][Cross] |
| Stroke width [Thin][Bold][Extra]  |
| Stroke style [Solid][Dashed][Dot] |
| Sloppiness  [Clean][Sketchy]      |  <- Clean/Sketchy per element, sheet default in properties
| Edges       [Sharp][Round]        |
| Font        [Hand-drawn][Normal][Code] size [S M L XL]
| Arrowheads  [start ▾][end ▾]      |
| Opacity ───────●──── [100] [-][+] |
| Layer       [Base ▾]              |
+ Stats ----------------------------+
| x [ 120 ] y [ 40 ] w [ 240 ] h [ 80 ] angle [ 0° ]
| (technical elements: DimensionField, "Not a length. Try 12 3/16\"")
+-----------------------------------+
```

Behaviour:
- Edits the selection; with nothing selected, edits the active tool's defaults (S3.5.4). Panel heading says which: "Selection (3)" or "Defaults for Rectangle".
- Mixed values show a "Mixed" chip, not a blank.
- Colours have names ("Red 600") for TalkBack and tooltips.
- Opacity is a slider PLUS a numeric field and -10 / +10 buttons (a slider is a drag, WCAG 2.5.7); the layer opacity slider of section 8 has the same companions.
- Numeric fields commit on Enter/blur; invalid input shows inline supporting text and keeps the previous value (exit: Esc reverts).

Selection stays visible while styling (UX-125): when the panel opens as a bottom sheet (compact) or side sheet (compact height) with a selection, the canvas pans (never zooms) so the selection's bounds sit inside the visible area above or beside the sheet; if the bounds are taller than the visible area its top edge stays visible and the status line says "Selection is larger than the visible area". The pan is a view change, not an undo entry, and the previous view is restored when the sheet closes unless the user moved the view meanwhile. Opening the Style tab never hides the element being edited, so a colour or size change is seen as it is made.

Error/edge: stroke width not available for an element kind (e.g. image) is disabled with reason; opacity on a layer-opacity-affected element shows "Effective 50% (layer 50%)".

---

## 8. Layers panel

```
+ Layers ------------------------------------+
| [+ New layer]                               |
| ≡  ◉ eye   🔒  Walls          12  [↑][↓] ⋮  |   ≡ drag handle; ↑↓ non-drag reorder
| ≡  ◉       🔓  Base            3  [↑][↓] ⋮  |   ⋮ = Rename, Opacity, Delete, Isolate
| Opacity (Walls) ──────●──── 50%             |
+---------------------------------------------+
```
- Hidden until a second layer exists (S3.5.2); a "Layers" item in the overflow menu creates the first extra layer.
- Eye and lock are `toggleable` with merged semantics: "Layer Walls, visible, switch".
- Row tap sets the active layer for new elements (active row has a left bar plus "Active" text, not colour only). "Move selection here" lives in the row menu.
- Delete layer with elements: confirm dialog "Delete layer 'Walls' and its 12 elements?" with [Delete layer and elements] [Move elements to Base then delete] [Cancel]; undoable.
- Reorder = one undo step. Opacity slider has numeric field.

Error/edge: deleting the last layer is disabled with reason; name collision on rename shows "A layer with this name exists" and keeps editing; locked + hidden both allowed.

### Element names and the Elements list (UX-110; plan Story 3.5.7)
Every element has a stable readable name: its kind plus a per-kind number, "Line 3", "Rectangle 1", "Circle 2", "Point 4". Numbers are counted per kind per sheet and are NEVER reused after a delete, so "Line 3" in a message always means the same line. A user may rename an element (F2 or Enter in the list; max 80 characters; a name already used shows "An element with this name exists"). Where names are visible:
- **On the canvas**: a name tag chip ("Line 3") appears at the element's anchor while it is selected or is named by a message (constraint conflict, Go to element, Pick target). The view toggle "Show names" (view cluster and overflow, per sheet; default ON on scaled/technical and converted sheets, OFF on diagram sheets) shows tags for every element in view; overlapping tags collapse to a count badge by the same rule as constraint glyphs (UX-106).
- **In the Elements tab** (side pane or bottom-sheet tab, always present): layers as expandable groups, elements in z order, each row = kind icon + name + text label if any + "locked"/"hidden" as text and icon; tapping a row selects the element and pans to it (a locked element is selected and shows [Unlock]; a row of a hidden layer is dimmed, says "(hidden)" and offers [Show layer] instead of selecting, the one selectability rule of section 4); Enter or F2 renames; the list is virtualised.
- **In messages, semantics and search**: "Parallel conflicts with Horizontal on Line 3", the screen-reader label "Line 3, horizontal", and Go to element (plan Story 3.6.5) all use the same names.

---

## 9. Constraint panel, glyphs and status line

### Constrain flyout (available when 1 or more elements are selected)

```
 Constrain ▾
   Coincident        Horizontal / Vertical
   Parallel / Perpendicular   Equal   Tangent   Symmetric   Fixed
   Dimensions: Distance  Angle  Radius/Diameter   [ ] Reference (driven)
```
What can be constrained is ONE rule (UX-81; plan Task 4.2.1d): constraints apply to technical geometry only: line and polyline, rectangle, circle (an ellipse with equal radii; an ellipse with unequal radii is not constrainable), arc, point. Text, arrows, images, freehand and frames are never constrainable; for them every Constrain item is disabled with the ONE string `SketchReasonText.NotConstrainable`, "Text, arrows, images, freehand and frames can't be constrained. Use align and distribute instead." (the same text in the flyout, the touch-help popup, the menu item semantics and the `NotConstrainable` command error; no other wording of this rule exists) Single-element items (Horizontal, Vertical, Fixed, Radius or Diameter on a circle, Distance as a line's length) are enabled for one selected element. Items invalid for the selection are disabled with a reason ("Tangent needs a circle or arc and one more element", "Parallel needs two lines"). With nothing selected the trigger is disabled: "Select an element to constrain". Circles are ellipses with equal radii; points and arcs are their own element kinds (plan OD-8). At compact width the Constrain trigger is the dock button and the overflow entry of section 2 (UX-118).

### Panel and canvas

```
+ Constraints ----------------------------+  status line (live region, also on canvas bottom):
| 2 degrees of freedom (2 moves still possible) |   "2 degrees of freedom" | "Fully constrained" |
| ✓ Horizontal   Line 1                   |   "Over-constrained: 1 conflict"
| ✓ Distance 120 Line 1          [ref?]   |
| ⚠ Parallel (redundant) Line 2, Line 3   |   row icon + text state: OK / Redundant / Conflict
| ✖ Perpendicular (conflict) Line 3       |   row tap highlights geometry on canvas
|                       [Delete] [Make reference]
+------------------------------------------+
Canvas: glyphs next to geometry (small H, ∥, ⟂, =, ◯ tangent icons; 3:1 contrast, hit area at the touch-target floor)
        fully constrained = dash style + colour; free geometry = default; conflict = glyph ✖ + dash + colour
```
Plain-language wording (UX-83): "N degrees of freedom" always carries the gloss "(N moves still possible)"; "Fully constrained" carries "(nothing can move)"; a Details expander explains "A degree of freedom is one way the sketch can still change shape or move." The status line, and every degrees-of-freedom text, is shown ONLY on a sheet that has at least one constraint or is a technical sheet (UX-31); on a plain diagram sheet with no constraints nothing about degrees of freedom appears, so a free-form diagram never reads "54 degrees of freedom".

State encoding (P3): fully constrained: solid-dash pattern A + check glyph; free: plain; redundant: warning glyph; conflicting: cross glyph + thicker alternating dash. Colour is additive.

### Flows
**Add a constraint (happy path)**
1. Select two lines, open Constrain, pick Parallel.
2. Solver runs on a transient scene; geometry animates to the solution (instant if reduced motion).
3. One transaction commits the constraint and solver-moved geometry; status updates.

**Auto-constraint on draw (S4.2.2, UX-82)**: an explicit toggle "Auto-constrain while drawing" (view cluster and overflow, stored per sheet). Default OFF on diagram sheets, ON on scaled/technical sheets and sheets converted from v1 (a converted sheet comes from the dimension-centric v1 editor, whose users expect endpoints to join; owner-confirmable D-2, one Ctrl or chip tap suppresses it per shape). When ON: ending a line on another's endpoint adds Coincident; a brief `AddedConstraint` toast "Constraint added." [Undo] (the kind and the elements are in its Details); near-horizontal (within 2 degrees) adds Horizontal. Suppression: Ctrl on desktop, and on touch a one-shot "Free draw" chip (applies to the next shape only). It only ever considers constrainable elements, never text or arrows.

**Dimension entry**: choose Distance, the typed field opens on the dimension label (`DimensionField`), Enter commits; Esc cancels; value like "12 3/16\"" accepted on technical sheets.

### Smart dimension: a suggested dimension from the selection (UX-115; plan Story 4.2.7, Tasks 4.2.7c and 4.2.7d; D-21)
The most common constraint work is "give this a size". With one line selected a chip in the action chip row (section 1) reads "Length 6 in? [Dimension]" (the current value in the sheet's unit); with one circle, "Radius 1/2 in?"; with a circle and a line selected, "Centre to edge 1 in?"; with nothing selected and degrees of freedom left, "Suggested: Width of Line 1 [Dimension]" offers the first unconstrained length or radius. Tap or click the chip, or press Enter or D while it has focus: the label editor of "Editing an existing dimension" opens on the new label with the current value selected; type the real value, Enter commits a driving dimension in one undo step (a duplicate redirects to the existing label; Replace existing applies); Esc commits nothing. A selection with no natural dimension (text, an arrow, two lines) shows no chip and the Constrain menu stays the path. The chip never adds a constraint until a typed value is committed. This is the Fusion/Shapr3D-style single-select aid; FreeCAD's Dimension tool and Shapr3D's tap-to-dimension are INFERRED references, not re-verified here (U8 class).

### Error states (over-constrained and friends)

| State | User sees | Exit |
|---|---|---|
| Conflict (new constraint contradicts existing) | New constraint rejected, geometry unchanged. Inline card: "Parallel conflicts with Horizontal on Line 3." Both highlighted | [Remove new constraint] (default), [Replace existing] (only when the conflicting constraint is a dimension of the same kind on the same target: edits it in place, UX-99), [Make it a reference dimension] (dimension kinds only), [Show conflicts], Esc dismisses = reject |
| Redundant (consistent, unnecessary) | Warning, constraint added, row flagged "Redundant" | [Delete] or leave |
| Non-convergence on commit | Geometry stays at last good pose; message "Couldn't solve these constraints. Nothing changed." | [Undo constraint], [Show conflicts], dismiss |
| Non-convergence during drag | Element freezes at last valid pose, transient toast "Can't satisfy constraints here" | release returns to last good; no partial/NaN result |
| Solve slow (> 100 ms) | "Solving..." text in the status row (a static label under reduced motion, UX-47) and a [Cancel] chip in the action chip row, so the control survives a collapsed status row | Cancel restores last good |
| Constraint applied to unsupported element (text/arrow/image/freedraw/frame) | menu item disabled with `SketchReasonText.NotConstrainable`: "Text, arrows, images, freehand and frames can't be constrained. Use align and distribute instead." | alignment tools |
| Over-constrained on open (status "Over-constrained: N conflicts") | the status line and an inline card name the conflicting constraints; this state can ONLY arise when a sheet is loaded with conflicting constraints (a `.excalidraw` import carrying constraint `extras`, an external edit of the sidecar file, or a sync merge of two devices' edits); interactive edits reject a conflicting constraint at once (first row), so editing alone can never reach it. Geometry is shown as stored and nothing is moved until the user resolves it | [Show conflicts], [Remove conflicting constraint] (one undo step), [Make it a reference dimension] for dimension kinds; the sheet stays editable |
| Delete geometry that has constraints | constraints removed in the same step; toast "Removed 2 constraints" [Undo] | Undo restores all (S4.2.1) |
| Projection mismatch (technical) | "Wall A is off by 1/16 in" in status/diagnostics; "N elements not used for dimensions" | link "Show elements" highlights them |

Non-drag: all constraints are applied from selection + menu; no drag needed.

### Choosing a constraint target: endpoint, vertex, centre or edge (UX-98; plan Story 4.2.6)
Constraints often need a PART of an element, not the whole element: Coincident joins two endpoints, Distance measures between a corner and an edge, Fixed pins one corner, Tangent touches an edge or a circle. A constraint operand is therefore an element plus a part: Start, End, Vertex, Centre, Edge, ArcStart or ArcEnd (Whole when no part is needed).
- **When picking is active**: choosing a constraint from the Constrain menu that needs sub-targets ARMS the picker (the status line says "Pick the first point" and a visible [Cancel] chip in the action chip row disarms it, because touch has no Esc and the status row may be collapsed; Esc cancels on a keyboard; tapping empty canvas does NOT cancel, so a mis-tap cannot lose a first pick); nothing is armed while the Select tool is just selecting, so clicking still selects whole elements.
- **Markers, not colour**: every valid target of the selected and nearby elements shows a marker (visible size 24 dp, hit area at the touch-target floor): a square for an endpoint or vertex, a circle for a centre, a short bar for an edge. The hovered or focused marker is highlighted AND a text chip names it ("Endpoint of Line 3", "Centre of Circle 1", "Edge of Rectangle 2"), announced politely. Invalid targets for the armed constraint are not shown (their reason is available through touch help).
- **Hit-test priority**: point-like parts (point elements, endpoints, vertices, centres, arc ends) beat edges, edges beat whole shapes; then nearest distance; then topmost. Tolerance is 6 px (mouse, stylus) or 22 dp (touch, half of the 44 dp floor) divided by zoom. Where two lines meet at a coincident endpoint, the topmost line's endpoint is chosen and the chip adds "also: Line 4 start".
- **No drag needed**: tap or click a marker; or Tab and Shift+Tab (and arrows) cycle the markers (z order, then Start, End, Centre, Edges) and Enter picks; or open "Pick target", a list of every target of the selected elements by name ("Line 3 start", "Line 3 end", "Line 3 edge", "Circle 1 centre"), which is also the screen-reader path. Esc cancels.
- **Result**: two picks make one constraint in one undo step ("Added Coincident: Line 1 end, Line 2 start"). Picking the same part twice shows "Pick a different target".

### Editing an existing dimension (UX-99; plan Story 4.2.7)
1. Tap or click the dimension's label on the canvas (or focus its row or glyph and press Enter or F2). The value field opens in place with the number selected.
2. Type the new value (technical sheets accept `12 3/16"`), Enter commits as ONE undo step together with the geometry the solver moves; Esc cancels. A value that is not a length shows "Not a length. Try 12 3/16"" and keeps the old value.
3. If the new value cannot be solved: the old value and geometry stay and the card reads "Changing 120 to 40 can't be solved: it conflicts with Horizontal on Line 3" with [Keep editing] [Revert] [Make reference].
4. A reference (driven) dimension's label is read-only: "Reference: driven by other constraints" with [Make driving].
5. Replace existing: choosing Distance on a target that already has a driving Distance does not add a second one; it opens the existing label for editing. If a second same-kind dimension still reaches the solver and conflicts, the rejection card offers [Replace existing] (the existing dimension takes the new value in place, one undo step).

### Dense sketches: declutter and spacing of constraint glyphs (UX-106)
Glyphs (H, parallel, perpendicular, equal, tangent, coincident, dimension values) sit at their geometry's anchor with a hit area at the touch-target floor (visible glyph 24 dp). Where two or more anchors fall within one hit-area size (24 dp mouse-only, 44 dp with touch or pen) at the current zoom they collapse into ONE count badge ("3") that opens a list of the underlying constraints (tap, click or Enter); selecting an element fans out ALL of its glyphs with hit areas of that size and 4 dp gaps even where they would have collapsed; below 50% zoom the glyphs of unselected elements are hidden behind a per-element count badge; the view-cluster toggle "Show constraint glyphs" overrides. No two visible glyph targets overlap, and glyphs keep 3:1 contrast (tested with 200 constraints at random). The large-drawing degradation order of section 15 still applies on top.

### Constraint shortcuts (UX-111)
`K` (single-key shortcuts On) or Ctrl+Shift+K (always; on the web Ctrl+K, because Ctrl+Shift+K opens the Firefox web console and cannot always be intercepted) opens the Constrain menu on the first enabled item. While the menu is open its mnemonics work: C Coincident, H Horizontal, V Vertical, L Parallel, P Perpendicular, E Equal, T Tangent, S Symmetric, F Fixed, D Distance, A Angle, R Radius/Diameter, X Reference toggle. These are menu navigation, not canvas single-key shortcuts, so they are safe with single-key shortcuts Off. Delete on a focused constraint row or glyph removes it; Enter or F2 on a focused dimension edits its value.

### Walkthrough: the bracket plate (requirements Job 2; UX-98, UX-99, UX-106, UX-115)
New technical sheet (scale set), so auto-constrain is ON (UX-82). Units: 6 in = 96 units, 4 in = 64, 1/2 in = 8, 1 in = 16 (1 unit = 1/16 in). Mouse path; the touch path replaces each drag with Crosshair placement and the keyboard path replaces each pick with Tab and Enter. **Counting convention (one, used by this section, requirements metric 2 and the tests)**: an action is one click, tap or key chord, a menu choice, or one typed value together with its confirming Enter (typing `6"` and pressing Enter is ONE action); a shape just drawn stays selected, and the Line tool returns to Select after one line unless tool lock (Q) is on, so the outline below uses tool lock and counts its toggle.
1. **Outline (6 actions)**: Line tool (L) (1), tool lock Q (1), then four 2-point drags (4), each starting on the previous end. Each snap adds Coincident and each near-horizontal or near-vertical line adds Horizontal or Vertical, announced through the notice queue as the `AddedConstraint` toast "Constraint added." (identical notices coalesce into a count; its Details list "Added Coincident (4)", "Added Horizontal (2)", "Added Vertical (2)"). Status: "4 degrees of freedom (4 moves still possible)".
2. **Pin the corner (4 actions)**: Select (V) (leaves the locked Line tool), K, Fixed; the picker arms and a click on the lower-left corner marker ("Endpoint of Line 1, also: Line 4 end") fixes it. Status: "2 degrees of freedom (2 moves still possible)" (width and height).
3. **Width (5 actions)**: K, Distance; click the lower-left marker, then the lower-right marker; the label opens; type `6"` and Enter (1). Status: "1 degree of freedom".
4. **Height (5 actions)**: K, Distance; lower-left marker, then upper-left marker; type `4"` and Enter (1). Status: "Fully constrained (nothing can move)" for the outline.
5. **Hole (2 + 3 + 10 actions)**: Ellipse tool (O), Shift-drag a circle inside the plate (2; the circle stays selected); K, Radius, type `1/2"` and Enter (3); then twice K, Distance with "Centre of Circle 1" and "Edge of Line 4" (left edge) typed `1"`, and "Centre of Circle 1" and "Edge of Line 1" (bottom edge) typed `1"` (K, Distance, two picks, type and Enter = 5 each). Status for the sketch: "Fully constrained (nothing can move)", 0 degrees of freedom.
6. **Check (5 actions)**: drag any corner (1): nothing moves. Select Line 1 and Line 2 (click, Shift-click), K, Parallel (4): rejected, "Parallel conflicts with Vertical on Line 2", geometry unchanged, actions [Remove new constraint] [Show conflicts] (Make reference is not offered for a geometric constraint).
7. **Edit a dimension (UX-99, 2 actions)**: tap the label "6"" (1), type `7"` and Enter (1): the plate widens by 1 in, the hole stays 1 in from the left edge, one undo step.
Total through the Constrain menu: 6 + 4 + 5 + 5 + 15 + 5 + 2 = **42 actions**. **With smart dimension chips (UX-115)**: step 3 becomes select Line 1 (1), tap the "Length" chip (1), type and Enter (1) = 3; step 4 the same, 3; the hole becomes O and Shift-drag (2), tap the "Radius" chip (1), type and Enter (1), then Shift-click Line 4 (1), tap the chip (1), type and Enter (1) for the left distance and click the circle (1), Shift-click Line 1 (1), tap the chip (1), type and Enter (1) for the bottom distance = 11; steps 1, 2, 6 and 7 are unchanged: 6 + 4 + 3 + 3 + 11 + 5 + 2 = **34 actions**. `BracketPlateUxScenarioTest` asserts both bounds (42 and 34) under this one counting convention and the 0-DOF status.

---

## 10. Non-drag transform mode (extends crosshair)

```
+ Crosshair mode ------------------------------+
| Action: [Place][Move element][Move point]     |
|         [Resize][Rotate][Bind end][Marquee]   |
|         [Draw polyline]                       |
|                                               |
|        [   ↑   ]     Step: ( ) 1 px  (•) 10 px  ( ) 1%
|   [ ← ] [ ● ] [ → ]                            |
|        [   ↓   ]                               |
| Preview: width 120 → 150                       |
|                    [Cancel]  [Apply]           |
+-----------------------------------------------+
```
Flow (move element, UX-40; WCAG 2.5.7): select the element (tap), choose Move element, nudge with the pad (Step 1 px, 10 px or 1%) or the arrow keys, the preview reads "x +30, y 0", Apply: the selection moves and bound arrows follow, one undo step; a touch-only user therefore never needs to drag an element (arrow keys alone also nudge a selection without entering this mode, section 17). Flow (resize): select element, choose Resize, nudge right 3x at 10 units, Apply: width +30, one undo step. Cancel or Esc discards. Keyboard equivalent: arrow keys nudge, Shift = 10 units, Enter applies, Esc cancels.

- Marquee: two taps define the region corners; summary "5 elements in region" [Select] [Cancel].
- Draw polyline by points: Place point repeatedly, [Finish] creates a freedraw-equivalent polyline.
- Bind end: list of candidate targets, ordered by distance, each with its label.

Errors: Apply that would make width <= 0 is clamped with message "Minimum size reached"; Apply on a locked element shows the locked message with [Unlock]. Cancel always available; nothing is committed until Apply.

### Keyboard-only drawing: a walked flow (UX-107; also UX-41, UX-105)
Tab order, fixed and tested, and the ONE list that section 17 and `F6` refer to: top bar (Back, name, Finger drawing chip, save status, Selection menu where it exists, Overflow); the notice region while it has an occupant (the banner's primary action and More, then each toast's actions, newest first); tool strip (ONE stop; roving focus with the arrow keys inside it); canvas (its first stop is the "Skip canvas elements" link); action chip row (only while chips exist); crosshair pad (only while Crosshair mode is active); selection bar (Duplicate, Group, Align, Order, Transform, Lock, Delete; only while a selection exists, and absent in the compact-height rail, where the top-bar Selection menu holds them); the view cluster (ONE stop in every layout, including the compact-height rail; roving focus with the arrow keys over zoom out, the zoom-level menu, zoom in, Grid, Snap, the Finger drawing toggle and Pan; Enter or Space activates, Esc closes an open zoom menu or Pan pad and returns focus to its button); side-pane tabs and active panel; the Notices (diagnostics) list while it is open; status line. While the first-run walkthrough is shown its popover is a region of this order directly after its anchored region: step 1 after the tool strip, step 2 after the view cluster, step 3 after the top bar (its Overflow button is the last top-bar stop), so before the notice region (section 22). Shift+Tab reverses; Tab never traps and Esc then Tab always leaves the canvas. `KeyboardDrawingFlowTest` walks the list at every layout of the chrome table.
1. Tab to the canvas (focus ring visible). Press R (or Tab to the strip, Enter on Rectangle, Tab back to the canvas).
2. Press Enter: Crosshair mode starts at the canvas centre (or the last pointer position); the crosshair pad receives focus order after the canvas but arrow keys already move the crosshair from the canvas (Shift = 10 units).
3. Enter places corner 1; arrow keys move the crosshair; Enter places corner 2. The rectangle is committed and announced "Added Rectangle 3".
4. Enter on the selected rectangle opens its text editor; type; Esc finishes.
5. Esc at any point before the final Enter cancels the in-progress rectangle with no command (the first Esc: Esc precedence row 6, section 17) and stays in Crosshair mode; a second Esc leaves the mode (row 7) and a third returns to Select (row 10).
Line: Enter per point and Enter twice (or Enter on the last point) to finish. Arrow: Enter on a shape binds. Text, Frame, Point and Arc follow the same place-by-Enter rule. Panning by keyboard is in section 3.

---

## 11. Import flow

### Entry points
Overflow > "Import .excalidraw..."; drag a file onto the desktop/web canvas; paste Excalidraw clipboard JSON (S5.1.4); Open with "Import .excalidrawlib" in the library. On web the drop lands on a canvas-local handler (the page-level drop target is a no-op on wasm, BUG-004) and the paste on the canvas paste event. Where `V2StorageAvailable` is false (iOS, a browser without OPFS atomic replace, no store) the overflow item is shown disabled with the reason of section 14.1, and a drop or paste shows a notice with the same reason and creates nothing (UX-135).

### Flow
```
[Pick file] -> size check (before parse) -> progress dialog [Cancel] -> summary dialog -> scene added (one undo step)
```
Progress dialog (off main thread):
```
+ Importing "architecture.excalidraw" +
| Reading 3.2 MB ...  ▓▓▓▓▓░░░░░     |
|                        [Cancel]     |
+-------------------------------------+
```
Summary dialog:
```
+ Import finished ----------------------------------+
| Imported 41 of 44 elements.                        |
| Skipped:  2 embeddables, 1 unsupported arrowhead   |
| Linked images were not downloaded.                 |
| ▸ Details (reason codes)        [Copy details]     |
|                  [Undo import]   [Done]            |
+----------------------------------------------------+
```
Imported content is added as a selected group offset to the viewport centre (or cursor for paste), as one undoable `ImportScene` command; [Undo import] reverts it.

### Error states

| Failure | Message | Exit |
|---|---|---|
| Not JSON / not Excalidraw | "Couldn't read this file: not valid Excalidraw JSON." + reason code | [Choose another file] [Close]; nothing created |
| File over 20 MB cap | "Files over 20 MB aren't imported." (before parsing) | [Choose another file] [Close] |
| Over element cap (> 5,000 incl. current) | "This drawing has 7,200 elements; the limit is 5,000." | [Import first 5,000] [Import into a new sketch] [Cancel] |
| Newer Excalidraw schema | "Created by a newer Excalidraw; some elements may differ." | proceeds with summary; [Cancel] before commit |
| Missing image binary | broken-image placeholder element labelled with filename | user can delete it; summary counts "1 image missing" |
| Unsupported elements | counted in summary; OD-6 decides placeholders (grey placeholder keeps round trip, recommended) | placeholder deletable; count shown |
| Cancelled | "Import cancelled. Nothing was changed." | none needed |
| App/process dies mid-import (Android) | on relaunch the sheet is unchanged (S5.1.5); no half-import | re-run import |
| Internal throwable | "Import failed unexpectedly. Nothing was changed." + [Copy details] | Close; logged `sketch.import.failed` |
| Clipboard text not Excalidraw | no-op, no message (so normal text paste is unaffected) | n/a |

Accessibility: dialogs trap focus, announce their title, return focus to the trigger; progress is announced at start and end only.

---

## 12. Export dialog

```
+ Export -----------------------------------------+
| Format   (•) PNG  ( ) SVG  ( ) .excalidraw       |
|          ( ) .excalidrawlib   ( ) CSV (technical)|
| Scope    (•) Whole sketch ( ) Selection ( ) Frame ▾ |
| Scale    ( ) 1x (•) 2x ( ) 3x     [x] Background |
| Style    (•) As drawn  ( ) Clean                 |
| Preview  [ thumbnail ]  400 x 200 px             |
|                         [Cancel]  [Export]       |
+--------------------------------------------------+
```
- SVG/CSV for v1-style technical sketches continue to use the existing writers; the dialog shows CSV only when the sheet has dimensions.
- ".excalidraw" shows an info line when layers exist: "Layers are flattened in the exported file (ADR-006)."
- Selection scope disabled with reason when nothing is selected.
- Success (UX-114): when the export completes a notice names the file and where it went (the `ExportSaved` toast "File exported." with [Open] and, under More, Copy path where the platform can open it; on the web build the `ExportSavedWeb` toast "File downloaded." has NO [Open] because a download cannot be opened by the app (UX-151), only Details and Dismiss; its Details name the file and place, "architecture.png in Downloads"; on web the downloaded file name and "Check your downloads"), announced politely; cancel and failure use the error states below, not this notice.

### Error states
| Failure | User sees | Exit |
|---|---|---|
| Export too large (PNG pixel cap, e.g. > 16k px side) | "This image would be 24,000 px wide. Choose 1x or export a frame." | [Use 1x] [Cancel]; dialog stays open |
| Encode/OOM failure | "Couldn't create the image. Try a smaller scale." | [Retry] [Cancel]; scene unaffected |
| Save target unwritable | "Couldn't save the file." | [Choose another location] [Cancel] |
| Web pop-up/download blocked | "Your browser blocked the download." | [Try again] |
| Missing images in export | export proceeds; footnote "1 missing image exported as placeholder" | n/a |

---

## 13. Element library

Side-pane tab "Library": grid of saved items (thumbnail + name). Actions: [Add selection to library], click to insert at viewport centre (fresh ids each time), Import `.excalidrawlib`, Export, per-item Delete (confirm, undoable within the session as a toast [Undo]).
Empty state: "Select elements, then choose Add to library to reuse them." Library import failures reuse the section 11 table (file too large / invalid / partial). Library is bounded: at the item cap, "Library is full (200 items). Delete an item to add more." with [Manage library]. Success is announced (UX-114) by the `LibraryAdded` toast "Added to library." [Undo] and the `LibraryInserted` toast "Inserted from library." [Undo]; their Details carry the names and counts ("Added 'Box group' to the library (3 of 200)", "Inserted 3 elements").

---

## 14. v2 enablement, migration, and old-build warnings

This is the highest-risk flow because writing v2 makes older builds refuse the whole project (ADR-001, plan OD-1: `schema > 1` is `Corrupt(NewerSchema)` for the project in old builds).

**One name for the feature.** Every user-facing string says "the new editor" ("New editor" where a label starts with it); the classic one is "the classic editor". "Sketch engine v2", "v2" and `sketch.engine.v2` are internal names and never appear in UI copy (a string-resource test over the `SketchConsentCopy` and banner strings fails if one does).

### 14.1 Enable the new editor (Settings > Sketches)
```
+ New editor (this graph, on this device) ----------+
| ( ) Off                                              |
|     New sketches are classic. Sketches already in   |
|     the new editor open read-only.                  |
| ( ) New sketch projects only                         |
|     New projects use the new editor, and so do      |
|     projects that already hold only new-editor     |
|     sketches. Other existing projects stay classic, |
|     and so does any sketch you add to one.          |
| ( ) All sketches (Upgrade)                           |
|     New sketches in any project use the new editor; |
|     each classic sketch gets an Upgrade offer, and  |
|     a backup is kept.                                |
| Learn what changes ▸                                 |
+-----------------------------------------------------+
```
The setting is the three-value mode of plan Task 1.4.3a (Off is the default). It is stored for this graph on this device only (settings, never synced), so the heading and the "Learn what changes" page say so and another device starts at Off until it is set there. Choosing a mode writes nothing and converts nothing; disabling (Off) is always allowed; open editors are not swapped (reopen applies).

**Accessibility contract of the mode control (U4; UX-76, UX-90).** The three options are ONE radio group: role radiogroup named "New editor", each option a radio with its selected state and its explanatory line as its accessible description (for example "New projects use the new editor, and so do projects that already hold only new-editor sketches. Other existing projects stay classic, and so does any sketch you add to one."; the Off line states that new-editor sketches already in the graph open read-only); Tab enters the group once and lands on the selected radio, Up and Down (Left and Right mirrored in right-to-left) move focus between the radios WITHOUT selecting and without opening any dialog, Space or Enter chooses. A disabled option stays in the arrow order and focusable, is announced as unavailable, and its reason "Not available in this build yet" is both visible text under it and its accessible description, reachable with focus or long press like every disabled-with-reason item (UX-90).

**Where `V2StorageAvailable` is false (UX-137; D-117).** Off is the only enabled radio and its line reads "New sketches are classic." without the clause about read-only new-editor sketches (they cannot be opened there at all, section 14.7); the other two radios are disabled and show the reason of the case (iOS "The new editor can't save sketches on iOS yet.", a browser that cannot save them one text per `Unavailable` reason and per browser family (the table "Web storage reasons" of ux.md 14.1 is the one source), no store "The new editor isn't available in the demo graph or while no graph is open."), as visible text and accessible description. **ONE reason precedence for every disabled new-editor item of Settings > Sketches**: `Platform` (the case text above) before `BuildCapped` ("Not available in this build yet"), the order of the reason table below. On such a platform Restore original, the backups' total size, Delete backups and the new-editor settings (Finger drawing, single-key shortcuts, default style) are shown disabled with the same reason, never hidden (UX-90); classic-sketch settings are unaffected.

**Web storage reasons (UX-141; D-128; the one source of every web reason text, used by the mode control, the no-editor screen and the `ClassicEditor` Details).** A browser's reason names no browser and no setting it cannot know; iPhone and iPad are told apart because every browser there uses Safari's engine (the family only chooses wording, never availability).

| Reason id | Case | Text |
|---|---|---|
| `NoOpfs:iosWebKit` | no OPFS on iPhone or iPad (any browser) | "On iPhone and iPad every browser uses Safari's engine, which can't save new-editor sketches yet. Use the desktop or Android app." |
| `NoOpfs` | no OPFS elsewhere (an old browser, or a private window that blocks it) | "This browser window can't keep new-editor sketches. Private windows often can't. Try a normal window, another browser, or the desktop or Android app." |
| `NoCreateWritable` | no `createWritable` | "This browser can't save new-editor sketches safely. Try another browser, or use the desktop or Android app." |
| `NoAtomicReplace` | neither replace path verified | "This browser can't save new-editor sketches safely. Try another browser, or use the desktop or Android app." |
| `NoSliceRead` | no slice read | "This browser can't save new-editor sketches safely. Try another browser, or use the desktop or Android app." |
| `PreloadFailed` | the saved sketches could not be read | "SteleKit couldn't read this browser's saved sketches. Reload the page. If it keeps happening, copy the details." |
| `PreloadTooLarge` | the saved sketches are over the preload cap | "This project's sketches are too large to open in a browser (over 128 MB). Open it on the desktop or Android app." |
| `CheckTimedOut` | the check did not finish in 10 s | "This browser didn't finish checking its storage. Reload the page to try again." |

**While the storage is being checked (U2; UX-141).** `Checking` is not a reason: the mode control shows Off enabled and the two On radios disabled with the visible text and accessible description "Checking whether this browser can save new-editor sketches..." (a polite live region that announces the result once: the radios enable, or the reason of the table appears); a v2 embed shows "New-editor sketch. Checking storage..." with [Open page]; opening a v2 sheet shows the routing table's `Pending` state; a create action waits for the status and then follows the resolved row, so a v2 sheet is never created from `Checking`. **A graph opened temporarily (UX-139).** Its mode control stays usable and shows under the radios, as visible text and accessible description: "This graph is open temporarily. New-editor sketches here are lost when you close or reload the page." **Browser storage (UX-143; D-125).** On the web build Settings > Sketches shows a row "Browser storage": "Kept" when `navigator.storage.persist()` was granted, otherwise "Not guaranteed" with [Ask the browser to keep it] (a click is the user gesture a prompt needs; the button is absent where the browser has no `persist()`); the request is made at the first write, never at startup (14.2). A refusal creates no toast and no banner: it is the row above and the sentence "Your browser has not promised to keep this site's storage." appended to the web durability line of every later first-write dialog and to the Details of the quota variant of Save failed (14.8); it changes no availability. The row's states, the inline status line of a refused or pending request and the action are the table `storage-row` of 14.8 (UX-147).

**Recurrence of the confirmation (U4).** The acknowledgement is a per-device LEVEL (none, `NewProjectsOnly`, `On`) raised by a confirmed Turn on (including the banner variant: it raises the level to `NewProjectsOnly`). Choosing the mode that is already selected is a no-op: no dialog, nothing written. Narrowing (On to `NewProjectsOnly`, or any mode to Off) never shows a dialog. Widening to a mode at or below the device's level (for example Off back to `NewProjectsOnly` on a device that already confirmed it, or `On` to `NewProjectsOnly` and back after confirming `On`) stores it without the dialog, because this device's user has already seen the warning for that scope; widening above the level (`NewProjectsOnly` to `On`, or any first choice on a device with no acknowledgement) shows the dialog BEFORE storing and Cancel stores nothing. The read-only banner's [Use new] always shows its banner variant (it is a decision about one sketch), whatever the level. The per-project first-write dialog (14.2) is unaffected. A mode the build cannot offer is shown disabled with "Not available in this build yet", never hidden: with `ConversionReady` false the two On modes are disabled, and after an A4 NO-GO (`ConversionOffered` false) only "All sketches (Upgrade)" is.

Flag-enable moment (UX-76; plan Task 1.4.6c; owner-confirmable). Choosing a mode other than Off shows ONE confirmation BEFORE the mode is stored, unless this device's acknowledgement level already covers it (the recurrence rules above). It has three variants, each with [Cancel] [Turn on], and all three store the acknowledgement the same way:
- **All sketches (Upgrade)**: the dialog drawn below.
- **New sketch projects only**: title "Turn on the new editor for new projects?", body "Sketches in new sketch projects, and in projects that already hold only new-editor sketches, use it. Other existing sketches and projects stay classic and nothing is converted.", and the whole-project sentence in its new-project form: "Older versions of SteleKit (including another device you haven't updated) can't open a project that contains a new-editor sketch."
- **[Use new] is absent where it cannot work (D-99).** When `ConversionReady` is false (or the mode cannot be turned on in this build) the `ReadOnly` banner has NO primary action: its summary stays and its Details say "This version of SteleKit can't edit new-editor sketches yet.", so the banner is never an enabled-looking dead end; Restore original, Export SVG and Close stay under More.
- **[Use new] on the read-only banner** (`SketchConsentCopy.ReadOnlyUseNew`, because the sketch is ALREADY a new-editor sketch and read-only, so the copy of the other two variants would be false): title "Edit this sketch with the new editor?", body "This sketch already uses the new editor, so it is read-only until the new editor is on. Turning it on reopens this sketch so you can edit it. New sketches in new projects will also use it. Classic sketches stay classic and nothing is converted.", and the whole-project sentence in its ALREADY-TRUE form: "Older versions of SteleKit (including another device you haven't updated) already can't open this project, because it contains a new-editor sketch." Its confirming button, [Turn on and reopen], stores `NewProjectsOnly` and reopens the sketch (section 1).
```
+ Turn on the new editor for all sketches? ---------------+
| New sketches use it; each classic sketch gets an Upgrade|
| offer. Nothing converts until you choose Upgrade.       |
| (!) Once a sketch is upgraded, older versions of        |
|     SteleKit (including another device you haven't      |
|     updated) can't open ANY sketch in that project.     |
| Learn what changes ▸          [Cancel]   [Turn on]      |
+---------------------------------------------------------+
```
The acknowledgement is stored per DEVICE as a level (not synced) so one device's "Turn on" cannot silence the warning on a device that has not seen it; this confirmation is the ONE way the flag is turned on, whether from Settings or from the read-only banner's [Use new] (below); the per-project first-write dialog (14.2) still appears at the first conversion in each sketch project. While the build cannot read v2 safely yet (`ConversionReady` false) every On mode is disabled, and after an A4 NO-GO only "All sketches (Upgrade)" is, each with the reason "Not available in this build yet", never hidden. "Learn what changes" opens an in-app page listing: what upgrades, what older versions will show, where backups are, how to restore, how to turn the editor off, that the setting is for this graph on this device only, what "other devices" means (any SteleKit that has not been updated), what a "project" is (the sketches saved together under one project name), which new sketches use the new editor in each mode, and that a project you empty of sketches counts as an existing project again.
One copy source (UX-103; plan Story 1.4.6, `SketchConsentCopy`): the whole-project sentence "Once a sketch is upgraded, older versions of SteleKit (including another device you have not updated) cannot open ANY sketch in that project." and the backup sentence are defined once and used by this confirmation, the first-write dialog (14.2), the Details of the post-conversion banner (14.3) and the Upgrade chip's consent sheet; a test fails if the wording appears anywhere else.

New sheet created as classic (UX-77; plan Task 1.4.6e). A sketch is created in the classic editor when the platform cannot store new-editor sketches, the mode is Off, the build is capped, the mode is New sketch projects only and the project is not a `NewSketchProject` (the canonical sentence of requirements.md "Flag modes"), the project holds technical data that refuses the new editor, or the user chose classic in the first-write dialog. ONE notice explains it: the `ClassicEditor` toast of the toast table (polite, through the notice queue) whose line is the fixed "Created as classic." with [Change]; the reason and how to change it are its Details, "Created with the classic editor because <reason>.", because a toast row carries no dynamic text. **Recurrence (the one rule; also a row of the toast recurrence table of section 16).** The toast is not shown while the STORED mode is Off (the mode the user chose, not the effective mode; the default; the reason is only a silent "Created as classic" entry in the Notices list), and in every other stored mode it is shown at most once per project per reason per device (recorded in the `ClassicNoticeLedger` of plan Task 1.4.6e, keyed by (graph, project slug, reason), bounded at 200 entries and pruned with the project and the graph; a different reason in the same project shows it once more). A stored mode that a cap lowers to an effective Off (the `Platform` and `BuildCapped` rows: for example a mode stored in a browser that had OPFS and read in a browser that does not) therefore still shows the toast, as `ClassicEditorFixed`; the `ModeOff` row is reached only by a stored Off. **[Change] is offered only where it can change the outcome**: the mode is New sketch projects only, "All sketches (Upgrade)" is selectable in this build, and the project holds no technical data that refuses the new editor (the three New-sketch-projects-only reasons below); for `Platform`, `BuildCapped`, `TechnicalData` and `UserChose` Settings cannot change the outcome, so the toast is the row `ClassicEditorFixed` of the toast table instead: the same line, the primary label [Learn more] (an allowed shared label; it opens the same reason text, so Details is not repeated in More, which holds Dismiss only), because Details is a More item and never a second primary. **Return path**: [Change] opens Settings > Sketches > New editor with focus on the mode group and changes nothing by itself; the just-created sketch stays open underneath in the classic editor, system back or the Settings Back control returns to it, a changed mode never swaps the open editor (the `EngineChange` toast offers [Reopen]), and once the mode is "All sketches (Upgrade)" the sketch's Upgrade chip is its way to the new editor. The reasons are one table in `SketchReasonText`, listed in PRECEDENCE order: when several apply, the Details name ONLY the first applicable row (one reason per toast, so the user is told the cause they can least change first), and the toast variant (`ClassicEditorFixed` or [Change]) follows that one row; `UserChose` never competes because it arises only from the dialog choice; a "project" for the user is the set of sketches saved together under one project name, such as "Kitchen" (defined once on the "Learn what changes" page and in the Details); the one short term for another install is "other devices", defined in the Details as any SteleKit that has not been updated, and the words "older apps" are not used:

| Case (id, in PRECEDENCE order) | Reason text (the <reason> of the Details) |
|---|---|
| `Platform`: the platform or graph cannot store new-editor sketches (iOS, a browser without OPFS atomic replace, a demo graph or no open graph) | "this device can't save new-editor sketches yet" (iOS, browser) or "this graph can't hold new-editor sketches" (no store) |
| `BuildCapped` | "this version of SteleKit can't use the new editor yet" |
| `TechnicalData` | "this project has dimensions or zones, and the new editor can't hold those yet" (its own fragment, `TechnicalDataNewSheet`: the user is already in the classic editor, so the conversion-refusal sentence of the router, which tells them to open the classic editor, is NOT reused here) |
| `ModeOff` | "the new editor is off for this graph" |
| `NpoClassicSheets`: New sketch projects only, existing project holding a classic sketch | "this project already has sketches from before the new editor, and New sketch projects only limits the new editor to projects created from now on" |
| `NpoEmptyProject`: New sketch projects only, existing project with no sketches (a project you emptied of sketches is an existing project again) | "this project already existed, and New sketch projects only limits the new editor to projects created from now on" |
| `NpoUnreadable`: New sketch projects only, project too large or not readable to check | "SteleKit couldn't confirm that every sketch in this project uses the new editor" |
| `UserChose`: the user chose classic in the first-write dialog | "you chose it" |

The Details of every New-sketch-projects-only reason end with the one route to the new editor in an existing project: "To use the new editor in an existing project, choose All sketches (Upgrade) in Settings: sketches you add to that project then use the new editor (after a one-time confirmation), and each classic sketch offers Upgrade."

**Finding the new editor while the mode is Off (U6; D-97).** Mode Off is the default, so no chip, no toast and no Upgrade entry exist for a new user. The one discovery path is an item "Try the new editor" in the classic sketch menu (and the same words as the first line of Settings > Sketches > New editor's explanation) shown while the effective mode is Off and neither the build nor the platform is capped (present in modes: Off; D-110): in `NewProjectsOnly` and `On` the paths that replace it are the sketch-menu entry "Upgrade to the new editor" (mode `On`, per sheet) and the Settings route in the `ClassicEditor` toast's [Change] and Details (`NewProjectsOnly`), and a capped build or platform has nothing to try; it opens Settings > Sketches > New editor with focus on the mode group and changes nothing by itself, so it asks no consent. The consent is asked ONCE per question, and the two gates ask different things: the Settings confirmation (14.1) is the only place the whole-project warning is shown in full, and the per-project first-write dialog (14.2) asks whether THIS project may become unreadable to older builds, showing the Settings text by reference ("As you confirmed in Settings") in the same session and in full only in a later session; merging the two gates was considered and not applied because the second consent is per project (D-16). No banner candidate, toast or counter is added for discovery (the banner table keeps its 14 candidates); whether to add a one-time discovery card is an owner decision recorded as D-97.

### 14.2 The Upgrade offer, and the first-write confirmation (opening never converts)
**Opening a v1 sketch with the flag on never converts it (UX-104; D-12).** The sketch opens in the classic editor exactly as before, and the `UpgradeChip` row of the banner table (a non-modal occupant of the banner slot) says the ONE consequence line "Upgrade blocks other devices." with [Start] and, under More, Details, Not now and Keep this sketch classic (announced politely; "other devices" means any SteleKit that has not been updated, defined in the Details; [Start] opens the first-write dialog, it never converts by itself). A 170-character consequence sentence cannot fit the one-row banner (section 1, content fit), so the row carries the consequence line only and the full sentence lives in two places read from `SketchConsentCopy`: Details (a sheet showing "This sketch can be upgraded to the new editor. Upgrading stops other devices (any SteleKit that has not been updated) from opening ANY sketch in this project.") and the first-write dialog below, which [Start] always opens before the first conversion in each project (D-16, D-41). **The consequence is never reduced to a bare question.** This is a consent row: it has no short title, and **the sheet replaces the row whenever the row's summary, word-wrapped greedily at the line width of the window's width and the current font scale, takes more than the row's 2 lines** (computed, see the computed block of section 1: the sheet replaces the row from font scale 1.11 at 320 dp wide and from 1.52 at 360 dp wide; at 1.0 the row fits at both). **Runtime rule versus test oracle.** AT RUNTIME real text measurement decides: `UpgradeOfferHost` measures the summary with the platform text layout at the live width and font scale, and the sheet replaces the row exactly when the measured summary takes more than 2 lines (no character table is shipped in the app). The greedy word-wrap model of the computed block is the CONSERVATIVE TEST ORACLE: for a representative font set per target (the bundled UI font on JVM and wasmJs, the system default font on Android, and on each one wider-glyph test font so the bound is not tied to one typeface; INFERRED, fonts the user installs are not enumerated) the measured line count must be at most the model's, so a window the model calls "row fits" always fits when measured, and the model can only choose the sheet where measurement would choose the row; `UpgradeChipConsentSheetTest` asserts `measuredLines <= modelLines` for every cell of the matrix and every font of that set. The chip is hosted in the classic editor, outside the v2 `ChromeBudgetTest`, so the same test also asserts the row's fit in the classic host's notice area at 320 and 360 dp and font scales 1.0 and 2.0: at most 2 lines, buttons at the floor, nothing clipped. Widths below 320 dp are outside the guaranteed set of the chrome table: measurement still decides, the row is never shown when it measures past 2 lines, and the matrix claims nothing below 320 dp. **Why the sheet opens by itself at large text.** Where the row cannot show the consequence without truncating it, a truncated row would be a bare question (above), so the full text is shown instead; once per project per day on this device (U9 quieter trigger), only when nothing else holds the user's attention, and every dismissal leaves the classic editor fully usable, which is the least interrupting way to show it. **Crossing a threshold while the sheet or row is visible.** An open sheet stays open when the window, the fold state or the font scale changes (it reflows and scrolls, never closes by itself and is never replaced by the row); a visible row that no longer fits is withdrawn and the sheet follows the open rule above (not mid-gesture, not during text entry, not over another modal; at most once per project per day on this device, and not again in this session after a dismissal); a visible sheet is never swapped back for the row while open, and after it closes the usual recurrence applies. The sheet's primary button reads [Start], the same word as the row (one wording for one action; "Upgrade" stays the sheet title and the menu entry). The sheet "Upgrade this sketch?" shows the line, the whole-project sentence and the three actions (Start, Not now, Keep this sketch classic), scrolls and is not clipped at font scale 2.0 or 3.0 (UX-123). **Behaviour of the sheet (UX-104, UX-126; plan Task 1.4.6i).** It opens once per sketch project per day on this device (stored as one day value per project in `ProjectAckStore`, `consentSheetDay`, pruned with the project), and only when the chip is the visible onboarding item and no gesture, text entry or other modal is active (otherwise it waits in the queue). Focus moves to its title (announced "Upgrade this sketch?, dialog"), Tab cycles Start, Not now, Keep this sketch classic and the scrolling body, and closing returns focus to the control that held it before, or to the canvas when none did (the sheet opens on its own). Esc, system back, a scrim tap, a drag down and [Not now] all mean Not now: the sketch stays `NotConverted`, nothing is written or acknowledged, the sheet does not reopen in this session for any sketch of that project, and the banner slot is left EMPTY (a truncated row is never shown), so working in the classic editor is never blocked; the upgrade stays reachable from the sketch menu "Upgrade to the new editor" and from a silent "Upgrade available" entry of the Notices list. [Start] opens the first-write dialog (or, in an acknowledged project, converts). In a LATER SESSION (a new app process) the chip returns (until-acted) and the sheet may open once more on a later day; within this session neither the row nor the sheet comes back for any sketch of that project. So no conversion starts before the user has been shown the whole-project sentence in a dialog. [Not now] dismisses it for the session and the sketch stays `NotConverted` (the chip returns in a later session). The chip's menu has "Keep this sketch classic" (the one wording everywhere): it marks only that sketch `Declined`, the chip never returns, and the sketch menu entry "Upgrade to the new editor" re-offers it. Only [Start] (or that menu entry) can start conversion. Creating a NEW sketch makes a new-editor sketch when the mode is `On`, or when it is `NewProjectsOnly` and the project is a `NewSketchProject` (the routing rule of section 1), after the first-write dialog below on the first one in a sketch project; in every other cell it is classic with the `ClassicEditor` toast.

**No offer for an action that cannot succeed (U4; D-98).** While `ConversionPolicy.refusal(project, sheet)` is non-null (the project has any dimension or the sheet has zones, until Story 4.2.5), the `UpgradeChip` is NOT emitted and no consent is asked: the classic sketch menu still lists "Upgrade to the new editor" but DISABLED with the plain reason "this project has dimensions or zones, and the new editor can't hold those yet" (the `TechnicalDataNewSheet` fragment of 14.1), so the permanent refusal cannot loop through consent, a failure dialog and a returning chip. The predicate is re-read when [Start] is activated; if it has become true since the chip was shown (a dimension was added meanwhile) nothing converts, the chip is withdrawn and the same disabled entry explains why. The 14.4 dialog is therefore only for failures that can still succeed on a retry and shows its reason in words first and the code second.

**First [Start] in a sketch project: confirmation (D-16).** The consequence is per PROJECT, because older builds fail on the whole project, so the acknowledgement is stored per (device, project slug), not synced. Before the first conversion or first new v2 sheet in that project:
```
+ Use the new editor? -------------------------------------+
| (!) Older versions of SteleKit can't open projects that      |
|     contain new-editor sketches.                             |
|                                                              |
| If you open this graph with an older SteleKit (including     |
| another device you haven't updated), the whole sketch        |
| project "Kitchen" won't open, not just this sketch.           |
|                                                              |
|                                                              |
| Your original is saved in kitchen/backup/ and can be         |
| restored.                                                    |
| [ ] I understand                                             |
|              [Open classic]        [Convert and open]        |
+--------------------------------------------------------------+
```
- **Focus, order and the disabled button (both variants).** Initial focus is the warning text block (a focusable, non-interactive region, so a sighted keyboard user sees the focus ring on the sentence they are consenting to and a screen reader reads it first); one Tab reaches the "I understand" checkbox (the primary button is disabled until it is checked, so it cannot take focus first); the dialog's accessible description is the whole-project text, read once on open; Tab order is the warning text block, the checkbox, the left button ([Open classic] or [Create classic sketch]), then the primary button, cycling inside the dialog; Esc and back are the left button's action (or a cancel in the new-sheet variant). While the box is unchecked the disabled primary button stays focusable, and its reason is VISIBLE text under the checkbox row, "Check I understand to continue.", and its accessible description, so the reason is not hover-only.
- The dialog has exactly ONE checkbox, "I understand" (U6; the earlier second checkbox "Keep this sketch classic and don't ask again" is removed because its combined behaviour with the first was undefined and it duplicated the chip menu). It starts unchecked every time the dialog opens and is never remembered by itself. "Convert and open" is disabled until it is checked (keyboard: Space toggles); [Open classic] is always enabled and is also what Esc and the system back action do. Checking the box and then choosing [Open classic], Esc or back stores nothing: the project is acknowledged only when "Convert and open" is activated.
- **New-sheet variant (UX-64; D-35)**: creating a NEW sketch with the flag on, in a project not yet acknowledged, converts nothing and there is no backup, so the dialog drops the backup sentence and its buttons say what they do:
```
+ Create a sketch with the new editor? -------------------------+
| (!) Older versions of SteleKit can't open projects that        |
|     contain new-editor sketches.                               |
| If you open this graph with an older SteleKit (including       |
| another device you haven't updated), the whole sketch project  |
| "Kitchen" won't open, not just this sketch.                    |
|                                                                |
| [ ] I understand                                               |
|        [Create classic sketch]       [Create and open]         |
+----------------------------------------------------------------+
```
  Same ONE checkbox (unchecked on every open) gating [Create and open] only; same `SketchConsentCopy` sentence. [Create classic sketch] is an explicit choice and says so: it creates a classic (v1) sheet and shows the UX-77 `ClassicEditor` toast (its Details: "Created with the classic editor because you chose it."); Esc and back CANCEL (nothing is created or written and the user returns to where they were), so no path creates a classic sheet silently. The project is acknowledged only when [Create and open] is activated.
- **Web build: the durability line and the persistence request (UX-139, UX-143; D-128, D-136).** Both dialog variants gain ONE line, between the older-versions warning and the checkbox row, read from `SketchConsentCopy.WebStorage` and part of the dialog's accessible description: "New-editor sketches are saved in this browser's private storage. The browser can clear it, so back it up with git sync or a linked folder." In a graph opened temporarily the line is instead "This graph is open temporarily: its new-editor sketches are kept only until you close or reload this page." In a window the web build suspects is private (`V2StorageStatus.Ready.privateSuspected`, 14.8, UX-148) and the graph is not opened temporarily it is "This looks like a private window: new-editor sketches are kept only until you close it." (a temporarily opened graph always gets the session-only line above, whatever the window, UX-151) The click on [Convert and open] or [Create and open] is the user gesture that requests `navigator.storage.persist()`; in a browser whose Spike 0.1.8 row says it prompts, the line "Your browser may ask to keep this site's storage." follows; [Open classic], [Create classic sketch], Esc and back request nothing.
- [Open classic]: nothing is written, the classic editor opens and the sketch stays `NotConverted` ([Open classic] is not a dismissal, so the chip is offered again the next time the sketch is opened). Declining for good is a different, deliberate action in one place only: the chip's menu "Keep this sketch classic", which marks that sheet `Declined` (remembered PER SHEET, plan Story 1.4.3), stops the chip appearing at open, and is undone by the sheet menu "Upgrade to the new editor", which shows this same dialog.
- **Right after an enable confirmation (no repeated paragraph).** When the whole-project warning was confirmed in the Settings enable confirmation or the banner variant of [Use new] in THIS app session, the first first-write dialog of a project shows the project-specific half by reference instead of repeating the two paragraphs: "(!) As you confirmed in Settings: older versions of SteleKit can't open the whole sketch project "Kitchen" once it holds a new-editor sketch." (`SketchConsentCopy.ProjectReference`, defined once there like the other sentences), followed by the same backup sentence (convert variant only), the same ONE "I understand" checkbox and the same buttons. In every other case (a later session, or no enable confirmation this session) the full text above shows. The per-project acknowledgement is stored exactly as before.
- **Later sketches in the same project after the acknowledgement** (D-12, D-16): the acknowledgement ("Convert and open") is stored once per sketch project (and per device, not synced). Opening other v1 sketches of THIS project still does not convert them; their chip's [Start] converts immediately with no modal, which is accepted because the whole-project consequence was acknowledged for this project on this device and stays on the chip (the line "Upgrade blocks other devices." or, where it does not fit, the consent sheet) at every font scale, and the Details of the 14.3 banner repeat the whole-project sentence after each conversion; a sketch in a DIFFERENT project shows the dialog again; [Not now] and "Keep this sketch classic" work per sketch exactly as above, so the owner can decline sketch by sketch. The dialog is its own UI task (plan Story 1.4.6, Task 1.4.6a; the chip is Task 1.4.6g).

### 14.3 After conversion (one-time notice, per sketch)
Banner (the `PostConversion` row of the banner table, an Info occupant of the banner slot, section 1): "Upgraded. Backup saved." [Learn more], with Dismiss under More. The row carries only that fixed text; the sheet behind [Learn more] shows the backup path (`BackupSentence`) and the whole-project sentence (`WholeProjectWarning`, UX-103).

### 14.4 Failure of conversion
```
+ Couldn't upgrade this sketch --------------------+
| Your original sketch wasn't changed.              |
| Reason: <code>   [Copy details]                   |
| [Open in legacy editor]   [Try again]   [Close]   |
+---------------------------------------------------+
```
Atomicity (S1.4.2): crash between steps leaves v1 referenced; reopening retries. "Open in legacy editor" requires a router escape hatch for (flag on, schema 1, conversion failed); see unresolved flow U2.

### 14.5 Opening a v2 project in an older build (the old-build upgrade warning)
The old build's own UI is out of this repo's reach for builds already shipped; the plan's S0.1.1 only pins that it fails closed. For builds shipped from this change onward (any build that knows schema 2 but not schema 3 or later, and the current build facing a newer schema; a v2 sheet in the combined reader-and-conversion release or later opens read-only through the routing matrix of section 1, never on this screen, and a build that predates it fails closed with its own error, with no earlier warning than the first-write dialog (OD-1 amended 2026-11-17)):
```
+ Update SteleKit to view this sketch -------------+
| This sketch was saved by a newer version of SteleKit.|
| Nothing was changed. Your other notes are fine.    |
| [Copy details] [Check for updates] [Export last backup] [Close] |
+----------------------------------------------------+
```
Actions have owners (UX-79; plan Task 1.4.6d): [Check for updates] calls an update-check port (fallback: opens the project's releases page) and [Export last backup] saves the newest `backup/*.v1.json` through the file-save seam without writing to the project, and is HIDDEN (not disabled: no reason or later action could help) when the project holds no `backup/*.v1.json`, because a backup exists only for a CONVERTED sheet and never for a sheet created as v2. Embed in a journal page shows the same text as a compact placeholder (section 18). No write ever occurs from this state (P2). For pre-v2 builds, the user-facing mitigation is only the 14.2 confirmation plus the shipped release notes; this is a stated limitation, not a solved flow.

### 14.6 Rollback (flag off) and restore
Where `V2StorageAvailable` is true, v2 sheets open read-only (section 1 matrix) and exports remain available; where it is false the sheet opens as the no-editor screen (14.7), from which nothing can be exported. In-app restore of the v1 backup IS provided (plan Story 6.2.6, Settings > Sketches "Restore original (v1) sheet"); the read-only banner offers it as its More item "Restore original" (its Details explain: "To go back to the classic editor's copy, restore the backup."). Restore always confirms first (UX-78) and ends with a success notice naming the kept copy (UX-114): "Restore the original sketch? Everything you changed in the new editor since the upgrade will be discarded. A copy of the new-editor version is kept in backup/. [Restore original] [Cancel]". Settings also shows the backups' total size and a "Delete backups" action with its own confirmation.

### 14.7 The no-editor screen: newer schema and storage unavailable (UX-133, UX-134, UX-138; plan Task 1.4.6j; D-117)
One screen with two bodies; it replaces the editor and the router never opens a banner over it.
```
+ This sketch can't be opened here -------------------+
| (the reason of the case, from the table "Web     |
| storage reasons" in 14.1)                          |
| Nothing can be exported from here in this version. |
|                                                    |
| [Copy details]   [How to open it]   [Close]        |
+----------------------------------------------------+
```
| State | Heading (level 1) | Actions in tab order |
|---|---|---|
| Newer schema | Update SteleKit to view this sketch | [Copy details], [Check for updates], [Export last backup], [Close] |
| Storage unavailable | This sketch can't be opened here | [Copy details], [How to open it], [Close] |
| Storage unavailable, transient (`PreloadFailed`, `CheckTimedOut`) | This sketch can't be opened right now | [Reload], [Copy details], [Close] |

[Reload] reloads the page (the only action that can change a transient state; focus lands on the heading again after the reload like any arrival, UX-138). [Copy details] copies the sketch name, the project name, the schema version, the platform and browser and the reason id, never sketch content; [How to open it] opens the in-app page "Where new-editor sketches open" (desktop, Android and the supported browsers; it claims no export and no feature the build lacks); [Export last backup] is as in 14.5 and hidden when none exists. On the storage-unavailable variant this release offers no export, and the screen SAYS so in one line ("Nothing can be exported from here in this version.") instead of offering one: that is a stated LIMIT of this release, not a measured impossibility, because a browser whose verdict is Unavailable only because it cannot write may still be able to read a sheet (Spike 0.1.8 and the owner pass of Task 1.4.7d record whether it can, and a later release may then add an export); the recovery is to copy the details, follow the guidance to open the sketch where it can be opened, or close.

**Accessibility contract (UX-138; WCAG 2.4.3, 4.1.2, 1.3.1, 4.1.3).** Focus lands on the heading on arrival (programmatic focus on a non-tabbable heading), so a screen reader announces the heading and the first paragraph once and no extra live-region message is sent; the container is a region named by the heading, not a dialog (no focus trap, nothing modal behind it); the heading is level 1; the tab order is the table's; Esc and system back activate [Close]; closing returns focus to the control that opened the sketch (the page block, the embed's [Open] or the sketch list row).

### 14.8 Web save states: quota, still saving, pending, persistence, private windows and the second tab (UX-144 to UX-151; plan Tasks 1.4.6j, 1.4.7k, 1.4.7l; D-134 to D-136)
**Save failed because the browser store is full (UX-144).** When the durable write fails with `StorageQuotaExceeded` the banner slot shows the `SaveFailed:Quota` row of the table below instead of `SaveFailed` (same rank 1, Blocking, never dismissible). Its primary action is [Save copy] (the Export copy action of the leave prompt and of Save failed, named for a button of at most 5-character words), because [Retry] cannot succeed on a full store; Retry moves under More and its Details are, in this order: "This browser has no room left for this site. Your work is still open in this tab and stays safe only while the tab stays open." then "1. Save a copy now." "2. Free space: close other tabs of SteleKit, delete data of other sites, or delete sketch backups in Settings > Sketches." "3. Then choose Retry." and, when persistence was refused, the sentence of 14.1 "Your browser has not promised to keep this site's storage." Retry stays under More (a user who freed space needs it) and the primary stays [Save copy] throughout, after a successful export too: a second copy is harmless and a primary that changed under the user's finger would not be.

<!-- save-variants:start -->
```text
# base | id | summary (320 dp, scale 1.0) | short title (scale 2.0) | primary label | More items | primary accessible name
SaveFailed | SaveFailed:Quota | Storage full. Keep tab open. | Storage full | Save copy | Retry; Details | Save copy of this sketch
```
<!-- save-variants:end -->

**The second tab (UX-149; defence in depth, D-149).** A second tab on a persistent web graph never reaches the editor (14.9); the lease conflict of a second browser tab is its own variant of the `LeaseConflict` banner row, so that the width, wrap and accessible-name checks cover the form without a primary action:

<!-- lease-variants:start -->
```text
# base | id | summary (320 dp, scale 1.0) | short title (scale 2.0) | primary label (- = none) | More items
LeaseConflict | LeaseConflict:OtherTab | Edited in another tab. | Other tab | - | Details; Close
```
<!-- lease-variants:end -->

**Still saving (UX-145, UX-150).** A write that has not reached `Persisted` after 3 s shows the `StillSaving` toast of the toast table once per save. Its timing contract is the block below, because the general notice rules do not fit a notice whose sentence stops being true: it is the one Info notice with no primary action EXEMPT from the 5 s minimum of section 16, yet it stays visible at least 1 s so a write that completes at 3.2 s never produces an unreadable flash; its polite status message (WCAG 4.1.3) is sent once at 4 s from the start of the write (3 s plus 1 s of visibility; when the lane is occupied and the toast is not shown, the 1 s clock still starts at 3 s, so the announcement time does not depend on the lane) and is cancelled when `Persisted` arrives first, so a write that persists within that second is silent and "Saved" is the only message (polite messages are throttled to one per second, UX-91); the toast never displaces another toast (in a lane of one it is not shown while an Undo or other persistent toast occupies the lane, its diagnostics-list entry and the "Saving..." status carry it, and its announcement still goes out once), and any newer toast displaces a shown StillSaving; at `Persisted` the toast AND its diagnostics-list entry (shown or displaced) are removed, because an earlier record of a state that is no longer true would mislead; on a failure it is replaced by the Save failed banner. The status chip is not a live region: its tooltip and accessible name read the same sentence for a pointer or screen-reader user who focuses it, and the toast is the ONE announcement source, so one event is never announced twice.

<!-- still-saving:start -->
```text
# key | value
shown-after-seconds | 3
min-visible-seconds | 1
announce-after-visible-seconds | 1
announce-at-seconds | 4
announce-clock-needs-shown-toast | no
displaces-other-toast | no
displaced-by-newer-toast | yes
removed-at | Persisted
list-entry-removed-at | Persisted
announcement-source | toast
chip-is-live-region | no
```
<!-- still-saving:end -->

**Leaving the page.** The browser's leave-page prompt is generic text that this app cannot set, and a mobile browser may suppress it: the edits of the last moments (up to one debounce window plus one write) can still be lost there; the flush on `visibilitychange` to hidden narrows that window and cannot close it, and the app does not claim otherwise.

**The Pending screen (UX-146; WCAG 2.4.3, 4.1.3).** Opening a v2 sheet while the storage status is `Checking` shows the `PendingScreen` with the level-1 heading "Checking this browser's storage..." and [Close]; it is a region named by its heading, not a dialog. Its rows:

<!-- pending-contract:start -->
```text
# event | seconds | visible | focus | announcement
arrive | 0 | the heading and [Close] | the heading (programmatic) | none beyond the heading
still-checking | 5 | a second line "This is taking longer than usual." | unchanged | polite status once
resolved-editor | - | the editor | the editor canvas, as when any sheet opens | polite status "Ready to edit" once
resolved-no-editor | - | the no-editor screen of the reason | its level-1 heading (UX-138) | none beyond the heading
timeout | 10 | the no-editor screen of `CheckTimedOut` with [Reload] | its level-1 heading | none beyond the heading
close | - | the screen closes | the control that opened the sketch | none
```
<!-- pending-contract:end -->

**Browser storage row (UX-147).** Settings > Sketches > Browser storage maps `PersistenceState` to these rows; a click on [Ask the browser to keep it] is never silent:

<!-- storage-row:start -->
```text
# state | PersistenceState | label | inline status line | action
Kept | Granted | Kept | - | -
NotGuaranteed | NotRequested | Not guaranteed | - | Ask the browser to keep it
Asking | NotRequested | Not guaranteed | Asking the browser... | -
Refused | Denied | Not guaranteed | The browser said no. It decides on its own, often after you use SteleKit more; keep git sync or a linked folder as your backup. | Ask the browser to keep it
Unsupported | Unsupported | Not guaranteed | This browser can't promise to keep site storage. | -
```
<!-- storage-row:end -->

The inline line is visible text in the row and a polite status message. **Private windows (UX-148; INFERRED).** Chromium incognito and Safari private browsing keep the OPFS API but clear it when the window closes, so the verdict is Available and "Saved" would otherwise be misleading. The app suspects a private window when `navigator.storage.estimate().quota` is below `EPHEMERAL_QUOTA_BYTES` = 268,435,456 (256 MiB) and persistence was not granted; this heuristic is INFERRED and Spike 0.1.8 records the estimate in a normal and in a private window of each browser, and where the two do not differ the hint is dropped and the conservative wording of 14.2 (the browser can clear its storage) is all that is said. When suspected, the first-write dialog line (14.2) and the "Saved" tooltip and accessible name say the sketches are kept only in this window, and a persistent cue in the hint row says so too, because a tooltip is invisible on touch and the dialog is shown once: the cue is one line of at most the toast line width at 320 dp. **One winner per state (UX-151).** A graph opened temporarily never reaches OPFS, so the private-window question does not arise for it: the session-only wording wins over the private-window wording in the dialog line, the tooltip, the accessible name and the cue, so a temporary graph has its own persistent cue (D-155; the dialog is shown once, a tooltip is invisible on touch and from font scale 1.5 the save status is glyph-only); otherwise a suspected private window wins over the conservative line. The block is the one table, and `WebStorageUx.durabilityWording(sessionOnly, privateSuspected)` returns its row:

<!-- durability-wording:start -->
```text
# sessionOnly | privateSuspected | wording id | first-write dialog line | Saved tooltip and accessible name | hint row cue (- = none; the full form of the `cue-forms` block)
true | true | SessionOnly | This graph is open temporarily: its new-editor sketches are kept only until you close or reload this page. | Saved for this session only | Temporary graph: lost on close.
true | false | SessionOnly | This graph is open temporarily: its new-editor sketches are kept only until you close or reload this page. | Saved for this session only | Temporary graph: lost on close.
false | true | PrivateWindow | This looks like a private window: new-editor sketches are kept only until you close it. | Saved in this window. A private window may clear it when it closes. | Private window: may be cleared.
false | false | Conservative | New-editor sketches are saved in this browser's private storage. The browser can clear it, so back it up with git sync or a linked folder. | Saved | -
```
<!-- durability-wording:end -->

**The cue at large text scale (UX-153; D-151, D-155).** The hint row is one line, and at font scale 2.0 one line holds half as many characters, so each cue has four forms of decreasing length (block `cue-forms`: full, mid, compact, chip) and a rule for sharing the line. A window has no `HintStatus` row where the chrome table collapses it or lists it under `permanent` for the layout; the computed block lists those windows once (the line "windows without HintStatus", read from BOTH the "Rows collapsed" column and the `permanent` section, so landscape phones and the 400% zoom windows are in it as well as the small phone at scale 2.0), and this paragraph does not repeat them. There the cue is the first chip of the action chip row, a persistent, non-dismissible chip that shows the longest of the four forms that fits the chip row width the table computes for that window (the computed block lists the form chosen per window; a window where even the chip form does not fit fails the script), so the cue is never cut, wrapped or scrolled out of the row:

<!-- hint-cue:start -->
```text
# key | value
line-order | prefix cue hint
prefix | Tool lock on
prefix-dropped-when | prefix and cue do not fit one line together
hint-text-dropped-when | the line would exceed one line
collapsed-home | action chip row
collapsed-home-position | first chip
collapsed-home-dismissible | no
```
<!-- hint-cue:end -->

<!-- cue-forms:start -->
```text
# wording id | full | mid | compact | chip
PrivateWindow | Private window: may be cleared. | Private window: may clear. | Private: may clear. | Private
SessionOnly | Temporary graph: lost on close. | Temporary: lost on close. | Temporary: not kept. | Temporary
```
<!-- cue-forms:end -->

The line is built left to right as the prefix, the cue and the hint text and truncated from the right, so the hint text yields first; the cue shows the longest of the full, mid and compact forms that fits the one line at the current font scale (computed per scale), and is never cut and never wrapped; when the prefix and the cue do not fit one line together the prefix leaves the line (the "Tool lock" chip of the action chip row and the live region still carry it); the cue is the first thing the row shows when no tool hint exists. A tap on the chip shows the tooltip text of its wording row in the table above as a dismissible, persistent-until-closed popover (UX-90); the chip's accessible name is the full form, which contains the visible label (WCAG 2.5.3).

False positives are possible (a normal window on a device with little free disk can report a low quota), so the wording says "looks like", and the hint, the cue and the private-window lines are dropped, leaving only the conservative line, for a browser where Spike 0.1.8 shows no separation between a normal and a private window.

### 14.9 The boot-refusal screen: a second tab on a persistent web graph (UX-152; plan Task 1.4.6k; D-149)
`DriverFactory.createDriverAsync` holds a tab-lifetime lock `stelekit-sqlite-driver-<graphId>` and a second tab that cannot take it throws `GraphLockedElsewhereException` at boot, before the editor exists (`DriverFactory.js.kt:38`, `browser/Main.kt:278`); today the page shows one plain paragraph in the `#loading` overlay (`showBootError`) with no heading, no action and no announcement. Which screen a real second tab shows, by platform and graph kind:

<!-- second-tab-outcomes:start -->
```text
# platform | graph kind | a second window or tab shows
desktop | any | neither (the writer lease is always held)
android | any | neither (the writer lease is always held)
web | persistent | BootRefusal
web | temporary | neither (no driver lock, separate in-memory databases)
web | demo-fallback | neither (no driver lock)
ios | any | neither (no sketch storage)
```
<!-- second-tab-outcomes:end -->

The `OtherTab` banner (14.8) is therefore the defence-in-depth state of the sidecar lease, not what a real second tab shows (the real behaviour is recorded in Task 1.4.7d). The boot-refusal screen is plain DOM in `#loading` (no Compose exists yet), so its figures are CSS pixels, defined once here:

<!-- boot-refusal:start -->
```text
# key | value
viewport-css-px | 320
root-px | 16
gutter-rem | 1
font-rem | 1
line-height-rem | 1.5
target-rem | 2.75
char-px | 8
text-scale | 2.0
heading | Graph open in another tab
message | This graph is already open in another browser tab. Switch to that tab, or close it and this page reloads by itself.
message-no-retry | This graph is already open in another browser tab. Switch to that tab, or close it and reload this page.
status-waiting | Waiting for the other tab to close.
status-free | The graph is free. Reloading...
action | Reload
action-accessible-name | Reload this page
secondary-action | Open temporarily
secondary-accessible-name | Open a temporary graph instead
secondary-target | ?mode=ephemeral
fg | #cccccc
bg | #1e1e1e
button-fg | #ffffff
button-bg | #2f6fd6
focus-ring | #ffffff
focus-ring-px | 2
link-fg | #8ab4f8
container-overflow-y | auto
animation | none
retry | navigator.locks.request with a signal (INFERRED)
```
<!-- boot-refusal:end -->

The screen is a region named by its level-1 heading (`aria-labelledby`). The message has `role="alert"` so a screen reader announces it once when it appears, and focus is NOT moved to the heading (a focused heading plus an alert would announce the same text twice, so the alert is the one announcement source); [Reload] is the first tab stop and [Open temporarily] the second, a link to `?mode=ephemeral`, the existing "open temporarily" entry of `browser/Main.kt:185`, which takes no driver lock, so a user who cannot close the other tab can still sketch (with the session-only cue of 14.8). Every size is in `rem`, so browser text zoom scales gutters, line height and targets together (WCAG 1.4.4); the screen reflows at 320 CSS px and at 400% zoom with no horizontal scrolling (1.4.10: the longest word fits one line at the largest text scale) and its container scrolls vertically (`#loading` gets `overflow-y: auto`, because `html, body` are `overflow: hidden` today, `index.html:13`); each action is at least `target-rem` tall and wide (2.5.8); text and button label meet 4.5:1 (1.4.3), the button against the page background and the focus ring against both meet 3:1 (1.4.11) and the focus ring is `focus-ring-px` thick (2.4.7); every child of `#loading` (the "Loading SteleKit" lines) is removed when the screen appears and nothing animates (there is no spinner today, `index.html`; a later one would honour `prefers-reduced-motion`). The script computes the ratios and the widths. The contract of its states:

<!-- boot-refusal-contract:start -->
```text
# event | seconds | visible | focus | announcement
arrive | 0 | the heading, the message, [Reload], [Open temporarily] and the waiting line | unchanged | the message once (role alert)
waiting | 0 | the waiting line "Waiting for the other tab to close." | unchanged | none beyond the message
lock-granted | - | the line "The graph is free. Reloading..." | unchanged | polite status once, then the page reloads after 1 s
reload-activated | - | the page reloads | - | none
retry-unsupported | - | the message-no-retry variant of the message, no waiting line, [Reload] and [Open temporarily] | unchanged | none beyond the message
```
<!-- boot-refusal-contract:end -->

The automatic retry waits on the same lock name with `navigator.locks.request(name, { signal })` and reloads when it is granted (INFERRED: Task 1.4.7d records that the pending request is granted when the first tab closes); a browser without that API shows the `message-no-retry` variant, because the sentence "this page reloads by itself" would promise a reload that never comes, and [Reload] and [Open temporarily] only. No graph name, path or id is shown or logged on this screen.

### Error table (section 14)
| State | User sees | Exit |
|---|---|---|
| Backup write fails | conversion aborted: "Couldn't create a backup, so nothing was changed." | [Try again] [Close]; v1 untouched |
| Verify-hash mismatch after write | "Couldn't verify the converted sketch. Original kept." | [Open in legacy editor] [Try again] |
| Disk full | "Not enough space to convert." | [Close]; v1 untouched |
| External edit of sidecar during session | existing `DiskConflict` flow with [Keep mine] [Load theirs] [Save as copy] | pick one |
| Two devices converting concurrently | second device sees conflict dialog above | same |
| Second browser tab on a persistent web graph | the boot-refusal screen of 14.9 | [Reload] or [Open temporarily], or wait: the page reloads when the first tab closes |

---

## 15. Large drawing banner and degradation

Thresholds: soft at 80% of cap (4,000 elements or 160 constraints), hard at 5,000 / 200. The banner below is the `LargeDrawing` row of the banner table, an Info occupant of the banner slot of section 1 (it waits if a higher-ranked occupant is showing); its row shows "Large drawing: effects off." with [Learn more] and Dismiss under More, and the explanation of the Details line is the sheet's content.

```
+------------------------------------------------------+
| (i) Large drawing: effects off. [Learn more] [⋮] |
+------------------------------------------------------+
Details: "Sketchy style is off, shadows are skipped, live constraint glyphs are hidden. Your drawing is unchanged." [Re-enable effects (may be slow)]
```
Degrade order: Sketchy rendering off (drawn as Clean, per-element style kept), shadows, off-screen culling, low-res thumbnails, constraint glyphs. Semantics emit only viewport + selected (cap 200) and say so: "Showing 200 of 4,812 elements for screen reader; use Find to reach others" (use "Go to element...", plan Story 3.6.5, to reach the others).

At the hard cap:
- Drawing a new element: refused with "This sketch has 5,000 elements, the maximum." [Delete some] [Split into a new sketch] (moves the selection into a new sketch) [OK].
- Paste/import: see section 11 (first 5,000 / new sketch / cancel).
- Constraints at 200: the `ConstraintLimit` toast "Constraint limit reached." with [Review] (Details: "200 constraints is the limit for one sketch.").

Memory pressure (Android): recoverable `LowMemory` toast "Low memory." with [Save now] and Dismiss under More (Details: "Save and reopen the sketch."); persisted logs apply.

---

## 16. Diagnostics host

Replaces the single `error: String?`. One list, newest first, collapsed to a one-line banner that expands. The banner is the single banner slot of section 1: one occupant at a time, chosen by the rank in the banner table there (the one place the order is defined; this section does not restate it).

```
+ Notices (2) ----------------------------+
| ✖ Couldn't save.                [Retry] |
| ⚠ Parallel conflicts with Horizontal on Line 3   [Show] |
+-----------------------------------------+
```
- Each entry: severity glyph + text + 0-2 actions. Announced once via a polite live region (assertive for errors that block work: save failure).
- Dismissable; resolved entries (save succeeded) auto-clear with "Saved".
- Every transient notice, toast and announcement goes through ONE priority queue (UX-91, plan Story 3.6.7): Blocking (save failed, conflict) > State (read-only, other editor) > Warning (constraint conflict, capacity) > Info (toasts, undo/redo) > Onboarding (the sequencer), the order of the banner table's tiers; one at a time, at most 3 visible then "+N more", identical notices coalesce; polite live-region announcements are throttled to at most one per second (last state wins) and DOF status is announced only when a drag settles.
- Notice timing (UX-112; WCAG 2.2.1, 2.2.2; D-20): a notice that carries an action (Undo, Unlock, Switch layer, Retry, Reload, Keep mine, Open) persists until the user acts or dismisses it, except a pure confirmation of an already-undoable edit ("Constraint added." [Undo]), which stays at least 10 seconds; the countdown pauses while the pointer hovers over it or focus is inside it and restarts from the full 10 s when they leave; an Info notice with no primary action stays at least 5 seconds with the same pause (the one exception is the `StillSaving` toast, whose timing is the contract block of 14.8, UX-150); a notice that carried an action and has gone remains in the list above, and Undo stays the ordinary Undo command. Touch note (WCAG 2.2.1): there is no hover on touch, so the pause comes from touching the notice (a touch on the notice freezes its countdown until released and 3 s after), from focus, and from screen-reader focus; a timed notice that is only informational can still be missed on touch, which is acceptable because no information in it is essential: every notice, with or without an action, stays readable in the diagnostics list, and nothing that needs an answer is action-less.
- One onboarding item at a time, and ONE recurrence table (UX-116, UX-122; D-19): the finger-drawing hints, the edge-pan-stopped notice, the Upgrade chip, the first-run coach mark, the single-key chip and the empty state go through an `OnboardingSequencer` that shows at most one, in the rank order of the banner table of section 1 (listed below in that order); a waiting item appears once the visible one is dismissed or acted on, nothing is announced while another is visible, and Blocking and Warning notices above always take precedence. The per-item recurrence is defined HERE and nowhere else (the earlier blanket "each once per device" contradicted the Upgrade chip, whose [Not now] returns, and the finger hint, which repeats once):

| Banner rank | Item (`OnboardingItem`) | Recurrence class | Shown when | Retired by | Max shows | Counter scope |
|---|---|---|---|---|---|---|
| 9 | `EdgePanStopped` ("Edge pan stopped working here...") | once | the reactive backstop of section 3 fires | any dismissal | 1 | per device |
| 10 | `FingerHintOff` ("Finger drags move the view...") | twice-or-acted | first one-finger drag with a drawing tool while Finger drawing is OFF | any action button ([Draw], [Got it]) or choosing the setting; otherwise after the 2nd showing | 2 | per device |
| 11 | `FingerHintOn` ("Finger drawing is on...") | twice-or-acted | first one-finger drag with a drawing tool on a device where no stylus was seen | any action button ([Turn off], [Got it]) or choosing the setting; otherwise after the 2nd showing | 2 | per device |
| 12 | `UpgradeChip` (classic editor only) | until-acted | every open of a `NotConverted` sheet with the mode `On` and no conversion refusal (D-98); where the row would wrap past 2 lines the consent sheet opens instead, at most once per sketch project per day on this device | [Start] (converted) or the chip menu "Keep this sketch classic" (`Declined`); [Not now] (or any dismissal of the sheet) hides it for the rest of this session, for every sketch of the project, and it returns in a later session | unlimited (the sheet form: at most once per day per project, and not again in the session after a dismissal) | per sheet |
| 13 | `CoachMark` | once | first v2 editor open on the device | any dismissal, Esc, or activating any pointed-at control | 1 | per device |
| 14 | `SingleKeyChip` (web) | once | first single-key press while `SingleKeyShortcuts` is Off | any dismissal or turning the setting on | 1 | per device |
| after 14 (not a banner) | `EmptyState` | while-empty | whenever the sheet has no elements and no higher item is visible | the first element (returns if the sheet is emptied again) | n/a (not stored) | none |

  The rank order puts `EdgePanStopped` first, then `FingerHintOff`, then `FingerHintOn` (the item that explains a blocked gesture goes before the item that explains a default), then the Upgrade chip, the coach mark and the single-key chip; the others wait their turn. The pen-detected flip (`PenDetected`, banner rank 6) is a Warning notice of the notice queue, not an onboarding item: once per device, never queued behind these. Classes: **once** = at most one showing ever on the device, any dismissal retires it; **twice-or-acted** = shown on the first trigger, shown once more on the next trigger if no action button was pressed, then retired; **until-acted** = returns on every trigger until its own acting button is used, with an in-session [Not now]; **while-empty** = a state, not a nag, never counted. The table is a `OnboardingPolicy` constant (item, banner rank, class, max shows, retiring events, scope; the rank is read from the banner table, not restated) that `OnboardingSequencer` reads and `OnboardingPolicyTest` asserts row by row, so the document, the code and the test cannot drift.

  **Toast recurrence (the only toast with a recurrence rule; every other toast confirms an action the user just took).**

| Toast | Recurrence class | Shown when | Retired by | Max shows | Counter scope |
|---|---|---|---|---|---|
| `ClassicEditor` ("Created as classic.") | once per project per reason | a sketch is created classic and the STORED mode is not Off (a cap that lowers it to an effective Off still shows it; with a stored Off only a silent Notices entry is written) | any dismissal or action ([Change] or [Learn more]) | 1 per (graph, project, reason) | per device |

  A `ToastRecurrencePolicy` constant holds this row and `OnboardingPolicyTest` asserts it beside the onboarding rows. [Change] is the toast's visible action only where Settings can change the outcome; otherwise [Learn more] is (the `ClassicEditorFixed` row, section 14.1).
- No user element text in messages that reach logs (P5); on-screen messages may name layers/elements.
- Maximum 20 entries; older collapsed into "+N earlier" with [Clear all].

Save status states (top bar; labels and the font-scale forms are in section 1): Saved / Saving... / Save failed [Retry] / Unsaved changes. **On the web build (UX-140; D-119)** "Saving..." covers both the debounce and the wait between a write that is in memory (`Cached`) and one that OPFS has committed (`Persisted`), "Saved" appears only at `Persisted`, after 3 s of waiting the `StillSaving` toast (the toast table, a polite status message visible on touch too) reads "Still saving to this browser." and the tooltip and accessible name of "Saving..." read "Saving... Still saving to this browser." (the visible label first, so the name contains it, WCAG 2.5.3; block `save-status-names`), in a graph opened temporarily those of "Saved" read "Saved for this session only", and in a window suspected to be private (and not temporary) they read "Saved in this window. A private window may clear it when it closes." (one winner per state, the `durability-wording` block of 14.8); a failed OPFS write raises the `SaveFailed` banner like any failed save, and a full browser store raises its quota variant (14.8), and the browser's own leave-page prompt guards pending writes and a temporary graph that holds a new-editor sketch; that prompt is the browser's generic text and a mobile browser may not show it, so the edits of the last moments can still be lost there (14.8). A failed save never discards memory; leaving the screen with unsaved changes prompts [Retry save] [Export copy] [Discard].

<!-- save-status-names:start -->
```text
# state | visible label (below font scale 1.5) | tooltip and accessible name
Saved | Saved | Saved
Saving | Saving | Saving...
StillSaving | Saving | Saving... Still saving to this browser.
SessionOnly | Saved | Saved for this session only
PrivateWindow | Saved | Saved in this window. A private window may clear it when it closes.
```
<!-- save-status-names:end -->

---

## 17. Keyboard map and shortcuts sheet

Press `?` (or overflow > Keyboard shortcuts) opens a modal listing the map, grouped: Tools, Edit, Arrange, View, Constraints. Esc closes; focus returns to the canvas.

Core map (S3.6.1): tools V/1 Select, R/2 Rectangle, D/3 Diamond, O/4 Ellipse, A/5 Arrow, L/6 Line, P/7 Freedraw, T/8 Text, 9 Image, H Hand, F Frame, E or 0 Eraser, X Point, C Arc (all 14, UX-100); Q tool lock; Ctrl+' Grid and Alt+S Snap (also the checkable overflow entries "Grid" and "Snap", so the two cluster toggles have a keyboard chord and a menu path; modifier chords, so they stay on when Single-key shortcuts is off; on macOS Cmd+'; layout caveats: Ctrl+Alt is never treated as Alt (where AltGr produces Ctrl+Alt, Alt+S does not fire Snap and the overflow entry is the path), and where the apostrophe is a dead key or absent the chord falls back to the PHYSICAL key, as the brackets do); K or Ctrl+Shift+K (web: Ctrl+K) Constrain menu (UX-111); Ctrl+Z / Shift+Ctrl+Z (Ctrl+Y) undo/redo; Ctrl+C/V/X/D; Ctrl+G / Ctrl+Shift+G group/ungroup; `[` `]` send backward/forward, Ctrl+`[` `]` to back/front; arrows nudge the selection (Shift = 10 units) and pan the view when nothing is selected (Shift 200 dp; Ctrl+arrow always pans; PageUp/PageDown one screen; Home fit; UX-105); Delete/Backspace; Tab/Shift+Tab cycle elements, F6/Shift+F6 jump to the next/previous region without cycling elements (UX-117; the walkthrough popover is not an F6 stop: from it F6 goes to the region that follows its anchored region in the F6 list and Shift+F6 to the anchored region itself, section 22); Enter edit text; Esc cancel/deselect; Ctrl+0 fit; Ctrl +/- zoom.

Single-key shortcuts (UX-85, UX-108; WCAG 2.1.4): the setting "Single-key shortcuts" covers EVERY printable single-character key pressed without Ctrl, Cmd or Alt, not only letters: tool letters, the digits 0 to 9, `?`, `[` and `]` and `Q` (tool lock); keys that are not printable characters (Delete, Backspace, arrows, Esc, Tab, Enter, F6, Home, PageUp, PageDown) and every modifier chord stay on in all settings. They fire only while the canvas has focus and are behind the setting (shortcuts sheet and Settings); with it off, the shortcuts sheet is reached by the overflow entry, `[` and `]` by the Order menu, and the tools by the strip. Defaults: ON on desktop; ON on Android only with a hardware keyboard and no TalkBack (touch exploration); OFF on web, with a one-time chip (the `SingleKeyChip` row "Single keys are off." with [Turn on]; Details: "Single-key tool shortcuts are off."; [Got it] under More) because a screen reader cannot be detected reliably in a browser. With it off only modifier chords work and every tool stays reachable from the strip by Tab, Enter and arrows. Tool letters match the character the layout produces (AZERTY, Dvorak), with a physical-key fallback for brackets; on macOS every Ctrl chord is Cmd and the sheet shows Cmd.

**Skipping the canvas elements (UX-117; WCAG 2.4.1)**: with many elements Tab would walk every one, so (a) a link "Skip canvas elements", visible on focus, is the first Tab stop inside the canvas region and moves focus to the NEXT stop of the tab order of section 10 (the action chip row while chips exist, then the crosshair pad, then the selection bar, so Align, Order, Lock and Transform are one Tab press away, or the view cluster where no selection exists), (b) F6 and Shift+F6 cycle the regions of that order without touching the elements: top bar, notice region (banner slot and toasts, only while occupied; this is the keyboard path to banner and toast actions), tool strip, canvas, selection bar (where it exists), view cluster, side panel, status line, (c) Esc from an element returns focus to the canvas container; all three are listed in the shortcuts sheet under View with one explanatory line. On the web a browser may keep F6 for its own address bar, so there the skip link and an overflow entry "Go to region..." (the same list of regions) are always present and are the guaranteed path; F6 is handled only while the canvas or a sketch region has focus. The overflow entry "Notices..." opens the diagnostics list, which holds every action of every notice and is also in the tab order.

**Esc precedence (P1; the ONE list, tested).** One Esc press undoes the first row below that currently applies and nothing else; the next press takes the next row. (1) an open dialog or sheet; (2) an open menu, flyout, zoom menu, Pan pad or help popup (the one with focus first); (3) a text overlay (commits a non-empty text exactly like [Done], removes an empty new one: section 6, so the text is never lost) or a dimension field (cancels the edit, the old value stays: section 9); (4) "Solving..." (cancels the solve, the last good scene stays); (5) an armed constraint picker (disarms it, nothing is added); (6) a mid-draw preview (cancels it, no command; this includes a rectangle whose first corner was placed by Enter in Crosshair mode, so the keyboard-drawing walk of section 10 needs ONE Esc to cancel it and stays in Crosshair mode); (7) Crosshair mode (cancels a pending Resize, Rotate or Move preview, otherwise leaves the mode); (8) the first-run walkthrough (ends it; with focus inside its popover no row above can apply, because a menu or dialog would hold focus, so Esc ends the tour at once); (9) a toast or banner action that has focus (a toast, or a banner with a Dismiss, is dismissed; a Blocking banner has no Dismiss, so Esc only returns focus to the canvas and the banner stays, and that press takes this row; Esc on a focused Upgrade chip action is Not now, and Esc on a constraint-conflict card is Remove new constraint, that card's dismissal, UX-32); (10) an active tool other than Select, including tool lock (returns to Select); (11) the selection (deselects); (12) the newest toast (dismisses it). Esc never undoes a committed edit and never closes the editor. `KeyboardDrawingFlowTest` (Task 3.6.3c) walks rows 6 and 7 of the keyboard-drawing flow; the all-rows assertion lives in `EscOrderIntegrationTest` (Task 6.2.4h), the first task after every owner of a row exists, and presses Esc twelve times with every row in effect, asserting the order above and that each press changes only its row; when Task 4.2.6b is parked (an A6 DEFER, an A1 PIVOT without Job 2 first) row 5 is absent and the same test asserts the remaining eleven rows in the same order, and when Task 4.2.7b is parked row 3 is the text overlay only.

**Screen readers (UX-108)**: the canvas exposes an application role so NVDA and JAWS switch to focus/application mode and pass keys through. In browse mode these readers reserve single-letter quick keys (INFERRED from their documentation, UNVERIFIED against the owner's reader, U11): H heading, F form field, E edit field, T table, L list, D landmark, B button, G graphic, K link. Our tool letters H, F, E, T, L, D collide with them in browse mode, which is the reason for the web default Off and for testing only in focus mode; the shortcuts sheet says so in one sentence.

Rules: the tool strip is ONE Tab stop with roving focus (arrow keys move inside it) and Tab cycles ELEMENTS only while the canvas has focus; dispatcher yields to text overlays and to focused fields; shortcut conflicts with the IME/browser (web: Ctrl+D bookmark, Ctrl+W) are resolved by preventDefault only when the canvas has focus; shortcuts never trap focus: Tab from the canvas goes first to the "Skip canvas elements" link, which moves on to the next control (section 10); Tab on past the link cycles the elements and then exits to the next control, and Esc then Tab leaves the canvas from any element.
Undo/redo announce: "Undid: move rectangle", "Redid: add arrow"; nothing to undo announces "Nothing to undo".

---

## 18. Embed preview and cross-graph copy (condensed)

Embed (journal/page block):
```
[ thumbnail of sketch ]   Kitchen · Sheet 1        [Open]
```
- Dispatches on schema (S6.1.1): v1 -> existing preview; v2 -> draw-list preview. Large (500+) sheets render a cached thumbnail.
- Where `V2StorageAvailable` is false (iOS, a browser without OPFS atomic replace, no store) the sheet file cannot be read, so a v2 embed shows the compact placeholder "New-editor sketch. Open it in the desktop or Android app." with [Open page] (no thumbnail, no error, never a crash; UX-135); in a browser whose verdict is Available a v2 embed renders the draw-list preview from the preloaded sidecar as on desktop.
- While `V2StorageStatus` is `Checking` a v2 embed shows the loading placeholder "New-editor sketch. Checking storage..." with [Open page] and then resolves to the preview or to the placeholder above (UX-141; D-128).
- `NewerSchema` project: placeholder "Update SteleKit to view" with [Open page]; never crashes.
- Missing/deleted sketch: "Sketch not found" [Remove embed] [Choose another].
- Tapping opens the editor per the routing matrix.

Cross-graph copy (overflow > Copy sketch to graph...):
- Pick target graph; unwritable targets are shown disabled with reason (`TargetWriterCapabilities`; on web this includes a graph the browser has not preloaded, INFERRED, the capability check decides). A v2 sketch whose source platform has `V2StorageAvailable` false is refused with the reason of section 14.1 and nothing is written (UX-135); from an Available browser it copies as on desktop.
- Success: "Copied to <graph>." [Open]. Name collision: "A sketch named 'kitchen' already exists there. Nothing was copied." [Rename and copy] [Cancel] (copy never edits existing files; `SlugExists`).
- Failure: "Couldn't copy" [Retry] [Cancel]; no partial bundle.

Acceptance: see UX-60, UX-61, UX-62, UX-63.

---

## 19. Log output (non-interactive, condensed)

Sample:
```
sketch.tool.activated tool=Rectangle
sketch.solve.failed kind=Conflict ids=c1,c2
sketch.import.summary imported=41 skipped=3
sketch.migrate.outcome sheetId=s1 result=Converted
```
Acceptance: ids and counts only, never element text or file names (UX-70); every user-visible failure in sections 1-16 has a corresponding log line (UX-71); logs use `Logger` (UX-72); no log line is emitted per pointer-move (UX-73).

---

## 20. UX acceptance criteria

Tasks measured on a fresh empty sketch unless stated. "Step" = ONE counting convention for this document, requirements metric 2 and the tests: one click, tap or key chord, a menu choice, or one typed string together with the key that confirms it (typing a label and pressing Esc, or a value and Enter, is ONE step).

### Authoring and tools
- UX-01 A user can draw a labelled box in <= 4 steps: (1) pick Rectangle, (2) drag, (3) Enter on the selected shape to open its text editor, (4) type the label and finish with Esc or a tap away.
- UX-02 A user can connect two existing boxes with a bound arrow in <= 3 steps (pick Arrow, drag from box A to box B, release); moving either box moves the arrow end.
- UX-03 The 15-element architecture diagram (9 labelled boxes, 4 bound arrows, 1 frame, 1 group) can be authored without leaving the editor, with no step requiring a drag on a touch-only device (non-drag path exists).
- UX-04 After drawing one shape with tool lock off, the active tool is Select; with tool lock on it stays; the lock state is announced and visible without colour, on the strip, in the hint text and as a chip of the action chip row.
- UX-05 Undo, Redo and Delete are visible and operable in every layout without scrolling the tool strip.
- UX-06 In a 360 x 640 dp window the tool strip is bottom-docked; in a 1280 x 800 dp window it is a left rail with a side pane (the layout rule of section 1 decides every other window); no control overlaps the crosshair chip, zoom cluster, action chip row, pan pad or shell mic button.
- UX-07 With Finger drawing OFF, one-finger touch drag with a drawing tool pans and creates nothing while stylus or mouse drag creates; the visible "Finger drawing" toggle changes this; a first-time hint explains it. (Defaults are in UX-80.)
- UX-08 A second pointer or Esc during a draw cancels the preview with no element and no undo entry.
- UX-09 Wheel/pinch zoom keeps the focal point; Fit to content is reachable in 1 step; zoom is clamped 1%-3000% with disabled +/- at limits.

### Selection, organisation, style
- UX-10 Resizing a multi-selection by one handle is a single undo step.
- UX-11 Selecting a grouped element selects the outer group; double-click descends one level; ungroup is available by shortcut and menu.
- UX-12 Attempting to edit an element on a locked layer shows the `LayerLocked` toast "Locked layer: <name>." with an Unlock action; the canvas never selects hidden or locked elements (the one selectability rule of section 4: the Elements list may select a locked element, to unlock or rename it, and never a hidden one).
- UX-13 Hiding a layer containing the selection clears the selection and announces it.
- UX-14 The style panel header states whether it edits "Selection (N)" or "Defaults for <tool>"; mixed values show "Mixed".
- UX-15 Every colour swatch has a text name available to screen readers and tooltips.
- UX-16 Layers can be created, reordered (via drag and via Move up/down buttons), locked, hidden, renamed, given opacity and deleted, each as one undo step.
- UX-17 Layer toggles read "Layer <name>, visible/hidden, switch" and "Layer <name>, locked/unlocked, switch" to TalkBack.
- UX-18 Deleting a layer with elements requires a confirmation that states the element count and offers a non-destructive alternative.
- UX-19 The layers panel is hidden until a second layer exists, and can still be reached from the overflow menu.

### Text
- UX-20 Multi-line text is editable in place with IME composition at zoom 2.5, and the overlay stays above the soft keyboard.
- UX-21 Blurring an empty text element leaves nothing and shows no error.
- UX-22 Shortcut letters type into the text overlay and do not switch tools while it has focus.
- UX-23 Pasting text over 10,000 characters is refused with a stated limit and the existing text is kept.

### Constraints
- UX-30 A constraint can be added to one or two selected elements in <= 3 steps (Constrain, pick type, optionally enter value); a single-element constraint such as Horizontal needs only one selected element.
- UX-31 On a sheet that has at least one constraint or is a technical sheet the status line always shows one of "Fully constrained", "N degrees of freedom", or "Over-constrained: N conflicts", exposed as a live region; on a diagram sheet with no constraints no degrees-of-freedom text is shown; "Over-constrained" appears only for a sheet LOADED with conflicting constraints (import, external edit, sync merge), never from interactive editing, which rejects the conflicting constraint.
- UX-32 An over-constraining new constraint is rejected; geometry is unchanged; the message names the constraint and its conflict partner (e.g. "Parallel conflicts with Horizontal on Line 3"); actions Remove new constraint, Make reference (dimension kinds), and Show conflicts are present; Esc counts as Remove.
- UX-33 Constraint state is conveyed by glyph or dash pattern plus text in the list, never colour alone; glyphs meet 3:1 contrast and the touch-target floor (UX-46).
- UX-34 Selecting a row in the constraint list highlights its geometry; selecting geometry highlights its constraints.
- UX-35 Non-convergence leaves geometry at the last good pose and never shows NaN, empty or half-solved geometry; the user sees "Can't satisfy constraints here" and can undo.
- UX-36 A solve longer than 100 ms shows a progress indicator with Cancel that restores the last good state.
- UX-37 Constraint menu items invalid for the selection are disabled with a stated reason, not hidden; the Constrain flyout is available for 1 or more selected elements (single-element kinds enabled) and its trigger is disabled with a reason when nothing is selected.
- UX-38 Deleting geometry that has constraints removes them in the same step and one undo restores both; a toast states the count.
- UX-39 Auto-added constraints (coincident, horizontal/vertical) show a one-line toast with Undo and can be suppressed with Ctrl.

### Non-drag, keyboard, accessibility
- UX-40 Resize, rotate, move element (crosshair "Move element", section 10), bind-arrow-end, marquee, draw-polyline, layer reorder, panning the view (pan arrows, UX-120) and resizing a bottom sheet (handle button, UX-120) each have a single-pointer or keyboard path with no drag (WCAG 2.5.7). The Freedraw tool's own stroke is the one recorded exemption (dragging along a path is essential); its result is reachable by "Draw polyline by points".
- UX-41 All tools, selection, move, resize, z-order, layer and constraint operations are reachable by keyboard alone; there is no keyboard trap (Tab leaves the canvas).
- UX-42 Pressing `?` opens a shortcuts sheet that lists every shortcut in the core map; Esc closes it and returns focus to the canvas.
- UX-43 Undo/redo announce "Undid: <action>" / "Redid: <action>"; empty history announces "Nothing to undo".
- UX-44 TalkBack/screen reader can focus individual elements (label by kind, e.g. "Arrow from API to DB"); with > 200 elements only viewport + selected emit nodes and a statement says so.
- UX-45 The focus ring on canvas elements and handles is two-tone (a 2 dp inner ring in the theme's on-surface colour inside a 2 dp outer ring in the inverse colour), each tone has >= 3:1 contrast against the pixels beside it (element fill on one side, canvas on the other) in the light, dark and high-contrast themes (sampled by `UxA11yAcceptanceTest`), and it is never clipped or fully hidden behind a panel.
- UX-46 All text and controls meet >= 4.5:1 contrast; non-text UI (handles, glyphs, strokes in panels) >= 3:1; interactive hit areas meet the ONE touch-target floor: >= 44 dp whenever a touch or pen pointer is present and >= 24 dp only on mouse-only devices (WCAG 2.5.8); the pinned dock buttons are 48 dp in every pointer class and the floor never moves a control (section 1).
- UX-47 With "reduce motion" enabled, EVERY transition is replaced by an instant change: solver and zoom animations, panel and bottom-sheet opening, flyout open/close, toast and notice enter/exit, the first-run coach mark, banner expand/collapse, the erase preview fade, the selection overlay, indeterminate progress, the skeleton shimmer and the "Solving..." indicator (static text or a static glyph instead); all go through one `MotionPolicy` (a grep test enforces it).
- UX-48 Save and solve results are announced through live regions (polite; assertive only for a blocked-work error).

### Import / export
- UX-50 Importing a valid `.excalidraw` file takes <= 3 steps (Import, pick file, Done) and is one undo step; the summary says "Imported X of Y elements" and lists skipped kinds with counts.
- UX-51 A non-Excalidraw file shows "Couldn't read this file: not valid Excalidraw JSON" with a reason code, creates nothing, and offers Choose another file and Close.
- UX-52 A file over the size cap is refused before parsing with the cap stated.
- UX-53 An import that would exceed 5,000 elements offers Import first 5,000, Import into a new sketch, and Cancel, each working.
- UX-54 Import is cancellable and leaves the sheet unchanged when cancelled or when the process dies mid-import.
- UX-55 Missing image binaries become labelled placeholders, never crashes; linked images state they were not downloaded.
- UX-56 Summary dialog has "Copy details" and "Undo import"; all dialogs trap focus, announce their title, and restore focus.
- UX-57 Export offers PNG (1x/2x/3x, background toggle), SVG, `.excalidraw`, and scope (whole, selection, frame); an impossible export shows the reason and a one-tap fix (e.g. Use 1x).
- UX-58 Exporting layers to `.excalidraw` shows the "layers are flattened" notice.
- UX-59 Pasting Excalidraw clipboard JSON places elements at the cursor in one undo step; pasting other text does nothing.

### Embed, copy
- UX-60 A journal embed of a v1 and a v2 sketch both render; a newer-schema project shows "Update SteleKit to view" and never crashes.
- UX-61 A missing sketch shows "Sketch not found" with Remove embed and Choose another.
- UX-62 Copy to graph refuses a name collision with "already exists" and changes nothing; unwritable targets are shown disabled with a reason.
- UX-63 Copy-to-graph success shows the destination and an Open action.

### Migration, flag, old-build warnings
- UX-64 Before the first v2 write in a sketch project, the confirmation states that older SteleKit versions will not open the whole sketch project, mentions the backup, and requires an explicit confirm (a NEW sketch in a not-yet-acknowledged project shows the new-sheet variant: no backup sentence, an explicit [Create classic sketch] button, Esc and back create nothing); [Open classic] writes nothing and the sketch stays offered (`NotConverted`); the dialog has one checkbox, "I understand", gating only "Convert and open"; "Keep this sketch classic" lives only in the Upgrade chip menu, makes the sheet `Declined` (per sheet) and is re-offered only from the sheet menu "Upgrade to the new editor". Merely opening a sketch never reaches this dialog (UX-104).
- UX-65 After conversion a one-time banner says the sketch was upgraded and a backup exists, with Details.
- UX-66 A failed or unverifiable conversion leaves the original unchanged and offers Try again, Open in legacy editor and Close.
- UX-67 With the flag off and `V2StorageAvailable` true, a v2 sketch opens read-only with the reason "Turn on the new editor to edit." and pan/zoom/select/export still work.
- UX-68 A newer-than-supported schema never writes and shows "Update SteleKit to view this sketch".
- UX-69 Toggling the flag mid-session does not swap or close the open editor and shows a notice that it applies next open, with a [Reopen] action that reopens this sketch at once on the user's explicit request; the read-only banner's [Use new] opens the banner variant of the enable confirmation (reopening unlocks editing THIS sketch; it stores `NewProjectsOnly`; disabled with "Not available in this build yet" while `ConversionReady` is false), and the sketch stays read-only until it is reopened.

### Observability and robustness
- UX-70 Log lines carry ids and counts only; element text and file names never appear.
- UX-71 Every user-visible error state in this document maps to one `sketch.*` log line.
- UX-72 A save failure shows "Couldn't save" with Retry, keeps the in-memory scene, and leaving the screen prompts Retry/Export copy/Discard.
- UX-73 At 4,000 elements a non-blocking "Large drawing" banner appears and degradation follows the stated order; at 5,000 new elements are refused with Delete some / Split into new sketch.
- UX-74 Empty sketch shows a helpful empty state with Import and shortcuts, which disappears on the first element.
- UX-75 No error state in this document is a dead end: each lists at least one action or a dismissal that restores a usable editor (verified by walking the tables in a review checklist).

### Triad repair additions (UX-76..UX-97)
- UX-76 Choosing a mode other than Off (Off, New sketch projects only, All sketches (Upgrade)) first shows a confirmation that repeats the whole-project old-version warning (a new-project form for New sketch projects only), with Learn what changes; the acknowledgement is per device and covers only turning the mode on (the Upgrade consequence is acknowledged per sketch project, UX-104); an option the build cannot offer is disabled with a stated reason, never hidden (after an A4 NO-GO only "All sketches (Upgrade)" is).
- UX-77 A sketch created as classic (flag off, build not ready, New sketch projects only in a project that is not a `NewSketchProject`, technical data refusal, or the user's own choice) shows the `ClassicEditor` toast ("Created as classic.", [Change], which opens Settings > Sketches > New editor) whose Details name the reason from the one `SketchReasonText` table and the route to the new editor; the Settings radio lines say that projects holding only new-editor sketches stay new and that a sketch added to any other existing project is classic in New sketch projects only; the toast is not shown in mode Off, appears once per project per reason, offers [Change] only where Settings can change the outcome (otherwise [Learn more]) and states the return path to the just-created sketch.
- UX-78 Restoring from backup requires a confirmation that states that edits made in the new editor since the upgrade are discarded and that a copy is kept.
- UX-79 The newer-schema screen's "Check for updates" and "Export last backup" both work and write nothing to the project.
- UX-80 Finger drawing: one name everywhere; default ON when no stylus has ever been detected on the device, OFF once one has (explicit choice never overridden); a hint (twice-or-acted, section 16) with Turn on/Turn off and Got it; finger-only phones can use every drawing tool with one finger and pan/zoom with two; the current state is always visible as text in the top bar ("Finger: On/Off", shortened to "On/Off" at font scale 1.5 and above), which never scrolls or collapses.
- UX-81 Constraints apply only to line, rect, polyline, circle (an ellipse with equal radii), arc and point; text, arrows, images, freehand and frames show disabled Constrain items with ONE plain-language reason (`SketchReasonText.NotConstrainable`, which names frames too); one rule table serves the flyout, auto-constrain and commands.
- UX-82 Auto-constrain is an explicit toggle, OFF by default on diagram sheets and ON on scaled/technical and converted sheets, with a touch-accessible one-shot suppression chip.
- UX-83 Degrees of freedom are always accompanied by a plain-language gloss ("2 moves still possible", "nothing can move").
- UX-84 The Constraints tab is present without a selection and shows an empty state that says how to start.
- UX-85 Single-key shortcuts (every printable single-character key, not only letters) fire only with canvas focus and can be turned off (WCAG 2.1.4); tool letters work on non-QWERTY layouts; on macOS chords use Cmd; defaults are in UX-108.
- UX-86 "Switch layer" and "Unlock all" actions exist, work, and announce their result.
- UX-87 Keyboard focus is never fully hidden behind a sheet, dock, banner or soft keyboard (WCAG 2.4.11).
- UX-88 At 200% font scale and 360 dp width nothing is clipped or unreachable and there is no horizontal page scroll.
- UX-89 Dark mode and high-contrast themes meet the same 4.5:1 / 3:1 bars, and the default stroke contrasts at least 3:1 with the canvas in both themes.
- UX-90 Every hover tooltip and disabled-reason has a touch and keyboard equivalent (long-press, help affordance or focus) exposed in semantics (WCAG 1.4.13).
- UX-91 All transient notices share one priority queue; polite live regions are throttled and coalesced (at most one per second).
- UX-92 Selection handles and every other control follow the one touch-target floor (a 44 dp hit area, 48 dp for primary dock buttons, whenever a touch or pen pointer is present; 24 dp mouse-only), and Delete is separated from Redo by at least 16 dp.
- UX-93 Fold/unfold, rotation and resize keep selection, active tool, tool lock, open panel, zoom/pan centre and text-edit contents.
- UX-94 Export, library, import and embed-thumbnail work longer than 200 ms shows a labelled progress state, announced at start and end.
- UX-95 Every user-visible string in the v2 UI comes from string resources.
- UX-96 A think-aloud pass with 3-5 users runs the SIX tasks of plan Story 6.2.8, numbered the same everywhere: (1) find a hidden tool, (2) turn on the new editor and read what it says (consent comprehension), (3) add a Parallel constraint, (4) draw a box and an arrow with touch on a phone, (5) pan the view while Finger drawing is ON, (6) give a line a length of 6 in (with and without the smart-dimension chip). At least one session (task 4 or 6) runs in a 320 x 480 dp window at font scale 2.0, the smallest guaranteed window, and records whether the chrome left a usable drawing area. The bar is ceil(0.8 n) of n participants (3 of 3, 4 of 4, 4 of 5) for tasks 1, 2, 3, 4 and 5 and ceil(0.6 n) (2, 3, 3) for task 6. Task 2 is passed only by answering the written comprehension question of UX-123 correctly; a miss on any task follows the decision rule of plan Story 6.2.8.
- UX-97 All 14 tools are visible flat only when the flat extent fits (one rule for every width class, section 1), otherwise they are grouped as split buttons (a main target and a separate flyout target, each at the touch floor); flyouts have a visible affordance and a keyboard path and open without any Alt-modified gesture (UX-109); a first-run coach mark appears once per device and its [Show me] runs the three-step walkthrough of section 22 (Back, Next, Done, focus moved to the popover and back, one announcement per step, wording that follows the input device).

Count of triad repair additions: UX-76 to UX-97 = 22.

### Triad repair iteration 2 additions (UX-98..UX-111)
- UX-98 A constraint target can be an endpoint, vertex, centre or edge, not only a whole element; point-like parts outrank edges in hit testing; candidates are marked by shape and named in text (never colour alone); picking works by tap, by keyboard cycling and from a list, with no drag; while a picker is armed a visible Cancel chip disarms it on touch.
- UX-99 A placed dimension's value is edited by tapping or clicking its label (or Enter or F2 on its row or glyph) as one undo step; a duplicate same-kind dimension on the same target opens the existing one, and a conflicting duplicate offers Replace existing.
- UX-100 All 14 tools appear in the tool inventory with a group, a shortcut (digits 1-9 for the first nine, letters for the rest, Eraser also 0), a hint line and a gesture row, and the shortcuts sheet lists all 14.
- UX-101 The Eraser previews what it will erase before release, works by tap on touch and without dragging, and skips locked elements with a count; where the platform exposes them, a stylus eraser end erases for that stroke and the barrel button is the secondary click.
- UX-102 When Finger drawing flips from ON to OFF because a pen was detected, a visible, announced notice says so at the flip; while Finger drawing is ON one-finger pan is replaced by two-finger drag, the Hand tool, the pan arrows (UX-120), an edge-pan strip where supported (UX-121) and Select-tool one-finger pan; the top bar always shows the Finger drawing state; and the think-aloud pass tests it. The pen-detected notice is the `PenDetected` banner row (a fixed summary, the full text in Details).
- UX-103 The old-version warning shown by the flag-enable confirmation, the first-write dialog and the post-conversion banner comes from one copy source and contains the same sentence (the post-conversion banner carries it in its Details, the Upgrade chip in its Details and consent sheet); the Upgrade chip's one consequence line "Upgrade blocks other devices." and its Details come from the same source.
- UX-104 Opening a v1 sketch never converts it; an Upgrade offer starts conversion; the owner can decline per sheet; after that sketch project's acknowledgement later sketches in the same project upgrade from the chip without a modal, and the whole-project sentence is shown in a dialog before the first conversion in each project (the Upgrade chip carries the consequence line, a Details sheet with the sentence and, whenever the wrapped line exceeds the row's 2 lines at the window's width and font scale (computed), a consent sheet instead of a short title that opens at most once per sketch project per day on this device, is dismissed by Esc, back, scrim tap, drag down or Not now, moves and returns focus and leaves the banner slot empty; the first-write dialog that [Start] opens carries it in full; the Details of the post-conversion banner repeat it; for later sketches of an acknowledged project the consequence stays on the chip at every font scale).
- UX-105 The view can be panned by keyboard alone (arrows with nothing selected, Ctrl+arrow always, PageUp and PageDown, Home).
- UX-106 Constraint glyphs never hide a hit-area-sized target (the touch-target floor) behind another: overlapping glyphs collapse into a count badge that opens a list, and a selected element fans out all its glyphs with 4 dp gaps.
- UX-107 A shape can be drawn with the keyboard alone through Crosshair mode, with a fixed, tested tab order and no trap.
- UX-108 Single-key tool shortcuts are off by default on web and under TalkBack, the canvas exposes an application role, and the NVDA and JAWS quick-key collisions are documented.
- UX-109 Tool-group flyouts open without Alt-modified gestures (the separate flyout target, long-press, right-click or barrel button, Down arrow).
- UX-110 Every element has a visible, stable name ("Line 3") on the canvas when selected or referenced and in the Elements list; names are never reused after a delete.
- UX-111 Constraint commands have keyboard shortcuts in the core keymap (K or Ctrl+Shift+K, on the web Ctrl+K, opens the Constrain menu with mnemonics; Delete and Enter or F2 act on a focused constraint).

### Triad repair iteration 3 additions (UX-112..UX-118)
- UX-112 A notice with an action (Undo, Unlock, Switch layer, Retry, Reload, Keep mine, Open) persists until acted on or dismissed (every toast has Dismiss as the last item of its More menu, so pointer and touch users can dismiss it; in a lane of one a newer toast moves the displayed one to the diagnostics list), except a confirmation of an undoable edit which stays at least 10 s; hover and focus pause every countdown; an Info notice with no primary action stays at least 5 s (WCAG 2.2.1, 2.2.2).
- UX-113 The web build reflows to one column at 320 CSS px and 400% zoom without page-level horizontal scrolling (WCAG 1.4.10), survives the text-spacing override without clipping (WCAG 1.4.12), and maps `prefers-reduced-motion` to the same `MotionPolicy` as UX-47.
- UX-114 Export, restore-original, library add and library insert each give success feedback through the notice queue: export names the file and where it went, restore names the kept copy, library add and insert announce counts; each is a toast of the `toast` table with a fixed summary, and the file, the copy and the counts are in its Details.
- UX-115 With one line, one circle, or a line and a circle selected, a suggestion chip offers the natural dimension with the current value prefilled; accepting it opens the label editor, and the bracket-plate path takes at most 34 actions with it (42 through the Constrain menu; one counting convention, section 9).
- UX-116 At most one onboarding item (finger-drawing hints, edge-pan-stopped notice, Upgrade chip, first-run coach mark, single-key chip, empty state) is visible at a time, in the rank order of the banner table of section 1, each following its row of the single recurrence table of section 16 (UX-122).
- UX-117 Keyboard users can skip the canvas elements: a visible-on-focus "Skip canvas elements" link that lands on the next stop of the one tab order of section 10 (so Align, Order, Lock, Transform and the banner and toast actions are reachable without walking elements), F6 and Shift+F6 cycling the regions of that order (top bar, notice region, tool strip, canvas, selection bar, view cluster, side panel, status line), the view cluster (zoom menu, Grid, Snap, Finger toggle, Pan) being one stop of that order with Grid and Snap on chords and overflow entries, and Esc back to the canvas, all listed in the shortcuts sheet.
- UX-118 At compact width Constrain is reachable from the dock (when elements are selected) and the overflow menu, and Point and Arc from a Geometry dock group or the overflow menu, by touch alone.

### Triad repair iteration 4 additions (UX-119..UX-125)
- UX-119 Layout is chosen on width AND height by the rule of section 1 (compact height uses the rail and a side sheet, whatever the width); in every guaranteed window of the chrome table, at font scales 1.0 and 2.0 and with a Blocking banner showing, the canvas is at least the table's minimum with every piece of chrome counted; at most three transient rows exist (banner slot, hint/status row, selection bar) and every banner candidate of the banner table competes for the ONE slot in its rank order; the pinned Undo, Redo and Delete never scroll or clip; tools are flat only when `flatExtent` fits and otherwise grouped as split buttons, at every width class; all other controls scroll with a visible chevron and are also in the overflow menu; rows collapse in the table's order and the compact-height rail keeps the hint/status row and selection bar permanently collapsed; every figure is computed from the chrome table by `check-plan.py`.
- UX-120 Panning the view and resizing a bottom sheet each have a single-pointer non-drag alternative (WCAG 2.5.7): a pan pad of four arrow buttons that pan a quarter of the canvas per tap (one 44 dp "Pan" button until opened; a grid or, where a grid would cover too much of the canvas, a row; no auto-collapse timer; shown by default only while Finger drawing is ON with a touch or pen pointer present; clear of an active edge strip), and a drag handle that is also a 44 dp button with explicit Expand, Collapse and Close buttons (sections 1 and 3).
- UX-121 The edge-pan strip has one geometry: it starts at the system gesture inset, is 44 dp wide (24 dp visible band meeting 3:1 contrast, 20 dp hit-only), covers only the protected span (at most the 200 dp the system grants per edge, drawn and hit-tested only there), and is OFF by default on every target the input spike did not mark Supported, with two-finger pan, the Hand tool and the pan arrows as the safe default (where Supported the default side is Right on Compact width and Both from 600 dp); a reactive cancel-detection backstop persists `ExclusionFailed` so the next session starts Off (section 3).
- UX-122 Onboarding recurrence is defined once, in the table of section 16 (once, twice-or-acted, until-acted, while-empty), as an `OnboardingPolicy` constant asserted row by row; UX-116's single-visible-item and priority rules read from it.
- UX-123 (think-aloud task 2, consent comprehension) After reading the flag-enable confirmation and the Upgrade chip with its Details (at font scale 1.0, and for the consent sheet at font scale 2.0, where the sheet must show the whole-project sentence unclipped), and before any Upgrade, the participant is first shown ONLY the Upgrade chip row (its line "Upgrade blocks other devices.", no Details) and asked "Does upgrading change anything on this device, or on others?" (a correct answer is "on other devices"; "just this device" or "nothing" is wrong: the line is the only consequence text on later sketches of an acknowledged project, so it must carry the direction by itself), and then, after the Details, is asked: "If you upgrade this sketch and then open this project on another device that has not been updated, what happens when you open this project there? Which sketches does it affect?"; a correct answer says the whole project (all of its sketches, not just the upgraded one) won't open; "just this sketch" or "nothing" is wrong. The pass bar is ceil(0.8 n) correct answers (3 of 3, 4 of 4, 4 of 5); a miss is a defect against the `SketchConsentCopy` wording or placement (plan Story 1.4.6) and one re-run, and a second miss is an owner decision recorded in the ADR index (for example a typed confirmation or an always-shown modal), never a silent change.
- UX-124 Hints follow the input device: on touch without a hardware keyboard no hint mentions Shift, Alt, Ctrl, Esc, Enter or Tab (modifiers appear as toggle chips and polylines end with a [Done] chip, both in the action chip row), and the in-place text editor on touch shows a [Done] button and commits on a tap outside (sections 2 and 6).
- UX-125 While a bottom or side sheet is open for styling, the canvas pans (never zooms) so the selected element stays visible above or beside it, and the previous view is restored on close (section 7). Focus reveal (WCAG 2.4.11): when keyboard focus lands on a canvas element, handle or control, the view pans (never zooms) by the least amount that clears it of every overlay rectangle (view cluster, pan pad, chip row, edge strip, toasts, banner, sheet, soft keyboard), and `FocusRevealTest` fails naming any focused node whose centre is covered.

### Triad repair iteration 6 additions (UX-126..UX-128)
- UX-126 Touch-required controls (modifier chips, Line's [Done], the picker's and the solver's [Cancel], the smart-dimension chip, the Crosshair chip) live in the action chip row, a canvas overlay at the touch floor that no row collapse removes and that exists in every window including the compact-height rail; the hint/status row is text only; tool lock is also a "Tool lock" chip while on; every banner is one row of severity icon, summary, one primary button and one More button, with a fixed summary and short title that word-wrap inside the row's two lines at the computed line width (320 dp, font scales 1.0 and 2.0; a consent row has a sheet instead of a short title), primary labels inside the button, no dynamic text in the row, the full copy one tap away in Details (an item of More), a primary accessible name that is the visible label plus its object and is unique, and the live region announcing the FULL summary at every font scale, and the consent row's row-versus-sheet outcome equal to the computed matrix at font scales 1.0, 1.15, 1.3, 1.5 and 2.0 at 320 and 360 dp (`ChromeBudgetTest`); a toast is one row of text, one visible action and a More button within its own computed budget (every confirmation of the toast table, including export, library, copy, restore, classic-editor, locked-items, limit and low-memory), and its actions are reachable by keyboard through the notice region.
- UX-127 Handles never overlap: on a small selection the on-canvas handles are reduced by the computed tiers (eight plus rotation, then four corners plus rotation, then none), and Resize, Rotate and Move stay reachable from the Transform menu (the selection bar's, or in the compact-height rail the top-bar Selection menu's), the Stats fields and Crosshair mode.
- UX-128 Below the smallest guaranteed window (down to 320 x 256 CSS px, a web page at 400% zoom) the editor degrades by the same rules without clipping or page-level horizontal scroll and with every control reachable; toasts stack in a lane of at most the table's share of the canvas (at least one), never take focus on arrival, and move to the diagnostics list when focus goes to a control they cover; the top bar uses its short forms first and then moves the Selection menu into Overflow, and a Blocking banner never collapses even there.

### Triad repair iteration 14 additions (UX-129..UX-133)
- UX-129 While the effective mode is Off and neither the build nor the platform is capped, the classic sketch menu lists "Try the new editor"; it opens Settings > Sketches > New editor with focus on the mode group, stores nothing and shows no dialog, banner, toast or counter; the item is present in modes: Off only, and in `NewProjectsOnly` and `On` the Upgrade entry and the toast routes replace it (D-97, D-110).
- UX-130 While `ConversionPolicy.refusal` is non-null no Upgrade chip and no consent is offered and the sketch-menu entry "Upgrade to the new editor" is shown disabled with the technical-data reason; a refusal that appears before [Start] withdraws the chip, converts nothing and opens no failure dialog (D-98).
- UX-131 Where Settings cannot change the outcome the classic-sketch toast is the row `ClassicEditorFixed` with the primary [Learn more], never [Change]; when several reasons apply, ONE is named, the first applicable row of the reason table in its precedence order (`Platform`, `BuildCapped`, `TechnicalData`, `ModeOff`, the three `Npo` rows, `UserChose`), and the toast variant follows that row (D-101).
- UX-132 On the newer-schema screen [Export last backup] is hidden, not disabled, when the project has no `backup/*.v1.json`, and where it exists it saves the newest one through the file-save seam without writing to the project (D-103).
- UX-133 On a platform that cannot store new-editor sketches (iOS, a browser without OPFS atomic replace, a demo graph or no open graph) the mode control is disabled with the stated reason, no new-editor sketch is created or imported, a new-editor sheet that reaches it opens as the no-editor screen with that reason, and classic sketches keep persisting in the database as today (D-104, D-116, D-140).

### Triad repair iteration 15 additions (UX-134..UX-138)
- UX-134 The no-editor screen of the storage-unavailable state offers [Copy details] and [How to open it] beside [Close] and says that nothing can be exported from it; the newer-schema variant gains [Copy details]; no export is claimed on that path (D-117).
- UX-135 Where `V2StorageAvailable` is false the import item, a drop and a paste show the reason and create nothing, a v2 embed is the labelled placeholder with [Open page] and a v2 copy source is refused with the reason; in a browser whose verdict is Available all of them work as on desktop (D-118).
- UX-136 Routing is evaluated top to bottom and the storage-unavailable row is first: a v2 sheet there is never a read-only banner, and the Off radio's read-only clause and the `ReadOnly` Details are not shown there (D-117).
- UX-137 The disabled-option reason of the mode control and of every other new-editor item of Settings > Sketches follows one precedence, `Platform` before `BuildCapped`; where storage is unavailable Restore original, the backup size, Delete backups and the new-editor settings are shown disabled with the same reason, never hidden (D-117).
- UX-138 The no-editor screen puts focus on its level-1 heading on arrival, is a region named by that heading and not a dialog, has the tab order of its table, closes on Esc and back and returns focus to the opener (WCAG 2.4.3, 4.1.2, 1.3.1, 4.1.3; D-117).

### Triad repair iteration 16 additions (UX-139..UX-143)
- UX-139 On the web build the first-write dialog of both variants carries the durability line of 14.2 (the browser can clear its private storage, back it up with git sync or a linked folder), and in a graph opened temporarily it carries the session-only line instead; the mode control of a temporary graph states the same in text and in its accessible description (D-128).
- UX-140 On the web build the save status stays "Saving..." until OPFS has committed the write and reads "Saved" only then, a write not yet committed at 3 s starts the `StillSaving` state and is announced once, at 4 s, as "Still saving to this browser" (whether or not its toast could be shown, D-152), a temporary graph's "Saved" reads "Saved for this session only", and a failed OPFS write raises the existing Save failed banner without discarding the work (D-119).
- UX-141 While the web storage status is `Checking` the mode control disables the two On options with the checking text, a v2 embed is the loading placeholder, opening a v2 sheet shows the `Pending` state instead of the no-editor screen, a create action waits for the status, and after 10 s the status is `Unavailable` with its own reason; each `Unavailable` reason has its own text in the table "Web storage reasons" (D-124, D-128).
- UX-142 On a persistent web graph a second tab is refused at boot by the boot-refusal screen (UX-152) and reloads by itself when the first tab closes; the `LeaseConflict:OtherTab` banner variant (read-only, no primary action, and a takeover after reloading the saved sketches when the first tab closes, D-123) is the defence-in-depth state of the sidecar lease, reachable only where the driver lock is not taken (D-149).
- UX-143 `navigator.storage.persist()` is requested only in the click handler of the first-write dialog, never at startup; a denied answer creates no toast and no banner and shows as "Not guaranteed" in Settings > Sketches > Browser storage with [Ask the browser to keep it] (D-125).

### Triad repair iteration 17 additions (UX-144..UX-148)
- UX-144 When the browser store is full the banner slot shows `SaveFailed:Quota` ("Storage full. Keep tab open.") with [Save copy] as its primary action, Retry under More and the free-space steps in its Details, and the scene is never rolled back (D-134).
- UX-145 A write that has not reached `Persisted` after 3 s shows the `StillSaving` toast as a visible polite status message (the chip's tooltip reads the same but the chip is not a live region), and the leave-page prompt is stated to be generic and possibly absent on mobile (D-134).
- UX-146 The Pending screen focuses its level-1 heading on arrival, adds a polite message at 5 s, moves focus to the editor canvas or the no-editor heading when it resolves and shows the `CheckTimedOut` no-editor screen with [Reload] at 10 s (D-135).
- UX-147 A refused or pending [Ask the browser to keep it] shows an inline status line on its row, and the transient no-editor screens (`PreloadFailed`, `CheckTimedOut`) offer [Reload] (D-135).
- UX-148 In a window suspected to be private the first-write dialog and the "Saved" status say the sketches are kept only in this window, and the heuristic is stated to be INFERRED (D-136).

### Triad repair iteration 18 additions (UX-149..UX-151)
- UX-149 Where the sidecar lease is held elsewhere (defence in depth: a persistent web graph is refused at boot first, UX-152), the sheet opens read-only under the banner variant `LeaseConflict:OtherTab` ("Edited in another tab.", no primary action, Details and Close under More), never under "Open in the other editor.", so the cause is in the summary and the width, wrap and name checks cover the form without a primary (D-144, D-149).
- UX-150 The `StillSaving` toast is exempt from the 5 s minimum but visible at least 1 s, never displaces another toast, announces once through its own polite message at 4 s from the start of the write (3 s plus 1 s, whether or not the toast could be shown) unless the write has persisted, is removed together with its list entry at `Persisted`, and the status chip is not a live region (D-144).
- UX-151 A graph opened temporarily gets the session-only wording even in a window that looks private (one winner per state, the `durability-wording` table), a suspected private window shows a persistent cue in the hint row and a temporary graph its own persistent cue (D-155), and the web `ExportSavedWeb` toast has no [Open] (D-144).

### Triad repair iteration 19 additions (UX-152..UX-153)
- UX-152 A second tab on a persistent web graph is refused at boot by a screen with a level-1 heading, an alert message as its one announcement, [Reload] as the first tab stop with a visible focus ring and [Open temporarily] as the second, both at least 44 CSS px, rem sizing, the graph name never shown, reflow at 320 CSS px and at 400% zoom, text contrast of at least 4.5:1, a button and focus ring of at least 3:1 and an automatic reload when the first tab closes, or a message without that promise where the browser cannot wait on the lock (14.9, D-149, D-156).
- UX-153 In a window suspected to be private, or a temporary graph, the persistent cue has four forms (full, mid, compact, chip), shares the one hint line by the rule of the `hint-cue` block (the hint text yields first), and is the first, non-dismissible chip of the action chip row in every window without a `HintStatus` row, in the longest form that fits that window's chip row (14.8, D-151, D-155).

Count (recounted by script): the ids UX-24..29 and UX-49 are absent from section 20 by design (ids are never renumbered or reused, so 153 minus these 7 gaps is the 146 counted criteria); UX-01..09 (9), 10..19 (10), 20..23 (4), 30..39 (10), 40..48 (9), 50..59 (10), 60..63 (4), 64..69 (6), 70..75 (6) = 68, plus UX-76 to UX-97 (22) = 90, plus UX-98 to UX-111 (14) = 104, plus UX-112 to UX-118 (7) = 111, plus UX-119 to UX-125 (7) = 118, plus UX-126 to UX-128 (3) = 121, plus UX-129 to UX-133 (5) = 126, plus UX-134 to UX-138 (5) = 131, plus UX-139 to UX-143 (5) = 136, plus UX-144 to UX-148 (5) = 141, plus UX-149 to UX-151 (3) = 144, plus UX-152 to UX-153 (2) = **146**.

---

## 21. Unresolved flows and decisions

- U1 (OD-1) Whether to accept that an old build cannot open any sheet of a project containing a v2 sheet. Section 14.2 wording assumes yes. If the owner declines, ADR-001 needs a v1-compatible projection and 14.2 changes. Decided 2026-11-16 (yes); amended 2026-11-17: no earlier reader-only release, so 14.2 is the only warning an un-updated device gets.
- U2 RESOLVED in plan: Story 1.4.3 router matrix now has `ConversionState` (`Declined`, `Failed(reason)`) -> legacy editable, so "Keep this sketch classic" (14.2) and "Open in legacy editor" (14.4) are backed. `HasTechnicalData` is a `Failed` reason until Story 4.2.5: conversion (and creating a new v2 sheet) is refused when the sheet has zones or the project has any dimension (Story 1.1.4 predicate); its text lives in `SketchReasonText`.
- U3 (OD-6) Placeholders for unsupported imported elements: recommended grey placeholders plus count; section 11 assumes yes.
- U4 In-app restore of the v1 backup: the plan says documented manual procedure and cites a "Story 6.2.5" that does not exist in plan.md (Epic 6.2 ends at 6.2.4). Section 14.6 therefore links to docs only. RESOLVED in plan: Story 6.2.6 (`SketchBackupRestore`, settings action "Restore original (v1) sheet"); 14.6 may link the in-app action.
- U5 "Go to element..." search for screen-reader users when more than 200 elements exist is proposed in section 15 but has no plan story; RESOLVED in plan: Story 3.6.5 "Go to element" (searchable, paged list; selects and focuses the element).
- U6 Locked target for arrow binding (section 5): permit vs refuse. A pre-W1 owner-session item (plan Unresolved Questions); the default PERMIT is applied if unanswered, and binding never moves the locked target.
- U10 (triad repair) Owner-confirmable defaults applied in this document: Finger drawing default and flip notice (section 3, UX-80, UX-102), constrainable kinds and auto-constrain defaults (section 9, UX-81/82), flag-enable copy and per-device acknowledgement (section 14.1, UX-76, UX-103), single-key shortcuts (section 17, UX-85, UX-108), "Take a tour" dropped (section 3), tool shortcuts and keyboard panning (section 17, D-8), stylus mapping (D-10), flyout gesture (D-11), Upgrade flow (14.2, D-12), element names (section 8, D-13). Iteration 6 defaults: banner content fit and the Upgrade chip consequence line (D-41), the action chip row (D-42), the pinned top-bar Finger chip and the fixed-width rail (D-43), pan pad variants, toast lane, degraded layout and handle tiers (D-44), selectability, Alt and web chord rules (D-45). See plan D-1..D-157 (the plan list is the single source).
- U7 Warning for builds already shipped (pre-v2) cannot be changed; 14.2 confirmation and release notes are the only mitigation (14.5). Owner should accept this as a stated limitation.
- U8 Several Excalidraw/FreeCAD/Shapr3D behaviours cited in research/ux.md are INFERRED and not re-verified here; shortcut map and tool-lock behaviour should be checked against the live Excalidraw docs before the keyboard map is frozen (plan Task 3.6.1c freezes it, and is GATED on U8, U11 and U12: it freezes the map only after they are recorded or the owner says "use defaults", in which case the shortcuts sheet footnote says so).
- U9 Device-only behaviours (stylus pressure, hover, palm rejection, TalkBack on Pixel_Fold) cannot be verified in CI; UX-07, UX-33, UX-44, UX-45 need the owner checklist in `implementation/owner-verification.md`.
- U11 (iteration 2) The NVDA/JAWS quick-key list in section 17 is INFERRED from screen-reader documentation and UNVERIFIED against the owner's reader; check it before the shortcuts sheet text is frozen.
- U12 (iteration 2) Stylus eraser-end and barrel-button availability per target depends on Spike 0.1.4; where a target exposes neither the mapping is simply absent (sections 3 and 5).
- U14 (iteration 3) The touch-target floor (D-17) and the edge-pan strip with its fallbacks (D-18) are owner-confirmable; the Android exclusion cap, iOS edge deferral and palm behaviour are measured or marked UNVERIFIED by plan Task 0.1.4d, never assumed.
- U15 (iteration 3) The smart-dimension references (FreeCAD, Shapr3D) are INFERRED; the 34-action and 42-action bounds are an INFERRED recount that `BracketPlateUxScenarioTest` asserts.
- U13 (iteration 2) The consent wording lives in one copy source (UX-103); the owner reviews the text once, in the batched session (D-3, D-12).

---

## 22. Accessibility, help and discoverability additions (triad repair)

- **First-run walkthrough (U3; UX-97; plan Task 3.6.8e), the ONE specification.** Started by [Show me] on the `CoachMark` banner row, or at any time by the overflow entry "Show tips"; starting it either way retires a visible `CoachMark` row (section 16 row 13). Exactly three steps, each a non-modal popover anchored to its target with a highlight ring, a title, one or two sentences, the indicator "Step n of 3" and the controls [Back] (disabled on step 1) and [Next] (replaced by [Done] on step 3). **The steps DESCRIBE and never instruct an action on the pointed-at control**, because activating a pointed-at control performs its action and ends the walkthrough (retiring the coach mark, section 16 row 13): only activating the anchored control itself ends it, while canvas gestures, panning and zooming do not. **Wording variant, chosen when a step is SHOWN (on start, [Next] and [Back]) and fixed while it stays on screen**, so the text never changes under a screen reader: touch or pen with no hardware keyboard (no key name, the rule of UX-124), mouse or trackpad, or keyboard (a key press switches to it, a pointer move away from it). **No variant names a key that needs canvas focus** (Home, the tool letters, Tab to cycle elements), because focus is in the popover, not on the canvas; keyboard text uses only Shift+Tab, which is always valid from the popover, and the arrow keys of the target once focus is there. Step 1 also follows the tool strip's computed state at show time (section 1, the one flat versus grouped rule), so it never mentions a control that is not on screen. Steps and wordings:
  - (1) **Tools**, anchored to the tool strip (the Dock never collapses, section 1, so the anchor is always the strip). Grouped (chevrons exist): touch "Tools are here. A chevron, or a long press on a group, shows more tools."; mouse "Tools are here. A chevron, or a right-click on a group, shows more tools."; keyboard "Tools are here. Shift+Tab goes back to the tool strip, where the arrow keys move between tools and Down opens a group.". Flat (no chevrons: whenever `flatExtent` fits the strip extent, the one rule and the computed windows of section 1): touch and mouse "All the tools are here, one button each."; keyboard "All the tools are here, one button each. Shift+Tab goes back to the tool strip, where the arrow keys move between tools.".
  - (2) **Zoom**, anchored to the view cluster, named by its target and never by a screen position (WCAG 1.3.3, and it mirrors in right-to-left); the button alternative comes first so a TalkBack user is not told a gesture first: touch "The view buttons fit the drawing and zoom. You can also pinch to zoom."; mouse "The view buttons fit the drawing and zoom. You can also scroll to zoom."; keyboard "The view buttons fit the drawing and zoom. Shift+Tab goes back to them."
  - (3) **Shortcuts**, anchored to the overflow BUTTON (the walkthrough never opens the menu, so with the menu closed the popover points at the button and the text names the entry), all variants: "Shortcuts are listed in the menu, under Keyboard shortcuts."
  
  **Focus and announcement order (non-interrupting).** On start focus moves ONCE to the popover container, a non-modal region whose accessible NAME is exactly the string a later step announces ("Step 1 of 3. Tools. <step-1 sentence for the variant and strip state>", for example, grouped and touch, "Step 1 of 3. Tools. Tools are here. A chevron, or a long press on a group, shows more tools."), with no separate accessible description, so a screen reader reads it once and no separate live announcement is sent for step 1; on every later step focus moves to the primary button ([Next] or [Done]) so Enter advances, and exactly ONE polite string is then announced after the focus change, never queued ahead of what the reader is already speaking: "Step 2 of 3. Zoom. <step-2 sentence>" and "Step 3 of 3. Shortcuts. Shortcuts are listed in the menu, under Keyboard shortcuts.". The walkthrough sends no other live-region string; assertive Blocking notices (section 16) are independent of it and interrupt as they always do. **Keyboard contract (one rule, consistent with sections 10 and 17: Tab never traps).** The popover is a labelled non-modal region in the tab order directly AFTER its anchored region (section 10's list gains it only while the walkthrough is shown: step 1 after the tool strip, step 2 after the view cluster, step 3 after the Overflow button, the last stop of the top bar). Tab and Shift+Tab move through [Back] and [Next] or [Done] and then ON to the next or previous stop of that order WITHOUT wrapping. F6 and Shift+F6 behave as everywhere (section 17): the popover is not itself an F6 stop, so F6 goes to the region that follows its anchored region in the F6 list (step 1: canvas; step 2: side panel; step 3: the notice region while it has an occupant, otherwise the next present region) and Shift+F6 goes to the anchored region itself, and the tour CONTINUES (the popover stays shown at its step until [Back], [Next], [Done], Esc or activating the anchored control). Esc ends the walkthrough from the popover and from anywhere else while it is shown, at row 8 of the ONE Esc order of section 17 (an open dialog, menu or flyout, a text overlay, "Solving...", an armed picker, Crosshair mode or a mid-draw preview takes its own Esc first, and the next Esc ends the tour; the keyboard variant shows "Esc to finish" beside the indicator, so the way out is stated, WCAG 2.1.2). On end focus returns to the control that held it before the walkthrough started (after overflow > Show tips that is the overflow button, because the menu closed when the entry was activated) and, if that control no longer exists (the coach mark row retired by starting), to the canvas region. **Placement (WCAG 2.4.11).** The popover sits on the side of its target with the most free space (above, below or at the logical start or end of it, mirrored in right-to-left), never covers its own anchor, the control that holds focus or the primary button it focuses, and if it would, it moves to the opposite side; at font scales 2.0 and 3.0 it scrolls and is not clipped. **Highlight ring (WCAG 1.4.11).** A two-tone ring (2 dp in the focus-ring token with a 1 dp contrasting outer halo) that holds at least 3:1 against the target's surround and the canvas in the light, dark and high-contrast token sets; it is never the only cue, the step text names the target. Reduced motion: no animation. The walkthrough changes nothing in the sketch: the targets stay operable. `CoachWalkthroughTest` asserts the controls, the wordings (none contains a screen position, a key that needs canvas focus or an imperative to activate the target; the step-1 text for the flat and grouped strip in all three variants, with no "chevron" in the flat text), the variant staying fixed while a step is shown and changing at the next step, the focus moves, the one-string-per-step announcement for all three steps, Tab and Shift+Tab moving through the popover and on without wrapping, F6 from the popover leaving to the next region with the tour still shown, Home from the popover not moving the view, Esc from outside the popover ending the tour at row 8 of the Esc order (each row above it taking its own Esc first), focus return (including a retired origin and the overflow button after Show tips), the placement rule at 320 dp and at font scale 2.0, and the ring contrast in the three token sets.
- **Focus not obscured (UX-87)**: at compact width with the bottom sheet or soft keyboard open, the focused control or element scrolls/insets into view; the audit test names any node that is fully covered.
- **Text scale and reflow (UX-88)**: at system font scale 200% and 360 dp wide, text wraps instead of clipping, panels scroll, the dock scrolls with an edge fade, and no horizontal page scroll appears.
- **Themes (UX-89)**: dark and high-contrast token sets are audited like the light set; the default stroke colour adapts so it never vanishes on the canvas; exports keep the colours chosen in the export dialog.
- **Touch help (UX-90)**: long-press (touch), the panel "i" affordance, or focus (keyboard) shows the same text a hover tooltip shows, as a dismissible, persistent popup also present in semantics; disabled-with-reason items expose their reason this way.
- **Notice queue (UX-91)**: see section 16.
- **Touch targets (UX-92, D-17)**: ONE floor for every interactive hit area, 44 dp whenever a touch or pen pointer is present and 24 dp only mouse-only (section 2).  "Larger touch handles" option (default on for touch-primary devices) gives 44-48 dp hit areas around 8 dp visible handles; Delete is 16 dp or a divider away from Redo; Delete is undoable and announces "Deleted: N elements".
- **Fold/unfold (UX-93)**: compact/expanded switches keep selection, tool, lock, open panel, view centre and in-progress text.
- **Loading states (UX-94)**: operations over 200 ms show a labelled progress indicator (Cancel where possible); thumbnails show a neutral placeholder.
- **Strings (UX-95)**: all user-visible text is in string resources; an audit test enforces it.
- **Web equivalents (UX-113; D-28)**: at 320 CSS px or 400% zoom the layout is one column (panels become full-width sheets, the canvas pans inside itself, no page-level horizontal scroll); under the text-spacing override (line height 1.5, paragraph spacing 2, letter spacing 0.12 em, word spacing 0.16 em) nothing clips and every control stays reachable; `prefers-reduced-motion: reduce` is read by the web actual of `MotionPolicy`.
- **Success feedback (UX-114)**: export, restore-original, library add and library insert each end with a notice naming what happened and where (section 12, 13, 14.6), through the one notice queue.
- **Usability validation (UX-96)**: think-aloud protocol in plan Story 6.2.8 with 3-5 users (six tasks, including the dimensioning task, and the decision rule for assumption A5); results in `implementation/usability-results.md`.
