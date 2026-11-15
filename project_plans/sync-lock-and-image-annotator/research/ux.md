# UX research: sync-lock-and-image-annotator

Accessed 2026-11-14. Labels: VERIFIED = code/screenshot/web page read this pass; INFERRED = reasoning or general product knowledge not re-checked on a source; UNVERIFIED = needs owner/device check.
Recommendations are inputs to design, not final designs.

## 0. Verified observations from screenshots and code

- Annotator screenshot (10852.png): the toast "Depth model ready ..." is not a toast. It is a status Row rendered inside the depth panel (`AnnotationEditorScreen.kt:1687`, `labelSmall`, white text) that sits over the image's top edge. Root cause of the overlap is placement (an overlay that does not reserve layout space), not duration. VERIFIED.
- The +/-/Fit buttons float over the image's right edge (screenshot), and "Fit" is disabled-looking at fit. They cover the outermost pixels, which compounds the "can't place a point at the edge" problem. VERIFIED (screenshot).
- Toolbar: the active "Select" label is dark teal on the dark toolbar (screenshot, label nearly invisible); inactive labels are hard-coded `Color(0xFFBBBBBB)`, disabled uses `DISABLED_TINT_COLOR` (`AnnotationToolbar.kt` ~L275). Active-label contrast is a likely WCAG 1.4.3 failure (INFERRED from the screenshot; measure the ratio before claiming).
- Tool buttons are `IconButton` (48dp default) with `contentDescription = label`, but no `selected`/`Role.RadioButton`/`toggleable` semantics were found by grep (VERIFIED absence by grep of `selected|Role|semantics` in `AnnotationToolbar.kt`: no hits). TalkBack therefore announces "Distance, button" with no active state. Reference is `enabled = isCalibrated`, so it is silently dead when "Not calibrated"; no reason is exposed.
- Zoom controls: `FilledTonalIconButton` (48dp default), `Text("+")` with `contentDescription` set on the Modifier. Zoom-out is `enabled = !current.isFit`, which encodes the "cannot zoom out past fit" limit in UI. VERIFIED (`AnnotationZoomControls.kt`).
- Calibration entry points today: banner "Not calibrated" (`CalibrationNudgeBanner.kt`), toolbar "Calibrate" button, `CalibrationSheet.kt` with: Use scale from <peer>, draw reference, BLE device, "Mark ruler ticks (most accurate)", "Use paper or a card (corrects tilt)", "How to calibrate". So multi-point ticks already exist. VERIFIED.
- `annotation-lens-distortion/design.md` already specifies multi-tick least squares with a one-parameter division model, residual percent, and refusal to accept k unless 3+ ticks and a 60% residual reduction. The UX job is to expose this (several known distances in several directions) rather than invent a model. VERIFIED.
- Sync UI: the sidebar shows red "Stale git lock file - tap to retry" with a refresh icon (screenshot 10849). In `SyncStatusBadge.kt` the Error row is clickable and its icon is 16dp with a contentDescription "Sync error - tap to retry"; `SyncStatusSheet.kt` has "Sync now" with `enabled = model.canSyncNow` and a `syncNowDisabledReason` text line. So a disabled-with-reason pattern already exists in the sheet. `RateLimited` is already modeled as a non-clickable "Retrying..." state ("never phrased as tap to retry"), which is the template for "Sync already running". VERIFIED.

## 1. Comparable patterns and recommendations

### 1.1 Tool mode: one-shot vs sticky
Convention (INFERRED, general product knowledge; not re-fetched):
- Drawing tools in Figma/Procreate-style editors are sticky by default in desktop tools, but on phones markup tools (Google Photos / iOS Markup) keep the pen sticky because the primary task is repeated strokes. Measurement apps (Apple Measure) are inherently "point-and-add" and return to live aim after each measurement, and the result is then tapped to open details/copy. Figma returns to Move after creating a shape by default and offers double-click / Shift-lock or key hold to keep the tool; Procreate-style tools stay selected.
- Fit for this app: one measurement = 2 points, a drag. Users measure many things in a photo, but pan/zoom is needed between each. So one-shot default is correct for the owner's stated flow.

