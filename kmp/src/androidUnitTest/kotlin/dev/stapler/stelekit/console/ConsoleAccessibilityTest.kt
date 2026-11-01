// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.ui.screens.ConsoleScreen
import dev.stapler.stelekit.wiring.FakeSettings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 3.5 / Task 3.5a for the line-based console screen that shipped in U3: touch targets, labelled controls
 * and the layout at 360dp with font scale 2.0. TalkBack focus order and IME ergonomics are owner pass 12.1; the
 * block-summary semantics of the UX design need a block-based scrollback, which the screen does not have yet.
 * Gradle-only (Robolectric): not registered in `AllAndroidUnitTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class ConsoleAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var session: ConsoleSession? = null

    @After
    fun tearDown() {
        session?.close()
    }

    private fun newSession(): ConsoleSession {
        val settings = FakeSettings().also { it.putBoolean(DeveloperMode.KEY, true) }
        return ConsoleSession(DeveloperMode(settings), ConsoleRegistry(listOf(HelpCommand())), batchMillis = 5).also { session = it }
    }

    @Test
    fun consoleScreen_should_LabelEveryIconControlAndTheCommandLine() {
        val s = newSession()
        composeRule.setContent { ConsoleScreen(s) }
        composeRule.onNodeWithContentDescription("Console command line").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Previous command").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Next command").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Export console output").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Clear console output").assertIsDisplayed()
    }

    @Test
    fun consoleScreen_should_GiveEveryShortcutAtLeast48dpTouchTargets() {
        val s = newSession()
        composeRule.setContent { ConsoleScreen(s) }
        // Chips only appear for registered verbs (just `help` here); the key row scrolls, so only the keys composed at 360dp are checked.
        fun check(name: String, node: androidx.compose.ui.test.SemanticsNodeInteraction) {
            try {
                node.assertHasClickAction().assertHeightIsAtLeast(MIN_TARGET).assertWidthIsAtLeast(MIN_TARGET)
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
        }
        check("help chip", composeRule.onNodeWithText("help"))
        check("Previous command", composeRule.onNodeWithContentDescription("Previous command"))
        check("Next command", composeRule.onNodeWithContentDescription("Next command"))
        listOf("\"", "'", "-").forEach { key -> check("key $key", composeRule.onNodeWithText(key)) }
    }

    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 2.0f)
    fun consoleScreen_should_KeepTheCommandLineAndRunVisible_When_FontScaleIsTwoAtThreeSixtyDp() {
        val s = newSession()
        composeRule.setContent { ConsoleScreen(s) }
        composeRule.onNodeWithTag("console-input").assertIsDisplayed()
        composeRule.onNodeWithText("Run").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Export console output").assertIsDisplayed()
    }

    private companion object {
        val MIN_TARGET = 48.dp
    }
}
