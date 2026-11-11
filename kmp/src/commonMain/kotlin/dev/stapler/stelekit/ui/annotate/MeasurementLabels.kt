package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.metersToDisplayString

/** Re-labels a derived length/area from its stored metres; angle and label values are unit-free. */
internal fun MeasurementAnnotation.relabeled(unit: MeasurementUnit): MeasurementAnnotation {
    val meters = valueMeters ?: return this
    return when (annotationType) {
        AnnotationType.DISTANCE, AnnotationType.GRID_REF -> copy(valueDisplay = metersToDisplayString(meters, unit))
        AnnotationType.AREA -> copy(valueDisplay = "${metersToDisplayString(meters, unit)}²")
        AnnotationType.ANGLE, AnnotationType.LABEL -> this
    }
}
