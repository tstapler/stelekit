// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.ui.screens.ConsoleScreen
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Story 3.1 "Config-change retention": input text (`rememberSaveable`) and scrollback (held by the
 * session) survive a state-saving restore. `MainActivity` declares `configChanges`, so a rotation
 * does not recreate the Activity; [dev.stapler.stelekit.ConsoleManifestConfigChangesTest] pins that
 * declaration. A pending-confirm modal does not exist until `sql!` (Story 3.3), so none is tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class ConsoleScreenRetentionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consoleScreen_should_KeepInputAndScrollback_When_StateIsSavedAndRestored() {
        val settings = FakeSettings().also { it.putBoolean(DeveloperMode.KEY, true) }
        val session = ConsoleSession(DeveloperMode(settings), ConsoleRegistry(listOf(HelpCommand())), batchMillis = 5)
        runBlocking { session.run("help") }

        val tester = StateRestorationTester(composeRule)
        tester.setContent { ConsoleScreen(session) }
        composeRule.onNodeWithTag("console-input").performTextInput("logs 5")

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("console-input").assertTextContains("logs 5")
        assertTrue(session.scrollback.lines.value.isNotEmpty())
        composeRule.onNodeWithText("> help").assertIsDisplayed()
        session.close()
    }
}
