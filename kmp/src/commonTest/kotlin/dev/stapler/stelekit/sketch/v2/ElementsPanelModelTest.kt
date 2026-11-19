// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElementsToLayer
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.ElementsPanelModel
import dev.stapler.stelekit.ui.sketch.v2.ElementsRow
import dev.stapler.stelekit.ui.sketch.v2.NameTagSubjects
import dev.stapler.stelekit.ui.sketch.v2.NameTagsModel
import dev.stapler.stelekit.ui.sketch.v2.RowOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.5.7b (UX-110, UX-12): the Elements tab rows, activation rules, rename errors, and the canvas name tags. */
class ElementsPanelModelTest {
    private val walls = LayerId("walls")

    private fun docWithTwoLayers(): SketchDocumentV2 {
        val base = W6Kit.run(SketchDocumentV2(), AddElement(W6Kit.rect("a", 0, 0)), AddElement(W6Kit.rect("b", 40, 0)), AddElement(W6Kit.circle("c", 100, 100)))
        return W6Kit.run(base, AddLayer(walls, "Walls"), MoveElementsToLayer(listOf(ElementId("b")), walls))
    }

    private fun items(rows: List<ElementsRow>) = rows.filterIsInstance<ElementsRow.Item>()

    @Test
    fun rows_should_GroupElementsUnderLayersTopLayerFirst_AndShowKindNames() {
        val rows = ElementsPanelModel.rows(docWithTwoLayers().scene)
        val headers = rows.filterIsInstance<ElementsRow.LayerHeader>()
        assertEquals(listOf("Walls", "Layer 1"), headers.map { it.layer.name })
        assertEquals(listOf("Rectangle 2", "Circle 1", "Rectangle 1"), items(rows).map { it.name })
        assertEquals(1, headers.first().elementCount)
        assertTrue(headers.all { it.expanded })
    }

