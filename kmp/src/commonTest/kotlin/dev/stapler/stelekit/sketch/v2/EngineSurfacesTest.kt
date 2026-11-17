// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.binding.BindArrowEnd
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.ContainerText
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SheetStyleDefaults
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.EditorExtras
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.HostServices
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.PointerAction
import dev.stapler.stelekit.ui.sketch.v2.SelectionAction
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import dev.stapler.stelekit.ui.sketch.v2.setSheetStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine's own commands mounted on the editor: shape and arrow labels, freeing an arrow end, line point editing, seeds and the sheet style. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineSurfacesTest {
    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun pt(x: Int, y: Int) = ScenePoint(u(x), u(y))

    private fun rect(id: String, x: Int, y: Int, size: Int = 100) = RectElement(ElementBase(ElementId(id)), u(x), u(y), u(size), u(size))

    private fun TestScope.rig(vararg elements: SceneElement): Pair<SketchEditorController, EditorExtras> {
        val doc = AddElements(elements.toList()).apply(SketchDocumentV2(sheetUuid = "s")).fold({ error("fixture: $it") }, { it.doc })
        val dispatcher = StandardTestDispatcher(testScheduler)
        var n = 0
        val ids = ElementIdSource { ElementId("n${n++}") }
        val metrics = FixedAdvanceTextMetricsProvider()
        val controller = SketchEditorController(doc, EditorPorts(StandardToolRegistry.create(metrics), null, SolveRunner(context = dispatcher), ids), dispatcher)
        return controller to EditorExtras(controller, HostServices(), ModernPreviewEnvironment(metrics, PainterResources()))
    }

    private fun event(type: InputType, x: Int, y: Int, at: Long = 0L) = PointerAction.ToolInput(InputEvent(type, pt(x, y), timestamp = at))

    private fun SketchEditorController.press(x: Int, y: Int, at: Long) = tools.onPointerActions(listOf(event(InputType.Down, x, y, at), event(InputType.Up, x, y, at + 1)))

    @Test
    fun label_should_AddTextToTheShapeAndRemoveItAgain() = runTest {
        val (controller, extras) = rig(rect("r1", 0, 0))
        assertTrue(extras.engine.openLabel(ElementId("r1")))
        extras.engine.typeLabel("Gateway")
        extras.engine.commitLabel()
        assertEquals("Gateway", assertNotNull(ContainerText.labelOf(controller.doc.scene, ElementId("r1"))).originalText)

        assertTrue(extras.engine.openLabel(ElementId("r1")))
        assertEquals("Gateway", extras.engine.labelEdit?.text, "the editor opens on the label that is there")
        extras.engine.removeLabel()
        assertNull(ContainerText.labelOf(controller.doc.scene, ElementId("r1")))
        controller.close()
        extras.close()
    }

    @Test
    fun label_should_RefuseTextOverTheSceneCap_AndKeepTheOldText() = runTest {
        val (controller, extras) = rig(rect("r1", 0, 0))
        extras.engine.openLabel(ElementId("r1"))
        extras.engine.typeLabel("ok")
        extras.engine.typeLabel("x".repeat(controller.doc.scene.limits.maxTextChars + 1))
        assertEquals("ok", extras.engine.labelEdit?.text)
        controller.close()
        extras.close()
    }

    @Test
    fun arrowLabel_should_BeSetAtTheArrowAndALockedOrTextShapeCannotBeLabelled() = runTest {
        val arrow = ArrowElement(ElementBase(ElementId("a1")), listOf(pt(0, 0), pt(100, 0)))
        val text = TextElement(ElementBase(ElementId("t1")), u(0), u(50), u(10), u(10), text = "hi")
        val (controller, extras) = rig(arrow, text)
        assertTrue(extras.engine.openLabel(ElementId("a1")))
        extras.engine.typeLabel("sends")
        extras.engine.commitLabel()
        assertEquals("sends", assertNotNull(ContainerText.labelOf(controller.doc.scene, ElementId("a1"))).originalText)
        assertEquals(false, extras.engine.openLabel(ElementId("t1")), "plain text is not a container")
        controller.close()
        extras.close()
    }

    @Test
    fun unbind_should_FreeTheSelectedArrowEnd_AndTheMenuOffersOnlyBoundEnds() = runTest {
        val arrow = ArrowElement(ElementBase(ElementId("a1")), listOf(pt(-50, 50), pt(-10, 50)))
        val (controller, extras) = rig(rect("r1", 0, 0), arrow)
        controller.commitNow(BindArrowEnd(ElementId("a1"), BindingEnd.End, ElementId("r1")))
        controller.tools.select(listOf(ElementId("a1")))

        assertEquals(listOf(SelectionAction.EditLabel, SelectionAction.UnbindEnd), extras.engine.menuItems())
        extras.engine.run(SelectionAction.UnbindEnd)

        assertNull((controller.doc.scene[ElementId("a1")] as ArrowElement).endBinding)
        assertEquals(listOf(SelectionAction.EditLabel), extras.engine.menuItems())
        controller.close()
        extras.close()
    }

    @Test
    fun editPoints_should_LetADragMoveAVertexOfTheSelectedLine() = runTest {
        val line = LineElement(ElementBase(ElementId("l1")), listOf(pt(0, 0), pt(100, 0)))
        val (controller, extras) = rig(line)
        controller.tools.select(listOf(ElementId("l1")))
        assertTrue(SelectionAction.EditPoints in extras.engine.menuItems())

        assertTrue(extras.engine.editLinePoints())
        controller.tools.onPointerActions(listOf(event(InputType.Down, 100, 0), event(InputType.Move, 100, 40), event(InputType.Up, 100, 40)))

        assertEquals(40.0, (controller.doc.scene[ElementId("l1")] as LineElement).points.last().y.value)
        controller.close()
        extras.close()
    }

    @Test
    fun doubleClick_should_OpenTheLabelOfTheShapeUnderThePointer() = runTest {
        val (controller, extras) = rig(rect("r1", 0, 0))
        controller.press(50, 50, at = 0L)
        assertNull(extras.engine.labelEdit, "one click only selects")
        controller.press(51, 50, at = 200L)
        assertEquals(ElementId("r1"), extras.engine.labelEdit?.target)
        controller.close()
        extras.close()
    }

    @Test
    fun aDrawnShape_should_GetItsOwnSeedAndStartCleanOnADefaultSheet() = runTest {
        val (controller, extras) = rig()
        controller.tools.chooseTool(ToolId.Rectangle)
        controller.tools.onPointerActions(listOf(event(InputType.Down, 0, 0), event(InputType.Move, 60, 40), event(InputType.Up, 60, 40)))

        val drawn = controller.doc.scene.elementsInOrder.single()
        assertTrue(drawn.base.seed != 0, "a shape gets its seed when it is created")
        assertEquals(0, drawn.base.style.roughness, "a new sheet is Clean (OD-5)")
        controller.close()
        extras.close()
    }

    @Test
    fun aSketchySheet_should_StartNewShapesSketchy_AndPersistTheChoiceInTheSheetFile() = runTest {
        val (controller, extras) = rig()
        controller.setSheetStyle(SheetStyleDefaults(RenderStyle.Sketchy))
        controller.tools.chooseTool(ToolId.Rectangle)
        controller.tools.onPointerActions(listOf(event(InputType.Down, 0, 0), event(InputType.Move, 60, 40), event(InputType.Up, 60, 40)))

        assertEquals(1, controller.doc.scene.elementsInOrder.single().base.style.roughness)
        controller.reloaded(controller.doc.copy(extras = dev.stapler.stelekit.sketch.v2.commands.SheetStyleExtras.write(controller.doc.extras, controller.sheetStyle)))
        assertEquals(RenderStyle.Sketchy, controller.sheetStyle.renderStyle)
        controller.setSheetStyle(SheetStyleDefaults.Default)
        assertEquals(0, controller.defaultStyle.roughness)
        controller.close()
        extras.close()
    }
}
