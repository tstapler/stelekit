package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.ScreenPoint
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.ViewTransform
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.ui.placement.PlacementController
import dev.stapler.stelekit.ui.placement.PlacementSpace
import dev.stapler.stelekit.ui.placement.PlacementState
import dev.stapler.stelekit.ui.placement.PxOffset
import dev.stapler.stelekit.ui.placement.crosshairPoint
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** The sheet region the canvas currently shows, as the [PlacementSpace] of the shared placement controller. */
class SketchPlacementSpace(
    private val view: ViewTransform,
    override val canvasWidthPx: Int,
    private val canvasHeightPx: Int,
    private val density: Float,
) : PlacementSpace<SketchPoint> {
    private val topLeft = sheetAt(0, 0)
    private val bottomRight = sheetAt(canvasWidthPx, canvasHeightPx)

    private fun sheetAt(xPx: Int, yPx: Int): SketchPoint =
        view.toSheet(ScreenPoint(pxToDp(xPx.toFloat(), density), pxToDp(yPx.toFloat(), density))).getOrNull() ?: SketchPoint(Inches(0), Inches(0))

    private val minX = min(topLeft.x.sixteenths, bottomRight.x.sixteenths)
    private val maxX = max(topLeft.x.sixteenths, bottomRight.x.sixteenths)
    private val minY = min(topLeft.y.sixteenths, bottomRight.y.sixteenths)
    private val maxY = max(topLeft.y.sixteenths, bottomRight.y.sixteenths)

    override fun center(): SketchPoint = clamp(sheetAt(canvasWidthPx / 2, canvasHeightPx / 2))

    override fun contains(point: SketchPoint): Boolean =
        point.x.sixteenths in minX..maxX && point.y.sixteenths in minY..maxY

    override fun clamp(point: SketchPoint): SketchPoint =
        SketchPoint(Inches(point.x.sixteenths.coerceIn(minX, maxX)), Inches(point.y.sixteenths.coerceIn(minY, maxY)))

    override fun shift(point: SketchPoint, offset: PxOffset): SketchPoint {
        val perPx = sixteenthsPerPx(density, view.dpPerInch)
        return SketchPoint(
            Inches(point.x.sixteenths + sixteenthsFor(offset.dx, perPx)),
            Inches(point.y.sixteenths + sixteenthsFor(offset.dy, perPx)),
        )
    }
}

private const val SIXTEENTHS_PER_INCH_D = 16.0

private fun sixteenthsPerPx(density: Float, dpPerInch: Rational): Double =
    SIXTEENTHS_PER_INCH_D / (density.toDouble() * dpPerInch.toFloat().toDouble())

/** [px] canvas pixels in sheet sixteenths; a non-zero [px] always moves by at least one sixteenth. */
fun sixteenthsFor(px: Int, sixteenthsPerPx: Double): Long {
    if (px == 0) return 0L
    val exact = (px * sixteenthsPerPx).roundToLong()
    return if (exact != 0L) exact else if (px > 0) 1L else -1L
}

/** What a confirmed crosshair commits. */
sealed interface PlacementCommit {
    data class Place(val point: SketchPoint) : PlacementCommit
    data class Move(val vertex: VertexRef, val point: SketchPoint) : PlacementCommit
}

/** Non-drag mode state: the shared controller's state plus the vertex being moved, if any. */
data class SketchPlacementSession(
    val state: PlacementState<SketchPoint> = PlacementState.Idle,
    val moving: VertexRef? = null,
) {
    val active: Boolean get() = state != PlacementState.Idle
}

private fun SketchPlacementSession.on(space: PlacementSpace<SketchPoint>) = PlacementController(space, state)

fun SketchPlacementSession.toggled(space: PlacementSpace<SketchPoint>): SketchPlacementSession =
    if (active) SketchPlacementSession() else SketchPlacementSession(on(space).begin().state)

/** TalkBack "Move vertex N": the crosshair jumps to the vertex and the next confirm moves it. */
fun SketchPlacementSession.startMove(space: PlacementSpace<SketchPoint>, vertex: VertexRef, at: SketchPoint): SketchPlacementSession =
    SketchPlacementSession(on(space).begin(at).state, vertex)

fun SketchPlacementSession.nudged(space: PlacementSpace<SketchPoint>, direction: NudgeDirection, step: NudgeStep): SketchPlacementSession =
    copy(state = on(space).nudge(direction, step).state)

/** A canvas tap in this mode only moves the crosshair. */
fun SketchPlacementSession.tapped(space: PlacementSpace<SketchPoint>, point: SketchPoint): SketchPlacementSession =
    copy(state = on(space).moveTo(point).state)

