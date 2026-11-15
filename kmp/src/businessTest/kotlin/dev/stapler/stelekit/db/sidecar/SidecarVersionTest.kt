// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.PlaneCalibration
import dev.stapler.stelekit.model.PlaneKind
import dev.stapler.stelekit.model.AnnotationType
import kotlin.test.Test
import kotlin.test.assertEquals

/** I-E3-04: `schemaVersion` is 2 only when v2 data are present, decided by the one `SidecarVersion.forContent`. */
class SidecarVersionTest {
    private val fs = FakeFileSystem()
    private val manager = ImageSidecarManager(fs)
    private val notes = EffectiveNotesPath("/g")
    private val pts = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1))
    private val plane = PlaneCalibration(List(9) { 1.0 }, "A4", 0.297, 0.21)

    private fun image(geometry: GeometryCalibration?) = ImageAnnotation(
        uuid = "verimg01", blockUuid = "verblk01", pageUuid = "verpg01", graphPath = "/g", filePath = "/g/v.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100, CalibrationBasis.NATIVE_PIXELS, geometry = geometry),
    )

    private fun measurement() = MeasurementAnnotation("vermeas1", "verimg01", AnnotationType.DISTANCE, pts, valueMeters = 0.9)

    private fun versionAfterSave(geometry: GeometryCalibration?, measurements: List<MeasurementAnnotation>): Int {
        manager.writeSidecar(image(geometry), measurements)
        return manager.readSidecar(notes, "verimg01").getOrNull()!!.schemaVersion
    }

    @Test
    fun schemaVersion_should_BeTwoOnlyWhenV2DataPresent() {
        val constraint = CalibrationConstraint("c1", pts[0], pts[1], 0.9)
        assertEquals(1, versionAfterSave(null, listOf(measurement())))
        assertEquals(1, versionAfterSave(GeometryCalibration(heightOffsetMeters = 0.002, plane = plane), listOf(measurement())))
        assertEquals(2, versionAfterSave(null, listOf(measurement().withValue(MeasurementValue.Overridden(0.95, 0.9)))))
        assertEquals(2, versionAfterSave(GeometryCalibration(constraints = listOf(constraint)), emptyList()))
        assertEquals(
            2,
            versionAfterSave(GeometryCalibration(fit = ConstraintFitRecord(FitTier.SCALE, 3, 1.0)), emptyList()),
        )
        assertEquals(2, versionAfterSave(GeometryCalibration(plane = plane.copy(kind = PlaneKind.CONSTRAINT_FIT)), emptyList()))
    }

    @Test
    fun forContent_should_BeOne_ForAnEmptyV1Sidecar() {
        val file = SidecarFile(imageAnnotation = SidecarImageAnnotation("a", "b", "c", "/g", "/g/a.jpg"))
        assertEquals(1, SidecarVersion.forContent(file.imageAnnotation, file.measurements))
    }
}
