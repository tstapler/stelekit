// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.sketch.v2.scene.WallScene
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** What the Line tool draws on a floor-plan sheet: plain lines, a chain of walls, or a door, window or opening placed on a wall. */
enum class PlanMode { Line, Wall, Door, Window, Opening }

/** The tool of each [PlanMode]; all of them live under the Line tool (the tool inventory is frozen, so there is no tool id of their own). */
object PlanTools {
    fun create(mode: PlanMode, wall: WallOptions = WallOptions()): Tool = when (mode) {
        PlanMode.Line -> LineTool()
        PlanMode.Wall -> WallTool(wall)
        PlanMode.Door -> OpeningTool(OpeningKind.Door)
        PlanMode.Window -> OpeningTool(OpeningKind.Window)
        PlanMode.Opening -> OpeningTool(OpeningKind.Opening)
    }
}

/** What a new wall is made of. [thickness] is in scene units; [angleSnap] is the "15 degrees" chip. */
data class WallOptions(
    val thickness: Double = WallElement.DEFAULT_THICKNESS_UNITS.toDouble(),
    val justification: WallJustification = WallJustification.Centre,
    val angleSnap: Boolean = false,
)

/** A fresh wall takes the wall fill when the current style has none, and is never drawn sketchy: a plan is crisp. */
internal fun wallStyle(style: ElementStyle): ElementStyle =
    style.copy(backgroundColor = if (style.backgroundColor == TRANSPARENT_FILL) WALL_FILL else style.backgroundColor, roughness = 0, roundness = null)

private const val TRANSPARENT_FILL = "transparent"
private const val WALL_FILL = "#ced4da"

/**
 * Wall tool: taps chain walls. Each tap adds a vertex (snapped onto the drawing's endpoints, midpoints and the walls already
 * placed); a tap on the first vertex of a chain of three or more closes the room; a tap on the last vertex, the Done chip
 * ([done]) or Enter finishes an open chain. A press-drag-release with no vertex yet draws a single wall. [withLength] places the
 * next vertex a typed length from the last one along the direction the pointer last pointed (the Measure keypad's entry).
 * A second pointer, Esc or a platform cancel abandons the chain with no command. Immutable: each event returns the next state.
 */
