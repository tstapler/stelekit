// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import arrow.core.flatMap
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SetContainerText
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ArrowTool
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.FrameTool
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.LineTool
import dev.stapler.stelekit.sketch.v2.tools.Modifiers
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.RectangleTool
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.toCommand
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.ui.sketch.v2.CrosshairDrawing
import dev.stapler.stelekit.ui.sketch.v2.CrosshairKey
import dev.stapler.stelekit.ui.sketch.v2.KeyAction
import dev.stapler.stelekit.ui.sketch.v2.KeyActionPlanner

/**
 * The scripted editor of Task 6.2.4a: tools in, commands and history out, the same path a gesture takes (tool event, `ToolCommand`,
 * `Command`, one `Transaction`, one history step), with ONE step counter under the convention of ux.md section 20 (one click,
 * tap or key chord, a menu choice, or one typed string with its confirming key; a drag counts as one and an arrow's release as a second).
 * It lives in `commonTest` so the JVM and Robolectric runs execute the very same script.
 */
class ScenarioEditor(limits: SceneLimits = SceneLimits.Default, private val pointer: PointerKind = PointerKind.Mouse) {
    private val metrics = FixedAdvanceTextMetricsProvider()
    private var next = 0
    private val ids = ElementIdSource { ElementId("e${next++}") }

    var history: HistoryV2 = HistoryV2.of(SketchDocumentV2(scene = SketchSceneV2.empty(limits), sheetUuid = SHEET), limits)
        private set
    var tool: Tool = SelectTool()
        private set

    /** Steps taken so far, under the one counting convention. */
    var steps: Int = 0
        private set

    /** The first refusal (for example `CapacityExceeded`) the script met, or null. */
    var refusal: CommandError? = null
        private set

    val doc: SketchDocumentV2 get() = history.present
    val scene: SketchSceneV2 get() = doc.scene

    private var clock = 0L

