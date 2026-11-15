// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Scene families for the held-out harness. [IN_MODEL] matches the solver's model class; every other
 * family breaks one assumption so a tier that only wins in-model is visible (anti-circularity).
 */
enum class SceneFamily {
    IN_MODEL,
    HEIGHTS_ABOVE_PLANE,
    TWO_PLANES,
    TAP_BIAS,
    HIGHER_ORDER_RADIAL,
    NON_SQUARE_PIXELS,
    CROPPED_OR_ZOOMED,
    ROTATED_90,
}

/** One known distance: two taps (normalised) and the true length in metres. */
data class GoldenSegment(val id: String, val p1: NormalizedPoint, val p2: NormalizedPoint, val trueMeters: Double)

/** [training] are the known distances a solver may use; [heldOut] is the withheld distance it is graded on. */
data class GoldenScene(
    val family: SceneFamily,
    val widthPx: Double,
    val heightPx: Double,
    val focalPx: Double,
    val tiltDeg: Double,
    val azimuthDeg: Double,
    /** The division-model `k` a calibration would currently hold for this lens (0 when no correction is known). */
    val solverK: Double,
    val training: List<GoldenSegment>,
    val heldOut: GoldenSegment,
)

/**
 * Pure pinhole simulator: a plane tilted by `tau` about an in-image axis, forward radial distortion
 * `1 + a r^2 + k2 r^4 + k3 r^6` (deliberately not the solver's division model), Gaussian tap noise and the
 * out-of-model families. Camera at the origin looking down +Z, x right, y down.
 */
object GoldenSceneGenerator {
    const val WIDTH = 4000.0
    const val HEIGHT = 3000.0
    const val FOCAL_PX = 3100.0

    /** The plane distance that makes the fronto-parallel scale 4000 px per 0.6 m (the lens-distortion simulation's scene). */
    const val PLANE_DISTANCE_M = FOCAL_PX / (WIDTH / 0.6)

    private const val SPREAD_FRACTION = 0.4
    private const val MAX_ATTEMPTS = 400
    private const val MARGIN = 0.08
    private const val MIN_LENGTH_M = 0.05
    private const val MAX_LENGTH_M = 0.20
    private const val DEG = PI / 180.0

    private class Vec(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec) = Vec(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = Vec(x * s, y * s, z * s)
        infix fun dot(o: Vec) = x * o.x + y * o.y + z * o.z
    }

    private class Plane(val origin: Vec, val e1: Vec, val e2: Vec, val n: Vec) {
        companion object {
            fun tilted(tiltDeg: Double, azimuthDeg: Double, distance: Double): Plane {
                val tau = tiltDeg * DEG
                val phi = azimuthDeg * DEG
                val ax = cos(phi)
                val ay = sin(phi)
                val c = cos(tau)
                val s = sin(tau)
                fun rot(v: Vec): Vec {
                    // Rodrigues rotation about a = (ax, ay, 0).
                    val dot = ax * v.x + ay * v.y
                    val crossX = ay * v.z
                    val crossY = -ax * v.z
                    val crossZ = ax * v.y - ay * v.x
                    return Vec(
                        v.x * c + crossX * s + ax * dot * (1 - c),
                        v.y * c + crossY * s + ay * dot * (1 - c),
                        v.z * c + crossZ * s,
                    )
                }
                return Plane(Vec(0.0, 0.0, distance), rot(Vec(1.0, 0.0, 0.0)), rot(Vec(0.0, 1.0, 0.0)), rot(Vec(0.0, 0.0, 1.0)))
            }
        }
    }

    private class Camera(
        val w: Double,
        val h: Double,
        val fx: Double,
        val fy: Double,
        val cx: Double,
        val cy: Double,
        val a: Double,
        val k2: Double,
        val k3: Double,
    ) {
        fun project(p: Vec): PxPoint? {
            if (p.z <= 0.0) return null
            val x = cx + fx * p.x / p.z
            val y = cy + fy * p.y / p.z
            val half = hypot(w, h) / 2.0
            val r2 = (hypot(x - cx, y - cy) / half).let { it * it }
            val f = 1.0 + a * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2
            return PxPoint(cx + (x - cx) * f, cy + (y - cy) * f)
        }

        fun backProject(px: Double, py: Double, plane: Plane): Pair<Double, Double>? {
            val d = Vec((px - cx) / fx, (py - cy) / fy, 1.0)
            val denom = plane.n dot d
            if (denom <= 1e-9) return null
            val hit = d * ((plane.n dot plane.origin) / denom)
            val rel = hit - plane.origin
            return (rel dot plane.e1) to (rel dot plane.e2)
        }
    }

