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

/**
 * Clamps [value] into `[margin, extent - loupeSize - margin]` without throwing. `coerceIn` throws
 * when that range is empty (canvas narrower than loupe plus margins, e.g. a panorama); in that
 * case the loupe is centred in the canvas and may overhang evenly. Always finite for finite input.
 */
fun clampLoupeOffset(value: Float, extent: Float, loupeSize: Float, margin: Float): Float {
    val max = extent - loupeSize - margin
    if (max < margin) return (extent - loupeSize) / 2f
    return value.coerceIn(margin, max)
}
