// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.ui.placement.PlacementController
import dev.stapler.stelekit.ui.placement.PlacementSpace
import dev.stapler.stelekit.ui.placement.PlacementState
import dev.stapler.stelekit.ui.placement.PxOffset
import kotlin.math.roundToInt

/**
 * The annotated image as the [PlacementSpace] of the shared placement controller: normalised
 * [0,1] points over a canvas of the given pixel size. Nudged points snap to the pixel grid so
 * opposite nudges reverse exactly. A [NormalizedPoint] cannot leave [0,1], so shifts clamp at the edge.
 *
 * When the view is zoomed by [zoom], nudge pixels are screen pixels: the grid is the zoomed canvas, so a
 * 1 px nudge at 8x moves 1/8 of an image pixel. Percent steps are a percent of the visible width,
 * [visibleWidthPx] (the viewport, if known) capped at the zoomed canvas width.
 */
class AnnotationPlacementSpace(
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    zoom: Float = 1f,
    visibleWidthPx: Int? = null,
) : PlacementSpace<NormalizedPoint> {
    private val extentXPx: Int = (canvasWidthPx * zoom).roundToInt().coerceAtLeast(1)
    private val heightPx: Int = (canvasHeightPx * zoom).roundToInt().coerceAtLeast(1)
    override val canvasWidthPx: Int = (visibleWidthPx?.let { minOf(it, extentXPx) } ?: extentXPx).coerceAtLeast(1)

    override fun center(): NormalizedPoint = NormalizedPoint(HALF, HALF)

    override fun contains(point: NormalizedPoint): Boolean = point.x in 0.0..1.0 && point.y in 0.0..1.0

    override fun clamp(point: NormalizedPoint): NormalizedPoint =
        NormalizedPoint(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0))

    override fun shift(point: NormalizedPoint, offset: PxOffset): NormalizedPoint =
        NormalizedPoint(
            shifted(point.x, offset.dx, extentXPx),
            shifted(point.y, offset.dy, heightPx),
        )

    private fun shifted(value: Double, deltaPx: Int, extentPx: Int): Double =
        (((value * extentPx).roundToInt() + deltaPx).toDouble() / extentPx).coerceIn(0.0, 1.0)

    private companion object {
        const val HALF = 0.5
    }
}

/** An existing annotation vertex being moved by the crosshair (TalkBack "Move vertex N"). */
data class VertexTarget(val annotationUuid: String, val index: Int)

/** What a confirmed crosshair commits. */
sealed interface AnnotationPlacementCommit {
    data class Place(val point: NormalizedPoint) : AnnotationPlacementCommit
    data class Move(val target: VertexTarget, val point: NormalizedPoint) : AnnotationPlacementCommit
}

/** Non-drag mode state: the shared controller's state plus the vertex being moved, if any. */
data class AnnotationPlacementSession(
    val state: PlacementState<NormalizedPoint> = PlacementState.Idle,
    val moving: VertexTarget? = null,
) {
    val active: Boolean get() = state != PlacementState.Idle
}

private fun AnnotationPlacementSession.on(space: PlacementSpace<NormalizedPoint>) = PlacementController(space, state)

fun AnnotationPlacementSession.toggled(space: PlacementSpace<NormalizedPoint>): AnnotationPlacementSession =
    if (active) AnnotationPlacementSession() else AnnotationPlacementSession(on(space).begin().state)

/** The crosshair jumps to [at] (a vertex) and the next confirm moves that vertex. */
fun AnnotationPlacementSession.startMove(
    space: PlacementSpace<NormalizedPoint>,
    target: VertexTarget,
    at: NormalizedPoint,
): AnnotationPlacementSession = AnnotationPlacementSession(on(space).begin(at).state, target)

fun AnnotationPlacementSession.nudged(
    space: PlacementSpace<NormalizedPoint>,
    direction: NudgeDirection,
    step: NudgeStep,
): AnnotationPlacementSession = copy(state = on(space).nudge(direction, step).state)

