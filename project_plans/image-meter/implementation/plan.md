# Implementation Plan: SteleKit Image Meter

## Overview

This plan delivers native image annotation with scaled measurements inside SteleKit, replacing the ImageMeter Android app workflow. Annotated images become first-class `image_annotation` blocks in the Logseq graph, with measurement data queryable as block properties and accessible from a gallery view. External BLE laser rangefinders provide ground-truth calibration; ARCore depth and monocular ML depth estimation serve as progressively less accurate fallbacks.

## Technology Decisions

| Concern | Decision | Rationale | ADR |
|---|---|---|---|
| Image block type | `blockType = "image_annotation"` extending existing Block model | Reuses all existing block infrastructure: rendering, back-links, search, page linking | [ADR-001] |
| Measurement storage | SQLDelight tables (fast query) + JSON sidecar at `.stelekit/images/<uuid>.measure.json` (portability) | NFR-2/NFR-3: sidecar is the portable ground truth; SQLDelight serves gallery UX | [ADR-001] |
| Annotation canvas | Compose Canvas `DrawScope` + ZoomImage host (commonMain) | KMP-native, no third-party annotation lib; ZoomImage handles large-image subsampling | [ADR-002] |
| Camera capture | CameraK (KMP wrapper) with CameraX 1.5 androidMain fallback | Best available KMP abstraction; escape hatch to CameraX without touching commonMain | [ADR-002] |
| Photo import on Android | Android Photo Picker API (system overlay) | Google Photos Library API `photoslibrary.readonly` scope removed March 2025; Picker is the only supported path | [ADR-003] |
| BLE laser rangefinder | Kable (Android/iOS/macOS) behind `ExternalMeasurementDevice` interface | Coroutine-native; protocol implementations are pluggable via `MeasurementDeviceRegistry` | [ADR-004] |
| USB serial (OTG) | usb-serial-for-android (kai-morich fork), androidMain only | LGPL 2.1; only production-grade option; legal risk confirmed acceptable (dynamic linking) | [ADR-004] |
| ARCore depth | ARCore Depth API, androidMain, gated on `isDepthModeSupported()` | 8–10 cm absolute error; used as tertiary calibration fallback with explicit accuracy warning | [ADR-005] |
| Monocular ML depth | Depth Anything V2 ViT-S via ONNX Runtime (androidMain); Core ML (iosMain) | ~500–800 ms on Snapdragon 695; inference-on-demand only, never real-time; confidence badge displayed | [ADR-005] |
| Google OAuth | Credential Manager API (Android); ASWebAuthenticationSession (iOS); localhost redirect (JVM) | Modern replacement for deprecated GoogleSignIn; per NFR-4, tokens in platform credential storage | [ADR-003] |
| Google Drive export | Drive REST API v3 via Ktor (commonMain) | Drive Android API deprecated Dec 2018; Ktor already in project | [ADR-003] |
| Error handling | Arrow `Either` at all repository/service boundaries | Matches existing codebase convention; DomainError hierarchy extended with SensorError | — |
| All SQL writes | `DatabaseWriteActor` + `RestrictedDatabaseQueries` | Existing write-serialization contract; new tables follow existing pattern exactly | — |
| Image coordinate system | Normalized [0,1] space stored in SQLDelight; viewport transform applied at draw time | Resolution-independent; zoom/pan never invalidates stored annotations | [ADR-002] |

## Epic Structure

| # | Epic | Scope |
|---|---|---|
| 1 | Foundation | Data models, SQLDelight schema, repositories, JSON sidecar, image storage |
| 2 | Image Capture and Import | Camera on Android/iOS, file picker on desktop/web, Android Photo Picker + Google Drive import |
| 3 | Annotation Canvas | Compose Canvas overlay, tool state machine, line/polygon/angle/label/grid tools, undo |
| 4 | Calibration Engine | Manual reference-object marking, ARCore integration, EXIF math, monocular depth (ONNX) |
| 5 | BLE Laser Rangefinder | Kable integration, Leica DISTO + Bosch GLM GATT protocols, USB serial fallback, HID keyboard mode |
| 6 | SteleKit Integration | image_annotation blocks, block property sync, gallery view, tagging, back-links, block math references |
| 7 | Google Drive Integration | OAuth via Credential Manager, Ktor REST client, resumable upload, JSON sidecar export |
| 8 | Platform Sensors | GPS tagging, compass bearing, accelerometer pitch/roll for perspective correction |
| 9 | Polish and Performance | 30 fps annotation canvas verification, large image handling, offline-first audit, EXIF rotation fix |

---

## Epic 1: Foundation

**Goal**: Establish data models, SQLDelight schema, repository interfaces, and image storage conventions that all subsequent epics build on. No UI; testable entirely in `commonTest` and `jvmTest`.

**Stories**:

- Story 1.1: Domain models for ImageAnnotation, MeasurementAnnotation, Calibration [M]
  - Task 1.1.1: Create `model/ImageAnnotation.kt` in `commonMain` with `ImageAnnotation`, `Calibration`, `CalibrationMethod`, `ImageSensorData` data classes and enums
  - Task 1.1.2: Create `model/MeasurementAnnotation.kt` with `MeasurementAnnotation`, `NormalizedPoint`, `AnnotationType` and unit conversion helpers (`metersToDisplay(unit)`)
  - Task 1.1.3: Extend `DomainError` sealed interface with `SensorError` subtypes: `PermissionDenied`, `HardwareUnavailable`, `CaptureFailed`
  - Task 1.1.4: Add `validBlockTypes` entry `"image_annotation"` to the existing block type set and update block rendering dispatch table stub in `BlockItem.kt`
  - Task 1.1.5: Unit tests for unit conversion helpers and model construction (commonTest)

- Story 1.2: SQLDelight schema — `image_annotations` and `measurement_annotations` tables [M]
  - Task 1.2.1: Add `image_annotations` table DDL to `SteleDatabase.sq` with all columns from architecture spec; add `CREATE INDEX` on `block_uuid` and `page_uuid`
  - Task 1.2.2: Add `measurement_annotations` table DDL with FK cascade delete from `image_annotations`; add `CREATE INDEX` on `image_uuid`
  - Task 1.2.3: Write SQLDelight named queries: `selectAllImageAnnotations`, `selectImageAnnotationByUuid`, `selectImageAnnotationsByPage`, `selectImageAnnotationsByTag`, `insertImageAnnotation`, `updateImageAnnotation`, `deleteImageAnnotation`, `insertMeasurementAnnotation`, `selectMeasurementsForImage`, `deleteMeasurementsForImage`
  - Task 1.2.4: Add forwarding stubs for all INSERT/UPDATE/DELETE queries to `RestrictedDatabaseQueries` annotated `@DirectSqlWrite`
  - Task 1.2.5: Write and run schema migration (new migration number); verify `ciCheck` passes

