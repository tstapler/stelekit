// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GeometryCalibration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** U-E3-33: the `setGeometry` predicate change (`!merged.isEmpty()`) must not alter any stored measurement value. */
class GoldenSidecarMeasurementIdentityTest {
    private val fs = FakeFileSystem()
    private val manager = ImageSidecarManager(fs)
    private val notes = EffectiveNotesPath(SidecarFixtures.GRAPH)
    private val path = manager.sidecarPath(notes, SidecarFixtures.IMAGE_UUID)

    /** Mirrors `AnnotationEditorViewModel.setGeometry` after Task 3.7a: edit the height fields only, null when empty. */
    private fun setHeightOffset(current: GeometryCalibration?, meters: Double?): GeometryCalibration? {
        val merged = (current ?: GeometryCalibration()).copy(
            heightOffsetMeters = meters ?: 0.0,
            cameraDistanceMeters = null,
        )
        return merged.takeIf { !it.isEmpty() }
    }

    private fun measurementsText(): String =
        Json.parseToJsonElement(fs.readFile(path)!!).jsonObject["measurements"].toString()

    private fun resave(edit: (GeometryCalibration?) -> GeometryCalibration?) {
        val sidecar = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!
        val annotation = sidecar.toDomainAnnotation()
        val calibration = annotation.calibration.copy(geometry = edit(annotation.calibration.geometry))
        manager.writeSidecar(annotation.copy(calibration = calibration), sidecar.toDomainMeasurements())
    }

    @Test
    fun existingMeasurementDistanceMeters_should_BeByteIdentical_After_LoadSaveAndHeightOffsetSetClearTwice() {
        fs.writeFile(path, SidecarFixtures.V0113)
        val before = measurementsText()
        val valueTexts = Regex("\"valueMeters\":[0-9.]+").findAll(before).map { it.value }.toList()
        assertEquals(2, valueTexts.size)

        resave { it }
        repeat(2) {
            resave { setHeightOffset(it, 0.004) }
            assertEquals(before, measurementsText())
            resave { setHeightOffset(it, null) }
            assertEquals(before, measurementsText())
        }
        assertEquals(valueTexts, Regex("\"valueMeters\":[0-9.]+").findAll(measurementsText()).map { it.value }.toList())
    }

    @Test
    fun scaleFit_should_KeepConstraintsAndFit_ThroughHeightOffsetSetClearTwice() {
        fs.writeFile(path, SidecarFixtures.V2_SCALE)
        repeat(2) {
            resave { setHeightOffset(it, 0.004) }
            resave { setHeightOffset(it, null) }
        }
        val geometry = manager.readSidecar(notes, SidecarFixtures.IMAGE_UUID).getOrNull()!!.imageAnnotation.calibrationGeometry!!
        assertEquals(1, geometry.constraints.size)
        assertEquals(3, geometry.fit!!.n)
        val saved: JsonObject = Json.parseToJsonElement(fs.readFile(path)!!).jsonObject
        assertEquals("2", saved["schemaVersion"].toString())
    }
}