Recommendations:
1. Default one-shot: after a completed drag (pointer up), commit, return to Select, select the new measurement (so its value is editable immediately, see 1.4). Show a brief, non-blocking cue (the toolbar Select chip animates; a 1-line hint "Tap Distance again to measure more" only the first 3 times, stored in Settings).
2. Lock gesture = double-tap (or long-press) a tool chip to lock it; a locked tool shows a lock badge/ring and a "Done" affordance is simply tapping Select. Discoverable alternative required, because double-tap is invisible and WCAG 2.5.1/2.5.7-style single-pointer alternatives matter: add a "Keep tool active" toggle in the overflow or a small lock icon appearing on the active chip while armed (tap to lock). Settings: "Return to Select after each measurement" (default on) and lock opt-in, matching the requirements' Risk Control.
3. Failure case: tapping a tool then abandoning (no drag) should not strand the user. Tapping the active tool again, tapping Select, Back, or Escape/two-finger gesture all disarm it. Armed state is visible in the toolbar and in a 1-line status ("Distance: drag from point A to B").
4. Cancel a drag mid-way: dragging off the canvas or pressing Back cancels without creating a measurement, and returns to Select (not silently stays armed) so the model stays predictable.

### 1.2 Pan/zoom while a tool is armed
Pattern (INFERRED): in touch editors a two-finger gesture always navigates (pan/pinch) regardless of the active tool, while one finger acts on the tool. Maps and Procreate do this; it removes the need to return to Select to pan at all.
Recommendations:
1. Two-finger pinch/pan always works in every mode (verify the gesture layer; `AnnotationGestureLayer` routing is a named rabbit hole). One finger is tool-owned; in Select one-finger pan stays.
2. Keep +/-/Fit buttons for TalkBack/desktop (already justified in `AnnotationZoomControls.kt` KDoc) but move them out of the canvas image area (see 1.3).
3. Precision placement: while the finger is down a loupe (`LoupeGeometry.kt` exists) offsets above the finger. Verify the loupe still clears the viewport edge when the point is near an edge (UNVERIFIED).

### 1.3 Zoom out beyond fit / pasteboard
Pattern (INFERRED): design/photo editors (Figma, Photoshop, Lightroom, Google Photos crop) allow zoom-out below fit and show a neutral pasteboard around the artwork; the artwork never snaps back unless "Fit" is tapped. Edge points are then placed in empty margin area with the point clamped/unclamped per tool.
Recommendations:
1. Allow scale down to ~25-30% of fit (so image is roughly a small fraction of the viewport, matching the success metric), and clamp pan so the image cannot leave the viewport entirely (always keep >= 20-25% of the image visible).
2. Pasteboard: a visibly different neutral surface (checker or darker surface), plus a thin image-bounds frame so the user can see where the photo ends. Points may be placed outside the image only if the model supports it; default: clamp placement to the image bounds, but allow the finger to be in the margin (so the edge pixel is reachable by dragging toward the margin). INFERRED design choice; confirm with the sidecar schema (normalized points outside [0,1] would be a schema question for architecture).
3. Zoom-out button must stay enabled below fit until the min scale; disable only at the min. Fit button enabled whenever not at fit (today it also is). Double-tap to toggle fit/100% is a common alternative; add as a bonus, not a requirement.
4. Reserve layout: put zoom controls in a dedicated column at the canvas edge, or in the pasteboard margin, not over the image.

