package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.sketch.ScreenPoint
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.ViewTransform
import dev.stapler.stelekit.sketch.hitPointer
import dev.stapler.stelekit.sketch.snapPointer
import dev.stapler.stelekit.sketch.translated
import dev.stapler.stelekit.sketch.withVertex
import dev.stapler.stelekit.units.Inches
import kotlin.math.hypot
import kotlin.math.roundToLong

/** What a one-finger drag that latched beyond the touch slop does. */
sealed interface DragIntent {
    /** Pans the view (select tool on empty space, or crosshair mode). */
    data object Pan : DragIntent

    /** Draws a new line or rectangle from [start]. */
    data class Draw(val start: SketchPoint) : DragIntent

    /** Moves one handle of the selected shape. */
    data class MoveVertex(val ref: VertexRef) : DragIntent

    /** Moves a whole shape. */
    data class MoveShape(val id: String) : DragIntent
}

/** The in-flight result of a drag: the shape as it would be committed, and the snap that produced its active point. */
data class DragPreview(val shape: SketchPrimitive, val point: SketchPoint, val snap: SnapKind, val axisAnchor: SketchPoint?)

/** Visible handle radius and the larger touch target around it. */
const val HANDLE_RADIUS_DP = 8f
const val HANDLE_HIT_DP = 24f

private const val PREVIEW_ID = "drag-preview"

/** Screen distance in dp between a sheet point and a screen position, or null when the transform overflows. */
private fun dpDistance(view: ViewTransform, p: SketchPoint, to: ScreenPoint): Double? {
    val s = view.toScreen(p).getOrNull() ?: return null
    return hypot(s.x.toFloat().toDouble() - to.x.toFloat(), s.y.toFloat().toDouble() - to.y.toFloat())
}

/** Index of the selected shape's handle within [HANDLE_HIT_DP] of [down], nearest first. */
fun handleAt(selected: SketchPrimitive?, view: ViewTransform, down: ScreenPoint): Int? =
    selected?.vertices?.withIndex()
        ?.mapNotNull { (i, v) -> dpDistance(view, v, down)?.takeIf { it <= HANDLE_HIT_DP }?.let { i to it } }
        ?.minByOrNull { it.second }?.first

/**
 * Decides what a drag starting at [down] means. A handle of the selected shape wins in any tool;
 * otherwise draw tools draw, and the select tool moves the shape under the finger or pans on empty space.
 */
fun resolveDragStart(
    tool: SketchTool,
    scene: SketchScene,
    selectedId: String?,
    view: ViewTransform,
    down: ScreenPoint,
    gridStep: Inches?,
): DragIntent {
    val selected = scene.primitives.firstOrNull { it.id == selectedId }
    handleAt(selected, view, down)?.let { return DragIntent.MoveVertex(VertexRef(selected!!.id, it)) }
    return when (tool) {
        SketchTool.LINE, SketchTool.RECTANGLE ->
            snapPointer(scene, view, down, gridStep).getOrNull()?.let { DragIntent.Draw(it.point) } ?: DragIntent.Pan
        SketchTool.ZONE_SELECT ->
            hitPointer(scene, view, down).getOrNull()?.primitive?.let { DragIntent.MoveShape(it.id) } ?: DragIntent.Pan
    }
}

/** The preview for [intent] with the finger at [current] ([down] is where it landed); null for pans and unresolvable positions. */
fun previewDrag(
    intent: DragIntent,
    tool: SketchTool,
    scene: SketchScene,
    view: ViewTransform,
    down: ScreenPoint,
    current: ScreenPoint,
    gridStep: Inches?,
): DragPreview? = when (intent) {
    DragIntent.Pan -> null
    is DragIntent.Draw -> snapPointer(scene, view, current, gridStep, intent.start).getOrNull()?.let { snap ->
        val shape = if (tool == SketchTool.RECTANGLE) SketchPrimitive.Rect(PREVIEW_ID, intent.start, snap.point)
        else SketchPrimitive.Line(PREVIEW_ID, intent.start, snap.point)
        DragPreview(shape, snap.point, snap.kind, intent.start)
    }
    is DragIntent.MoveVertex -> scene.primitives.firstOrNull { it.id == intent.ref.primitiveId }?.let { target ->
        val others = scene.filtered { it.id != target.id }
        val anchor = (target as? SketchPrimitive.Line)?.let { if (intent.ref.index == 0) it.b else it.a }
        snapPointer(others, view, current, gridStep, anchor).getOrNull()?.let { snap ->
            target.withVertex(intent.ref.index, snap.point)?.let { DragPreview(it, snap.point, snap.kind, anchor) }
        }
    }
    is DragIntent.MoveShape -> scene.primitives.firstOrNull { it.id == intent.id }?.let { target ->
        val anchorVertex = target.vertices.first()
        val from = view.toScreen(anchorVertex).getOrNull()
        val others = scene.filtered { it.id != target.id }
        // Snap where the shape's first vertex would land; the whole shape follows by the same offset.
        from?.let {
            val landing = ScreenPoint(
                it.x.add(pxDelta(current.x, down.x)).getOrNull() ?: return@let null,
                it.y.add(pxDelta(current.y, down.y)).getOrNull() ?: return@let null,
            )
            snapPointer(others, view, landing, gridStep).getOrNull()?.let { snap ->
                val moved = target.translated(snap.point.x.sixteenths - anchorVertex.x.sixteenths, snap.point.y.sixteenths - anchorVertex.y.sixteenths)
                DragPreview(moved, snap.point, snap.kind, null)
            }
        }
    }
}

private fun pxDelta(a: dev.stapler.stelekit.units.Rational, b: dev.stapler.stelekit.units.Rational) =
    a.subtract(b).getOrNull() ?: dev.stapler.stelekit.units.Rational.ZERO

/** Short name of a snap for the on-canvas indicator; null when nothing snapped. */
fun SnapKind.indicatorLabel(): String? = when (this) {
    SnapKind.ENDPOINT -> "Endpoint"
    SnapKind.MIDPOINT -> "Midpoint"
    SnapKind.INTERSECTION -> "Intersection"
    SnapKind.GRID -> "Grid"
    SnapKind.AXIS_LOCK -> "Straight"
    SnapKind.NONE -> null
}

/** Live size readout: a line's length (exact when horizontal or vertical), or a rectangle's width by height. */
fun readoutOf(shape: SketchPrimitive): String = when (shape) {
    is SketchPrimitive.Line -> {
        val dx = shape.b.x.sixteenths - shape.a.x.sixteenths
        val dy = shape.b.y.sixteenths - shape.a.y.sixteenths
        when {
            dy == 0L -> lengthTextOf(dx)
            dx == 0L -> lengthTextOf(dy)
            else -> "${((hypot(dx.toDouble(), dy.toDouble()) / Inches.SIXTEENTHS_PER_INCH * 10).roundToLong() / 10.0)}\""
        }
    }
    is SketchPrimitive.Rect -> "${lengthTextOf(shape.b.x.sixteenths - shape.a.x.sixteenths)} x ${lengthTextOf(shape.b.y.sixteenths - shape.a.y.sixteenths)}"
}

/** A drag draws something real only when the shape has extent. */
fun DragPreview.hasExtent(): Boolean = shape.vertices.distinct().size > 1
