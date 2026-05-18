# Architecture Research: SteleKit Image Meter

**Date**: 2026-05-16  
**Author**: Architecture research agent  
**Based on**: requirements.md, existing codebase analysis, stack.md

---

## 1. Image Data Model

### Decision: Image Block as a Markdown-Compatible Block with Sidecar Metadata

The existing `Block` model uses `blockType` to discriminate structural variants (`bullet`, `paragraph`, `heading`, etc.). Adding `"image_annotation"` as a new `blockType` is the right extension point — it slots into the existing `validBlockTypes` set, renders via `BlockItem` dispatch (which already has a `ImageBlock` composable stub), and persists as Markdown using Logseq's standard `![alt](path)` or `![alt](../assets/img.jpg)` syntax in `block.content`.

**Proposed `blockType` value**: `"image_annotation"`

**Markdown representation** (stored in `block.content`):
```markdown
![Living room floor](../assets/images/2026-05-16-living-room.jpg)
```

**Block properties** (stored in `block.properties` JSON, surfaced as Logseq `::key:: value` notation):
```
::image-id:: <uuid>            — stable identifier for the annotated image record
::calibration:: scale-set      — calibration state: none | manual | arcore | exif | ble
::px-per-meter:: 412.5         — calibration value (pixels per real-world meter)
::unit:: m                     — preferred measurement unit for this image
::location:: 49.2827,-123.1207 — optional GPS coordinate from sensor
::bearing:: 273.4              — optional compass bearing from sensor
::source:: google-photos        — import source: local | camera | google-photos | google-drive
```

**New domain model** (`model/ImageAnnotation.kt` in `commonMain`):
```kotlin
data class ImageAnnotation(
    val uuid: String,             // Matches block property image-id
    val blockUuid: String,        // Back-link to the owning Block
    val pageUuid: String,         // Denormalized for gallery queries
    val localPath: String,        // Absolute local file path (never a blob)
    val sourceUri: String? = null,// Original Google Photos/Drive URI
    val calibration: Calibration,
    val annotations: List<MeasurementAnnotation> = emptyList(),
    val tags: List<String> = emptyList(),
    val capturedAt: Instant,
    val importedAt: Instant,
    val sensorData: ImageSensorData? = null,
)

data class Calibration(
    val method: CalibrationMethod,
    val pixelsPerMeter: Double,     // Primary calibration coefficient
    val confidencePercent: Int = 100,
    val referenceSegmentPx: Double? = null, // pixel length of reference object
    val referenceMeters: Double? = null,    // known real-world length of reference object
)

enum class CalibrationMethod { NONE, MANUAL_REFERENCE, ARCORE_DEPTH, EXIF_FOCAL, BLE_LASER, MONOCULAR_ML }

data class ImageSensorData(
    val latLng: Pair<Double, Double>? = null,
    val bearingDeg: Float? = null,
    val pitchDeg: Float? = null,    // for perspective correction
    val rollDeg: Float? = null,
    val altitudeM: Float? = null,
)
```

**Relationship summary**:
- One `Block` (blockType=`image_annotation`) → one `ImageAnnotation` record (via `image-id` property)
- `ImageAnnotation` → many `MeasurementAnnotation` records (see Section 2)
- Image file lives on disk; path stored in `ImageAnnotation.localPath`, not in SQLDelight as a blob

---

## 2. Measurement Data Model

### Decision: Dedicated SQLDelight Tables, JSON Annotations Sidecar for Graph Portability

Two storage layers work together:

**Layer A — SQLDelight tables** (fast indexed query, in-app gallery, tag filtering):
```sql
-- image_annotations table: one row per annotated image
CREATE TABLE image_annotations (
    uuid TEXT NOT NULL PRIMARY KEY,
    block_uuid TEXT NOT NULL,
    page_uuid TEXT NOT NULL,
    local_path TEXT NOT NULL,
    source_uri TEXT,
    calibration_method TEXT NOT NULL DEFAULT 'NONE',
    pixels_per_meter REAL NOT NULL DEFAULT 0.0,
    confidence_pct INTEGER NOT NULL DEFAULT 0,
    unit TEXT NOT NULL DEFAULT 'm',
    tags TEXT,                         -- JSON array of tag strings
    lat_lng TEXT,                      -- "lat,lng" or NULL
    bearing_deg REAL,
    pitch_deg REAL,
    roll_deg REAL,
    captured_at INTEGER NOT NULL,
    imported_at INTEGER NOT NULL,
    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
);

-- measurement_annotations table: one row per annotation on an image
CREATE TABLE measurement_annotations (
    uuid TEXT NOT NULL PRIMARY KEY,
    image_uuid TEXT NOT NULL REFERENCES image_annotations(uuid) ON DELETE CASCADE,
    annotation_type TEXT NOT NULL,     -- distance | area | angle | label | grid
    label TEXT,
    value_meters REAL,                 -- computed real-world value (null for labels)
    value_sq_meters REAL,              -- for area measurements
    value_degrees REAL,                -- for angle measurements
    points_json TEXT NOT NULL,         -- JSON array of {x, y} normalized [0,1] image coordinates
    ble_device_id TEXT,                -- if set by a laser measurement, the BLE device UUID
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_image_annotations_block ON image_annotations(block_uuid);
CREATE INDEX idx_image_annotations_page  ON image_annotations(page_uuid);
CREATE INDEX idx_measurement_annotations_image ON measurement_annotations(image_uuid);
```

**Layer B — JSON sidecar file** (graph portability, plain text git-friendly, NFR-2 compliance):
- Path: `<graph>/.stelekit/images/<image-uuid>.measure.json`
- Written any time annotations change; mirrors the SQLDelight rows in a self-contained format
- Format:
```json
{
  "version": 1,
  "imageUuid": "<uuid>",
  "blockUuid": "<block-uuid>",
  "calibration": { "method": "MANUAL_REFERENCE", "pixelsPerMeter": 412.5, "confidencePct": 100 },
  "annotations": [
    {
      "uuid": "<ann-uuid>",
      "type": "distance",
      "label": "wall_A",
      "valueMeters": 3.24,
      "points": [{"x": 0.12, "y": 0.45}, {"x": 0.78, "y": 0.45}]
    }
  ],
  "sensorData": { "latLng": [49.28, -123.12], "bearingDeg": 273.4 }
}
```

**Rationale for this split**:
- NFR-2 requires measurements survive graph export — the JSON sidecar is the authoritative portable format
- NFR-3 (deterministic reproduction) is satisfied: all calculation inputs are stored explicitly (pixelsPerMeter + normalized coordinates → computed distance), so measurements can be recalculated from scratch
- The SQLDelight tables serve the gallery query and tag-filter UX; they are rebuilt from sidecars on import if the DB is ever lost (same pattern as block UUID recovery via `SidecarManager`)

**MeasurementAnnotation domain model**:
```kotlin
data class MeasurementAnnotation(
    val uuid: String,
    val imageUuid: String,
    val type: AnnotationType,
    val label: String? = null,
    val valueMeters: Double? = null,
    val valueSqMeters: Double? = null,
    val valueDegrees: Double? = null,
    val points: List<NormalizedPoint>,  // normalized [0,1] coordinate space
    val bleDeviceId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NormalizedPoint(val x: Float, val y: Float)

enum class AnnotationType { DISTANCE, AREA, ANGLE, LABEL, GRID }
```

**Block property sync** (US-4.1, US-3.8): After saving annotations, a `MeasurementSyncer` writes named measurement values back to the parent block's `properties` map:
- `distance:wall_A` → `3.24 m`
- `area:room_floor` → `14.5 m²`
These become queryable via the existing block property system and usable in `{{measure: living-room-floor.width}}` template syntax.

---

## 3. Canvas / Annotation Layer Architecture

### Pattern: ZoomImage host + stacked Compose Canvas overlay + ViewModel state machine