### 1.4 Inline editing of a measurement value
Pattern (INFERRED): Apple Measure: tap the result chip to open a detail card (copy/ save). Moasure-style and CAD-on-mobile tools: tap the dimension label to edit; a numeric keypad appears with unit; edited dimension drives scale (when it is a calibration) or just overrides text (when it is a note). Figma: double-click value to edit.
Recommendations:
1. Tap the measurement's label (it is already a rendered chip, e.g. "442 px") to select it and open an inline editor (bottom sheet or anchored popup) with the value, unit chip, and a clear choice of what the edit means. This is the key mental-model decision; surface it explicitly:
   - "This line is actually 90 cm" -> "Use as known distance" (adds a calibration reference; the whole photo's scale updates, other measurements change). Primary action when the photo is not calibrated.
   - "Just relabel" -> overrides display text only (a note); never silently rewrites geometry or scale.
   Default: if "Not calibrated", the edit is a known distance; if calibrated, ask once ("Use as another known distance, or replace the label?") and remember the choice for the session.
2. Show the effect before commit: "Other measurements will change (3)" with Undo in the snackbar. Undo/redo must cover calibration edits (existing `UndoHistory`).
3. Numeric field accepts the display unit (feet-inches input: `5'3"`, `5 ft 3 in`, decimals); validation inline, not toast: empty/zero/negative disabled with helper text.
4. The 48dp tap target problem: labels on small lines are tiny. Make the hit area for a label at least 48dp (invisible padding) and also allow tapping the line itself. TalkBack: each measurement is an accessibility node (`AnnotationSemanticNode.kt` exists) with action "Edit value".

### 1.5 Multi-point calibration a person can complete on a phone
Observations (INFERRED, plus verified design in `annotation-lens-distortion/design.md`): people realistically supply 1-3 known distances (door height/width, a tape measure, a sheet of paper or card), not 6+. Multiple distances in different directions cost real effort: each needs a known length, a placement drag, a number entry.
Recommendations (the "Not calibrated" state and entry, owner has several known distances):
1. Replace the passive banner with a state that has a next action and a progress meaning: "Not calibrated - add a known distance" (tap goes straight to the quickest path), not a bare label with a dash icon. Once there is 1 reference: "Scale set from 1 distance (approx.)" with action "Add another to improve accuracy". With 2+ in different directions: "Calibrated from 3 distances, +/-1.2%" (uses `residualPercent`) with the confidence badge (`CalibrationConfidenceBadge.kt` exists). Colors plus text, never color alone.
2. Entry: make the banner a single tap target (>= 48dp) that opens a "Known distances" list (a sheet), not one-shot flows. Each row: a mini label ("Door width"), value + unit, direction icon (horizontal / vertical / diagonal), per-row residual ("0.8% off"), delete. Footer button "Add known distance" which arms Distance, then after the drag asks for the value (reuse 1.4 inline editor) and returns here. Cap is not needed in UI; residual warnings guide.
3. Guidance that avoids the sheet's current long menu (peer calibration, draw, BLE, ruler ticks, paper) being a wall of options: surface two primary choices on top ("Draw a known distance", "Use a card or sheet of paper") and put the rest under "More ways". Suggest an adaptive nudge after the 2nd distance: "Add one in a different direction (e.g., vertical) to correct stretching" (INFERRED value; confirm the modeling in the research/calibration lane).
4. Do not say "lens distortion" to the user as the headline. Prior art (`measurement-accuracy-and-camera-profiles`) says distortion is the smallest error term; frame as "Improve accuracy" with the residual shown, and show the real dominant causes (tilt, depth) in `MeasurementAdviceStrip` guidance rather than promising a distortion fix. INFERRED UX choice from the verified prior art.
5. Inconsistent distances: if residuals exceed a threshold, flag the worst row ("This one disagrees by 6% - re-measure or remove?") rather than refusing; allow "Use anyway". If only collinear, say "All distances are in the same direction; add another direction to check stretching" (informational, not blocking).
6. Impossible: value <= 0, two points identical (zero pixel length), duplicate same-line entries; show inline errors at the field, keep the user's input.
7. Verification step (cheap, trust-building): after calibration offer "Check: measure something you know" is not necessary; the residual number serves.

### 1.6 Camera lens-switch UI
Verified (web, Google/press sources via search 2026-11-14): Pixel cameras use a zoom pill with preset stops (0.5x/0.6x, 1x, 2x, 5x), automatically select the lens as zoom changes, and offer "Manual Lens Selection" in settings; Pixel 10 volume buttons step through preset zoom stops. Sources: [Android Authority on Pixel 10 volume zoom stops](https://www.androidauthority.com/pixel-10-camera-volume-buttons-zoom-3592363/), [GSMArena Pixel 7 review](https://www.gsmarena.com/pixel_7_and_7_pro-review-2492p4.php), [Google blog, Pixel 7 camera](https://blog.google/products-and-platforms/devices/pixel/pixel-7-camera/). I found no source for a "long-press zoom dial" interaction design (UNVERIFIED; the Samsung/Pixel zoom dial is general product knowledge, INFERRED).
Recommendations:
1. A row of chips above the shutter, built from the lenses the device actually exposes, labeled by zoom ratio relative to the main lens (0.6x / 1x / 2x / 5x) with focal-length (e.g. "13mm eq.") as the secondary line or content description. Selected chip is filled and also announced as selected. 48dp min target even though chips look small.
2. Measurement-first nuance: for this app the lens matters for accuracy (wide angle = more distortion), so default to the main (1x) lens and show a small hint when the ultra-wide is selected ("Wide angle distorts edges; fine for tight spaces"). Record lens id and focal length in the saved photo metadata (requirement 4).
3. Behavior of digital vs optical: if only logical-camera zoom exists (OEM hides physical lenses), show a zoom slider/stops, but label only real optical stops as lenses. Do not label a digital crop "2x lens". Show "Digital" in the chip hint. INFERRED; depends on the Camera2 capability research lane.
4. Lens unavailable/failed to bind: keep the previous lens running, show an inline one-line message ("Ultra-wide isn't available on this device right now") and disable that chip with reason; never leave a black preview. Switch should show the previous frame (crossfade/blur) rather than a black flash. Target switch time ~1 s is from the requirements (UNVERIFIED).
5. Flash: single cycling button (Off / Auto / On) with state text, and a separate torch toggle only if needed. Pinch-to-zoom on the preview synchronizes with the chips (chip highlights nearest stop). Tap-to-focus ring. Long-press to lock AE/AF is an optional extra.
6. Permission denied: a clear screen state with an explanation of why the camera is needed and two actions: "Try again" (if not permanently denied) and "Open app settings" (if "don't ask again"); plus "Pick from gallery instead" as the escape route. There is already `PermissionRationaleDialog.kt` to reuse.
7. Orientation/foldable: the owner's AVD is Pixel_Fold; chips/rail must reflow in landscape (rail at the side) and not sit under system bars (INFERRED).

### 1.7 Sync button when a sync is already running
Patterns (INFERRED, general): Files/Drive/Dropbox/Obsidian Sync show a spinning icon and "Syncing..." and ignore or queue re-presses; pull-to-refresh while refreshing is a no-op; email clients coalesce. Nobody cancels a running sync on a second tap; cancel is a separate explicit control.
Recommendations:
1. While a sync is running the Sync button is disabled-with-progress: spinner plus text "Syncing...", `enabled = false` is not enough alone: expose the reason ("A sync is already running") through `syncNowDisabledReason` and a `stateDescription` for TalkBack. A press during run: no-op that gives feedback (the label briefly pulses/announces "Already syncing"), never a cancel and never a restart.
2. "Join" semantics: if a trigger (manual press) arrives mid-run, show "Syncing..." as is and, on completion, the usual result. Optionally mark "Queued: one more sync after this one" only when the press arrived after the run had already passed the fetch phase (INFERRED value; avoid in v1, simply coalesce). Log "joined running sync" (requirements' observability).
3. A sync's cancel (if any) is a separate control with a confirm-free but explicit label ("Stop"), and must be safe (lock handling is another lane).
4. The sync trigger sources (scheduled/worker) are invisible to the user; the UI should show the same "Syncing..." for all sources and, if a scheduled run was running when the user pressed, the same message.

## 2. Mental models and expectations

- "Tool" vs "mode": the owner calls them modes and expects "tap a tool, drag, done, back to normal." The mental model is a verb that applies once (like Apple Measure), with "Select/hand" as the resting state. Evidence: owner request in requirements (VERIFIED).
- "Select" currently looks like a mode to enter (the hand icon), not a resting state. Recommendation: rename/iconify as "Move / Select" with the hand-and-cursor icon, and consider making it not a toolbar peer but the implicit default (the other tools are "add" actions) while keeping a visible highlighted state for clarity. INFERRED.
- Calibration: users think "tell it how long something is," not "calibrate." Wording: "Set scale" / "Known distance". "Calibrated" can remain as the status word. INFERRED.
- Sync: "my notes are safe" is the emotional core. A red "Stale git lock file" exposes an implementation detail; users interpret it as "my data is broken." Wording below.
- Measurement values: users expect that editing a dimension changes the dimension, and, in a scaled photo, either changes the scale or the label. Ambiguity here is the largest mental-model risk; resolve via the explicit two-choice in 1.4.

## 3. Accessibility

Standards (web search 2026-11-14; secondary summaries, official Understanding pages not opened): WCAG 2.2 SC 2.5.8 Target Size (Minimum, AA) is 24x24 CSS px or spacing; 2.5.5 (AAA) is 44x44; SC 2.5.7 Dragging Movements (AA) requires a single-pointer, non-drag alternative unless dragging is essential. Sources: [W3C What's New in WCAG 2.2](https://www.w3.org/WAI/standards-guidelines/wcag/new-in-22/), [Deque: WCAG 2.2 and native mobile](https://www.deque.com/blog/what-wcag-2-2-means-for-native-mobile-accessibility/), [Deque WCAG 2.2 updates](https://dequeuniversity.com/resources/wcag-2.2). The 48dp Android guidance is general knowledge (INFERRED, not found by search); the app already uses `MIN_TARGET_DP` in `SyncStatusSheet.kt`.

Recommendations and gaps:
1. Targets: tool chips, zoom buttons and calibration rows keep >= 48dp. Known gaps: the Error row in `SyncStatusBadge.kt` uses a 16dp icon inside a `clickable` Row with no min height (the screenshot row is a text+icon strip; VERIFIED code lines ~285-312), so its height depends on content. Add `heightIn(min = 48.dp)`. Measurement labels and point handles need >= 48dp hit areas (touch slop) though visually small.
2. Dragging (2.5.7): placing and moving points is drag-based. Provide a non-drag path: tap point A then tap point B (two-tap placement) as an alternative to a drag; for moving a point, select it and use nudge arrows (+/- 1px, hold to repeat) or enter coordinates in the inspector. This also serves the owner's "points near the edge" problem and TalkBack/switch users. It must be an option in the same tool (tap-tap if the pointer goes down and up within slop without moving). Note that "one tap + one drag" success metric stays.
3. TalkBack semantics: toolbar as a group with `selectableGroup()`; each tool `Role.RadioButton` + `selected` (or `ToggleButton` for lock), `stateDescription` "locked"; disabled Reference exposes why ("Calibrate first"); mode changes announced via live region ("Distance tool active", "Returned to Select"); the new measurement announced ("Distance 90 cm added"). Measurement values reachable as nodes with "Edit value" custom action. Zoom controls announce the scale ("Zoomed to 40%").
4. Focus order: banner -> canvas -> toolbar -> action row; after finishing an inline edit, focus returns to the edited measurement node.
5. Contrast: active-toolbar label (dark teal on near-black) fails visually in the screenshot; use `onPrimaryContainer` on a filled chip background or white; inactive `0xFFBBBBBB` on dark is OK (~9:1 estimated, INFERRED, measure). The white-on-photo status text needs a scrim; text over images must not rely on the photo's brightness. Do not convey calibration state by color only.
6. Error messages are text with an icon, announced via live region, not only a toast.
7. Reduced motion: lens-switch crossfade and mode animations respect system animation scale (INFERRED).

## 4. Error states

- Stale lock message (current: "Stale git lock file - tap to retry", red, sidebar). Problems: it blames a file name, asks for a retry that will fail the same way if the lock is live, and implies the user must do something. Recommendation, depending on the sync lane's liveness decision:
  - Lock is held by our own live run (the likely case here): not an error at all. Show neutral "Syncing..." (no red). 
  - Lock is ours and proven dead (crash leftover): auto-clear silently, retry once, log it; if that succeeds no UI. 
  - Unsure (fail-closed per requirements): amber, not red: "Sync paused: another git operation may be running. We'll retry automatically." with a secondary "Details" opening the sheet. Only if auto-retry fails twice, show "Couldn't sync. Your notes are saved on this device." with actions "Try again" and "Details". Fixing a lock the app cannot prove stale must require an explicit, labeled confirmation in the details sheet ("Remove lock file - only if no other sync or git app is running"), never from the one-tap badge.
  - Always say notes are safe locally when true (verify via the #676 guard), and show "last synced N h ago" (already present).
- Calibration impossible or inconsistent: see 1.5 items 5-6. Never block measuring; the fallback is "Not calibrated, px only" and a clear path to fix.
- Lens unavailable: see 1.6 item 4. Permission denied: see 1.6 item 6.
- The toast/status overlapping the canvas: render it in layout space (a status strip above/below the canvas) or as a Material snackbar anchored at the bottom above the toolbar. Material guidance (found in search, via a mirror page, so verify at the official site): snackbars go at the bottom, in front of content, and should avoid covering frequently used touch targets or navigation; place them above a bottom bar. Source: [material.io snackbars](https://material.io/components/snackbars) (summary only). More fundamentally, "estimation coming in a future update" is a promise about an unreleased feature; hide it entirely when `onEstimate == null` (scope says only fix the misleading toast) or move it to settings. The visible-in-annotator model-ready message has no user job.
- Unsaved/loss: retain `UnsavedChangesDialog`; calibration edits are undoable.

## 5. Jobs to be done

Main job (owner): "When I need to know a real-world size and only have a photo (renovation, fitting furniture in a space), I want a trustworthy dimension quickly, so I can decide or plan without going back with a tape."
- Functional: place measurements fast with one hand; fix mistakes; edit a known value; know how far to trust a number; capture with the right lens.
- Emotional: confidence ("is this number right?"), control (no accidental modes, no stuck tool), not-anxious about data ("my notes synced"), not feeling stupid at an unexplained red error.
- Social: share a measurement with a contractor/partner and be believed: needs visible scale source ("from 3 known distances, +/-1.2%"), clear export labels, and consistent units (already feet-inches default).
- Sync job: "When I finish writing notes, I want them safely on my other devices without thinking about git." Failures should be rare, self-healing and quiet.
- Capture job: "When the space is tight, get the whole wall in frame (ultra-wide), but know it costs accuracy."

## 6. Concrete recommendation summary (for planning)

1. One-shot tools by default with auto-select of the new measurement; lock via chip lock affordance (double-tap as shortcut); two-finger navigation in all modes.
2. Zoom out to ~25-30% of fit with a pasteboard and frame; zoom controls out of the image area; clamp pan to keep part of the image visible.
3. Tap label -> inline editor with an explicit "known distance" vs "relabel" choice and a preview of impact; undoable.
4. "Not calibrated" becomes an actionable single tap target (48dp) leading to a Known distances list; state ladder: none -> 1 (approx.) -> 2+ with residual; guidance to vary direction; flag worst row; no model jargon.
5. Camera: lens chips from real lenses (ratio + mm), 1x default, hint on wide-angle, flash cycle button, failure keeps current lens, permission screen with settings/gallery routes; record lens and focal length.
6. Sync: running = disabled "Syncing..." with reason, press is a no-op/join, never cancel; live-own-lock is not an error; stale-lock copy rewritten; amber for unsure; fail closed.
7. A11y: `selectableGroup` + radio semantics + live announcements; tap-tap placement and nudge as drag alternatives; fix active-label contrast; 48dp for the sync error row and labels.

## Gaps / not verified
- No source found for a long-press zoom dial; no official W3C Understanding pages opened (summaries only); Apple Measure/Figma/Procreate/Moasure behaviors are from general knowledge, not re-fetched.
- Contrast ratios not computed. Gesture layer behavior for two-finger gestures while a tool is armed not read in code.
- Whether outside-image points are representable in the sidecar schema is for the architecture lane.
