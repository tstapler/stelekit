package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import androidx.compose.foundation.Canvas

/** Minimum separation (in pixels) before label collision avoidance offsets a label. */
private const val COLLISION_THRESHOLD_PX = 64f

/**
 * Canvas overlay that renders text measurement labels with leader-line callouts at
 * the midpoint of each annotation.
 *
 * Labels are positioned at the annotation midpoint offset by a fixed vector, with basic
 * collision avoidance: if two labels overlap within [COLLISION_THRESHOLD_PX], the later
 * label is shifted along the perpendicular axis.
 *
 * Uses Compose [TextMeasurer] (CMP 1.6+) for cross-platform text metrics.
 */
@Composable
fun MeasurementLabelOverlay(
    annotations: List<MeasurementAnnotation>,
    canvasSize: IntSize,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // Retain leader-line path to avoid per-frame allocation
    val leaderPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (canvasSize == IntSize.Zero) return@Canvas

        val placedLabelCenters = mutableListOf<Offset>()

        annotations.forEach { annotation ->
            val label = annotation.valueDisplay ?: annotation.label ?: return@forEach
            if (label.isBlank()) return@forEach

            val screenPoints = annotation.normalizedPoints.map { it.toScreen(canvasSize) }
            if (screenPoints.isEmpty()) return@forEach

            val anchorPoint = annotationMidpoint(screenPoints, annotation.annotationType)
            val labelOffset = Offset(0f, -40.dp.toPx())
            var labelCenter = anchorPoint + labelOffset

            // Collision avoidance: shift along Y axis if too close to an existing label
            for (existing in placedLabelCenters) {
                val dx = labelCenter.x - existing.x
                val dy = labelCenter.y - existing.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < COLLISION_THRESHOLD_PX) {
                    labelCenter = labelCenter.copy(y = labelCenter.y - COLLISION_THRESHOLD_PX)
                }
            }
            placedLabelCenters += labelCenter

            // Measure text
            val labelStyle = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val measured = textMeasurer.measure(label, labelStyle)
            val textW = measured.size.width.toFloat()
            val textH = measured.size.height.toFloat()
            val padding = 4.dp.toPx()

            val bgRect = androidx.compose.ui.geometry.Rect(
                left = labelCenter.x - textW / 2 - padding,
                top = labelCenter.y - textH / 2 - padding,
                right = labelCenter.x + textW / 2 + padding,
                bottom = labelCenter.y + textH / 2 + padding,
            )

            // Draw background chip
            drawRoundRect(
                color = Color(0xCC000000),
                topLeft = Offset(bgRect.left, bgRect.top),
                size = androidx.compose.ui.geometry.Size(bgRect.width, bgRect.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )

            // Draw leader line from anchor to label bottom
            leaderPath.reset()
            leaderPath.moveTo(anchorPoint.x, anchorPoint.y)
            leaderPath.lineTo(labelCenter.x, bgRect.bottom)
            drawPath(
                leaderPath,
                color = Color(0xAAFFFFFF),
                style = Stroke(width = 1.dp.toPx()),
            )

            // Draw text
            drawText(
                textMeasurer = textMeasurer,
                text = label,
                topLeft = Offset(
                    bgRect.left + padding,
                    bgRect.top + padding,
                ),
                style = labelStyle,
            )
        }
    }
}

/**
 * Compute the screen-space midpoint for placing a label callout.
 *
 * - DISTANCE / GRID_REF: midpoint of the two endpoints
 * - AREA: centroid of the polygon
 * - ANGLE: vertex point (index 1)
 * - LABEL: the anchor point
 */
private fun annotationMidpoint(
    screenPoints: List<Offset>,
    type: AnnotationType,
): Offset {
    if (screenPoints.isEmpty()) return Offset.Zero
    return when (type) {
        AnnotationType.DISTANCE, AnnotationType.GRID_REF -> {
            if (screenPoints.size >= 2) {
                Offset(
                    (screenPoints[0].x + screenPoints[1].x) / 2,
                    (screenPoints[0].y + screenPoints[1].y) / 2,
                )
            } else screenPoints[0]
        }

        AnnotationType.AREA -> {
            val cx = screenPoints.map { it.x }.average().toFloat()
            val cy = screenPoints.map { it.y }.average().toFloat()
            Offset(cx, cy)
        }

        AnnotationType.ANGLE -> {
            // Label at the vertex
            if (screenPoints.size >= 2) screenPoints[1] else screenPoints[0]
        }

        AnnotationType.LABEL -> screenPoints[0]
    }
}