- Story 1.3: Repository interfaces and in-memory implementations [M]
  - Task 1.3.1: Create `repository/ImageAnnotationRepository.kt` interface in `commonMain` with Flow-returning reads and `Either`-returning writes (annotated `@DirectRepositoryWrite`)
  - Task 1.3.2: Create `repository/MeasurementAnnotationRepository.kt` interface
  - Task 1.3.3: Implement `InMemoryImageAnnotationRepository` and `InMemoryMeasurementAnnotationRepository` for test use
  - Task 1.3.4: Register factories in `RepositoryFactory` and add fields to `RepositorySet`
  - Task 1.3.5: Add `WriteRequest` variants (`SaveImageAnnotation`, `SaveMeasurements`, `DeleteMeasurement`, `DeleteImageAnnotation`) to `DatabaseWriteActor`

- Story 1.4: SQLDelight production repository implementations [L]
  - Task 1.4.1: Implement `SqlDelightImageAnnotationRepository` following the `PlatformDispatcher.DB` dispatcher matrix (reads via `mapToList(DB)`, writes via `withContext(DB)`)
  - Task 1.4.2: Implement `SqlDelightMeasurementAnnotationRepository`
  - Task 1.4.3: Wire new repository writes through `DatabaseWriteActor` execute blocks using `RestrictedDatabaseQueries`
  - Task 1.4.4: Integration tests for CRUD round-trips using in-memory SQLite in `jvmTest`

- Story 1.5: JSON sidecar read/write service [M]
  - Task 1.5.1: Define sidecar JSON schema (version 1) and `@Serializable` data classes in `commonMain` under `db/sidecar/`
  - Task 1.5.2: Implement `ImageSidecarManager` with `writeSidecar(annotation, measurements)` and `readSidecar(uuid)` using `FileSystem.writeFileBytes()` / `readFileBytes()`; sidecar path `<graph>/.stelekit/images/<uuid>.measure.json`
  - Task 1.5.3: Implement `ImageSidecarIndexer.rebuildFromSidecars(graphPath)` that walks `*.measure.json` files and upserts into SQLDelight tables — recovery path when DB is lost
  - Task 1.5.4: Round-trip serialization tests for all `AnnotationType` variants (commonTest) [ADR-001 validation]
  - Task 1.5.5: Extend `FileSystem` interface with `readFileBytes(path): ByteArray` and `writeFileBytes(path, data)` if not already present; add platform `actual` implementations for `androidMain`, `iosMain`, `jvmMain`

- Story 1.6: Image file storage conventions [S]
  - Task 1.6.1: Define `ImageStoragePathResolver` in `commonMain` that returns `<graph>/assets/images/<yyyy-MM-dd>-<uuid-prefix>.jpg` from a graph path and UUID
  - Task 1.6.2: Add `ImageImportService.reservePath(graphPath, uuid)` that creates the `assets/images/` directory tree if absent
  - Task 1.6.3: Unit tests for path resolution and directory creation

---

## Epic 2: Image Capture and Import

**Goal**: Users can get images into the annotation editor from camera (Android/iOS), file picker (all platforms), Android Photo Picker (Android), and Google Drive file browse (Android/iOS/Desktop).

**Stories**:

- Story 2.1: `CameraProvider` expect/actual interface and sensor abstraction module [L]
  - Task 2.1.1: Create `platform/sensor/CameraProvider.kt` interface in `commonMain`: `capturePhoto(): Either<DomainError.SensorError, PlatformImageFile>`, `isAvailable: Boolean`
  - Task 2.1.2: Create `platform/sensor/SensorModule.kt` analogous to `RepositoryFactory`; wired per platform in entry points (`Main.kt`, Android `Application`)
  - Task 2.1.3: Implement `NoOpCameraProvider` (returns `HardwareUnavailable`) for platforms without camera (jvmMain import-only mode)
  - Task 2.1.4: Implement `AndroidCameraProvider` in `androidMain` using CameraK; handle `CAMERA` permission request flow
  - Task 2.1.5: Implement `IOSCameraProvider` in `iosMain` using CameraK AVFoundation wrapper
  - Task 2.1.6: Unit tests for `NoOpCameraProvider` (commonTest); smoke test on Android that permission check works without crash

- Story 2.2: EXIF orientation normalization [M]
  - Task 2.2.1: Implement `ExifOrientationFixer` in `androidMain` using `ExifInterface` to read orientation and rotate the `Bitmap` programmatically before saving to `assets/images/`
  - Task 2.2.2: Store corrected EXIF `FocalLength`, `FocalLengthIn35mmFilm`, `Make`, `Model` in `ImageAnnotation.sensorData` for later calibration use
  - Task 2.2.3: Test against Samsung Galaxy and Google Pixel EXIF behavior (jvmTest with sample EXIF-tagged JPEG fixtures)

- Story 2.3: Image import service and block creation [M]
  - Task 2.3.1: Implement `ImageImportService.import(tempFile: PlatformImageFile, graphPath: String): Either<DomainError, ImageAnnotation>` in `commonMain`; orchestrates copy → `ImageAnnotation` DB insert → sidecar write → block creation
  - Task 2.3.2: Implement block creation: creates a `Block` with `blockType = "image_annotation"`, Markdown content `![](../assets/images/<name>.jpg)`, and initial block properties (`::image-id::`, `::calibration::`, `::unit::`)
  - Task 2.3.3: For camera capture: auto-insert the block into today's journal page (US-4.6) via `JournalRepository.todayPage()` + `BlockRepository.addBlock()`
  - Task 2.3.4: Integration test: import a JPEG fixture → verify `image_annotations` row, sidecar JSON, and block all created (jvmTest)

- Story 2.4: Android Photo Picker integration [M] [ADR-003]
  - Task 2.4.1: Integrate `ActivityResultContracts.PickVisualMedia` (Android Photo Picker, API 33+ / backported via Play Services) in `androidMain`; no `READ_MEDIA_IMAGES` permission needed
  - Task 2.4.2: On selection, copy the content URI bytes to `assets/images/` via `FileSystem.writeFileBytes()`; never persist the content URI (temporary grant)
  - Task 2.4.3: Pass resulting `PlatformImageFile` to `ImageImportService.import()`
  - Task 2.4.4: Test on API 28 device (backport path) and API 34 device (native path)

- Story 2.5: Desktop file picker and webcam import [M]
  - Task 2.5.1: Implement desktop file picker in `jvmMain` using `JFileChooser` (or AWT FileDialog); filter for JPEG/PNG
  - Task 2.5.2: Implement `WebcamCameraProvider` stub in `jvmMain` using `javax.imageio` snapshot (live preview is out of scope for v1; capture single frame via JavaCV if available, else present file picker)
  - Task 2.5.3: Wire desktop file import into the `ImageImportService` pipeline