    /** A segment of [lengthM] centred at world-pixel ([cx], [cy]) on the fronto-parallel golden plane, taps normalised. */
    fun frontoParallelSegment(
        kForward: Double,
        cx: Double,
        cy: Double,
        angleRad: Double,
        lengthM: Double,
    ): Pair<NormalizedPoint, NormalizedPoint> {
        val cam = Camera(WIDTH, HEIGHT, FOCAL_PX, FOCAL_PX, WIDTH / 2, HEIGHT / 2, kForward, 0.0, 0.0)
        val plane = Plane.tilted(0.0, 0.0, PLANE_DISTANCE_M)
        val centre = cam.backProject(cx, cy, plane) ?: error("centre does not hit the plane")
        val pts = listOf(-0.5, 0.5).map {
            val u = centre.first + cos(angleRad) * it * lengthM
            val v = centre.second + sin(angleRad) * it * lengthM
            cam.project(plane.origin + plane.e1 * u + plane.e2 * v) ?: error("point behind camera")
        }
        require(pts.all { it.x in 0.0..WIDTH && it.y in 0.0..HEIGHT }) { "synthetic point falls outside the frame" }
        return NormalizedPoint(pts[0].x / WIDTH, pts[0].y / HEIGHT) to NormalizedPoint(pts[1].x / WIDTH, pts[1].y / HEIGHT)
    }

    /**
     * One random scene of [family] with [nTrain] known distances plus one withheld, or null when no valid
     * layout was found (never happens for sane arguments). [azimuthDeg] null picks a random tilt direction.
     */
    @Suppress("LongParameterList")
    fun random(
        family: SceneFamily,
        rnd: Random,
        nTrain: Int,
        tiltDeg: Double,
        tapNoisePx: Double,
        azimuthDeg: Double? = null,
        spreadFraction: Double = SPREAD_FRACTION,
    ): GoldenScene? {
        repeat(MAX_ATTEMPTS) {
            build(family, rnd, nTrain, tiltDeg, tapNoisePx, azimuthDeg ?: (rnd.nextDouble() * 360.0), spreadFraction)?.let { return it }
        }
        return null
    }

