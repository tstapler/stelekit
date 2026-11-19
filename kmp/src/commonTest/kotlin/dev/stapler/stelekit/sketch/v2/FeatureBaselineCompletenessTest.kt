// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.ArrangeActionId
import dev.stapler.stelekit.ui.sketch.v2.StylePanelModel
import dev.stapler.stelekit.ui.sketch.v2.StyleProperty
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 3.5.4c (requirements metric 1, D-22): the Excalidraw 0.18.1 feature baseline is a counted checklist, enforced the way
 * `ToolInventoryCompletenessTest` enforces the 14 tools. The list lives in [FeatureBaseline] (commonTest, so it runs on every
 * target; a resource file would not be readable on wasmJs). Adding or removing a baseline feature fails here until
 * requirements.md and the list change together.
 */
class FeatureBaselineCompletenessTest {
    private val features = FeatureBaseline.features

    private fun idsOf(prefix: String, count: Int) = (1..count).map { "$prefix-${it.toString().padStart(2, '0')}" }

    @Test
    fun baseline_should_HoldExactlyTheFortyOneIds_InRequirementsOrder() {
        val expected = idsOf("FB-SP", STYLE_COUNT) + idsOf("FB-AR", ARRANGE_COUNT) + idsOf("FB-AL", ALIGN_COUNT) + idsOf("FB-LB", LIBRARY_COUNT)
        assertEquals(TOTAL, expected.size)
        assertEquals(expected, features.map { it.id })
        assertEquals(features.size, features.map { it.name }.toSet().size, "two features share a name")
    }

    @Test
    fun everyId_should_BelongToTheGroupItsPrefixNames() {
        val prefixes = mapOf(BaselineKind.Style to "FB-SP", BaselineKind.Arrange to "FB-AR", BaselineKind.Align to "FB-AL", BaselineKind.Library to "FB-LB")
        for (feature in features) assertTrue(feature.id.startsWith(prefixes.getValue(feature.kind)), "${feature.id} is filed under ${feature.kind}")
    }

    @Test
    fun everyStyleId_should_ResolveToAStylePanelModelField_OverASelectionOfEveryKind() {
        val fixture = BaselineFixture()
        val selection = listOf(fixture.rects[0], fixture.text, fixture.arrow).map { checkNotNull(fixture.doc.scene[it]) }
        val model = StylePanelModel.of(selection, ElementStyle(), ToolId.Select, fixture.doc.scene)
        for (feature in features.filter { it.kind == BaselineKind.Style }) {
            val property = StyleProperty.valueOf(feature.target)
            assertNotNull(model.valueOf(property), "${feature.id} (${feature.name}): the panel model has no value for ${feature.target}")
        }
    }

    @Test
    fun everyArrangeId_that_NamesAnArrangeAction_should_ExistInTheArrangeModel() {
        val actions = ArrangeActionId.entries.map { it.name }.toSet()
        val named = features.filter { it.kind == BaselineKind.Arrange && it.target in actions }
        assertEquals(
            listOf("FB-AR-01", "FB-AR-02", "FB-AR-03", "FB-AR-04", "FB-AR-05", "FB-AR-06"),
            named.map { it.id },
            "the six z-order and group ids resolve to arrange actions; the rest are commands",
        )
    }

    @Test
    fun everyFeature_should_RunItsScenarioAgainstTheRealCommandOrControl() = runTest {
        for (feature in features) assertTrue(feature.exercise(BaselineFixture()), "${feature.id} (${feature.name}): ${feature.target} did not do its job")
    }

    @Test
    fun everyFeature_should_NameACoveringTestAndATarget() {
        for (feature in features) {
            assertTrue(feature.test.endsWith("Test"), "${feature.id}: covering test ${feature.test}")
            assertTrue(feature.target.isNotBlank(), "${feature.id}: no target")
        }
    }

    private companion object {
        const val STYLE_COUNT = 15
        const val ARRANGE_COUNT = 13
        const val ALIGN_COUNT = 8
        const val LIBRARY_COUNT = 5
        const val TOTAL = 41
    }
}
