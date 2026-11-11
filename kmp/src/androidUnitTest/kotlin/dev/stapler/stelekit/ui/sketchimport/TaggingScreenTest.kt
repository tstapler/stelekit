// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketchimport.KeyError
import dev.stapler.stelekit.sketchimport.TagChoice
import dev.stapler.stelekit.sketchimport.TaggingItem
import dev.stapler.stelekit.units.Rational
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaggingScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val item = TaggingItem("svg:abc", "50\"", Rational(50, 1), Rational(1, 2), listOf("niche.width"))
    private val state = SketchImportUiState(
        phase = ImportPhase.Tagging,
        fileName = "plan.svg",
        items = listOf(item),
        boundCount = 3,
        registryKeys = listOf("niche.width", "rod.height"),
    )

    @Test
    fun `lists unbound labels and picking a key reports the choice`() {
        val chosen = mutableListOf<Pair<String, TagChoice>>()
        rule.setContent { TaggingScreen(state, { r, c -> chosen += r to c }, {}, {}) }
        rule.onNodeWithText("\"50\"\"").assertIsDisplayed()
        rule.onNodeWithText("3 labels matched automatically; 1 need a dimension.").assertIsDisplayed()
        rule.onNodeWithText("rod.height").performClick()
        assertEquals(listOf<Pair<String, TagChoice>>("svg:abc" to TagChoice.Existing("rod.height")), chosen)
    }

    @Test
    fun `save and cancel invoke their callbacks and a key error is shown`() {
        var saved = 0
        var cancelled = 0
        rule.setContent {
            TaggingScreen(state.copy(keyError = KeyError.Blank), { _, _ -> }, { saved++ }, { cancelled++ })
        }
        rule.onNodeWithText("Enter a key name").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `skip reports a skip choice`() {
        val chosen = mutableListOf<TagChoice>()
        rule.setContent { TaggingScreen(state, { _, c -> chosen += c }, {}, {}) }
        rule.onNodeWithText("Skip").performClick()
        assertEquals(listOf<TagChoice>(TagChoice.Skip), chosen)
    }
}
