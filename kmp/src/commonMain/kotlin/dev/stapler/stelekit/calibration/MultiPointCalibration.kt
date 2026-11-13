package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.LensCalibration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Least-squares scale through 2+ ruler ticks; [residualPercent] is the worst tick error as a percentage of the ruler span. */
data class TickFit(
    val pixelsPerMeter: Double,
    val residualPercent: Double,
    val cost: Double,
    /** Largest tick disagreement (along or across the ruler) in image pixels. */
    val maxResidualPx: Double,
    /** Per-tick disagreement in image pixels (along and across the ruler combined), in tick order. */
    val tickResidualsPx: List<Double> = emptyList(),
)

/** How the radial coefficient is chosen when calibrating from ticks. */
sealed interface LensMode {
    /** No correction: the scale is a plain fit (reproduces single-line behaviour for two ticks). */
    data object Off : LensMode

    /** A user preset / slider value. */
    data class Fixed(val k: Double) : LensMode

    /** Use the matching camera profile's k (see [CameraProfiles.resolve]); per-photo [Auto] when there is none or it fails the self-check. */
    data object Profile : LensMode

    /** Estimate k from the tick residuals when the evidence supports it, else fall back to 0. */
    data object Auto : LensMode
}

/** Multi-tick scale fit and radial-coefficient estimation. Pure and platform-agnostic. */
object MultiPointCalibration {
    private const val K_STEP = 0.005
    private const val REFINE_ITERATIONS = 40
    private const val GOLDEN = 0.6180339887498949
    private const val MIN_VISIBLE_RESIDUAL_PX = 1.0

    /** Auto only searches phone-plausible lenses; a larger fitted k means the ticks are not a straight ruler. */
    private const val AUTO_MAX_ABS_K = 0.05

    /** A genuine ruler leaves at most tap noise after correction; more means the model does not explain the ticks. */
    private const val MAX_RESIDUAL_AFTER_CORRECTION_PX = 4.0
    private const val REQUIRED_COST_REDUCTION = 0.6
    private const val MIN_TICKS_FOR_ESTIMATE = 3
    private const val PERCENT = 100.0

    /** Presets for the lens slider/toggle; names avoid brand claims, values are typical magnitudes. */
    val PRESET_K: Map<String, Double> = mapOf(
        "None" to 0.0,
        "Slight barrel" to -0.005,
        "Typical phone" to -0.01,
        "Wide angle" to -0.02,
    )

    /**
     * Fits `position = a + m * t` where `t` is each undistorted tick projected on the ruler axis, and
     * adds the off-axis scatter to the cost so a bent ruler (lens curvature) is visible to the k search.
     */
    fun fitScale(ticks: List<CalibrationTick>, k: Double, widthPx: Double, heightPx: Double): TickFit? {
        if (ticks.size < 2) return null
        val pts = ticks.map { RadialLens.undistort(it.x, it.y, k, widthPx, heightPx) }
        val pos = ticks.map { it.positionMeters }
        val span = pos.max() - pos.min()
        if (span <= 0.0) return null
        val axis = principalAxis(pts, pos) ?: return null
        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        val t = pts.map { (it.x - mx) * axis.first + (it.y - my) * axis.second }
        val perp = pts.map { -(it.x - mx) * axis.second + (it.y - my) * axis.first }
        val meanPos = pos.average()
        val varT = t.sumOf { it * it }
        if (varT <= 0.0) return null
        val m = t.indices.sumOf { t[it] * (pos[it] - meanPos) } / varT
        if (m <= 0.0) return null
        val along = t.indices.map { pos[it] - meanPos - m * t[it] }
        val cost = along.sumOf { it * it } + perp.sumOf { (it * m) * (it * m) }
        val maxPx = max(along.maxOf { abs(it) } / m, perp.maxOf { abs(it) })
        val perTick = along.indices.map { hypot(along[it] / m, perp[it]) }
        return TickFit(1.0 / m, along.maxOf { abs(it) } / span * PERCENT, cost, maxPx, perTick)
    }

