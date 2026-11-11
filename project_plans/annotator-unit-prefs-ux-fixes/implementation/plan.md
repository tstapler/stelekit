# Implementation Plan: Annotator Unit Preferences & UX Fixes

**Project:** `annotator-unit-prefs-ux-fixes`
**Source:** Backlog item f2dde580-0986-4123-ac98-a04c7f5599fb

---

## Overview

Seven discrete changes, all in the annotator subsystem. No new dependencies, no DB schema changes, no migrations. All changes are in KMP `commonMain` except the depth model race fix (Android-only `androidMain`).

---

## Task Breakdown

### Task 1 — Fix Dead Space (Layout — `AnnotationEditorScreen.kt`)

**Root cause correction:** The requirements.md claims an "inner Scaffold." There is none. The dead space is caused by the outer app `Scaffold`'s inset consumption stacking with the annotator `Scaffold`'s default `contentWindowInsets`. Verify by checking if the annotator `Scaffold` passes `contentWindowInsets`.

**Change:** Pass `contentWindowInsets = WindowInsets(0)` to the annotator's `Scaffold` composable, OR apply `Modifier.consumeWindowInsets(paddingValues)` to the content `Box`/`Column` to prevent double application. Verify visually (screenshot test or emulator run).

**Files:** `AnnotationEditorScreen.kt`

**Estimate:** 1h

---

### Task 2 — Add `selectedUnit` State + `setDisplayUnit()` to ViewModel

**Changes in `AnnotationEditorViewModel.kt`:**

1. Add `selectedUnit: MeasurementUnit = MeasurementUnit.FEET_INCHES` to `AnnotationEditorState`.
2. In `initialize(imageAnnotation)`: set `state.selectedUnit = imageAnnotation.unit` (preserves per-annotation stored unit).
3. Add `fun setDisplayUnit(unit: MeasurementUnit)` method:
   - Optimistically update `state.selectedUnit`.
   - Launch `imageAnnotationRepository.saveImageAnnotation(currentAnnotation.copy(unit = unit))` in `viewModelScope` with error handling.
   - Write `platformSettings?.putString("image_meter_default_unit", unit.name)`.

**Files:** `AnnotationEditorViewModel.kt`

**Estimate:** 2h

---

### Task 3 — Wire `onUnitSelect` in Screen + Audit Render Sites

**Changes in `AnnotationEditorScreen.kt`:**

1. Replace `onUnitSelect = {}` with `onUnitSelect = { viewModel.setDisplayUnit(it) }`.
2. Audit every `state.imageAnnotation?.unit` read that feeds measurement label rendering. Replace with `state.selectedUnit` (which is initialized from `imageAnnotation.unit` in Task 2 and updated on change).
3. The `displayUnit = state.imageAnnotation?.unit` parameter to `AnnotationToolbar` must become `displayUnit = state.selectedUnit` (non-nullable now that it's always present in state).

**Files:** `AnnotationEditorScreen.kt`

**Estimate:** 1.5h

---

### Task 4 — Global Default Unit Setting on New Annotation Creation

**Where new `ImageAnnotation` objects are created:** Likely `ScreenRouter.kt`, `AnnotationEditorViewModel.kt`, or a repository factory. Find the construction site and apply the settings read there.

**Change at creation site:**
```kotlin
val defaultUnit = platformSettings
    ?.getString("image_meter_default_unit", "FEET_INCHES")
    ?.let { runCatching { MeasurementUnit.valueOf(it) }.getOrNull() }
    ?: MeasurementUnit.FEET_INCHES

ImageAnnotation(
    ...,
    unit = defaultUnit,
)
```

**Files:** Wherever `ImageAnnotation(...)` is constructed for a new annotation (search for `ImageAnnotation(` in `ScreenRouter.kt` and ViewModel).

**Estimate:** 1h

---

### Task 5 — Change `ImageAnnotation.unit` Default to `FEET_INCHES`

**Change in `ImageAnnotation.kt`:**
```kotlin
val unit: MeasurementUnit = MeasurementUnit.FEET_INCHES,  // was METERS
```

This ensures serialization round-trips and in-memory defaults use feet/inches without settings lookup.

**Also update `AnnotationEditorState.gridRefUnit`:** This must stay `METERS` (GRID_REF dialog excludes FEET_INCHES). Do NOT inherit from `selectedUnit`.

**Files:** `ImageAnnotation.kt`

**Estimate:** 0.5h

---

### Task 6 — Depth Model "Ready" State UX Copy Fix

In `AnnotationEditorScreen.kt` (or `DepthEstimationPanel.kt`), find the branch that renders when `depthModelUiState == DepthModelUiState.Ready && onEstimateDepth == null`.

**Change:** Replace the ambiguous "Estimation coming soon" label (which reads as an error) with:
> "Depth model ready — estimation coming in a future update."

Ensure the disabled button uses a **distinct visual treatment** from the error/absent state so users understand the model is downloaded.

**Files:** `AnnotationEditorScreen.kt` (search for "Estimation coming soon")

**Estimate:** 0.5h

---

### Task 7 — DepthModelDownloader SUCCEEDED→Failed Race Fix + Test

**Change in `DepthModelDownloader.kt` (`androidMain`):**

In `apply(infos)`, when `WorkInfo.State.SUCCEEDED && !isModelReady()`, instead of immediately emitting `Failed`:

```kotlin
WorkInfo.State.SUCCEEDED -> {
    var retries = 0
    while (!isModelReady() && retries < 20) {
        delay(100L)
        retries++
    }
    if (isModelReady()) ModelState.Ready
    else ModelState.Failed("The model file went missing after downloading. Try again.")
}
```

**New test `DepthModelDownloaderRaceTest`** in `androidUnitTest` (Robolectric, headless):
- Mock `isModelReady()` to return false twice then true.
- Assert that `apply()` emits `Ready`, not `Failed`.
- Assert that if `isModelReady()` never returns true, `Failed` is eventually emitted.

**Files:** `DepthModelDownloader.kt`, new `DepthModelDownloaderRaceTest.kt`

**Estimate:** 2h (including test)

---

## Sequencing

Tasks 1, 6, 7 are independent and can be done in any order.
Tasks 2, 3, 4, 5 form a logical unit (unit preference system) and should be done together:
- Task 5 first (change default) → Task 2 (ViewModel state) → Task 3 (wire screen) → Task 4 (new annotation creation).

Suggested order: 5 → 2 → 3 → 4 → 1 → 6 → 7.

---

## Testing Strategy

- **Unit tests:** `DepthModelDownloaderRaceTest` (Task 7). `AnnotationEditorViewModelTest` for `setDisplayUnit()` behavior: verify state update, verify repo save called, verify settings written.
- **Screenshot tests:** Before/after for dead space fix (Task 1) using existing Roborazzi infrastructure.
- **Manual smoke:** Run annotator on `Pixel_Fold` AVD, change unit, close and reopen annotation, verify unit restored. Open a new annotation, verify FEET_INCHES default.

---

## Out of Scope (Confirmed)

- Actual depth inference implementation (bitmap sourcing not wired).
- Settings screen UI entry for default unit (follow-on item).
- Migrating existing saved annotations to FEET_INCHES.
- iOS/desktop depth model (Android-only).
