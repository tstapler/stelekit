// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Story 3.1: the Developer category and its console toggle on a host with neither the libsql driver
 * nor an MCP launcher (the Web shape, where `SettingsDialog` used to hide the category entirely).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsDeveloperCategoryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        enabled: Boolean,
        onToggle: ((Boolean) -> Unit)?,
        onOpenConsole: (() -> Unit)? = null,
    ) = composeRule.setContent {
        SettingsDialog(
            visible = true,
            onDismiss = {},
            currentTheme = StelekitThemeMode.SYSTEM,
            onThemeChange = {},
            currentLanguage = Language.ENGLISH,
            onLanguageChange = {},
            onReindex = {},
            initialCategory = SettingsCategory.DEVELOPER,
            isDeveloperModeEnabled = enabled,
            onDeveloperModeToggle = onToggle,
            onOpenConsole = onOpenConsole,
        )
    }

    @Test
    fun developerCategory_should_ShowToggleAndWarning_And_NoConsoleRow_When_DeveloperModeIsOff() {
        show(enabled = false, onToggle = {}, onOpenConsole = {})
        composeRule.onNodeWithText(DeveloperMode.WARNING).assertIsDisplayed()
        composeRule.onNode(isToggleable()).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Dev console").fetchSemanticsNodes().size)
    }

    @Test
    fun developerCategory_should_ReportToggleChanges_And_OpenConsole_When_DeveloperModeIsOn() {
        val toggled = mutableListOf<Boolean>()
        var opened = 0
        show(enabled = true, onToggle = { toggled += it }, onOpenConsole = { opened++ })
        composeRule.onNodeWithText("Dev console").performClick()
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(1, opened)
        assertEquals(listOf(false), toggled)
    }

    @Test
    fun developerCategory_should_ShowNoToggle_When_HostPassesNone() {
        show(enabled = false, onToggle = null)
        assertEquals(0, composeRule.onAllNodesWithText(DeveloperMode.WARNING).fetchSemanticsNodes().size)
    }
}