- Story 2.6: Web (WASM) file import [S]
  - Task 2.6.1: Implement `WebApiCameraProvider` in `wasmJsMain` using `navigator.mediaDevices.getUserMedia()` — capture one frame as Blob, convert to ByteArray, pass to `ImageImportService`
  - Task 2.6.2: Fallback: `<input type="file" accept="image/*">` for platforms where camera API is unavailable
  - Task 2.6.3: Smoke test in browser using Kotlin/Wasm test harness

---

## Epic 3: Annotation Canvas

**Goal**: A Compose Multiplatform annotation editor with a layered `ZoomImage` + `Canvas` architecture, supporting line, polygon, angle, text+leader, and grid tools with undo/redo. All interactions stored in normalized [0,1] coordinate space. Meets NFR-1 (30 fps).

**Stories**:

- Story 3.1: `AnnotationEditorViewModel` and state model [M]
  - Task 3.1.1: Create `ui/annotate/AnnotationEditorViewModel.kt` with `StateFlow<AnnotationEditorState>`; fields: `currentTool: AnnotationTool`, `inProgressPoints: List<NormalizedPoint>`, `committedAnnotations: List<MeasurementAnnotation>`, `calibration: Calibration`, `imageAnnotation: ImageAnnotation`
  - Task 3.1.2: Implement `AnnotationTool` enum: `SELECT`, `DISTANCE`, `AREA`, `ANGLE`, `LABEL`, `GRID_REF`
  - Task 3.1.3: Implement undo/redo stack as `ArrayDeque<AnnotationEditorState>` (max 50 steps); expose `undo()` and `redo()` on ViewModel
  - Task 3.1.4: Implement `commitAnnotation(points, tool)` that computes the domain value (distance/area/angle in meters/m²/degrees) from `calibration.pixelsPerMeter` and the pixel-space point list and writes via `MeasurementAnnotationRepository`
  - Task 3.1.5: Unit tests for measurement computation math: pixel → meters, polygon area, angle between three points (commonTest)

- Story 3.2: ZoomImage host with annotation Canvas overlay [L] [ADR-002]
  - Task 3.2.1: Create `ui/annotate/AnnotationEditorScreen.kt` with `Box(fillMaxSize)` containing: Layer 1 = `ZoomImage` (Coil 3 model, `rememberZoomState()`), Layer 2 = committed annotations `Canvas`, Layer 3 = in-progress `Canvas` with gesture handling, Layer 4 = `MeasurementLabelOverlay`, Layer 5 = `AnnotationToolbar`
  - Task 3.2.2: Implement coordinate transform helpers: `Offset.toNormalized(imageSize, zoomState)` and `NormalizedPoint.toScreen(imageSize, zoomState)` — applied at draw time only, never stored
  - Task 3.2.3: Implement `detectAnnotationGestures` using `Modifier.pointerInput`: tap adds a point to `inProgressPoints`; after the required number of points for the active tool, `commitAnnotation()` is called
  - Task 3.2.4: Implement committed annotations Canvas: `drawAnnotationLine()`, `drawAnnotationPolygon()`, `drawAnnotationAngle()`, `drawAnnotationGrid()` in `DrawScope`; retain `Path` objects via `remember` to avoid per-frame allocation
  - Task 3.2.5: Screenshot tests for each annotation type rendered on a sample image (jvmTest / Roborazzi)

- Story 3.3: Measurement label overlay and text rendering [M]
  - Task 3.3.1: Implement `MeasurementLabelOverlay` composable using `TextMeasurer` (commonMain, CMP 1.6+) to draw leader-line callouts on Canvas; labels positioned at annotation midpoint offset by a fixed vector
  - Task 3.3.2: Implement label collision avoidance: if two labels overlap within 24dp, offset the second label along the perpendicular axis
  - Task 3.3.3: Implement `AnnotationType.LABEL` (text + leader arrow): tap to place anchor, drag to place text box, type text in a floating `TextField`
  - Task 3.3.4: Test label rendering on Desktop (Skiko font metrics) and Android to catch cross-platform offset discrepancies

- Story 3.4: Annotation toolbar and tool selection UI [S]
  - Task 3.4.1: Implement `AnnotationToolbar` composable with icon buttons for each `AnnotationTool`; active tool highlighted; undo/redo buttons
  - Task 3.4.2: Implement unit selection `DropdownMenu` (m, cm, mm, ft, in); selection stored in `ImageAnnotation.unit` via ViewModel → repository
  - Task 3.4.3: Implement annotation selection and deletion: `SELECT` tool tap on an existing annotation highlights it; delete button removes from repository and undo stack

- Story 3.5: Image export — bake annotations into JPEG [M]
  - Task 3.5.1: Implement `AnnotationExporter.bakeToImageBitmap(composable): ImageBitmap` using `GraphicsLayer.toImageBitmap()` (CMP 1.7+) in `commonMain`
  - Task 3.5.2: Implement JPEG encoding: `androidMain` via `Bitmap.compress(JPEG)`; `jvmMain` via `ImageIO.write()`; `iosMain` via `UIImageJPEGRepresentation`; coordinate via `expect`/`actual` `ImageEncoder`
  - Task 3.5.3: Test: import JPEG, add two line annotations, export — verify exported image bytes contain the annotation overlays (jvmTest)

---

## Epic 4: Calibration Engine

**Goal**: Support manual reference-object calibration (primary), ARCore depth (tertiary), EXIF math (quaternary), and monocular ML depth (fallback of last resort). All methods update `Calibration` in the `ImageAnnotation`. The BLE laser path (secondary) is in Epic 5.

**Stories**:

- Story 4.1: Manual reference-object calibration (US-2.1) [M]
  - Task 4.1.1: Implement `GRID_REF` tool mode: user draws a two-point line on a known-length object; a dialog prompts for the real-world length and unit
  - Task 4.1.2: Implement `CalibrationService.computeFromReference(pixelStart, pixelEnd, imageSize, knownMeters): Calibration` — sets `method = MANUAL_REFERENCE`, `pixelsPerMeter = knownMeters / pixelDistance`, `confidencePercent = 100`
  - Task 4.1.3: Update `AnnotationEditorViewModel` to re-derive all committed measurements after calibration change (recalculate `valueMeters` for each existing annotation)
  - Task 4.1.4: Unit tests for `computeFromReference` with known pixel distances (commonTest)

- Story 4.2: EXIF focal-length calibration (US-2.3) [M]
  - Task 4.2.1: Implement `ExifCalibrationService.estimate(exifData, depthHintMeters?): Calibration?` — reads `FocalLength`, `FocalLengthIn35mmFilm`, computes horizontal FOV, returns a `Calibration` with `method = EXIF_FOCAL` and `confidencePercent = 20` (±15% accuracy)
  - Task 4.2.2: Surface calibration confidence as a color-coded badge in `AnnotationEditorScreen`: green (BLE/manual), yellow (ARCore), orange (EXIF), red (ML)
  - Task 4.2.3: Unit tests with EXIF values from Pixel 8 and Samsung S24 sample images (commonTest)