    @Test
    fun rows_should_ListTopmostElementFirstWithinALayer() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElement(W6Kit.rect("a", 0, 0)), AddElement(W6Kit.rect("b", 40, 0)), AddElement(W6Kit.rect("c", 80, 0)))
        assertEquals(listOf("c", "b", "a"), items(ElementsPanelModel.rows(doc.scene)).map { it.element.id.value })
    }

    @Test
    fun rows_should_HideElementsOfACollapsedLayer_And_BoundLabelsAreNotRows() {
        val doc = docWithTwoLayers()
        val label = TextElement(
            doc.scene[ElementId("a")]!!.base.copy(id = ElementId("a-label")), W6Kit.u(0), W6Kit.u(0), W6Kit.u(10), W6Kit.u(10),
            text = "Gateway", containerId = ElementId("a"), textAlign = TextAlign.Center, verticalAlign = VerticalAlign.Middle,
        )
        val scene = W6Kit.run(doc, AddElement(label)).scene
        val rows = ElementsPanelModel.rows(scene, collapsed = setOf(walls))
        assertEquals(listOf("c", "a"), items(rows).map { it.element.id.value })
        assertEquals("Gateway", items(rows).first { it.element.id.value == "a" }.textLabel)
        assertTrue(items(rows).none { it.element.id.value == "b" }, "Walls is collapsed")
    }

    @Test
    fun items_should_SayLockedAndHiddenAsText_And_DimTheHiddenOnes() {
        val locked = W6Kit.run(docWithTwoLayers(), LockElements(listOf(ElementId("a")), true), SetLayerVisible(walls, false))
        val byId = items(ElementsPanelModel.rows(locked.scene)).associateBy { it.element.id.value }
        assertEquals("locked", byId.getValue("a").stateText)
        assertEquals("(hidden)", byId.getValue("b").stateText)
        assertTrue(byId.getValue("b").dimmed)
        assertTrue(byId.getValue("a").description.contains("locked"))
        assertEquals("", byId.getValue("c").stateText)
    }

    @Test
    fun activate_should_SelectLockedElementsWithUnlock_And_RefuseHiddenLayerOnesWithShowLayer() {
        val state = W6Kit.run(docWithTwoLayers(), LockElements(listOf(ElementId("a")), true), SetLayerVisible(walls, false))
        val byId = items(ElementsPanelModel.rows(state.scene)).associateBy { it.element.id.value }
        val lockedOutcome = assertIs<RowOutcome.Select>(ElementsPanelModel.activate(byId.getValue("a")))
        assertEquals(LockElements(listOf(ElementId("a")), false), lockedOutcome.unlock)
        val hiddenOutcome = assertIs<RowOutcome.LayerHidden>(ElementsPanelModel.activate(byId.getValue("b")))
        assertEquals(SetLayerVisible(walls, true), hiddenOutcome.showLayer)
        assertNull(assertIs<RowOutcome.Select>(ElementsPanelModel.activate(byId.getValue("c"))).unlock)
    }

    @Test
    fun rename_should_ReportATakenNameAndALongName_WithTheDocumentedText() {
        val doc = docWithTwoLayers()
        val taken = ElementsPanelModel.rename(ElementId("c"), "Rectangle 1").apply(doc)
        val error = assertNotNull(taken.leftOrNull())
        assertEquals("An element with this name exists", ElementsPanelModel.renameError(error))
        val tooLong = ElementsPanelModel.rename(ElementId("c"), "x".repeat(doc.scene.limits.maxNameChars + 1)).apply(doc).leftOrNull()
        assertTrue(ElementsPanelModel.renameError(assertNotNull(tooLong))!!.contains("${doc.scene.limits.maxNameChars}"))
        assertNull(ElementsPanelModel.renameError(CommandError.UnknownElement("x")))
        val ok = ElementsPanelModel.rename(ElementId("c"), "Pump").apply(doc).get().doc.scene[ElementId("c")]!!
        assertEquals("Pump", dev.stapler.stelekit.sketch.v2.scene.ElementNames.nameOf(ok))
    }

    @Test
    fun reveal_should_PanToAnOffscreenElement_WithoutZooming() {
        val scene = W6Kit.run(SketchDocumentV2(), AddElement(W6Kit.rect("far", 5000, 3000))).scene
        val result = assertNotNull(ElementsPanelModel.reveal(scene, ElementId("far"), SketchViewport.of(), ScreenRect(0.0, 0.0, 800.0, 600.0)))
        assertTrue(result.panned)
        assertEquals(1.0, result.viewport.zoom)
        assertTrue(result.viewport.panX < 0.0 && result.viewport.panY < 0.0)
    }

    @Test
    fun window_should_ReturnOnlyTheRowsOfTheVisibleRange() {
        val rows = ElementsPanelModel.rows(
            (0 until 200).fold(SketchDocumentV2()) { d, i -> W6Kit.run(d, AddElement(W6Kit.rect("e$i", i * 20, 0))) }.scene,
        )
        assertEquals(201, rows.size)
        assertEquals(12, ElementsPanelModel.window(rows, first = 10, count = 12).size)
        assertEquals(0, ElementsPanelModel.window(rows, first = 500, count = 5).size)
    }

    @Test
    fun nameTags_should_ShowSelectedAndNamed_AndEveryoneWhenShowNamesIsOn() {
        val doc = docWithTwoLayers()
        val vp = SketchViewport.of(panY = TAG_ROOM)
        val selected = NameTagsModel.layout(doc.scene, vp, 800.0, 600.0, NameTagSubjects(selected = setOf(ElementId("c"))))
        assertEquals(listOf("Circle 1"), selected.tags.map { it.text })
        val named = NameTagsModel.layout(doc.scene, vp, 800.0, 600.0, NameTagSubjects(selected = setOf(ElementId("c")), named = setOf(ElementId("a"))))
        assertEquals(setOf("Circle 1", "Rectangle 1"), named.tags.map { it.text }.toSet())
        val all = NameTagsModel.layout(doc.scene, vp, 800.0, 600.0, NameTagSubjects(showAll = true))
        assertEquals(3, all.tags.size + all.badges.sumOf { it.count })
    }

    @Test
    fun nameTags_should_CollapseOverlappingTagsIntoACountBadge_And_SkipHiddenLayersAndOffscreen() {
        val crowded = (0 until 4).fold(SketchDocumentV2()) { d, i -> W6Kit.run(d, AddElement(W6Kit.rect("k$i", i, 40))) }
        val layout = NameTagsModel.layout(crowded.scene, SketchViewport.of(), 800.0, 600.0, NameTagSubjects(showAll = true))
        assertTrue(layout.tags.isEmpty())
        assertEquals(listOf(4), layout.badges.map { it.count })
        val far = W6Kit.run(SketchDocumentV2(), AddElement(W6Kit.rect("far", 9000, 9000))).scene
        val offscreen = NameTagsModel.layout(far, SketchViewport.of(), 800.0, 600.0, NameTagSubjects(showAll = true))
        assertTrue(offscreen.tags.isEmpty() && offscreen.badges.isEmpty())
        val hiddenLayer = W6Kit.run(docWithTwoLayers(), SetLayerVisible(walls, false)).scene
        val shown = NameTagsModel.layout(hiddenLayer, SketchViewport.of(), 800.0, 600.0, NameTagSubjects(showAll = true))
        assertTrue(shown.tags.none { it.id.value == "b" } && shown.badges.none { ElementId("b") in it.ids })
    }

    @Test
    fun showNamesDefault_should_BeOnForTechnicalSheetsAndOffForDiagrams() {
        assertTrue(NameTagsModel.defaultShowNames(technicalOrConverted = true))
        assertTrue(!NameTagsModel.defaultShowNames(technicalOrConverted = false))
    }

    private companion object {
        /** Pan that keeps the tag above a rect at y = 0 on screen. */
        const val TAG_ROOM = 50.0
    }
}
