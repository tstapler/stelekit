package dev.stapler.stelekit.ui.placement

import dev.stapler.stelekit.units.Inches

/** Test consumer with exact [Inches] points on a sheet whose origin is the top-left corner. */
data class InchesPoint(val x: Inches, val y: Inches)

class InchesSheetSpace(private val width: Inches, private val height: Inches) : PlacementSpace<InchesPoint> {
    override fun contains(point: InchesPoint) =
        point.x.sixteenths in 0..width.sixteenths && point.y.sixteenths in 0..height.sixteenths

    override fun clamp(point: InchesPoint) = InchesPoint(
        Inches(point.x.sixteenths.coerceIn(0, width.sixteenths)),
        Inches(point.y.sixteenths.coerceIn(0, height.sixteenths)),
    )

    override fun offset(point: InchesPoint, direction: NudgeDirection, step: NudgeStep) = InchesPoint(
        Inches(point.x.sixteenths + direction.dx * size(step, width.sixteenths)),
        Inches(point.y.sixteenths + direction.dy * size(step, height.sixteenths)),
    )

    private fun size(step: NudgeStep, extent: Long): Long = when (step) {
        NudgeStep.ONE_PIXEL -> 1L
        NudgeStep.TEN_PIXELS -> 10L
        NudgeStep.ONE_PERCENT -> maxOf(1L, extent / 100)
    }
}

/** Test consumer with plain integer pixel points, standing in for a normalised-coordinate consumer. */
data class PixelPoint(val x: Long, val y: Long)

class PixelCanvasSpace(private val width: Long, private val height: Long) : PlacementSpace<PixelPoint> {
    override fun contains(point: PixelPoint) = point.x in 0..width && point.y in 0..height

    override fun clamp(point: PixelPoint) = PixelPoint(point.x.coerceIn(0, width), point.y.coerceIn(0, height))

    override fun offset(point: PixelPoint, direction: NudgeDirection, step: NudgeStep) = PixelPoint(
        point.x + direction.dx * size(step, width),
        point.y + direction.dy * size(step, height),
    )

    private fun size(step: NudgeStep, extent: Long): Long = when (step) {
        NudgeStep.ONE_PIXEL -> 1L
        NudgeStep.TEN_PIXELS -> 10L
        NudgeStep.ONE_PERCENT -> maxOf(1L, extent / 100)
    }
}
