// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount")

package dev.stapler.stelekit.cli

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.platform.PngEncoder
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExport
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExportOptions
import dev.stapler.stelekit.sketch.v2.annotation.ExportInk
import dev.stapler.stelekit.sketch.v2.commands.SheetDimensionsExtras
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.dimensionsOn
import dev.stapler.stelekit.sketch.v2.commands.sheetSetup
import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.Orientation
import dev.stapler.stelekit.sketch.v2.export.PaperSize
import dev.stapler.stelekit.sketch.v2.export.ScalePresets
import dev.stapler.stelekit.sketch.v2.export.SheetPlanner
import dev.stapler.stelekit.sketch.v2.export.SheetPrint
import dev.stapler.stelekit.sketch.v2.export.SheetSetup
import dev.stapler.stelekit.sketch.v2.export.TitleBlock
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.withoutUnderlay
import dev.stapler.stelekit.ui.sketch.v2.SheetImageBitmaps
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.CssColor
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What `render` was asked for. [width] is the output width in pixels (SVG: the `width` attribute); null keeps the natural size. */
internal data class RenderRequest(
    /** True draws the dimension annotations, false hides them, null follows the sheet's own flag. */
    val dimensions: Boolean? = null,
    val width: Int? = null,
    val style: RenderStyle = RenderStyle.Clean,
    val background: String? = "#ffffff",
    /** The photo to draw as the sheet's underlay; null leaves the underlay out, which is what an export does by default. */
    val underlay: UnderlayImage? = null,
    /** Set when the output goes on a page at a drawing scale (`--paper`, `--scale`, a PDF); null keeps the picture's natural size. */
    val sheet: SheetFlags? = null,
)

/**
 * The page flags of `render`: each null field falls back to the sheet's stored setup, then to a default (Letter, the drawing's
 * orientation, the largest standard scale that fits). [dpi] is the PNG resolution; [tiled] writes a PDF too big for a page as
 * several pages.
 */
internal data class SheetFlags(
    val paper: PaperSize? = null,
    val orientation: Orientation? = null,
    val scale: DrawingScale? = null,
    val margin: Long? = null,
    val scaleBar: Boolean? = null,
    val north: Int? = null,
    val title: TitleBlock? = null,
    val dpi: Int = DEFAULT_DPI,
    val tiled: Boolean = false,
) {
    fun resolve(stored: SheetSetup?, content: Bounds): SheetSetup {
        val chosenPaper = paper ?: stored?.paper ?: PaperSize.Letter
        val keptMargin = stored?.margin?.takeIf { chosenPaper == stored.paper }
        val partial = SheetSetup(
            paper = chosenPaper,
            orientation = orientation ?: stored?.orientation ?: SheetPlanner.orientationFor(content),
            margin = margin ?: keptMargin ?: SheetSetup.defaultMargin(chosenPaper),
            scaleBar = scaleBar ?: stored?.scaleBar ?: true,
            northDegrees = north ?: stored?.northDegrees,
            titleBlock = title ?: stored?.titleBlock,
        )
        val chosen = scale ?: stored?.scale ?: SheetPlanner.bestFit(partial, content, !chosenPaper.imperial) ?: ScalePresets.family(!chosenPaper.imperial).last().scale
        return partial.copy(scale = chosen)
    }

    companion object {
        const val DEFAULT_DPI = 150
    }
}

/** A photo file given to `render --underlay`: its sha256 (which must equal the underlay's `fileId`), type and bytes. */
internal class UnderlayImage(val fileId: String, val mime: String, val bytes: ByteArray)

/** Headless rendering of a v2 sheet through the app's own draw list, SVG writer and Compose painter. */
internal object SketchRender {
    private const val FLOAT_SLACK = 1e-6
    private const val PAD_FRACTION = 0.04
    private const val MIN_PAD = 16.0
    private const val DEFAULT_PNG_WIDTH = 1600
    private const val MIN_PNG_WIDTH = 400

