package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.model.SketchPrimitive as StoredPrimitive
import dev.stapler.stelekit.units.Inches

// Public (was internal in ui.sketch) because the v2 converter in another module and the v1 view model both use them.

fun SketchSheet.toSheetProps(): SheetProps = SheetProps.fromStored(kind, viewDirection, scaleSixteenthsPerUnit, layers)

fun StoredPrimitive.toScenePrimitive(): SketchPrimitive? {
    val a = SketchPoint(Inches(x1), Inches(y1))
    val b = SketchPoint(Inches(x2), Inches(y2))
    return when (kind) {
        "line" -> SketchPrimitive.Line(uuid, a, b, layer)
        "rect" -> SketchPrimitive.Rect(uuid, a, b, layer)
        else -> null
    }
}
