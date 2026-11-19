// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Review finding 6 (UX-46, section 17): the panel's tabs are Tab-role selectable nodes named in words, not clickable enum names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h900dp-xhdpi")
class PanelTabsA11yTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tabs_should_HaveTheTabRoleASelectedStateAndAWordLabel() {
        val state = SketchEditorState(panelTab = PanelTab.Style)
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.MouseOnly), window = WindowDp(1280, 900)) }

        val style = rule.onNodeWithTag(ShellTags.tab(PanelTab.Style))
        style.assertIsSelected().assertTextEquals(SketchStrings.PanelTabs.STYLE)
        assertEquals(Role.Tab, style.fetchSemanticsNode().config[SemanticsProperties.Role])
        rule.onNodeWithTag(ShellTags.tab(PanelTab.Elements)).assertIsNotSelected().assertTextEquals(SketchStrings.PanelTabs.ELEMENTS)
    }

    @Test
    fun activatingATab_should_SelectItAndOpenItsPanel() {
        val state = SketchEditorState(panelTab = PanelTab.Style)
        rule.setContent { SketchScreenV2(state, SketchShellConfig(ToolStripModel(false), PointerClass.MouseOnly), window = WindowDp(1280, 900)) }

        rule.onNodeWithTag(ShellTags.tab(PanelTab.Elements)).performClick()

        rule.onNodeWithTag(ShellTags.tab(PanelTab.Elements)).assertIsSelected()
        assertEquals(PanelTab.Elements, state.panelTab)
    }
}
