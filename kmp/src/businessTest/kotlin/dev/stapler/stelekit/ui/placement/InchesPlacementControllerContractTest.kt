package dev.stapler.stelekit.ui.placement

import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.units.Inches

/** Test consumer: sheet-inch points on a canvas where one pixel is one sixteenth. */
class InchesTestSpace : PlacementSpace<SketchPoint> {
    override val canvasWidthPx = 800
    private val heightPx = 600L

    override fun center() = SketchPoint(Inches(400), Inches(300))

    override fun contains(point: SketchPoint) = point.x.sixteenths in 0..800L && point.y.sixteenths in 0..heightPx

    override fun clamp(point: SketchPoint) =
        SketchPoint(Inches(point.x.sixteenths.coerceIn(0, 800)), Inches(point.y.sixteenths.coerceIn(0, heightPx)))

    override fun shift(point: SketchPoint, offset: PxOffset) =
        SketchPoint(Inches(point.x.sixteenths + offset.dx), Inches(point.y.sixteenths + offset.dy))
}

class InchesPlacementControllerContractTest : PlacementControllerContractTest<SketchPoint>() {
    override fun space(): PlacementSpace<SketchPoint> = InchesTestSpace()

    override fun interiorPoint() = SketchPoint(Inches(120), Inches(90))

    override fun outsidePoint() = SketchPoint(Inches(-50), Inches(9_000))
}