    private fun at(x: Int, y: Int) = ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y))

    private fun context() = ToolContext(scene, zoom = 1.0, style = ElementStyle(), ids = ids)

    private fun event(type: InputType, x: Int, y: Int) = InputEvent(type, at(x, y), null, pointer, InputEvent.PRIMARY_POINTER, clock++, Modifiers.None, 1)

    /** Picks [id] from the strip: one step. */
    fun pick(id: ToolId) {
        steps++
        tool = when (id) {
            ToolId.Rectangle -> RectangleTool()
            ToolId.Arrow -> ArrowTool()
            ToolId.Line -> LineTool()
            ToolId.Frame -> FrameTool()
            ToolId.Select -> SelectTool()
            else -> error("the architecture script does not use $id")
        }
    }

    /** A press-drag-release with the active tool; counts [cost] steps. The tool returns to Select after a commit (tool lock off). */
    fun drag(x0: Int, y0: Int, x1: Int, y1: Int, cost: Int = 1) {
        steps += cost
        var committed = false
        for (e in listOf(event(InputType.Down, x0, y0), event(InputType.Move, (x0 + x1) / 2, (y0 + y1) / 2), event(InputType.Move, x1, y1), event(InputType.Up, x1, y1))) {
            val result = tool.onEvent(e, context())
            result.command?.let { committed = commit(it) || committed }
            result.next?.let { tool = it }
        }
        if (committed && tool.id != ToolId.Select) tool = SelectTool()
    }

    /** Enter on a selected shape opens its text editor and the typed label plus Esc commits it: two steps. */
    fun label(containerId: ElementId, text: String) {
        steps += 2
        apply(SetContainerText(containerId, ids.next(), text, metrics))
    }

    /** The Ctrl+G chord over [selection]: one step, one history entry. */
    fun group(selection: List<ElementId>) {
        steps++
        KeyActionPlanner.plan(KeyAction.Group, scene, selection, ids).command?.let { apply(it) }
    }

    /** The ids the select tool holds after a marquee drag. */
    fun selection(): List<ElementId> = (tool as? SelectTool)?.selection.orEmpty()

    /**
     * A keyboard-only shape: Crosshair mode starts at [start], Enter opens the shape, [far] steps of ten units go right and down,
     * Enter finishes it. No pointer drag is used, so this is the non-drag path of UX-03.
     */
    fun crosshairShape(id: ToolId, start: ScenePoint, farRight: Int, farDown: Int) {
        val tool = when (id) {
            ToolId.Rectangle -> RectangleTool()
            ToolId.Frame -> FrameTool()
            else -> error("no crosshair shape for $id")
        }
        val session = checkNotNull(CrosshairDrawing.begin(tool, start)) { "no Crosshair session for $id" }
        runKeys(session, listOf(CrosshairKey.Enter) + repeated(CrosshairKey.Move(1, 0, far = true), farRight) + repeated(CrosshairKey.Move(0, 1, far = true), farDown) + CrosshairKey.Enter)
    }

    /** A keyboard-only arrow: Enter on a shape's edge binds it, [farRight] steps carry the crosshair to the next shape, Enter binds that end. */
    fun crosshairArrow(start: ScenePoint, farRight: Int) {
        val session = checkNotNull(CrosshairDrawing.begin(ArrowTool(), start)) { "no Crosshair session for the arrow tool" }
        runKeys(session, listOf(CrosshairKey.Enter) + repeated(CrosshairKey.Move(1, 0, far = true), farRight) + listOf(CrosshairKey.Enter, CrosshairKey.Enter))
    }

    private fun repeated(key: CrosshairKey, times: Int): List<CrosshairKey> = List(times) { key }

    private fun runKeys(start: CrosshairDrawing, keys: List<CrosshairKey>) {
        var session: CrosshairDrawing? = start
        for (key in keys) {
            val current = session ?: break
            val step = current.press(key, context(), clock++)
            step.command?.let { commit(it) }
            session = step.session
        }
    }

    /** Applies the whole move gesture and reports whether it committed; used by the bound-arrow check. */
    fun move(id: ElementId, dx: Int, dy: Int) {
        val box = scene[id] as RectElement
        val x = box.x.value.toInt() + box.width.value.toInt() / 2
        val y = box.y.value.toInt()
        pick(ToolId.Select)
        drag(x, y, x + dx, y + dy)
    }

    fun undo() {
        history = history.undo()
    }

    private fun commit(command: ToolCommand): Boolean = command.toCommand().fold({ refusal = refusal ?: it; false }, { apply(it) })

    private fun apply(command: dev.stapler.stelekit.sketch.v2.commands.Command): Boolean {
        val committed: Either<CommandError, dev.stapler.stelekit.sketch.v2.commands.Committed> = Transaction.begin(doc).apply(command).flatMap { it.commit() }
        return committed.flatMap { history.commit(it) }.fold({ refusal = refusal ?: it; false }, { history = it; true })
    }

    companion object {
        const val SHEET = "00000000-0000-4000-8000-0000000000a1"
    }
}

/** The element ids the architecture script produced, in creation order, for the assertions of the tests. */
class ArchitectureDiagram(val editor: ScenarioEditor, val boxes: List<ElementId>, val arrows: List<ElementId>, val frame: ElementId?)

/** The 15-element architecture diagram of requirements metric 2: 9 labelled boxes, 4 bound arrows, 1 frame, 1 group. */
object ArchitectureScenario {
    const val STEP_BUDGET = 60
    const val STEPS_EXPECTED = 52

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val BYTE_MASK = 0xFFL
    private const val HEX = 16
    private const val COLUMNS = 3
    private const val ROWS = 3
    private const val BOX_W = 120
    private const val BOX_H = 60
    private const val PITCH_X = 200
    private const val PITCH_Y = 120
    private const val ORIGIN_X = 100
    private const val ORIGIN_Y = 100
    private const val FAR = 10
    private val NAMES = listOf("Web", "API", "Auth", "Queue", "Worker", "Cache", "Search", "Store", "Audit")

    /** Pairs (from, to) of box indexes the four arrows join. */
    private val LINKS = listOf(0 to 1, 1 to 2, 3 to 4, 4 to 5)

    private fun x(col: Int) = ORIGIN_X + col * PITCH_X
    private fun y(row: Int) = ORIGIN_Y + row * PITCH_Y

    /** The canonical sheet JSON of what [editor] holds: the form the golden is kept in. */
    fun sceneJson(editor: ScenarioEditor): String = SheetCodecV2.encode(editor.doc).getOrNull()!!.decodeToString()

