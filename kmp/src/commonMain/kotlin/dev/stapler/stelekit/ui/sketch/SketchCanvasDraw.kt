package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SnapKind

/** Draws one text label; the canvas supplies a text-measurer-backed one, tests a recording one. */
internal typealias LabelPainter = (text: String, at: Offset, color: Color, background: Color) -> Unit

private const val HANDLE_RING_DP = 2f
private const val SNAP_RING_RADIUS_DP = 14f
private const val GUIDE_DASH_DP = 8f
private const val GUIDE_GAP_DP = 6f
private const val LABEL_OFFSET_X_DP = 18f
private const val LABEL_OFFSET_Y_DP = 6f
private const val READOUT_OFFSET_X_DP = 24f
private const val READOUT_OFFSET_Y_DP = 52f
private const val READOUT_BACKGROUND_ALPHA = 0.85f

/** The in-flight shape, in sheet-inch units (call inside the sheet transform). */
internal fun DrawScope.drawPreviewShape(shape: SketchPrimitive, color: Color, strokeInSheetUnits: Float) {
    val path = Path()
    shape.segments.forEachIndexed { i, seg ->
        if (i == 0) path.moveTo(seg.a.x.toInchesFloat(), seg.a.y.toInchesFloat())
        path.lineTo(seg.b.x.toInchesFloat(), seg.b.y.toInchesFloat())
    }
    drawPath(path, color, style = Stroke(strokeInSheetUnits))
}

internal fun DrawScope.drawHandle(at: SketchPoint, frame: DrawFrame, color: Color, density: Float) {
    val (x, y) = frame.px(at.x.toInchesFloat(), at.y.toInchesFloat())
    val c = Offset(x, y)
    drawCircle(Color.White, HANDLE_RADIUS_DP * density, c)
    drawCircle(color, HANDLE_RADIUS_DP * density, c, style = Stroke(HANDLE_RING_DP * density))
}

/** Ring and name at the snapped point, a guide back to the anchor on a straight lock, and the live size above the finger. */
internal fun DrawScope.drawSnapFeedback(drag: DragPreview, frame: DrawFrame, accent: Color, ink: Color, density: Float, label: LabelPainter) {
    val (x, y) = frame.px(drag.point.x.toInchesFloat(), drag.point.y.toInchesFloat())
    val at = Offset(x, y)
    drag.axisAnchor?.takeIf { drag.snap == SnapKind.AXIS_LOCK }?.let { a ->
        val (ax, ay) = frame.px(a.x.toInchesFloat(), a.y.toInchesFloat())
        drawLine(accent, Offset(ax, ay), at, density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_DASH_DP * density, GUIDE_GAP_DP * density)))
    }
    drag.snap.indicatorLabel()?.let { name ->
        drawCircle(accent, SNAP_RING_RADIUS_DP * density, at, style = Stroke(HANDLE_RING_DP * density))
        label(name, Offset(x + LABEL_OFFSET_X_DP * density, y + LABEL_OFFSET_Y_DP * density), accent, Color.Unspecified)
    }
    label(readoutOf(drag.shape), Offset(x - READOUT_OFFSET_X_DP * density, y - READOUT_OFFSET_Y_DP * density), ink, Color.White.copy(alpha = READOUT_BACKGROUND_ALPHA))
}
