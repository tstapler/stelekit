// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import kotlin.math.roundToInt

/**
 * Semantic accessibility overlay for a canvas full of [MeasurementAnnotation]s.
 *
 * Renders a transparent [Box] that fills the canvas, then places one
 * [AnnotationSemanticNode] per annotation. Each node is sized and positioned to match
 * the annotation's bounding rectangle in screen pixels (with a minimum 44×44 dp touch
 * target) and exposes a TalkBack-readable content description + click action.
 *
 * This composable must be stacked on top of the [androidx.compose.foundation.Canvas]
 * that draws the annotations so that the semantic tree mirrors the visual layout.
 *
 * @param annotations All annotations currently visible on the canvas.
 * @param canvasSize The pixel size of the canvas as reported by [androidx.compose.ui.layout.onGloballyPositioned].
 * @param onAnnotationSelected Callback invoked with the annotation's UUID when selected.
 */
@Composable
fun AnnotationSemanticOverlay(
    annotations: List<MeasurementAnnotation>,
    canvasSize: IntSize,
    onAnnotationSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        for (annotation in annotations) {
            AnnotationSemanticNode(
                annotation = annotation,
                canvasSize = canvasSize,
                onSelect = { onAnnotationSelected(annotation.uuid) },
            )
        }
    }
}

/**
 * A single invisible semantic node representing one [MeasurementAnnotation].
 *
 * Positioned absolutely over the annotation's bounding rect in screen space.
 * Enforces a minimum 44×44 dp touch target as per Material / WCAG guidelines.
 */
@Composable
private fun AnnotationSemanticNode(
    annotation: MeasurementAnnotation,
    canvasSize: IntSize,
    onSelect: () -> Unit,
) {
    val density = LocalDensity.current
    val minTouchTargetPx = with(density) { 44.dp.toPx() }

    val points = annotation.normalizedPoints
    if (points.isEmpty()) return

    val minNormX = points.minOf { it.x }
    val minNormY = points.minOf { it.y }
    val maxNormX = points.maxOf { it.x }
    val maxNormY = points.maxOf { it.y }

    val rawX = minNormX * canvasSize.width
    val rawY = minNormY * canvasSize.height
    val rawW = (maxNormX - minNormX) * canvasSize.width
    val rawH = (maxNormY - minNormY) * canvasSize.height

    val effectiveW = maxOf(rawW, minTouchTargetPx.toDouble())
    val effectiveH = maxOf(rawH, minTouchTargetPx.toDouble())

    val widthDp: Dp = with(density) { effectiveW.toFloat().toDp() }
    val heightDp: Dp = with(density) { effectiveH.toFloat().toDp() }

    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(
                        x = rawX.roundToInt(),
                        y = rawY.roundToInt(),
                    )
                }
            }
            .size(width = widthDp, height = heightDp)
            .semantics {
                contentDescription = annotation.toAccessibleDescription()
                role = Role.Button
                onClick(label = "Select annotation") { onSelect(); true }
            },
    )
}

/**
 * Builds a human-readable TalkBack description for a [MeasurementAnnotation].
 *
 * Format examples:
 * - `"Distance measurement: door frame, 2.1 m"`
 * - `"Area measurement, 6.0 m²"`
 * - `"Angle measurement: corner, 90°"`
 * - `"Label: window sill"`
 */
private fun MeasurementAnnotation.toAccessibleDescription(): String {
    val labelPart = if (!label.isNullOrBlank()) ": $label" else ""
    val valuePart = if (valueDisplay != null) ", $valueDisplay" else ""
    return when (annotationType) {
        AnnotationType.DISTANCE,
        AnnotationType.GRID_REF -> "Distance measurement$labelPart$valuePart"
        AnnotationType.AREA -> "Area measurement$labelPart$valuePart"
        AnnotationType.ANGLE -> "Angle measurement$labelPart$valuePart"
        AnnotationType.LABEL -> "Label: ${label ?: "unlabeled"}"
    }
}