    /**
     * Estimates k by grid search plus golden-section refinement of the fit cost. Returns 0 unless there
     * are 3+ ticks, the uncorrected marks disagree by at least a pixel, the fit removes most of that error and leaves at most tap noise.
     */
    fun estimateK(ticks: List<CalibrationTick>, widthPx: Double, heightPx: Double): Double {
        if (ticks.size < MIN_TICKS_FOR_ESTIMATE) return 0.0
        val base = fitScale(ticks, 0.0, widthPx, heightPx) ?: return 0.0
        fun cost(k: Double) = fitScale(ticks, k, widthPx, heightPx)?.cost ?: Double.MAX_VALUE
        val steps = (AUTO_MAX_ABS_K / K_STEP).toInt()
        val best = (-steps..steps).map { it * K_STEP }.minBy { cost(it) }
        val refined = refine(best - K_STEP, best + K_STEP, ::cost)
        val corrected = fitScale(ticks, refined, widthPx, heightPx) ?: return 0.0
        val accept = base.maxResidualPx >= MIN_VISIBLE_RESIDUAL_PX &&
            corrected.cost <= base.cost * (1.0 - REQUIRED_COST_REDUCTION) &&
            corrected.maxResidualPx <= MAX_RESIDUAL_AFTER_CORRECTION_PX
        return if (accept) refined else 0.0
    }

    /**
     * Builds a basis-v2 [Calibration] from ruler ticks. Returns null when the ticks cannot define a scale.
     * The returned `pixelsPerMeter` is in the same pixel units as [widthPx] x [heightPx].
     */
    fun calibrate(
        ticks: List<CalibrationTick>,
        widthPx: Double,
        heightPx: Double,
        mode: LensMode,
        profile: CameraProfile? = null,
    ): Calibration? {
        val applied = (CameraProfiles.resolve(profile, ticks, widthPx, heightPx) as? ProfileOutcome.Applied)
            ?.profile?.takeIf { mode == LensMode.Profile }
        val k = when {
            applied != null -> applied.k
            mode == LensMode.Off -> 0.0
            mode is LensMode.Fixed -> mode.k.coerceIn(-RadialLens.MAX_ABS_K, RadialLens.MAX_ABS_K)
            else -> estimateK(ticks, widthPx, heightPx)
        }
        val fit = fitScale(ticks, k, widthPx, heightPx) ?: return null
        return Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = fit.pixelsPerMeter,
            confidencePercent = 100,
            basisVersion = CalibrationBasis.NATIVE_PIXELS_LENS,
            lens = LensCalibration(
                k, ticks, fit.residualPercent,
                kEstimated = applied == null && mode != LensMode.Off && mode !is LensMode.Fixed && k != 0.0,
                profileId = applied?.id,
                profileLabel = applied?.label,
            ),
        )
    }

    private fun principalAxis(pts: List<PxPoint>, pos: List<Double>): Pair<Double, Double>? {
        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        val sxx = pts.sumOf { (it.x - mx) * (it.x - mx) }
        val syy = pts.sumOf { (it.y - my) * (it.y - my) }
        val sxy = pts.sumOf { (it.x - mx) * (it.y - my) }
        if (hypot(sxx, syy) == 0.0) return null
        val angle = 0.5 * atan2(2.0 * sxy, sxx - syy)
        var ax = cos(angle)
        var ay = sin(angle)
        val meanPos = pos.average()
        val sign = pts.indices.sumOf { ((pts[it].x - mx) * ax + (pts[it].y - my) * ay) * (pos[it] - meanPos) }
        if (sign < 0.0) {
            ax = -ax
            ay = -ay
        }
        return ax to ay
    }

    private fun refine(lo0: Double, hi0: Double, cost: (Double) -> Double): Double {
        var lo = lo0
        var hi = hi0
        repeat(REFINE_ITERATIONS) {
            val a = hi - GOLDEN * (hi - lo)
            val b = lo + GOLDEN * (hi - lo)
            if (cost(a) < cost(b)) hi = b else lo = a
        }
        return (lo + hi) / 2.0
    }
}
