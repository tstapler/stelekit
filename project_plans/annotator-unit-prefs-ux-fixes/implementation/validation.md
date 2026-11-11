# Validation: Annotator Unit Preferences & UX Fixes

## AC-1: No dead space above calibration badge
- **Gate:** Roborazzi screenshot test shows calibration badge flush to top of content area, no visible blank region between navigation bar and badge.
- **Manual check:** Run `bazel run //kmp:desktop_app` or `Pixel_Fold` AVD, open an annotation — verify no dead space.

## AC-2: Unit dropdown changes displayed unit immediately
- **Gate:** `AnnotationEditorViewModelTest` — call `setDisplayUnit(FEET)`, assert `state.selectedUnit == FEET` immediately after.
- **Manual check:** Open annotator, tap unit button, change unit — all measurement labels update instantly.

## AC-3: Unit is persisted per annotation
- **Gate:** `AnnotationEditorViewModelTest` — call `setDisplayUnit(FEET)`, assert `imageAnnotationRepository.saveImageAnnotation()` called with `unit = FEET`.
- **Manual check:** Change unit to FEET, navigate away, reopen same annotation — unit shows FEET.

## AC-4: Global default unit setting written and read
- **Gate:** `AnnotationEditorViewModelTest` — call `setDisplayUnit(FEET)`, assert `platformSettings.getString("image_meter_default_unit")` returns `"FEET"`. Create a new annotation — verify it opens with `FEET`.
- **Code review:** Confirm `initialize()` reads `"image_meter_default_unit"` when constructing a new annotation.

## AC-5: Default unit is FEET_INCHES
- **Gate:** `ImageAnnotation()` with no args → `unit == FEET_INCHES`. Settings key fallback string is `"FEET_INCHES"`.
- **Code review:** `ImageAnnotation.kt` default parameter, settings read fallback.

## AC-6: Depth model panel clearly distinguishes downloaded vs. not-yet-usable
- **Gate:** `AnnotationEditorScreenTest` (or screenshot test) — when `depthModelUiState = Ready` and `onEstimateDepth = null`, rendered text contains "ready" and does NOT contain text suggesting download failure.
- **Manual check:** Download model on Pixel_Fold AVD — panel shows "Depth model ready — estimation coming in a future update."

## AC-7: Depth model SUCCEEDED→Failed race eliminated
- **Gate:** `DepthModelDownloaderRaceTest` — mock `isModelReady()` returning false×2 then true; assert emitted state is `Ready`, not `Failed`.
- **Gate:** Same test with `isModelReady()` always false — assert `Failed` eventually emitted within 2.5s.

---

## Pre-Submission Checklist

- [ ] `bazel test //kmp:business_tests` — green
- [ ] `bazel test //kmp:jvm_tests` (with display) — no new failures
- [ ] `./gradlew testDebugUnitTest` — green (covers `DepthModelDownloaderRaceTest`)
- [ ] Screenshot test for dead-space fix added and passing
- [ ] `gridRefUnit` field confirmed still defaults to `METERS`
- [ ] `AnnotationToolbar` dropdown visibility not broken (null-gating preserved)
