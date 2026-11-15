// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.model

import dev.stapler.stelekit.calibration.FocalSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationConstraintCodecTest {
    private val constraint = CalibrationConstraint(
        id = "c1",
        p1 = NormalizedPoint(0.1, 0.2),
        p2 = NormalizedPoint(0.6, 0.25),
        distanceMeters = 0.9,
        label = "door",
        source = ConstraintSource.FROM_MEASUREMENT,
        measurementUuid = "m1",
    )

    private val prior = PriorBasis(
        method = CalibrationMethod.MANUAL_REFERENCE,
        pixelsPerMeter = 1800.0,
        confidencePercent = 100,
        plane = PlaneCalibration(List(9) { if (it % 4 == 0) 0.001 else 0.0 }, "A4 paper", 0.297, 0.21),
        lens = LensCalibration(k = -0.01),
    )

    private val fit = ConstraintFitRecord(
        tier = FitTier.GRADIENT,
        n = 6,
        residualMeanPct = 1.2,
        looPct = 1.8,
        worstId = "c3",
        focalSource = FocalSource.EXIF_ONLY,
        priorBasis = prior,
    )

    private val full = GeometryCalibration(
        heightOffsetMeters = 0.002,
        cameraDistanceMeters = 0.7,
        plane = PlaneCalibration(
            homography = List(9) { it.toDouble() },
            referenceName = "Known distances",
            widthMeters = 1.0,
            heightMeters = 0.8,
            kind = PlaneKind.CONSTRAINT_FIT,
        ),
        constraints = listOf(constraint),
        fit = fit,
    )

    @Test
    fun geometry_should_RoundTripConstraintsFitAndPlaneKind() {
        assertEquals(full, GeometryCalibrationCodec.decode(GeometryCalibrationCodec.encode(full)))
    }

    @Test
    fun oldGeometryJson_should_DecodeWithEmptyConstraintsNoFitAndPaperKind() {
        val old = """{"heightOffsetMeters":0.01,"plane":{"homography":[1,0,0,0,1,0,0,0,1],"referenceName":"A4","widthMeters":0.297,"heightMeters":0.21}}"""
        val decoded = GeometryCalibrationCodec.decode(old)!!
        assertTrue(decoded.constraints.isEmpty())
        assertNull(decoded.fit)
        assertEquals(PlaneKind.PAPER_REFERENCE, decoded.plane!!.kind)
    }

    @Test
    fun paperPlane_should_NotWriteTheKindKey_SoOldFilesStayByteIdentical() {
        val paper = GeometryCalibration(plane = PlaneCalibration(List(9) { 1.0 }, "A4", 0.297, 0.21))
        assertFalse(GeometryCalibrationCodec.encode(paper).contains("kind"))
        assertFalse(GeometryCalibrationCodec.encode(paper).contains("constraints"))
    }

    @Test
    fun unknownKeys_should_BeIgnoredOnDecode() {
        val text = """{"heightOffsetMeters":0.01,"futureThing":{"a":1},"constraints":[],"fit":null}"""
        assertEquals(0.01, GeometryCalibrationCodec.decode(text)!!.heightOffsetMeters)
    }

    @Test
    fun isEmpty_should_BeFalse_WhenAnySingleFieldIsSet() {
        assertTrue(GeometryCalibration().isEmpty())
        val single = listOf(
            GeometryCalibration(heightOffsetMeters = 0.01),
            GeometryCalibration(cameraDistanceMeters = 1.0),
            GeometryCalibration(plane = full.plane),
            GeometryCalibration(constraints = listOf(constraint)),
            GeometryCalibration(fit = fit),
        )
        single.forEach { assertFalse(it.isEmpty(), "$it") }
        // Tripwire: a new GeometryCalibration field must be added to the list above.
        assertEquals(single.size, GeometryCalibration.serializer().descriptor.elementsCount)
    }

    @Test
    fun measurementValue_should_MapFromColumnsAndNormaliseIllegalPairs() {
        assertEquals(MeasurementValue.Computed(1.0), MeasurementValue.fromColumns(1.0, ValueSource.COMPUTED, null))
        assertEquals(MeasurementValue.Computed(1.0), MeasurementValue.fromColumns(1.0, ValueSource.COMPUTED, 0.5))
        assertEquals(MeasurementValue.Overridden(0.9, 0.8741), MeasurementValue.fromColumns(0.9, ValueSource.USER_OVERRIDE, 0.8741))
        assertEquals(MeasurementValue.Overridden(0.9, 0.9), MeasurementValue.fromColumns(0.9, ValueSource.USER_OVERRIDE, null))
        assertNull(MeasurementValue.fromColumns(null, ValueSource.USER_OVERRIDE, 0.5))
    }

    @Test
    fun measurementAnnotation_should_ExposeItsColumnProjectionAsASealedValue() {
        val pts = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.2, 0.2))
        val base = MeasurementAnnotation("m1", "i1", AnnotationType.DISTANCE, pts, valueMeters = 0.9)
        assertEquals(ValueSource.COMPUTED, base.valueSource)
        assertNull(base.originalValueMeters)
        assertEquals(MeasurementValue.Computed(0.9), base.value)
        val overridden = base.withValue(MeasurementValue.Overridden(0.95, 0.9))
        assertEquals(0.95, overridden.valueMeters)
        assertEquals(ValueSource.USER_OVERRIDE, overridden.valueSource)
        assertEquals(0.9, overridden.originalValueMeters)
        assertNotEquals(base, overridden)
    }
}