- Story 4.3: ARCore depth calibration (US-2.2) [L] [ADR-005]
  - Task 4.3.1: Create `platform/sensor/DepthSensorProvider.kt` interface in `commonMain`; `actual` implementations: `ARCoreDepthProvider` (androidMain), `LidarDepthProvider` (iosMain, ARKit), `NoOpDepthProvider` (jvmMain/wasmJsMain)
  - Task 4.3.2: Implement `ARCoreDepthProvider` in `androidMain`: check `session.isDepthModeSupported(DepthMode.AUTOMATIC)` at startup; `acquireDepthFrame()` returns `DepthFrame` with confidence map and depth values; fallback to `null.right()` if not supported
  - Task 4.3.3: Implement `CalibrationService.computeFromDepthFrame(depthFrame, tapPoint): Calibration` — samples depth at user tap point, sets `method = ARCORE_DEPTH`, `confidencePercent` derived from ARCore confidence value at that pixel
  - Task 4.3.4: Display explicit accuracy warning in UI: "ARCore depth accuracy ±8–10 cm. Not suitable for measurements under 15 cm." (per pitfalls research) [ADR-005 constraint]
  - Task 4.3.5: Gate the ARCore path on `DepthSensorProvider.isAvailable`; if `false`, skip to next calibration method in the fallback chain
  - Task 4.3.6: Integration test on emulator with mocked ARCore depth session (androidUnitTest)

- Story 4.4: Monocular ML depth — Depth Anything V2 via ONNX Runtime (US-2.5) [L] [ADR-005]
  - Task 4.4.1: Bundle Depth Anything V2 ViT-S ONNX model (from `fabio-sim/Depth-Anything-ONNX`) as an Android asset; iOS via Core ML converted model
  - Task 4.4.2: Implement `MonocularDepthEstimator` in `androidMain` using `onnxruntime-android`; gate model load on `Build.VERSION.SDK_INT >= 26` and `ActivityManager.getMemoryInfo().totalMem >= 3GB`
  - Task 4.4.3: Implement `CalibrationService.computeFromMLDepth(depthMap, tapPoint, imageSize): Calibration` — samples depth at user tap, sets `method = MONOCULAR_ML`, `confidencePercent = 15` (with range warning)
  - Task 4.4.4: Implement iOS `MonocularDepthEstimator` using Core ML (iosMain); Core ML model exported from ONNX via `coremltools`
  - Task 4.4.5: Expose "Estimate depth (AI)" button in calibration UI; runs inference on the captured still image (not real-time); display spinner during ~500–800ms inference; show result with "Low confidence — verify with reference object" warning
  - Task 4.4.6: Unit test for depth-to-calibration conversion math; integration test that model file loads without crash (androidUnitTest)

- Story 4.5: Calibration fallback chain and confidence UI [S]
  - Task 4.5.1: Implement `CalibrationFallbackChain` that tries: BLE reading (if device connected) → manual reference (if drawn) → ARCore depth (if available) → EXIF (if focal length present) → ML (if memory gate passes) → `NONE`
  - Task 4.5.2: Implement calibration status indicator in `AnnotationEditorScreen` header bar: icon + color badge + accuracy string ("±1mm BLE", "±8cm ARCore", "±15% AI estimate")
  - Task 4.5.3: Unit test fallback chain ordering with mocked providers (commonTest)

---

## Epic 5: BLE Laser Rangefinder

**Goal**: Connect Leica DISTO and Bosch GLM devices via BLE GATT; inject laser readings directly as line annotation measurements. USB serial and BT HID keyboard emulation as fallbacks. All protocol implementations are pluggable via `MeasurementDeviceRegistry`.

**Stories**:

- Story 5.1: `ExternalMeasurementDevice` interface and device registry [M] [ADR-004]
  - Task 5.1.1: Create `platform/measurement/ExternalMeasurementDevice.kt` interface in `commonMain` with `connect()`, `disconnect()`, `readMeasurement()`, `measurementFlow()`, `connectionState: StateFlow<DeviceConnectionState>`
  - Task 5.1.2: Create `MeasurementDeviceRegistry` object and `MeasurementDeviceFactory` interface in `commonMain`
  - Task 5.1.3: Create `MeasurementDeviceScanner` interface and `CompositeMeasurementDeviceScanner` that aggregates results from all registered factories
  - Task 5.1.4: Create `MeasurementReading` and `DeviceConnectionState` data types
  - Task 5.1.5: Unit tests for composite scanner aggregation (commonTest)

- Story 5.2: Kable BLE infrastructure (Android/iOS) [L] [ADR-004]
  - Task 5.2.1: Add Kable dependency to `androidMain` and `iosMain` source sets in `kmp/build.gradle.kts`
  - Task 5.2.2: Implement `KableBleDeviceFactory` in `androidMain`/`iosMain` using Kable `Scanner { }` for discovery; advertise via `MeasurementDeviceRegistry.register()`
  - Task 5.2.3: Declare Android BLE permissions in `AndroidManifest.xml`: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+); request at runtime before scan; check `ContextCompat.checkSelfPermission` before every scan call
  - Task 5.2.4: Implement `ForegroundService` for BLE connection lifecycle on Android API 31+; persistent notification with "Measuring..." label; auto-dismiss on disconnect
  - Task 5.2.5: Implement exponential backoff retry on GATT 133: min 2s, max 60s, max 5 retries before surfacing error to user
  - Task 5.2.6: Enforce `gatt.disconnect()` + `gatt.close()` on every disconnect path (normal and error) to prevent GATT object leak (30-object Android BLE stack limit)
  - Task 5.2.7: Negotiate MTU explicitly to 100 bytes before first characteristic read (middle-ground for DISTO compatibility per pitfalls research)
  - Task 5.2.8: Integration test using a mock BLE peripheral (Kable testing utilities) (androidUnitTest)

- Story 5.3: Leica DISTO protocol implementation [M]
  - Task 5.3.1: Implement `LeicaDistoProtocol` implementing `ExternalMeasurementDevice`; target service UUID `3ab10100-f831-4395-b29d-570977d5bf94`; parse little-endian float characteristic notification as meters
  - Task 5.3.2: Implement 2-second ACK write to control characteristic after each measurement notification (per DISTO protocol requirement)
  - Task 5.3.3: Implement `LeicaDistoDeviceFactory` registering in `KableBleDeviceFactory`; filter scan results by service UUID prefix
  - Task 5.3.4: Test with known byte sequences from `seichter/d2relay` reference implementation (commonTest, no hardware required)

