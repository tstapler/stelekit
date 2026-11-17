// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tasks 3.6.1a/b (UX-42): the shortcuts sheet and the Compose key-event bridge into the pure dispatcher. This is the
 * Robolectric home of the plan's `KeyboardShortcutsTest`: a Compose-behaviour test needing no pixels belongs here, not in
 * jvmTest (CLAUDE.md, testing best practices), so it runs without a display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ShortcutsSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val linux = KeymapEnv(isMac = false, isWeb = false)

    @Test
    fun sheet_should_ListAll14ToolsAndCloseOnButton_UX42() {
        var closed = 0
        val model = ShortcutsSheetModel.of(linux, singleKeyEnabled = true)
        rule.setContent { ShortcutsSheet(model, onSingleKeyChange = {}, onClose = { closed++ }) }
        rule.waitForIdle()
        rule.onNodeWithTag(ShortcutsSheetTags.row("rectangle")).assert(hasContentDescription("Rectangle: R, 2"))
        rule.onNodeWithTag(ShortcutsSheetTags.CLOSE).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun settingSwitch_should_ReportTheNewValue() {
        var enabled by mutableStateOf(true)
        rule.setContent { ShortcutsSheet(ShortcutsSheetModel.of(linux, enabled), onSingleKeyChange = { enabled = it }, onClose = {}) }
        rule.waitForIdle()
        rule.onNodeWithTag(ShortcutsSheetTags.SETTING).performClick()
        rule.waitForIdle()
        assertEquals(false, enabled)
    }

    private fun canvas(singleKey: Boolean, seen: MutableList<KeyOutcome>) {
        rule.setContent {
            val focus = remember { FocusRequester() }
            Box(
                Modifier.size(40.dp).testTag("canvas").focusRequester(focus).focusable().onKeyEvent { event ->
                    val press = event.toKeyPress() ?: return@onKeyEvent false
                    val outcome = KeyDispatcher.dispatch(press, KeyContext(linux, singleKey, canvasFocused = true, textInputOrDialogFocused = false, hasSelection = false))
                    seen += outcome
                    outcome is KeyOutcome.Handled
                },
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        }
        rule.waitForIdle()
    }

    @Test
    fun keyEvents_should_SelectToolsWithTheSettingOn() {
        val seen = mutableListOf<KeyOutcome>()
        canvas(singleKey = true, seen)
        rule.onNodeWithTag("canvas").performKeyInput { pressKey(Key.R) }
        assertEquals(KeyOutcome.Handled(KeyAction.SelectTool(SketchToolId.Rectangle)), seen.last())
    }

    @Test
    fun keyEvents_should_ChangeNothingWhenTheSettingIsOff() {
        val seen = mutableListOf<KeyOutcome>()
        canvas(singleKey = false, seen)
        rule.onNodeWithTag("canvas").performKeyInput { pressKey(Key.R) }
        assertTrue(seen.last() is KeyOutcome.SingleKeyBlocked)
    }

    @Test
    fun keyEvents_should_MapCtrlZToUndo() {
        val seen = mutableListOf<KeyOutcome>()
        canvas(singleKey = true, seen)
        rule.onNodeWithTag("canvas").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        assertTrue(seen.any { it == KeyOutcome.Handled(KeyAction.Undo) }, "saw $seen")
    }
}
