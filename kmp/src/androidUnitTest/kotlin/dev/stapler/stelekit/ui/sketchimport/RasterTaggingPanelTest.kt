// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.sketchimport.PngSize
import dev.stapler.stelekit.sketchimport.RasterInputError
import dev.stapler.stelekit.sketchimport.TagChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RasterTaggingPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val fresh = RasterState(PngSize(1000, 500)).toggled()
    private fun RasterState.line(x2: Double) = tapped(NormalizedPoint(0.1, 0.5)).placed().tapped(NormalizedPoint(x2, 0.5)).placed()
    private val keys = listOf("niche.width")

    @Test
    fun `before the scale the panel asks for two points and a length and set scale follows the input`() {
        var current = fresh
        rule.setContent { RasterTaggingPanel(current, keys, null, { edit -> current = edit(current) }, { _, _ -> }) }
        rule.onNodeWithText("Place two points on a length you know, then type it.").assertExists()
        rule.onNodeWithText("0 of 2 points placed").assertExists()
        rule.onNodeWithText("Set scale").assertIsNotEnabled()
        rule.onNodeWithText("Clear points").performScrollTo().performClick()
        assertTrue(current.points.isEmpty())
    }

    @Test
    fun `set scale reports the calibration edit once a line and a length exist`() {
        var current = fresh.line(0.6).typed("100\"")
        rule.setContent { RasterTaggingPanel(current, keys, null, { edit -> current = edit(current) }, { _, _ -> }) }
        rule.onNodeWithText("Set scale").performScrollTo().assertIsEnabled().performClick()
        assertTrue(current.isCalibrated)
    }

    @Test
    fun `after the scale a measured line can be bound and a tag removed`() {
        val bound = mutableListOf<Pair<TagChoice, Boolean>>()
        var current = fresh.line(0.6).typed("100\"").calibrated().line(0.35)
        rule.setContent { RasterTaggingPanel(current, keys, null, { edit -> current = edit(current) }, { c, r -> bound += c to r }) }
        rule.onNodeWithText("Measured: 4' 2\"").assertExists()
        rule.onNodeWithText("niche.width").performScrollTo().performClick()
        assertEquals(listOf<Pair<TagChoice, Boolean>>(TagChoice.Existing("niche.width") to false), bound)
        rule.onNodeWithText("Bind the reference (8' 4\") to a dimension").performScrollTo().performClick()
        rule.onNodeWithText("\"Reference 8' 4\"\"").assertExists()
        rule.onNodeWithText("Redo scale").performScrollTo().performClick()
        assertTrue(!current.isCalibrated)
    }

    @Test
    fun `an input error is shown in words`() {
        rule.setContent { RasterTaggingPanel(fresh.failed(RasterInputError.NotALength), keys, null, {}, { _, _ -> }) }
        rule.onNodeWithText("Type one length such as 5 3/8\" or 12'-6\"").assertExists()
    }

    @Test
    fun `the tagging screen shows the raster step for a png`() {
        val state = SketchImportUiState(phase = ImportPhase.Tagging, fileName = "plan.png", raster = fresh, registryKeys = keys)
        rule.setContent { TaggingScreen(state, { _, _ -> }, {}, {}, raster = RasterControls(null, {}, { _, _ -> })) }
        rule.onNodeWithText("Calibrate plan.png").assertExists()
    }
}
