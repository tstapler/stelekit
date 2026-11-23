// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import dev.stapler.stelekit.sketch.v2.tools.ToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideContentTest {
    private val registry = GuideContent.registry()

    @Test
    fun everyFeatureTrigger_should_StartExactlyOneTour() {
        for (feature in listOf(GuideFeatures.CONSTRAINTS, GuideFeatures.TECHNICAL_TAB, GuideFeatures.POINT_DRAG)) {
            assertEquals(1, registry.forTrigger(feature).size, feature)
        }
    }

    @Test
    fun orientationTour_should_NeverStartOnItsOwn() {
        assertTrue(registry.byId(GuideContent.ORIENTATION)!!.triggers.isEmpty())
    }

    @Test
    fun everyStep_should_HaveATitleAndABody() {
        for (tour in registry.all) for (step in tour.steps) assertTrue(step.title.isNotBlank() && step.body.isNotBlank(), "${tour.id}/${step.id}")
    }

    @Test
    fun everyTourReplay_should_ExistInHelp_AndEveryHelpTourIdShouldExist() {
        val linked = GuideContent.helpTopics.mapNotNull { it.tourId }.toSet()
        assertEquals(registry.all.map { it.id }.toSet(), linked)
        for (id in linked) assertNotNull(registry.byId(id))
    }

    @Test
    fun help_should_ListEveryToolAndUniqueTopicIds() {
        val ids = GuideContent.helpTopics.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (tool in ToolId.entries) assertTrue("tool.${tool.name.lowercase()}" in ids, tool.name)
    }

    @Test
    fun onlyThePointAndArcTools_should_CountAsFirstUseOfConstraints() {
        assertEquals(GuideFeatures.CONSTRAINTS, GuideFeatures.ofTool(ToolId.Point))
        assertEquals(GuideFeatures.CONSTRAINTS, GuideFeatures.ofTool(ToolId.Arc))
        for (tool in ToolId.entries - setOf(ToolId.Point, ToolId.Arc)) assertNull(GuideFeatures.ofTool(tool), tool.name)
    }
}
