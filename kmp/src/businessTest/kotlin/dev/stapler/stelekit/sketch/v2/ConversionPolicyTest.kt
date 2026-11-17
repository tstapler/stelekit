// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionPolicy
import dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.1.4a: the exact refusal predicate. */
class ConversionPolicyTest {
    private val zones = JsonArray(listOf(buildJsonObject { put("name", "z") }))

    private fun bundle(sheetZones: JsonArray, dims: Boolean): ProjectBundle {
        val b = SketchTestSupport.bundle(sheets = 2)
        return b.copy(
            sheets = b.sheets.mapIndexed { i, s -> if (i == 0) s.copy(zones = sheetZones) else s },
            dimensions = if (dims) b.dimensions else emptyList(),
        )
    }

    @Test
    fun refusal_should_BeNull_WhenNoZonesAndNoDimensions() {
        val b = bundle(JsonArray(emptyList()), dims = false)
        b.sheets.forEach { assertNull(ConversionPolicy.refusal(b, it, technicalDataEditable = false)) }
    }

    @Test
    fun refusal_should_BeHasTechnicalData_WhenTheSheetHasZones() {
        val b = bundle(zones, dims = false)
        assertEquals(HasTechnicalData, ConversionPolicy.refusal(b, b.sheets[0], technicalDataEditable = false))
        assertNull(ConversionPolicy.refusal(b, b.sheets[1], technicalDataEditable = false))
    }

    @Test
    fun refusal_should_BeHasTechnicalDataForEverySheet_WhenTheProjectHasDimensions() {
        val b = bundle(JsonArray(emptyList()), dims = true)
        b.sheets.forEach { assertEquals(HasTechnicalData, ConversionPolicy.refusal(b, it, technicalDataEditable = false)) }
    }

    @Test
    fun refusal_should_BeNullWithTheRealPolicy_AfterStory425() {
        val b = bundle(zones, dims = true)
        assertTrue(ConversionPolicy.TECHNICAL_DATA_EDITABLE)
        b.sheets.forEach { assertNull(ConversionPolicy.refusal(b, it)) }
    }

    @Test
    fun refusal_should_NeverRefuse_AfterThePolicyFlip() {
        val b = bundle(zones, dims = true)
        b.sheets.forEach { assertNull(ConversionPolicy.refusal(b, it, technicalDataEditable = true)) }
    }
}
