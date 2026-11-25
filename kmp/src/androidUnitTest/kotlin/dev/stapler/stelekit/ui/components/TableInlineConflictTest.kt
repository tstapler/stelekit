// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 4.5e (deferred from PR-B): the inline conflict banner, Resolve chip, Back rung 1 and the held-commit gate. Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableInlineConflictTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val host = TableTestHost(rule)
    private val theirs = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 99 | red |\n| Pear | 5 | green |"

    private fun editor() = rule.onNode(hasSetTextAction())
    private fun heldDraft42() {
        host.show()
        rule.onNodeWithText("3").performMouseInput { click() }
        rule.waitForIdle()
        editor().performTextReplacement("42")
        host.content = theirs
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun foreignEdit_should_ShowTheBanner_When_ADraftIsPending() {
        heldDraft42()
        rule.onNodeWithTag("conflictBanner").assertExists()
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun back_should_DismissOnlyTheBanner_KeepTheDraftAndLeaveAResolveChip() {
        heldDraft42()
        back()
        rule.onAllNodes(hasTestTag("conflictBanner")).assertCountEquals(0)
        rule.onNodeWithTag("resolveChip").assertExists()
        editor().assertExists()
        assertTrue(host.commits.isEmpty(), "dismissing the banner writes nothing")
        rule.onNodeWithTag("resolveChip").performClick()
        rule.onNodeWithTag("conflictBanner").assertExists()
    }

    @Test
    fun committingKey_should_ReshowTheBannerAfterItWasDismissed() {
        heldDraft42()
        back()
        rule.onNodeWithTag("strip:Resolve").assertExists()
        rule.onNodeWithTag("strip:Markdown").assertExists()
        editor().performKeyPress(androidx.compose.ui.input.key.Key.Enter)
        rule.waitForIdle()
        rule.onNodeWithTag("conflictBanner").assertExists()
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun keepMine_should_CommitTheDraftOnTopOfTheirs() {
        heldDraft42()
        rule.onNodeWithTag("conflictKeepMine").performClick()
        rule.waitForIdle()
        assertEquals(1, host.commits.size)
        assertTrue(host.commits.single().contains("| Apple | 42 | red |"), host.commits.single())
        rule.onAllNodes(hasTestTag("conflictBanner")).assertCountEquals(0)
    }

    @Test
    fun takeTheirs_should_DiscardTheDraftAndOfferUndo() {
        heldDraft42()
        rule.onNodeWithTag("conflictTakeTheirs").performClick()
        rule.waitForIdle()
        assertTrue(host.commits.isEmpty())
        rule.onNodeWithText(TableMessages.DISCARDED_EDIT).assertExists()
        rule.onNodeWithText("99").assertExists()
    }

    @Test
    fun editAsMarkdown_should_HandTheDraftToTheRecoveredStoreAndOpenTheRawEditor() {
        heldDraft42()
        rule.onNodeWithTag("conflictEditMarkdown").performClick()
        rule.waitForIdle()
        assertEquals(1, host.started)
        assertTrue(host.commits.isEmpty())
    }
}

@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performKeyPress(key: androidx.compose.ui.input.key.Key) {
    performKeyInput { pressKey(key) }
}
