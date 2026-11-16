# Owner verification checklist: Epic E2 (annotator interaction)

These items need the owner's phone and cannot run in CI. They are the remaining wall-clock blockers for closing Story 2.7 and the "owner-verified" half of Success Metric 2. Everything else in E2 is CI-green (see the PR/report for test evidence). Build a debug APK from branch `feat/annotator-modes-and-canvas` and install it.

## A. Task 2.7a: drag-measure threshold (`DRAG_COMMIT_DP`, OC-6; PROPOSED 48 dp)

Counters are persisted log lines (no coordinates, no photo path). Read them from the in-app Logs screen export, or `filesDir/logs/current.log`:

- `placement gesture=drag-measure`
- `placement gesture=aim-place`
- `placement undo-after-drag-measure` (an undo within 5 s of a drag-measure commit)

Procedure: on a real photo, at Fit and again at 2x zoom, perform 20 deliberate drag-measures (Distance armed, press at A, drag to B, lift) and 20 deliberate aim-places (press, aim with the loupe, release near the press). Count the log lines per batch.

Pass criteria:

1. At least 18 of the 20 drag-measures log `gesture=drag-measure`.
2. At most 1 of the 20 aim-places logs `gesture=drag-measure`.
3. `undo-after-drag-measure` appears for at most 3 of the 20 drag-measures.
4. You report no unnoticed wrong measurement.

If (2) or (3) fails, raise `DRAG_COMMIT_DP` in `PlacementClassifier.kt` in 8 dp steps and repeat; if (1) fails, lower it. If the finger-occlusion rate (3) stays high at every threshold, turn drag-measure off at 2x zoom and above (OC-5 fallback) and record it in the PR.

- [ ] Fit: drag-measure >= 18/20, aim-place misclassified <= 1/20, immediate undo <= 3/20
- [ ] 2x zoom: same three numbers
- [ ] Refining A right after a drag-measure (drag A's handle, loupe shows, one undo entry, strip "Drag an end to fine-tune")

## B. System-gesture inset on the phone (adversarial C11, ADR-006 decision 6)

The canvas is padded by `WindowInsets.systemGestures` left/right (Android). The inset value on the owner's phone is UNVERIFIED.

- [ ] With gesture navigation on, place a point on the outermost left and right pixel at Fit: no Back gesture triggers, stored x is 0.0 / 1.0.
- [ ] Press that starts in the inset gutter is not a placement; release in the gutter shows "Cancelled" and keeps the tool armed.
- [ ] With three-button navigation (inset 0) the layout looks unchanged.

## C. 12 MP jank (Task 2.7a, D-E2-01; PROPOSED thresholds OC-14)

Use the existing harness (`kmp/TESTING_README.md`, `scripts/gfxinfo-session.sh`) on the same device and script, before (pre-2.4a, commit `e3831960c4`) and after (this branch): scripted pinch Fit to 0.25 and back on a 12 MP photo.

- [ ] Baseline p95 frame time and jank rate recorded (closes the UNVERIFIED baseline)
- [ ] After: p95 <= 16.7 ms and jank < 5 percent, and within the same SLO bucket as the baseline
- [ ] Zoomed to 0.25: image width <= 25 percent of viewport width; whole image visible and centred
- [ ] Memory: no OOM or "app keeps stopping" when zooming out on the 12 MP photo (check `last-crash.txt` and the Logs screen)

## D. TalkBack (UX-54, UX-55, UX-53)

- [ ] Tool chips announce as radio buttons with selected state; Keep tool toggle announces "locked"
- [ ] Chip custom actions "Keep tool" / "Release tool" work
- [ ] "Place point with crosshair" canvas action: crosshair announced, arrows announce coordinates, Place point announces "Point A placed", second announces "Distance ... added, returned to Select"; no drag or double-tap-and-hold needed
- [ ] Pan custom actions and the Pan pad move the view

## E. Feel and layout

- [ ] One-handed: tap Distance, drag A to B once, back in Select with pan/zoom working (Success Metric 2)
- [ ] Select label is readable on the dark toolbar (owner screenshot regression F3)
- [ ] Toolbar at your usual font size and at largest font: nothing clipped; zoom row and pan row off the photo
- [ ] Mic FAB hidden in the editor, and the idle strip hint "Voice notes: go Back to use the mic" shows on the first three opens (OC-12: voice capture is unavailable while the editor is open)
- [ ] Settings: "Return to Select after each measurement" (on) and "Keep tools active" (off, Advanced) toggle and take effect on the next editor open
- [ ] Fold (Pixel Fold, if applicable): layout at both postures

## Status

All items above are open. None blocks the CI-green merge of E2; they close Story 2.7 "owner-verified".