    /** FNV-1a (64 bit, hex) of the draw list of [scene] in [style] under fixed-advance metrics: equal on every target when the draw list is. */
    fun drawListDigest(scene: SketchSceneV2, style: RenderStyle): String {
        var hash = FNV_OFFSET
        for (b in DrawListJson.encode(DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene, style)).encodeToByteArray()) {
            hash = (hash xor (b.toLong() and BYTE_MASK)) * FNV_PRIME
        }
        return hash.toULong().toString(HEX)
    }

    /** Runs the script with the pointer: tools, drags, typed labels, a marquee and Ctrl+G. */
    fun run(editor: ScenarioEditor = ScenarioEditor()): ArchitectureDiagram {
        val boxes = mutableListOf<ElementId>()
        for ((index, name) in NAMES.withIndex()) {
            val col = index % COLUMNS
            val row = index / COLUMNS
            editor.pick(ToolId.Rectangle)
            editor.drag(x(col), y(row), x(col) + BOX_W, y(row) + BOX_H)
            val id = editor.scene.elementsInOrder.lastOrNull { it is RectElement && it.id !in boxes }?.id ?: return ArchitectureDiagram(editor, boxes, emptyList(), null)
            editor.label(id, name)
            boxes += id
        }
        val arrows = LINKS.map { (from, to) ->
            val a = editor.scene[boxes[from]] as RectElement
            val b = editor.scene[boxes[to]] as RectElement
            val midY = a.y.value.toInt() + BOX_H / 2
            editor.pick(ToolId.Arrow)
            editor.drag(a.x.value.toInt() + BOX_W, midY, b.x.value.toInt(), midY, cost = 2)
            editor.scene.elementsInOrder.last { it is ArrowElement }.id
        }
        editor.pick(ToolId.Frame)
        editor.drag(x(0) - FAR * 4, y(0) - FAR * 4, x(COLUMNS - 1) + BOX_W + FAR * 4, y(0) + BOX_H + FAR * 4)
        val frame = editor.scene.elementsInOrder.filterIsInstance<FrameElement>().single().id
        editor.drag(x(0) - FAR * 4, y(ROWS - 1) - FAR * 2, x(COLUMNS - 1) + BOX_W + FAR * 4, y(ROWS - 1) + BOX_H + FAR * 2)
        editor.group(editor.selection())
        return ArchitectureDiagram(editor, boxes, arrows, frame)
    }

    /** The same diagram with Touch pointers only and no drag: Crosshair mode places every shape, arrow and frame. */
    fun runWithoutDrag(editor: ScenarioEditor = ScenarioEditor(pointer = PointerKind.Touch)): ArchitectureDiagram {
        val boxes = mutableListOf<ElementId>()
        for ((index, name) in NAMES.withIndex()) {
            val col = index % COLUMNS
            val row = index / COLUMNS
            editor.crosshairShape(ToolId.Rectangle, ScenePoint(SceneUnit.ofInt(x(col)), SceneUnit.ofInt(y(row))), BOX_W / FAR, BOX_H / FAR)
            val id = editor.scene.elementsInOrder.last { it is RectElement }.id
            editor.label(id, name)
            boxes += id
        }
        val arrows = LINKS.map { (from, to) ->
            val a = editor.scene[boxes[from]] as RectElement
            editor.crosshairArrow(ScenePoint(SceneUnit.ofInt(a.x.value.toInt() + BOX_W), SceneUnit.ofInt(a.y.value.toInt() + BOX_H / 2)), (PITCH_X * (to - from).coerceAtLeast(1) - BOX_W) / FAR)
            editor.scene.elementsInOrder.last { it is ArrowElement }.id
        }
        editor.crosshairShape(ToolId.Frame, ScenePoint(SceneUnit.ofInt(x(0) - FAR * 4), SceneUnit.ofInt(y(0) - FAR * 4)), (x(COLUMNS - 1) + BOX_W + FAR * 8 - x(0)) / FAR, (BOX_H + FAR * 8) / FAR)
        val ids = boxes.drop(COLUMNS * (ROWS - 1)).flatMap { box -> listOf(box) + editor.scene.elementsInOrder.filter { (it as? TextElement)?.containerId == box }.map { it.id } }
        editor.group(ids)
        return ArchitectureDiagram(editor, boxes, arrows, editor.scene.elementsInOrder.filterIsInstance<FrameElement>().single().id)
    }
}
