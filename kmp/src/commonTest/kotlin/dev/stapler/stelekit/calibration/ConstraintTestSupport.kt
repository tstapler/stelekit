// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.fail

fun GoldenSegment.toConstraint() = CalibrationConstraint(id, p1, p2, trueMeters)

fun GoldenScene.frame(exifFocal35mmEq: Double? = null) = CaptureFrameInfo(widthPx, heightPx, exifFocal35mmEq)

fun constraint(id: String, x1: Double, y1: Double, x2: Double, y2: Double, meters: Double) =
    CalibrationConstraint(id, NormalizedPoint(x1, y1), NormalizedPoint(x2, y2), meters)

fun <A, B> Either<A, B>.right(): B = when (this) {
    is Either.Right -> value
    is Either.Left -> fail("expected Right but was Left($value)")
}

fun <A, B> Either<A, B>.left(): A = when (this) {
    is Either.Left -> value
    is Either.Right -> fail("expected Left but was Right($value)")
}

val FRAME_4000x3000 = CaptureFrameInfo(4000.0, 3000.0)

/** Metric length of [seg] under [fit], measured the way the app measures: undistort with the fit's k, then plane or scale. */
fun ConstraintFit.measure(seg: GoldenSegment, scene: GoldenScene): Double {
    val a = RadialLens.undistort(seg.p1.x, seg.p1.y, k, scene.widthPx, scene.heightPx)
    val b = RadialLens.undistort(seg.p2.x, seg.p2.y, k, scene.widthPx, scene.heightPx)
    val h = homography ?: return kotlin.math.hypot(b.x - a.x, b.y - a.y) / pixelsPerMeter
    val ma = Homography(h).apply(a) ?: return Double.NaN
    val mb = Homography(h).apply(b) ?: return Double.NaN
    return kotlin.math.hypot(mb.x - ma.x, mb.y - ma.y)
}

fun pctError(predicted: Double, truth: Double) = kotlin.math.abs(predicted - truth) / truth * 100.0

/** Relative size of the tap-noise floor on a segment of [lengthPx] (two taps of [tapPx] each), in percent. */
fun noiseFloorPct(tapPx: Double, lengthPx: Double) = kotlin.math.sqrt(2.0) * tapPx / lengthPx * 100.0

fun GoldenSegment.lengthPx(scene: GoldenScene) =
    kotlin.math.hypot((p2.x - p1.x) * scene.widthPx, (p2.y - p1.y) * scene.heightPx)

/** Held-out error in percent of one fit on one scene. */
fun heldOutErrorPct(fit: ConstraintFit, scene: GoldenScene) = pctError(fit.measure(scene.heldOut, scene), scene.heldOut.trueMeters)

/** The status quo baseline: the first known distance alone (taps undistorted with the scene's k), percent error on the held-out distance. */
fun singleScaleErrorPct(scene: GoldenScene): Double {
    fun len(g: GoldenSegment): Double {
        val a = RadialLens.undistort(g.p1.x, g.p1.y, scene.solverK, scene.widthPx, scene.heightPx)
        val b = RadialLens.undistort(g.p2.x, g.p2.y, scene.solverK, scene.widthPx, scene.heightPx)
        return kotlin.math.hypot(b.x - a.x, b.y - a.y)
    }
    val ref = scene.training.first()
    return pctError(len(scene.heldOut) * ref.trueMeters / len(ref), scene.heldOut.trueMeters)
}