    private class Spec(
        val camera: Camera,
        val planes: List<Plane>,
        val tapBiasPx: Double,
        val zoom: Double,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun build(
        family: SceneFamily,
        rnd: Random,
        nTrain: Int,
        tiltDeg: Double,
        tapNoisePx: Double,
        azimuthDeg: Double,
        spreadFraction: Double,
    ): GoldenScene? {
        val zoom = if (family == SceneFamily.CROPPED_OR_ZOOMED) 1.5 + rnd.nextDouble() else 1.0
        val offX = if (family == SceneFamily.CROPPED_OR_ZOOMED) (rnd.nextDouble() - 0.5) * 0.4 * WIDTH else 0.0
        val offY = if (family == SceneFamily.CROPPED_OR_ZOOMED) (rnd.nextDouble() - 0.5) * 0.4 * HEIGHT else 0.0
        val aspect = if (family == SceneFamily.NON_SQUARE_PIXELS) 1.02 + rnd.nextDouble() * 0.04 else 1.0
        val radial = family == SceneFamily.HIGHER_ORDER_RADIAL
        val a = if (radial) -(0.005 + rnd.nextDouble() * 0.015) else 0.0
        val k2 = if (radial) (rnd.nextDouble() - 0.3) * 0.03 else 0.0
        val k3 = if (radial) (rnd.nextDouble() - 0.5) * 0.02 else 0.0
        val camera = Camera(WIDTH, HEIGHT, FOCAL_PX * zoom, FOCAL_PX * zoom * aspect, WIDTH / 2 + offX, HEIGHT / 2 + offY, a, k2, k3)
        val planes = mutableListOf(Plane.tilted(tiltDeg, azimuthDeg, PLANE_DISTANCE_M))
        if (family == SceneFamily.TWO_PLANES) {
            planes += Plane.tilted(rnd.nextDouble() * 20.0, rnd.nextDouble() * 360.0, PLANE_DISTANCE_M * (0.8 + rnd.nextDouble() * 0.45))
        }
        val bias = if (family == SceneFamily.TAP_BIAS) 1.0 + rnd.nextDouble() else 0.0
        val spec = Spec(camera, planes, bias, zoom)

        val total = nTrain + 1
        val planeIndex = List(total) { if (planes.size == 1) 0 else if (it < 2) it else rnd.nextInt(planes.size) }
        val segments = (0 until total).map { i ->
            segment(family, spec, planeIndex[i], rnd, tapNoisePx, if (i == nTrain) "held" else "s$i") ?: return null
        }
        val training = segments.take(nTrain)
        if (midpointSpread(training) < spreadFraction) return null

        val turned = family == SceneFamily.ROTATED_90
        fun fin(s: GoldenSegment) = if (turned) s.copy(p1 = turnQuarter(s.p1), p2 = turnQuarter(s.p2)) else s
        val width = if (turned) HEIGHT else WIDTH
        val height = if (turned) WIDTH else HEIGHT
        return GoldenScene(
            family, width, height, camera.fx, tiltDeg, azimuthDeg, a,
            training.map(::fin), fin(segments.last()),
        )
    }

    /** 90 degree clockwise turn of a normalised point in a landscape frame into the portrait frame. */
    private fun turnQuarter(p: NormalizedPoint) = NormalizedPoint(1.0 - p.y, p.x)

    @Suppress("LongParameterList")
    private fun segment(
        family: SceneFamily,
        spec: Spec,
        planeIdx: Int,
        rnd: Random,
        tapNoisePx: Double,
        id: String,
    ): GoldenSegment? {
        val cam = spec.camera
        val plane = spec.planes[planeIdx]
        val mx = WIDTH * (MARGIN + rnd.nextDouble() * (1 - 2 * MARGIN))
        val my = HEIGHT * (MARGIN + rnd.nextDouble() * (1 - 2 * MARGIN))
        val (u0, v0) = cam.backProject(mx, my, plane) ?: return null
        val angle = rnd.nextDouble() * PI
        val length = (MIN_LENGTH_M + rnd.nextDouble() * (MAX_LENGTH_M - MIN_LENGTH_M)) / spec.zoom
        val height = if (family == SceneFamily.HEIGHTS_ABOVE_PLANE) PLANE_DISTANCE_M * (0.01 + rnd.nextDouble() * 0.04) else 0.0
        val ends = listOf(-0.5, 0.5).map { t ->
            val u = u0 + cos(angle) * t * length
            val v = v0 + sin(angle) * t * length
            cam.project(plane.origin + plane.e1 * u + plane.e2 * v - plane.n * height) ?: return null
        }
        val extended = if (spec.tapBiasPx == 0.0) ends else extend(ends, spec.tapBiasPx)
        val taps = extended.map { PxPoint(it.x + gauss(rnd) * tapNoisePx, it.y + gauss(rnd) * tapNoisePx) }
        if (taps.any { it.x !in 0.0..WIDTH || it.y !in 0.0..HEIGHT }) return null
        return GoldenSegment(
            id,
            NormalizedPoint(taps[0].x / WIDTH, taps[0].y / HEIGHT),
            NormalizedPoint(taps[1].x / WIDTH, taps[1].y / HEIGHT),
            length,
        )
    }

    /** Pushes both taps outward along the segment, the way a user systematically taps just outside an endpoint. */
    private fun extend(ends: List<PxPoint>, byPx: Double): List<PxPoint> {
        val dx = ends[1].x - ends[0].x
        val dy = ends[1].y - ends[0].y
        val len = hypot(dx, dy).coerceAtLeast(1e-9)
        val ux = dx / len * byPx
        val uy = dy / len * byPx
        return listOf(PxPoint(ends[0].x - ux, ends[0].y - uy), PxPoint(ends[1].x + ux, ends[1].y + uy))
    }

    private fun midpointSpread(segments: List<GoldenSegment>): Double {
        val diag = hypot(WIDTH, HEIGHT)
        val mids = segments.map { ((it.p1.x + it.p2.x) / 2 * WIDTH) to ((it.p1.y + it.p2.y) / 2 * HEIGHT) }
        var best = 0.0
        for (i in mids.indices) for (j in i + 1 until mids.size) {
            best = maxOf(best, hypot(mids[i].first - mids[j].first, mids[i].second - mids[j].second) / diag)
        }
        return best
    }

    private fun gauss(rnd: Random): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}
