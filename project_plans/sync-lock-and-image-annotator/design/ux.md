# UX design: sync-lock-and-image-annotator

Date: 2026-11-14. Inputs: `requirements.md`, `research/ux.md`, `implementation/plan.md`, ADR-001/002/004/005/006/007, owner screenshots (`uploads/...10852.png` annotator, `...10849.png` git panel, `...10850.png` logs, `...10851.jpg` photo), and the existing UI code (`ui/annotate/AnnotationToolbar.kt`, `AnnotationZoomControls.kt`, `CalibrationNudgeBanner.kt`, `CalibrationConfidenceBadge.kt`, `AnnotationEditorScreen.kt` `CalibrationStatusBar`, `ui/components/SyncStatusBadge.kt`, `SyncStatusSheet.kt`).

Labels: VERIFIED = read in code/screenshot this pass; INFERRED = reasoning; UNVERIFIED = needs a measurement or device check. The screenshots are 1080 px wide; dp conversions assume density 2.75 to 3 (INFERRED, so sizes read from pixels are approximate; measure with the layout inspector before citing).

Design system (VERIFIED from code and screenshots): Material3 (`MaterialTheme.colorScheme`, `typography`), warm off-white surface, teal primary/primaryContainer, dark annotator toolbar (`TOOLBAR_BACKGROUND_COLOR = 0xEE1A1A1A`, inactive tint `0xFFBBBBBB`, disabled `DISABLED_TINT_COLOR 0xFF9E9E9E`), `MIN_TARGET_DP = 48` in `SyncStatusSheet.kt`, no warning/amber theme token (the sync UI hard-codes `Color(0xFFF59E0B)` at `SyncStatusBadge.kt:165,203,255,348` and `SyncStatusSheet.kt:125`; VERIFIED by grep, so Story 1.7b adds a light/dark token pair), `PlainTooltip` on toolbar buttons, `ModalBottomSheet`-style sheets for calibration. Wireframes below are 360 dp wide, portrait, unless stated. `[ ]` is a button/target, `( )` a chip, `<...>` a variable.

## 0. Surfaces designed

| # | Surface | Kind | Epic |
|---|---------|------|------|
| S1 | Sync status row + sheet (idle, running, second press, queued, paused, failed, lock removal) | interactive | E1 |
| S2 | Annotator tool modes: armed state, one-shot, lock, tap-tap placement, abandon/cancel | interactive | E2 |
| S3 | Pasteboard canvas, zoom controls row, status strip (fix for the overlapping "toast") | interactive | E2 |
| S4 | Move-a-point without dragging (select + nudge) | interactive | E2 |
| S5 | Inline measurement value editor (known distance vs change label only, preview, undo) | interactive | E3 |
| S6 | Calibration state ladder pill + "Known distances" sheet | interactive | E3 |
| S7 | Camera capture dialog: lens chips, flash, zoom, hints, permission and failure states | interactive | E4 |
| S8 | Sync decision/lock log lines (Logs screen) | non-interactive | E1 |
| S9 | Camera capability probe output | non-interactive | E4 |

Total 9 (7 interactive, 2 condensed).

## 1. Observed defects this design must fix (from the screenshots, VERIFIED unless noted)

1. "Depth model ready - estimation coming in a future update" is a status row drawn over the top 56 dp of the photo (10852.png). It covers image pixels and promises an unreleased feature.
2. Zoom `+`, `-`, `Fit` circles float over the photo's right edge (10852.png) and cover the outermost pixels. Their diameter is about 32-36 dp by pixel measurement (UNVERIFIED density), below the 48 dp guideline.
3. The active Select chip fills only the icon; its label "Select" is dark teal on the near-black toolbar (about 1.3:1 estimated, UNVERIFIED, measure it). Inactive labels `0xFFBBBBBB` are about 9:1, disabled `0xFF9E9E9E` about 6.5:1 (matches the code comment).
4. The visible "Not calibrated" status is the small grey pill from `CalibrationConfidenceBadge` (about 19 dp tall, a dash icon, no action). `CalibrationNudgeBanner` is a different component, shown only in some states (`AnnotationEditorScreen.kt:401`).
5. The centre mic FAB (x 472-608 px) overlaps the toolbar's second row; the Redo button sits at x about 495 px (10852.png). Adding controls to that row without fixing this collides with the FAB.
6. The git row "Stale git lock file - tap to retry" is red, 17 dp-ish tall, blames a filename, and offers a retry that fails identically when the lock is live (10849.png; logs 10850.png show the error 0.36 s after a sync start).

## 2. S1 - Sync status (Epic 1)

### 2.1 Wireframes

Sidebar row (today) and the states it must express. Row min height 48 dp, whole row tappable to open the sheet (Details); a trailing 48 dp icon button is the only retry/sync action.

```
IDLE            [o] Synced 2 min ago . behind 0                  [sync]
RUNNING         [spinner] Syncing...  Fetching                    (no button)
                (second line, only if a follow-up is queued: "One more sync will run after this")
RATE-LIMITED    [clock] Retrying shortly (GitHub rate limit)      (existing, not clickable)
PAUSED (amber)  [!] Sync paused - retrying automatically  >      
                Your notes are saved on this device.
FAILED (red)    [!] Couldn't sync. Your notes are saved here.  [Sync now] [Details]
```

Sheet (extends `SyncStatusSheet.kt`):

```
+-------------------------------------------+
| Git sync                          [x]     |
| personal-wiki . last merged 1 h ago       |
|-------------------------------------------|
| STATE   [spinner] Syncing... (Fetching)   |  <- Running
|         A sync is already running.        |
|         [ Syncing...  (disabled) ]        |  <- 48dp, enabled=false, reason text shown
|-------------------------------------------|
| STATE   [!] Sync paused                   |  <- Paused
|         We couldn't confirm that nothing  |
|         else is syncing, so we paused to  |
|         protect your notes. We'll retry   |
|         automatically (next try in ~1     |
|         min). Notes are saved on this     |
|         device.                           |
|         [ Retry now ]  (enabled; 48 dp)   |
|         > Details: lock owner unknown,    |
|           seen 14:02, try 1 of 2          |
|           [ Copy details ]                |
|           [ Remove lock file... ]  (only  |
|             when verdict NoRecord, or     |
|             after failure escalation)     |
+-------------------------------------------+
```

Confirm dialog (only reachable from the sheet, never from the one-tap row):

```
+--------------------------------------+
| Remove the git lock file?            |
| Do this only if no other sync or git |
| app is running. Removing a lock that |
| is in use can damage the repository. |
|           [ Cancel ]  [ Remove ]     |  <- initial focus on Cancel
+--------------------------------------+
```

Copy table (final strings):

| State | Text | Colour token | Action |
|-------|------|--------------|--------|
| Running, any trigger | "Syncing..." + phase | neutral (onSurfaceVariant) | none; row opens sheet |
| Second press during run | no new text; one polite live-region announcement "Already syncing" (a one-shot `AlreadySyncing` UI event from the coordinator, no snackbar, no toast) | neutral | no-op |
| Typed-count "lift guard" press during a run | "Wait for the running sync to finish, then confirm again" (dialog stays open) | neutral | none; the lift flag is never queued |
| Follow-up queued | "One more sync will run after this" (sheet and row line 2) | neutral | none |
| Own lock, live | same as Running (never an error) | neutral | none |
| Own lock, proven dead | silent auto-clear, one retry, log line | none | none |
| Unsure (`OwnerUnknown`) | Row: "Sync paused - retrying automatically". Sheet body (cause-neutral, because the verdict means the owner could not be determined, not that another sync is running): "We couldn't confirm that nothing else is syncing, so we paused to protect your notes. We'll retry automatically." | warning/amber | row opens Details; sheet offers an enabled "Retry now" (below) |
| Paused, "Retry now" | "Retry now" (48 dp, enabled while a retry is pending) | warning | runs one extra attempt immediately through the gate; does not consume the automatic retry budget; see 2.2 step 5 |
| Paused entered | one polite live-region announcement "Sync paused. Retrying automatically." (also on entering Failed: "Couldn't sync. Your notes are saved on this device.") | n/a | none (UX-04) |
| Unsure after 2 failed auto-retries | "Couldn't sync. Your notes are saved on this device." | error | "Sync now" (a fresh run, restarts the retry budget), "Details" |
| `NoRecord` lock | "A leftover lock file is blocking sync" (Details only) | warning | "Remove lock file..." with confirm |
| Remove failed | "Couldn't remove the lock file: <reason>." | error | "Copy details" |

**Action vocabulary (one rule, triad repair 2).** "Sync now" starts a user-initiated run and is the only start label (Idle row and sheet, and the Failed state). "Retry now" exists only inside Paused. "Try again" is not used for sync (it stays in the camera and save-failure copy, which are different surfaces). Three labels for near-identical actions are therefore two, and each names a different consequence: "Sync now" is a fresh run, "Retry now" hurries the pending automatic retry.

Notes are safe copy is shown only when the #676 guard confirms local content is intact; if that cannot be confirmed, omit the sentence rather than claim it (INFERRED requirement).

### 2.2 Interaction flow