    /** The bundled font is not available headless, so layout uses the persisted text boxes; every glyph is assumed present. */
    private val metrics = object : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded

        override fun lineHeight(style: TextStyle): Double = style.fontSize

        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize

        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    /** How the annotations are drawn, or null for none: the request over the sheet's flag, the ink following the background. */
    fun dimensionOptions(doc: SketchDocumentV2, request: RenderRequest): DimensionExportOptions? =
        if (request.dimensions ?: sheetShows(doc)) DimensionExportOptions(ink = ExportInk.forBackground(request.background)) else null

    /** A spec-built plan already carries its authored dimension marks, so without a stored choice it is not annotated twice. */
    private fun sheetShows(doc: SketchDocumentV2): Boolean =
        SheetDimensionsExtras.read(doc.extras) ?: (doc.dimensionsOn && doc.extras["agent"] == null)

    /** The sheet as drawn: without the photo underlay unless [RenderRequest.underlay] supplies it. */
    private fun sceneOf(doc: SketchDocumentV2, request: RenderRequest): SketchSceneV2 = if (request.underlay != null) doc.scene else doc.scene.withoutUnderlay()

    private fun photoSource(request: RenderRequest): SvgImageSource =
        request.underlay?.let { u -> SvgImageSource { id -> if (id == u.fileId) SvgImage(u.mime, u.bytes) else null } } ?: SvgImageSource.None

    /** The sheet laid out on a page for [request], or null when the sheet is empty. */
    fun print(doc: SketchDocumentV2, request: RenderRequest, flags: SheetFlags): SheetPrint? {
        val scene = sceneOf(doc, request)
        if (scene.size == 0) return null
        val options = SvgExportOptions(request.background, dimensions = dimensionOptions(doc, request))
        val content = SvgExporter.content(scene, DrawListBuilder(metrics), request.style, options)
        val setup = flags.resolve(doc.sheetSetup, content.bounds)
        val plan = SheetPlanner.plan(setup, content.bounds, !setup.paper.imperial)
        return SheetPrint(plan, content.ops, clip = !plan.fits, ink = ExportInk.forBackground(request.background), background = request.background)
    }

    fun pageSvg(doc: SketchDocumentV2, request: RenderRequest, flags: SheetFlags): String? = print(doc, request, flags)?.svg(photoSource(request))

    fun pdf(doc: SketchDocumentV2, request: RenderRequest, flags: SheetFlags, title: String): ByteArray? = print(doc, request, flags)?.pdf(flags.tiled, title)

