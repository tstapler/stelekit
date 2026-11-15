# Owner verification checklist: Epic E3 (measurement value editing and multi-distance calibration)

CI closes SM3a (value editing). SM3b (calibration accuracy) is closed only by your real photos and tape measurements: synthetic tests cannot, and `AccuracyStudyVerdictTest` rejects an "SM3 met" verdict without the evidence table. The default is safe until you finish: the GRADIENT tier ships off (`ConstraintTuning.GRADIENT_DEFAULT_ON = false`) and SCALE with leave-one-out is the shipped tier.

## A. Take the photos (Task 3.8a, D-E3-01)

You need at least 5 real photos: your door-frame photo plus at least 4 more. Each needs at least 6 tape-measured distances.

- Vary the scene: a door frame, a desk, a shelf, a wall with a poster, a floor or table at an angle. Include at least one photo with a visible tilt and one near-fronto-parallel.
- Use the camera you actually measure with, at zoom 1 (no pinch, no crop). Do not edit or crop the saved JPEG.
- In each photo pick 6 or more distances in different places and directions (some near the edges, some near the centre, some horizontal, some vertical, some diagonal). Each distance should be a visible object edge or two marks you can tap precisely. Prefer 5 to 25 cm objects, because that is where SM3b's tolerance bites.
- Put the tape or ruler beside the object when you take the photo so you can re-read it later. Do not move the camera between taking the photo and measuring.

## B. Record ground truth

For each distance, read the tape twice, to the nearest millimetre, and write the mean in metres. A reading error of 1 mm is already 0.6% on 18 cm.

Per photo, write one JSON file in `accuracy-fixtures/real/` (git-ignored; it never enters the public repo):

```json
{
  "photos": [
    {
      "id": "door-frame",
      "set": "A",
      "doorFrame": true,
      "widthPx": 4000,
      "heightPx": 3000,
      "distances": [
        { "id": "d1", "p1": { "x": 0.21, "y": 0.34 }, "p2": { "x": 0.33, "y": 0.35 }, "trueMeters": 0.182 }
      ]
    }
  ]
}
```

- `x`, `y` are fractions of the photo width and height (the same normalised coordinates the annotation editor stores). Reading taps out of the app is not automated: take them from the photo's annotation sidecar, or tap the same points in an image viewer that shows pixel coordinates and divide by the photo size.
- `set`: use `"A"` for the door-frame photo plus at least 2 more (these tune the thresholds) and `"B"` for at least 2 further photos never used for tuning (these decide). Do not move a photo between sets after you have seen results.
- `widthPx`/`heightPx` are the photo's pixel size after EXIF rotation, as the editor shows it.
- `exifFocal35mmEq` is optional.

## C. Run the study

From the repo root, on a machine with the Gradle wrapper:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
STELEKIT_STUDY_FIXTURES=$PWD/accuracy-fixtures/real \
  ./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.calibration.RealPhotoHoldOutStudyTest' -i
```

The test needs no display. It prints the held-out table (leave-one-out per photo, then the set A to B transfer) and writes it to `kmp/build/reports/accuracy-study-table.txt`. The test passes only when SM3b is met on your photos; a failing run is a valid result, not a bug, and its message lists the reasons. Without the fixtures the test skips.

SM3b PROPOSED pass rule (OC-13, you confirm or change it): on set B, the shipped default tier's mean absolute held-out error is strictly below single-scale and at most 2% of the true length, with a 0.5 cm floor for objects under 25 cm (2% of 18 cm is 0.36 cm, so the 0.5 cm floor applies there). No single photo may be more than 10% worse than single-scale. To change the tolerance edit `StudyTolerance` in `ConstraintStudyRunner.kt` and `AccuracyStudyVerdictTest`/requirements; no production code changes.

Not covered by the runner: the "best existing calibration (ticks, ruler, paper)" baseline from the plan. It needs ruler ticks or a paper reference per photo and is left to a follow-up if you want it in the table.

## D. Record the verdict

Edit `project_plans/sync-lock-and-image-annotator/implementation/accuracy-study.md`:

1. Add one row per photo to the evidence table (`| Photo | Set | Door frame | Tape distances | Single-scale MAE (cm) | Default-tier MAE (cm) |`; Door frame is `yes` or `no`, Set is `A` or `B`).
2. Paste the printed table below it.
3. Replace the last line with exactly one of:
   - `SM3 met: shipped default tier has lower mean absolute error than single scale on the real hold-out`
   - `SM3 not met for distortion: default stays SCALE-with-LOO (a noise-averaging gain only); the owner was told`

Verify with `./gradlew :kmp:jvmTest --tests '*AccuracyStudyVerdictTest'`. The audit rejects "met" with fewer than 5 photos, fewer than 6 distances on any photo, no door-frame photo or an empty set B, and requires the verdict line to be the last line. Fill the PR checklist item "SM3: met / not met"; it may not stay blank.

## E. Flip the GRADIENT default (only on "SM3 met")

Only a "met" verdict may do this, and only if the study's GRADIENT row (`gradient-tier`) beat `default-tier` on set A and set B.

1. In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/calibration/ConstraintTuning.kt` set `GRADIENT_DEFAULT_ON = true`.
2. Update `GradientDefaultOffTest` (U-E3-32), which asserts the off default, to assert the new default.
3. Set the tier thresholds (`LOO_MARGIN`, `MIN_POSITION_SPAN_FRACTION`, `MAX_TILT_DEG`, noise floor) from set A only, then rerun section C and confirm set B still passes.
4. Rollback is the Settings toggle `calibration_gradient_tier`, or reverting the constant.

If the verdict is "not met", leave the constant `false`; the owner can still try the tier through the Settings toggle.

## F. Device checks for the E3 UI (needs the phone, CI cannot cover)

Build a debug APK from branch `feat/measurement-edit-and-calibration`.

- [ ] TalkBack: tapping a measurement label opens the value editor in one tap (TalkBack offers "Edit value"); an edited measurement is announced as edited; the calibration pill reads the tier and the estimated accuracy, not a bare check; the Known distances rows announce distance, value and state.
- [ ] 360 dp wide screen: the value editor sheet, the calibration pill and banner, and the Known distances rows have no clipped text, no control under 48 dp, no overlap and no horizontal scroll (UX-68).
- [ ] 200% font scale: the same screens still show every label (wrapping is fine), the editor's Save and Cancel stay reachable with the keyboard open.
- [ ] RTL (Settings, Developer options, Force RTL layout direction): the first toolbar row mirrors, the photo does not, and the sheets keep their order.
- [ ] Value edit round trip on a real photo: edit a measurement's value, close and reopen the photo, the value persists with the edited marker; one undo restores it.
- [ ] Old-build compatibility: open the same photo in release v0.113.3; measurements still read correctly.
