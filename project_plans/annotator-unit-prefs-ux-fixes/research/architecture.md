# Architecture Research: Annotator Unit Preferences & UX Fixes

## Relevant Files (Confirmed Paths)

| File | Path |
|------|------|
| `AnnotationEditorScreen.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt` |
| `AnnotationEditorViewModel.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorViewModel.kt` |
| `AnnotationToolbar.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationToolbar.kt` |
| `MeasurementAnnotation.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/MeasurementAnnotation.kt` |
| `ImageAnnotation.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ImageAnnotation.kt` |
| `Settings.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/Settings.kt` |
| `PlatformSettings.android.kt` | `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.android.kt` |
| `DepthModelDownloader.kt` | `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ml/DepthModelDownloader.kt` |
| `ScreenRouter.kt` | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ScreenRouter.kt` |

---

## Issue 1: Dead Space at Top of Annotator

**Requirements.md claim:** An inner `Scaffold` applies `WindowInsets.safeDrawing` double-stacking on top of insets consumed by the outer app layout.

**Actual code (confirmed by read):** There is **no inner `Scaffold`** in `AnnotationEditorScreen.kt`. The screen uses a single outer `Scaffold`. The dead space is likely caused by the outer `Scaffold` using `ScaffoldDefaults.contentWindowInsets` (the default) while the outer app `NavHost` or parent `Scaffold` *also* applies padding, producing double-counted insets. The fix is to pass `contentWindowInsets = WindowInsets(0)` to suppress duplicate inset consumption at the annotator level, or to pass `WindowInsets.Zero` to the content column.

---

## Issue 2: Unit Selection No-Op

**`onUnitSelect = {}`** at `AnnotationEditorScreen.kt` ~line 600 — an explicit no-op with a TODO comment.

**`AnnotationEditorState`** (in ViewModel) has no `displayUnit` or `selectedUnit` field. The only unit field is `state.imageAnnotation?.unit`, which is read-only from the persisted entity.

**`AnnotationToolbar`** already has `onUnitSelect: (MeasurementUnit) -> Unit` in its signature and renders the full `UnitDropdown`.

**`ImageAnnotation.unit`** is a persisted field (`MeasurementUnit`, default `METERS`). Changing unit requires updating the entity via `imageAnnotationRepository.saveImageAnnotation(updated)`.

**To fix:**
1. Add `selectedUnit: MeasurementUnit` to `AnnotationEditorState` (initialized from `imageAnnotation.unit` on `initialize()`).
2. Add `setDisplayUnit(unit: MeasurementUnit)` to `AnnotationEditorViewModel` — updates `selectedUnit` in state, saves updated `ImageAnnotation` to repo, and writes `"image_meter_default_unit"` to `platformSettings`.
3. Wire `onUnitSelect = { viewModel.setDisplayUnit(it) }` in `AnnotationEditorScreen`.
4. All measurement label rendering must use `state.selectedUnit` (falling back to `imageAnnotation.unit`) rather than `state.imageAnnotation?.unit` directly.

---

## Issue 3: Global Default Unit Setting

**`Settings` interface** already supports `getString(key, default)` / `putString(key, value)`. The key `"image_meter_calibrated_before"` is the only existing annotator setting key.

**New key:** `"image_meter_default_unit"` (String, default `"FEET_INCHES"`).

**Flow:**
- On `AnnotationEditorViewModel.initialize()`: if `imageAnnotation.unit == METERS` (the old default), override with `platformSettings.getString("image_meter_default_unit", "FEET_INCHES").toMeasurementUnit()`.
- On `setDisplayUnit(unit)`: persist to `imageAnnotation.unit` via repo, AND write `platformSettings.putString("image_meter_default_unit", unit.name)`.

---

## Issue 4: Depth Model "Not Available" After Download

**Root cause in requirements.md:** `onEstimateDepth = null` in `ScreenRouter.kt` line 497 is **intentional** — bitmap sourcing for inference is not yet wired. The panel already renders a disabled "Estimation coming soon" button when `onEstimate == null`. Users read this as download failure.

**Secondary race condition (confirmed):** `DepthModelDownloader.apply()` maps WorkManager `SUCCEEDED` state to `Failed` if `isModelReady()` returns false due to filesystem sync lag. The fix is a short retry with delay (up to 2s) before emitting `Failed`.

**ScreenRouter.kt lines ~440-497:** The `onEstimateDepth = null` comment explicitly says "pending bitmap-sourcing work". The panel needs clearer UI copy to signal "downloaded, not yet usable" vs. "download failed."

---

## `MeasurementUnit` Values

```kotlin
enum class MeasurementUnit {
    METERS, CENTIMETERS, MILLIMETERS,
    FEET, INCHES, FEET_INCHES   // FEET_INCHES = US construction style "5' 4 1/4\""
}
```

Note: The `GRID_REF` calibration dialog **excludes** `FEET_INCHES` (decimal input only). This restriction must be preserved.

---

## Settings Infrastructure

- KMP interface (`Settings.kt`): `getBoolean`, `putBoolean`, `getString`, `putString`, `containsKey`
- Android: `EncryptedSharedPreferences` (AES256), store name `"stelekit_secure_prefs"`
- Currently used in annotator for `"image_meter_calibrated_before"` (Boolean)
- New key will be a String: `"image_meter_default_unit"`
