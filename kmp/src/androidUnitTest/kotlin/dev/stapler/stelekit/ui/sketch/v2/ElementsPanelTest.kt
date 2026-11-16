// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.sketch.v2.W6Kit
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElementsToLayer
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.5.7b (UX-110, UX-12): the Elements tab over a live document: layered groups, select, in-place rename with the
 * collision message, Esc to revert, Unlock and Show layer, and a virtualised list. Robolectric, no display needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class ElementsPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val walls = LayerId("walls")

    private class Host(initial: SketchDocumentV2) {
        var doc by mutableStateOf(initial)
        var selected by mutableStateOf<Set<ElementId>>(emptySet())
        val applied = mutableListOf<Command>()
        fun send(c: Command): CommandError? {
            applied += c
            return Transaction.begin(doc).apply(c).fold({ it }, { t -> t.commit().fold({ null }, { done -> doc = done.after; null }) })
        }
    }

    private fun docOf(count: Int = 3): SketchDocumentV2 =
        (1..count).fold(SketchDocumentV2()) { d, i -> W6Kit.run(d, AddElement(W6Kit.rect("e$i", i * 20, 0))) }

    private fun show(host: Host) {
        rule.setContent {
            ElementsPanel(host.doc.scene, host.selected, onSelect = { host.selected = setOf(it) }, onCommand = host::send)
        }
        rule.waitForIdle()
    }

    @Test
    fun tap_should_SelectTheElement_AndRowReadsNameKindAndState() {
        val host = Host(W6Kit.run(docOf(), LockElements(listOf(ElementId("e2")), true)))
        show(host)
        rule.onNodeWithTag(ElementsPanelTags.row(ElementId("e1"))).performClick()
        rule.waitForIdle()
        assertEquals(setOf(ElementId("e1")), host.selected)
        rule.onNodeWithTag(ElementsPanelTags.row(ElementId("e2")))
            .assert(hasContentDescription("Rectangle 2, locked"))
        rule.onNodeWithTag(ElementsPanelTags.unlock(ElementId("e2"))).performClick()
        rule.waitForIdle()
        assertEquals(false, host.doc.scene[ElementId("e2")]!!.base.locked)
    }

    @Test
    fun rename_should_KeepEditingWithTheMessage_When_TheNameIsTaken_AndCommitWhenFree() {
        val host = Host(docOf())
        show(host)
        rule.onNodeWithTag(ElementsPanelTags.rename(ElementId("e3"))).performClick()
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performTextClearance()
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performTextInput("Rectangle 1")
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag(ElementsPanelTags.RENAME_ERROR).assertTextEquals("An element with this name exists")
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).assertIsDisplayed()
        assertEquals("Rectangle 3", ElementNames.nameOf(host.doc.scene[ElementId("e3")]!!))
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performTextClearance()
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performTextInput("Pump")
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("Pump", ElementNames.nameOf(host.doc.scene[ElementId("e3")]!!))
        rule.onAllNodesWithTag(ElementsPanelTags.RENAME_FIELD).assertCountEquals(0)
    }

    @Test
    fun escape_should_RevertARename_WithNoCommand() {
        val host = Host(docOf())
        show(host)
        rule.onNodeWithTag(ElementsPanelTags.rename(ElementId("e1"))).performClick()
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performTextInput("zzz")
        rule.onNodeWithTag(ElementsPanelTags.RENAME_FIELD).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onAllNodesWithTag(ElementsPanelTags.RENAME_FIELD).assertCountEquals(0)
        assertTrue(host.applied.none { it is dev.stapler.stelekit.sketch.v2.commands.RenameElement })
    }

    @Test
    fun hiddenLayerRow_should_BeDimmedSayHidden_OfferShowLayer_And_NotSelect() {
        val base = W6Kit.run(docOf(), AddLayer(walls, "Walls"), MoveElementsToLayer(listOf(ElementId("e1")), walls), SetLayerVisible(walls, false))
        val host = Host(base)
        show(host)
        rule.onNodeWithTag(ElementsPanelTags.row(ElementId("e1")))
            .assert(hasContentDescription("Rectangle 1, (hidden)"))
            .performClick()
        rule.waitForIdle()
        assertTrue(host.selected.isEmpty(), "a hidden-layer row never selects")
        rule.onNodeWithTag(ElementsPanelTags.showLayer(ElementId("e1"))).performClick()
        rule.waitForIdle()
        assertEquals(true, host.doc.scene.layer(walls)!!.visible)
    }

    @Test
    fun layerHeader_should_CollapseAndExpandItsElements() {
        val host = Host(docOf())
        show(host)
        rule.onNodeWithTag(ElementsPanelTags.row(ElementId("e1"))).assertIsDisplayed()
        rule.onNodeWithTag(ElementsPanelTags.layer(LayerId.Default)).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(ElementsPanelTags.row(ElementId("e1"))).assertCountEquals(0)
        rule.onNodeWithTag(ElementsPanelTags.layer(LayerId.Default)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ElementsPanelTags.row(ElementId("e1"))).assertIsDisplayed()
    }

    @Test
    fun list_should_ComposeOnlyTheVisibleWindow_When_TheSheetHoldsManyElements() {
        val host = Host(docOf(count = 400))
        show(host)
        val composed = (1..400).count { rule.onAllNodesWithTag(ElementsPanelTags.row(ElementId("e$it"))).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(composed in 1..MAX_COMPOSED_ROWS, "composed $composed of 400 rows")
    }

    private companion object {
        const val MAX_COMPOSED_ROWS = 40
    }
}