1. Idle -> user taps the row's refresh icon (or the sheet's "Sync now") -> state Running within one frame; icon replaced by spinner; no confirmation.
2. Running -> any further trigger (second tap that raced the state change, scheduled tick, worker, desktop menu command) -> coalesced. UI: no state change, no error, no cancel, no restart. If the trigger was Manual, set the queued line. A TalkBack polite live region says "Already syncing".
3. Run ends OK -> "Synced just now". If a follow-up was queued it starts immediately and the row stays Running without flicker to Idle.
4. Run ends with verdict `OwnerAlive` -> treated as Running by another actor (neutral), poll via the existing driver; no error.
5. Verdict `OwnerUnknown` -> Paused (amber). Auto-retry twice (ADR-001 decision 14: after about 30 s and about 2 min, published as `pausedRetry` so Details shows a countdown, "try 1 of 2"). The second failed automatic retry -> Failed (red) with "Sync now" and "Details". The retry waits for the gate to be free and never runs during a live sync.
   - **Retry now (user control; budget rule changed in triad repair 2).** While a retry is pending the sheet shows an enabled "Retry now". Pressing it cancels the pending timer and runs one extra `sync` attempt immediately through the normal gate: if a run holds the gate it coalesces exactly like a second Sync press (no error, `AlreadySyncing` announcement, no extra retry scheduled). **It does not consume the automatic budget:** the "try n of 2" number does not move, and if the manual attempt ends in an unknown owner again the pending automatic try is re-armed with its original delay counted from now. Two quick presses therefore cannot reach red; only the two timed automatic failures can. Repeated presses never add automatic retries and never restart the budget.
   - **Sync now from Failed.** It is a user-initiated run through the gate. If it ends in an unknown owner again, a fresh two-try amber cycle starts (the budget restarts); each cycle needs one deliberate press, so there is no unattended loop.
   - **Durability.** The countdown timer is an in-process coroutine on the service scope (the same mechanism as `RateLimitRetry.kt:22`, `scope.launch`), so it does not survive process death and may be delayed while the app is backgrounded. The paused state is therefore **not persisted**: a relaunch or the next WorkManager periodic tick re-runs the claim (periodic work is enqueued in `WorkManagerTaskScheduler.kt:158,185`, VERIFIED by grep) and either clears a provably dead owner silently or starts a fresh amber cycle (two tries). If the app returns to the foreground after the scheduled retry time has passed, the overdue retry runs once on first observation (Task 1.7c), so amber never sits indefinitely.
6. Verdict `NoRecord` -> Paused with Details showing "Remove lock file..."; confirm dialog -> remove -> re-check status and repo health -> sync retries once -> success returns to Idle; failure shows the error text in the sheet.
7. Cancel (if the product exposes a Stop control) is a separate labelled control; while a non-cancellable phase runs it shows "Stopping..." and stays disabled until the call returns (matches Story 1.5). No Stop control is introduced by this design.

Exit paths: every state can be dismissed by closing the sheet (`[x]`, Back, scrim tap); Failed has "Sync now" and "Details"; Paused has Details and automatic progression; the confirm dialog has Cancel (default). No state requires the user to act to recover in the common case.

### 2.3 Error and edge states

| Situation | User sees | Exit |
|-----------|-----------|------|
| Press while running | Nothing changes except the live-region announcement | none needed |
| Press while paused (retry pending) | "Retry now" is enabled; pressing it runs one extra attempt immediately (coalescing-safe, does not consume or add an automatic retry) | wait, Retry now, or Details |
| App backgrounded or killed while paused | Timer may be late or lost; nothing persisted; relaunch/next periodic tick re-claims; overdue retry runs once on foreground | none needed |
| Scheduled tick during manual run | Invisible (logged `decision=skipped`) | none |
| Prior failure, later skipped tick | Failure stays visible until a run publishes its result (plan 1.6) | Sync now |
| App relaunch after force-stop with leftover own lock | Auto-clear, one retry; usually no UI | none |
| Liveness cannot be read (EACCES) | Paused (amber), never red at first | auto-retry, then Failed |
| No network | existing offline/Retry state, unchanged | existing |
| Merge conflict | existing conflict UI, unchanged; row not Running | existing |

### 2.4 Accessibility

Row `Role.Button`, min height 48 dp, `stateDescription` ("Syncing", "Paused, retrying automatically", "Error"); live region for transitions Running/Paused/Failed (**entering Paused announces "Sync paused. Retrying automatically." once, polite**; entering Failed announces the Failed text; UX-04); spinner decorative (the text carries meaning); colour is never the only signal (icon + text). Disabled Sync button exposes its reason through `stateDescription` and the visible reason line.

## 3. S2 - Annotator tool modes (Epic 2)

### 3.1 Wireframe: toolbar and armed state

Toolbar is a `selectableGroup`; the selected tool has a filled indicator behind icon AND a light label.

```
Armed (Distance), one-shot:
+--------------------------------------------------------------+
| Distance: drag from point A to B   (status strip, live region)|
+--------------------------------------------------------------+
|  (hand)  [(Distance)]  (crop)  (grid)  (tag)  (ruler) | (sliders)|
|  Select    Distance     Area    Angle   Label  Reference  Calibrate|
|  [undo] [redo] [ Keep tool ] [cm v]                           |
+--------------------------------------------------------------+
Locked:  chip shows a 16dp lock badge (decorative); [ Keep tool ] is toggled on, stateDescription "locked".
```

- Select label when active: white (`Color.White`, about 17:1 on `0xEE1A1A1A`, computed from luminance, UNVERIFIED by tool) in semibold, plus the filled indicator behind the icon. Not teal-on-dark. The plan's "onPrimaryContainer on a filled chip" only works if the fill extends behind the label (see finding F3).
- `[ Keep tool ]` is a 48 dp toggle (lock icon, label "Keep tool") in the action row, visible only while a placement tool is armed; desktop has a keyboard shortcut for it. Long-press or double-tap on a tool chip also locks (shortcut, not the only path). Long-press and double-tap collide with TalkBack's double-tap-to-activate and are hidden gestures, so every tool chip also exposes a **TalkBack custom action "Keep tool"** (and "Release tool" when locked) that toggles the same state; the visible 48 dp toggle remains the primary path.
- Tapping the already-active tool chip disarms it (back to Select). Tapping Select, Back or Esc also disarms. Every other cancel keeps the tool armed; see the single cancel table in 3.3.
- Reference is disabled when not calibrated and exposes "Calibrate first" (tap on the disabled chip is a no-op plus a one-line hint in the status strip "Calibrate first to use Reference").
- The mic FAB must not overlap the toolbar: decision, the FAB is **not shown while the annotation editor is the active screen** (Task 2.5c; the editor has its own voice-free toolbar), so no inset juggling is needed. **Recorded consequence (owner confirmation OC-12):** voice capture is unavailable while the annotation editor is open; the owner leaves the editor (Back) to use it. The FAB host is UNVERIFIED until Task 2.1a. **Voice hint (UX-70):** because the FAB is gone, the editor tells the owner where voice went: the first three times the editor opens, the idle strip reads "Voice notes: go Back to use the mic" (lowest strip priority, dismissible, counter in Settings key `annotator_voice_hint_count`).

### 3.2 Flow: one-shot default (Success Metric 2)

1. Fresh editor opens in Select (pan/zoom active).
2. Tap Distance -> chip filled, status strip "Distance: drag from point A to B", announce "Distance tool active".
3. Press on A, drag more than 48 dp, lift on B (ADR-007; the loupe follows the finger while down, and past 48 dp a marker at A and a ghost line A to finger show that release will create a measurement) -> measurement committed, new measurement selected, tool returns to Select, strip reads "Distance 90 cm added. Tap Distance again to measure more" (the hint only the first 3 times, stored in Settings), announce "Distance 90 cm added, returned to Select". Total: 1 tap + 1 drag.
4. Pan/zoom works immediately in Select, and two-finger pinch/pan works in every mode.
5. Lock: tap `[ Keep tool ]` (or long-press the chip) before or after arming; further measurements keep the tool; tap Select or the chip again to leave. Lock resets on leaving the editor.

Alternative path (WCAG 2.5.7, and the loupe-precise path): with Distance armed, press, aim with the loupe and release within 48 dp of the press places point A; the next press-aim-release (any distance) places point B and commits the same measurement. After the first point the strip reads "Point A placed. Tap point B" and a ghost point shows A. Release within 48 dp of the press is never a measurement by itself, so the existing aim-then-release behaviour is unchanged. Hint (first three times): "Drag A to B, or tap two points for precision". Drag-measure applies only at Fit or closer; zoomed out below Fit the strip reads "Tap point A" and a long drag places one loupe-aimed point (ADR-007 decision 1). After a drag-measure commit the strip reads "Drag an end to fine-tune" (first three times); dragging an end shows the loupe.

### 3.3 Exit paths and error states

**One rule, one table (cancel versus disarm).** Only an explicit user act on the tool disarms: tapping the active chip, tapping Select, or Back/Esc. **Every cancel of a gesture (a second finger, an off-canvas or back-gesture release, a drag that returns inside the threshold) discards only the press that is down, keeps the tool armed and keeps an already-placed first point.** This replaces the earlier split where an off-canvas drag returned to Select while a second finger kept the tool armed (triad repair 1; owner confirmation OC-9, default: stay armed; if the owner prefers Select after an off-canvas slip, change the single row marked (OC-9) and the one `disarmOnOffCanvas` constant, nothing else).