- Story 5.4: Bosch GLM protocol implementation [M]
  - Task 5.4.1: Implement `BoschGlmProtocol` implementing `ExternalMeasurementDevice`; target SPP-over-BLE UUID `0x1101`; parse ASCII format `MM:D<float_value>\r\n` (per `philipptrenz/BOSCH-GLM-rangefinder` reference)
  - Task 5.4.2: Implement `BoschGlmDeviceFactory`; scan filter by device name prefix "Bosch" or "GLM"
  - Task 5.4.3: Test with known byte sequences from reference implementation (commonTest)

- Story 5.5: BT HID keyboard emulation mode [M]
  - Task 5.5.1: Implement `KeyboardEmulationDeviceFactory` in `commonMain`; no BLE scan needed — intercepts text input from a floating "measurement capture" `TextField`
  - Task 5.5.2: Parse typed measurement strings: regex `(\d+\.?\d*)\s*(mm|cm|m|ft|in)?`; convert to meters; emit as `MeasurementReading`
  - Task 5.5.3: Add "Keyboard device mode" toggle in BLE device settings screen
  - Task 5.5.4: Unit tests for measurement string parsing with edge cases: comma decimal separator, trailing whitespace, unit-less values (commonTest)

- Story 5.6: USB serial (OTG) fallback [M]
  - Task 5.6.1: Add `usb-serial-for-android` (kai-morich fork) dependency to `androidMain`; confirm LGPL 2.1 compliance via dynamic `.aar` linking
  - Task 5.6.2: Implement `AndroidUsbSerialDeviceFactory` in `androidMain`; detect USB device attach via `UsbManager.ACTION_USB_DEVICE_ATTACHED` broadcast; filter for supported chipsets
  - Task 5.6.3: Implement raw serial read wrapped in a `Flow<MeasurementReading>`; parse device-specific ASCII or binary format (Bosch GLM 100C USB format)
  - Task 5.6.4: Request `USB_PERMISSION` via PendingIntent before opening the device
  - Task 5.6.5: Register `AndroidUsbSerialDeviceFactory` in `MeasurementDeviceRegistry` at app startup

- Story 5.7: Measurement injection into annotation and calibration [M]
  - Task 5.7.1: Implement "Inject reading" flow: user draws a distance line annotation, then taps "Set from laser" button; next `MeasurementReading` from the active device is used as the `knownMeters` value for that segment, updating `Calibration` to `method = BLE_LASER`
  - Task 5.7.2: Update `MeasurementAnnotation.bleDeviceId` field with the device ID of the rangefinder that provided the reading
  - Task 5.7.3: Implement device connection status chip in `AnnotationEditorScreen`: shows device name + signal strength + last reading; green when connected
  - Task 5.7.4: Integration test: mock device emits a reading, verify annotation `valueMeters` updated and calibration set to `BLE_LASER` (androidUnitTest)

---

## Epic 6: SteleKit Integration

**Goal**: Annotated images are embedded in pages as blocks; measurement properties are queryable; a gallery view shows all annotated images; back-links work bidirectionally; block math references work for named measurements.

**Stories**:

- Story 6.1: `image_annotation` block rendering in `BlockItem.kt` [M]
  - Task 6.1.1: Implement `ImageAnnotationBlockItem` composable in `ui/components/blocks/`; loads the image via `AsyncImage` (Coil 3); shows thumbnail with measurement count badge; taps open `AnnotationEditorScreen`
  - Task 6.1.2: Render block properties inline below the image: `::length::`, `::area::`, `::calibration::` displayed as a compact metadata row
  - Task 6.1.3: Screenshot tests for the block item in `jvmTest` / Roborazzi

- Story 6.2: Block property sync from annotations [M]
  - Task 6.2.1: Implement `MeasurementPropertySyncer` in `commonMain`; after any annotation save, updates the parent block's `properties` map: named annotations become `::distance:<label>::`, `::area:<label>::`, `::angle:<label>::`
  - Task 6.2.2: Implement `{{measure: <image-ref>.<label>}}` template resolver in `commonMain` parser; resolves named measurement values to formatted strings for block math (US-3.8, US-4.5)
  - Task 6.2.3: Unit tests for property sync with various annotation configurations (commonTest)

- Story 6.3: Gallery view screen [L]
  - Task 6.3.1: Create `ui/gallery/GalleryScreen.kt` composable; queries `ImageAnnotationRepository.getAllImageAnnotations()` via `GalleryViewModel`; displays a lazy staggered grid of image thumbnails
  - Task 6.3.2: Implement tag filter chips row at top of gallery; selecting a tag calls `getImageAnnotationsByTag()`
  - Task 6.3.3: Implement sort options: by date (captured), by date (imported), by measurement count
  - Task 6.3.4: Each gallery card shows: thumbnail, page name, tag chips, measurement count, calibration confidence badge
  - Task 6.3.5: Tapping a gallery card navigates to `AnnotationEditorScreen` for that image, and also provides a "Go to page" navigation link
  - Task 6.3.6: Screenshot tests for gallery grid (jvmTest / Roborazzi)

- Story 6.4: Tag management [S]
  - Task 6.4.1: Implement tag editor in `AnnotationEditorScreen` sidebar: `TextField` with autocomplete from existing graph tags; adds to `ImageAnnotation.tags` list
  - Task 6.4.2: Update `image_annotations.tags` JSON column on save; re-index via `SQLDelightImageAnnotationRepository`
  - Task 6.4.3: Verify tags are compatible with existing Logseq query system (block properties `::tags::` format)

- Story 6.5: Journal auto-insert on camera capture [S]
  - Task 6.5.1: After `ImageImportService.import()` for camera-sourced images, call `JournalRepository.getOrCreateTodayPage()` and insert the new `image_annotation` block at the bottom of today's journal page
  - Task 6.5.2: Unit test: mock camera capture → verify today's journal page has the image block (jvmTest)

- Story 6.6: Navigation and back-links [S]
  - Task 6.6.1: Wire `AnnotationEditorScreen` into the existing `StelekitViewModel` navigation: add `navigateToAnnotationEditor(imageAnnotationUuid)` route
  - Task 6.6.2: Wire `GalleryScreen` into the main navigation (sidebar or new tab in `App.kt`)
  - Task 6.6.3: "Go to page" link in the annotation editor header navigates to `StelekitViewModel.navigateTo(pageUuid)`

---

## Epic 7: Google Drive Integration

**Goal**: OAuth via Credential Manager (Android), export annotated image + JSON sidecar to Google Drive. Google Photos import via system Picker API (not programmatic library access — scopes revoked March 2025).

**Stories**:

