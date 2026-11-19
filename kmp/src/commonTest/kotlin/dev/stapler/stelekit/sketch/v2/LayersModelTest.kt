// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.step
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteLayer
import dev.stapler.stelekit.sketch.v2.commands.DeleteLayerAndElements
import dev.stapler.stelekit.sketch.v2.commands.MoveToBaseThenDelete
import dev.stapler.stelekit.sketch.v2.commands.RenameLayer
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetLayerOpacity
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.MoveLayer
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.ui.sketch.v2.DeleteLayerDialog
import dev.stapler.stelekit.ui.sketch.v2.LayerNotices
import dev.stapler.stelekit.ui.sketch.v2.LayerSelectionRules
import dev.stapler.stelekit.ui.sketch.v2.LayersPanelModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.5.2 (ADR-006): layer commands, the panel model, selectability and the layer toast actions. */
class LayersModelTest {
    private val walls = LayerId("walls")
    private val base = LayerId.Default

    private fun twoLayers(): SketchDocumentV2 = run(SketchDocumentV2(), AddLayer(walls, "Walls"))

    private fun withWallElements(n: Int): SketchDocumentV2 =
        run(twoLayers(), AddElements((1..n).map { rect("w$it", it * 20, 0, layer = walls) }))

    @Test
    fun movingAndFading_should_BeSeparateUndoSteps_And_PaintWallsAbove() {
        val extra = LayerId("extra")
        val history = W6Kit.history(AddLayer(walls, "Walls"), AddLayer(extra, "Extra"), MoveLayer(walls, 2), SetLayerOpacity(walls, 0.5))
        val layers = history.present.scene.layers
        assertEquals(listOf(base, extra, walls), layers.sortedBy { it.order }.map { it.id })
        assertEquals(0.5, layers.first { it.id == walls }.opacity)
        val undone = history.undo()
        assertEquals(1.0, undone.present.scene.layer(walls)!!.opacity, "one undo reverts the opacity step only")
        assertEquals(2, undone.present.scene.layer(walls)!!.order, "the move stays")
        assertEquals(1, undone.undo().present.scene.layer(walls)!!.order, "the next undo reverts the move")
    }

    @Test
    fun panel_should_BeHiddenUntilASecondLayerExists_And_ListTopFirst() {
        assertFalse(LayersPanelModel.isVisible(SketchDocumentV2().scene))
        val doc = twoLayers()
        assertTrue(LayersPanelModel.isVisible(doc.scene))
        val rows = LayersPanelModel.rows(doc.scene, base)
        assertEquals(listOf("Walls", "Layer 1"), rows.map { it.layer.name })
        assertFalse(rows[0].canMoveUp)
        assertTrue(rows[0].canMoveDown)
        assertEquals("Layer Walls, visible", rows[0].visibilityLabel)
        assertEquals(LayersPanelModel.BASE_NOT_DELETABLE, rows[1].deleteDisabledReason)
        assertTrue(rows[1].active)
    }

    @Test
    fun toggles_should_ReadAsVisibleHiddenAndLockedUnlocked() {
        val doc = run(twoLayers(), SetLayerVisible(walls, false), SetLayerLocked(walls, true))
        val row = LayersPanelModel.rows(doc.scene, base).first { it.layer.id == walls }
        assertEquals("Layer Walls, hidden", row.visibilityLabel)
        assertEquals("Layer Walls, locked", row.lockLabel)
    }

    @Test
    fun deleteDialog_should_CountElements_And_BothActionsAreOneUndoStep() {
        val doc = withWallElements(12)
        val dialog = assertNotNull(LayersPanelModel.deleteDialog(doc.scene, walls))
        assertEquals("Delete layer 'Walls' and its 12 elements?", dialog.title)
        assertEquals(listOf("Delete layer and elements", "Move elements to Base then delete", "Cancel"), listOf(DeleteLayerDialog.DELETE_ALL_LABEL, DeleteLayerDialog.MOVE_LABEL, DeleteLayerDialog.CANCEL_LABEL))

        val deleted = W6Kit.history(AddLayer(walls, "Walls"), AddElements((1..12).map { rect("w$it", it, 0, layer = walls) }), DeleteLayerAndElements(walls))
        assertEquals(0, deleted.present.scene.size)
        assertNull(deleted.present.scene.layer(walls))
        assertEquals(12, deleted.undo().present.scene.size, "one undo restores the layer and its 12 elements")

        val moved = W6Kit.history(AddLayer(walls, "Walls"), AddElements((1..12).map { rect("w$it", it, 0, layer = walls) }), MoveToBaseThenDelete(walls))
        assertEquals(12, moved.present.scene.size)
        assertTrue(moved.present.scene.elementsInOrder.all { it.base.layerId == base })
        assertNull(moved.present.scene.layer(walls))
        assertEquals(walls, moved.undo().present.scene.elementsInOrder.first().base.layerId, "one undo puts them back on Walls")
    }

