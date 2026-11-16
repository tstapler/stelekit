// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotSelected
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Story 3.5.4a/b (UX-15): the style panel edits the selection or the tool defaults, shows Mixed, names colours.
 * Robolectric, Gradle-only for the same reason as `LayersPanelTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h1200dp-xhdpi")
class StylePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private fun rect(id: String) = RectElement(ElementBase(ElementId(id)), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(5), SceneUnit.ofInt(5))

    private class Host(var doc: SketchDocumentV2) {
        var defaults by mutableStateOf(ElementStyle())
        var tick by mutableStateOf(0)
        val sent = mutableListOf<Command>()
        fun send(c: Command) {
            sent += c
            doc = Transaction.begin(doc).apply(c).fold({ error("$it") }, { t -> t.commit().fold({ doc }, { it.after }) })
            tick++
        }
    }

    private fun host(vararg ids: String): Host {
        val base = SketchDocumentV2()
        val doc = Transaction.begin(base).apply(AddElements(ids.map { rect(it) })).fold({ error("$it") }, { it.doc })
        return Host(doc)
    }

    private fun show(host: Host, selected: List<String>) {
        rule.setContent {
            @Suppress("UNUSED_EXPRESSION") host.tick
            val selection: List<SceneElement> = selected.map { host.doc.scene[ElementId(it)]!! }
            StylePanel(host.doc.scene, selection, host.defaults, ToolId.Rectangle, onCommand = host::send, onDefaults = { host.defaults = dev.stapler.stelekit.ui.sketch.v2.StyleActions.applyToDefaults(host.defaults, it) })
        }
        rule.waitForIdle()
    }

    @Test
    fun boldStroke_should_UpdateTheSelectionInOneCommand() {
        val h = host("r1")
        show(h, listOf("r1"))
        rule.onNodeWithTag(StylePanelTags.HEADING).assertTextEquals("Selection (1)")
        rule.onNodeWithTag(StylePanelTags.choice("Stroke width.Extra bold")).performClick()
        rule.waitForIdle()
        assertEquals(1, h.sent.size)
        assertEquals(4.0, h.doc.scene[ElementId("r1")]!!.base.style.strokeWidth)
        rule.onNodeWithTag(StylePanelTags.choice("Stroke width.Extra bold")).assertIsSelected()
    }

    @Test
    fun nothingSelected_should_ChangeTheToolDefaults_NotTheScene() {
        val h = host("r1")
        show(h, emptyList())
        rule.onNodeWithTag(StylePanelTags.HEADING).assertTextEquals("Defaults for Rectangle")
        rule.onNodeWithTag(StylePanelTags.choice("Stroke width.Extra bold")).performClick()
        rule.waitForIdle()
        assertEquals(4.0, h.defaults.strokeWidth)
        assertEquals(emptyList(), h.sent)
    }

    @Test
    fun mixedSelection_should_ShowTheMixedChipAndMarkNoChoice() {
        val h = host("r1", "r2")
        h.send(StyleElements(listOf(ElementId("r2")), StylePatch(strokeColor = "#e03131")))
        h.sent.clear()
        show(h, listOf("r1", "r2"))
        rule.onNodeWithTag(StylePanelTags.MIXED_PREFIX + "Stroke").assertTextEquals("Mixed")
        rule.onNodeWithTag(StylePanelTags.swatch("Black")).assertIsNotSelected()
        rule.onNodeWithTag(StylePanelTags.swatch("Red 600")).assertIsNotSelected()
    }

    @Test
    fun everySwatch_should_CarryItsTextName_UX15() {
        show(host("r1"), listOf("r1"))
        for (c in ColorPalette.stroke) rule.onNodeWithTag(StylePanelTags.swatch(c.name)).assert(hasContentDescription("Stroke ${c.name}"))
        for (c in ColorPalette.background) rule.onNodeWithTag(StylePanelTags.swatch(c.name)).assert(hasContentDescription("Fill ${c.name}"))
        rule.onNodeWithTag(StylePanelTags.swatch("Red 600")).performClick()
        rule.waitForIdle()
    }

    @Test
    fun opacityButtons_should_StepByTen_AndMixedHasNoNote() {
        val h = host("r1")
        show(h, listOf("r1"))
        rule.onNodeWithTag(StylePanelTags.OPACITY_MINUS).performClick()
        rule.waitForIdle()
        assertEquals(90, h.doc.scene[ElementId("r1")]!!.base.opacity)
        rule.onNodeWithTag(StylePanelTags.OPACITY_NOTE).assertDoesNotExist()
    }

    @Test
    fun statsPanel_should_CommitANumberOnDone_AndKeepTheOldValueOnText() {
        val h = host("r1")
        rule.setContent {
            @Suppress("UNUSED_EXPRESSION") h.tick
            ElementStatsPanel(h.doc.scene[ElementId("r1")]!!, technical = false, onCommand = h::send)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performTextClearance()
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performTextInput("12abc")
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performImeAction()
        rule.waitForIdle()
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x.error", useUnmergedTree = true).assertTextEquals(ElementStatsEdits.NOT_A_NUMBER)
        assertEquals(0, h.sent.size)
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performTextClearance()
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performTextInput("40")
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performImeAction()
        rule.waitForIdle()
        assertEquals(40.0, (h.doc.scene[ElementId("r1")] as RectElement).x.value)
    }
}
