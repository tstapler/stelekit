// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.math.abs
import kotlin.math.min

/** Events in, commands and previews out (Task 3.1.1b); every tool test folds its events through [runScenario]. */
class ScenarioResult(
    val commands: List<ToolCommand>,
    /** The preview after each event, in order (null = cleared). */
    val previews: List<ToolPreview?>,
    val finalTool: Tool,
)

class ScenarioBuilder internal constructor() {
    internal val events = mutableListOf<InputEvent>()
    private var clock = 0L

    private fun at(x: Int, y: Int) = ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y))

    private fun add(type: InputType, x: Int, y: Int, pointer: Int, kind: PointerKind) {
        events += InputEvent(type, at(x, y), pointerKind = kind, pointerId = pointer, timestamp = clock++)
    }

    fun down(x: Int, y: Int, pointer: Int = InputEvent.PRIMARY_POINTER, kind: PointerKind = PointerKind.Mouse) = add(InputType.Down, x, y, pointer, kind)
    fun move(x: Int, y: Int, pointer: Int = InputEvent.PRIMARY_POINTER, kind: PointerKind = PointerKind.Mouse) = add(InputType.Move, x, y, pointer, kind)
    fun up(x: Int, y: Int, pointer: Int = InputEvent.PRIMARY_POINTER, kind: PointerKind = PointerKind.Mouse) = add(InputType.Up, x, y, pointer, kind)
    fun cancel(pointer: Int = InputEvent.PRIMARY_POINTER) = add(InputType.Cancel, 0, 0, pointer, PointerKind.Mouse)
    fun escape() { events += InputEvent.escape(clock++) }
}

fun testToolContext(scene: SketchSceneV2 = SketchSceneV2.empty()): ToolContext {
    var n = 0
    return ToolContext(scene, zoom = 1.0, style = ElementStyle(), ids = ElementIdSource { ElementId("e${n++}") })
}

fun runScenario(start: Tool, ctx: ToolContext = testToolContext(), build: ScenarioBuilder.() -> Unit): ScenarioResult {
    val events = ScenarioBuilder().apply(build).events
    var tool = start
    val commands = mutableListOf<ToolCommand>()
    val previews = mutableListOf<ToolPreview?>()
    for (event in events) {
        val result = tool.onEvent(event, ctx)
        result.command?.let { commands += it }
        previews += result.preview
        result.next?.let { tool = it }
    }
    return ScenarioResult(commands, previews, tool)
}

/**
 * A minimal press-drag-release rectangle used to exercise [DragGestureTool] and the harness before the real
 * Rectangle tool lands (Story 3.2.1); not a registered tool.
 */
class ScenarioRectTool : DragGestureTool() {
    override val id: ToolId = ToolId.Rectangle

    override fun idle(): Tool = this

    private fun rect(start: ScenePoint, end: ScenePoint, ctx: ToolContext): RectElement {
        val x = min(start.x.value, end.x.value)
        val y = min(start.y.value, end.y.value)
        return RectElement(
            ElementBase(ctx.ids.next(), style = ctx.style),
            SceneUnit.of(x).getOrNull()!!, SceneUnit.of(y).getOrNull()!!,
            SceneUnit.of(abs(end.x.value - start.x.value)).getOrNull()!!, SceneUnit.of(abs(end.y.value - start.y.value)).getOrNull()!!,
        )
    }

    override fun previewFor(start: ScenePoint, current: ScenePoint, event: InputEvent, ctx: ToolContext) =
        ToolPreview(listOf(rect(start, current, ctx)))

    override fun commandFor(start: ScenePoint, end: ScenePoint, event: InputEvent, ctx: ToolContext) =
        AddElementCommand(rect(start, end, ctx))
}

/** The scenario test classes that cover each tool id; a tool with none is listed in [pendingStory] with the story that adds it. */
object ToolScenarioCatalog {
    val covered: Map<ToolId, List<String>> = mapOf(
        ToolId.Rectangle to listOf("ToolCancelTest"),
    )

    /** May only shrink: when a tool gains a scenario test, move it to [covered] and delete its line here. */
    val pendingStory: Map<ToolId, String> = mapOf(
        ToolId.Select to "3.2.2",
        ToolId.Diamond to "3.2.1",
        ToolId.Ellipse to "3.2.1",
        ToolId.Line to "3.2.1",
        ToolId.Eraser to "3.2.3",
        ToolId.Hand to "3.2.3",
        ToolId.Frame to "3.2.3",
        ToolId.Point to "3.2.4",
        ToolId.Arc to "3.2.4",
        ToolId.Arrow to "3.3.1",
        ToolId.Text to "3.4.1",
        ToolId.Freedraw to "3.4.2",
        ToolId.Image to "3.4.3",
    )
}