```
AnnotationEditorScreen (Composable)
├── AnnotationEditorViewModel (owns annotation state, tool mode, undo stack)
│   ├── annotationState: StateFlow<AnnotationEditorState>
│   │   ├── currentTool: AnnotationTool (SELECT | DISTANCE | AREA | ANGLE | LABEL)
│   │   ├── inProgressPoints: List<NormalizedPoint>  — in-flight annotation
│   │   ├── committedAnnotations: List<MeasurementAnnotation>
│   │   └── calibration: Calibration
│   └── ImageAnnotationRepository (suspend fun save, load)
│
└── Box (fillMaxSize) {
    // Layer 1: zoomable image host (handles subsampling of large construction photos)
    ZoomImage(model = localPath, zoomState = rememberZoomState())
    
    // Layer 2: committed annotations (rendered via Canvas DrawScope)
    Canvas(modifier = Modifier.fillMaxSize()) {
        committedAnnotations.forEach { annotation ->
            drawAnnotation(annotation, calibration, imageSize, zoomState.transform)
        }
    }
    
    // Layer 3: in-progress annotation (current tool drag preview)
    Canvas(modifier = Modifier.fillMaxSize()
        .pointerInput(currentTool) {
            detectAnnotationGestures(
                onTap = { viewModel.addPoint(it.toNormalized(imageSize, zoomState)) },
                onDrag = { viewModel.updateInProgressPoint(it.toNormalized(...)) },
            )
        }
    ) {
        drawInProgressAnnotation(inProgressPoints, currentTool)
    }
    
    // Layer 4: measurement labels (separate pass to avoid clipping)
    MeasurementLabelOverlay(annotations, calibration, zoomState)
    
    // Layer 5: tool palette + calibration controls
    AnnotationToolbar(currentTool, onToolSelected = viewModel::setTool)
}
```

**Coordinate handling**: All points stored in normalized [0,1] space relative to the image dimensions. The `zoomState.transform` matrix is applied at draw time only — annotations never store screen coordinates. This makes annotations resolution-independent and correct across zoom levels.

**Text labels** use `TextMeasurer` (available in `commonMain` via Compose Multiplatform 1.6+) to draw leader-line callouts on the `Canvas`.

**30 fps target (NFR-1)**: The annotation `Canvas` recomposes only when `annotationState` changes. The `ZoomImage` scroll/zoom transform is a `GraphicsLayer` transform applied by the Compose runtime — it does not trigger annotation recomposition. This meets the NFR-1 target on Snapdragon 695 because gesture-driven panning is at the `GraphicsLayer` level (hardware-accelerated, ~60 fps), and annotation redraw only occurs on pointer events.

**Baked export**: When exporting to Google Drive, `GraphicsLayer.toImageBitmap()` (Compose 1.7+) captures the composable tree into a pixel buffer in `commonMain` without Skiko direct access.

---

## 4. Sensor Abstraction Layer

### Pattern: `expect interface` + `actual` per platform, following the existing `AudioRecorder` / `FileSystem` pattern

**commonMain interfaces** (`platform/sensor/`):

```kotlin
// CameraProvider.kt
interface CameraProvider {
    val isAvailable: Boolean
    suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile>
    suspend fun startPreview(): Flow<PlatformImageFrame>
    suspend fun stopPreview()
}

// DepthSensorProvider.kt
interface DepthSensorProvider {
    val isAvailable: Boolean
    /** Returns a depth confidence map aligned to the current camera frame, or null if no hardware depth. */
    suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?>
}

data class DepthFrame(
    val confidenceMap: FloatArray,   // [0,1] per pixel, same resolution as camera frame
    val depthMeters: FloatArray,     // metric depth per pixel
    val width: Int,
    val height: Int,
)

// MotionSensorProvider.kt (accelerometer + compass + GPS)
interface MotionSensorProvider {
    val sensorDataFlow: Flow<ImageSensorData>
    fun startSensing()
    fun stopSensing()
}

// PlatformImageFile.kt
data class PlatformImageFile(val path: String, val mimeType: String = "image/jpeg")

// DomainError extension
sealed interface SensorError : DomainError {
    data class PermissionDenied(override val message: String) : SensorError
    data class HardwareUnavailable(override val message: String) : SensorError
    data class CaptureFailed(override val message: String) : SensorError
}
```

**Platform implementations**:

| Source set | `CameraProvider` | `DepthSensorProvider` | `MotionSensorProvider` |
|---|---|---|---|
| `androidMain` | `CameraXCameraProvider` (CameraX 1.5 via CameraK) | `ARCoreDepthProvider` (ARCore Depth API; falls back to `NoOpDepthProvider`) | `AndroidMotionSensorProvider` (SensorManager + FusedLocationProvider) |
| `iosMain` | `AVFoundationCameraProvider` (via CameraK) | `LidarDepthProvider` (ARKit LiDAR, falls back to `NoOpDepthProvider`) | `IOSMotionSensorProvider` (CoreMotion + CoreLocation) |
| `jvmMain` | `WebcamCameraProvider` (JavaCV / MediaPipe camera) | `NoOpDepthProvider` | `NoOpMotionSensorProvider` |
| `wasmJsMain` | `WebApiCameraProvider` (MediaDevices.getUserMedia) | `NoOpDepthProvider` | `GeolocationMotionProvider` (browser Geolocation API) |

