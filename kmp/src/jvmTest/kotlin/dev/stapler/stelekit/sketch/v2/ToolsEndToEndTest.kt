// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ArcTool
import dev.stapler.stelekit.sketch.v2.tools.ArrowTool
import dev.stapler.stelekit.sketch.v2.tools.DiamondTool
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.EllipseTool
import dev.stapler.stelekit.sketch.v2.tools.EraserTool
import dev.stapler.stelekit.sketch.v2.tools.FrameTool
import dev.stapler.stelekit.sketch.v2.tools.FreedrawTool
import dev.stapler.stelekit.sketch.v2.tools.HandTool
import dev.stapler.stelekit.sketch.v2.tools.ImageTool
import dev.stapler.stelekit.sketch.v2.tools.LineTool
import dev.stapler.stelekit.sketch.v2.tools.PickedImage
import dev.stapler.stelekit.sketch.v2.tools.PointTool
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.RectangleTool
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.TextTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.ToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.toCommand
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.PointerAction
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer
import dev.stapler.stelekit.ui.sketch.v2.SketchPointerAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 6.2.4a, requirements metric 1: every one of the 14 tools of the frozen inventory is driven through the real pointer
 * adapter (raw pointer samples in, tool events out), the real tools, the bridge to commands and a transaction per gesture,
 * and the resulting sheet survives an encode and decode through the codec. The Text tool's typed string and the Image tool's
 * picked file are what the host would hand the tool, supplied here the way their tool tests do.
 */
class ToolsEndToEndTest {
    private val metrics = FixedAdvanceTextMetricsProvider()
    private val picked = PickedImage("file-1", 200, 100)

    private val registry = ToolRegistry.Empty
        .with(ToolId.Select) { SelectTool() }
        .with(ToolId.Hand) { HandTool() }
        .with(ToolId.Rectangle) { RectangleTool() }
        .with(ToolId.Diamond) { DiamondTool() }
        .with(ToolId.Ellipse) { EllipseTool() }
        .with(ToolId.Line) { LineTool() }
        .with(ToolId.Arrow) { ArrowTool() }
        .with(ToolId.Freedraw) { FreedrawTool() }
        .with(ToolId.Text) { TextTool(metrics) }
        .with(ToolId.Image) { ImageTool(picked) }
        .with(ToolId.Eraser) { EraserTool() }
        .with(ToolId.Frame) { FrameTool() }
        .with(ToolId.Point) { PointTool() }
        .with(ToolId.Arc) { ArcTool() }

    private inner class Rig {
        val adapter = SketchPointerAdapter()
        var doc = SketchDocumentV2(sheetUuid = ScenarioEditor.SHEET)
        var tool: Tool = SelectTool()
        var viewChanges = 0
        private var next = 0
        private var clock = 0L
        private val ids = ElementIdSource { ElementId("t${next++}") }

        fun use(id: ToolId) {
            tool = checkNotNull(registry.create(id)) { "no tool registered for $id" }
            adapter.handTool = id == ToolId.Hand
            adapter.selectTool = id == ToolId.Select
            adapter.drawingTool = id !in setOf(ToolId.Select, ToolId.Hand, ToolId.Eraser)
            adapter.touchTapsReachTool = id == ToolId.Select || id == ToolId.Eraser
        }

        private fun raw(phase: RawPhase, x: Double, y: Double) =
            RawPointer(id = 0, kind = PointerKind.Mouse, phase = phase, position = ScreenPx(x, y), timestamp = clock++)

        private fun feed(p: RawPointer) {
            for (action in adapter.onPointer(p)) {
                when (action) {
                    is PointerAction.ToolInput -> dispatch(action)
                    is PointerAction.ViewChange -> viewChanges++
                    is PointerAction.ContextMenu -> Unit
                }
            }
        }

        private fun dispatch(action: PointerAction.ToolInput) {
            val active = action.toolOverride?.let { checkNotNull(registry.create(it)) } ?: tool
            val result = active.onEvent(action.event, ToolContext(doc.scene, zoom = adapter.viewport.zoom, style = ElementStyle(), ids = ids))
            result.command?.let { command ->
                val tx = Transaction.begin(doc).apply(command.toCommand().getOrNull() ?: error("no command mapping for $command")).getOrNull() ?: error("refused: $command")
                doc = tx.commit().getOrNull()?.after ?: doc
            }
            if (action.toolOverride == null) result.next?.let { tool = it }
        }

        fun drag(x0: Double, y0: Double, x1: Double, y1: Double) {
            feed(raw(RawPhase.Down, x0, y0))
            feed(raw(RawPhase.Move, (x0 + x1) / 2, (y0 + y1) / 2))
            feed(raw(RawPhase.Move, x1, y1))
            feed(raw(RawPhase.Up, x1, y1))
        }

        fun click(x: Double, y: Double) {
            feed(raw(RawPhase.Down, x, y))
            feed(raw(RawPhase.Up, x, y))
        }

        fun typeAndCommit(text: String) {
            val editing = assertIs<TextTool.Editing>(tool)
            editing.session.replace(text)
            val result = editing.commit(ToolContext(doc.scene, zoom = 1.0, style = ElementStyle(), ids = ids))
            val command = assertNotNull(result.command).toCommand().getOrNull()!!
            doc = Transaction.begin(doc).apply(command).getOrNull()!!.commit().getOrNull()!!.after
            result.next?.let { tool = it }
        }
    }

