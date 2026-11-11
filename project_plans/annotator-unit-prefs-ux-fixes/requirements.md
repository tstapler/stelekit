# Requirements: Annotator Unit Preferences & UX Fixes

**Project:** `annotator-unit-prefs-ux-fixes`
**Source:** Backlog item f2dde580-0986-4123-ac98-a04c7f5599fb

---

## Problem Statement

The image annotator (`AnnotationEditorScreen`) has four UX defects observed in production:

1. **Dead space at top** — a large blank area appears between the app navigation bar and the
   calibration status badge. The inner `Scaffold` inside `AnnotationEditorScreen` applies its own
   `WindowInsets.safeDrawing` content padding on top of insets already consumed by the outer app
   layout, producing double-counted top insets.

2. **Unit selection is a no-op** — the unit dropdown in `AnnotationToolbar` renders correctly and
   opens, but `onUnitSelect = {}` in `AnnotationEditorScreen` (line 598) is an explicit no-op.
   `AnnotationEditorViewModel` has no `setDisplayUnit()` method and no `selectedUnit` state field.
   Changing the unit in the dropdown has no effect.

3. **No global default unit setting** — the `Settings` interface is already in place
   (`platform/Settings.kt`) but no key exists for a preferred measurement unit. Each new
   `ImageAnnotation` defaults to `MeasurementUnit.METERS`. Users who always work in feet/inches
   must mentally re-select every session, with no way to persist their preference.

4. **Depth model "Estimation coming soon" is misleading** — after a user downloads the depth
   model, the panel transitions to `DepthModelUiState.Ready` but `onEstimateDepth = null` (wired
   intentionally in `ScreenRouter.kt` line 497 pending bitmap-sourcing work). The panel displays
   "Estimation coming soon" (half-alpha disabled button), which users read as "the download
   failed" or "feature unavailable." A secondary bug in `DepthModelDownloader.apply()` causes a
   SUCCEEDED WorkManager state to briefly map to `Failed` if `isModelReady()` returns false due to
   filesystem sync lag between the worker completing its file rename and the observer running.

---

## Acceptance Criteria

### AC-1: No dead space above calibration badge
The blank area between the app navigation bar and `CalibrationConfidenceBadge` is eliminated.
`AnnotationEditorScreen`'s inner `Scaffold` does not double-apply top window insets; the
calibration badge renders flush to the top of the content area.

### AC-2: Unit dropdown changes the displayed unit immediately
Selecting a unit in the `AnnotationToolbar` unit dropdown immediately updates all measurement
labels displayed on the annotation canvas and in the toolbar. `AnnotationEditorViewModel.setDisplayUnit(unit)`
exists and is wired to `onUnitSelect` in `AnnotationEditorScreen`. `AnnotationEditorState`
contains a `selectedUnit: MeasurementUnit` field used for display in preference over
`imageAnnotation.unit`.

### AC-3: Unit preference is persisted per annotation
When the user changes the unit, the new unit is saved back to `ImageAnnotation.unit` in the
repository so reopening the same annotation restores the last-used unit.

### AC-4: Global default unit setting
A settings key `"image_meter_default_unit"` (String, default `"FEET_INCHES"`) exists. When a new
annotation is initialized without a previously stored unit, the ViewModel reads this key from
`platformSettings` and applies it. When the user changes the unit inside the editor, the new unit
is also written to `"image_meter_default_unit"` as the new global default.

### AC-5: Default unit is feet and inches
The fallback default for `"image_meter_default_unit"` is `"FEET_INCHES"`, and
`ImageAnnotation.unit` defaults to `MeasurementUnit.FEET_INCHES` (not `METERS`). Existing
annotations without an explicitly stored unit will display in feet/inches after migration.

### AC-6: Depth model panel clearly distinguishes downloaded vs. not-yet-usable
When `DepthModelUiState.Ready` and `onEstimateDepth == null`, the panel shows a distinct label
that communicates the model *is* downloaded (not that an error occurred). Suggested text:
"Depth model ready — estimation coming soon." The label is visually distinct from the
"Download depth model" (Absent) state.

### AC-7: Depth model SUCCEEDED→Failed race eliminated
In `DepthModelDownloader.apply()`, when WorkManager emits `SUCCEEDED` and `isModelReady()`
returns false (filesystem lag), the downloader waits up to 2 seconds with a retry interval rather
than immediately emitting `Failed`. A unit test (`DepthModelDownloaderRaceTest`) verifies the
recovery path.

---

## Out of Scope

- Implementing actual depth inference (wiring `onEstimateDepth` with a real model run). The
  "coming soon" state remains intentional; this item only improves its labeling.
- Adding a unit preference UI in the app-level Settings screen (AC-4 adds persistence without a
  dedicated settings screen entry; a settings screen UI is a follow-on item).
- Migrating existing persisted `ImageAnnotation` records to `FEET_INCHES` (AC-5 sets the default
  going forward; existing saved annotations retain their stored unit on reopen unless the user
  changes it).