| Event | Press that is down | Pending point A | Tool | Strip text |
|-------|-------------------|-----------------|------|------------|
| Second finger lands mid-press | discarded | kept | stays armed | none (pinch/pan takes over) |
| Drag released off-canvas, outside the system-gesture inset rectangle, or started in it (OC-9) | discarded, nothing created | kept | stays armed | "Cancelled" |
| Drag past 48 dp then back within 48 dp before lifting | becomes aim-place (one point), ghost line removed | placed if first | stays armed | normal prompt |
| Tap the active tool chip | n/a | discarded | disarmed (Select) | idle text |
| Tap Select | n/a | discarded | disarmed (Select) | idle text |
| Back/Esc with a tool armed (first press) | n/a | discarded (one point silently; two or more unfinished multi-step points ask the existing "Discard N unfinished points?" dialog first) | disarmed (Select); the editor is not left | idle text |
| Back/Esc in Select with nothing pending (second press) | n/a | n/a | editor closes through the existing leave check | n/a |
| "Cancel" button in the strip (tap-tap point A pending) | n/a | discarded | **stays armed** | the tool prompt again |
| Commit persistence fails | n/a | removed with the rolled-back measurement | restored only if the user has not picked another | "Couldn't save. Try again" |

| Situation | Behaviour |
|-----------|-----------|
| Arm tool then do nothing | Armed state is visible (strip + chip); disarm by tapping chip, Select or Back/Esc (a two-finger pan does not disarm, it just pans) |
| Back/Esc with a tool armed | First press disarms (and discards a pending point A); the editor is not left. Second press, now in Select, leaves through the existing leave check |
| Tap A placed, user changes mind | The strip shows a visible 48 dp **Cancel** beside "Point A placed. Tap point B" (discards A, tool stays armed; discoverable for sighted and switch-access users). Tapping the chip, Select or Back/Esc also discards A and disarms; no measurement. A second finger (pinch to zoom before placing B) keeps A |
| Second finger lands mid-drag | See the table above (in-flight placement discarded, tool stays armed, any placed A is kept) |
| Drag ends off-canvas / system back gesture | See the table above (cancel without creating, **tool stays armed**, one-line "Cancelled" in the strip; OC-9) |
| Press/release within 48 dp of each other | Aim-place: one point at the release position (first point), never a degenerate measurement |
| Drag past 48 dp then back within 48 dp before lifting | Reverts to aim-place; the ghost line disappears; no measurement |
| Both points identical after tap-tap | Rejected, strip: "Choose a second point away from the first"; point A kept |
| Commit persistence fails | Measurement removed (rollback), strip error "Couldn't save. Try again"; tool restored only if the user has not picked another |
| Undo after auto-return | Removes the measurement; redo does not re-arm any tool (ADR-005) |
| Multi-step (Reference/ticks/plane/Area) | Stays armed until its own Done/Confirm; Done is a visible 48 dp button in the strip; Cancel beside it |
| Setting "Return to Select" off | Legacy: tool stays; strip hint shown once |

Settings copy: "Return to Select after each measurement" (default on), "Keep tools active" (default off, advanced).

### 3.4 Accessibility

