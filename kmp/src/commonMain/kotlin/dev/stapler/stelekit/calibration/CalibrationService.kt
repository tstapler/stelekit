package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure-math calibration computations.
 *
 * All methods are stateless and platform-agnostic. No platform-specific code here.
 * For EXIF-based estimation, use [ExifCalibrationService].
 */
object CalibrationService {

    /**
     * Compute a [Calibration] from a two-point reference line drawn over an object of
     * known real-world length.
     *
     * The pixel start/end are in *normalized* [0,1] image-space coordinates. The
     * [imageWidthPx] / [imageHeightPx] values must be the native resolution of the image
     * (not the on-screen canvas size).
     *
     * @param pixelStart    normalized start point of the reference line ([0,1] space)
     * @param pixelEnd      normalized end point of the reference line ([0,1] space)
     * @param imageWidthPx  image native width in pixels
     * @param imageHeightPx image native height in pixels
     * @param knownLengthMeters real-world length of the reference object in meters
     * @return [Calibration] with [CalibrationMethod.MANUAL_REFERENCE] and
     *         [Calibration.confidencePercent] = 100, or null if the pixel distance is zero.
     */
    fun computeFromReference(
        pixelStart: NormalizedPoint,
        pixelEnd: NormalizedPoint,
        imageWidthPx: Double,
        imageHeightPx: Double,
        knownLengthMeters: Double,
    ): Calibration? {
        require(knownLengthMeters > 0.0) { "knownLengthMeters must be positive, got $knownLengthMeters" }
        require(imageWidthPx > 0.0) { "imageWidthPx must be positive" }
        require(imageHeightPx > 0.0) { "imageHeightPx must be positive" }

        val dx = (pixelEnd.x - pixelStart.x) * imageWidthPx
        val dy = (pixelEnd.y - pixelStart.y) * imageHeightPx
        val pixelDistance = sqrt(dx * dx + dy * dy)

        if (pixelDistance == 0.0) return null

        val pixelsPerMeter = pixelDistance / knownLengthMeters
        return Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = pixelsPerMeter,
            confidencePercent = 100,
        )
    }

    /**
     * Compute a [Calibration] by sampling depth from an [DepthFrame] at a normalized tap point.
     *
     * Used for ARCore depth calibration. The returned [Calibration.confidencePercent] is
     * derived from the ARCore per-pixel confidence value (0–255) scaled to [0,100].
     *
     * @param depthFrame         the ARCore depth frame
     * @param tapPointNormalized normalized image coordinates of the user's tap [0,1]
     * @param imageWidthPx       native image width in pixels (used to compute pixelsPerMeter)
     * @return [Calibration] with [CalibrationMethod.ARCORE_DEPTH], or null if depth
     *         is unavailable at the tap point or confidence is zero.
     */
    fun computeFromDepthFrame(
        depthFrame: DepthFrame,
        tapPointNormalized: NormalizedPoint,
        imageWidthPx: Double,
    ): Calibration? {
        require(imageWidthPx > 0.0) { "imageWidthPx must be positive" }
        if (depthFrame.width == 0 || depthFrame.height == 0) return null

        val px = (tapPointNormalized.x * depthFrame.width).toInt().coerceIn(0, depthFrame.width - 1)
        val py = (tapPointNormalized.y * depthFrame.height).toInt().coerceIn(0, depthFrame.height - 1)
        val idx = py * depthFrame.width + px

        if (idx >= depthFrame.depthMapMm.size) return null
        val depthMm = depthFrame.depthMapMm[idx]
        if (depthMm <= 0f) return null

        val depthM = depthMm / 1000.0

        // pixels per meter at this depth using a simple pin-hole model:
        // 1 meter at distance D subtends imageWidthPx / (D * 2) pixels if FOV = 90°.
        // ARCore depth gives us the actual metric depth so we use it directly as the
        // reference scale: pixelsPerMeter = imageWidthPx / depthM (width : 1m at depth D).
        // This is intentionally conservative — the badge will show ±8–10 cm confidence.
        val pixelsPerMeter = imageWidthPx / depthM

        val confidenceRaw = if (idx < depthFrame.confidenceMap.size) depthFrame.confidenceMap[idx] else 0f
        val confidencePercent = ((confidenceRaw / 255f) * 100).toInt().coerceIn(0, 100)

        if (confidencePercent == 0) return null

        return Calibration(
            method = CalibrationMethod.ARCORE_DEPTH,
            pixelsPerMeter = pixelsPerMeter,
            confidencePercent = confidencePercent,
        )
    }

    /**
     * Compute a [Calibration] from a monocular ML depth map.
     *
     * The depth map is a flat [FloatArray] in row-major order (same dimensions as the image).
     * Values are depth estimates in meters (relative, not absolute — use with caution).
     *
     * @param depthMap           flat FloatArray of depth estimates (meters, relative)
     * @param tapPointNormalized normalized image coordinates of the user's tap [0,1]
     * @param imageWidthPx       native image width in pixels
     * @param imageHeightPx      native image height in pixels
     * @return [Calibration] with [CalibrationMethod.MONOCULAR_ML] and
     *         [Calibration.confidencePercent] = 15, or null if depth is zero at the tap point.
     */
    fun computeFromMLDepth(
        depthMap: FloatArray,
        tapPointNormalized: NormalizedPoint,
        imageWidthPx: Double,
        imageHeightPx: Double,
    ): Calibration? {
        require(imageWidthPx > 0.0) { "imageWidthPx must be positive" }
        require(imageHeightPx > 0.0) { "imageHeightPx must be positive" }

        val mapWidth = imageWidthPx.toInt()
        val mapHeight = imageHeightPx.toInt()

        if (depthMap.size != mapWidth * mapHeight) return null

        val px = (tapPointNormalized.x * mapWidth).toInt().coerceIn(0, mapWidth - 1)
        val py = (tapPointNormalized.y * mapHeight).toInt().coerceIn(0, mapHeight - 1)
        val idx = py * mapWidth + px

        val depthM = depthMap[idx].toDouble()
        if (depthM <= 0.0) return null

        val pixelsPerMeter = imageWidthPx / depthM

        return Calibration(
            method = CalibrationMethod.MONOCULAR_ML,
            pixelsPerMeter = pixelsPerMeter,
            confidencePercent = 15,
        )
    }
}