- Story 7.1: OAuth token management [L] [ADR-003]
  - Task 7.1.1: Define `GoogleTokenStore` interface in `commonMain` with `saveTokens()`, `getAccessToken()`, `getRefreshToken()`, `clearTokens()`
  - Task 7.1.2: Implement `AndroidGoogleTokenStore` in `androidMain` using `EncryptedSharedPreferences` backed by Android Keystore (following existing `EncryptionManager` pattern)
  - Task 7.1.3: Implement `IosGoogleTokenStore` in `iosMain` using Keychain (`SecItemAdd`/`SecItemCopyMatching`)
  - Task 7.1.4: Implement `JvmGoogleTokenStore` in `jvmMain` using `java.security.KeyStore` (JKS) or OS keyring via `secret-service` DBus on Linux
  - Task 7.1.5: Implement token refresh interceptor as a Ktor `Auth` plugin; transparently refreshes when `access_token` expires using `refresh_token` via `https://oauth2.googleapis.com/token`
  - Task 7.1.6: Unit tests for token expiry and refresh logic (commonTest with mock HTTP)

- Story 7.2: Android Credential Manager OAuth flow [M]
  - Task 7.2.1: Configure Credential Manager with `GetGoogleIdOption(serverClientId = WEB_CLIENT_ID, filterByAuthorizedAccounts = false)`; request scopes `drive.file` (upload) + `photoslibrary.appendonly` (write-back to album)
  - Task 7.2.2: Exchange Google ID token for Drive access token via server token endpoint
  - Task 7.2.3: Implement OAuth sign-in UI: "Connect Google Account" card in Settings screen; show logged-in user email when authenticated
  - Task 7.2.4: Implement sign-out flow: `GoogleTokenStore.clearTokens()` + Credential Manager revoke

- Story 7.3: Ktor Google API client [M]
  - Task 7.3.1: Implement `GoogleApiClient` in `commonMain` wrapping Ktor `HttpClient`; inject the Ktor engine per platform; attach `BearerAuth` plugin pointing to `GoogleTokenStore`
  - Task 7.3.2: Implement Drive REST API v3 methods: `uploadFile(multipart)`, `listFiles(folderId)`, `createFolder(name, parentId)`
  - Task 7.3.3: Implement Photos Picker API REST bridge: call `https://photospicker.googleapis.com/v1/sessions` to initiate a picker session; return the picker URI for the Android WebView overlay
  - Task 7.3.4: Unit tests with mocked Ktor engine for Drive upload, folder listing, and Photos Picker session (commonTest)

- Story 7.4: Google Drive export flow [M]
  - Task 7.4.1: Implement "Export to Drive" button in `AnnotationEditorScreen`: bakes annotations via `AnnotationExporter.bakeToImageBitmap()`, encodes to JPEG, uploads annotated image to Drive folder via `GoogleApiClient.uploadFile()`
  - Task 7.4.2: Serialize `ImageAnnotation` + `List<MeasurementAnnotation>` to JSON sidecar and upload as a second file with `.measure.json` extension alongside the image
  - Task 7.4.3: Implement resumable upload for files > 5 MB: store resumable upload session URI in SQLDelight (`drive_upload_sessions` table or in `image_annotations.source_uri`); recover session after process death via `WorkManager`
  - Task 7.4.4: Use WorkManager with `NetworkType.CONNECTED` constraint for large uploads; show upload progress in notification
  - Task 7.4.5: Integration test: mock `GoogleApiClient` → verify annotated JPEG + JSON sidecar both uploaded with correct filenames (jvmTest)

- Story 7.5: Google Photos Picker import (system overlay) [M] [ADR-003]
  - Task 7.5.1: Implement Google Photos Picker flow via REST Picker API: create session → open WebView/custom tab showing picker UI → receive selected media item IDs on callback
  - Task 7.5.2: Download selected media bytes using `baseUrl` (re-fetched on import; not stored long-term per API contract) via `GoogleApiClient.downloadPhoto()`
  - Task 7.5.3: Pass downloaded bytes to `ImageImportService.import()` with `source = GOOGLE_PHOTOS`; store `mediaItemId` (not `baseUrl`) in `ImageAnnotation.sourceUri`
  - Task 7.5.4: UI copy: "Select from Google Photos — you will choose specific photos to share with SteleKit" (clarify limited access per post-March-2025 scope restrictions)

- Story 7.6: Google Drive file browse and import [S]
  - Task 7.6.1: Implement Drive file browser composable: calls `GoogleApiClient.listFiles()`, displays folder tree as a `LazyColumn`, allows navigation into subfolders
  - Task 7.6.2: On file selection, download via `GoogleApiClient.downloadFile()` and pass to `ImageImportService.import()` with `source = GOOGLE_DRIVE`

---

## Epic 8: Platform Sensors

**Goal**: GPS tagging of captured images, compass bearing annotation, accelerometer pitch/roll stored for perspective correction metadata. iOS LiDAR depth integration.

**Stories**:

- Story 8.1: `MotionSensorProvider` interface and platform implementations [M]
  - Task 8.1.1: Create `platform/sensor/MotionSensorProvider.kt` interface in `commonMain`: `sensorDataFlow: Flow<ImageSensorData>`, `startSensing()`, `stopSensing()`
  - Task 8.1.2: Implement `AndroidMotionSensorProvider` in `androidMain` using `SensorManager` (accelerometer, rotation vector) + `FusedLocationProviderClient` (GPS); emit `ImageSensorData` at 10 Hz during capture mode
  - Task 8.1.3: Implement `IOSMotionSensorProvider` in `iosMain` using `CoreMotion.CMMotionManager` + `CoreLocation.CLLocationManager`
  - Task 8.1.4: Implement `NoOpMotionSensorProvider` (jvmMain/wasmJsMain) returning empty Flow
  - Task 8.1.5: On camera capture, snapshot the latest `ImageSensorData` and store in the `ImageAnnotation`

- Story 8.2: GPS tagging and location display [S]
  - Task 8.2.1: Store `latLng` and `altitudeM` from `ImageSensorData` in `ImageAnnotation` at capture time
  - Task 8.2.2: Display GPS coordinates in `AnnotationEditorScreen` metadata sidebar (format: "49.2827° N, 123.1207° W")
  - Task 8.2.3: Write `latLng` to `image_annotations.lat_lng` in SQLDelight; surface in gallery card metadata row
  - Task 8.2.4: Request `ACCESS_FINE_LOCATION` permission at runtime on Android before starting `MotionSensorProvider`

- Story 8.3: Compass bearing annotation [S]
  - Task 8.3.1: If `bearingDeg` is present in `ImageSensorData`, auto-create a `AnnotationType.LABEL` annotation "Bearing: 273°N" positioned at the top-right of the image
  - Task 8.3.2: Store `bearing_deg` in `image_annotations` column; display in gallery card

