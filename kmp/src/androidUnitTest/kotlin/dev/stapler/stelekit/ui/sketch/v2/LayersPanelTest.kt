// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.5.2a (UX-16, UX-17, UX-18, UX-19): the layers panel over a live document. Robolectric, no display needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class LayersPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val walls = LayerId("walls")

    private fun rect(id: String, layer: LayerId) =
        RectElement(ElementBase(ElementId(id), layerId = layer), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(5), SceneUnit.ofInt(5))

    private fun docWith(elementsOnWalls: Int, twoLayers: Boolean = true): SketchDocumentV2 {
        var doc = SketchDocumentV2()
        fun run(c: Command) { doc = Transaction.begin(doc).apply(c).fold({ error("$it") }, { it.doc }) }
        if (twoLayers) run(AddLayer(walls, "Walls"))
        if (elementsOnWalls > 0) run(AddElements((1..elementsOnWalls).map { rect("w$it", walls) }))
        return doc
    }

    /** Shows the panel over [initial]; [applied] counts the commands the panel sent. */
    private class Host(initial: SketchDocumentV2) {
        var doc by mutableStateOf(initial)
        var active by mutableStateOf(LayerId.Default)
        var created = 0
        val applied = mutableListOf<Command>()
        fun send(c: Command): CommandError? {
            applied += c
            return Transaction.begin(doc).apply(c).fold({ it }, { t -> t.commit().fold({ null }, { done -> doc = done.after; null }) })
        }
    }

    private fun show(host: Host) {
        rule.setContent {
            LayersPanel(host.doc.scene, host.active, onActiveLayer = { host.active = it }, onCommand = host::send, onNewLayer = { host.created++ })
        }
        rule.waitForIdle()
    }

    @Test
    fun panel_should_RenderNothingUntilASecondLayerExists_UX19() {
        show(Host(docWith(0, twoLayers = false)))
        rule.onNodeWithTag(LayersPanelTags.NEW_LAYER).assertDoesNotExist()
    }

    @Test
    fun toggles_should_ReadAsSwitchesWithVisibleAndLockedState_UX17() {
        val host = Host(docWith(0))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.visible(walls))
            .assert(hasContentDescription("Layer Walls, visible"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(LayersPanelTags.visible(walls)).assert(hasContentDescription("Layer Walls, hidden"))
        rule.onNodeWithTag(LayersPanelTags.locked(walls)).assert(hasContentDescription("Layer Walls, unlocked")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(LayersPanelTags.locked(walls)).assert(hasContentDescription("Layer Walls, locked"))
        assertEquals(false, host.doc.scene.layer(walls)!!.visible)
        assertEquals(true, host.doc.scene.layer(walls)!!.locked)
    }

    @Test
    fun moveButtons_should_ReorderWithoutDragging_UX16() {
        val host = Host(docWith(0))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.up(walls)).assertIsNotEnabled()
        rule.onNodeWithTag(LayersPanelTags.down(walls)).performClick()
        rule.waitForIdle()
        assertEquals(0, host.doc.scene.layer(walls)!!.order)
        assertEquals(1, host.doc.scene.layer(LayerId.Default)!!.order)
    }

    @Test
    fun rowTap_should_SetTheActiveLayer_AndSayActive() {
        val host = Host(docWith(0))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.name(walls)).performClick()
        rule.waitForIdle()
        assertEquals(walls, host.active)
    }

    @Test
    fun renameCollision_should_ShowTheMessageAndKeepEditing() {
        val host = Host(docWith(0))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.rename(walls)).performClick()
        rule.onNodeWithTag(LayersPanelTags.RENAME_FIELD).performTextClearance()
        rule.onNodeWithTag(LayersPanelTags.RENAME_FIELD).performTextInput("Layer 1")
        rule.onNodeWithText("Save name").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(LayersPanelTags.RENAME_ERROR).assertTextEquals("A layer with this name exists")
        rule.onNodeWithTag(LayersPanelTags.RENAME_FIELD).assertIsDisplayed()
        assertEquals("Walls", host.doc.scene.layer(walls)!!.name)
    }

    @Test
    fun deleteWithElements_should_AskFirst_AndMoveToBaseIsOneCommand_UX18() {
        val host = Host(docWith(12))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.delete(walls)).performClick()
        rule.onNodeWithTag(LayersPanelTags.DIALOG_TITLE).assertTextEquals("Delete layer 'Walls' and its 12 elements?")
        rule.onNodeWithTag(LayersPanelTags.DELETE_CANCEL).performClick()
        rule.waitForIdle()
        assertEquals(12, host.doc.scene.size, "Cancel changes nothing")
        assertTrue(host.applied.isEmpty())
        rule.onNodeWithTag(LayersPanelTags.delete(walls)).performClick()
        rule.onNodeWithTag(LayersPanelTags.DELETE_MOVE).performClick()
        rule.waitForIdle()
        assertEquals(12, host.doc.scene.size)
        assertNull(host.doc.scene.layer(walls))
        assertEquals(1, host.applied.size)
    }

    @Test
    fun deleteAll_should_RemoveTheLayerAndItsElements() {
        val host = Host(docWith(3))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.delete(walls)).performClick()
        rule.onNodeWithTag(LayersPanelTags.DELETE_ALL).performClick()
        rule.waitForIdle()
        assertEquals(0, host.doc.scene.size)
    }

    @Test
    fun baseLayer_should_HaveDeleteDisabledWithItsReason() {
        show(Host(docWith(0)))
        rule.onNodeWithTag(LayersPanelTags.delete(LayerId.Default)).assertIsNotEnabled()
            .assert(hasContentDescription("Delete layer Layer 1, disabled: The base layer cannot be deleted"))
    }

    @Test
    fun opacity_should_ChangeThroughTheButtons_NotOnlyASlider() {
        val host = Host(docWith(0))
        show(host)
        rule.onNodeWithTag(LayersPanelTags.name(walls)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(LayersPanelTags.OPACITY_MINUS).performClick()
        rule.waitForIdle()
        assertEquals(0.9, host.doc.scene.layer(walls)!!.opacity, 1e-9)
    }
}
