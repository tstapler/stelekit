// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.4, Task 3.1.4d (UX-80, UX-119, UX-127): the top bar tiers and the Selection menu at every window of the chrome
 * table. Robolectric; Gradle-only like the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h1080dp-mdpi")
class TopBarTest {
    @get:Rule
    val rule = createComposeRule()

    private val table = ChromeTableParser.parseFixture()
    private var fingerToggles = 0
    private val chosen = mutableListOf<SelectionAction>()

    private class Scene(val widthDp: Int, val heightDp: Int, val scale: Double, val fingerOn: Boolean, val status: SaveStatus)

    private var scene by mutableStateOf<Scene?>(null)

    @Before
    fun host() {
        rule.setContent {
            val s = scene ?: return@setContent
            val plan = ChromeBudget.topBar(ChromeBudget.layout(s.widthDp, s.heightDp), s.widthDp, s.scale)
            CompositionLocalProvider(LocalDensity provides Density(1f, s.scale.toFloat())) {
                Box(Modifier.requiredSize(s.widthDp.dp, 200.dp)) {
                    TopBar(
                        TopBarContent("A sketch with a rather long name indeed", plan, s.fingerOn, PointerClass.TouchOrPen, s.status, 2, SelectionMenuModel.items(true, true)),
                        TopBarActions(onBack = {}, onFingerToggle = { fingerToggles++ }, onSelectionAction = { chosen += it }),
                    )
                }
            }
        }
    }

    private fun show(widthDp: Int, heightDp: Int, scale: Double, fingerOn: Boolean = false, status: SaveStatus = SaveStatus.Unsaved) {
        scene = Scene(widthDp, heightDp, scale, fingerOn, status)
        rule.waitForIdle()
    }

    private fun text(tag: String): String {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return (listOf(node) + node.children).joinToString("") { n ->
            if (SemanticsProperties.Text in n.config) n.config[SemanticsProperties.Text].joinToString { it.text } else ""
        }
    }

    @Test
    fun fingerChip_should_ReadAsText_InTheFullAndShortForms_And_Toggle() {
        show(360, 640, 1.0)
        assertEquals(table.raw("topbar").single { it[0] == "FingerDrawing" }[3], text(ShellTags.FINGER_CHIP))
        rule.onNodeWithTag(ShellTags.FINGER_CHIP).performClick()
        assertEquals(1, fingerToggles)
        show(360, 640, 2.0, fingerOn = true)
        assertEquals("On", text(ShellTags.FINGER_CHIP))
    }

    @Test
    fun saveStatus_should_BeTextBelowScale15_And_AGlyphButtonWithFullSemanticsFrom15() {
        show(360, 640, 1.0)
        assertEquals("Unsaved", text(TopBarTags.SAVE))
        show(360, 640, 2.0)
        val bounds = rule.onNodeWithTag(TopBarTags.SAVE).getBoundsInRoot()
        assertEquals(ChromeBudget.SAVE_SHORT_DP.toFloat(), bounds.width.value)
        assertTrue(rule.onNodeWithTag(TopBarTags.SAVE).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().isNotBlank())
    }

    @Test
    fun topBar_should_NeverWrap_And_KeepOneRowHeight_At_EveryGuaranteedWindow() {
        for (w in table.windows.filter { it.guaranteed }) {
            show(w.widthDp, w.heightDp, w.fontScale)
            rule.onAllNodesWithTag(ShellTags.TOP_BAR).assertCountEquals(1)
            val bar = rule.onNodeWithTag(ShellTags.TOP_BAR).getBoundsInRoot()
            assertEquals(ChromeRow.TopBar.heightDp.toFloat(), bar.height.value, "${w.name} ${w.widthDp}@${w.fontScale}")
            val name = rule.onNodeWithTag(TopBarTags.NAME).getBoundsInRoot().width.value
            assertTrue(name >= table.raw("topbar").single { it[0] == "NameMin" }[1].toInt(), "${w.name} ${w.widthDp}@${w.fontScale}: name $name")
        }
    }

    @Test
    fun selectionMenu_should_ExistInTheRailWithASideSheet_And_RunItsItems() {
        show(600, 360, 1.0)
        rule.onNodeWithTag(SelectionMenuTags.BUTTON).performClick()
        rule.onNodeWithTag(SelectionMenuTags.item(SelectionAction.Duplicate)).performClick()
        assertEquals(listOf(SelectionAction.Duplicate), chosen)
        show(360, 640, 1.0)
        rule.onAllNodesWithTag(SelectionMenuTags.BUTTON).assertCountEquals(0)
    }

    @Test
    fun selectionMenu_should_HoldBindEndOnlyForOneArrow_And_LockOrUnlockByState() {
        assertTrue(SelectionAction.BindEnd in SelectionMenuModel.items(anyUnlocked = true, oneArrowSelected = true))
        assertTrue(SelectionAction.BindEnd !in SelectionMenuModel.items(anyUnlocked = true, oneArrowSelected = false))
        assertTrue(SelectionAction.Unlock in SelectionMenuModel.items(anyUnlocked = false, oneArrowSelected = false))
        assertTrue(SelectionAction.Lock !in SelectionMenuModel.items(anyUnlocked = false, oneArrowSelected = false))
    }
}