class WallTool private constructor(
    private val options: WallOptions,
    private val points: List<ScenePoint>,
    private val press: Press?,
    private val finished: Finished?,
    private val heading: Double,
) : Tool {
    constructor(options: WallOptions = WallOptions()) : this(options, emptyList(), null, null, 0.0)

    override val id: ToolId = ToolId.Line

    /** The vertices placed so far, for the host's status line and Done chip. */
    val placedPoints: List<ScenePoint> get() = points

    private class Press(val pointerId: Int, val at: ScenePoint)

    private class Finished(val at: ScenePoint, val timestamp: Long)

    private fun idle(finish: Finished? = null) = WallTool(options, emptyList(), null, finish, heading)

    private fun snapped(from: ScenePoint?, to: ScenePoint, event: InputEvent, ctx: ToolContext): PointSnapper.Placed {
        val placed = PointSnapper.place(to, from, event, ctx, extraVertices = points)
        val stepping = options.angleSnap || event.modifiers.shift
        if (placed.hit != null || from == null || !stepping) return placed
        return PointSnapper.Placed(AngleSnap.snap(from, to), null)
    }

    /** Finishes the chain as an open wall (the Done chip and Enter); nothing is committed with fewer than two distinct vertices. */
    fun done(ctx: ToolContext, timestamp: Long = 0L): ToolResult = finish(points, closed = false, ctx, timestamp)

    /** Closes the chain into a room (three or more vertices), or finishes it open when it has fewer. */
    fun close(ctx: ToolContext, timestamp: Long = 0L): ToolResult = finish(points, closed = points.size >= WallElement.MIN_CLOSED_POINTS, ctx, timestamp)

    /** True when [at] is on the chain's first vertex and the chain is long enough to close into a room. */
    private fun closesRoom(at: ScenePoint, ctx: ToolContext): Boolean =
        points.size >= WallElement.MIN_CLOSED_POINTS && near(points.first(), at, ctx)

    /**
     * Places the next vertex [length] scene units from the last one, along the direction the pointer last pointed (or along the
     * first axis when it never moved). Typed sizes are exact: nothing snaps them.
     */
    fun withLength(length: Double, ctx: ToolContext, timestamp: Long = 0L): ToolResult {
        val from = points.lastOrNull() ?: return ToolResult.Nothing
        if (!(length > 0.0) || !length.isFinite()) return ToolResult.Nothing
        val to = ScenePoint(unitOrZero(from.x.value + length * cos(heading)), unitOrZero(from.y.value + length * sin(heading)))
        if (to == from) return ToolResult.Nothing
        return addVertex(to, ctx, timestamp)
    }

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
        event.type == InputType.Escape || event.type == InputType.Cancel -> abandon()
        event.type == InputType.Down -> onDown(event, ctx)
        press == null -> if (event.type == InputType.Move && points.isNotEmpty()) hover(event, ctx) else ToolResult.Nothing
        event.pointerId != press.pointerId -> ToolResult.Nothing
        event.type == InputType.Move -> hover(event, ctx)
        event.type == InputType.Up -> onUp(event, ctx)
        else -> ToolResult.Nothing
    }

    private fun abandon(): ToolResult = if (points.isEmpty() && press == null) ToolResult.Nothing else ToolResult(preview = null, next = idle())

    private fun onDown(event: InputEvent, ctx: ToolContext): ToolResult = when {
        press != null && event.pointerId != press.pointerId -> ToolResult(preview = null, next = idle())
        isSecondClickOfFinish(event, ctx) -> ToolResult.Nothing
        else -> ToolResult(next = WallTool(options, points, Press(event.pointerId, event.point), finished, heading))
    }

    private fun isSecondClickOfFinish(event: InputEvent, ctx: ToolContext): Boolean {
        val f = finished ?: return false
        return event.timestamp - f.timestamp <= LineTool.DOUBLE_CLICK_MS && near(f.at, snapped(points.lastOrNull(), event.point, event, ctx).point, ctx)
    }

    private fun near(a: ScenePoint, b: ScenePoint, ctx: ToolContext): Boolean =
        hypot(a.x.value - b.x.value, a.y.value - b.y.value) * ctx.zoom <= SelectTool.DRAG_THRESHOLD_PX

    private fun hover(event: InputEvent, ctx: ToolContext): ToolResult {
        val from = points.lastOrNull() ?: press?.let { snapped(null, it.at, event, ctx).point }
        val placed = snapped(from, event.point, event, ctx)
        val turned = if (from == null) heading else headingOf(from, placed.point, heading)
        val shown = if (points.isEmpty()) listOfNotNull(from, placed.point) else points + placed.point
        val preview = wall(shown, closed = false, ElementBase(PREVIEW_ID, style = wallStyle(ctx.style)))?.let { placed.marked(ToolPreview(listOf(it))) }
        return ToolResult(preview = preview, next = WallTool(options, points, press, finished, turned))
    }

    private fun headingOf(from: ScenePoint, to: ScenePoint, fallback: Double): Double =
        if (from == to) fallback else atan2(to.y.value - from.y.value, to.x.value - from.x.value)

    private fun onUp(event: InputEvent, ctx: ToolContext): ToolResult {
        val down = checkNotNull(press) { "an Up is only handled with a press" }.at
        val dragged = !near(down, event.point, ctx)
        val placedDown = snapped(points.lastOrNull(), down, event, ctx).point
        return when {
            points.isEmpty() && dragged -> {
                val start = snapped(null, down, event, ctx).point
                finish(listOf(start, snapped(start, event.point, event, ctx).point), closed = false, ctx, event.timestamp)
            }
            closesRoom(down, ctx) || closesRoom(placedDown, ctx) -> finish(points, closed = true, ctx, event.timestamp)
            points.isNotEmpty() && (near(points.last(), down, ctx) || near(points.last(), placedDown, ctx)) -> finish(points, closed = false, ctx, event.timestamp)
            else -> addVertex(placedDown, ctx, event.timestamp)
        }
    }

    private fun addVertex(at: ScenePoint, ctx: ToolContext, timestamp: Long): ToolResult {
        val next = points + at
        if (next.size >= SceneLimits.Default.maxPointsPerElement) return finish(next, closed = false, ctx, timestamp)
        val turned = points.lastOrNull()?.let { headingOf(it, at, heading) } ?: heading
        return ToolResult(preview = chain(next, ctx), next = WallTool(options, next, null, null, turned))
    }

    /** What is placed so far, so a tap on a touch screen (no pointer move after it) still shows the chain: the walls, or a marker for the first vertex. */
    private fun chain(vertices: List<ScenePoint>, ctx: ToolContext): ToolPreview? {
        val walls = wall(vertices, closed = false, ElementBase(PREVIEW_ID, style = wallStyle(ctx.style)))
        if (walls != null) return ToolPreview(listOf(walls))
        val first = vertices.firstOrNull() ?: return null
        return ToolPreview(listOf(PointElement(ElementBase(PREVIEW_ID, style = ctx.style), first.x, first.y)))
    }

    private fun finish(vertices: List<ScenePoint>, closed: Boolean, ctx: ToolContext, timestamp: Long): ToolResult {
        val at = vertices.lastOrNull() ?: return ToolResult(preview = null, next = idle())
        val element = wall(vertices, closed, ElementBase(ctx.ids.next(), style = wallStyle(ctx.style)))
        return ToolResult(element?.let { AddElementCommand(it) }, null, idle(Finished(at, timestamp)))
    }

    /** A wall needs two vertices, no two consecutive ones the same place, and three for a closed room. */
    private fun wall(vertices: List<ScenePoint>, closed: Boolean, base: ElementBase): WallElement? {
        val distinct = vertices.fold(emptyList<ScenePoint>()) { acc, p -> if (acc.lastOrNull() == p) acc else acc + p }
        val room = if (closed && distinct.size > 1 && distinct.first() == distinct.last()) distinct.dropLast(1) else distinct
        val shut = closed && room.size >= WallElement.MIN_CLOSED_POINTS
        val thickness = SceneUnit.of(options.thickness).getOrNull() ?: return null
        val ok = room.size >= WallElement.MIN_POINTS && WallGeometry.hasDistinctVertices(room, shut)
        return if (ok) WallElement(base, room, thickness, options.justification, shut) else null
    }
}

