package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.units.Inches

/** Inches-to-pixels mapping that centres a scene in a preview box: `px = origin + inches * scale`. */
data class PreviewFit(val originX: Float, val originY: Float, val scale: Float)

/** The box (in sheet sixteenths) around everything in [scene], or null for an empty scene. */
fun sceneBounds(scene: SketchScene): Aabb? {
    val boxes = scene.primitives.map { it.aabb }
    if (boxes.isEmpty()) return null
    return Aabb(boxes.minOf { it.minX }, boxes.minOf { it.minY }, boxes.maxOf { it.maxX }, boxes.maxOf { it.maxY })
}

private const val MIN_EXTENT_INCHES = 1f
private const val MIN_ASPECT = 0.4f
private const val MAX_ASPECT = 4f

private fun Aabb.widthInches() = maxOf((maxX - minX).toFloat() / Inches.SIXTEENTHS_PER_INCH, MIN_EXTENT_INCHES)
private fun Aabb.heightInches() = maxOf((maxY - minY).toFloat() / Inches.SIXTEENTHS_PER_INCH, MIN_EXTENT_INCHES)

/** Width over height for the preview box, clamped so a long thin line neither vanishes nor takes over the page. */
fun previewAspectRatio(bounds: Aabb?): Float =
    if (bounds == null) 2f else (bounds.widthInches() / bounds.heightInches()).coerceIn(MIN_ASPECT, MAX_ASPECT)

/**
 * Scales and centres [bounds] inside a [widthPx] by [heightPx] box leaving [paddingPx] on every side;
 * null when the box has no room left for drawing.
 */
fun fitPreview(bounds: Aabb, widthPx: Float, heightPx: Float, paddingPx: Float): PreviewFit? {
    val availW = widthPx - 2 * paddingPx
    val availH = heightPx - 2 * paddingPx
    if (availW <= 0f || availH <= 0f) return null
    val w = bounds.widthInches()
    val h = bounds.heightInches()
    val scale = minOf(availW / w, availH / h)
    val cx = (bounds.minX + bounds.maxX) / 2f / Inches.SIXTEENTHS_PER_INCH
    val cy = (bounds.minY + bounds.maxY) / 2f / Inches.SIXTEENTHS_PER_INCH
    return PreviewFit(widthPx / 2f - cx * scale, heightPx / 2f - cy * scale, scale)
}
