package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.hypot

/** Travel (dp) between press and release beyond which a DISTANCE press is a drag-measure (ADR-007). */
const val DRAG_COMMIT_DP: Int = 48

/** Distance (image px) below which two points count as identical. */
const val IDENTICAL_POINT_IMAGE_PX: Float = 2f

/** One rule: an off-canvas or system-gesture-inset release cancels but keeps the tool armed (OC-9). */
@Suppress("TopLevelPropertyNaming") // lowerCamelCase name is the ADR-007 / OC-9 flag the plan and tests cite
const val disarmOnOffCanvas: Boolean = false

private const val SCALE_EPSILON: Float = 1e-4f

sealed interface PlacementGesture {
    data class AimPlace(val point: Offset) : PlacementGesture
    data class DragMeasure(val start: Offset, val end: Offset) : PlacementGesture
}

/** True when [viewScale] is at or above Fit; tolerant of float rounding so Fit itself counts. */
fun isFitOrGreater(viewScale: Float, fitScale: Float = FIT_SCALE): Boolean =
    viewScale >= fitScale - SCALE_EPSILON

/**
 * Classifies a press/release pair. DragMeasure only for DISTANCE with no pending point, at or above
 * Fit, and release strictly farther than [thresholdPx] from the press; everything else aim-places
 * a single point at the release position.
 */
fun classifyPlacement(
    pressPx: Offset,
    releasePx: Offset,
    thresholdPx: Float,
    pendingPoints: Int,
    tool: AnnotationTool,
    viewScale: Float,
    fitScale: Float = FIT_SCALE,
): PlacementGesture {
    val travel = hypot(releasePx.x - pressPx.x, releasePx.y - pressPx.y)
    val dragEligible = tool == AnnotationTool.DISTANCE &&
        pendingPoints == 0 &&
        isFitOrGreater(viewScale, fitScale)
    return if (dragEligible && travel > thresholdPx) {
        PlacementGesture.DragMeasure(pressPx, releasePx)
    } else {
        PlacementGesture.AimPlace(releasePx)
    }
}

/** Identical points: closer than [minImagePx] image pixels (both points in image px). */
fun arePointsIdentical(a: Offset, b: Offset, minImagePx: Float = IDENTICAL_POINT_IMAGE_PX): Boolean =
    hypot(a.x - b.x, a.y - b.y) < minImagePx

/**
 * True when a release lies outside the canvas viewport shrunk by [insetPx] on every side (the
 * system-gesture inset; zero on desktop and three-button navigation).
 */
fun isOffCanvasRelease(release: Offset, canvas: Size, insetPx: Float = 0f): Boolean {
    val inset = abs(insetPx)
    return release.x < inset || release.y < inset ||
        release.x > canvas.width - inset || release.y > canvas.height - inset
}
