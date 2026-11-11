# Research: what the repo already has (depth, sensors, calibration)

All VERIFIED by reading files on origin/main (worktree at this branch's base) unless noted. Paths under `kmp/src/`.

## Calibration model

- `commonMain/.../model/ImageAnnotation.kt`: `Calibration(method, pixelsPerMeter, confidencePercent, basisVersion)`; `CalibrationBasis` has `LEGACY_DECODE_RELATIVE = 0` and `NATIVE_PIXELS = 1` only. `CalibrationMethod` lists `NONE, BLE_LASER, MANUAL_REFERENCE, ARCORE_DEPTH, LIDAR_DEPTH, EXIF_FOCAL, MONOCULAR_ML`.
- `calibration/CalibrationService.computeFromReference`: one reference line, one scalar. PR #638 (not on main) adds basis 2 `NATIVE_PIXELS_LENS`, `MultiPointCalibration`, `RadialLens`, `LensMeasurement`, `Calibration.lens` (k, ticks, residual), column `image_annotations.calibration_lens_json`, sidecar key `calibrationLens` (VERIFIED from the branch diff stat and design.md).
- `calibration/CalibrationFallbackChain` (BLE, manual, ARCore/LiDAR, EXIF, ML) is constructed only in `commonTest/.../CalibrationFallbackChainTest.kt`; no production construction found by grep (`CalibrationFallbackChain(` appears only in its own definition and tests). So the "chain" is dead code in the shipped app today. Consequence: the depth and EXIF methods have never produced a user-visible calibration. They should be rewired or removed deliberately (plan epic 6).

## Sensors

- `platform/sensor/MotionSensorProvider` (interface; `NoOp`, `AndroidMotionSensorProvider`, `IOSMotionSensorProvider`) emits `ImageSensorData` with pitch/roll/bearing/GPS at about 10 Hz; `snapshotSensorData(timeoutMs=500)` is used at capture in `AndroidCameraProvider` (line 134).
- Sensor data is attached only when the image came from the in-app capture (`ImageImportService` reads `tempFile.sensorData`, line ~119). Gallery/existing images get an empty `ImageSensorData`. Impact: the tilt warning (below) can never fire for annotate-existing (#639) images.
- Tilt warning: `AnnotationEditorScreen.kt` lines ~369-376: `abs(pitchDeg) > 15.0 || abs(rollDeg) > 10.0`, text "Camera tilted - measurements may be inaccurate. Use a reference object for calibration." The same predicate is duplicated in `commonTest/.../GpsTaggingTest.kt` (a private copy, so the test does not guard the real UI code; recommended: extract to a shared function, plan story 2.4).

## Depth

- `DepthSensorProvider` (`isAvailable`, `acquireDepthFrame(): Either<SensorError, DepthFrame?>`, "at the current moment"); `NoOpDepthProvider`; `androidMain/.../ARCoreDepthProvider.kt` (ARCore session, `ArCoreApk.checkAvailability`); `iosMain/.../IOSLidarDepthProvider.kt` (reads `sceneDepth`). `DepthFrame` = row-major `depthMapMm`, `confidenceMap` 0-255, width, height.
- `platform/ml/MonocularDepthEstimator` + `androidMain/.../OnnxMonocularDepthEstimator.kt` (ONNX Runtime, `depth_anything_v2_small.onnx`, memory gate 3 GB, API 26+); `DepthModelDownloadWorker`.
- `AnnotationEditorViewModel` holds `depthMap: FloatArray?` ("Relative depth map, normalized 0 to 1", VERIFIED comment), `depthModelUiState` (Absent, Downloading, Ready, Failed), `runDepthEstimation(imageBitmap, tapPoint)` delegating to `DepthEstimationCoordinator`.
- The banner "Depth model ready - estimation coming in a future update" renders when the model is Ready and `onEstimate == null` (`AnnotationEditorScreen.kt` ~1552). `ScreenRouter.kt:559` passes `onEstimateDepth = null`, so the button path is never shown in production (VERIFIED).

## What "estimation coming in a future update" actually needs

1. A production caller for `runDepthEstimation` (the router lambda) and a consumer of `state.depthMap`.
2. A metric interpretation: the stored map is normalized 0 to 1 relative depth, but `CalibrationService.computeFromMLDepth` treats map values as metres (VERIFIED mismatch). Needs either a metric model or to be demoted to "relative depth, used only to flag where an object looks closer than the reference" (ordinal use, see ADR-003).
3. A pinhole scale with `f_px` instead of `imageWidthPx / depth` (research/depth-sources.md, section 1).
4. A decision on where depth plugs into `Calibration`: ADR-003 says depth never replaces a manual reference; it supplies D (camera-to-plane distance) or per-object height h to the correction in ADR-002, stored as an additive `Calibration.geometry` block, not as a new `pixelsPerMeter` source.

## Existing tests relevant to this plan (grep of origin/main)

Calibration and annotate (business/commonTest): `calibration/CalibrationBasisServiceTest`, `db/CalibrationBasisMigrationTest`, `commonTest/calibration/CalibrationFallbackChainTest`, `commonTest/annotate/AnnotationCoordinateTransformTest`, `ui/annotate/{AnnotationPlacementTest, AnnotationEditorUnitTest, AnnotationEditorPersistenceTest, ExifOrientationTest, UndoHistoryTest, ...}`. Robolectric: `androidUnitTest/.../ui/annotate/{AnnotationEditorScreenRobolectricTest, CalibrationSheetTest, CalibrationNudgeBannerTest, AnnotationNonDragModeTest, InProgressAnnotationCanvasGestureTest, ...}`. Library: `ui/library/{LibraryViewModelStateTest, LibraryViewModelActionsTest, LibrarySketchFilterTest, LibraryFilterSqlParityTest, LibraryAnnotatedJoinTest, LibrarySessionTest, LargeLibraryBatchingTest}` plus `androidUnitTest/.../ui/library/{LibraryParityTest, LibraryScreenFilterTest}` and `androidInstrumentedTest/.../LibraryE2ETest`. Flow style: `androidUnitTest/.../wiring/HomeSketchFlowEntryPointTest.kt`.
Not on main (in flight): `LensDistortionSimulationTest`, `CalibrationLensPersistenceTest` (#638).
Gaps: no golden measurement tests on main; no test that the tilt predicate used by the UI is the one tested; no end-to-end "Library -> annotate -> calibrate -> measure -> reopen" flow; no tests for zoom/pan hit-testing (the zoom/pan PR was not among open PRs in `gh pr list` at this time, so its number and state are UNKNOWN; see plan.md dependencies).
