// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.snap.GuideAxis
import dev.stapler.stelekit.sketch.v2.snap.QuarterTurn
import dev.stapler.stelekit.sketch.v2.snap.ResizeSides
import dev.stapler.stelekit.sketch.v2.snap.SnapGuide
import dev.stapler.stelekit.sketch.v2.snap.SnapOutcome
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import kotlin.math.abs
import kotlin.math.hypot

/** What the overlay and the host read from any state of the Select tool. */
interface SelectionAware {
    /** The selected ids in selection order. */
    val selection: List<ElementId>

    /** The marquee rectangle while one is being dragged, in scene units. */
    val marquee: Box?
}

/** The box, angle and capabilities of what the handles surround. */
data class SelectionFrame(val box: Box, val angle: Double, val rotatable: Boolean)

/**
 * Select tool (Story 3.2.2). A click selects the topmost selectable element (a whole group when it has one; Shift
 * toggles), a drag on an element moves the selection, a drag on a handle resizes or rotates it, a drag on empty canvas
 * is a marquee. Every gesture commits ONE command through the tool-command bridge, so one gesture is one undo step.
 * Locked elements and elements on locked or hidden layers are never selected from the canvas and are skipped by every
 * edit, raising [ToolNotice.LockedSkipped] when a mixed selection loses some (UX-12). Esc, a platform cancel or a second
 * pointer abandons the gesture with no command (UX-08).
 *
 * Immutable like every [Tool]: [selection] is state, and the host reads it back from the tool in [ToolResult.next].
 * [touchPresent] selects the 44 dp handle floor over the 24 dp mouse floor; [altDragDuplicates] is false on Linux (UX-109).
 */