/**
 * Door, window and opening tool: press on a wall (or hover with a mouse) to see where the opening lands, release to place it.
 * The opening is centred on the pointer, kept inside the wall segment it is on, and a door swings to the side that was
 * pressed. A press that is not on a wall places nothing. Esc or a second pointer abandons the gesture.
 */
class OpeningTool private constructor(private val kind: OpeningKind, private val pressedBy: Int?) : Tool {
    constructor(kind: OpeningKind) : this(kind, null)

    override val id: ToolId = ToolId.Line

    private class Placement(val host: WallElement, val segment: Int, val offset: Double, val width: Double, val flipSide: Boolean)

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when (event.type) {
        InputType.Escape, InputType.Cancel -> ToolResult(preview = null, next = OpeningTool(kind, null))
        InputType.Down -> ToolResult(next = OpeningTool(kind, event.pointerId))
        InputType.Move -> ToolResult(preview = previewAt(event, ctx))
        InputType.Up -> if (pressedBy == event.pointerId) commitAt(event, ctx) else ToolResult.Nothing
    }

    private fun placementAt(event: InputEvent, ctx: ToolContext): Placement? {
        val tolerance = ctx.hitTolerancePx(event.pointerKind != PointerKind.Mouse) / ctx.zoom
        val walls = ctx.scene.elementsInOrder.filterIsInstance<WallElement>()
        val best = walls.map { it to WallGeometry.probe(it, event.point.x.value, event.point.y.value) }
            .filter { (w, probe) -> probe.distance <= max(WallGeometry.offsets(w).left, WallGeometry.offsets(w).right) + tolerance }
            .minByOrNull { (_, probe) -> probe.distance } ?: return null
        val (wall, probe) = best
        val seg = WallGeometry.segments(wall)[probe.segment]
        val across = (WallGeometry.vec(event.point) - seg.a).dot(seg.n)
        val wanted = if (kind == OpeningKind.Window) OpeningElement.DEFAULT_WINDOW_UNITS.toDouble() else OpeningElement.DEFAULT_DOOR_UNITS.toDouble()
        val (offset, width) = WallGeometry.fit(wall, probe.segment, probe.s - wanted / 2.0, wanted) ?: return null
        return Placement(wall, probe.segment, offset, width, flipSide = across < 0.0)
    }

    private fun opening(at: Placement, base: ElementBase): OpeningElement? {
        val offset = SceneUnit.of(at.offset).getOrNull() ?: return null
        val width = SceneUnit.of(at.width).getOrNull() ?: return null
        return OpeningElement(base, at.host.id, at.segment, offset, width, kind, flipSide = at.flipSide)
    }

    private fun previewAt(event: InputEvent, ctx: ToolContext): ToolPreview? {
        val at = placementAt(event, ctx) ?: return null
        val base = ElementBase(PREVIEW_ID, style = wallStyle(ctx.style).copy(backgroundColor = TRANSPARENT_FILL))
        val placed = opening(at, base) ?: return null
        return ToolPreview(WallScene.openingLines(at.host, placed))
    }

    private fun commitAt(event: InputEvent, ctx: ToolContext): ToolResult {
        val at = placementAt(event, ctx) ?: return ToolResult(preview = null, next = OpeningTool(kind, null))
        val base = ElementBase(ctx.ids.next(), style = wallStyle(ctx.style).copy(backgroundColor = TRANSPARENT_FILL))
        val placed = opening(at, base) ?: return ToolResult(preview = null, next = OpeningTool(kind, null))
        return ToolResult(AddElementCommand(placed), null, OpeningTool(kind, null))
    }
}