    /** The page as a PNG at [SheetFlags.dpi]; null for an empty sheet or a page over the PNG size cap. */
    fun pagePng(doc: SketchDocumentV2, request: RenderRequest, flags: SheetFlags): ByteArray? {
        val print = print(doc, request, flags) ?: return null
        val limits = SceneLimits.Default
        val widthPx = ceil(print.plan.pageWidth * flags.dpi / PAGE_UNITS_PER_INCH).toInt()
        val heightPx = ceil(print.plan.pageHeight * flags.dpi / PAGE_UNITS_PER_INCH).toInt()
        if (widthPx > limits.maxPngSidePx || heightPx > limits.maxPngSidePx || widthPx.toLong() * heightPx > limits.maxPngPixels) return null
        val bitmap = ImageBitmap(widthPx, heightPx, ImageBitmapConfig.Argb8888)
        val viewport = SketchViewport.of(zoom = flags.dpi / PAGE_UNITS_PER_INCH, panX = 0.0, panY = 0.0)
        val resources = PainterResources(textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr))
        val reach = Size(widthPx.toFloat(), heightPx.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), reach) {
            request.background?.let { drawRect(CssColor.parse(it)) }
            ScenePainter(resources = resources).paint(this, print.pages(false).single(), viewport, null)
        }
        return PngEncoder().encode(bitmap).takeIf { it.isNotEmpty() }
    }

    private const val PAGE_UNITS_PER_INCH = 16.0

    fun svg(doc: SketchDocumentV2, request: RenderRequest): String {
        val scene = sceneOf(doc, request)
        val pad = padding(scene)
        val photo = photoSource(request)
        val raw = SvgExporter.export(scene, DrawListBuilder(metrics), request.style, photo, SvgExportOptions(request.background, pad, dimensions = dimensionOptions(doc, request)))
        return request.width?.let { resize(raw, it) } ?: raw
    }

    /** Rewrites the root `width` and `height` so the picture is [width] wide with the same aspect; the viewBox is untouched. */
    private fun resize(svg: String, width: Int): String {
        val head = Regex("width=\"([0-9.]+)\" height=\"([0-9.]+)\"").find(svg) ?: return svg
        val w = head.groupValues[1].toDouble()
        val h = head.groupValues[2].toDouble()
        if (w <= 0.0) return svg
        val height = SketchLengths.tidy(h * width / w)
        return svg.replaceFirst(head.value, "width=\"$width\" height=\"${height.toString().removeSuffix(".0")}\"")
    }

    fun png(doc: SketchDocumentV2, request: RenderRequest): ByteArray? {
        val scene = sceneOf(doc, request)
        val dimensions = dimensionOptions(doc, request)?.let { DimensionExport.layout(scene, it) }.orEmpty()
        val shapes = contentBounds(scene) ?: return null
        val content = DimensionExport.bounds(dimensions)?.let { union(shapes, it) } ?: shapes
        val pad = padding(scene)
        val unitsWide = content.maxX - content.minX + 2 * pad
        val unitsHigh = content.maxY - content.minY + 2 * pad
        val target = request.width ?: unitsWide.toInt().coerceIn(MIN_PNG_WIDTH, DEFAULT_PNG_WIDTH)
        val limits = SceneLimits.Default
        val zoom = min(target / unitsWide, limits.maxPngSidePx / max(unitsWide, unitsHigh))
        val widthPx = max(1, ceil(unitsWide * zoom - FLOAT_SLACK).toInt())
        val heightPx = max(1, ceil(unitsHigh * zoom - FLOAT_SLACK).toInt())
        if (widthPx.toLong() * heightPx > limits.maxPngPixels) return null
        val ops: List<DrawOp> = DrawListBuilder(metrics).build(scene, request.style) + DimensionExport.drawOps(dimensions, ExportInk.forBackground(request.background))
        val bitmap = ImageBitmap(widthPx, heightPx, ImageBitmapConfig.Argb8888)
        val viewport = SketchViewport.of(zoom = zoom, panX = -(content.minX - pad) * zoom, panY = -(content.minY - pad) * zoom)
        val photo = request.underlay?.let { u -> SheetImageBitmaps.reduceAndDecode(u.bytes)?.let { u.fileId to it } }
        val resources = PainterResources(textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), image = { id -> photo?.takeIf { it.first == id }?.second })
        // Text is laid out against the draw scope's size but positioned in scene units, so the scope must cover the scene's far corner or text beyond it vanishes.
        val reach = Size(max(widthPx.toDouble(), content.maxX + unitsWide).toFloat(), max(heightPx.toDouble(), content.maxY + unitsHigh).toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), reach) {
            request.background?.let { drawRect(CssColor.parse(it)) }
            ScenePainter(resources = resources).paint(this, ops, viewport, null)
        }
        return PngEncoder().encode(bitmap).takeIf { it.isNotEmpty() }
    }

    private fun contentBounds(scene: SketchSceneV2): Bounds? = scene.elementsInOrder.map { ElementBounds.of(it) }.reduceOrNull(::union)

    private fun union(a: Bounds, b: Bounds) = Bounds(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY))

    private fun padding(scene: SketchSceneV2): Double {
        val b = contentBounds(scene) ?: return MIN_PAD
        return max(MIN_PAD, PAD_FRACTION * max(b.maxX - b.minX, b.maxY - b.minY))
    }


}
