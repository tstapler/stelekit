// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.PlaneCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeasurementAdvisorAccuracyTest {
    private val w = 4000.0
    private val h = 3000.0
    private val focal35 = 3100.0 / 5000.0 * 43.2666
    private val points = listOf(NormalizedPoint(0.4, 0.5), NormalizedPoint(0.6, 0.5))

    private fun cal(geometry: GeometryCalibration?) =
        Calibration(CalibrationMethod.MANUAL_REFERENCE, 3100.0 / 0.6, 100, CalibrationBasis.NATIVE_PIXELS, geometry = geometry)

    private fun assess(c: Calibration, focal: Double? = focal35) = MeasurementAdvisor.assess(points, c, w, h, 0.0, 0.0, focal)

    private fun plane(check: Double?) =
        PlaneCalibration(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), "A4 paper", 0.297, 0.21, check)

    @Test
    fun noGeometryMeansNoAccuracyLines() {
        assertTrue(assess(cal(null)).accuracy.isEmpty())
    }

    @Test
    fun heightOffsetReportsItsUncertainty() {
        val r = assess(cal(GeometryCalibration(0.01)))
        assertTrue(r.accuracy.any { it.startsWith("Height offset 10.0 mm applied") && it.contains("about 0.4%") }, r.accuracy.toString())
        assertTrue(r.accuracy.last().startsWith("Estimated error about 0.4%"))
        assertEquals(0.42, r.estimatedErrorPercent, 0.01)
    }

    @Test
    fun heightOffsetWithoutADistanceSaysItIsNotApplied() {
        val r = assess(cal(GeometryCalibration(0.01)), focal = null)
        assertTrue(r.accuracy.any { it.contains("not applied") })
    }

    @Test
    fun goodPlaneCheckIsReportedWithoutAWarning() {
        val r = assess(cal(GeometryCalibration(plane = plane(1.0))))
        assertTrue(r.accuracy.any { it.startsWith("Plane reference: A4 paper") })
        assertTrue(r.accuracy.any { it.startsWith("Centre check off by 1.0 mm") })
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
    }

    @Test
    fun largePlaneCheckWarns() {
        val r = assess(cal(GeometryCalibration(plane = plane(9.0))))
        assertTrue(r.warnings.any { it.startsWith("The plane fit failed its centre check by 9.0 mm") })
        assertTrue(r.estimatedErrorPercent > 2.0)
    }

    @Test
    fun planeWithoutACheckStillListsTheReference() {
        val r = assess(cal(GeometryCalibration(plane = plane(null))))
        assertTrue(r.accuracy.first().startsWith("Plane reference"))
        assertTrue(r.accuracy.none { it.startsWith("Centre check") })
    }
}
