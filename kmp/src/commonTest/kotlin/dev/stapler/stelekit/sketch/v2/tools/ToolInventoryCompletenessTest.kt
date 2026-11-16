// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 3.1.1 (success metric 1): the tool inventory is frozen and every tool id has a scenario test or a named pending story. */
class ToolInventoryCompletenessTest {
    @Test
    fun inventory_should_BeTheFrozenFourteenTools() {
        assertEquals(
            listOf("Select", "Rectangle", "Diamond", "Ellipse", "Arrow", "Line", "Freedraw", "Text", "Image", "Eraser", "Frame", "Hand", "Point", "Arc"),
            ToolId.entries.map { it.name },
        )
    }

    @Test
    fun everyToolId_should_HaveAScenarioTest_Or_BeListedAsPendingItsStory() {
        val unaccounted = ToolId.entries.filter { it !in ToolScenarioCatalog.covered && it !in ToolScenarioCatalog.pendingStory }
        assertTrue(unaccounted.isEmpty(), "tools with no scenario test and no pending story: $unaccounted")
    }

    @Test
    fun pendingList_should_OnlyShrink_When_AToolGainsAScenarioTest() {
        val both = ToolScenarioCatalog.covered.keys.intersect(ToolScenarioCatalog.pendingStory.keys)
        assertTrue(both.isEmpty(), "covered tools still listed pending (delete the pending line): $both")
        assertTrue(ToolScenarioCatalog.covered.values.all { it.isNotEmpty() })
    }

    @Test
    fun registry_should_ReportMissingTools_And_CreateRegisteredOnes() {
        assertEquals(ToolId.entries, ToolRegistry.Empty.missing())
        val reg = ToolRegistry.Empty.with(ToolId.Rectangle) { ScenarioRectTool() }
        assertEquals(ToolId.entries - ToolId.Rectangle, reg.missing())
        assertEquals(ToolId.Rectangle, reg.create(ToolId.Rectangle)?.id)
        assertEquals(null, reg.create(ToolId.Hand))
    }
}
