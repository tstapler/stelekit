package dev.stapler.stelekit.ui.placement

import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.annotate.AnnotationPlacementSpace

/** (A NormalizedPoint cannot be out of range, so the "outside" probe is the corner.) The annotate editor's normalised-image space must satisfy the same contract as the sketch's. */
class AnnotationPlacementControllerContractTest : PlacementControllerContractSuite<NormalizedPoint>() {
    override fun space(): PlacementSpace<NormalizedPoint> = AnnotationPlacementSpace(800, 600)

    override fun interiorPoint() = NormalizedPoint(0.25, 0.75)

    override fun outsidePoint() = NormalizedPoint(1.0, 0.0)
}
