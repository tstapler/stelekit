// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.GeometryCalibration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class HeightOffsetDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val ok = HeightOffsetPreview(GeometryCalibration(0.01), "Measurements shrink by 1.7%", null)
    private val bad = HeightOffsetPreview(null, HeightOffsetCopy.NEEDS_DISTANCE, null)

    @Test
    fun explainsTheOffsetAndAppliesAValidDraft() {
        var applied: GeometryCalibration? = null
        rule.setContent {
            HeightOffsetDialog(HeightOffsetDraft("10"), ok, {}, { applied = it }, {}, {})
        }
        rule.onNodeWithText("looks bigger", substring = true).assertExists()
        rule.onNodeWithText("Measurements shrink", substring = true).assertExists()
        rule.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(GeometryCalibration(0.01), applied)
    }

    @Test
    fun invalidDraftShowsTheReasonAndBlocksApply() {
        rule.setContent { HeightOffsetDialog(HeightOffsetDraft("10"), bad, {}, {}, {}, {}) }
        rule.onNodeWithText(HeightOffsetCopy.NEEDS_DISTANCE).assertExists()
        rule.onNodeWithText("Apply").assertIsNotEnabled()
    }

    @Test
    fun presetFillsTheHeightAndClearAndCancelReachCallbacks() {
        var draft: HeightOffsetDraft? = null
        var cleared = 0
        var dismissed = 0
        rule.setContent { HeightOffsetDialog(HeightOffsetDraft(), ok, { draft = it }, {}, { cleared++ }, { dismissed++ }) }
        rule.onNodeWithText("Credit card (0.76 mm)").performClick()
        assertEquals("0.76", draft?.heightText)
        rule.onNodeWithText("Clear").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cleared)
        assertEquals(1, dismissed)
    }

    @Test
    fun rowOffersToSetOrEditTheHeight() {
        var edits = 0
        rule.setContent { HeightOffsetRow(GeometryCalibration(0.0076), onEdit = { edits++ }) }
        rule.onNodeWithText("Height offset 7.6 mm applied").assertExists()
        rule.onNodeWithText("Edit height").performClick()
        assertEquals(1, edits)
    }
}
