# Adversarial Review: Annotator Unit Preferences & UX Fixes

## Blocker 1: Dead-Space Root Cause Unverified

**Issue:** The requirements.md diagnoses the dead space as an "inner Scaffold" double-applying insets, but there is no inner Scaffold in the current code. The actual root cause is unconfirmed.

**Risk:** Applying `contentWindowInsets = WindowInsets(0)` to the wrong composable level could leave the content overlapping system UI (status bar, gesture nav) on some devices.

**Mitigation:** Before submitting a fix, run the annotator on the `Pixel_Fold` AVD and confirm the dead space is eliminated without content overlapping system bars. Add a Roborazzi screenshot test to catch regressions.

---

## Blocker 2: `setDisplayUnit()` Persistence — Repo `Either` Error Path

**Issue:** The plan calls for `launch { imageAnnotationRepository.saveImageAnnotation(updated) }` inside `setDisplayUnit()`. If this returns `Either.Left(DomainError...)`, the state is already updated optimistically but the repo save silently failed.

**Risk:** The user sees the unit changed but on reopen it reverts to the old unit, with no error shown.

**Mitigation:** Handle the `Either.Left` case — log the error and optionally show a transient `EditorError` state to the user. At minimum, don't silently discard the failure.

---

## Blocker 3: Settings Key `"image_meter_default_unit"` Invalid MeasurementUnit Value

**Issue:** `platformSettings.getString("image_meter_default_unit", "FEET_INCHES")` will return whatever string was previously written. If a future code change renames the enum or if the key was manually corrupted, `MeasurementUnit.valueOf(it)` throws `IllegalArgumentException`.

**Mitigation:** Wrap in `runCatching { MeasurementUnit.valueOf(it) }.getOrDefault(MeasurementUnit.FEET_INCHES)`. The plan already includes this, but confirm it's in the implementation.

---

## Concern 1: `gridRefUnit` Must Not Inherit Global Default

The `AnnotationEditorState.gridRefUnit` field is used for the calibration GRID_REF dialog, which excludes `FEET_INCHES`. If the global default flows into `gridRefUnit`, the dialog will break (or silently exclude the value, leaving the user confused).

**Mitigation:** Confirm `gridRefUnit` is initialized to `METERS` and is not touched by `setDisplayUnit()`. The ViewModel must keep these two unit fields separate.

---

## Concern 2: `AnnotationToolbar.displayUnit` Null vs Non-Null Change

The plan changes `displayUnit = state.imageAnnotation?.unit` (nullable) to `displayUnit = state.selectedUnit` (non-nullable from state). If `AnnotationToolbar` has logic gating the dropdown on `displayUnit != null`, the dropdown may now always be visible even before an annotation is loaded.

**Mitigation:** Audit `AnnotationToolbar.kt` line ~207 for `if (displayUnit != null)` gating. If the dropdown visibility was intentionally null-gated, preserve that by using `displayUnit = state.selectedUnit.takeIf { state.imageAnnotation != null }`.

---

## Concern 3: Depth Model Race Fix — `delay()` in `apply()` Context

The `apply()` function is called from a coroutine collecting `WorkManager.getWorkInfosForUniqueWorkFlow()`. Adding `delay()` here suspends the collector — this is fine in a coroutine context. Confirm that `apply()` is called inside a `collect {}` block (not in a plain callback or `runBlocking`), ensuring `delay()` is cooperative and cancellable.

---

## Non-Blockers

- Task 6 (depth model copy fix) is a one-line change with zero risk.
- Task 5 (`ImageAnnotation.unit` default change) only affects new annotations and is trivially correct.