class SelectTool(
    override val selection: List<ElementId> = emptyList(),
    private val touchPresent: Boolean = false,
    private val altDragDuplicates: Boolean = true,
) : Tool, SelectionAware {
    override val id: ToolId = ToolId.Select
    override val marquee: Box? get() = null

    private fun withSelection(ids: List<ElementId>) = SelectTool(ids, touchPresent, altDragDuplicates)

    // ---- overlay queries -------------------------------------------------------------------------------------------

    /** The selected elements that still exist and may be edited from the canvas. */
    fun editable(ctx: ToolContext): List<SceneElement> =
        selection.mapNotNull { ctx.scene[it] }.filter { Selectability.isSelectable(ctx.scene, it) }

    /** The box the handles surround: one element's own rotated box, or the joint box of several. */
    fun selectionFrame(ctx: ToolContext): SelectionFrame? {
        val items = editable(ctx)
        if (items.isEmpty()) return null
        val rotatable = items.all { it is BoxElement || it is EllipseElement }
        val single = items.singleOrNull()
        if (single != null && (single is BoxElement || single is EllipseElement)) return SelectionFrame(ownBox(single), ownAngle(single), rotatable)
        val joint = items.map { ElementBounds.geometry(it) }.reduce { a, b -> Bounds(minOf(a.minX, b.minX), minOf(a.minY, b.minY), maxOf(a.maxX, b.maxX), maxOf(a.maxY, b.maxY)) }
        return SelectionFrame(Box(joint.minX, joint.minY, joint.maxX - joint.minX, joint.maxY - joint.minY), 0.0, rotatable)
    }

    /**
     * The handles to draw and hit-test: floor-sized ones where they fit, else the four compact corners ([HandleLayout.compact]).
     * [around] is the frame to surround (default the selection's); the overlay passes the snapped preview frame while a drag runs.
     */
    fun handleLayout(ctx: ToolContext, touch: Boolean = touchPresent, around: SelectionFrame? = selectionFrame(ctx)): HandleLayout? {
        val f = around ?: return null
        val floorPx = TouchFloor.dp(touch) * ctx.pxPerDp
        val sized = HandleLayout.compute(f.box.cx, f.box.cy, f.box.width, f.box.height, f.angle, ctx.zoom, floorPx, f.rotatable)
        // On touch, a selection too small for floor-sized handles still gets its four corners, so a finger can resize it without
        // moving it. A mouse keeps the plain rule (no handles below the smallest tier): it can aim, or zoom.
        return if (sized.tier == HandleTier.None && touch) HandleLayout.compact(f.box.cx, f.box.cy, f.box.width, f.box.height, f.angle, ctx.zoom, floorPx) else sized
    }

    /** Half the touch floor (22 dp touch, 12 dp mouse), in scene units: how far from a vertex a press still grabs it. */
    private fun vertexTolerance(ctx: ToolContext, touch: Boolean): Double = TouchFloor.dp(touch) / 2.0 * ctx.pxPerDp / ctx.zoom

    /** The vertex of the single selected line that [point] is within reach of, nearest first; vertices win over the box handles they overlap. */
    private fun vertexUnder(point: ScenePoint, ctx: ToolContext, touch: Boolean): Pair<LineElement, Int>? {
        val line = editable(ctx).singleOrNull() as? LineElement ?: return null
        val tolerance = vertexTolerance(ctx, touch)
        val index = line.points.indices.minByOrNull { hypot(line.points[it].x.value - point.x.value, line.points[it].y.value - point.y.value) } ?: return null
        val d = hypot(line.points[index].x.value - point.x.value, line.points[index].y.value - point.y.value)
        return if (d <= tolerance) line to index else null
    }

    /** The vertices of the single selected line: the overlay draws each as a grab point, since a press on one drags just that point. */
    fun vertexPoints(ctx: ToolContext): List<ScenePoint> = (editable(ctx).singleOrNull() as? LineElement)?.points.orEmpty()

    // ---- host verbs (keyboard, selection bar) ----------------------------------------------------------------------

    fun selectAll(ctx: ToolContext): ToolResult = ToolResult(next = withSelection(Selectability.selectable(ctx.scene).map { it.id }))

    fun clearSelection(): ToolResult = ToolResult(next = withSelection(emptyList()))

    /** Selects exactly [ids], for the Elements list, Go to element and Lock (which leaves the locked elements selected). */
    fun select(ids: List<ElementId>): SelectTool = withSelection(ids)

    fun delete(ctx: ToolContext): ToolResult = whenEditable(ctx) { ids, skipped ->
        ToolResult(DeleteSelectionCommand(ids), null, withSelection(emptyList()), skipNotice(skipped))
    }

    /** Ctrl+D and the selection-bar button: copies offset by [DUPLICATE_OFFSET] units, copies selected afterwards. */
    fun duplicate(ctx: ToolContext): ToolResult = whenEditable(ctx) { ids, skipped ->
        val map = ids.associateWith { ctx.ids.next() }
        ToolResult(DuplicateSelectionCommand(ids, map, DUPLICATE_OFFSET, DUPLICATE_OFFSET), null, withSelection(map.values.toList()), skipNotice(skipped))
    }

    fun flip(ctx: ToolContext, horizontal: Boolean): ToolResult = whenEditable(ctx) { ids, skipped ->
        ToolResult(FlipSelectionCommand(ids, horizontal), null, null, skipNotice(skipped))
    }

    private fun whenEditable(ctx: ToolContext, build: (List<ElementId>, Int) -> ToolResult): ToolResult {
        val items = editable(ctx)
        if (items.isEmpty()) return ToolResult.Nothing
        return build(items.map { it.id }, selection.count { ctx.scene[it] != null } - items.size)
    }

    private fun skipNotice(skipped: Int): ToolNotice? = if (skipped > 0) ToolNotice.LockedSkipped(skipped) else null

    // ---- events ----------------------------------------------------------------------------------------------------

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when (event.type) {
        InputType.Down -> onDown(event, ctx)
        InputType.Escape -> if (selection.isEmpty()) ToolResult.Nothing else ToolResult(next = withSelection(emptyList()))
        else -> ToolResult.Nothing
    }

    private fun onDown(event: InputEvent, ctx: ToolContext): ToolResult {
        val touch = touchPresent || event.pointerKind != PointerKind.Mouse
        vertexUnder(event.point, ctx, touch)?.let { (line, index) ->
            return ToolResult(next = VertexDrag(this, event.pointerId, line, index, event.point))
        }
        val handle = handleLayout(ctx, touch)?.hit(event.point.x.value, event.point.y.value)
        if (handle != null) return ToolResult(next = Transforming(this, event.pointerId, handle, event.point, ctx))
        val hit = ctx.hitTester.hit(event.point, ctx.zoom, ctx.hitTolerancePx(touch))
        if (hit == null) return ToolResult(next = Marquee(this, event.pointerId, event.point, if (event.modifiers.shift) selection else emptyList()))
        val next = clickSelection(hit, selection, event.modifiers.shift, event.clickCount, ctx.scene)
        val duplicate = altDragDuplicates && event.modifiers.alt && hit in next
        return ToolResult(next = Pressed(withSelection(next), event.pointerId, event.point, duplicate, event.modifiers.shift, ctx))
    }

    // ---- states ----------------------------------------------------------------------------------------------------

    /** Pointer down on an element: a click until it travels past the drag threshold, then a move or an Alt-duplicate. */
    private class Pressed(
        private val owner: SelectTool,
        private val pointerId: Int,
        private val start: ScenePoint,
        private val duplicate: Boolean,
        private val shift: Boolean,
        ctx: ToolContext,
    ) : Tool, SelectionAware {
        override val id: ToolId get() = ToolId.Select
        override val selection: List<ElementId> get() = owner.selection
        override val marquee: Box? get() = null
        private val idMap: Map<ElementId, ElementId> =
            if (duplicate) owner.editable(ctx).associate { it.id to ctx.ids.next() } else emptyMap()

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> preview(event, ctx)
            event.type == InputType.Up -> finish(event, ctx)
            else -> ToolResult.Nothing
        }

        private fun delta(event: InputEvent): Pair<Double, Double> {
            var dx = event.point.x.value - start.x.value
            var dy = event.point.y.value - start.y.value
            if (event.modifiers.shift && !shift) if (abs(dx) >= abs(dy)) dy = 0.0 else dx = 0.0
            return dx to dy
        }

        /**
         * The move delta snapped to neighbours and the grid, so the preview shows what commits; Ctrl bypasses. A duplicate is
         * not snapped (its originals stay put as snap targets only in the next move), and a Shift axis lock keeps its zero axis.
         */
        private fun snappedDelta(event: InputEvent, items: List<ElementId>, ctx: ToolContext): SnapOutcome {
            val (dx, dy) = delta(event)
            if (duplicate) return SnapOutcome(dx, dy, emptyList())
            val out = ctx.snap.move(ctx.scene, ctx.snapIndex, items.toSet(), dx, dy, ctx.zoom, ctx.pxPerDp, bypass = event.modifiers.ctrl)
            val locked = event.modifiers.shift && !shift
            // A locked axis stays put, so a guide on it would show a snap that did not happen.
            val shown = out.guides.filterNot { (locked && dx == 0.0 && it.axis == GuideAxis.Vertical) || (locked && dy == 0.0 && it.axis == GuideAxis.Horizontal) }
            return SnapOutcome(if (locked && dx == 0.0) 0.0 else out.dx, if (locked && dy == 0.0) 0.0 else out.dy, shown)
        }

        private fun travelled(event: InputEvent, ctx: ToolContext): Boolean =
            hypot(event.point.x.value - start.x.value, event.point.y.value - start.y.value) * ctx.zoom > DRAG_THRESHOLD_PX

        private class Planned(val command: ToolCommand, val shown: List<ElementId>, val guides: List<SnapGuide>, val cursor: ScenePoint)

        private fun command(event: InputEvent, ctx: ToolContext): Planned? {
            val items = owner.editable(ctx).map { it.id }
            if (items.isEmpty()) return null
            val out = snappedDelta(event, items, ctx)
            val (dx, dy) = out.dx to out.dy
            // Where the grabbed point lands once the move snaps: where it was pressed plus the snapped delta (the loupe's crosshair).
            val cursor = ScenePoint(unitOrZero(start.x.value + dx), unitOrZero(start.y.value + dy))
            return if (duplicate) {
                val copies = idMap.filterKeys { it in items }
                Planned(DuplicateSelectionCommand(items, copies, dx, dy), copies.values.toList(), emptyList(), cursor)
            } else {
                Planned(MoveSelectionCommand(items, dx, dy, FrameMembership.changesAfterMove(ctx.scene, items.toSet(), dx, dy)), items, out.guides, cursor)
            }
        }

        private fun preview(event: InputEvent, ctx: ToolContext): ToolResult {
            if (!travelled(event, ctx)) return ToolResult.Nothing
            val plan = command(event, ctx) ?: return ToolResult.Nothing
            return ToolResult(preview = previewOf(plan.command, ctx, plan.shown, plan.cursor, plan.guides, owner.takeUnless { duplicate }))
        }

        private fun finish(event: InputEvent, ctx: ToolContext): ToolResult {
            if (!travelled(event, ctx)) return ToolResult(preview = null, next = owner)
            val cmd = command(event, ctx)?.command ?: return ToolResult(preview = null, next = owner)
            val skipped = owner.selection.count { ctx.scene[it] != null } - owner.editable(ctx).size
            val after = if (duplicate) owner.withSelection(idMap.values.toList()) else owner
            return ToolResult(cmd, null, after, owner.skipNotice(skipped))
        }
    }

    /**
     * One vertex of the selected line follows the pointer (one [SetLinePointsCommand], so one undo step). The vertex keeps
     * its offset from where it was pressed, so a finger resting beside it does not make it jump; it then snaps to a nearby
     * point of another shape, or to 15 degree steps from the previous vertex with Shift.
     */
    private class VertexDrag(
        private val owner: SelectTool,
        private val pointerId: Int,
        private val line: LineElement,
        private val index: Int,
        private val start: ScenePoint,
    ) : Tool, SelectionAware {
        override val id: ToolId get() = ToolId.Select
        override val selection: List<ElementId> get() = owner.selection
        override val marquee: Box? get() = null

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(preview = null, next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(preview = null, next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> ToolResult(preview = if (travelled(event, ctx)) moved(event, ctx).let { m -> previewOf(SetLinePointsCommand(line.id, m.points), ctx, listOf(line.id), m.points[index])?.let(m.placed::marked) } else null)
            event.type == InputType.Up ->
                if (travelled(event, ctx)) ToolResult(SetLinePointsCommand(line.id, moved(event, ctx).points), null, owner) else ToolResult(preview = null, next = owner)
            else -> ToolResult.Nothing
        }

        private fun travelled(event: InputEvent, ctx: ToolContext): Boolean =
            hypot(event.point.x.value - start.x.value, event.point.y.value - start.y.value) * ctx.zoom > DRAG_THRESHOLD_PX

        private class Moved(val points: List<ScenePoint>, val placed: PointSnapper.Placed)

        /**
         * The vertex where the drag has put it. With point snaps on (Snap button) it locks onto the drawing's features, the
         * line's own other vertices included, perpendicular and tangent measured from the previous vertex. Settings that
         * are [SnapSettings.None] (a bare tool context) keep the older nearest-vertex snap; any other settings snap only
         * as they say.
         */
        private fun moved(event: InputEvent, ctx: ToolContext): Moved {
            val grabbed = line.points[index]
            val raw = ScenePoint(
                unitOrZero(event.point.x.value + grabbed.x.value - start.x.value),
                unitOrZero(event.point.y.value + grabbed.y.value - start.y.value),
            )
            val touch = owner.touchPresent || event.pointerKind != PointerKind.Mouse
            val previous = line.points.getOrNull(index - 1)
            val others = line.points.filterIndexed { i, _ -> i != index }
            val placed = PointSnapper.place(raw, previous, event, ctx, exclude = setOf(line.id), extraVertices = others)
            val to = when {
                placed.hit != null -> placed.point
                ctx.snap == SnapSettings.None && !event.modifiers.ctrl -> VertexSnap.nearest(raw, line, index, owner.vertexTolerance(ctx, touch), ctx) ?: if (event.modifiers.shift && previous != null) AngleSnap.snap(previous, raw) else raw
                event.modifiers.shift && previous != null -> AngleSnap.snap(previous, raw)
                else -> placed.point
            }
            return Moved(line.points.toMutableList().also { it[index] = to }, placed)
        }
    }

    /** A handle drag: resize about the opposite handle, or rotate about the centre. */
    private class Transforming(
        private val owner: SelectTool,
        private val pointerId: Int,
        private val handle: Handle,
        private val start: ScenePoint,
        ctx: ToolContext,
    ) : Tool, SelectionAware {
        override val id: ToolId get() = ToolId.Select
        override val selection: List<ElementId> get() = owner.selection
        override val marquee: Box? get() = null
        private val items: List<SceneElement> = owner.editable(ctx)
        private val frame: SelectionFrame = checkNotNull(owner.selectionFrame(ctx)) { "a handle drag needs a selection" }

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(preview = null, next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(preview = null, next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> ToolResult(preview = steps(event, ctx)?.let { previewOf(TransformSelectionCommand(it.transforms), ctx, items.map { e -> e.id }, snappedCursor(event, ctx), it.guides, owner) })
            event.type == InputType.Up -> {
                val skipped = owner.selection.count { ctx.scene[it] != null } - items.size
                val cmd = steps(event, ctx)?.let { TransformSelectionCommand(it.transforms) }
                ToolResult(cmd, null, owner, owner.skipNotice(skipped))
            }
            else -> ToolResult.Nothing
        }

        /** The pointer with the resize snap applied (a rotate has none), so the loupe's crosshair sits on the edge that will land. */
        private fun snappedCursor(event: InputEvent, ctx: ToolContext): ScenePoint {
            if (handle == Handle.Rotate) return event.point
            // Aspect (Shift, or any text corner) and Alt (from centre) move the landing edge beyond the snap offset, so the crosshair follows the finger.
            val textCorner = items.singleOrNull() is TextElement && handle.sx != 0 && handle.sy != 0
            if (event.modifiers.shift || event.modifiers.alt || textCorner) return event.point
            val rawDx = event.point.x.value - start.x.value
            val rawDy = event.point.y.value - start.y.value
            val out = snappedDelta(rawDx, rawDy, event.modifiers, ctx)
            return ScenePoint(unitOrZero(event.point.x.value + out.dx - rawDx), unitOrZero(event.point.y.value + out.dy - rawDy))
        }

        private class Steps(val transforms: List<ElementTransform>, val guides: List<SnapGuide> = emptyList())

        private fun steps(event: InputEvent, ctx: ToolContext): Steps? {
            val dx = event.point.x.value - start.x.value
            val dy = event.point.y.value - start.y.value
            if (dx == 0.0 && dy == 0.0) return null
            return if (handle == Handle.Rotate) Steps(rotateSteps(event)) else resizeSteps(dx, dy, event.modifiers, ctx)
        }

        /**
         * Snaps the dragged edge(s) in the pointer delta before resizing, so the anchored edge stays put and Shift (aspect) and
         * Alt (from centre) still apply on top; Ctrl bypasses. Quarter-turn frames snap on their visible edges, any other angle stays raw (see [QuarterTurn]).
         */
        private fun snappedDelta(dx: Double, dy: Double, mods: Modifiers, ctx: ToolContext): SnapOutcome {
            val raw = SnapOutcome(dx, dy, emptyList())
            val aligned = QuarterTurn.align(frame.box.cx, frame.box.cy, frame.box.width, frame.box.height, frame.angle, ResizeSides(handle.sx, handle.sy)) ?: return raw
            return ctx.snap.resize(
                ctx.scene, ctx.snapIndex, items.mapTo(HashSet()) { it.id }, aligned.bounds, aligned.sides,
                dx, dy, keepAspect = mods.shift, fromCentre = mods.alt, zoom = ctx.zoom, pxPerDp = ctx.pxPerDp, bypass = mods.ctrl,
            )
        }

        private fun resizeSteps(rawDx: Double, rawDy: Double, mods: Modifiers, ctx: ToolContext): Steps {
            val single = items.singleOrNull()
            if (single is TextElement) return textResizeSteps(single, rawDx, rawDy, mods, ctx)
            val out = snappedDelta(rawDx, rawDy, mods, ctx)
            val next = TransformMath.resize(frame.box, frame.angle, handle, out.dx, out.dy, keepAspect = mods.shift, fromCentre = mods.alt)
            if (single != null) return Steps(listOf(fit(single, next, null)), out.guides)
            val moved = items.map { e ->
                val mapped = TransformMath.mapBox(ownBox(e), frame.box, next)
                when {
                    e is PointElement || e is ArcElement -> shift(e, mapped)
                    e is TextElement -> TextResize.groupScale(e, ownBox(e), mapped)
                    else -> fit(e, mapped, null)
                }
            }
            return Steps(moved, out.guides)
        }

        /**
         * A text corner always keeps its aspect ([TextResize]), so the snap picks its primary axis as for Shift. The guides are
         * dropped when the font limit stops the dragged edge short of the snapped line, so a guide only shows a snap that landed.
         */
        private fun textResizeSteps(e: TextElement, rawDx: Double, rawDy: Double, mods: Modifiers, ctx: ToolContext): Steps {
            val corner = handle.sx != 0 && handle.sy != 0
            val out = snappedDelta(rawDx, rawDy, mods.copy(shift = mods.shift || corner), ctx)
            val box = ownBox(e)
            val t = TextResize.handleDrag(e, box, handle, out.dx, out.dy, mods.alt, ctx.textMetrics)
            val wanted = TransformMath.resize(box, e.angle, handle, out.dx, out.dy, keepAspect = corner, fromCentre = mods.alt)
            val landed = (handle.sx == 0 || abs(t.width - wanted.width) < LANDED_EPSILON) && (handle.sy == 0 || abs(t.height - wanted.height) < LANDED_EPSILON)
            return Steps(listOf(t), if (landed) out.guides else emptyList())
        }

        private fun rotateSteps(event: InputEvent): List<ElementTransform> {
            val cx = frame.box.cx
            val cy = frame.box.cy
            if (items.size == 1) {
                val e = items.single()
                val angle = TransformMath.rotate(cx, cy, frame.angle, start.x.value, start.y.value, event.point.x.value, event.point.y.value, event.modifiers.shift)
                return listOf(fit(e, ownBox(e), angle))
            }
            val delta = TransformMath.normalise(
                TransformMath.rotate(cx, cy, 0.0, start.x.value, start.y.value, event.point.x.value, event.point.y.value, event.modifiers.shift),
            )
            return items.map { e ->
                val b = ownBox(e)
                val (ncx, ncy) = TransformMath.rotatePoint(cx, cy, b.cx, b.cy, delta)
                fit(e, Box(ncx - b.width / 2.0, ncy - b.height / 2.0, b.width, b.height), TransformMath.normalise(ownAngle(e) + delta))
            }
        }

        private fun fit(e: SceneElement, b: Box, angle: Double?): ElementTransform =
            ElementTransform.Fit(e.id, b.x, b.y, b.width, b.height, angle)

        private fun shift(e: SceneElement, mapped: Box): ElementTransform {
            val old = ownBox(e)
            return ElementTransform.Shift(e.id, mapped.cx - old.cx, mapped.cy - old.cy)
        }
    }

    /** A rubber-band rectangle over empty canvas; release selects the selectable elements it fully contains. */
    private class Marquee(
        private val owner: SelectTool,
        private val pointerId: Int,
        private val start: ScenePoint,
        private val base: List<ElementId>,
        private val current: ScenePoint = start,
    ) : Tool, SelectionAware {
        override val id: ToolId get() = ToolId.Select
        override val selection: List<ElementId> get() = base
        override val marquee: Box
            get() = Box(
                minOf(start.x.value, current.x.value), minOf(start.y.value, current.y.value),
                abs(current.x.value - start.x.value), abs(current.y.value - start.y.value),
            )

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> ToolResult(next = Marquee(owner, pointerId, start, base, event.point))
            event.type == InputType.Up -> {
                val box = Marquee(owner, pointerId, start, base, event.point).marquee
                val inside = if (box.width * ctx.zoom <= DRAG_THRESHOLD_PX && box.height * ctx.zoom <= DRAG_THRESHOLD_PX) emptyList() else contained(box, ctx)
                ToolResult(next = owner.withSelection(base + inside.filter { it !in base }))
            }
            else -> ToolResult.Nothing
        }

        private fun contained(box: Box, ctx: ToolContext): List<ElementId> {
            val area = Bounds(box.x, box.y, box.x + box.width, box.y + box.height)
            val candidates = ctx.spatialIndex?.query(area)?.mapNotNull { ctx.scene[it] } ?: ctx.scene.elementsInOrder
            return candidates
                .filter { Selectability.isSelectable(ctx.scene, it) }
                .filter { e -> ElementBounds.geometry(e).let { b -> b.minX >= area.minX && b.maxX <= area.maxX && b.minY >= area.minY && b.maxY <= area.maxY } }
                .map { it.id }
        }
    }

    companion object {
        /**
         * The selection after a click on [hit] with [selection] already held: Shift toggles the whole group, a double click descends one
         * group level, a click on a selected element keeps the selection and any other click selects the topmost group. The one rule the
         * canvas and the dimension values share.
         */
        fun clickSelection(hit: ElementId, selection: List<ElementId>, shift: Boolean, clickCount: Int, scene: SketchSceneV2): List<ElementId> {
            val group = groupOf(hit, scene)
            return when {
                shift -> if (hit in selection) selection - group.toSet() else selection + group.filter { it !in selection }
                clickCount >= DOUBLE_CLICK -> descend(hit, selection, scene) ?: if (hit in selection) selection else group
                hit in selection -> selection
                else -> group
            }
        }

        private fun groupOf(hit: ElementId, scene: SketchSceneV2): List<ElementId> {
            val outer = scene[hit]?.base?.groupIds?.lastOrNull() ?: return listOf(hit)
            return scene.elementsInOrder.filter { outer in it.base.groupIds && Selectability.isSelectable(scene, it) }.map { it.id }
        }

        /**
         * Double-click on a grouped element (UX-11): when the selection is exactly one group level of [hit], the next level
         * in (or [hit] alone below the innermost group); null when the selection is not a group level of [hit].
         */
        private fun descend(hit: ElementId, selection: List<ElementId>, scene: SketchSceneV2): List<ElementId>? {
            val outermostFirst = scene[hit]?.base?.groupIds?.asReversed().orEmpty()
            val levels = outermostFirst.map { g -> scene.elementsInOrder.filter { g in it.base.groupIds && Selectability.isSelectable(scene, it) }.map { it.id } }
            val at = levels.indexOfFirst { it.toSet() == selection.toSet() }
            return when {
                at < 0 -> null
                at + 1 < levels.size -> levels[at + 1]
                else -> listOf(hit)
            }
        }

        /** Pointer travel, in screen pixels, below which a press and release is a click rather than a drag. */
        const val DRAG_THRESHOLD_PX = 4.0

        /** The platform click count that means "descend one group level". */
        const val DOUBLE_CLICK = 2

        /** Ctrl+D copies land this many scene units (one inch) down and to the right of the originals. */
        const val DUPLICATE_OFFSET = 16.0

        /** How far, in scene units, a resized text side may fall from the snapped drag and still count as landed on its guide. */
        private const val LANDED_EPSILON = 1e-6
    }
}

