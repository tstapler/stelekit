// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.AddFrameCommand
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.LinePointEditTool
import dev.stapler.stelekit.sketch.v2.tools.PlanMode
import dev.stapler.stelekit.sketch.v2.tools.PlanTools
import dev.stapler.stelekit.sketch.v2.tools.WallTool
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.SelectionAware
import dev.stapler.stelekit.sketch.v2.tools.TextEditSession
import dev.stapler.stelekit.sketch.v2.tools.TextTool
import dev.stapler.stelekit.sketch.v2.tools.MultiPointerTool
import dev.stapler.stelekit.sketch.v2.tools.UnderlayAdjustTool
import dev.stapler.stelekit.sketch.v2.tools.UnderlayCalibrateTool
import dev.stapler.stelekit.sketch.v2.tools.Tool
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.ToolPreview

/**
 * The active tool of one editor and what feeds it: the pointer adapter, the strip's tool choice and the selection the Select tool
 * holds. A finished gesture's [ToolCommand] goes to [onCommand]; the session itself never edits the document. The tool is an
 * immutable state machine, so this class only holds the current state and the preview it asked to show.
 */
@Suppress("TooManyFunctions")
class ToolSession(
    private val ui: SketchEditorState,
    private val ports: EditorPorts,
    private val context: () -> ToolContext,
    private val notifier: EditorNotifier,
    private val readOnly: Boolean,
    private val onCommand: (ToolCommand) -> Unit,
) {
    val adapter = SketchPointerAdapter(ui.viewport, ui.fingerDrawing)

    /** The touch magnifier's state; it follows the tool inputs that [onPointerActions] feeds, and a view change ends it. */
    val loupe = LoupeTracker()

    /** The shape being drawn over the canvas; null when no gesture runs. */
    var preview: ToolPreview? by mutableStateOf(null)
        private set

    /** The text edit open on the canvas (the Text tool's editing state), or null. */
    var textSession: TextEditSession? by mutableStateOf(null)
        private set

    /** Where a secondary click or a long press asked for a context menu, in scene space; the host shows and clears it. */
    var contextMenuAt: ScenePoint? by mutableStateOf(null)

    /** Asked for the second quick press of the Select tool over an element; true means the host took the press (it opens that element's label). */
    var onDoubleClick: ((ElementId) -> Boolean)? = null

    /** The shape whose interior holds a point, for a press that hits no outline (a transparent shape is hit on its edge only); null by default. */
    var interiorAt: (ScenePoint) -> ElementId? = { null }

    /** What the Line tool draws now: plain lines, walls, or a door, window or opening on a wall (the chips of a floor plan pick it). */
    var planMode: PlanMode by mutableStateOf(PlanMode.Line)
        private set

    /** How many vertices the wall chain being drawn holds (0 when none, or another mode is active); observable, so the chain's chips follow it. */
    var wallVertexCount: Int by mutableIntStateOf(0)
        private set

    private val doubleClicks = DoubleClickDetector()
    private var tool: Tool = ports.tools.create(ToolId.Select) ?: error("the tool registry has no Select tool")
    private var overrideTool: Tool? = null

    init {
        adapter.isEmptyCanvas = { px -> context().let { it.hitTester.hit(ui.viewport.toScene(px), ui.viewport.zoom, it.hitTolerancePx(ports.touchPresent)) } == null }
        configureAdapter()
    }

    /** Feeds what [SketchPointerAdapter] decided to the active tool, the view and the context menu. */
    fun onPointerActions(actions: List<PointerAction>) {
        syncTool()
        for (action in actions) {
            when (action) {
                is PointerAction.ToolInput -> if (!readOnly) {
                    feed(action)
                    loupe.onInput(action.event, ui.viewport, context().pxPerDp)
                }
                is PointerAction.ViewChange -> {
                    ui.viewport = action.viewport
                    loupe.onViewChange()
                }
                is PointerAction.ContextMenu -> contextMenuAt = action.at
            }
        }
    }

    /** One raw pointer sample from the canvas; the shell's view and tool state apply first. */
    fun onPointer(sample: RawPointer) {
        syncInput()
        onPointerActions(adapter.onPointer(sample))
    }

    fun onWheel(wheel: WheelInput) {
        syncInput()
        onPointerActions(adapter.onWheel(wheel))
    }

    /** Switches the Line tool to [mode]; an unfinished chain or gesture is dropped. */
    fun choosePlanMode(mode: PlanMode) {
        ui.chooseTool(ToolId.Line)
        planMode = mode
        tool = PlanTools.create(mode)
        wallVertexCount = 0
        preview = null
        textSession = null
        configureAdapter()
    }

    /** The Done chip: finishes the wall chain as an open wall. */
    fun finishWalls() {
        (tool as? WallTool)?.let { take(it.done(context(), notifier.now())) }
    }

    /** The Close room chip: joins the chain's last vertex back to its first. */
    fun closeRoom() {
        (tool as? WallTool)?.let { take(it.close(context(), notifier.now())) }
    }

    /** The typed length of the next wall, in scene units (the Length keypad). */
    fun typeWallLength(units: Double) {
        (tool as? WallTool)?.let { take(it.withLength(units, context(), notifier.now())) }
    }

    /** Picks [id] from the strip, a key or the overflow menu. */
    fun chooseTool(id: ToolId) {
        ui.chooseTool(id)
        syncTool()
    }

    /** Makes the adapter and the active tool follow the shell's state (view, tool, Finger drawing); the host calls it after each recomposition. */
    fun syncInput() {
        if (adapter.viewport != ui.viewport) adapter.setViewport(ui.viewport)
        adapter.fingerDrawing = ui.fingerDrawing
        syncTool()
    }

    /** Selects [ids] (the Elements panel, Go to element) and gives them to the Select tool. */
    fun select(ids: List<ElementId>) {
        ui.selection = ids.mapTo(LinkedHashSet()) { it.value }
        if (ui.activeTool != ToolId.Select) ui.chooseTool(ToolId.Select)
        tool = SelectTool(ids, ports.touchPresent)
        preview = null
        configureAdapter()
    }

    /** The document changed: the Select tool re-reads the selection, which the edit may have shortened. */
    fun selectionPruned() {
        if (tool is SelectionAware && tool.id == ToolId.Select) tool = SelectTool(ui.selection.map(::ElementId), ports.touchPresent)
    }

    /** Hands the pointer to point editing of the line [id] (move a vertex, insert one on a segment); Esc returns to Select. */
    fun editLinePoints(id: ElementId) {
        tool = LinePointEditTool(id)
        preview = null
        textSession = null
        configureAdapter()
    }

    /** Hands the pointer to the underlay [next] tool (calibrate or adjust); it returns to Select when it ends. */
    fun beginUnderlay(next: Tool) {
        if (ui.activeTool != ToolId.Select) ui.chooseTool(ToolId.Select)
        tool = next
        preview = null
        textSession = null
        configureAdapter()
    }

    /** True while an underlay tool holds the pointer. */
    val underlayActive: Boolean get() = tool is UnderlayCalibrateTool || tool is UnderlayAdjustTool

    /** Commits the open text edit (the overlay's Done, a tap outside, blur). */
    fun commitText() {
        val editing = tool as? TextTool.Editing ?: return
        take(editing.commit(context()))
    }

    /** True while the tool is the idle Select tool the strip chose, which has no gesture to abandon. */
    private val selecting: Boolean get() = tool.id == ui.activeTool && tool is SelectionAware

    /** Esc: the tool abandons its gesture, else a drawing tool returns to Select, else the selection is dropped (ux.md section 17). */
    fun escape() {
        if (preview != null || textSession != null || !selecting) {
            val idleDrawingTool = preview == null && textSession == null && ui.activeTool != ToolId.Select
            val result = tool.onEvent(InputEvent.escape(notifier.now()), context())
            take(result)
            // Row 10 of the one Esc order: a tool with nothing left to abandon hands the canvas back to Select.
            if (idleDrawingTool && result.next == null && result.command == null) chooseTool(ToolId.Select)
        } else {
            ui.selection = emptySet()
            tool = SelectTool(emptyList(), ports.touchPresent)
        }
        readSelection()
    }

    /** Makes [tool] follow `ui.activeTool` (the strip writes it directly) and the adapter follow the tool. */
    private fun syncTool() {
        if (tool.id != ui.activeTool) {
            planMode = PlanMode.Line
            wallVertexCount = 0
            tool = freshTool(ui.activeTool)
            preview = null
            textSession = null
            ports.log.toolActivated(ui.activeTool.name)
        }
        configureAdapter()
    }

    private fun freshTool(id: ToolId): Tool =
        if (id == ToolId.Select) SelectTool(ui.selection.map(::ElementId), ports.touchPresent) else ports.tools.create(id) ?: tool

    private fun configureAdapter() {
        val active = ui.activeTool
        adapter.handTool = active == ToolId.Hand
        adapter.selectTool = active == ToolId.Select
        adapter.touchTapsReachTool = active == ToolId.Select || active == ToolId.Eraser
        adapter.drawingTool = active != ToolId.Select && active != ToolId.Hand
        adapter.underlayTool = tool is UnderlayCalibrateTool || tool is UnderlayAdjustTool
        adapter.multiPointerTool = tool is MultiPointerTool
    }

    private fun feed(input: PointerAction.ToolInput) {
        val override = input.toolOverride
        val event = if (override == null) counted(input.event) ?: return else input.event
        val running = if (override != null) overrideTool ?: ports.tools.create(override) else tool
        running ?: return
        val result = running.onEvent(event, context())
        if (override == null) {
            take(result)
            return
        }
        overrideTool = if (input.event.type == InputType.Up || input.event.type == InputType.Cancel) null else result.next ?: running
        result.notice?.let(notifier::toolNotice)
        result.command?.let { commit(it) }
    }

    /** Takes what the active tool answered: its next state, the preview, the selection, a text edit and what it committed. */
    private fun take(result: dev.stapler.stelekit.sketch.v2.tools.ToolResult) {
        result.next?.let {
            tool = it
            configureAdapter()
        }
        wallVertexCount = (tool as? WallTool)?.placedPoints?.size ?: 0
        preview = result.preview
        readSelection()
        textSession = (tool as? TextTool.Editing)?.session
        result.notice?.let(notifier::toolNotice)
        result.command?.let { commit(it) }
    }

    /** Marks the second quick press of the Select tool as a double click (the platform events carry no count); null when the host took the press. */
    private fun counted(event: InputEvent): InputEvent? {
        if (event.type != InputType.Down || tool !is SelectionAware) return event
        val hit = context().let { it.hitTester.hit(event.point, ui.viewport.zoom, it.hitTolerancePx(ports.touchPresent)) } ?: interiorAt(event.point)
        if (!doubleClicks.press(event, hit)) return event
        return if (hit != null && onDoubleClick?.invoke(hit) == true) null else event.copy(clickCount = 2)
    }

    private fun readSelection() {
        (tool as? SelectionAware)?.let { ui.selection = it.selection.mapTo(LinkedHashSet()) { id -> id.value } }
    }

    private fun commit(command: ToolCommand) {
        onCommand(command)
        if (command is AddElementCommand || command is AddFrameCommand) afterCreation(command as? AddElementCommand)
    }

    /** Told after a shape was drawn and selected (tool lock off); the technical sheet opens the Width field from it. */
    var onShapeCreated: (AddElementCommand) -> Unit = {}

    /** A shape was committed: the tool returns to Select unless tool lock is on (UX-04) and the new element is selected. */
    private fun afterCreation(created: AddElementCommand?) {
        val keep = ui.toolLock
        ui.onShapeCommitted()
        if (!keep) {
            ui.selection = created?.let { setOf(it.element.id.value) } ?: emptySet()
            tool = freshTool(ui.activeTool)
            preview = null
            configureAdapter()
            created?.let(onShapeCreated)
        }
    }
}
