package dev.stapler.stelekit.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.serialization.Serializable

/**
 * The geometric/semantic type of a single measurement overlay on an image.
 */
enum class AnnotationType {
    DISTANCE,
    AREA,
    ANGLE,
    LABEL,
    GRID_REF,
}

/**
 * Display unit for measurement values.
 */
enum class MeasurementUnit {
    METERS,
    CENTIMETERS,
    MILLIMETERS,
    FEET,
    INCHES,
    /**
     * US construction format: feet and inches with fractional inches to the nearest 1/16".
     * Example: 1.6256 m → "5' 4 1/4\"" (5 feet, 4.25 inches).
     */
    FEET_INCHES,
    ;

    /** Short symbol shown on measurement labels and in export data. */
    fun symbol(): String = when (this) {
        METERS -> "m"
        CENTIMETERS -> "cm"
        MILLIMETERS -> "mm"
        FEET -> "ft"
        INCHES -> "in"
        FEET_INCHES -> "ft-in"
    }

    /** Human-readable name for UI pickers (e.g. unit dropdown). */
    fun displayName(): String = when (this) {
        METERS -> "Meters (m)"
        CENTIMETERS -> "Centimeters (cm)"
        MILLIMETERS -> "Millimeters (mm)"
        FEET -> "Feet (ft)"
        INCHES -> "Inches (in)"
        FEET_INCHES -> "Feet & Inches (ft-in)"
    }
}

/**
 * A normalized 2-D point in image space, where (0.0, 0.0) is the top-left corner
 * and (1.0, 1.0) is the bottom-right corner.
 *
 * Points are stored in normalized space so they remain valid regardless of
 * display resolution, zoom level, or future image rescaling.
 */
@Serializable
data class NormalizedPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x in 0.0..1.0) { "NormalizedPoint.x must be in [0,1], got $x" }
        require(y in 0.0..1.0) { "NormalizedPoint.y must be in [0,1], got $y" }
    }
}

/**
 * A single measurement overlay attached to one [ImageAnnotation].
 *
 * [normalizedPoints] are stored in image-normalized [0,1] space.
 * [valueMeters] is the computed real-world value in SI base unit (meters for distance,
 * square meters for area, degrees for angle).
 * [valueDisplay] is the pre-formatted string shown to the user, e.g. "3.2 m" or "6.0 m²".
 */
data class MeasurementAnnotation(
    val uuid: String,
    val imageUuid: String,
    val annotationType: AnnotationType,
    val normalizedPoints: List<NormalizedPoint>,
    val valueMeters: Double? = null,
    val valueDisplay: String? = null,
    val label: String? = null,
    val colorHex: String = "#FF0000",
    val bleDeviceId: String? = null,
) {
    init {
        Validation.validateUuid(uuid)
        Validation.validateUuid(imageUuid)
        require(normalizedPoints.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }) {
            "All normalizedPoints must be in [0,1] range"
        }
        when (annotationType) {
            AnnotationType.DISTANCE -> require(normalizedPoints.size == 2) {
                "DISTANCE annotation requires exactly 2 points, got ${normalizedPoints.size}"
            }
            AnnotationType.ANGLE -> require(normalizedPoints.size == 3) {
                "ANGLE annotation requires exactly 3 points, got ${normalizedPoints.size}"
            }
            AnnotationType.AREA -> require(normalizedPoints.size >= 3) {
                "AREA annotation requires at least 3 points, got ${normalizedPoints.size}"
            }
            AnnotationType.LABEL, AnnotationType.GRID_REF -> require(normalizedPoints.size == 1) {
                "${annotationType} annotation requires exactly 1 point, got ${normalizedPoints.size}"
            }
        }
    }
}

// ── Unit conversion helpers ───────────────────────────────────────────────────

private const val METERS_TO_FEET = 3.280839895
private const val METERS_TO_INCHES = 39.3700787402

/**
 * Convert [meters] to the display value in [unit].
 *
 * Returns the raw numeric value — format/round at the call site.
 * For [MeasurementUnit.FEET_INCHES] returns raw feet; use [formatFeetInches] for display.
 */
fun metersToDisplay(meters: Double, unit: MeasurementUnit): Double = when (unit) {
    MeasurementUnit.METERS -> meters
    MeasurementUnit.CENTIMETERS -> meters * 100.0
    MeasurementUnit.MILLIMETERS -> meters * 1000.0
    MeasurementUnit.FEET -> meters * METERS_TO_FEET
    MeasurementUnit.INCHES -> meters * METERS_TO_INCHES
    MeasurementUnit.FEET_INCHES -> meters * METERS_TO_FEET
}

/**
 * Format [meters] as a human-readable string in the given [unit].
 *
 * For [MeasurementUnit.FEET_INCHES], produces US construction format with fractional
 * inches to the nearest 1/16": e.g. `1.6256 m` → `"5' 4 1/4\""`.
 *
 * For [MeasurementUnit.INCHES], produces fractional display to the nearest 1/16":
 * e.g. `0.3175 m` → `"12 1/2\""`.
 *
 * All other units round to 3 significant decimal places.
 */