internal fun ownBox(e: SceneElement): Box = when (e) {
    is BoxElement -> Box(e.x.value, e.y.value, e.width.value, e.height.value)
    is EllipseElement -> Box(e.cx.value - e.rx.value, e.cy.value - e.ry.value, e.rx.value * 2.0, e.ry.value * 2.0)
    else -> ElementBounds.geometry(e).let { Box(it.minX, it.minY, it.maxX - it.minX, it.maxY - it.minY) }
}

internal fun ownAngle(e: SceneElement): Double = when (e) {
    is BoxElement -> e.angle
    is EllipseElement -> e.angle
    else -> 0.0
}

/** What [command] would leave on the canvas for [shown], by running it against a scratch copy of the scene; null when it would fail. */
internal fun previewOf(
    command: ToolCommand,
    ctx: ToolContext,
    shown: Collection<ElementId>,
    cursor: ScenePoint? = null,
    guides: List<SnapGuide> = emptyList(),
    frameOwner: SelectTool? = null,
): ToolPreview? {
    val applied = command.toCommand().getOrNull()?.apply(SketchDocumentV2(ctx.scene))?.getOrNull() ?: return null
    val frame = frameOwner?.selectionFrame(ctx.copy(scene = applied.doc.scene))
    return ToolPreview(shown.mapNotNull { applied.doc.scene[it] }, guides = guides, frame = frame, cursor = cursor)
}
