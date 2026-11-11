// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.units.Inches
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DimensionFieldTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun validTextCommitsTheExactLength() {
        val committed = mutableListOf<Inches>()
        rule.setContent { DimensionField(initial = "", onCommit = { committed += it }) }
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextInput("12 3/16")
        rule.onNodeWithText("Set").performClick()
        assertEquals(listOf(Inches(12 * 16 + 3)), committed)
    }

    @Test
    fun invalidTextShowsAnInlineErrorWithAnExampleAndCommitsNothing() {
        val committed = mutableListOf<Inches>()
        rule.setContent { DimensionField(initial = "", onCommit = { committed += it }) }
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextInput("banana")
        rule.onNodeWithText("Set").performClick()
        rule.onNodeWithText("That is not a length. Try ${DimensionInput.EXAMPLES}.").assertIsDisplayed()
        assertTrue(committed.isEmpty())
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(DIMENSION_FIELD_TAG).performTextInput("3' 4\"")
        rule.onNodeWithText("Set").performClick()
        assertEquals(listOf(Inches(40 * 16)), committed)
    }
}
