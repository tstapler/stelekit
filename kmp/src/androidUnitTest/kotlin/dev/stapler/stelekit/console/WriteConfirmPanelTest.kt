// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.ui.screens.WriteConfirmPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The `sql!` confirm content (the dialog window around it is unreachable under Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WriteConfirmPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun request(tier: ConfirmTier) = ConfirmRequest(
        title = "Run this write?",
        statement = "update pages set is_favorite=1",
        details = listOf("12 rows will change in pages."),
        tier = tier,
        primaryLabel = "Update 12 rows",
    )

    @Test
    fun panel_should_ShowStatementDetailsAndAllowProceed_When_StandardTier() {
        val answers = mutableListOf<ConfirmAnswer>()
        composeRule.setContent { WriteConfirmPanel(request(ConfirmTier.Standard), onAnswer = { answers += it }) }
        composeRule.onNodeWithTag("console-write-statement").assertTextContains("update pages set is_favorite=1")
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsEnabled().performClick()
        assertEquals(listOf<ConfirmAnswer>(ConfirmAnswer.Proceed(null)), answers)
    }

    @Test
    fun panel_should_AnswerCancel_When_CancelClicked() {
        val answers = mutableListOf<ConfirmAnswer>()
        composeRule.setContent { WriteConfirmPanel(request(ConfirmTier.Standard), onAnswer = { answers += it }) }
        composeRule.onNodeWithTag("console-write-cancel-button").performClick()
        assertEquals(listOf<ConfirmAnswer>(ConfirmAnswer.Cancel), answers)
    }

    @Test
    fun panel_should_KeepProceedDisabledUntilExactCountTyped_When_TypedCountTier() {
        val answers = mutableListOf<ConfirmAnswer>()
        composeRule.setContent { WriteConfirmPanel(request(ConfirmTier.TypedCount(12)), onAnswer = { answers += it }) }
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("console-write-typed-count").performTextInput("11")
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("console-write-typed-count").performTextInput("2")
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("console-write-typed-count").performTextClearance()
        composeRule.onNodeWithTag("console-write-typed-count").performTextInput(" 12 ")
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsEnabled().performClick()
        assertEquals(listOf<ConfirmAnswer>(ConfirmAnswer.Proceed(" 12 ")), answers)
    }

    @Test
    fun panel_should_RequireTheExactRestorePhrase_When_TypedPhraseTier() {
        val answers = mutableListOf<ConfirmAnswer>()
        composeRule.setContent { WriteConfirmPanel(request(ConfirmTier.TypedPhrase("RESTORE")), onAnswer = { answers += it }) }
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("console-write-typed-count").performTextInput("restore")
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("console-write-typed-count").performTextClearance()
        composeRule.onNodeWithTag("console-write-typed-count").performTextInput("RESTORE")
        composeRule.onNodeWithTag("console-write-confirm-button").assertIsEnabled().performClick()
        assertEquals(listOf<ConfirmAnswer>(ConfirmAnswer.Proceed("RESTORE")), answers)
    }
}