fun metersToDisplayString(meters: Double, unit: MeasurementUnit): String {
    if (meters.isNaN() || meters.isInfinite()) return "—"
    return when (unit) {
        MeasurementUnit.FEET_INCHES -> formatFeetInches(meters)
        MeasurementUnit.INCHES -> formatFractionalInches(meters * METERS_TO_INCHES)
        else -> {
            val value = metersToDisplay(meters, unit)
            "${roundToDecimals(value, 3)} ${unit.symbol()}"
        }
    }
}

/**
 * Format [meters] as `N' [M][F]"` where N = whole feet, M = whole inches, F = fractional inches.
 *
 * Fractional inches are rounded to the nearest 1/16" and displayed as a reduced fraction
 * (e.g. `4/16` → `1/4`). Zero fractions are omitted. When remaining inches are exactly zero
 * the `"` suffix is still emitted for consistent units: `"6' 0\""`.
 *
 * Examples:
 * - 1.63195 m → `"5' 4 1/4\""`  (5 ft + 4.25 in)
 * - 1.8288 m  → `"6' 0\""`       (exactly 6 feet)
 * - 0.3175 m  → `"1' 1/2\""`     (1 ft + 0.5 in; zero whole inches omitted)
 */
internal fun formatFeetInches(meters: Double): String {
    val totalInches = meters * METERS_TO_INCHES
    val wholeFeet = totalInches.toLong() / 12L
    val remainingInches = totalInches - wholeFeet * 12.0
    return "${wholeFeet}' ${formatFractionalInches(remainingInches)}"
}

/**
 * Format [inches] as whole inches plus a reduced fraction to the nearest 1/16".
 *
 * Examples: `4.25` → `"4 1/4\""`, `12.0` → `"12\""`, `0.0625` → `"1/16\""`, `0.9375` → `"15/16\""`.
 */
internal fun formatFractionalInches(inches: Double): String {
    val sixteenths = kotlin.math.round(inches * 16.0).toLong()
    val wholeInches = sixteenths / 16L
    val fracSixteenths = sixteenths % 16L
    return if (fracSixteenths == 0L) {
        "$wholeInches\""
    } else {
        val gcd = gcd(fracSixteenths, 16L)
        val num = fracSixteenths / gcd
        val den = 16L / gcd
        if (wholeInches == 0L) "$num/$den\"" else "$wholeInches $num/$den\""
    }
}

private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

private fun roundToDecimals(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return kotlin.math.round(value * factor) / factor
}

/**
 * Compute pixel distance → meters conversion.
 *
 * Returns [Either.Left] when [pixelsPerMeter] is zero (division by zero guard).
 */
fun pixelDistanceToMeters(
    pixelDistance: Double,
    pixelsPerMeter: Double,
): Either<DomainError, Double> {
    if (pixelsPerMeter == 0.0) {
        return DomainError.ValidationError.ConstraintViolation(
            "pixelsPerMeter must be non-zero for measurement conversion"
        ).left()
    }
    return (pixelDistance / pixelsPerMeter).right()
}

/**
 * Compute the area (m²) of a closed polygon defined by [normalizedPoints] in normalized
 * [0,1] image space, scaled by [pixelsPerMeter] to get real-world m².
 *
 * Uses the Shoelace (Gauss's area) formula. Returns 0.0 for degenerate (collinear) inputs.
 *
 * @param imageWidthPx actual image width in pixels (used to denormalize x)
 * @param imageHeightPx actual image height in pixels (used to denormalize y)
 */
fun polygonAreaMeters(
    normalizedPoints: List<NormalizedPoint>,
    pixelsPerMeter: Double,
    imageWidthPx: Double,
    imageHeightPx: Double,
): Double {
    if (normalizedPoints.size < 3 || pixelsPerMeter == 0.0) return 0.0
    val px = normalizedPoints.map { it.x * imageWidthPx }
    val py = normalizedPoints.map { it.y * imageHeightPx }
    val n = px.size
    var area = 0.0
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += px[i] * py[j]
        area -= px[j] * py[i]
    }
    val pixelArea = kotlin.math.abs(area) / 2.0
    return pixelArea / (pixelsPerMeter * pixelsPerMeter)
}

/**
 * Compute the interior angle in degrees at [vertex] formed by [arm1] and [arm2].
 *
 * Returns [Either.Left] when [vertex] coincides with either arm point (zero-length vector).
 */
fun angleBetweenThreePoints(
    arm1: NormalizedPoint,
    vertex: NormalizedPoint,
    arm2: NormalizedPoint,
): Either<DomainError, Double> {
    val v1x = arm1.x - vertex.x
    val v1y = arm1.y - vertex.y
    val v2x = arm2.x - vertex.x
    val v2y = arm2.y - vertex.y
    val len1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
    val len2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)
    if (len1 == 0.0 || len2 == 0.0) {
        return DomainError.ValidationError.ConstraintViolation(
            "Vertex coincides with an arm point — angle is undefined"
        ).left()
    }
    val dot = (v1x * v2x + v1y * v2y) / (len1 * len2)
    val clamped = dot.coerceIn(-1.0, 1.0)
    return (kotlin.math.acos(clamped) * 180.0 / kotlin.math.PI).right()
}
