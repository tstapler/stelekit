// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

// jvmTest-reason: desktop-only
package dev.stapler.stelekit.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import dev.stapler.stelekit.ui.components.settings.OptionGroup
import dev.stapler.stelekit.ui.components.settings.OptionSpec
import org.junit.Rule
import org.junit.Test

/** Desktop radio groups move selection and focus with the arrow keys, skipping disabled options. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ScheduledSyncRadioKeyboardTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show() {
        rule.setContent {
            MaterialTheme {
                var selected by androidx.compose.runtime.remember { mutableIntStateOf(0) }
                val labels = listOf("A", "B (disabled)", "C", "D")
                OptionGroup(
                    options = labels.mapIndexed { i, l -> OptionSpec(l, selected = i == selected, enabled = i != 1) },
                    onPick = { selected = it },
                )
            }
        }
    }

    @Test
    fun arrowKeys_should_moveSelectionSkippingDisabled_When_DesktopRadioGroupFocused() {
        show()
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithText("A").assertIsSelected()
        rule.onNodeWithText("A").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        rule.onNodeWithText("C").assertIsSelected()
        rule.onNodeWithText("C").performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        rule.onNodeWithText("D").assertIsSelected()
        rule.onNodeWithText("D").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        rule.onNodeWithText("D").assertIsSelected()
        rule.onNodeWithText("D").performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        rule.onNodeWithText("C").assertIsSelected()
        rule.onNodeWithText("C").performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        rule.onNodeWithText("A").assertIsSelected()
    }
}