Toolbar `selectableGroup`; each chip `Role.RadioButton`, `selected`; lock toggle `Role.Switch`/`ToggleButton` with `stateDescription` "locked"/"not locked"; status strip is a polite live region; chips and Keep tool at least 48 dp; each tool chip keeps a text label (already present) so it is not icon-only. Each tool chip has the custom action "Keep tool" / "Release tool" (long-press and double-tap are not available under TalkBack's own double-tap).

**TalkBack path for placing a point (explicit, announced).** Touch exploration cannot drive the loupe-aim press, so the placement canvas exposes a custom action "Place point with crosshair" (also listed in the toolbar overflow as "Crosshair placement") that starts the existing crosshair session (`AnnotationNonDragMode`): TalkBack announces "Crosshair at 50 percent, 50 percent. Use the arrow buttons to move, then Place point"; each nudge announces the new coordinate in percent and, once a first point exists, the live length ("Distance 90 cm"); Place point announces "Point A placed" / "Distance 90 cm added, returned to Select". Without this path a TalkBack user could not create a measurement, so it is acceptance-tested (UX-54).

### 3.5 Status strip priority, Cancel and Back (triad repair 2)

**Strip priority (one slot, one winner).** The strip is one reserved single-line live-region slot above the canvas (at least 48 dp tall whenever it carries a button). Exactly one message shows; the highest priority wins and a lower one is not queued, it simply reappears when the higher one clears:

| Priority | Message | Lifetime |
|----------|---------|----------|
| 1 | Error ("Couldn't save. Try again", "Choose a second point away from the first") | until the next user action, at most 4 s |
| 2 | Tool prompt or pending state ("Distance: drag from point A to B", "Point A placed. Tap point B" with [Cancel], multi-step prompt with [Done] [Cancel], transient "Cancelled" for 2 s) | while armed or pending |
| 3 | First-three-times hints | 3 shows, then never |
| 4 | Status text (for example "Depth model ready"; none when `onEstimate == null`) | while true |
| 5 | Voice hint (UX-70) | 3 shows, then never |
| 6 | Idle text | otherwise |

**The Pan pad is not in the strip.** It has its own row (the pan row) between the canvas and the toolbar, outside the photo, so a zoomed tap-tap placement (the main precision case) shows the prompt "Point A placed. Tap point B" with its Cancel in the strip and the four pan arrows in the pan row at the same time. Neither hides the other. Pan row visibility is the single rule in 4.1.

**Back, in order (first match consumes the press; pure `EditorBackPolicy`, Task 2.3a).** (1) An open sheet, dialog or nudge session closes itself (a nudge session is "Cancel move"). (2) Else a tool is armed: disarm to Select and discard any pending point; if two or more unfinished multi-step points exist, the existing "Discard N unfinished points?" dialog (`backOutcome`, `LeavePolicy.kt:24-27`) comes first. (3) Else, in Select with nothing pending: the existing leave path (`viewModel.resolveBack()`, `AnnotationEditorScreen.kt:324-332`): Close, or the unsaved-changes/save-failed dialog. So with a tool armed Back never leaves the editor on the first press.

**Predictive back.** The app opts in (`android:enableOnBackInvokedCallback="true"`, `androidApp/src/main/AndroidManifest.xml:42`, VERIFIED) and the editor's `PlatformBackHandler(enabled = true)` is always enabled (`AnnotationEditorScreen.kt:324`, VERIFIED), because leaving needs the save check. A back gesture therefore commits the same step as the Back button, and a gesture the finger abandons mid-way changes nothing. Already in Select with nothing pending, the gesture leaves through step 3; no custom preview animation is drawn (the system one is not available while the handler is enabled; accepted, INFERRED, checked on the phone).

## 4. S3 - Pasteboard, zoom controls and status strip (Epic 2)

### 4.1 Wireframe

```
+-----------------------------------------+
| [status strip: reserved layout slot]    |  <- depth/info/error text lives here, never over the photo
+-----------------------------------------+
| /////  pasteboard (neutral surface) //// |
| ///   +---------------+   frame  ////// |
| ///   |    photo      |           ////// |
| ///   +---------------+           ////// |
| ////////////////////////////////////// |
+-----------------------------------------+
| [-]  25%  [+]   [Fit]  [Pan]  (zoom row) |   <- 48dp targets, outside the photo area
+-----------------------------------------+
| toolbar ...                              |

Pan row (own row, between the canvas and the toolbar; never in the status strip):
+-----------------------------------------+
| [<] [^] [v] [>]  Move view               |   <- four 48dp arrows, outside the photo
+-----------------------------------------+
| (status strip above the canvas keeps its prompt and Cancel while the pad is shown) |
```

**One visibility rule (ux 4.1, UX-53, ADR-006 decision 2).** The `[Pan]` toggle and the pan row are available exactly when `viewScale >= FIT_SCALE` (the clamp then lets the image move by `slack`, even at Fit), and the pan row shows only while `[Pan]` is on. Strictly below Fit (centred, fully visible, pan is a no-op) `[Pan]` is disabled with "Nothing to pan" and the row is hidden. The older wordings "above Fit" (4.1) and "hidden at or below Fit" (UX-53) are replaced by this one.

**Non-drag pan (WCAG 2.5.7).** One-finger drag pans a zoomed canvas, so a button alternative is required: the **Pan pad** above, in its own pan row. Each arrow moves the view by 25 percent of the viewport along that axis per press; an arrow is disabled at the clamp edge with `stateDescription` "At image edge"; below Fit the toggle is disabled and the row hidden (pan is a no-op there). The same moves are exposed as TalkBack custom actions "Pan left / right / up / down" on the canvas and as the arrow keys on desktop. Pinch has `[-]` / `[+]`; one-finger pan has the pad; point placement has tap-tap, crosshair and nudge.

**Second row at small widths, large text and RTL.** The second row (Undo, Redo, Keep tool, unit chip, `[-] 40% [+] [Fit] [Pan]`) is a wrapping flow layout, never a horizontally scrolling strip (a scrolled-off control is invisible to low-vision and TalkBack users). At 360 dp it wraps to two lines; every control stays at least 48 dp and none is clipped or overlapped. At 200 percent font scale the unit chip and percentage text wrap instead of truncating, the row may take up to three lines, and the toolbar's total height stays at most 40 percent of a 640 dp-tall window (otherwise the zoom group and the pan row collapse into one "Zoom" button that opens the same controls, including the pan arrows, in a popup). The pan row counts toward that 40 percent budget. In RTL layouts the row order mirrors, while the photo and its coordinates are never mirrored (image x always grows to the right). Criterion UX-62.

Design decision (adopted by plan Task 2.5b): the zoom controls become a horizontal row that merges into the toolbar's second row (Undo, Redo, Keep tool, unit chip, and `[-] 40% [+] [Fit]`), which avoids both the image overlay and a vertical rail eating canvas width on a 360 dp phone. At widths of 600 dp or more (unfolded Pixel Fold, tablet, desktop) the row moves to a vertical rail at the canvas edge outside the photo. The percentage text is the current scale relative to Fit and is the TalkBack value ("Zoom 40 percent").

Pasteboard: neutral surface (darker than page surface, checker optional) plus a 1 dp image-bounds frame in `outline` colour; the frame is 3:1 against the pasteboard (non-text contrast, WCAG 1.4.11).

### 4.2 Flow

1. Pinch out (or tap `[-]`): scale falls to 0.25 of Fit and stops; below Fit the image is centred and fully visible (pan is a no-op), and at or above Fit the clamp keeps the image reachable by construction (ADR-006 decision 2; the older "at least 25 percent of the photo stays in the viewport" statement was dropped there as vacuous below Fit). `[-]` disabled only at the floor with `stateDescription` "Minimum zoom"; `[Fit]` enabled whenever not at Fit; double-tap on the canvas toggles Fit/100 percent (bonus).
2. In Select or an armed tool, drag a finger into the pasteboard: the point lands on the nearest image edge pixel (clamped); the loupe shows the clamped point and stays inside the viewport. A faint tick on the frame shows where the point will land.
3. Status text (e.g. "Depth model ready") renders in the strip. When there is no estimation feature (`onEstimate == null`) no depth text is shown at all.

### 4.3 Edge cases

| Situation | Behaviour |
|-----------|-----------|
| Finger near the system back-gesture edge | The canvas is laid out inside the system-gesture inset (ADR-006 decision 6), so the outermost pixel at Fit is not under the back-gesture zone; a press or release outside that rectangle is off-canvas (cancel). Zooming out adds more pasteboard reach |
| 12 MP photo at 0.25 | Decoded bounded for scale below 1; if frames drop, the control still works (graceful degrade) |
| Point exactly at the frame | Stored x=0.0 or 1.0; the handle is drawn fully (not clipped) because the pasteboard supplies room |
| Landscape / fold | Row becomes a rail (section 4.1); no overlap with insets |
| Reduced motion | Zoom-to-Fit and auto-return animations disabled |

## 5. S4 - Moving a point without dragging (Epic 2)

Reuse, do not rebuild: `AnnotationNonDragMode.kt` already provides the nudge pad (`NudgePad`, 48 dp cells, steps 1 px / 10 px / 1 percent via `NudgeStep`, direction arrows, Place point / Apply, `Cancel move`) and a `moving` session for an existing point (ADR-007 decision 6).

```
 point selected -> nudge pad (existing)
 +------------------------------------------------+
 |  step: (1 px) (10 px) (1 %)                      |
 |            [^]                                   |
 |        [<] [Apply] [>]                           |
 |            [v]       [Cancel move]               |
 +------------------------------------------------+
```

1. Tap a handle in Select (hit area at least 48 dp regardless of zoom) -> the pad opens in move mode for that point (Task 2.3d adds this entry point if it is absent; verified by reading `AnnotationPlacement.kt`).
2. Arrows move the point; the measurement value updates live; one undo entry per move session (committed on Apply).
3. At the image edge the arrow is disabled with "At image edge" as its state description.
4. Exit: Apply, Cancel move, tapping empty canvas, Back/Esc. Undo reverts the whole session.

This is the 2.5.7 alternative for moving points and the edge-reach helper (finding F4). The earlier "inspector strip with 1 px / 5 px" is withdrawn in favour of the shipped pad.

## 6. S5 - Inline value editor (Epic 3, ADR-002)

### 6.0 Scope and unit chip (triad repair 2; owner confirmation OC-19)

**Which measurements open the editor (default).** Distance only, the full two-meaning editor of 6.1. Area and Angle values are not editable in this plan: an angle is scale-independent, so a typed angle cannot calibrate anything, and an area would need a square-root scale rule and a non-length `MeasurementValue`, which ADR-002 does not model (it stores metres). Tapping an Area or Angle value selects the measurement, exposes no "Edit value" action, and the strip says "Only distance values can be edited" (priority 2, 2 s). A Label annotation has no measured value; tapping it keeps its existing text editing. The default is a proposal: extending to Area or Angle with the same "Change label only" choice (and "known area" for Area) is the "if no" path, and it adds a value kind to ADR-002's `MeasurementValue` plus one editor variant per kind.

**Unit chip in the editor.** It sets the **entry unit** for the number typed in this editor only (default: the toolbar's current display unit). It never changes the global display unit (the toolbar unit chip) and never changes how the value is stored (metres). After Apply the label is shown in the global display unit. A unit typed inside the text (for example `5'3"` or `2.5 ft`) wins over the chip for that entry.

### 6.1 Wireframe

Tap the measurement label chip ("442 px" or "90 cm"); label hit area at least 48 dp (invisible padding), tapping the line also selects. Anchored bottom sheet:

```
+-----------------------------------------+
| Distance                         [x]    |
| [ 90        ]  (cm v)                   |  <- numeric field, unit chip, inline error line
|  helper: "Enter a length, e.g. 90, 2.5 ft, 5'3\"" |
| What does this mean?                    |
| (o) Use as known distance   (recommended if photo is "Not calibrated")
|     Sets the photo's scale. 3 other measurements will change.
|     Scale: 1 px = 0.21 cm  ->  0.19 cm  |
| ( ) Change label only                   |
|     Shows 90 cm on this line. The line's measured length, the scale and other measurements stay.
|                [ Cancel ]  [ Apply ]    |
+-----------------------------------------+
After Apply -> snackbar above the toolbar: "Scale updated. 3 measurements changed.  [Undo]"
```

**Preselection rule (one rule; replaces the earlier "remembered for the session" versus "neither preselected" conflict):**
1. A photo that is "Not calibrated": "Use as known distance" is always preselected.
2. A calibrated photo, **first edit in this editor session**: neither option is preselected and Apply is disabled until the user chooses (error prevention: the two choices have different consequences).
3. A calibrated photo, **later edits in the same editor session**: the option chosen last time is preselected and still shown and changeable, so a run of fine-tuning edits does not force a decision each time; the preselection is not persisted past leaving the editor.
Apply is disabled for empty, zero, negative, non-numeric input, with the reason in the helper text and the user's input kept. "Change label only" marks the number as edited (pencil glyph, "edited" in the accessible name) because the line's real length then differs from the label; the old wording "Just relabel" is retired as jargon. When an edited measurement's endpoint is dragged, the confirm dialog's initial focus is "Keep my value".

**Snackbar Undo duration.** The Undo snackbars here and in 7.3 use `SnackbarDuration.Indefinite` (with a dismiss action) when a touch-exploration or switch-access service is enabled, and `Long` otherwise; the toolbar Undo button remains available after the snackbar is gone, so Undo is never time-limited for any user.

### 6.2 Flow

1. Tap label -> sheet opens, field focused, keyboard numeric, current value prefilled and selected.
2. Type value; preview text updates as the choice changes ("3 other measurements will change" / "1 measurement keeps your edited value").
3. Apply -> one undo entry (edit plus any re-fit). Focus returns to the edited measurement node; announce "Distance 90 cm. Scale updated".
4. Dragging an endpoint of a label-only-edited measurement asks "This value was edited by you. Clear it and use the measured length?" [Keep my value] [Clear and recompute]; declining reverts the points (plan 3.4). The edited label shows a small pencil glyph and "edited" in its TalkBack name so it is distinguishable.

### 6.3 Errors and exits

| Situation | Behaviour |
|-----------|-----------|
| Value empty/0/negative | Apply disabled; helper "Enter a length greater than 0" |
| Unit mismatch (typed "5'3\"" while unit is cm) | Parsed and converted; the field reflects the parsed value in the display unit |
| Implausible ratio (>3x or <1/3 of existing scale) in known-distance mode | Non-blocking warning "This is 4x the current scale. Check the unit." [Apply anyway] |
| Segment has zero pixel length | Known-distance option disabled: "This line is too short to set a scale"; "Change label only" still allowed |
| Solver returns a typed failure (collinear, too few) | Scale tier SCALE is used, one line says why in the sheet footer; measuring never blocked |
| Save fails | Rollback, inline error "Couldn't save the change. Try again"; sheet stays open with input preserved |
| Dismiss | [x], Cancel, Back, scrim tap: nothing changes (if the field is dirty, a discard confirm is not needed; undo does not exist since nothing was committed) |

Accessibility: custom action "Edit value" on each measurement node; sheet title announced; radio group semantics; field has label "Length" and unit chip has a role; error text is announced.

## 7. S6 - Calibration pill and "Known distances" (Epic 3)

### 7.1 The status pill is the entry point (not only the banner)

The visible control today is `CalibrationConfidenceBadge`/`CalibrationStatusBar`. It becomes a single 48 dp-tall tap target that opens the Known distances sheet. State ladder (text + icon, never colour only; icons: dash, ruler, check):

| State | Pill text | Trailing |
|-------|-----------|----------|
| 0 distances, no other calibration | "Not calibrated - add a known distance" | chevron |
| 1 distance | "Scale from 1 distance (approx.)" | "Add another" |
| 2 distances | "Scale from 2 distances, agree within X%" (X = disagreement of the two; no residual of a fit yet) | "Add another" |
| 3+ non-collinear | "Calibrated from N distances, +/-R%" (R = mean residual) | confidence badge |
| 3+, all within 15 degrees of one direction | as above plus hint line "Add one in a different direction to check stretching" (shown only when the optional PER_AXIS tier is enabled; for the tilt tier (`GRADIENT`, which ships default-off until the owner's photo study, ADR-003) the hint is "Add one in a different part of the photo to check tilt" when midpoints cluster, shown only while that tier is enabled) | |
| Fit failed / fell back | "Scale from N distances (basic)" and the sheet explains why | |
| Legacy calibration (BLE, manual reference, ticks, paper, EXIF estimate) | the existing method label ("+/-manual ref", "Scale uncertain"), unchanged; "Not calibrated" is shown only when `calibration` is null or method NONE | chevron |
| Legacy basis (basisVersion 0) | existing label; the known-distances action is disabled with "Calibrated with an older method. Re-calibrate to add distances." and a link to Calibrate | chevron |

### 7.2 Sheet wireframe

```
+---------------------------------------------+
| Known distances                       [x]   |
| Calibrated from 3 distances, +/-1.2%  [badge]|
|---------------------------------------------|
| [h] Door width      0.90 m    0.8% off   [del]|
| [v] Door height     2.03 m    0.9% off   [del]|
| [d] Shelf diagonal  1.20 m    6% off  [!]  [del]|
|     "Disagrees by 6% - re-measure or remove?"   |
|     [ Re-measure ] [ Use anyway ]               |
|---------------------------------------------|
| Hint: Add one in a different direction to   |
| check stretching.                           |
| [ + Draw a known distance ]                 |
| [ Use a card or sheet of paper ]            |
| > More ways (peer scale, BLE laser, ruler ticks, how to calibrate) |
+---------------------------------------------+
```

**Empty state (0 rows).** The sheet never shows an empty list; with no known distances it shows:

```
+---------------------------------------------+
| Known distances                       [x]   |
| Not calibrated - add a known distance       |
|---------------------------------------------|
| A known distance is a length you know, like |
| a door width. Draw a line along it and type |
| its length; more distances improve accuracy.|
| [ + Draw a known distance ]                 |
| [ Use a card or sheet of paper ]            |
| > More ways (peer scale, BLE laser, ruler ticks, how to calibrate) |
+---------------------------------------------+
```

Exits: `[x]`, Back, scrim, or either action. When a legacy calibration exists (no constraints), the heading shows the legacy method label instead of "Not calibrated" (UX-33) and the same body is shown.

Row: direction icon (horizontal/vertical/diagonal), name (default "Distance N", tap to rename), value + unit (tap opens the S5 editor), residual with text ("0.8% off"), delete 48 dp. Row min height 56 dp.

### 7.3 Flows

1. Pill -> sheet. "Draw a known distance": sheet closes, Distance arms (status strip "Known distance: drag from A to B"); after the drag the S5 editor opens in known-distance mode with the new measurement; Apply returns to the sheet (reopened) with the new row and updated pill; Cancel returns to the sheet with nothing added and the drawn line removed.
2. Delete a row: confirm via existing `CalibrationChangeWarningDialog` stating the number of measurements that will change; snackbar Undo (duration rule in 6.1: indefinite under touch exploration or switch access); calibration undo restores the full constraint list.
3. "Use anyway" on a flagged row dismisses the flag for this fit only; the residual stays shown.
4. "More ways" expands the existing options unchanged.

### 7.4 Errors and exits

| Situation | Behaviour |
|-----------|-----------|
| Two points identical / distance <= 0 | Inline error at the field; input kept |
| Duplicate segment (same endpoints within 2 px) | "This segment is already a known distance" [Replace value] [Cancel] |
| Unit typo (one row 2.54 m vs 0.90 m neighbours) | Row flagged with the implied ratio |
| Residuals split into two groups (for example a door frame and a floor tile) | Hint "These distances may be on different surfaces" replaces the single-row "re-measure" wording |
| All collinear | Informational hint only, not blocking |
| Fewer constraints than the richer model needs | Sheet says "Needs 1 more distance to check for camera tilt" (plan 3.3: the tilt tier, `GRADIENT`, needs N >= 5 and measures a scale gradient, not a focal length; it is shown only while enabled), tier SCALE used |
| Solver failure | Pill stays at last good state; sheet footer states the reason; no number is shown for the failed fit |
| Delete the last row | Pill returns to "Not calibrated - add a known distance" (or the legacy method if one exists) |
| Dismiss | [x], Back, scrim |

Never headline "lens distortion" (prior art ranks it smallest, VERIFIED in requirements); wording is "Improve accuracy".

## 8. S7 - Camera capture dialog (Epic 4)

### 8.1 Wireframe

```
+-----------------------------------------+
| [x]                          [flash: Auto]|  <- top bar, 48dp targets
|                                           |
|            live preview                   |
|                                           |
|   hint: Wide angle distorts edges;        |  <- only for ultra-wide, dismissible
|   fine for tight spaces                   |
|   ( 0.5x ) ( [1x] ) ( 1.6x )              |  <- lens chips, >= 48dp, selected = filled
|     13mm     24mm    38mm   (secondary line, eq.)
|            [  shutter  ]                  |
+-----------------------------------------+
Single logical camera (lenses hidden by OEM):
   ( 0.6x ) ( [1x] ) ( 2x* ) ( 5x* )   * Digital  (hint "Digital zoom, lower quality")
Landscape / unfolded: chips and shutter move to a side rail; nothing under system bars.
```

### 8.2 Flows

1. Dialog opens on the last-used lens or 1x; chips are built from what the device exposes; nothing is invented.
2. Tap a chip -> previous frame stays visible (brief crossfade, none under reduced motion), shutter disabled during rebind, chip shows a progress state, then selected; announce "Ultra-wide selected". Five quick taps -> only the last requested lens binds after the in-flight one.
3. Flash button cycles Off -> Auto -> On with text and icon, and announces the new state.
4. Pinch to zoom moves the highlight to the nearest chip; chips only claim optical lenses. **Hysteresis:** the highlight moves to a different chip only after the zoom ratio has passed the midpoint between two stops by at least 5 percent of the span between them and stayed there for 150 ms of virtual time, so a pinch hovering at a midpoint does not flicker between chips; releasing the pinch snaps the highlight to the nearest stop without rebinding (pinch is a zoom change, not a lens switch).
   - **Hint persistence:** the ultra-wide hint is dismissed per install, not per session: dismissing writes a Settings flag (`camera_ultrawide_hint_dismissed`), and it is not shown again until the owner resets app settings.
   - **Initial bind (loading):** on first open the preview is empty until the first frame arrives; the dialog shows a neutral surface with a 48 dp progress indicator labelled "Starting camera..." (polite live region), the shutter and chips disabled; if no frame arrives within 5 s it becomes the "Camera isn't available" screen (8.3). The first bind is not a lens switch, so there is no previous frame to hold.
   - **Flash unavailable:** if the selected camera has no flash unit (front camera, some ultra-wide or tele lenses, or an emulator), the flash button is shown disabled with the content description "Flash not available on this lens" (not hidden, so the layout does not jump between lenses); the cycle Off/Auto/On is skipped.
5. Shutter -> photo returned with the lens record (chip choice is not trusted; capture metadata is). Preview of the taken photo uses the existing flow; Retake/Use unchanged.

### 8.3 Error and permission states

| Situation | User sees | Exit |
|-----------|-----------|------|
| Lens bind fails | Previous lens keeps running; chip disabled with reason; inline line "Ultra-wide isn't available on this device right now" (3 s, polite live region) | choose another chip, shutter, close |
| All lenses fail / camera in use | Full-area message "Camera isn't available. Another app may be using it." [Try again] [Pick from gallery] [Close] | three exits |
| Permission not yet granted | Existing rationale dialog (`PermissionRationaleDialog`), then system prompt | Not now -> gallery/close |
| Permission denied (can ask again) | Explanation + [Allow camera] [Pick from gallery instead] [Close] | |
| Permission permanently denied | Explanation + [Open app settings] [Pick from gallery instead] [Close] | |
| Permission revoked while open | Preview stops, same screen as "denied" without losing the unsaved state | |
| Rotation / fold change during rebind | Selected lens restored from saved state; shutter disabled until bound; no black frame (last frame held) | |
| Capture fails | Inline "Couldn't take the photo. Try again." shutter re-enabled | retry, close |
| Only one back lens and no zoom range | No chips; zoom row hidden; flash and shutter remain | |
| First bind, no frame yet | "Starting camera..." with progress; shutter and chips disabled; after 5 s without a frame the all-lenses-failed screen | close, wait |
| Selected camera has no flash | Flash button disabled, "Flash not available on this lens"; layout unchanged | other lens |
| OEM hides ultra-wide/tele behind one logical camera | Zoom-ratio chips with optical/"Digital" labels; one honest line "Ultra-wide isn't available as a separate lens on this device" (dismissible, same persistence as the wide-angle hint); still counts as SM4 met | none needed |

Accessibility: chips are a `selectableGroup` with `Role.RadioButton`, content description "0.5x ultra-wide, 13 millimetres equivalent", selected state announced; flash has state text; shutter 72 dp; all targets 48 dp or more; focus order top bar, preview, chips, shutter.

## 9. Condensed non-interactive surfaces

### S8 - Sync log lines (Logs screen)

```
sync trigger=manual decision=started graph=personal-wiki
sync trigger=manual decision=coalesced graph=personal-wiki
lock verdict=OwnerUnknown file=index.lock owner=a1b2 reason=proc-unreadable
rerun requested | rerun started
```
- Every trigger yields exactly one `decision=` line; every lock decision one `lock verdict=` line with owner and reason.
- Lines are plain text, selectable and exportable; no secrets or file contents.
- A coalesced trigger is never logged at error level.
- Lock lines name the file but not absolute device paths beyond the repo-relative name.

### S9 - Camera capability probe output

```
camera id=0 facing=BACK focal=[5.6] logical=true physical=[2,3,4] zoom=0.6..8.0 intrinsics=yes distortion=no
camera id=2 facing=BACK focal=[2.2] ...
```
- Debug-only entry; no new permission.
- One line per camera id plus one EXIF line per test photo; failures logged per field (never abort the probe).
- Export writes a fixture with device make/model only (no serial or location).
- Output order is by camera id; tolerant of missing characteristics ("n/a").

## 9a. Text scale, narrow width and RTL on every new surface (UX-68, triad repair 2)

UX-62's wrap rule (4.1) is the general rule for every new or changed surface, not only the second toolbar row: at 360 dp wide, 200 percent font scale and an RTL layout no text is clipped or truncated away, no control falls below 48 dp or overlaps another, and nothing scrolls out of reach horizontally. Per surface:

| Surface | Rule at 200 percent / 360 dp / RTL |
|---------|-------------------------------------|
| First toolbar row (7 labelled chips: Select, Distance, Area, Angle, Label, Reference, Calibrate; 7 x 48 dp = 336 dp is already tight at 360 dp with no font scaling) | a wrapping flow layout like the second row; labels wrap to two lines instead of clipping; the row may take two lines; order mirrors in RTL, the photo does not; counts toward the 40 percent toolbar budget (the "Zoom"/popup collapse applies) |
| Sync row and sheet (S1) | row text wraps (min height 48 dp, grows); the sheet scrolls vertically with its primary action ("Sync now" / "Retry now") reachable without scrolling past the body copy; buttons wrap, never truncate |
| Value editor sheet (S5) | numeric field, unit chip and the two radio rows wrap; consequence lines wrap; [Cancel] [Apply] stay pinned and visible; the sheet content scrolls vertically |
| Known distances rows (S6) | the 56 dp minimum height grows; long names wrap to two lines; value, residual text and delete stay visible (delete 48 dp) |
| Camera chips and top bar (S7) | the chip secondary line ("13mm") wraps below or folds into the content description at 200 percent; chips wrap onto two rows rather than overflow; flash, close and shutter keep 48 dp and 72 dp |

## 10. UX acceptance criteria (testable by a human or a Robolectric test)

Targets: WCAG 2.2 2.5.8 (minimum 24x24 CSS px), Android 48 dp guideline, 2.5.7 Dragging Movements, 1.4.3 contrast 4.5:1 text, 1.4.11 3:1 non-text, 4.1.3 status messages. Sources: research/ux.md section 3 (secondary summaries, W3C Understanding pages not opened).

Sync (S1)
- UX-01: With a sync running, the sidebar row shows "Syncing..." in a neutral colour (no red element anywhere on screen), and no retry control is present.
- UX-02: Pressing Sync a second time (any entry point) does not change the visible state, starts no new run, shows no error, and TalkBack announces "Already syncing".
- UX-03: When a manual press arrived mid-run, the row/sheet shows "One more sync will run after this"; exactly one follow-up runs; no text shows when none is queued.
- UX-04: With verdict `OwnerUnknown` the row reads "Sync paused - retrying automatically", uses the warning colour (text contrast at least 4.5:1 in light and dark), is at least 48 dp tall, and tapping it opens Details (does not start a sync). Entering Paused is announced once through a polite live region ("Sync paused. Retrying automatically."); entering Failed announces the Failed text.
- UX-05: After two failed automatic retries the row reads "Couldn't sync. Your notes are saved on this device." with "Sync now" and "Details" actions, each at least 48 dp.
- UX-06: "Remove lock file..." exists only in Details, never on the one-tap row; the confirm dialog states "Only if no other sync or git app is running", defaults focus to Cancel, and nothing is deleted before Remove is tapped.
- UX-07: The sheet's disabled Sync button shows the reason text "A sync is already running" and exposes it as `stateDescription`.
- UX-08: The words "index.lock", "stale" and other file-level jargon never appear on the one-tap row; they may appear in Details.
- UX-09: Every sync state in section 2 can be left with Back, [x], or a visible action (no dead end).

Annotator modes (S2)
- UX-10: From a fresh editor, one tool tap plus one drag creates a measurement, the toolbar shows Select active, and a one-finger pan then moves the photo with no extra tap.
- UX-11: Armed state is visible in two places (filled chip and status strip text) and is announced; disarming works via the active chip, Select and Back/Esc (not via a second finger, see UX-13).
- UX-12: Tap-tap placement creates the same measurement as a drag, with the first point shown and the strip prompting for the second; discarding the first point is possible without creating anything.
- UX-13: A second finger during a drag cancels only the in-flight placement and pinch/pan works; the tool stays armed and a placed first point is kept (the single behaviour; ADR-007 decision 4). The off-canvas / back-gesture release follows the same rule (cancel only the press, tool stays armed, A kept; table in 3.3; OC-9).
- UX-14: The "Keep tool" toggle (48 dp) locks the tool without any hidden gesture; long-press and double-tap on the chip also lock; the locked state is shown by badge and `stateDescription` "locked", and resets on leaving the editor.
- UX-15: Each tool chip is a `Role.RadioButton` with `selected` state inside a `selectableGroup`; the commit announcement "Distance 90 cm added, returned to Select" is delivered via a live region.
- UX-16: The active Select label has contrast at least 4.5:1 against the surface directly behind the label; inactive labels at least 4.5:1; disabled Reference exposes "Calibrate first".
- UX-17: Every tool chip and the lock toggle is at least 48 dp in both dimensions (24 dp absolute floor for any smaller decoration); the toolbar does not overlap the mic FAB or system bars.

Canvas (S3, S4)
- UX-18: Zoom-out reaches 25 percent of Fit and stops; `[-]` is disabled only there; Fit is enabled whenever not at Fit; at the floor the whole image is visible, centred, and at most 25 percent of the viewport width (area at most 6.25 percent of the viewport); at or above Fit the clamp keeps the far edge `slack` (25 percent of the viewport dimension) short of the viewport edge (ADR-006 decision 2). There is no "25 percent of the photo stays visible" invariant.
- UX-19: A point can be placed on the outermost pixel row and column by dragging a finger into the pasteboard, and the stored coordinate is exactly 0 or 1 on that axis.
- UX-20: The zoom controls' bounds and any status text do not intersect the photo's bounds at Fit; no status text covers the top 56 dp of the image.
- UX-21: No "estimation coming in a future update" text is shown anywhere when depth estimation is unavailable.
- UX-22: Zoom controls are at least 48 dp, expose the scale as a value ("Zoom 40 percent"), and the pasteboard frame has 3:1 contrast against the pasteboard.
- UX-23 (2.5.7): Any point can be moved to within 1 image px using handle tap + the existing nudge pad (steps 1 px, 10 px, 1 percent), with no dragging; the arrows are 48 dp and announce the new coordinate.
- UX-24: Hit radius for handles stays 24 dp or more on screen at 25 percent and 100 percent zoom.

Value editor (S5)
- UX-25: Tapping a measurement label (hit area at least 48 dp) opens the editor in one tap; TalkBack offers "Edit value".
- UX-26: The editor states both meanings ("Use as known distance", "Change label only") with a one-line consequence each; on a "Not calibrated" photo the first is preselected; on a calibrated photo the first edit of an editor session has neither preselected and Apply disabled until one is chosen, and later edits in that session preselect the last choice (section 6.1 rule).
- UX-27: Before Apply the preview shows the count of other measurements that will change and the scale before/after; after Apply a snackbar offers Undo and one undo restores everything.
- UX-28: Empty, zero, negative or non-numeric input disables Apply and shows the reason in the helper text without discarding input; `5'3"` parses to 160.0 cm.
- UX-29: An edited (label-only) value is visibly marked (pencil glyph) and its accessible name includes "edited"; dragging its endpoint asks for confirmation.
- UX-30: Every error in the editor table (section 6.3) has a visible message and at least one exit (Cancel, Back, [x], or retry).

Calibration (S6)
- UX-31: The status pill is one tap target at least 48 dp tall and opens Known distances in one tap, for 0, 1, 2 and 3+ distances.
- UX-32: The pill text matches the ladder in section 7.1 for each count, includes an icon, and conveys no state by colour alone.
- UX-33: A photo with a legacy calibration (BLE, manual reference, ticks, paper, EXIF) never shows "Not calibrated".
- UX-34: Each row shows direction icon, value, and residual text; a row with residual at least 3 times the mean (and over 3 percent) is flagged with "re-measure or remove?" and offers "Use anyway".
- UX-35: A user can add a third known distance from the sheet in at most 4 steps (tap Draw, drag, type value, Apply), and the sheet returns with the row listed.
- UX-36: Deleting a row shows the number of measurements affected, offers Undo, and Undo restores the previous constraint list and fit.
- UX-37: The words "lens distortion" do not appear as a headline in the pill or sheet.
- UX-38: Collinear-only distances show an informational hint and never block measuring.

Camera (S7)
- UX-39: Chips are shown only for lenses/optical stops the device exposes; digital stops are labelled "Digital"; no chip is shown when only one lens and no zoom range exist.
- UX-40: Each chip is at least 48 dp, announces lens name, ratio, focal length and selected state; the default selection is 1x or the last-used lens.
- UX-41: Switching shows the previous frame (never a black preview), disables the shutter during rebind, and five rapid taps end on the last lens.
- UX-42: A failed lens shows the inline message "<Lens> isn't available on this device right now", keeps the previous preview, and disables that chip with a reason.
- UX-43: Flash cycles Off/Auto/On with visible text and an announcement; each press changes exactly one step.
- UX-44: With permission permanently denied the screen offers "Open app settings" and "Pick from gallery instead" and a close; with a retryable denial it offers "Allow camera" and the same two.
- UX-45: Rotation or fold change keeps the selected lens and never shows content under system bars.
- UX-46: The ultra-wide hint "Wide angle distorts edges; fine for tight spaces" appears when ultra-wide is selected and is dismissible.

Global
- UX-47: Every error or empty state in sections 2 to 8 has at least one exit action or automatic recovery. **Automated where feasible:** each presentation model that renders a state (`SyncStatusSheetModel`, the camera dialog state, the value-editor error set, the known-distances presenter, the tool-strip state) declares `exits` for every state value, and `ExitPathAuditTest` iterates every value (sealed subclasses or enum entries, so a new state without exits fails to compile or fails the test); the remaining purely visual exits (scrim tap, Back) are verified by walking the tables once on the phone.
- UX-48: All new status/error text is exposed to TalkBack (live region or focus), not toast-only.
- UX-49: Contrast is at least 4.5:1 for text and 3:1 for icons/borders in light and dark themes for every new element.
- UX-50: Animations (lens crossfade, auto-return pulse, zoom to Fit) respect the system "remove animations" setting.

Added in triad repair 1 (UX-51 to UX-62)
- UX-51: While a paused retry is pending, the sync sheet shows an enabled "Retry now" (48 dp); pressing it runs one extra attempt immediately through the gate, does **not** consume the automatic budget (the "try n of 2" number does not move; five quick presses never reach Failed), never adds or restarts automatic retries, and coalesces without error if a run holds the gate.
- UX-52: The paused state is not persisted and the retry timer is in-process: after process death the next launch or periodic tick re-claims (silent clear of a provably dead owner, or a fresh two-try amber cycle), and an overdue retry runs once when the status UI is next observed; amber never sits indefinitely.
- UX-53 (WCAG 2.5.7): A zoomed canvas can be panned without dragging: the Pan pad (four 48 dp arrows in the pan row, disabled at the clamp edge with "At image edge"), TalkBack custom actions "Pan left/right/up/down" and desktop arrow keys; the pad lives in its own pan row (never in the status strip) and is available exactly when `viewScale >= FIT_SCALE` and `[Pan]` is on; strictly below Fit the toggle is disabled with "Nothing to pan" (one rule, 4.1).
- UX-54: A TalkBack user can place a point and finish a measurement: the "Place point with crosshair" custom action starts the crosshair session; each nudge announces the coordinate in percent and the live length; Place point announces "Point A placed" or "Distance 90 cm added, returned to Select".
- UX-55: Each tool chip exposes the TalkBack custom actions "Keep tool" and "Release tool" that toggle the same state as the visible toggle (long-press and double-tap stay as sighted shortcuts only).
- UX-56: On first open the camera dialog shows "Starting camera..." with a progress indicator, shutter and chips disabled, and after 5 s without a frame the all-lenses-failed screen.
- UX-57: A camera without a flash unit shows the flash button disabled with "Flash not available on this lens"; it is never hidden and the Off/Auto/On cycle skips it.
- UX-58: The Known distances sheet with zero rows shows the explanatory empty state with "Draw a known distance" and "Use a card or sheet of paper", never an empty list.
- UX-59: Pinch-to-chip uses hysteresis (highlight changes only after passing the midpoint by 5 percent of the span and holding 150 ms) and never rebinds a lens by itself; five alternating pinch micro-movements at a midpoint change the highlight at most once.
- UX-60: Dismissing the ultra-wide hint persists in Settings (per install) and the hint does not reappear after a restart; the OEM-hidden-lens line follows the same rule.
- UX-61: Undo snackbars (value edit, calibration delete) use an indefinite duration with a dismiss action when touch exploration or switch access is enabled and the long duration otherwise; the toolbar Undo button always remains available.
- UX-62: The annotator's second toolbar row has no clipped, overlapping or sub-48 dp control at 360 dp width, at 200 percent font scale (text wraps, row may take three lines, toolbar at most 40 percent of a 640 dp-tall window or the zoom group collapses into one "Zoom" button) and in an RTL layout (order mirrors; the photo and its coordinates do not).

Added in triad repair 2 (UX-63 to UX-70)
- UX-63: One strip, one priority order (error, tool prompt or pending state, first-three-times hint, status text, voice hint, idle). With the view zoomed above Fit and a tap-tap point A pending, the strip shows "Point A placed. Tap point B" with its Cancel and the pan row shows the four arrows at the same time; a lower-priority message returns when a higher one clears.
- UX-64: Back with a tool armed disarms first (discarding a pending point; two or more unfinished multi-step points ask "Discard N unfinished points?" first) and does not leave the editor; Back in Select with nothing pending leaves through the existing leave check; an open sheet, dialog or nudge session consumes Back before either; a predictive-back gesture commits the same step and an abandoned gesture changes nothing.
- UX-65: A pending tap-tap point A shows a visible Cancel (48 dp) in the strip; it discards A, keeps the tool armed and restores the tool prompt.
- UX-66: Tapping an Area or Angle value selects the measurement, offers no "Edit value" action and shows "Only distance values can be edited"; a Distance label opens the editor; a Label annotation keeps its existing text editing (default; OC-19).
- UX-67: The editor's unit chip sets only the entry unit of that editor session (default the display unit); it never changes the toolbar's display unit or the stored metres; a unit typed in the text wins for that entry.
- UX-68: At 360 dp, 200 percent font scale and RTL, the first toolbar row, the sync row and sheet, the value editor sheet, the Known distances rows and the camera chips and top bar have no clipped text, no control below 48 dp, no overlap and no horizontal scroll (9a).
- UX-69: Sync uses one action vocabulary: "Sync now" starts a user-initiated run (Idle, Failed), "Retry now" appears only in Paused; Retry now does not consume the automatic budget, "Sync now" from Failed restarts it, and "Try again" is not used for sync.
- UX-70: The first three times the editor opens, the idle strip reads "Voice notes: go Back to use the mic" (OC-12); it is lowest priority and never shown after three shows.

**Verification status of sizes and contrast (UNVERIFIED until Task 2.5b).** The Select-label contrast, the zoom-control and pill sizes and the pasteboard-frame contrast in section 1 are computed or estimated, not measured on the device. They stay UNVERIFIED until these tests run: `ToolbarContrastTest.selectLabel_should_HaveContrastAtLeast45AgainstToolbar_AndReferenceExposeCalibrateFirst` (X-16), `ToolbarLayoutBoundsTest.chipsAndLockToggle_should_Be48dpAndNotOverlapMicFabOrSystemBars` (X-17), `ZoomControlsTest.zoomControls_should_Be48dpExposeValueAndFrameHave3to1Contrast` (X-22), `ThemeContrastTest.newTokensAndElements_should_MeetContrastInLightAndDark` (X-49), `CalibrationPillTest.pill_should_Be48dpAndOpenKnownDistancesSheetInOneTap_ForEachCount` (X-31) and the warning-token pair in `SyncStatusBadgeTest.pausedRow_should_UseWarningTokenWith45ContrastLightAndDarkAnd48dpAndOpenDetails` (X-04). Task 2.5b (and 1.7b, 3.6b for their rows) is where they first run; a failing computation changes the colour or size, not the criterion.

Total: 70 criteria (50 original + 12 added in triad repair 1 + 8 added in triad repair 2).

## 11. Findings: plan stories that contradict or under-specify this design

Contradictions (plan must change or the design must):

- F1 (calibration state, high). Plan 3.6 rewires `CalibrationNudgeBanner` and keys the ladder on constraints only. The control the owner sees is `CalibrationConfidenceBadge`/`CalibrationStatusBar` (grey "Not calibrated" pill, VERIFIED `CalibrationConfidenceBadge.kt:87`, `AnnotationEditorScreen.kt:1316`), which is a different component. And a constraint-only ladder would show "Not calibrated" for photos calibrated by the existing BLE/manual/ticks/paper methods. Design: the pill is the actionable entry (UX-31) and legacy methods keep their labels (UX-33). Story 3.6 and 3.6a need this.
- F2 (ladder rung 2). Plan 3.6 defines 1 and "3 non-collinear" rungs; the requirement and this design need a defined state for exactly 2 distances (no LOO possible, provisional SCALE needs N >= 3). Design states "agree within X%". Plan 3.3/3.6a should add this rung.
- F3 (Select label contrast). Plan 2.5b fixes the label with `onPrimaryContainer` on a filled chip. In the screenshot the fill covers only the icon; the label sits on the dark toolbar, so a dark `onPrimaryContainer` label reproduces the failure. Either extend the fill behind the label or use a light label (this design). 
- F4 (lock affordance geometry and drag alternative). Plan 2.6 puts a 48 dp lock icon "on the active chip"; the chip itself is about 48 by 64 dp and its tap means disarm, so a nested 48 dp target is impossible without overlap. Design: separate "Keep tool" toggle in the action row plus long-press/double-tap shortcuts. Also ADR-005 promises nudge as the 2.5.7 alternative for moving points, but no plan story builds it (S4, UX-23).
- F5 (zoom controls placement). Plan 2.5b allows "a dedicated column or pasteboard margin". On a 360 dp portrait phone a column costs canvas width; the design chooses a row in the toolbar's second row (rail at 600 dp or more). Plan should adopt that (2.5b test asserts non-intersection either way).
- F6 (mic FAB overlap). The app-level mic FAB overlaps the annotator toolbar's second row near Redo (10852.png). No story addresses it, and Stories 2.5b/2.6a add more controls to that row. Needs a story or an explicit decision to hide the FAB in the editor.

Under-specified (missing states the design needs):

- F7 (sync, auto-retry and escalation). Plan 1.7 specifies the amber "Sync paused - we'll retry automatically" text but no story schedules the automatic retry, caps it, or defines the escalation to a red "Couldn't sync" with Try again/Details (research/ux.md section 4). Without it the amber state is a dead end. Needs a task in 1.4/1.7.
- F8 (queued follow-up display). Plan 1.4 has a `RerunRequest` but no UI for it; this design shows "One more sync will run after this". Research suggested deferring it in v1; either drop UX-03 or add to 1.7a.
- F9 (second press feedback). Plan 1.6a says "neutral message and no state change" but does not define the message or channel; design uses a polite live-region announcement only (UX-02).
- F10 (tool disarm paths). Plan 2.2/2.3 define commit and second-finger cancel but not abandoning an armed tool, tap-tap first-point discard, off-canvas drag cancel, or "tap active chip disarms". Added in 3.3 (UX-11, UX-12).
- F11 (known-distance round trip). Plan 3.6b says "arm DISTANCE then open the value editor" but with auto-return the new measurement is merely selected. It needs a one-shot "pending known distance" flag so the editor opens automatically and Cancel removes the drawn line and reopens the sheet (7.3).
- F12 (camera). Plan 4.4 covers permanently denied but not the retryable denial, revoked-while-open, or all-lenses-failed screens; and does not cover the held-frame crossfade, the dismissible hint, or pinch-to-chip sync (8.2, 8.3; UX-41, UX-44, UX-46).
- F13 (value editor errors). Plan 3.5 specifies the editor and preview but not save-failure, implausible-ratio warning, zero-length-segment, or the "edited" marker (6.3; UX-29, UX-30).
- F14 (touch target audit). Existing targets below 48 dp: sync Error row (plan 1.7b covers), the calibration pill (about 19 dp tall), the zoom buttons (about 32-36 dp, UNVERIFIED), measurement labels and handles (plan 3.5a covers labels; handles need 2.4b scope). The pill and zoom buttons have no explicit task.
- F15 (amber token). Plan 1.7 requires a "warning (amber) token"; this pass did not find one in `MaterialTheme.colorScheme` use in the sync UI (a grep of the sync UI and theme returned nothing, UNVERIFIED). If none exists, a theme token pair with 4.5:1 contrast in light and dark must be added in Story 1.7b.

No flow in this design lacks an exit path or an error state. The plan's own stories lack exit/error coverage in F7, F10, F11, F12 and F13.

### Resolution status (added in the Phase 3 repair pass; details in `implementation/plan.md` Repair Log)

| Finding | Covered by | Note |
|---------|------------|------|
| F1 | Story 3.6, Tasks 3.6a/3.6b | pill is the entry; legacy labels kept |
| F2 | Story 3.3 (N=2), Story 3.6 ladder | |
| F3 | Story 2.5, Task 2.5b | light label, not `onPrimaryContainer` |
| F4 | Task 2.6a (Keep tool), Task 2.3d (existing nudge pad) | S4 rewritten to reuse `AnnotationNonDragMode` |
| F5 | Story 2.5, Task 2.5b | adopted |
| F6 | Task 2.5c | FAB hidden in the editor |
| F7 | Task 1.7c, ADR-001 decision 14 | two retries then red |
| F8 | Tasks 1.7a/1.7b | follow-up line kept |
| F9 | Task 1.6a | live-region only |
| F10 | Story 2.3, ADR-007 | |
| F11 | Tasks 2.2b, 3.6c | `pendingKnownDistance` |
| F12 | Task 4.4c | |
| F13 | Task 3.5c | |
| F14 | Tasks 1.7b, 2.4b, 2.5b, 3.5a, 3.6b | |
| F15 | Task 1.7b | token pair added |

### Triad repair 1 (UX lens gaps, 2026-11-14)

| Triad gap | Covered by |
|-----------|------------|
| 1 UX-18 / S3 flow 1 said 25 percent stays visible | UX-18 and section 4.2 step 1 rewritten to ADR-006 decision 2 |
| 2 48 dp drag-measure threshold unvalidated | Task 2.3b log counters and Task 2.7a pass criteria (plan); owner confirmation OC-6 |
| 3 long-press/double-tap vs TalkBack | Custom actions "Keep tool"/"Release tool" (3.1, 3.4, UX-55) |
| 4 jargon "Just relabel"; preselect conflict | "Change label only" and the one preselection rule (6.1, UX-26) |
| 5 paused sync: no Retry now, durability, cause copy | 2.1 wireframe and copy table, 2.2 step 5, 2.3 table (UX-51, UX-52) |
| 6 WCAG 2.5.7 pan; TalkBack placement | Pan pad (4.1, UX-53); crosshair custom action (3.4, UX-54) |
| 7 missing states (a)-(f) | 8.2 step 4 and 8.3 (loading, no flash, hysteresis, hint persistence), 7.2 empty state, 6.1 snackbar duration, 4.1 second row at 360 dp / 200 percent / RTL (UX-56 to UX-62); landscape small phones: the same flow layout rule, rail only from 600 dp |
| 8 UNVERIFIED sizes/contrast | Unchanged: still measured by Task 2.5b computed-contrast tests; labelled UNVERIFIED in section 1 |
| 9 inconsistent cancel semantics | Single table in 3.3, OC-9 |
| 10 FAB hidden, no voice capture in editor | Recorded in 3.1, OC-12 |
| 11 hysteresis and hint persistence | 8.2 step 4 (UX-59, UX-60) |
| UX-47 only a manual walk | UX-47 now has `ExitPathAuditTest` over presentation-model states (Task 1.8a) |

### Triad repair 2 (UX lens gaps, 2026-11-14)

| Triad gap | Covered by |
|-----------|------------|
| 1 pan pad and prompts share the strip slot | 3.5 strip priority table, pan row in 4.1 (UX-63) |
| 2 pan pad visibility stated three ways | one rule in 4.1 (`viewScale >= FIT_SCALE` and `[Pan]` on), UX-53, ADR-006 decision 2 |
| 3 Back with an armed tool; predictive back | 3.3 table rows, 3.5 Back order (UX-64) |
| 4 no visible Cancel for pending point A | 3.3 table, 3.5 (UX-65) |
| 5 value editor scope; unit chip meaning | 6.0 (UX-66, UX-67, OC-19) |
| 6 200 percent / 360 dp / RTL beyond the second row | 9a (UX-68) |
| 7 three sync action labels; budget semantics | 2.1 vocabulary rule, 2.2 step 5 (UX-69, UX-51, OC-3 changed) |
| 8 live region on entering Paused | 2.4, UX-04 |
| 9 contrast and sizes UNVERIFIED | recorded in section 10 with the exact test ids; Task 2.5b |
| 10 camera copy and flash cycle minors | no change: UX-42, UX-44 and UX-57 already cover them; the loading and failed screens share copy by design (an unavailable camera is the same state to the owner) |
| 11 no in-editor hint that voice is after Back | 3.1 voice hint (UX-70) |
| 12 UX-47 automates presentation-model states only | no change: the visual exits (scrim, Back) stay a one-time phone walk, already stated in UX-47 |
