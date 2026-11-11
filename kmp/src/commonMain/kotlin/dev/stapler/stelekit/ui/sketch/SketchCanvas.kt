package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import dev.stapler.stelekit.sketch.ScreenPoint
import dev.stapler.stelekit.sketch.ViewTransform
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.units.Rational

const val SKETCH_CANVAS_TAG = "sketch-canvas"

private const val STROKE_DP = 2f
private const val ANCHOR_RADIUS_DP = 4f
private const val SELECTED_STROKE_DP = 4f
private const val LABEL_SKIP_BELOW_DP_PER_INCH = 24f

/**
 * Sheet drawing surface: one `pointerInput` gesture machine. A single pointer that stays within
 * the touch slop is a tap (placement or selection by tool); beyond the slop it pans; two pointers
 * pan and zoom. The in-progress rubber band is read only inside the draw lambda, so moving the
 * pointer redraws without recomposing.
 */
@Composable
fun SketchCanvas(
    state: SketchUiState,
    onPlace: (SketchPoint) -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onViewChange: (ViewTransform) -> Unit = {},
    crosshair: SketchPoint? = null,
    onCrosshairTap: ((SketchPoint) -> Unit)? = null,
    onSizeChange: (IntSize) -> Unit = {},
) {
    var view by remember { mutableStateOf(initialSketchView()) }
    var hover by remember { mutableStateOf<SketchPoint?>(null) }
    val cache = remember { SketchRenderCache() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val slop = LocalViewConfiguration.current.touchSlop
    val currentState by rememberUpdatedState(state)
    val currentPlace by rememberUpdatedState(onPlace)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentViewChange by rememberUpdatedState(onViewChange)
    val currentCrosshairTap by rememberUpdatedState(onCrosshairTap)
    val currentSizeChange by rememberUpdatedState(onSizeChange)
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = ink)

    fun setView(next: ViewTransform) {
        view = next
        currentViewChange(next)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag(SKETCH_CANVAS_TAG)
            .onSizeChanged { currentSizeChange(it) }
            .pointerInput(density, slop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val classifier = TapOrDragClassifier(slop)
                    var transforming = false
                    fun screenOf(o: Offset) = ScreenPoint(pxToDp(o.x, density), pxToDp(o.y, density))
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            transforming = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            setView(applyPinch(view, pan.x, pan.y, zoom, centroid.x, centroid.y, density))
                            event.changes.forEach { it.consume() }
                        } else if (!transforming) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            val moved = change.position - down.position
                            if (classifier.update(moved.x, moved.y)) {
                                val delta = change.positionChange()
                                setView(applyDrag(view, delta.x, delta.y, density))
                                hover = null
                                change.consume()
                            } else {
                                hover = resolveSnap(currentState, view, screenOf(change.position))
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    hover = null
                    val crosshairTap = currentCrosshairTap
                    if (!transforming && !classifier.isDrag && crosshairTap != null) {
                        view.toSheet(screenOf(down.position)).onRight(crosshairTap)
                    } else if (!transforming && !classifier.isDrag) {
                        val s = currentState
                        resolveTap(s.tool, s.document.visibleScene, view, screenOf(down.position), s.document.sheet.gridStep, s.pendingStart).onRight { action ->
                            when (action) {
                                is TapAction.Place -> currentPlace(action.point)
                                is TapAction.Select -> currentSelect(action.id)
                            }
                        }
                    }
                }
            },
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val v = view
        val frame = drawFrameOf(v, density)
        val scale = frame.scale
        val strokeWidth = frame.strokeInSheetUnits(STROKE_DP, density)
        val shapes = cache.shapesFor(state.document.visibleScene)
        withTransform({
            translate(frame.originX, frame.originY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            for (shape in shapes) {
                val selected = shape.id == state.selectedId
                drawPath(shape.path, if (selected) accent else ink, style = Stroke(if (selected) frame.strokeInSheetUnits(SELECTED_STROKE_DP, density) else strokeWidth))
            }
            state.pendingStart?.let { start ->
                val a = Offset(start.x.toInchesFloat(), start.y.toInchesFloat())
                drawCircle(accent, radius = frame.strokeInSheetUnits(ANCHOR_RADIUS_DP, density), center = a)
                hover?.let { h -> drawLine(accent, a, Offset(h.x.toInchesFloat(), h.y.toInchesFloat()), strokeWidth) }
            }
        }
        crosshair?.let { drawCrosshair(it, frame, accent, density) }
        if (v.dpPerInch.toFloat() >= LABEL_SKIP_BELOW_DP_PER_INCH) {
            drawLabels(shapes, frame, measurer, labelStyle)
        }
    }
}

fun initialSketchView(): ViewTransform = ViewTransform.of(origin = ScreenPoint(Rational(16), Rational(16)))

private const val CROSSHAIR_ARM_DP = 14f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrosshair(at: SketchPoint, frame: DrawFrame, color: androidx.compose.ui.graphics.Color, density: Float) {
    val (x, y) = frame.px(at.x.toInchesFloat(), at.y.toInchesFloat())
    val arm = CROSSHAIR_ARM_DP * density
    val width = STROKE_DP * density
    drawLine(color, Offset(x - arm, y), Offset(x + arm, y), width)
    drawLine(color, Offset(x, y - arm), Offset(x, y + arm), width)
}

private fun resolveSnap(state: SketchUiState, view: ViewTransform, at: ScreenPoint): SketchPoint? =
    if (state.tool == SketchTool.ZONE_SELECT) null
    else resolveTap(state.tool, state.document.visibleScene, view, at, state.document.sheet.gridStep, state.pendingStart).getOrNull().let { (it as? TapAction.Place)?.point }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabels(
    shapes: List<CachedShape>,
    frame: DrawFrame,
    measurer: TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
) {
    for (shape in shapes) {
        val text = shape.label ?: continue
        val (x, y) = frame.px(shape.labelX, shape.labelY)
        drawText(measurer, text, Offset(x, y), style)
    }
}
