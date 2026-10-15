# Research: annotate-ui-test-coverage

## Existing Test Infrastructure

### ViewModel tests already present
`commonTest/kotlin/dev/stapler/stelekit/annotate/AnnotationEditorViewModelTest.kt` — 315 lines covering:
- Tool selection, distance/angle/area commits
- Undo/redo (stack limit, calibration undo)
- LABEL tool (show/confirm/dismiss)
- Delete annotation
- Min-length guard

**Branch gaps in ViewModel (JaCoCo 52%):**
- `commitAnnotation` — GRID_REF branch (shows dialog, not tested end-to-end)
- `updateCalibration` — invalid pixelsPerMeter guard, no prior calibration path
- `injectMeasurementFromDevice` — entire method (0 test coverage)
- `addTag` / `removeTag` — persistence failure paths, blank/duplicate tag guards
- `addPoint` AREA polygon close-by-proximity branch
- `initialize` — `hasBeenMutated` TOCTOU guard (requires concurrent access test)
- `parseGridRefLengthToMeters` — partially tested but not all 6 unit variants

### DepthEstimationCoordinator
`commonTest` — no test file exists. The coordinator is created inside `AnnotationEditorViewModel`, but the class is `public` and directly instantiable with injected `MonocularDepthEstimator`.

Key branches needing coverage:
- `runDepthEstimation`: guard when already running (`isDepthInferenceRunning`)
- `initResult.isLeft()` → error path
- `depthResult.fold` → Left (estimation failure), Right (success)
- Right path with `tapPoint != null` (triggers `onCalibrationReady`)
- Right path with `tapPoint == null` (no callback)

### UI Composable tests
`jvmTest/kotlin/dev/stapler/stelekit/ui/annotate/`:
- `AnnotationEditorScreenKeyboardTest.kt` — keyboard shortcut tests
- `AnnotationEditorScreenTagWiringTest.kt` — tag panel wiring
- `AnnotationToolbarShortcutTest.kt` — toolbar shortcuts
- `DisabledTintContrastTest.kt` — accessibility

**0% coverage composables (no test file):**
- `BleDevicePanel.kt` — sealed state machine, `formatMeters` helper, `rememberCoroutineScope`-based scanning
- `CalibrationSheet.kt` — `showHowTo` toggle, peer calibration branch
- `CalibrationNudgeBanner.kt` — `isFirstUse` boolean branch
- `PermissionRationaleDialog.kt` — 3 dialog variants

## Test Placement Decision

Per project `CLAUDE.md` and testing best practices:
> "A Compose-behavior test (dialog gating, text/content assertions, click handlers) that doesn't need true pixel rendering belongs in `androidUnitTest` (Robolectric, `./gradlew testDebugUnitTest`), not `jvmTest`."

Robolectric is headless (no X11) — ideal for `BleDevicePanel`, `CalibrationSheet`, `CalibrationNudgeBanner`, and `PermissionRationaleDialog` content assertions and click-handler tests.

`BleDevicePanel.formatMeters` is a `private fun` in the file — it can be tested by extracting it to a package-private `internal fun`, or tested via a wrapper test at the panel state display level. The cleanest approach (without modifying source) is to test the state transitions at the Robolectric level, exercising the `formatMeters` string via `ReadingReceived` state display. Alternatively, if a small refactor is approved, moving `formatMeters` to `internal` enables direct testing from `androidUnitTest`.

## `DepthEstimationCoordinator` Test Strategy

The coordinator takes a `MonocularDepthEstimator` interface. A fake can be created in `commonTest`:
```kotlin
class FakeMonocularDepthEstimator(
    private val initResult: Either<DomainError, Unit> = Unit.right(),
    private val depthResult: Either<DomainError, FloatArray> = FloatArray(100).right(),
) : MonocularDepthEstimator {
    override suspend fun initialize() = initResult
    override suspend fun estimateDepth(bitmap: ImageBitmap) = depthResult
}
```
Tests must use `runTest` with `advanceUntilIdle()` to let the coordinator's coroutine complete.

## `injectMeasurementFromDevice` Strategy

The `ExternalMeasurementDevice` interface exposes `measurementFlow(): Flow<MeasurementReading>`. A fake can emit a single reading synchronously. The test must:
1. Commit a DISTANCE annotation first.
2. `setActiveDevice(fakeDevice)`.
3. Call `injectMeasurementFromDevice()`.
4. `advanceUntilIdle()`.
5. Assert calibration updated to BLE_LASER and annotation `bleDeviceId` set.

## `parseGridRefLengthToMeters` Coverage

The function is `internal` so it's directly callable from `commonTest`. Only 2-3 unit variants appear covered currently. Adding a property-based test with `Arb.double(0.1, 1000.0)` across all units would raise branch coverage for the `when` expression significantly.

## GRID_REF Dialog Flow Coverage

`confirmGridRefCalibration()` has guards:
- `points.size < 2` → early return
- `parseGridRefLengthToMeters` returns null → early return  
- `lengthMeters <= 0.0` → early return
- `CalibrationService.computeFromReference` returns null → early return
- Happy path: calibration applied

Each guard is a separate branch. Tests using `makeViewModel()` and calling `confirmGridRefCalibration()` with varying setup states will cover these.

## Existing Patterns to Follow

- `AnnotationEditorViewModelTest` uses `InMemoryMeasurementAnnotationRepository` — follow this pattern.
- `NewGraphFlowTest.kt` / `AddGraphAppOwnedTest.kt` are the reference examples for Robolectric Compose tests in `androidUnitTest`.
- `kotest-property` is available in `commonTest` for property-based tests.

## Risk: `BleDevicePanel` global `MeasurementDeviceRegistry`

`BleDevicePanel` calls `MeasurementDeviceRegistry.getAllDevices()` inside a `LaunchedEffect`. This is a global singleton. Robolectric tests of this component must either mock or stub it, or only test states that don't reach the `LaunchedEffect` execution path (e.g. inject an initial state). The component has internal `var panelState by remember { mutableStateOf(...) }`, so test access is through UI semantics only — content assertion tests are the safest approach.