- Story 8.4: Accelerometer pitch/roll for perspective correction metadata [S]
  - Task 8.4.1: Store `pitchDeg` and `rollDeg` from `ImageSensorData` in `ImageAnnotation.sensorData`
  - Task 8.4.2: If `abs(pitchDeg) > 15°` or `abs(rollDeg) > 10°`, display a yellow warning banner in `AnnotationEditorScreen`: "Camera tilted — measurements may be inaccurate. Use a reference object for calibration."
  - Task 8.4.3: Store tilt data in JSON sidecar for future perspective correction implementation

- Story 8.5: iOS LiDAR depth provider [M]
  - Task 8.5.1: Implement `LidarDepthProvider` in `iosMain` using ARKit `ARSession` with `ARWorldTrackingConfiguration` and `frameSemantics.sceneDepth`; available on iPhone 12 Pro+ and iPad Pro with LiDAR
  - Task 8.5.2: Return `DepthFrame` from `acquireDepthFrame()` when LiDAR is available; set `isAvailable = true` on those devices
  - Task 8.5.3: Map LiDAR depth accuracy to `confidencePercent = 85` (±1–2 cm); display "LiDAR calibration (high confidence)" badge

---

## Epic 9: Polish and Performance

**Goal**: Verify 30 fps on real Snapdragon 695 hardware (NFR-1), fix EXIF rotation bugs on Samsung devices, test large image handling without OOM, audit offline-first behavior, and confirm all detekt + CI checks pass.

**Stories**:

- Story 9.1: 30 fps annotation canvas performance verification [M]
  - Task 9.1.1: Profile annotation canvas on Moto G Power 5G (Snapdragon 695) using Android Systrace; measure frame time for 20-annotation scenario
  - Task 9.1.2: Fix any recomposition hotspots: move `Path` object construction outside `DrawScope.drawWithContent` lambda; use `remember { Path() }` pattern for retained paths
  - Task 9.1.3: Verify ZoomImage pan/zoom runs at 60 fps via `GraphicsLayer` transform (does not trigger annotation recomposition); confirm with Compose recomposition counter
  - Task 9.1.4: If < 30 fps, apply `drawIntoCanvas` retain optimization and retest; document result in performance log

- Story 9.2: Large image handling — OOM prevention [M]
  - Task 9.2.1: Implement `inSampleSize` calculation in `AndroidCameraProvider`: load display-resolution preview (`inSampleSize = 4`); store full-res file on disk untouched
  - Task 9.2.2: Verify ZoomImage subsampling activates on images > 4 MP (ZoomImage uses tiling internally for large images); confirm on a 50 MP Samsung sample image
  - Task 9.2.3: Add `BitmapFactory.Options.inBitmap` reuse for annotation export to avoid double-allocation
  - Task 9.2.4: Test on a device with 3 GB RAM (minimum gate from Epic 4 ML model story); confirm no OOM

- Story 9.3: Samsung EXIF rotation regression test [S]
  - Task 9.3.1: Add `jvmTest` fixture: Samsung-produced JPEG with `ORIENTATION_TRANSVERSE` EXIF tag; verify `ExifOrientationFixer` produces a correctly-oriented image
  - Task 9.3.2: Add fixture: Samsung landscape JPEG with incorrect orientation tag; verify the fix applies
  - Task 9.3.3: Add fixture: Google Pixel portrait JPEG; verify no unnecessary rotation is applied

- Story 9.4: Offline-first audit [S]
  - Task 9.4.1: Remove all network calls from the annotation editor hot path; confirm Ktor client is only instantiated when cloud features are explicitly triggered
  - Task 9.4.2: Run `ciCheck` with network disabled; verify no `UnresolvedAddressException` or similar
  - Task 9.4.3: Test: disable WiFi on device, annotate an image, save — verify data persisted to SQLDelight and sidecar with no errors

- Story 9.5: Detekt and CI conformance [S]
  - Task 9.5.1: Run `./gradlew ciCheck` after each epic's completion; fix all detekt warnings introduced by new code
  - Task 9.5.2: Ensure all new `suspend fun` at repository boundaries return `Either<DomainError, T>` (no raw exceptions at boundaries); add a detekt rule if needed
  - Task 9.5.3: Verify `DatabaseWriteActor` actor coverage: grep for any direct `queries.insert*` / `queries.update*` calls outside `RestrictedDatabaseQueries`; fix any violations

- Story 9.6: Accessibility and UX polish [S]
  - Task 9.6.1: Add `contentDescription` to all annotation canvas touch targets; verify TalkBack announces annotation type and measurement value when focused
  - Task 9.6.2: Ensure all measurement labels meet WCAG 2.1 minimum contrast ratio 4.5:1 against the image background (use a semi-transparent background chip behind label text)
  - Task 9.6.3: Add haptic feedback on annotation point commit (Android `Vibrator`; iOS `UIImpactFeedbackGenerator`)

---

## Known Issues

### Potential Bugs Identified During Planning

#### Concurrency Risk: `rememberCoroutineScope` Leak in AnnotationEditorViewModel [SEVERITY: High]

**Description**: If `AnnotationEditorViewModel` receives an externally supplied scope (e.g., from `rememberCoroutineScope()`), scope cancellation on navigation away will kill in-flight annotation writes.

**Mitigation**:
- `AnnotationEditorViewModel` must own its `CoroutineScope` internally: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- Never pass `rememberCoroutineScope()` to `AnnotationEditorViewModel` constructor.
- Audit pattern: grep for `remember.*scope` in `AnnotationEditorScreen.kt`.

**Files Likely Affected**: `AnnotationEditorViewModel.kt`, `AnnotationEditorScreen.kt`

#### Data Integrity Risk: Calibration Change Does Not Re-Derive Existing Measurements [SEVERITY: High]

**Description**: If `CalibrationService.computeFromReference()` updates `pixelsPerMeter` and `AnnotationEditorViewModel` does not re-calculate all previously committed `MeasurementAnnotation.valueMeters` values, existing annotations will silently display stale (wrong) measurements.

**Mitigation**:
- Story 4.1.3 explicitly re-derives all committed measurements on calibration change.
- Add unit test: calibrate, add 3 annotations, change calibration, verify all 3 `valueMeters` updated.

**Files Likely Affected**: `AnnotationEditorViewModel.kt`, `CalibrationService.kt`

#### Integration Risk: BLE GATT 133 Unrecoverable State on Android 14 [SEVERITY: High]

**Description**: Android 14 has a documented regression where repeated GATT 133 errors can leave the BLE stack in an unrecoverable state until device reboot.

**Mitigation**:
- Story 5.2.5 implements exponential backoff with a max of 5 retries.
- After 5 retries, surface a user-visible error with "Please toggle Bluetooth off and on, or restart the app" message.
- Implement `gatt.close()` on every disconnect path without exception (Story 5.2.6).
- Monitor GATT 133 error rate via crash reporting; alert if > 10% of BLE connection attempts fail.

