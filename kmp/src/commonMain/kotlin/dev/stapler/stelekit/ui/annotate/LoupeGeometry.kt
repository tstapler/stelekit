package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Offset of the zoomed inner image inside the loupe so that canvas point [touch] lands on the
 * loupe's centre (the crosshair). The inner image is `canvasSize * zoomFactor` and laid out with
 * the same Fit scaling as the main image, so canvas point `p` appears at `p * zoomFactor`.
 * Rounded (not truncated) so the error is at most half a pixel per axis.
 */
internal fun loupeInnerOffset(loupeSizePx: Float, touch: Offset, zoomFactor: Float): IntOffset =
    IntOffset(
        (loupeSizePx / 2f - touch.x * zoomFactor).roundToInt(),
        (loupeSizePx / 2f - touch.y * zoomFactor).roundToInt(),
    )