**No-op stubs** ensure graceful degradation (NFR-5):
```kotlin
class NoOpDepthProvider : DepthSensorProvider {
    override val isAvailable = false
    override suspend fun acquireDepthFrame() = null.right()
}
```

**Injection**: `SensorModule` (a new class alongside `RepositoryFactory`) provides sensor implementations; wired in the platform entry point the same way `DriverFactory` is wired today.

---

## 5. External Device Protocol Layer (Laser Rangefinders)

### Pattern: `ExternalMeasurementDevice` interface + `DeviceRegistry` plugin registry

The existing `PluginHost` system provides a registration pattern but is JS-oriented. The measurement device layer uses a narrower, purpose-built interface:

```kotlin
// commonMain: platform/measurement/ExternalMeasurementDevice.kt
interface ExternalMeasurementDevice {
    val deviceId: String          // stable ID (BLE address, USB path, etc.)
    val displayName: String
    val protocol: DeviceProtocol
    val connectionState: StateFlow<DeviceConnectionState>

    suspend fun connect(): Either<DomainError.SensorError, Unit>
    suspend fun disconnect()
    /** Suspends until a measurement reading arrives, then returns it. */
    suspend fun readMeasurement(): Either<DomainError.SensorError, MeasurementReading>
    /** Streaming mode: emits each reading as it arrives. */
    fun measurementFlow(): Flow<Either<DomainError.SensorError, MeasurementReading>>
}

enum class DeviceProtocol { BLE_GATT, USB_SERIAL, USB_HID, BT_HID_KEYBOARD }

data class MeasurementReading(
    val valueMeters: Double,
    val rawString: String,         // e.g. "3.245 m" from device; for audit log
    val timestamp: Instant,
    val unitFromDevice: String,    // as reported by device before conversion
)

enum class DeviceConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }

// Device scanner — platform-specific
interface MeasurementDeviceScanner {
    fun scan(): Flow<ExternalMeasurementDevice>
    suspend fun stopScan()
}

// Registry
object MeasurementDeviceRegistry {
    private val factories = mutableListOf<MeasurementDeviceFactory>()

    fun register(factory: MeasurementDeviceFactory) { factories.add(factory) }
    fun createScanner(): MeasurementDeviceScanner = CompositeMeasurementDeviceScanner(factories)
}

interface MeasurementDeviceFactory {
    val supportedProtocols: Set<DeviceProtocol>
    fun createScanner(): MeasurementDeviceScanner
}
```

**Built-in implementations** (registered at platform startup):

| Implementation | Platform | Protocol | Covers |
|---|---|---|---|
| `KableBleDeviceFactory` | android / ios / macOS | `BLE_GATT` | Leica DISTO, Bosch GLM (Kable library) |
| `AndroidUsbSerialDeviceFactory` | androidMain | `USB_SERIAL` | FTDI/CDC/CH340 devices via usb-serial-for-android |
| `AndroidUsbHidDeviceFactory` | androidMain | `USB_HID` | USB HID class devices |
| `KeyboardEmulationDeviceFactory` | commonMain | `BT_HID_KEYBOARD` | Devices that type readings as keystrokes; parsed by text interceptor |

**Parsing**: Each factory includes a device-specific `ReadingParser` that converts raw bytes or text to `MeasurementReading`. The Leica DISTO BLE GATT parser targets service UUID `3ab10100-f831-4395-b29d-570977d5bf94` (custom DISTO profile).

**Extensibility** (US-6.3): New sensor types register a `MeasurementDeviceFactory` via `MeasurementDeviceRegistry.register()` — no core annotation logic changes.

---

## 6. Google Integration Architecture

### Pattern: Platform-agnostic OAuth token interface + Ktor REST client in commonMain

**Token management** — `expect`/`actual` backed by platform credential storage (NFR-4):

```kotlin
// commonMain
interface GoogleTokenStore {
    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Instant)
    suspend fun getAccessToken(): String?
    suspend fun getRefreshToken(): String?
    suspend fun clearTokens()
}
```

