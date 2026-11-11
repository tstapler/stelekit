# Feature Research: Annotator Unit Preferences & UX Fixes

## Feature Map

### F-1: Dead Space Fix
- **Impact:** Visual polish, usability — calibration badge and toolbar are mispositioned.
- **Effort:** Very low (1–2 lines: pass `contentWindowInsets = WindowInsets(0)` to `Scaffold` or add `consumeWindowInsets()` to the column).
- **Risk:** None — no logic change, only layout.

### F-2: Unit Dropdown Wired
- **Impact:** Core UX correctness — currently the unit selector does nothing, which is confusing.
- **Effort:** Low–medium: new ViewModel method + state field + wire in Screen + confirm all measurement rendering uses the new state field.
- **Risk:** Low. Need to audit every call site that reads `state.imageAnnotation?.unit` for rendering measurement labels to ensure they all switch to `state.selectedUnit`.

### F-3: Unit Persisted Per Annotation
- **Impact:** Users don't re-select unit when reopening an annotation.
- **Effort:** Low — `setDisplayUnit()` calls `imageAnnotationRepository.saveImageAnnotation(updated)` with the new unit.
- **Risk:** Low. Must use `Either` error handling per repo contract. Must not block UI (wrap in a `launch` or handle `suspend` correctly from ViewModel scope).

### F-4: Global Default Unit Setting
- **Impact:** Users who always work in feet/inches don't re-select every new session.
- **Effort:** Low — one `Settings.getString/putString` call each way.
- **Risk:** Very low. Settings interface already in place and battle-tested.

### F-5: Default Units Changed to FEET_INCHES
- **Impact:** Aligns the default with the user's stated preference and likely the majority of construction/renovation use cases.
- **Effort:** Trivial — change `ImageAnnotation.unit` default from `METERS` to `FEET_INCHES`, update `"image_meter_default_unit"` default fallback string.
- **Risk:** Low. Existing saved annotations retain their stored unit on reopen; only new annotations and new sessions are affected.

### F-6: Depth Model "Ready" State UX Fix
- **Impact:** Users who download the model see confirmation it downloaded, not confusion that it failed.
- **Effort:** Very low — text change in `DepthEstimationPanel` or `AnnotationEditorScreen` for the `Ready + onEstimate == null` branch.
- **Risk:** None.

### F-7: DepthModelDownloader SUCCEEDED→Failed Race Fix
- **Impact:** Eliminates spurious "model file went missing" error on first download.
- **Effort:** Low — add retry loop (up to 2s, 100ms intervals) in `DepthModelDownloader.apply()` before emitting `Failed` on `SUCCEEDED+!isModelReady()`. Add unit test `DepthModelDownloaderRaceTest`.
- **Risk:** Low. Bounded retry window; no infinite loop risk.

---

## Interaction Between Features

- F-2 and F-3 share the same `setDisplayUnit()` ViewModel method — do both in one implementation.
- F-4 is called from within `setDisplayUnit()` (write to settings on change) and `initialize()` (read from settings on open). Keeps unit preference wiring in one place.
- F-5 affects `ImageAnnotation` model default and the settings key fallback string — both trivial one-line changes.
- F-6 and F-7 are independent of each other and of F-1 through F-5.

---

## Existing Infrastructure That Makes This Easy

- `Settings` interface: clean, already injected into `AnnotationEditorScreen` via parameter.
- `AnnotationEditorViewModel`: already has `platformSettings` reference and uses it.
- `ImageAnnotationRepository.saveImageAnnotation()`: already exists and returns `Either<DomainError, Unit>`.
- `DepthModelDownloader.apply()`: single function, isolated to Android; straightforward to add retry logic.
- `DepthEstimationPanel` or the screen's conditional rendering: already checks `onEstimate != null`.
