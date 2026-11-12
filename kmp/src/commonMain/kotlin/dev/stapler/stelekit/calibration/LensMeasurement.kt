package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

/** Confidence statement for one measurement: [warnings] are user-facing sentences, empty when nothing notable. */
data class MeasurementReliability(
    val estimatedErrorPercent: Double,
    val warnings: List<String>,
) {
    val hasWarning: Boolean get() = warnings.isNotEmpty()
}

/**
 * Measures with a [Calibration]. Calibrations without lens data (legacy, basic, native) use the plain
 * pixel distance exactly as before; basis-v2 calibrations undistort each point first.
 */
object LensMeasurement {
    /** Typical uncorrected phone barrel coefficient used to size the "different radius" warning. */
    private const val ASSUMED_UNCORRECTED_K = 0.01
    private const val CORRECTED_RESIDUAL_FRACTION = 0.25
    private const val WARN_ERROR_PERCENT = 0.5
    private const val WARN_RESIDUAL_PERCENT = 1.0
    private const val RADIAL_DIFF_FACTOR = 2.0
    private const val PERCENT = 100.0
    private const val PLANE_CELLS = 9

    const val DEPTH_ADVICE: String =
        "Anything raised above the ruler's surface (a thick object on top of the ruler) looks larger; " +
            "software cannot correct this."

    const val SHOOTING_GUIDANCE: String =
        "For the most accurate scale: lay the ruler beside the object on the same flat surface, shoot straight " +
            "down (camera square-on), keep the object near the centre of the frame, fill the frame, and mark 3 or " +
            "more ruler ticks spread over the longest span you can."

    private fun k(calibration: Calibration): Double = calibration.lens?.k ?: 0.0

    private fun pts(points: List<NormalizedPoint>, calibration: Calibration, w: Double, h: Double): List<PxPoint> =
        points.map { RadialLens.undistort(it.x, it.y, k(calibration), w, h) }

    /** Distance in metres between two points, or null when [calibration] has no usable scale. */
    fun distanceMeters(a: NormalizedPoint, b: NormalizedPoint, calibration: Calibration, w: Double, h: Double): Double? {
        val ppm = calibration.pixelsPerMeter
        if (!ppm.isFinite() || ppm <= 0.0) return null
        val (p, q) = pts(listOf(a, b), calibration, w, h)
        val plane = planeOf(calibration)
        if (plane == null) return hypot(q.x - p.x, q.y - p.y) / ppm
        val mp = plane.apply(p) ?: return null
        val mq = plane.apply(q) ?: return null
        return hypot(mq.x - mp.x, mq.y - mp.y)
    }

    private fun planeOf(calibration: Calibration): Homography? =
        calibration.geometry?.plane?.homography?.takeIf { it.size == PLANE_CELLS }?.let(::Homography)

    /** Polygon area in square metres (shoelace over undistorted points), or null without a usable scale. */
    fun areaSquareMeters(points: List<NormalizedPoint>, calibration: Calibration, w: Double, h: Double): Double? {
        val ppm = calibration.pixelsPerMeter
        if (!ppm.isFinite() || ppm <= 0.0 || points.size < 3) return null
        val plane = planeOf(calibration)
        val p = pts(points, calibration, w, h).let { raw ->
            if (plane == null) raw else raw.map { plane.apply(it) ?: return null }
        }
        val unit = if (plane == null) ppm * ppm else 1.0
        var twice = 0.0
        for (i in p.indices) {
            val j = (i + 1) % p.size
            twice += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(twice) / 2.0 / unit
    }

    /**
     * Estimates how trustworthy a measurement of [points] is. Only calibrations that remember their ruler
     * ticks can be assessed; others return no warnings (their error is unknown, not zero).
     */
    fun assess(points: List<NormalizedPoint>, calibration: Calibration, w: Double, h: Double): MeasurementReliability {
        val lens = calibration.lens ?: return MeasurementReliability(0.0, emptyList())
        if (lens.ticks.isEmpty() || points.isEmpty()) return MeasurementReliability(0.0, emptyList())
        val refR2 = lens.ticks.map { RadialLens.radius(it.x, it.y, w, h) }.average().let { it * it }
        val objR2 = points.map { RadialLens.radius(it.x, it.y, w, h) }.average().let { it * it }
        val corrected = lens.k != 0.0
        val radialPercent = RADIAL_DIFF_FACTOR * ASSUMED_UNCORRECTED_K * abs(objR2 - refR2) * PERCENT *
            (if (corrected) CORRECTED_RESIDUAL_FRACTION else 1.0)
        val estimate = max(radialPercent, lens.residualPercent)
        val warnings = buildList {
            if (lens.residualPercent > WARN_RESIDUAL_PERCENT) {
                add("Ruler marks disagree by ${oneDecimal(lens.residualPercent)}%: lens distortion or camera tilt.")
            }
            if (radialPercent >= WARN_ERROR_PERCENT) {
                val why = if (corrected) "lens correction is approximate" else "lens distortion is not corrected"
                add(
                    "Measurement may be off by ~${oneDecimal(estimate)}%: ruler and object are at different " +
                        "distances from the image centre and $why.",
                )
            }
        }
        return MeasurementReliability(estimate, warnings)
    }

    private fun oneDecimal(v: Double): String {
        val tenths = round(v * 10.0).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