| Platform | `actual` implementation | Storage |
|---|---|---|
| `androidMain` | `AndroidGoogleTokenStore` | Android Keystore (EncryptedSharedPreferences) — follows existing `EncryptionManager` pattern |
| `iosMain` | `IosGoogleTokenStore` | iOS Keychain |
| `jvmMain` | `JvmGoogleTokenStore` | system keyring via `java.security.KeyStore` or SecretService DBus |

**OAuth flow**:
- **Android**: Credential Manager API (`GetGoogleIdOption`) for token acquisition; no WebView needed
- **iOS**: `ASWebAuthenticationSession` for the authorization code flow
- **Desktop**: local HTTP redirect receiver on `localhost:PORT` for the authorization code; Ktor `embeddedServer` spins up for the redirect

**API client** — `commonMain` Ktor client:
```kotlin
class GoogleApiClient(
    private val tokenStore: GoogleTokenStore,
    private val httpClient: HttpClient,  // Ktor, engine injected per platform
) {
    // Google Photos Library API v1
    suspend fun listPhotos(pageToken: String? = null): Either<DomainError.NetworkError, PhotoListResponse>
    suspend fun downloadPhoto(baseUrl: String, destPath: String): Either<DomainError, Unit>

    // Google Drive API v3
    suspend fun listDriveFiles(folderId: String? = null): Either<DomainError.NetworkError, DriveFileListResponse>
    suspend fun uploadFile(name: String, mimeType: String, bytes: ByteArray, parentFolderId: String?): Either<DomainError.NetworkError, DriveFile>
    suspend fun downloadFile(fileId: String, destPath: String): Either<DomainError, Unit>
}
```

The Ktor client handles token refresh transparently via an `Auth` plugin with a token refresh interceptor.

**No mandatory internet** (NFR-6): The `GoogleApiClient` is only instantiated when the user initiates a cloud operation. All import/export is gated behind an explicit user action.

---

## 7. Repository Pattern Extension

### ImageRepository and MeasurementRepository

Following the exact patterns of `SqlDelightBlockRepository` and `SqlDelightPageRepository`:

```kotlin
// commonMain: repository/ImageAnnotationRepository.kt
interface ImageAnnotationRepository {
    fun getAllImageAnnotations(): Flow<Either<DomainError, List<ImageAnnotation>>>
    fun getImageAnnotationsByPage(pageUuid: String): Flow<Either<DomainError, List<ImageAnnotation>>>
    fun getImageAnnotationByUuid(uuid: String): Flow<Either<DomainError, ImageAnnotation?>>
    fun getImageAnnotationsByTag(tag: String): Flow<Either<DomainError, List<ImageAnnotation>>>

    @DirectRepositoryWrite
    suspend fun saveImageAnnotation(annotation: ImageAnnotation): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteImageAnnotation(uuid: String): Either<DomainError, Unit>
}

// commonMain: repository/MeasurementAnnotationRepository.kt
interface MeasurementAnnotationRepository {
    fun getMeasurementsForImage(imageUuid: String): Flow<Either<DomainError, List<MeasurementAnnotation>>>
    fun getMeasurementByUuid(uuid: String): Flow<Either<DomainError, MeasurementAnnotation?>>

    @DirectRepositoryWrite
    suspend fun saveMeasurement(measurement: MeasurementAnnotation): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun saveMeasurements(measurements: List<MeasurementAnnotation>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteMeasurement(uuid: String): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteAllMeasurementsForImage(imageUuid: String): Either<DomainError, Unit>
}
```

**Write routing** through `DatabaseWriteActor` — new `WriteRequest` variants:
```kotlin
class SaveImageAnnotation(
    val annotation: ImageAnnotation,
    override val priority: Priority = Priority.HIGH,
    override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
) : WriteRequest()

class SaveMeasurements(
    val measurements: List<MeasurementAnnotation>,
    override val priority: Priority = Priority.HIGH,
    override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
) : WriteRequest()
```

**Dispatcher**: All reads use `.asFlow().mapToList(PlatformDispatcher.DB)` or `.flowOn(PlatformDispatcher.DB)`. All writes use `withContext(PlatformDispatcher.DB)` inside the repository method body.

**`RestrictedDatabaseQueries` additions**: Every new `INSERT`/`UPDATE`/`DELETE` query in `SteleDatabase.sq` for the two new tables gets a forwarding stub annotated `@DirectSqlWrite`.