/**
 * EXIF focal-length based calibration estimation.
 *
 * This object is separate from [CalibrationService] because it operates on [ImageSensorData]
 * which requires no depth frame, and it produces a coarser estimate (±15%).
 */
object ExifCalibrationService {

    /**
     * Estimate a [Calibration] from EXIF focal-length data in [sensorData].
     *
     * Formula (pinhole camera model):
     * ```
     * horizontalFOV = 2 * atan(sensorWidth / (2 * focalLength))
     * pixelsPerMeter at depth D = imageWidth / (2 * D * tan(fovH / 2))
     * ```
     *
     * When [depthHintMeters] is null, a standard reference distance of 2.0 m is used.
     *
     * Returns null if the required EXIF fields are absent.
     *
     * @param sensorData      EXIF data captured at shoot time
     * @param depthHintMeters optional depth hint for the pixel-per-meter conversion
     * @param imageWidthPx    native image width in pixels
     */
    fun estimate(
        sensorData: ImageSensorData,
        imageWidthPx: Double,
        depthHintMeters: Double? = null,
    ): Calibration? {
        // We need focal length. Prefer actual focal length, fall back to 35mm equivalent.
        val focalLengthMm = sensorData.focalLengthMm
        val focal35mm = sensorData.focalLength35mmEq

        if (focalLengthMm == null && focal35mm == null) return null
        if (imageWidthPx <= 0.0) return null

        // Derive horizontal FOV.
        // For actual focal length we also need the sensor width (crop-factor math).
        // We use the 35mm-equivalent to avoid needing the physical sensor size, since
        // full-frame 35mm standard width is 36 mm.
        val fovHalfRadians: Double = if (focal35mm != null && focal35mm > 0.0) {
            atan(36.0 / (2.0 * focal35mm))
        } else {
            // Fall back: use actual focal length assuming a 6.4mm sensor (typical mobile)
            val sensorWidthMm = 6.4
            atan(sensorWidthMm / (2.0 * focalLengthMm!!))
        }

        val depthM = depthHintMeters ?: 2.0
        if (depthM <= 0.0) return null

        // pixelsPerMeter = imageWidth / (2 * depth * tan(fovH/2))
        val pixelsPerMeter = imageWidthPx / (2.0 * depthM * tan(fovHalfRadians))

        if (pixelsPerMeter <= 0.0) return null

        return Calibration(
            method = CalibrationMethod.EXIF_FOCAL,
            pixelsPerMeter = pixelsPerMeter,
            confidencePercent = 20,
        )
    }
}

/**
 * A snapshot of depth data from ARCore (Android) or LiDAR (iOS).
 *
 * All arrays are row-major with dimensions [width] x [height].
 *
 * @param depthMapMm    depth values in millimeters (positive = in front of camera)
 * @param confidenceMap per-pixel confidence [0–255]; 0 = no data, 255 = maximum confidence
 * @param width         map width in pixels
 * @param height        map height in pixels
 */
data class DepthFrame(
    val depthMapMm: FloatArray,
    val confidenceMap: FloatArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthFrame) return false
        return width == other.width &&
            height == other.height &&
            depthMapMm.contentEquals(other.depthMapMm) &&
            confidenceMap.contentEquals(other.confidenceMap)
    }

    override fun hashCode(): Int {
        var result = depthMapMm.contentHashCode()
        result = 31 * result + confidenceMap.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