/** A canvas tap or release in this mode only moves the crosshair; it never places. */
fun AnnotationPlacementSession.tapped(space: PlacementSpace<NormalizedPoint>, point: NormalizedPoint): AnnotationPlacementSession =
    copy(state = on(space).moveTo(point).state)

fun AnnotationPlacementSession.respaced(space: PlacementSpace<NormalizedPoint>): AnnotationPlacementSession =
    copy(state = on(space).withSpace(space).state)

/** Places at (or moves a vertex to) the crosshair. A placement keeps the crosshair for the next point; a move ends the mode. */
fun AnnotationPlacementSession.confirmed(
    space: PlacementSpace<NormalizedPoint>,
): Pair<AnnotationPlacementSession, AnnotationPlacementCommit?> {
    val placed = on(space).confirm().state as? PlacementState.Placed ?: return this to null
    val target = moving
    return if (target != null) AnnotationPlacementSession() to AnnotationPlacementCommit.Move(target, placed.point)
    else copy(state = placed) to AnnotationPlacementCommit.Place(placed.point)
}

/** [points] with the vertex at [index] replaced; unchanged if [index] is out of range. */
fun replaceVertex(points: List<NormalizedPoint>, index: Int, with: NormalizedPoint): List<NormalizedPoint> =
    if (index in points.indices) points.toMutableList().also { it[index] = with } else points

// ── ViewModel entry points (extensions keep the hotspot class small) ──────────

fun AnnotationEditorViewModel.togglePlacementMode(space: PlacementSpace<NormalizedPoint>) =
    updatePlacement { it.toggled(space) }

fun AnnotationEditorViewModel.nudgeCrosshair(space: PlacementSpace<NormalizedPoint>, direction: NudgeDirection, step: NudgeStep) =
    updatePlacement { it.nudged(space, direction, step) }

fun AnnotationEditorViewModel.tapCrosshair(space: PlacementSpace<NormalizedPoint>, point: NormalizedPoint) =
    updatePlacement { it.tapped(space, point) }

fun AnnotationEditorViewModel.respacePlacement(space: PlacementSpace<NormalizedPoint>) =
    updatePlacement { it.respaced(space) }

fun AnnotationEditorViewModel.cancelPlacement() = updatePlacement { AnnotationPlacementSession() }

/** TalkBack "Move vertex N": starts a move of [vertexIndex] of [annotationUuid]; false if it does not exist. */
fun AnnotationEditorViewModel.beginVertexMove(space: PlacementSpace<NormalizedPoint>, annotationUuid: String, vertexIndex: Int): Boolean {
    val vertex = state.value.committedAnnotations.firstOrNull { it.uuid == annotationUuid }
        ?.normalizedPoints?.getOrNull(vertexIndex) ?: return false
    updatePlacement { it.startMove(space, VertexTarget(annotationUuid, vertexIndex), vertex) }
    return true
}

/** Commits the crosshair as a new point or a vertex move; returns whether anything was committed. */
fun AnnotationEditorViewModel.confirmCrosshair(space: PlacementSpace<NormalizedPoint>): Boolean {
    val (next, commit) = state.value.placement.confirmed(space)
    updatePlacement { next }
    when (commit) {
        is AnnotationPlacementCommit.Place -> addPoint(commit.point)
        is AnnotationPlacementCommit.Move -> moveVertex(commit.target, commit.point)
        null -> Unit
    }
    return commit != null
}

private fun AnnotationEditorViewModel.moveVertex(target: VertexTarget, point: NormalizedPoint) {
    val original = state.value.committedAnnotations.firstOrNull { it.uuid == target.annotationUuid } ?: return
    beginAnnotationEdit()
    updateAnnotationPoints(target.annotationUuid, replaceVertex(original.normalizedPoints, target.index, point))
    commitAnnotationEdit(target.annotationUuid)
}