**`RepositoryFactory` additions**:
```kotlin
fun createImageAnnotationRepository(backend: GraphBackend): ImageAnnotationRepository
fun createMeasurementAnnotationRepository(backend: GraphBackend): MeasurementAnnotationRepository
```

Both have `IN_MEMORY` implementations for tests and `SQLDELIGHT` implementations for production.

**`RepositorySet` extension**: Add `imageAnnotationRepository` and `measurementAnnotationRepository` fields.

---

## 8. Image Storage Strategy

### Local-first, path-reference only — no blobs in SQLDelight

**Storage location** per platform:

| Platform | Image directory | Notes |
|---|---|---|
| Android | `<graph-path>/.stelekit/images/` (internal app-private) OR `<graph-path>/assets/images/` (user-visible, SAF-accessible) | Follow `FileRegistry` pattern; prefer `assets/images/` so images are visible in file managers and survive graph export |
| iOS | `<graph-path>/assets/images/` (iCloud-eligible if graph is in iCloud Drive) | |
| Desktop/JVM | `<graph-path>/assets/images/` | Consistent with Logseq asset convention |

**File naming**: `<yyyy-MM-dd>-<uuid-prefix>.jpg` — date prefix for human readability, uuid suffix for uniqueness.

**`FileSystem` extension**: Add `readFileBytes(path)` and `writeFileBytes(path, data)` to the existing `FileSystem` interface (stubs already exist — they throw `UnsupportedOperationException`). Platform implementations fill in real byte-level IO. The image import pipeline uses these.

**Image import flow**:
1. Camera capture → `CameraProvider.capturePhoto()` → `PlatformImageFile` (temp path)
2. `ImageImportService.import(tempFile, targetGraphPath)` copies bytes to `assets/images/<name>.jpg` via `FileSystem.writeFileBytes()`
3. SQLDelight `ImageAnnotation` row inserted with `local_path` = relative path from graph root
4. Sidecar JSON written to `.stelekit/images/<uuid>.measure.json`
5. Parent block created with `blockType = "image_annotation"` and Markdown content `![](../assets/images/<name>.jpg)`

**Reference format in SQLDelight**: `local_path` stores the path relative to the graph root (e.g., `assets/images/2026-05-16-abc.jpg`). Absolute paths are reconstructed as `graphPath + "/" + localPath` at query time. This matches how `block.filePath` works in the existing `Page` model.

**Large image handling**: Images are never stored as BLOBs. `ZoomImage` handles subsampling for display. For Drive export, `FileSystem.readFileBytes()` reads the file and streams it to the Ktor upload request — no in-memory accumulation of full resolution images.

**Sidecar sync on graph import**: `ImageSidecarIndexer` (a companion to `SidecarManager`) walks `<graph>/.stelekit/images/*.measure.json` and rebuilds the `image_annotations` and `measurement_annotations` SQLDelight tables if they are absent — same recovery strategy as UUID sidecar recovery.

---

## Key Architectural Decisions Summary

1. **Image storage is path-reference only, never SQLDelight BLOBs.** Images live at `<graph>/assets/images/` (consistent with Logseq asset convention and graph portability). SQLDelight tables store `local_path` as a relative string. Measurement data has two synchronized representations: SQLDelight tables for fast in-app queries, and `.stelekit/images/<uuid>.measure.json` NDJSON sidecars for graph-portable plain-text storage (NFR-2). The sidecar is the source of truth for export and recovery.

2. **Annotated images are `image_annotation` blocks in the existing block model**, not a parallel entity hierarchy. This reuses the entire existing block rendering, page linking, back-link, search, and export infrastructure. The `ImageAnnotationRepository` is a new repository but it extends `RepositorySet` using exactly the same Arrow `Either`, `DatabaseWriteActor`, and `PlatformDispatcher.DB` patterns already established.

3. **The sensor and external device layers are pure `expect`/`actual` interfaces in `commonMain`**, mirroring the `AudioRecorder`/`FileSystem` pattern. The `MeasurementDeviceRegistry` is a plugin registry (similar to `PluginHost`) that allows new rangefinder protocols to be added by registering a `MeasurementDeviceFactory` — zero changes to annotation core logic. Google OAuth is encapsulated behind a `GoogleTokenStore` `expect` interface backed by platform credential storage, with all REST calls made from a shared Ktor client in `commonMain`.
