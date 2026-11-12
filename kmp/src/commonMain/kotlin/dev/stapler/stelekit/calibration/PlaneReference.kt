// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.PlaneCalibration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Rectangles of standard, verifiable size that can stand in for a ruler (long side first). */
enum class ReferenceRectangle(val label: String, val longMeters: Double, val shortMeters: Double) {
    A4("A4 paper (297 x 210 mm)", 0.297, 0.210),
    LETTER("US Letter paper (11 x 8.5 in)", 0.2794, 0.2159),
    ID1("ID-1 card (85.60 x 53.98 mm)", 0.0856, 0.05398),
}

/** Four corners tapped in order around a [rectangle]; the first edge (corner 1 to 2) is its long or short side. */
data class PlaneSession(
    val rectangle: ReferenceRectangle = ReferenceRectangle.A4,
    val firstEdgeIsLong: Boolean = true,
    val corners: List<NormalizedPoint> = emptyList(),
    /** Optional fifth tap on the sheet centre, held out of the fit to check it. */
    val checkPoint: NormalizedPoint? = null,
) {
    val firstEdgeMeters: Double get() = if (firstEdgeIsLong) rectangle.longMeters else rectangle.shortMeters
    val secondEdgeMeters: Double get() = if (firstEdgeIsLong) rectangle.shortMeters else rectangle.longMeters

    /** The first four taps are corners; a fifth is the centre check point; further taps are ignored. */
    fun withCorner(point: NormalizedPoint): PlaneSession = when {
        corners.size < CORNERS -> copy(corners = corners + point)
        checkPoint == null -> copy(checkPoint = point)
        else -> this
    }

    fun withoutLastCorner(): PlaneSession = if (checkPoint != null) copy(checkPoint = null) else copy(corners = corners.dropLast(1))

    /** Every tapped point in order, for the on-image preview. */
    fun allPoints(): List<NormalizedPoint> = corners + listOfNotNull(checkPoint)

    companion object {
        const val CORNERS = 4
    }
}

/** What confirming a [PlaneSession] would do right now. */
data class PlanePreview(
    val calibration: Calibration?,
    val message: String,
    /** Held-out centre-point error in mm, or null when no check point was tapped. */
    val checkResidualMm: Double? = null,
    /** True when [checkResidualMm] is large enough that the corners are probably off. */
    val checkFailed: Boolean = false,
) {
    val canApply: Boolean get() = calibration != null
}

/** Builds the plane calibration from a rectangle's corners. Pure; the editor shows [PlanePreview.message]. */
object PlaneReference {
    private const val MM = 1000.0
    private const val MIN_AREA_FRACTION = 0.001

    /** A held-out centre error above this share of the long edge means the corners or lens model are off. */
    const val CHECK_WARN_FRACTION = 0.02

    const val GUIDANCE: String =
        "Lay the paper or card flat in the same plane as the object, then tap its four corners in order around the " +
            "edge. Corners 1 to 2 run along the edge you pick below. Optionally tap the centre of the sheet as a fifth " +
            "point to check the fit."

    fun preview(session: PlaneSession, existingLensK: Double, widthPx: Double, heightPx: Double): PlanePreview {
        val tapped = session.corners.size
        if (tapped < PlaneSession.CORNERS) return PlanePreview(null, "Tap corner ${tapped + 1} of ${PlaneSession.CORNERS}.")
        val src = session.corners.map { RadialLens.undistort(it.x, it.y, existingLensK, widthPx, heightPx) }
        if (!isConvexInOrder(src) || areaOf(src) < MIN_AREA_FRACTION * widthPx * heightPx) {
            return PlanePreview(null, "Corners must go around the rectangle in order (no crossing) and cover a visible area.")
        }
        val a = session.firstEdgeMeters
        val b = session.secondEdgeMeters
        val dst = listOf(PxPoint(0.0, 0.0), PxPoint(a, 0.0), PxPoint(a, b), PxPoint(0.0, b))
        val fit = HomographyFitter.fit(src, dst) as? HomographyFit.Success
            ?: return PlanePreview(null, "These corners cannot define a plane; retap them.")
        val ppm = meanScale(src, a, b)
        val check = session.checkPoint?.let { checkResidualMm(fit.homography, it, a, b, existingLensK, widthPx, heightPx) }
        val failed = check != null && check > CHECK_WARN_FRACTION * session.rectangle.longMeters * MM
        val lens = LensCalibration(k = existingLensK)
        val calibration = Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = ppm,
            confidencePercent = 100,
            basisVersion = CalibrationBasis.NATIVE_PIXELS_LENS,
            lens = lens,
            geometry = GeometryCalibration(
                plane = PlaneCalibration(fit.homography.values, session.rectangle.label, a, b, check),
            ),
        )
        val message = "${session.rectangle.label}: scale about ${round(MM / ppm * 100.0) / 100.0} mm per pixel at the " +
            "reference. Distances are measured on the plane, so tilt across it is corrected."
        return PlanePreview(calibration, message + checkMessage(check, failed), check, failed)
    }

    private fun checkMessage(check: Double?, failed: Boolean): String = when {
        check == null -> ""
        failed -> " Centre check is off by ${oneDecimal(check)} mm: retap the corners or choose the other edge."
        else -> " Centre check: ${oneDecimal(check)} mm."
    }

    private fun oneDecimal(v: Double): String {
        val tenths = round(v * 10.0).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    /** Maps the tapped centre through the fitted plane and returns its distance (mm) from the true centre. */
    private fun checkResidualMm(h: Homography, tap: NormalizedPoint, a: Double, b: Double, k: Double, w: Double, hh: Double): Double? {
        val mapped = h.apply(RadialLens.undistort(tap.x, tap.y, k, w, hh)) ?: return null
        return hypot(mapped.x - a / 2.0, mapped.y - b / 2.0) * MM
    }

    /** Average pixels per metre over the four edges, used wherever a single scale is still needed. */
    private fun meanScale(src: List<PxPoint>, a: Double, b: Double): Double {
        val edges = listOf(a, b, a, b)
        return src.indices.sumOf { i ->
            val n = src[(i + 1) % src.size]
            hypot(n.x - src[i].x, n.y - src[i].y) / edges[i]
        } / src.size
    }

    private fun areaOf(p: List<PxPoint>): Double =
        abs(p.indices.sumOf { i -> p[i].x * p[(i + 1) % p.size].y - p[(i + 1) % p.size].x * p[i].y }) / 2.0

    /** True when every turn has the same sign, i.e. the polygon is convex and not self-crossing. */
    private fun isConvexInOrder(p: List<PxPoint>): Boolean {
        val turns = p.indices.map { i ->
            val o = p[i]
            val q = p[(i + 1) % p.size]
            val r = p[(i + 2) % p.size]
            (q.x - o.x) * (r.y - q.y) - (q.y - o.y) * (r.x - q.x)
        }
        return turns.all { it > 0.0 } || turns.all { it < 0.0 }
    }
}
