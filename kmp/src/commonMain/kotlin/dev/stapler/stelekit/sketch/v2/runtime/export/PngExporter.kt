// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.export

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.platform.PngEncoder
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExport
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExportOptions
import dev.stapler.stelekit.sketch.v2.annotation.ExportedDimension
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.CssColor
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * What to export. [scale] is the pixel density (1 to [MAX_SCALE]); [background] is a CSS colour or null for transparent;
 * [selection] limits the export to those elements; [bounds] overrides the content bounds (a frame crop, Story 5.2.3); [dimensions],
 * when set, draws the dimension annotations and grows the image to hold them.
 */
data class PngExportOptions(
    val scale: Int = 1,
    val background: String? = "#ffffff",
    val padding: Double = SvgExportOptions.DEFAULT_PADDING,
    val selection: Set<ElementId>? = null,
    val bounds: Bounds? = null,
    val dimensions: DimensionExportOptions? = null,
) {
    companion object {
        const val MAX_SCALE = 3
    }
}

/** The pixel size of an export. */
data class PixelSize(val widthPx: Long, val heightPx: Long)

sealed interface PngExportError {
    /** The image would exceed `SceneLimits.maxPngSidePx` on a side; nothing was allocated. The dialog offers 1x. */
    data class TooLarge(val widthPx: Long, val heightPx: Long, val maxSidePx: Int) : PngExportError

    data object InvalidScale : PngExportError

    /** No element (or no selected element) to export. */
    data object NothingToExport : PngExportError

    /** The encoder returned no bytes (it failed or ran out of memory); the scene is untouched. */
    data object EncodeFailed : PngExportError
}

/**
 * PNG export (Story 5.2.2): draws the same draw list the editor shows, through [ScenePainter], into an offscreen
 * `ImageBitmap` and encodes it with the platform [PngEncoder]. The pixel size is checked against
 * `SceneLimits.maxPngSidePx` and `maxPngPixels` before any bitmap is allocated. Offscreen draw works on wasmJs too (Spike 0.1.3c).
 */
class PngExporter(
    private val encoder: PngEncoder = PngEncoder(),
    private val resources: PainterResources = PainterResources(),
    private val limits: SceneLimits = SceneLimits.Default,
) {
    /** The pixel size [export] would produce, or why it would refuse; nothing is allocated or drawn. */
    fun measure(scene: SketchSceneV2, options: PngExportOptions = PngExportOptions()): Either<PngExportError, PixelSize> =
        plan(scene, options).map { PixelSize(it.widthPx, it.heightPx) }

    fun export(scene: SketchSceneV2, builder: DrawListBuilder, style: RenderStyle, options: PngExportOptions = PngExportOptions()): Either<PngExportError, ByteArray> {
        val plan = plan(scene, options).fold({ return it.left() }, { it })
        val content = plan.content
        val bitmap = ImageBitmap(plan.widthPx.toInt(), plan.heightPx.toInt(), ImageBitmapConfig.Argb8888)
        val viewport = SketchViewport.of(
            zoom = options.scale.toDouble(),
            panX = -(content.minX - options.padding) * options.scale,
            panY = -(content.minY - options.padding) * options.scale,
        )
        val ops = builder.build(plan.subject, style) + options.dimensions?.let { DimensionExport.drawOps(plan.dimensions, it.ink) }.orEmpty()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            options.background?.let { drawRect(CssColor.parse(it)) }
            ScenePainter(resources = resources).paint(this, ops, viewport, null)
        }
        val bytes = encoder.encode(bitmap)
        return if (bytes.isEmpty()) PngExportError.EncodeFailed.left() else bytes.right()
    }

    private class Plan(val subject: SketchSceneV2, val content: Bounds, val widthPx: Long, val heightPx: Long, val dimensions: List<ExportedDimension>)

    private fun plan(scene: SketchSceneV2, options: PngExportOptions): Either<PngExportError, Plan> {
        if (options.scale !in 1..PngExportOptions.MAX_SCALE) return PngExportError.InvalidScale.left()
        val subject = options.selection?.let { keep -> scene.order.filter { it !in keep }.fold(scene) { s, id -> s.remove(id).getOrNull() ?: s } } ?: scene
        val dimensions = options.dimensions?.let { DimensionExport.layout(subject, it) }.orEmpty()
        val content = options.bounds ?: contentBounds(subject)?.let { b -> DimensionExport.bounds(dimensions)?.let { union(b, it) } ?: b } ?: return PngExportError.NothingToExport.left()
        val widthPx = pixels(content.maxX - content.minX + 2 * options.padding, options.scale)
        val heightPx = pixels(content.maxY - content.minY + 2 * options.padding, options.scale)
        if (widthPx > limits.maxPngSidePx || heightPx > limits.maxPngSidePx || widthPx * heightPx > limits.maxPngPixels) {
            return PngExportError.TooLarge(widthPx, heightPx, limits.maxPngSidePx).left()
        }
        return Plan(subject, content, widthPx, heightPx, dimensions).right()
    }

    /** The pixel width or height of [sceneUnits] at [scale], at least one pixel. */
    private fun pixels(sceneUnits: Double, scale: Int): Long = max(1.0, ceil(sceneUnits * scale)).toLong()

    private fun contentBounds(scene: SketchSceneV2): Bounds? = scene.elementsInOrder.map { ElementBounds.of(it) }.reduceOrNull(::union)

    private fun union(a: Bounds, b: Bounds) = Bounds(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY))
}
