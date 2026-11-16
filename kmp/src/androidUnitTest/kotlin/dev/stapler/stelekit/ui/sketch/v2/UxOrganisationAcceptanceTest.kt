// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveToBaseThenDelete
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.hit.HitTester
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX-12, UX-13, UX-16, UX-17, UX-18, UX-19 against a real document, the real hit tester and the layer models (Story 3.5.2).
 * The panel's own rendering is `LayersPanelTest`; this class is the scenario-level acceptance for the organisation surface.
 */
class UxOrganisationAcceptanceTest {
    private val walls = LayerId("walls")

    private fun rect(id: String, layer: LayerId, x: Int = 0) =
        RectElement(
            ElementBase(ElementId(id), layerId = layer, style = dev.stapler.stelekit.sketch.v2.scene.ElementStyle(backgroundColor = "#ffc9c9")),
            SceneUnit.ofInt(x), SceneUnit.ofInt(0), SceneUnit.ofInt(20), SceneUnit.ofInt(20),
        )

    private fun history(vararg commands: Command): HistoryV2 {
        var h = HistoryV2.of()
        for (c in commands) {
            val done = Transaction.begin(h.present).apply(c).fold({ error("$it") }, { it }).commit().fold({ error("$it") }, { it })
            h = h.commit(done).fold({ error("$it") }, { it })
        }
        return h
    }

    private fun hit(doc: SketchDocumentV2, x: Int): ElementId? {
        val index = SpatialIndex().also { it.rebuild(doc.scene) }
        return HitTester(doc.scene, index).hit(ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(10)), zoom = 1.0)
    }

    @Test
    fun ux12_canvas_should_NeverSelectLockedOrHiddenLayerElements_ButTheListMayAskForALockedOne() {
        val h = history(AddLayer(walls, "Walls"), AddElements(listOf(rect("w", walls, x = 0), rect("b", LayerId.Default, x = 100))))
        assertEquals(ElementId("w"), hit(h.present, 10))
        val locked = history(AddLayer(walls, "Walls"), AddElements(listOf(rect("w", walls))), SetLayerLocked(walls, true)).present
        assertNull(hit(locked, 10), "the canvas skips a locked layer")
        val element = locked.scene[ElementId("w")]!!
        assertTrue(LayerSelectionRules.isSelectable(locked.scene, element, LayerSelectionRules.Source.ElementsList))
        val hidden = history(AddLayer(walls, "Walls"), AddElements(listOf(rect("w", walls))), SetLayerVisible(walls, false)).present
        assertNull(hit(hidden, 10))
        assertTrue(!LayerSelectionRules.isSelectable(hidden.scene, hidden.scene[ElementId("w")]!!, LayerSelectionRules.Source.ElementsList))
    }

    @Test
    fun ux13_hidingTheLayerThatHoldsTheSelection_should_ClearAndAnnounceIt() {
        val doc = history(AddLayer(walls, "Walls"), AddElements(listOf(rect("w", walls))), SetLayerVisible(walls, false)).present
        val outcome = LayerSelectionRules.afterHide(doc.scene, setOf(ElementId("w")), walls)
        assertEquals(emptySet(), outcome.selection)
        assertTrue(outcome.announcement!!.startsWith("Selection cleared"))
    }

    @Test
    fun ux16_17_19_layersAreManagedByUndoableCommands_AndPanelAppearsWithTheSecondLayer() {
        val one = history(AddElements(listOf(rect("b", LayerId.Default))))
        assertTrue(!LayersPanelModel.isVisible(one.present.scene), "UX-19: hidden until a second layer exists")
        val two = history(AddElements(listOf(rect("b", LayerId.Default))), LayersPanelModel.newLayer(one.present.scene, walls))
        assertTrue(LayersPanelModel.isVisible(two.present.scene))
        assertEquals("Layer 2", two.present.scene.layer(walls)!!.name)
        assertEquals(1, two.undo().present.scene.layers.size, "UX-16: creating a layer is exactly one undo step")
        assertEquals("Layer Layer 2, visible", LayersPanelModel.rows(two.present.scene, LayerId.Default).first().visibilityLabel)
    }

    @Test
    fun ux18_deleteConfirmation_should_NameTheCountAndOfferBothOutcomes() {
        val h = history(AddLayer(walls, "Walls"), AddElements((1..12).map { rect("w$it", walls, x = it * 30) }))
        val dialog = LayersPanelModel.deleteDialog(h.present.scene, walls)!!
        assertEquals("Delete layer 'Walls' and its 12 elements?", dialog.title)
        assertEquals(MoveToBaseThenDelete(walls), dialog.moveToBase)
        assertEquals(0, h.undo().present.scene.size, "adding the 12 elements was one undo step")
    }
}
