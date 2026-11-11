package dev.stapler.stelekit.ui.placement

import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.ui.sketch.SketchPlacementSpace
import dev.stapler.stelekit.ui.sketch.initialSketchView
import dev.stapler.stelekit.units.Inches

/** The real sketch consumer must satisfy the same contract as the test consumer. */
class SketchPlacementControllerContractTest : PlacementControllerContractSuite<SketchPoint>() {
    override fun space(): PlacementSpace<SketchPoint> = SketchPlacementSpace(initialSketchView(), 800, 600, 2f)

    override fun interiorPoint() = SketchPoint(Inches(40), Inches(30))

    override fun outsidePoint() = SketchPoint(Inches(-5_000), Inches(5_000))
}