    @Test
    fun emptyLayer_should_DeleteWithoutAsking_And_BaseCannotBeDeleted() {
        val doc = twoLayers()
        assertNull(LayersPanelModel.deleteDialog(doc.scene, walls))
        assertNull(step(doc, DeleteLayer(walls)).scene.layer(walls))
        assertIs<CommandError.DefaultLayerProtected>(DeleteLayerAndElements(base).apply(doc).swap().get())
        assertNull(LayersPanelModel.deleteDialog(doc.scene, base))
    }

    @Test
    fun rename_should_RefuseACollision_And_TheLayerCapIsSceneLimits() {
        val doc = twoLayers()
        val error = RenameLayer(walls, "Layer 1").apply(doc).swap().get()
        assertIs<CommandError.DuplicateLayerName>(error)
        assertEquals("A layer with this name exists", LayersPanelModel.errorText(error))
        val limit = doc.scene.limits.maxLayers
        var full = SketchDocumentV2()
        for (i in 1 until limit) full = step(full, AddLayer(LayerId("l$i"), "L$i"))
        assertIs<CommandError.TooManyLayers>(AddLayer(LayerId("extra"), "Extra").apply(full).swap().get())
    }

    @Test
    fun hidingALayer_should_ClearItsSelectionAndAnnounceIt() {
        val doc = run(withWallElements(2), AddElement(rect("b1", 0, 50)), SetLayerVisible(walls, false))
        val outcome = LayerSelectionRules.afterHide(doc.scene, setOf(ElementId("w1"), ElementId("b1")), walls)
        assertEquals(setOf(ElementId("b1")), outcome.selection)
        assertEquals("Selection cleared: layer Walls hidden (1 element deselected)", outcome.announcement)
        assertNull(LayerSelectionRules.afterHide(doc.scene, setOf(ElementId("b1")), walls).announcement)
    }

    @Test
    fun selectability_should_FollowTheOneRule_ForCanvasAndElementsList() {
        val doc = run(withWallElements(1), AddElement(rect("b1", 0, 50)), SetLayerLocked(walls, true))
        val onLocked = doc.scene[ElementId("w1")]!!
        assertFalse(LayerSelectionRules.isSelectable(doc.scene, onLocked, LayerSelectionRules.Source.Canvas))
        assertTrue(LayerSelectionRules.isSelectable(doc.scene, onLocked, LayerSelectionRules.Source.ElementsList), "the list may select a locked element")
        val hidden = run(doc, SetLayerVisible(walls, false))
        assertFalse(LayerSelectionRules.isSelectable(hidden.scene, hidden.scene[ElementId("w1")]!!, LayerSelectionRules.Source.ElementsList), "never a hidden one")
    }

    @Test
    fun toastActions_should_UnlockSwitchAndUnlockAll_WithAnnouncements() {
        val locked = run(withWallElements(2), SetLayerLocked(walls, true))
        val unlock = LayerNotices.unlock(locked.scene.layer(walls)!!)
        assertEquals("Layer Walls unlocked", unlock.announcement)
        assertEquals(false, step(locked, unlock.command!!).scene.layer(walls)!!.locked)

        val switch = LayerNotices.switchLayer(locked.scene.layer(base)!!)
        assertEquals(base, switch.activeLayer)
        assertEquals("Active layer: Layer 1", switch.announcement)

        val lockedIds = listOf(ElementId("w1"), ElementId("w2"))
        val lockedEls = step(locked, dev.stapler.stelekit.sketch.v2.commands.LockElements(lockedIds, true))
        val all = LayerNotices.unlockAll(lockedIds)
        assertEquals("Unlocked 2 locked elements", all.announcement)
        assertTrue(step(lockedEls, all.command!!).scene.elementsInOrder.none { it.base.locked })
        assertEquals("2 locked elements were not changed", LayerNotices.skippedDetails(2))
    }
}
