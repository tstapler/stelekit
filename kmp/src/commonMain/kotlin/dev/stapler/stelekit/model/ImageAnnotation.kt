package dev.stapler.stelekit.model

import kotlinx.serialization.Serializable

/**
 * Source of the image — how it entered the system.
 */
enum class ImageSource {
    CAMERA,
    FILE,
    GOOGLE_PHOTOS,
    GOOGLE_DRIVE,
}

/**
 * Method used to establish the pixels-per-meter calibration.
 *
 * Listed in descending accuracy order (matches [CalibrationFallbackChain] priority).
 */
enum class CalibrationMethod {
    /** No calibration set — measurements cannot be displayed in real-world units. */
    NONE,
    /** BLE laser rangefinder reading (primary, highest accuracy). */
    BLE_LASER,
    /** User drew a line over a known-length reference object. */
    MANUAL_REFERENCE,
    /** ARCore depth API at the tapped point (±8–10 cm absolute error). */
    ARCORE_DEPTH,
    /** iOS LiDAR sensor (±1–2 cm, high confidence). */
    LIDAR_DEPTH,
    /** EXIF focal-length math (±15%). */
    EXIF_FOCAL,
    /** Monocular ML depth estimation (Depth Anything V2, ±15%). */
    MONOCULAR_ML,
}

/**
 * Calibration state for a single [ImageAnnotation].
 *
 * [pixelsPerMeter] is the conversion factor that maps screen-space pixels (at the image's
 * native resolution) to real-world meters. All [MeasurementAnnotation.valueMeters] values
 * are derived from this single scalar.
 */
@Serializable
data class Calibration(
    val method: CalibrationMethod = CalibrationMethod.NONE,
    val pixelsPerMeter: Double = 0.0,
    /** 0–100 integer confidence level (100 = BLE/manual reference, 20 = EXIF, 15 = ML). */
    val confidencePercent: Int = 0,
)

/**
 * Platform sensor data snapshotted at the moment of image capture.
 *
 * All fields are nullable: a field is null when the corresponding sensor was unavailable
 * or permission was denied at capture time.
 */
@Serializable
data class ImageSensorData(
    val latLng: Pair<Double, Double>? = null,
    val altitudeM: Double? = null,
    val bearingDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val focalLengthMm: Double? = null,
    val focalLength35mmEq: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
)

/**
 * A first-class image annotation entity stored as a `blockType = "image_annotation"` block.
 *
 * [uuid] is the canonical identifier shared between the SQLDelight row, the JSON sidecar
 * file at `<graph>/.stelekit/images/<uuid>.measure.json`, and the on-disk image at
 * `<graph>/assets/images/<date>-<uuid-prefix>.jpg`.
 *
 * Constraints enforced in [init]:
 * - [uuid] must be a non-blank alphanumeric string (see [Validation.validateUuid]).
 * - [blockUuid] and [pageUuid] must satisfy the same constraint.
 */
data class ImageAnnotation(
    val uuid: String,
    val blockUuid: String,
    val pageUuid: String,
    val graphPath: String,
    val filePath: String,
    val thumbnailPath: String? = null,
    val source: ImageSource = ImageSource.FILE,
    val sourceUri: String? = null,
    val capturedAtMs: Long? = null,
    val importedAtMs: Long = 0L,
    val calibration: Calibration = Calibration(),
    val unit: MeasurementUnit = MeasurementUnit.METERS,
    val tags: List<String> = emptyList(),
    val sensorData: ImageSensorData = ImageSensorData(),
) {
    init {
        Validation.validateUuid(uuid)
        Validation.validateUuid(blockUuid)
        Validation.validateUuid(pageUuid)
        require(graphPath.isNotBlank()) { "graphPath cannot be blank" }
        require(filePath.isNotBlank()) { "filePath cannot be blank" }
    }
}