**Files Likely Affected**: `KableBleDeviceFactory.kt`, `AndroidBleConnectionManager.kt`

#### Data Loss Risk: Sidecar Write Failure After SQLDelight Write Succeeds [SEVERITY: Medium]

**Description**: `ImageSidecarManager.writeSidecar()` is called after the SQLDelight insert succeeds. If the sidecar write fails (disk full, permission error), the DB and sidecar are out of sync. On graph export, the sidecar is the authoritative source — missing sidecar means measurement data loss.

**Mitigation**:
- Implement transactional write order: write sidecar first, then commit SQLDelight row. If sidecar write fails, return error without persisting to DB.
- Add a `SidecarIntegrityChecker` that runs at startup and detects DB rows with missing sidecars; re-creates sidecars from DB data.
- Unit test: mock `FileSystem.writeFileBytes` to throw; verify DB row was not committed.

**Files Likely Affected**: `ImageImportService.kt`, `ImageSidecarManager.kt`

#### Platform Risk: Android Content URI Expiry on Photo Picker Import [SEVERITY: Medium]

**Description**: The Android Photo Picker returns a temporary content URI grant. If the app does not copy the image bytes to `assets/images/` synchronously in the same process session, the URI becomes invalid and the import silently fails.

**Mitigation**:
- Story 2.4.2 copies bytes immediately on selection before any coroutine suspension yields control.
- Use `contentResolver.openInputStream(uri)` inside the `ActivityResultCallback` before any `withContext` call.
- Test on API 28 (backport path) where grant behavior differs from API 33+.

**Files Likely Affected**: `AndroidPhotoPicker.kt`, `ImageImportService.kt`

#### Performance Risk: Annotation Canvas Recomposition on Every ZoomImage Gesture [SEVERITY: Medium]

**Description**: If the annotation `Canvas` composable reads ZoomImage's `zoomState` directly (a `State<ZoomState>` that updates every frame during pan/zoom), Compose will recompose the annotation Canvas at 60 fps during pan, causing NFR-1 violation.

**Mitigation**:
- Story 3.2.3 uses `ZoomImage`'s `GraphicsLayer` transform (hardware-accelerated) for pan/zoom; the annotation Canvas reads `zoomState` only inside `DrawScope` (which is called during a Canvas draw pass, not recomposition).
- Specifically: use `Modifier.graphicsLayer { scaleX = ...; translationX = ... }` on the outer `Box`, not inside the annotation Canvas lambda.
- Profile with Android Studio Layout Inspector recomposition counter during pan gesture.

**Files Likely Affected**: `AnnotationEditorScreen.kt`

#### Security Risk: Drive Access Token Stored in SharedPreferences Without Keystore Encryption [SEVERITY: Medium]

**Description**: If `AndroidGoogleTokenStore` falls back to plain `SharedPreferences` instead of `EncryptedSharedPreferences`, the OAuth access token is stored in plaintext and readable by root-access or backup extraction.

**Mitigation**:
- Story 7.1.2 explicitly uses `EncryptedSharedPreferences` backed by Android Keystore.
- Add an assertion in the implementation: if `EncryptedSharedPreferences.create()` throws, propagate the error to the user rather than falling back to plain `SharedPreferences`.
- Add a security test that verifies token file is not readable without Keystore decryption.

**Files Likely Affected**: `AndroidGoogleTokenStore.kt`

#### Data Integrity Risk: ML Depth Model Not Loaded on First Use — Silent Fallback [SEVERITY: Low]

**Description**: If the Depth Anything V2 ONNX model fails to load (memory gate, unsupported ops, corrupt asset), `MonocularDepthEstimator` may return null silently and the `CalibrationFallbackChain` proceeds to `NONE` without notifying the user that ML depth was attempted and failed.

**Mitigation**:
- `MonocularDepthEstimator.initialize()` returns `Either<DomainError, Unit>`; failure is surfaced to `CalibrationFallbackChain`.
- `CalibrationFallbackChain` logs each fallback step at `INFO` level with the reason for skipping each method.
- If all methods result in `NONE`, show user a dismissible banner: "No calibration set — measurements will not show real-world values. Draw a reference line or connect a laser meter."

**Files Likely Affected**: `MonocularDepthEstimator.kt`, `CalibrationFallbackChain.kt`

---

## Architecture Decision Records Flagged

| ADR | Decision | Location |
|---|---|---|
| ADR-001 | Image block representation: `image_annotation` blockType + dual-layer storage (SQLDelight + JSON sidecar) | [decisions/ADR-001-image-annotation-block-type.md] |
| ADR-002 | Annotation canvas: Compose Canvas DrawScope + ZoomImage (no third-party annotation lib); normalized coordinate system | [decisions/ADR-002-annotation-canvas-architecture.md] |
| ADR-003 | Google Photos import via system Picker API (not Library API — scopes revoked March 2025); Drive REST API v3 via Ktor; Credential Manager OAuth | [decisions/ADR-003-google-integration.md] |
| ADR-004 | BLE via Kable behind `ExternalMeasurementDevice` interface + `MeasurementDeviceRegistry` plugin pattern; USB serial via usb-serial-for-android (LGPL 2.1) | [decisions/ADR-004-external-measurement-device.md] |
| ADR-005 | Calibration fallback chain: BLE laser (primary) → manual reference → ARCore (tertiary, ±8–10 cm, explicit warning) → EXIF (quaternary, ±15%) → ML depth (last resort, ±15%, not real-time); ARCore 5 m distance cap | [decisions/ADR-005-calibration-chain.md] |

---

## Implementation Sequencing

Dependencies between epics establish this delivery order:

1. **Epic 1** (Foundation) — no dependencies; must be complete before any other epic
2. **Epic 2** (Image Capture) — requires Epic 1 (`ImageImportService`, `ImageAnnotation` model, file storage)
3. **Epic 3** (Annotation Canvas) — requires Epic 1 (`MeasurementAnnotation`, `AnnotationType`, repositories)
4. **Epic 4** (Calibration Engine) — requires Epic 3 (canvas tool mode state machine, `CalibrationService` integration point)
5. **Epic 5** (BLE Laser) — requires Epic 4 (calibration injection flow in Story 5.7)
6. **Epic 6** (SteleKit Integration) — requires Epics 2, 3 (block rendering, gallery queries)
7. **Epic 7** (Google Drive) — requires Epic 2 (image import pipeline), Epic 3 (baked export), Epic 6 (OAuth settings UI context)
8. **Epic 8** (Platform Sensors) — requires Epic 2 (camera capture pipeline); can run parallel with Epics 4–7
9. **Epic 9** (Polish) — requires all Epics complete; final gate before ship

Epics 4, 5, 6, 7, and 8 can be developed in parallel by separate developers after Epic 3 is complete.
