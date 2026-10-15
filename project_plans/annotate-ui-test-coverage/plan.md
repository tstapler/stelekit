# Plan: annotate-ui-test-coverage

## Objective

Raise `ui.annotate` line coverage from 46.4% → ≥ 75% and branch coverage from 27.3% → ≥ 50% by adding targeted tests for the ViewModel branch gaps, the zero-covered UI components, and `DepthEstimationCoordinator`.

## Work Streams

### Stream 1 — ViewModel branch gap closure (commonTest)

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/annotate/AnnotationEditorViewModelTest.kt` (extend) or a new companion file `AnnotationEditorViewModelBranchTest.kt`.

Tasks:
1. **GRID_REF dialog flow** — Tests for `addPoint` with GRID_REF tool triggering dialog; `confirmGridRefCalibration` success path; each early-return guard (blank text, zero length, `computeFromReference` returning null by giving degenerate points); `dismissGridRefDialog`.
2. **`updateCalibration` guards** — invalid pixelsPerMeter (NaN, Infinity, ≤ 0) rejected; no prior calibration → no calibration snapshot created; count=0 → `calibrationChangeMessage` not set.
3. **`injectMeasurementFromDevice`** — no device set (no-op); no DISTANCE annotation committed (no-op); device emits reading → calibration updated to BLE_LASER, annotation `bleDeviceId` set. Uses `FakeExternalMeasurementDevice` + `runTest` + `advanceUntilIdle`.
4. **`addTag` / `removeTag`** — blank tag ignored; sanitisation (spaces/special chars → hyphens); duplicate tag not added; tag removed when present; no-op when not present.
5. **`addPoint` AREA close-by-proximity** — polygon with ≥4 points where last tap is within `0.03` tolerance of first → committed; outside tolerance → in-progress continues.
6. **`initialize` TOCTOU guard** — second `initialize` call with updated ImageAnnotation does not overwrite annotations user already committed.

**Estimated new tests: ~20**

### Stream 2 — `parseGridRefLengthToMeters` exhaustive unit tests (commonTest)

**New file:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/annotate/ParseGridRefLengthTest.kt`

- One parametrized `@Test` (or `checkAll` with `Arb.double`) per unit: METERS, CENTIMETERS, MILLIMETERS, FEET, INCHES, FEET_INCHES.
- Edge cases: blank string → null; negative → null; zero → null; non-numeric → null.
- FEET_INCHES treated same as FEET (same conversion factor).

**Estimated new tests: ~12**

### Stream 3 — `DepthEstimationCoordinator` (commonTest)

**New file:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/annotate/DepthEstimationCoordinatorTest.kt`

Setup: define `FakeMonocularDepthEstimator` sealed fake with configurable `initResult` and `depthResult`.

Tests:
1. `runDepthEstimation` guard — does nothing when `isDepthInferenceRunning=true`.
2. Init failure → `depthEstimationError` set, `isDepthInferenceRunning=false`.
3. Estimate failure → `depthEstimationError` set.
4. Estimate success, `tapPoint=null` → `depthMap` stored, `onCalibrationReady` NOT invoked.
5. Estimate success, `tapPoint` provided, valid image dims → `onCalibrationReady` invoked with `MONOCULAR_ML` calibration.
6. `updateDepthModelUiState` propagates to state.
7. `close()` cancels scope — subsequent `runDepthEstimation` is a no-op (no crash).

**Estimated new tests: ~7**

### Stream 4 — `BleDevicePanelState` and `formatMeters` (commonTest or androidUnitTest)

**New file:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/annotate/BleDevicePanelStateTest.kt`

Since `formatMeters` is `private`, test it indirectly through a visible wrapper, or add a note that it can be tested by promoting to `internal`. For now, test `BleDevicePanelState` sealed class construction (compile-time), and a `formatMeters` helper extracted to `internal`.

Minor production refactor (1-line change): change `private fun formatMeters(...)` → `internal fun formatMeters(...)` in `BleDevicePanel.kt`. This enables direct unit testing without a Compose render.

Tests for `formatMeters`:
- `1.5` → `"1.500 m"`
- `0.05` → `"50 mm"`
- `0.999` → `"999 mm"`
- `1.0` → `"1.000 m"`

**Estimated new tests: ~4 + 1 line source change**

### Stream 5 — Robolectric UI tests for zero-covered composables (androidUnitTest)

**Files in** `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/annotate/`:

#### `CalibrationNudgeBannerTest.kt`
- `isFirstUse=true` → renders "Set a scale" title and "Calibrate Now" button
- `isFirstUse=false` → renders "Scale not set" compact text and "tap to calibrate" button
- Click `onCalibrateClick` invoked in both variants

#### `PermissionRationaleDialogTest.kt`
- `CameraPermissionRationaleDialog` — button text "Continue" when not permanent; "Open Settings" when permanent
- `BlePermissionRationaleDialog` — 3 body text variants (isPermanentlyDenied, requiresLocation, default)
- `DeleteAnnotationConfirmationDialog` — with label, without label (text assertions)

#### `CalibrationSheetTest.kt`
- Without `peerCalibration` → no "Use scale from" button
- With `peerCalibration` → "Use scale from X" button visible; click invokes `onUsePeerCalibration`
- "How to calibrate" toggle expands/collapses step list

**Estimated new tests: ~12**

## Implementation Order

1. Stream 2 first (pure functions, no risk, quick wins)
2. Stream 3 (DepthEstimationCoordinator — isolated, no Compose)
3. Stream 1 (ViewModel branch gaps — extends existing test class)
4. Stream 4 (1-line source change + unit tests)
5. Stream 5 (Robolectric — requires Android test infra)

## Coverage Target Validation

Expected coverage after all streams:
- `AnnotationEditorViewModel.kt`: 73% → ~87% (52% → ~82% branch)
- `DepthEstimationCoordinator.kt`: 24% → ~90% (0% → ~90% branch)
- `parseGridRefLengthToMeters` (internal): 0% → 100%
- `BleDevicePanel.kt`: 0% → ~20% (formatMeters covered; LaunchedEffect scanning remains untestable without global mock)
- `CalibrationNudgeBanner.kt`: 0% → ~80%
- `PermissionRationaleDialog.kt`: 36% → ~85%
- `CalibrationSheet.kt`: 0% → ~75%

Overall `ui.annotate` package: 46.4% → estimated ~72–78% line, ~50–55% branch — meeting the 75%/50% targets.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| `DepthEstimationCoordinator` test flakiness with `advanceUntilIdle` | Use `UnconfinedTestDispatcher` to avoid timing issues |
| `BleDevicePanel` `LaunchedEffect` reaches `MeasurementDeviceRegistry.getAllDevices()` global in Robolectric | Do not write Robolectric tests that trigger the `LaunchedEffect`; test state transitions by calling `formatMeters` directly or keep Robolectric tests to static layout assertions only |
| `injectMeasurementFromDevice` uses `flow { }.first()` — coroutine timing in tests | Fake `measurementFlow` returns `flowOf(reading)` so `first()` terminates immediately |
| `CalibrationService.computeFromReference` can return null for degenerate inputs | Test negative confirms that null return causes `confirmGridRefCalibration` to no-op |
