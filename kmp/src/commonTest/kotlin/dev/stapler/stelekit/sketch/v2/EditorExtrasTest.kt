// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.clipboard.ClipboardPayload
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.library.ElementLibrary
import dev.stapler.stelekit.sketch.v2.library.InMemoryLibraryStorage
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.MoveSelectionCommand
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.ClipboardPort
import dev.stapler.stelekit.ui.sketch.v2.EditorExtras
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.ExtraPanel
import dev.stapler.stelekit.ui.sketch.v2.HostServices
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.SelectionAction
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 6.2.0d: the clipboard, the library, grid and snap, the solver tier gate and the panels of the host's extras, over a real controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorExtrasTest {
    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun rect(id: String, x: Int) = RectElement(ElementBase(ElementId(id), zKey = ZKey("a$id")), u(x), u(0), u(10), u(10))

    private fun TestScope.rig(tier: SolverTier = SolverTier.Full, storage: InMemoryLibraryStorage = InMemoryLibraryStorage()): Pair<SketchEditorController, EditorExtras> {
        val doc = AddElements(listOf(rect("r1", 0), rect("r2", 100))).apply(SketchDocumentV2(sheetUuid = "s")).fold({ error("fixture: $it") }, { it.doc })
        val dispatcher = StandardTestDispatcher(testScheduler)
        var n = 0
        val ids = ElementIdSource { ElementId("n${n++}") }
        val metrics = FixedAdvanceTextMetricsProvider()
        val controller = SketchEditorController(doc, EditorPorts(StandardToolRegistry.create(metrics), null, SolveRunner(context = dispatcher), ids), dispatcher)
        val extras = EditorExtras(controller, HostServices(library = storage, tier = { tier }), ModernPreviewEnvironment(metrics, PainterResources()))
        return controller to extras
    }

    private fun SketchEditorController.rects() = doc.scene.elementsInOrder.filterIsInstance<RectElement>()

    @Test
    fun copyThenPaste_should_PutOurMarkerOnTheClipboardAndAddACopy() = runTest {
        val (controller, extras) = rig()
        var text: String? = null
        extras.clipboard.port = ClipboardPort({ text }, { text = it })
        controller.tools.select(listOf(ElementId("r1")))

        extras.clipboard.copy()
        assertTrue(text.orEmpty().startsWith(ClipboardPayload.MARKER), "the clipboard holds the sketch payload")
        extras.clipboard.paste()
        extras.clipboard.paste()

        assertEquals(4, controller.rects().size, "two pastes added two copies")
        assertEquals(10.0, controller.rects().filter { it.id.value.startsWith("n") }.map { it.x.value }.let { it[1] - it[0] }, "each paste steps by the paste offset")
        controller.close()
        extras.close()
    }

    @Test
    fun cut_should_RemoveTheSelectionAndPasteItBack() = runTest {
        val (controller, extras) = rig()
        var text: String? = null
        extras.clipboard.port = ClipboardPort({ text }, { text = it })
        controller.tools.select(listOf(ElementId("r2")))

        extras.clipboard.cut()
        assertEquals(1, controller.rects().size)
        extras.clipboard.paste()

        assertEquals(2, controller.rects().size)
        controller.close()
        extras.close()
    }

    @Test
    fun pasteOfUnknownText_should_ChangeNothing() = runTest {
        val (controller, extras) = rig()
        extras.clipboard.port = ClipboardPort({ "just words" }, {})

        extras.clipboard.paste()

        assertEquals(2, controller.rects().size)
        controller.close()
        extras.close()
    }

    @Test
    fun library_should_SaveInsertAndDeleteAndKeepTheTextInItsStorage() = runTest {
        val storage = InMemoryLibraryStorage()
        val (controller, extras) = rig(storage = storage)
        controller.tools.select(listOf(ElementId("r1")))

        extras.library.saveSelection()
        val item = extras.library.items.single()
        assertEquals(1, ElementLibrary.load(storage).items.size, "the storage holds the saved item")

        extras.library.insert(item.id)
        assertEquals(3, controller.rects().size, "the item inserted as fresh copies")
        extras.library.delete(item.id)
        assertTrue(extras.library.items.isEmpty())
        assertTrue(ElementLibrary.load(storage).items.isEmpty())
        controller.close()
        extras.close()
    }

    @Test
    fun gridOn_should_SnapAFinishedMoveToTheGridStep() = runTest {
        val (controller, extras) = rig()
        controller.tools.select(listOf(ElementId("r1")))
        extras.toggleGrid()

        val out = controller.interceptToolCommand(MoveSelectionCommand(listOf(ElementId("r1")), 13.0, 4.0)) as MoveSelectionCommand

        assertEquals(20.0, out.dx)
        assertEquals(0.0, out.dy)
        extras.toggleGrid()
        val untouched = controller.interceptToolCommand(MoveSelectionCommand(listOf(ElementId("r1")), 13.0, 4.0)) as MoveSelectionCommand
        assertEquals(13.0, untouched.dx, "grid off leaves the move alone")
        controller.close()
        extras.close()
    }

    @Test
    fun snapOn_should_AlignTheMovedShapeToANeighbourWithinTheSnapRadius() = runTest {
        val (controller, extras) = rig()
        extras.toggleSnap()

        // r1 spans x 0..10; moving it 87 puts its right edge at 97, within 8 px of r2's left edge at 100 (zoom 1).
        val out = controller.interceptToolCommand(MoveSelectionCommand(listOf(ElementId("r1")), 90.0, 0.0)) as MoveSelectionCommand

        assertEquals(90.0, out.dx, "the right edge (100) already meets r2's left edge")
        val near = controller.interceptToolCommand(MoveSelectionCommand(listOf(ElementId("r1")), 87.0, 0.0)) as MoveSelectionCommand
        assertEquals(90.0, near.dx, "3 units short of the neighbour snaps onto it")
        controller.close()
        extras.close()
    }

    @Test
    fun hiddenTier_should_RemoveEveryConstraintAid() = runTest {
        val (controller, extras) = rig(tier = SolverTier.Hidden)

        assertTrue(!extras.constraintsAvailable)
        controller.close()
        extras.close()
    }

    @Test
    fun selectionMenuItems_should_OpenTheirPanels_And_Others_Open_None() = runTest {
        val (controller, extras) = rig()

        extras.open(SelectionAction.Align)
        assertEquals(ExtraPanel.Align, extras.panel)
        extras.open(SelectionAction.Order)
        assertEquals(ExtraPanel.Order, extras.panel)
        extras.open(SelectionAction.Transform)
        assertEquals(ExtraPanel.Transform, extras.panel)
        extras.open(SelectionAction.BindEnd)
        assertEquals(ExtraPanel.Transform, extras.panel)
        extras.panel = null
        extras.open(SelectionAction.Duplicate)
        assertNull(extras.panel)
        controller.close()
        extras.close()
    }

    @Test
    fun commitNow_should_ApplyAsOneStepAndReturnTheDocumentItLeft() = runTest {
        val (controller, extras) = rig()

        val applied = controller.commitNow(AddElements(listOf(rect("r3", 200))))

        assertNotNull(applied.getOrNull())
        assertEquals(3, controller.rects().size)
        assertEquals(1, controller.history.undoDepth)
        controller.close()
        extras.close()
    }
}
