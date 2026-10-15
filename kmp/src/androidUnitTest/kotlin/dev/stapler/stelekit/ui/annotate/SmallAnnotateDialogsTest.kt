// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SmallAnnotateDialogsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun unsavedChanges_buttonsInvokeMatchingCallbacks() {
        val calls = mutableListOf<String>()
        rule.setContent {
            UnsavedChangesDialog(
                onSave = { calls += "save" },
                onDiscard = { calls += "discard" },
                onKeepEditing = { calls += "keep" },
            )
        }
        rule.onNodeWithText("Save changes?").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Discard").performClick()
        rule.onNodeWithText("Keep editing").performClick()
        assertEquals(listOf("save", "discard", "keep"), calls)
    }

    @Test
    fun labelInput_addDisabledWhenBlank_enabledWhenTyped() {
        rule.setContent { LabelInputOverlay(text = "  ", onTextChange = {}, onConfirm = {}, onDismiss = {}) }
        rule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun labelInput_confirmAndCancelInvokeCallbacks() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            LabelInputOverlay(text = "Door", onTextChange = {}, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }
        rule.onNodeWithText("Add label").assertExists()
        rule.onNodeWithText("Add").assertIsEnabled().performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun calibrationChangeWarning_pluralisesMeasurementCount() {
        var keep = 0
        var change = 0
        rule.setContent {
            CalibrationChangeWarningDialog(
                existingAnnotationCount = 3,
                onKeepCurrentScale = { keep++ },
                onChangeScale = { change++ },
            )
        }
        rule.onNodeWithText("You have 3 measurements using the current scale.", substring = true).assertExists()
        rule.onNodeWithText("Change scale").performClick()
        rule.onNodeWithText("Keep current scale").performClick()
        assertEquals(1, change)
        assertEquals(1, keep)
    }

    @Test
    fun calibrationChangeWarning_singularForOne() {
        rule.setContent {
            CalibrationChangeWarningDialog(existingAnnotationCount = 1, onKeepCurrentScale = {}, onChangeScale = {})
        }
        rule.onNodeWithText("You have 1 measurement using the current scale.", substring = true).assertExists()
    }
}
