# Requirements: annotate-ui-test-coverage

## Background

The `ui.annotate` package (23 files, 2648 lines) is the annotation editor for SteleKit's image measurement feature. As of branch `stelekit-merges @ e337d58c1e`, it has only 46.4% line coverage and 27.3% branch coverage — the lowest coverage of any major feature package.

Key file coverage gaps identified by JaCoCo:
- `AnnotationEditorScreen.kt` — 915 lines, 31% line / 23% branch
- `BleDevicePanel.kt` — 209 lines, **0%** line / **0%** branch
- `CalibrationSheet.kt` — 110 lines, **0%** coverage
- `CalibrationNudgeBanner.kt` — 52 lines, **0%** coverage
- `AnnotationEditorViewModel.kt` — 436 lines, 73% line / 52% branch (gaps in calibration paths, BLE device injection, depth estimation)
- `DepthEstimationCoordinator.kt` — 58 lines, 24% line / **0%** branch
- `PermissionRationaleDialog.kt` — 94 lines, 36% coverage

The incremental coverage gate introduced in PR #402 will flag any new changes to this package if coverage does not improve, so closing this gap is a prerequisite for sustainable future development.

## Goals

1. Raise `ui.annotate` line coverage to ≥ 75% (from 46.4%).
2. Raise `ui.annotate` branch coverage to ≥ 50% (from 27.3%).
3. Cover all ViewModel branches (calibration edge cases, BLE device injection, depth estimation happy/error paths, GRID_REF dialog flow).
4. Cover `DepthEstimationCoordinator` fully (init failure, estimate failure, estimate success with and without tapPoint).
5. Cover UI components (`BleDevicePanel`, `CalibrationSheet`, `CalibrationNudgeBanner`, `PermissionRationaleDialog`) via Robolectric (`androidUnitTest`) since they do not require pixel rendering.

## Non-Goals

- 100% coverage of `AnnotationEditorScreen.kt` (Compose Desktop + screenshot-level tests belong in `jvmTest` and require X11; not in scope for this item).
- Modifying production source code to make tests pass (tests must exercise the code as-is).
- Performance or visual regression tests for the annotation canvas.

## Acceptance Criteria

- AC-1: `AnnotationEditorViewModel` branch coverage reaches ≥ 80% (measured by JaCoCo on `jvmTest` or `businessTest`).
- AC-2: `DepthEstimationCoordinator` branch coverage reaches ≥ 80%.
- AC-3: `parseGridRefLengthToMeters` is tested for all 6 unit variants (METERS, CENTIMETERS, MILLIMETERS, FEET, INCHES, FEET_INCHES) plus blank/negative input edge cases.
- AC-4: `BleDevicePanel` `formatMeters` helper is unit-tested (≥1m path, <1m path).
- AC-5: `BleDevicePanelState` sealed states can be constructed and compared in tests without crash (compile-time validation).
- AC-6: `CalibrationNudgeBanner` `isFirstUse=true` / `isFirstUse=false` branches covered via Robolectric `@Test` in `androidUnitTest`.
- AC-7: `PermissionRationaleDialog` — all three dialog variants (`CameraPermissionRationaleDialog`, `BlePermissionRationaleDialog`, `DeleteAnnotationConfirmationDialog`) rendered in Robolectric tests.
- AC-8: `CalibrationSheet` peer-calibration branch (with and without `peerCalibration`) covered via Robolectric.
- AC-9: `AnnotationEditorViewModel.injectMeasurementFromDevice` happy path covered (device emits reading → calibration updated).
- AC-10: All new tests pass on `bazel test //kmp:business_tests` or `./gradlew testDebugUnitTest` with no display requirement.

## Constraints

- New ViewModel tests must use `InMemoryMeasurementAnnotationRepository` (no DB setup).
- New `DepthEstimationCoordinator` tests must inject a fake `MonocularDepthEstimator`.
- UI tests for `BleDevicePanel`, `CalibrationNudgeBanner`, `PermissionRationaleDialog`, and `CalibrationSheet` must go in `androidUnitTest` (Robolectric) — not `jvmTest` — to avoid X11 dependency.
- Tests must not use `rememberCoroutineScope` in classes under test (see architecture constraints in `CLAUDE.md`).
- `BleDevicePanel` can be tested structurally (state machine / `formatMeters`) without a full Compose render test, since its `LaunchedEffect` scanning logic depends on `MeasurementDeviceRegistry` global state.
