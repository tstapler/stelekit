// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 4.4e: the inline Back and Esc ladder, rungs 2 to 6 (rung 1, the conflict banner, is PR-C). Gradle-only (Compose host). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableInlineBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val host = TableTestHost(rule)

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun backEnabled(): Boolean {
        var enabled = false
        rule.runOnUiThread { enabled = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    private fun tapTouch(text: String) {
        rule.onNodeWithText(text).performTouchInput { click() }
        rule.waitForIdle()
    }

    private fun activate(tag: String) {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun pressEsc() {
        rule.onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
    }

    @Test
    fun rung2_should_CloseThePickerFirst_AndKeepTheSelection() {
        host.show()
        tapTouch("Apple")
        activate("strip:CancelEdit")
        activate("strip:Extend")
        rule.onNodeWithTag("tablePicker:Extend").assertExists()
        back()
        rule.onAllNodes(hasTestTag("tablePicker:Extend")).assertCountEquals(0)
        rule.onNodeWithTag("tableStrip").assertExists()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
    }

    @Test
    fun rung2_should_DismissAWhyNotice() {
        host.show(initial = "| A | B |\n|---|---|\n| 1 | =A2/0 |")
        tapTouch("#DIV/0!")
        activate("strip:CancelEdit")
        activate("strip:Why")
        rule.onNodeWithText("Division by zero").assertExists()
        back()
        rule.onAllNodes(hasText("Division by zero")).assertCountEquals(0)
        rule.onNodeWithTag("tableStrip").assertExists()
    }

    @Test
    fun rung4_should_CommitOnceAndNavigate_When_BackInEditWithTheImeHidden() {
        host.show()
        tapTouch("3")
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        back()
        assertEquals(1, host.commits.size)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("strip:Close").assertExists()
    }

    @Test
    fun rung4_should_CancelWithoutCommit_When_EscInEdit() {
        host.show()
        tapTouch("3")
        rule.onNode(hasSetTextAction()).performTextReplacement("45")
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertTrue(host.commits.isEmpty())
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText(TableMessages.DISCARDED_EDIT).assertExists()
    }

    @Test
    fun rung5_should_CollapseARangeBeforeClosingTheStrip_ThenRung6ClosesIt_ThenBackIsLeftToThePlatform() {
        host.show()
        tapTouch("Apple")
        activate("strip:CancelEdit")
        activate("strip:SelectRow")
        rule.onAllNodes(isSelected()).assertCountEquals(3)
        back()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
        rule.onNodeWithTag("tableStrip").assertExists()
        back()
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
        assertFalse(backEnabled(), "nothing active: the platform keeps Back")
    }

    @Test
    fun esc_should_CollapseThenCloseInNavigate_AndBeIgnoredWhenNothingIsActive() {
        host.show()
        tapTouch("Apple")
        activate("strip:CancelEdit")
        activate("strip:SelectRow")
        pressEsc()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
        pressEsc()
        rule.onAllNodes(hasTestTag("tableStrip")).assertCountEquals(0)
        pressEsc()
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun backHandler_should_BeDisabled_When_NoCellIsActive() {
        host.show()
        assertFalse(backEnabled())
        tapTouch("Apple")
        assertTrue(backEnabled())
    }
}
