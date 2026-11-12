// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden scene T-G6: a plane tilted about the image x axis, photographed through a pinhole camera
 * (4000x3000, f = 3100 px, 60 cm above the plane origin). Ground truth is metres on the plane.
 */
class PlaneReferenceTest {
    private val w = 4000.0
    private val h = 3000.0
    private val f = 3100.0
    private val distance = 0.6

    private fun project(x: Double, y: Double, tiltDeg: Double): NormalizedPoint {
        val t = tiltDeg * kotlin.math.PI / 180.0
        val z = distance + y * sin(t)
        return NormalizedPoint((w / 2 + f * x / z) / w, (h / 2 + f * y * cos(t) / z) / h)
    }

    /** A4 landscape on the plane at x in [-0.30, -0.003], y in [-0.105, 0.105]. */
    private fun sheetCorners(tilt: Double) = listOf(
        project(-0.30, -0.105, tilt), project(-0.003, -0.105, tilt), project(-0.003, 0.105, tilt), project(-0.30, 0.105, tilt),
    )

    private fun session(tilt: Double) = PlaneSession(corners = sheetCorners(tilt))

    @Test
    fun fiveDegreeTiltIsCorrectedWithinHalfAMillimetre() {
        val calibration = assertNotNull(PlaneReference.preview(session(5.0), 0.0, w, h).calibration)
        // An 18 cm object 25 cm away from the sheet along the tilt axis.
        val a = project(0.07, 0.25, 5.0)
        val b = project(0.25, 0.25, 5.0)
        val measured = assertNotNull(LensMeasurement.distanceMeters(a, b, calibration, w, h))
        assertEquals(0.18, measured, 0.0005)
    }

    @Test
    fun theSingleScaleBaselineIsOffByMoreThanFourMillimetres() {
        val corners = sheetCorners(5.0)
        val lineMeters = 0.297
        val px = hypot((corners[1].x - corners[0].x) * w, (corners[1].y - corners[0].y) * h)
        val ppm = px / lineMeters
        val a = project(0.07, 0.25, 5.0)
        val b = project(0.25, 0.25, 5.0)
        val naive = hypot((b.x - a.x) * w, (b.y - a.y) * h) / ppm
        assertTrue(abs(naive - 0.18) > 0.004, "baseline error ${abs(naive - 0.18)}")
    }

    @Test
    fun noTiltAgreesWithTheScalarScale() {
        val calibration = assertNotNull(PlaneReference.preview(session(0.0), 0.0, w, h).calibration)
        val a = project(0.0, 0.0, 0.0)
        val b = project(0.18, 0.0, 0.0)
        assertEquals(0.18, assertNotNull(LensMeasurement.distanceMeters(a, b, calibration, w, h)), 1e-9)
        assertEquals(f / distance, calibration.pixelsPerMeter, f / distance * 0.02)
    }

    @Test
    fun areaIsMeasuredOnThePlane() {
        val calibration = assertNotNull(PlaneReference.preview(session(5.0), 0.0, w, h).calibration)
        val square = listOf(project(0.05, 0.2, 5.0), project(0.15, 0.2, 5.0), project(0.15, 0.3, 5.0), project(0.05, 0.3, 5.0))
        assertEquals(0.01, assertNotNull(LensMeasurement.areaSquareMeters(square, calibration, w, h)), 0.0002)
    }

    @Test
    fun edgeChoiceSwapsTheRectangleOrientation() {
        val tall = PlaneSession(
            firstEdgeIsLong = false,
            corners = listOf(project(-0.105, -0.25, 0.0), project(0.105, -0.25, 0.0), project(0.105, 0.047, 0.0), project(-0.105, 0.047, 0.0)),
        )
        val calibration = assertNotNull(PlaneReference.preview(tall, 0.0, w, h).calibration)
        val measured = LensMeasurement.distanceMeters(project(-0.105, -0.25, 0.0), project(-0.105, 0.047, 0.0), calibration, w, h)
        assertEquals(0.297, assertNotNull(measured), 1e-6)
    }

    @Test
    fun incompleteOrCrossedCornersCannotApply() {
        assertEquals("Tap corner 1 of 4.", PlaneReference.preview(PlaneSession(), 0.0, w, h).message)
        assertFalse(PlaneReference.preview(PlaneSession(corners = sheetCorners(0.0).take(3)), 0.0, w, h).canApply)
        val c = sheetCorners(0.0)
        val bowtie = PlaneSession(corners = listOf(c[0], c[2], c[1], c[3]))
        assertFalse(PlaneReference.preview(bowtie, 0.0, w, h).canApply)
        val collinear = PlaneSession(corners = listOf(0.1, 0.2, 0.3, 0.4).map { NormalizedPoint(it, 0.5) })
        assertFalse(PlaneReference.preview(collinear, 0.0, w, h).canApply)
    }

    @Test
    fun presetsAreTheDocumentedSizes() {
        assertEquals(0.0856, ReferenceRectangle.ID1.longMeters)
        assertEquals(0.05398, ReferenceRectangle.ID1.shortMeters)
        assertEquals(0.297, ReferenceRectangle.A4.longMeters)
        assertEquals(0.2159, ReferenceRectangle.LETTER.shortMeters)
    }

    private fun centre(tilt: Double) = project(-0.1515, 0.0, tilt)

    @Test
    fun centreCheckOnATiltedSheetIsWithinAMillimetre() {
        val preview = PlaneReference.preview(session(5.0).withCorner(centre(5.0)), 0.0, w, h)
        assertFalse(preview.checkFailed)
        assertTrue(assertNotNull(preview.checkResidualMm) < 1.0, "residual ${preview.checkResidualMm}")
        assertTrue(preview.message.contains("Centre check:"))
        assertNotNull(preview.calibration?.geometry?.plane?.checkResidualMm)
    }

    @Test
    fun aMisplacedCentreTapFailsTheCheckButStillAllowsApply() {
        val off = project(-0.1515 + 0.03, 0.03, 0.0)
        val preview = PlaneReference.preview(session(0.0).withCorner(off), 0.0, w, h)
        assertTrue(preview.checkFailed)
        assertTrue(preview.canApply)
        assertTrue(preview.message.contains("retap the corners"))
    }

    @Test
    fun noCheckPointMeansNoResidual() {
        val preview = PlaneReference.preview(session(0.0), 0.0, w, h)
        assertEquals(null, preview.checkResidualMm)
        assertFalse(preview.checkFailed)
    }

    @Test
    fun fifthTapIsTheCheckPointAndUndoRemovesItFirst() {
        val s = session(0.0).withCorner(NormalizedPoint(0.5, 0.5))
        assertEquals(4, s.corners.size)
        assertEquals(NormalizedPoint(0.5, 0.5), s.checkPoint)
        assertEquals(5, s.allPoints().size)
        assertEquals(s.withCorner(NormalizedPoint(0.1, 0.1)), s)
        assertEquals(null, s.withoutLastCorner().checkPoint)
        assertEquals(4, s.withoutLastCorner().corners.size)
    }
}