    private inline fun <reified T> SketchSceneV2.count(): Int = elementsInOrder.count { it is T }

    private fun drawEveryShape(rig: Rig) {
        rig.use(ToolId.Rectangle); rig.drag(0.0, 0.0, 100.0, 60.0)
        rig.use(ToolId.Diamond); rig.drag(200.0, 0.0, 300.0, 60.0)
        rig.use(ToolId.Ellipse); rig.drag(400.0, 0.0, 500.0, 60.0)
        rig.use(ToolId.Line); rig.drag(0.0, 200.0, 100.0, 260.0)
        rig.use(ToolId.Arrow); rig.drag(150.0, 200.0, 300.0, 260.0)
        rig.use(ToolId.Freedraw); rig.drag(350.0, 200.0, 450.0, 260.0)
        rig.use(ToolId.Text); rig.click(0.0, 400.0); rig.typeAndCommit("Hello")
        rig.use(ToolId.Image); rig.click(300.0, 400.0)
        rig.use(ToolId.Frame); rig.drag(-20.0, 600.0, 120.0, 700.0)
        rig.use(ToolId.Point); rig.click(300.0, 620.0)
        rig.use(ToolId.Arc)
        rig.click(400.0, 600.0); rig.click(500.0, 600.0); rig.click(450.0, 570.0)
    }

    @Test
    fun tools_should_ProduceExpectedScene_When_AllToolsDrivenThroughPointerAdapter() {
        val rig = Rig()
        drawEveryShape(rig)
        val drawn = rig.doc.scene

        assertEquals(1, drawn.count<RectElement>(), "rectangle")
        assertEquals(1, drawn.count<DiamondElement>(), "diamond")
        assertEquals(1, drawn.count<EllipseElement>(), "ellipse")
        assertEquals(1, drawn.count<LineElement>(), "line")
        assertEquals(1, drawn.count<ArrowElement>(), "arrow")
        assertEquals(1, drawn.count<FreedrawElement>(), "freedraw")
        assertEquals(1, drawn.count<TextElement>(), "text")
        assertEquals(1, drawn.count<ImageElement>(), "image")
        assertEquals(1, drawn.count<FrameElement>(), "frame")
        assertEquals(1, drawn.count<PointElement>(), "point")
        assertEquals(1, drawn.count<ArcElement>(), "arc")
        assertIs<TextElement>(drawn.elementsInOrder.first { it is TextElement })
        assertIs<ImageElement>(drawn.elementsInOrder.first { it is ImageElement })

        rig.use(ToolId.Select)
        val boxId = drawn.elementsInOrder.first { it is RectElement }.id
        val before = (drawn[boxId] as RectElement).x.value
        rig.drag(50.0, 0.0, 80.0, 0.0)
        assertEquals(before + 30.0, (rig.doc.scene[boxId] as RectElement).x.value, "the Select tool moved the box")

        rig.use(ToolId.Eraser)
        val ellipseCount = rig.doc.scene.count<EllipseElement>()
        rig.drag(400.0, 30.0, 500.0, 30.0)
        assertEquals(ellipseCount - 1, rig.doc.scene.count<EllipseElement>(), "the Eraser tool deleted the ellipse it crossed")

        rig.use(ToolId.Hand)
        rig.drag(10.0, 10.0, 60.0, 40.0)
        assertTrue(rig.viewChanges > 0, "the Hand tool pans the view, never the scene")

        val reloaded = SheetCodecV2.decode(SheetCodecV2.encode(rig.doc).getOrNull()!!).getOrNull()!!
        assertEquals(rig.doc, reloaded, "the sheet survives the codec round trip")
    }

    @Test
    fun everyInventoryTool_should_BeDrivenByThisTest() {
        assertEquals(emptyList(), registry.missing(), "a tool added to the inventory needs a gesture in the end-to-end test")
    }
}
