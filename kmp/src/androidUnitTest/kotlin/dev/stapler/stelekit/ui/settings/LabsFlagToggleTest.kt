// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.settings.HOME_SKETCH_DISCLOSURE
import dev.stapler.stelekit.ui.components.settings.HOME_SKETCH_LABEL
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

/** Story 5.0b: the Labs category shows one switch, reports toggles, and states what it enables once on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class LabsFlagToggleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(initial: Boolean, toggled: MutableList<Boolean>) = composeRule.setContent {
        var enabled by remember { mutableStateOf(initial) }
        SettingsDialog(
            visible = true,
            onDismiss = {},
            currentTheme = StelekitThemeMode.SYSTEM,
            onThemeChange = {},
            currentLanguage = Language.ENGLISH,
            onLanguageChange = {},
            onReindex = {},
            initialCategory = SettingsCategory.LABS,
            homeSketchEnabled = enabled,
            onHomeSketchToggle = { on ->
                toggled += on
                enabled = on
            },
        )
    }

    @Test
    fun labs_should_ShowOneOffSwitchWithoutDisclosure_When_FlagIsOff() {
        show(initial = false, toggled = mutableListOf())
        composeRule.onNodeWithText(HOME_SKETCH_LABEL).assertExists()
        assertEquals(0, composeRule.onAllNodesWithText(HOME_SKETCH_DISCLOSURE).fetchSemanticsNodes().size)
    }

    @Test
    fun labs_should_ReportToggle_And_ShowDisclosure_When_TurnedOn() {
        val toggled = mutableListOf<Boolean>()
        show(initial = false, toggled = toggled)
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(listOf(true), toggled)
        composeRule.onNodeWithText(HOME_SKETCH_DISCLOSURE).assertExists()
    }
}