fun SketchPlacementSession.respaced(space: PlacementSpace<SketchPoint>): SketchPlacementSession =
    copy(state = PlacementController(space, state).withSpace(space).state)

/** Places at (or moves a vertex to) the crosshair. A placement keeps the crosshair for the next point; a move ends the mode. */
fun SketchPlacementSession.confirmed(space: PlacementSpace<SketchPoint>): Pair<SketchPlacementSession, PlacementCommit?> {
    val placed = on(space).confirm().state as? PlacementState.Placed ?: return this to null
    val target = moving
    return if (target != null) SketchPlacementSession() to PlacementCommit.Move(target, placed.point)
    else copy(state = placed) to PlacementCommit.Place(placed.point)
}

fun SketchViewModel.applyCommit(commit: PlacementCommit) = when (commit) {
    is PlacementCommit.Place -> placePoint(commit.point)
    is PlacementCommit.Move -> moveVertex(commit.vertex, commit.point)
}

private val PAD_CELL = 56.dp
private val MIC_CLEARANCE = 72.dp

/**
 * The sketch canvas plus the non-drag placement mode (Story 3.6): a crosshair moved by nudge
 * buttons that places only on "Place point". Every placement runs through [PlacementController].
 */
@Composable
fun SketchNonDragMode(state: SketchUiState, viewModel: SketchViewModel, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf(initialSketchView()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var session by remember { mutableStateOf(SketchPlacementSession()) }
    var step by remember { mutableStateOf(NudgeStep.TEN_PIXELS) }
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val space = SketchPlacementSpace(view, size.width, size.height, density)
    Box(modifier) {
        SketchCanvas(
            state = state,
            onPlace = viewModel::placePoint,
            onSelect = viewModel::select,
            modifier = Modifier.sketchSemantics(state) { ref ->
                state.document.scene.vertexAt(ref)?.let { session = session.startMove(space, ref, it) }
            },
            onViewChange = { view = it; session = session.respaced(SketchPlacementSpace(it, size.width, size.height, density)) },
            crosshair = session.state.crosshairPoint,
            onCrosshairTap = if (session.active) { point -> session = session.tapped(space, point) } else null,
            onSizeChange = { size = it },
            onCommitDrag = { intent, preview -> viewModel.commitDrag(intent, preview) },
        )
        // The shell's docked mic button rises above the bottom bar at the centre, so controls sit at the end
        // edge, and the open pad clears the mic's height.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = if (session.active) MIC_CLEARANCE else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (session.active) {
                NudgePad(
                    step = step,
                    isMove = session.moving != null,
                    onStep = { step = it },
                    onNudge = { session = session.nudged(space, it, step) },
                    onConfirm = {
                        val (next, commit) = session.confirmed(space)
                        session = next
                        commit?.let {
                            viewModel.applyCommit(it)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onCancel = { session = SketchPlacementSession() },
                )
            }
            FilterChip(
                selected = session.active,
                onClick = { session = session.toggled(space) },
                label = { Text("Crosshair") },
                modifier = Modifier.semantics { contentDescription = "Place at crosshair mode" },
            )
        }
    }
}

@Composable
private fun NudgePad(
    step: NudgeStep,
    isMove: Boolean,
    onStep: (NudgeStep) -> Unit,
    onNudge: (NudgeDirection) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NudgeStep.entries.forEach { FilterChip(selected = step == it, onClick = { onStep(it) }, label = { Text(it.label()) }) }
        }
        NudgeButton(NudgeDirection.UP, step, onNudge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeButton(NudgeDirection.LEFT, step, onNudge)
            Button(onClick = onConfirm, modifier = Modifier.size(width = PAD_CELL * 2, height = PAD_CELL)) { Text(if (isMove) "Apply" else "Place point") }
            NudgeButton(NudgeDirection.RIGHT, step, onNudge)
        }
        NudgeButton(NudgeDirection.DOWN, step, onNudge)
        TextButton(onClick = onCancel, modifier = Modifier.size(width = PAD_CELL * 2, height = PAD_CELL)) { Text(if (isMove) "Cancel move" else "Cancel") }
    }
}

@Composable
private fun NudgeButton(direction: NudgeDirection, step: NudgeStep, onNudge: (NudgeDirection) -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = { onNudge(direction) },
        modifier = modifier.size(PAD_CELL).semantics { contentDescription = nudgeDescription(direction, step) },
    ) { Text(direction.arrow()) }
}

private fun NudgeDirection.arrow(): String = when (this) {
    NudgeDirection.LEFT -> "<"
    NudgeDirection.RIGHT -> ">"
    NudgeDirection.UP -> "^"
    NudgeDirection.DOWN -> "v"
}
