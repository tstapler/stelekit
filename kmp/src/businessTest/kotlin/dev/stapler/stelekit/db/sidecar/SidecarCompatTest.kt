// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.PlaneKind
import dev.stapler.stelekit.model.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.2 sidecar compatibility: I-E3-01 here, I-E3-03 in Task 3.2c2, I-E3-02 in `FrozenOldReaderDecodeTest`. */
class SidecarCompatTest {
    private val fs = FakeFileSystem()
    private val manager = ImageSidecarManager(fs)
    private val notes = EffectiveNotesPath(SidecarFixtures.GRAPH)
    private val path = manager.sidecarPath(notes, SidecarFixtures.IMAGE_UUID)

    private fun put(json: String) = fs.writeFile(path, json)

    @Test
    fun oldV0113SidecarFixture_should_LoadWithEmptyConstraintsAndComputedValues_AndResaveIdentically() {
        put(SidecarFixtures.V0113)
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        val annotation = sidecar.toDomainAnnotation()
        val measurements = sidecar.toDomainMeasurements()

        assertEquals(1, sidecar.schemaVersion)
        val geometry = annotation.calibration.geometry!!
        assertTrue(geometry.constraints.isEmpty())
        assertNull(geometry.fit)
        assertEquals(PlaneKind.PAPER_REFERENCE, geometry.plane!!.kind)
        assertTrue(measurements.all { it.valueSource == ValueSource.COMPUTED && it.originalValueMeters == null })
        assertEquals(MeasurementValue.Computed(0.8741234567891), measurements.first().value)

        manager.writeSidecar(annotation, measurements)
        assertEquals(SidecarFixtures.V0113, fs.readFile(path))
    }

    @Test
    fun unknownKeyFutureThing_should_SurviveLoadAndSave() {
        val withFuture = SidecarFixtures.V0113
            .replaceFirst("{\"imageAnnotation\":{", "{\"futureTop\":{\"a\":1},\"imageAnnotation\":{\"futureThing\":[1,2],")
            .replaceFirst("\"valueDisplay\":\"87.4 cm\"", "\"valueDisplay\":\"87.4 cm\",\"futureMeasurementKey\":\"x\"")
        put(withFuture)
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        manager.writeSidecar(sidecar.toDomainAnnotation(), sidecar.toDomainMeasurements())

        val saved = fs.readFile(path)!!
        assertTrue("\"futureThing\":[1,2]" in saved, saved)
        assertTrue("\"futureTop\":{\"a\":1}" in saved, saved)
        assertTrue("\"futureMeasurementKey\":\"x\"" in saved, saved)
        assertTrue("\"valueMeters\":0.8741234567891" in saved, saved)
    }

    @Test
    fun clearedKnownKeys_should_NotBeResurrectedByTheOverlay() {
        put(SidecarFixtures.V2)
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        val plain = sidecar.toDomainMeasurements().map { it.withValue(MeasurementValue.Computed(0.8741234567891)) }
        manager.writeSidecar(sidecar.toDomainAnnotation(), plain)
        assertTrue("USER_OVERRIDE" !in fs.readFile(path)!!)
    }

    @Test
    fun v2Sidecar_should_LoadItsConstraintFitPlaneKindAndOverride_AndResaveIdentically() {
        put(SidecarFixtures.V2)
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        val annotation = sidecar.toDomainAnnotation()
        val measurements = sidecar.toDomainMeasurements()

        assertEquals(2, sidecar.schemaVersion)
        val geometry = annotation.calibration.geometry!!
        assertEquals(PlaneKind.CONSTRAINT_FIT, geometry.plane!!.kind)
        assertEquals("c1", geometry.constraints.single().id)
        assertEquals(6, geometry.fit!!.n)
        assertEquals(MeasurementValue.Overridden(0.9, 0.8741234567891), measurements.single().value)

        manager.writeSidecar(annotation, measurements)
        assertEquals(SidecarFixtures.V2, fs.readFile(path))
    }

    @Test
    fun unknownKeysNestedInCalibrationGeometry_should_SurviveLoadAndSave() {
        val future = SidecarFixtures.V2
            .replaceFirst("\"calibrationGeometry\":{", "\"calibrationGeometry\":{\"futureGeom\":7,")
            .replaceFirst("\"kind\":\"CONSTRAINT_FIT\"", "\"kind\":\"CONSTRAINT_FIT\",\"futurePlane\":true")
            .replaceFirst("\"label\":\"door\"", "\"label\":\"door\",\"futureConstraint\":\"k\"")
            .replaceFirst("\"worstId\":\"c1\"", "\"worstId\":\"c1\",\"futureFit\":[1]")
        put(future)
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        manager.writeSidecar(sidecar.toDomainAnnotation(), sidecar.toDomainMeasurements())
        val saved = fs.readFile(path)!!
        for (needle in listOf("\"futureGeom\":7", "\"futurePlane\":true", "\"futureConstraint\":\"k\"", "\"futureFit\":[1]")) {
            assertTrue(needle in saved, "$needle lost: $saved")
        }
    }

    @Test
    fun unknownEnumValueFromNewerBuild_should_DegradeToDefaults_NotDropTheGeometry() {
        put(SidecarFixtures.V2.replaceFirst("\"kind\":\"CONSTRAINT_FIT\"", "\"kind\":\"HOLOGRAM\"").replaceFirst("\"tier\":\"GRADIENT\"", "\"tier\":\"WARP\""))
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()
        assertTrue(sidecar != null, "whole sidecar failed to decode")
        val geometry = sidecar.toDomainAnnotation().calibration.geometry!!
        assertEquals("c1", geometry.constraints.single().id)
        assertEquals(6, geometry.fit!!.n)
        assertEquals(PlaneKind.PAPER_REFERENCE, geometry.plane!!.kind)
    }

    @Test
    fun fileAtAHigherSchemaVersion_should_KeepItsVersionWhenResavedHere() {
        put(SidecarFixtures.V2.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":3"))
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        manager.writeSidecar(sidecar.toDomainAnnotation(), sidecar.toDomainMeasurements())
        assertTrue("\"schemaVersion\":3" in fs.readFile(path)!!)
    }
}
