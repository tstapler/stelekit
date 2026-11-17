// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.ui.sketch.DIMENSION_FIELD_TAG
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.units.Rational
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Story 4.2.5: the v2 editor edits named dimensions and zones of a converted sheet with the v1 components, one model write
 * per commit. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class TechnicalDataPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val sheet = SheetUuid("s1")

    private fun zone(key: String, text: String, total: Boolean = false) = JsonObject(
        buildMap {
            put("key", JsonPrimitive(key))
            put("text", JsonPrimitive(text))
            if (total) put("total", JsonPrimitive(true))
        },
    )

    private fun dimension(key: String, inches: Long, locked: Boolean = false) = SketchDimension(
        uuid = "d-$key", projectUuid = ProjectUuid("p1"), key = key, kind = "length", value = Rational(inches, 1),
        precision = Rational(1, 16), sourceKind = "sheet", locked = locked,
    )

    private fun bundle(dimensions: List<SketchDimension>) = ProjectBundle(
        project = SketchProject(uuid = ProjectUuid("p1"), slug = "kitchen-0a1b2c", name = "Kitchen", createdAt = 1L),
        sheets = listOf(
            SketchSheet(
                uuid = sheet, projectUuid = ProjectUuid("p1"), kind = "plan", viewDirection = "top", unit = "in", scaleSixteenthsPerUnit = 16,
                zones = JsonArray(listOf(zone("left", "3'"), zone("right", "2'"), zone("all", "5'", total = true))),
            ),
        ),
        primitives = emptyList(),
        dimensions = dimensions,
    )

    private var model by mutableStateOf(bundle(listOf(dimension("Wall A", 120), dimension("Wall B", 96))))
    private val writes = mutableListOf<ProjectBundle>()

    private fun show() {
        rule.setContent { TechnicalDataPanel(model, sheet, onBundleChange = { writes += it; model = it }) }
        rule.waitForIdle()
    }

    @Test
    fun panel_should_ListEachNamedDimensionInFeetAndInches() {
        show()

        rule.onNodeWithTag(TechnicalDataTags.PANEL).assertIsDisplayed()
        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG)[0].assert(hasText("10' 0\"", substring = true))
        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG)[1].assert(hasText("8' 0\"", substring = true))
    }

    @Test
    fun editingADimension_should_WriteTheModelOnce_AndChangeOnlyThatDimension() {
        val before = model
        show()

        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG)[0].performTextClearance()
        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG)[0].performTextInput("11'")
        rule.onAllNodesWithText("Set")[0].performClick()
        rule.waitForIdle()

        assertEquals(1, writes.size, "one commit is one model write")
        assertEquals(Rational(132, 1), writes.single().dimensions.first { it.key == "Wall A" }.value)
        assertSame(before.dimensions[1], writes.single().dimensions[1])
        assertSame(before.sheets, writes.single().sheets)
    }

    @Test
    fun anInvalidLength_should_ShowTheInlineErrorAndWriteNothing() {
        show()

        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG)[0].performTextClearance()
        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG)[0].performTextInput("banana")
        rule.onAllNodesWithText("Set")[0].performClick()
        rule.waitForIdle()

        assertEquals(0, writes.size)
    }

    @Test
    fun editingAZone_should_WriteOnlyThatZone_AndFlagASumThatNoLongerAddsUp() {
        show()
        rule.onNodeWithTag(TechnicalDataTags.SUM_VIOLATED).assertDoesNotExist()

        rule.onNode(hasSetTextAction() and hasText("left")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasText("left")).performTextInput("4'")
        rule.onAllNodesWithText("Set")[2].performClick()
        rule.waitForIdle()

        assertEquals(1, writes.size)
        val zones = writes.single().sheets.single().zones
        assertEquals("4'", zones[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("2'", zones[1].jsonObject.getValue("text").jsonPrimitive.content)
        rule.onNodeWithTag(TechnicalDataTags.SUM_VIOLATED).assertExists()
        rule.onNodeWithText(SketchStrings.TechnicalData.SUM_VIOLATED).assertExists()
    }

    @Test
    fun aLockedDimension_should_BeReadOnly() {
        model = bundle(listOf(dimension("Fixed", 36, locked = true)))
        show()

        rule.onNodeWithText(SketchStrings.TechnicalData.lockedDimension("Fixed", "3' 0\"")).assertIsDisplayed()
        rule.onAllNodesWithTag(DIMENSION_FIELD_TAG).assertCountEquals(0)
    }

    @Test
    fun noDimensions_should_SaySo_WhileZonesStayEditable() {
        model = bundle(emptyList())
        show()

        rule.onNodeWithText(SketchStrings.TechnicalData.NO_DIMENSIONS).assertIsDisplayed()
        rule.onNodeWithText("Zones").assertIsDisplayed()
    }
}
