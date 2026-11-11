package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.graphics.Path
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter

/** One primitive ready to draw: outline in sheet inches, plus an optional length label anchored at its midpoint. */
class CachedShape(val id: String, val path: Path, val label: String?, val labelX: Float, val labelY: Float)

/**
 * Sheet-space [Path]s and label strings, rebuilt only when the scene instance changes. Pan and
 * zoom never invalidate it because the canvas applies the view as a draw transform.
 */
class SketchRenderCache {
    private var builtFor: SketchScene? = null
    private var shapes: List<CachedShape> = emptyList()

    fun shapesFor(scene: SketchScene): List<CachedShape> {
        if (builtFor !== scene) {
            shapes = scene.primitives.map(::build)
            builtFor = scene
        }
        return shapes
    }

    private fun build(p: SketchPrimitive): CachedShape {
        val path = Path()
        p.vertices.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(v.x.toInchesFloat(), v.y.toInchesFloat()) else path.lineTo(v.x.toInchesFloat(), v.y.toInchesFloat())
        }
        if (p is SketchPrimitive.Rect) path.close()
        return when (p) {
            is SketchPrimitive.Line -> {
                val dx = p.b.x.sixteenths - p.a.x.sixteenths
                val dy = p.b.y.sixteenths - p.a.y.sixteenths
                // Axis-aligned lines read as their length; a slanted line has no single typed length.
                val len = if (dy == 0L) dx else if (dx == 0L) dy else null
                val text = len?.let { LengthFormatter.format(dev.stapler.stelekit.units.Inches(kotlin.math.abs(it)), LengthFormat.FEET_INCHES) }
                CachedShape(p.id, path, text, (p.a.x.toInchesFloat() + p.b.x.toInchesFloat()) / 2, (p.a.y.toInchesFloat() + p.b.y.toInchesFloat()) / 2)
            }
            is SketchPrimitive.Rect -> CachedShape(p.id, path, null, 0f, 0f)
        }
    }
}
