// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.SketchTestSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Story 1.1.4a: technical data stays verbatim in the model; the layer mirror is the only v2-initiated write. */
class TechnicalDataTest {
    private val s1 = SheetUuid("p1-s0")

    private fun fixture(): ProjectBundle {
        val base = SketchTestSupport.bundle(sheets = 1, primitivesPerSheet = 2)
        val zones = JsonArray(listOf("kitchen", "pantry").map { n -> buildJsonObject { put("name", n); put("kind", "length") } })
        return base.copy(
            sheets = base.sheets.map { it.copy(zones = zones, scaleSixteenthsPerUnit = 32) },
            dimensions = listOf("w", "h", "sum").map { SketchTestSupport.dimension("p1-d-$it", key = it) },
        )
    }

    private fun model(b: ProjectBundle) =
        Json.parseToJsonElement(SidecarCodec.encode(b).getValue("project-model.json").decodeToString()).jsonObject

    @Test
    fun withLayerMirror_should_LeaveZonesDimensionsAndScaleBytesIdentical_WhenNothingChanges() {
        val b = fixture()
        val mirrored = TechnicalData.withLayerMirror(b, s1, TechnicalData.layerNames(b, s1))

        assertSame(b, mirrored)
        assertContentEquals(
            SidecarCodec.encode(b).getValue("project-model.json"),
            SidecarCodec.encode(mirrored).getValue("project-model.json"),
        )
    }

    @Test
    fun withLayerMirror_should_ChangeOnlyLayers_WhenLayersChange() {
        val b = fixture()
        val before = model(b)
        val after = model(TechnicalData.withLayerMirror(b, s1, listOf("Base", "Walls")))

        val layers = after["sheets"]!!.jsonArray[0].jsonObject["layers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Base", "Walls"), layers)
        assertEquals(before["dimensions"], after["dimensions"])
        val bs = before["sheets"]!!.jsonArray[0].jsonObject
        val asheet = after["sheets"]!!.jsonArray[0].jsonObject
        assertEquals(bs["zones"], asheet["zones"])
        assertEquals(bs["scaleSixteenthsPerUnit"], asheet["scaleSixteenthsPerUnit"])
    }

    @Test
    fun accessors_should_ReadZonesDimensionsAndScale() {
        val b = fixture()
        assertEquals(2, TechnicalData.zones(b, s1).size)
        assertEquals(3, TechnicalData.dimensions(b).size)
        assertEquals(32L, TechnicalData.scaleSixteenthsPerUnit(b, s1))
    }
}
