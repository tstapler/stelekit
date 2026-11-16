// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** U-E4-14: a missing characteristic never aborts the probe, and the export carries device make and model only. */
class CapabilityProbeFormatTest {
    private val full = ProbeCamera(
        id = "0", facing = "BACK", focalsMm = listOf(5.6), sensorWidthMm = 6.4, sensorHeightMm = 4.8,
        logicalMulti = true, physicalIds = listOf("2", "3"), physicalBind = mapOf("2" to "ok", "3" to "fail:IllegalArgumentException"),
        hasIntrinsicCalibration = true, hasLensDistortion = false, distortionModes = listOf(0, 1), minZoom = 0.6, maxZoom = 8.0,
    )

    @Test
    fun probeLine_should_TolerateMissingCharacteristicsAndExportOnlyMakeModel() {
        val empty = ProbeCamera(id = "9")
        val line = CameraProbeFormat.line(empty)
        assertTrue(line.contains("camera id=9"), line)
        assertTrue(line.contains("focal=?"), line)

        val fullLine = CameraProbeFormat.line(full)
        assertTrue(fullLine.contains("physical=2:ok,3:fail:IllegalArgumentException"), fullLine)
        assertTrue(fullLine.contains("zoom=0.6..8.0"), fullLine)

        val json = CameraProbeFormat.exportJson(ProbeFixture("Google", "Pixel 9", listOf(full, empty)))
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("make", "model", "cameras"), root.keys)
        val cameraKeys = (root["cameras"] as kotlinx.serialization.json.JsonArray).flatMap { it.jsonObject.keys }.toSet()
        assertFalse(cameraKeys.any { it.contains("serial", ignoreCase = true) || it.contains("location", ignoreCase = true) })
    }
}
