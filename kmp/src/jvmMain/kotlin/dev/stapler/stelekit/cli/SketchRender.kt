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
import arrow.core.getOrElse
import dev.stapler.stelekit.platform.PngEncoder
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
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
    val dimensions: Boolean = false,
    val width: Int? = null,
    val style: RenderStyle = RenderStyle.Clean,
    val background: String? = "#ffffff",
)

/** Headless rendering of a v2 sheet through the app's own draw list, SVG writer and Compose painter. */
internal object SketchRender {
    private const val FLOAT_SLACK = 1e-6
    private const val PAD_FRACTION = 0.04
    private const val MIN_PAD = 16.0
    private const val DEFAULT_PNG_WIDTH = 1600
    private const val MIN_PNG_WIDTH = 400
    private const val OVERLAY_ID = "auto"
    private const val MAX_OVERLAY_SHAPES = 60

    /** The bundled font is not available headless, so layout uses the persisted text boxes; every glyph is assumed present. */
    private val metrics = object : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded

        override fun lineHeight(style: TextStyle): Double = style.fontSize

        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize

        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    fun scene(doc: SketchDocumentV2, request: RenderRequest): SketchSceneV2 =
        if (request.dimensions) withMeasuredDimensions(doc.scene, isPlan(doc)) else doc.scene

    /** A sheet the spec builder made says its mode; any other sheet counts as a plan when it is more than a few hundred units across. */
    private fun isPlan(doc: SketchDocumentV2): Boolean {
        val mode = ((doc.extras["agent"] as? kotlinx.serialization.json.JsonObject)?.get("mode") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (mode != null) return mode == SpecMode.Plan.wire
        val b = contentBounds(doc.scene) ?: return false
        return max(b.maxX - b.minX, b.maxY - b.minY) > PLAN_MIN_EXTENT
    }

    fun svg(doc: SketchDocumentV2, request: RenderRequest): String {
        val scene = scene(doc, request)
        val pad = padding(scene)
        val raw = SvgExporter.export(scene, DrawListBuilder(metrics), request.style, options = SvgExportOptions(request.background, pad))
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
        val scene = scene(doc, request)
        val content = contentBounds(scene) ?: return null
        val pad = padding(scene)
        val unitsWide = content.maxX - content.minX + 2 * pad
        val unitsHigh = content.maxY - content.minY + 2 * pad
        val target = request.width ?: unitsWide.toInt().coerceIn(MIN_PNG_WIDTH, DEFAULT_PNG_WIDTH)
        val limits = SceneLimits.Default
        val zoom = min(target / unitsWide, limits.maxPngSidePx / max(unitsWide, unitsHigh))
        val widthPx = max(1, ceil(unitsWide * zoom - FLOAT_SLACK).toInt())
        val heightPx = max(1, ceil(unitsHigh * zoom - FLOAT_SLACK).toInt())
        if (widthPx.toLong() * heightPx > limits.maxPngPixels) return null
        val ops: List<DrawOp> = DrawListBuilder(metrics).build(scene, request.style)
        val bitmap = ImageBitmap(widthPx, heightPx, ImageBitmapConfig.Argb8888)
        val viewport = SketchViewport.of(zoom = zoom, panX = -(content.minX - pad) * zoom, panY = -(content.minY - pad) * zoom)
        val resources = PainterResources(textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr))
        // Text is laid out against the draw scope's size but positioned in scene units, so the scope must cover the scene's far corner or text beyond it vanishes.
        val reach = Size(max(widthPx.toDouble(), content.maxX + unitsWide).toFloat(), max(heightPx.toDouble(), content.maxY + unitsHigh).toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), reach) {
            request.background?.let { drawRect(CssColor.parse(it)) }
            ScenePainter(resources = resources).paint(this, ops, viewport, null)
        }
        return PngEncoder().encode(bitmap).takeIf { it.isNotEmpty() }
    }

    private fun contentBounds(scene: SketchSceneV2): Bounds? =
        scene.elementsInOrder.map { ElementBounds.of(it) }.reduceOrNull { a, b ->
            Bounds(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY))
        }

    private fun padding(scene: SketchSceneV2): Double {
        val b = contentBounds(scene) ?: return MIN_PAD
        return max(MIN_PAD, PAD_FRACTION * max(b.maxX - b.minX, b.maxY - b.minY))
    }

    /** The scene plus a width and height dimension for every rectangle, ellipse and diamond on a drawing layer; never saved. */
    fun withMeasuredDimensions(scene: SketchSceneV2, plan: Boolean): SketchSceneV2 {
        val font = if (plan) 64.0 else 14.0
        val thin = if (plan) 16.0 else 1.0
        val offset = if (plan) 18.0 * 16 else 30.0
        var out = if (scene.layer(LayerId(SketchLayers.DIMENSIONS)) == null) scene.withLayers(scene.layers + Layer(LayerId(SketchLayers.DIMENSIONS), "Dimensions", order = Int.MAX_VALUE)) else scene
        val sink = object : MarkSink {
            override fun add(e: SceneElement) {
                out = out.add(e).getOrElse { out }
            }

            override fun text(id: String, text: String, cx: Double, cy: Double, fs: Double, color: String, groups: List<GroupId>) {
                val w = SketchLengths.tidy(text.length * fs * 0.55)
                val h = SketchLengths.tidy(fs * TextElement.DEFAULT_LINE_HEIGHT)
                val unit = { v: Double -> SceneUnit.of(v).getOrElse { SceneUnit.ZERO } }
                add(
                    TextElement(
                        ElementBase(ElementId(id), LayerId(SketchLayers.DIMENSIONS), groupIds = groups, style = ElementStyle(strokeColor = color, roughness = 0)),
                        unit(cx - w / 2), unit(cy - h / 2), unit(w), unit(h), 0.0, text, text, fs, 1, TextAlign.Center, VerticalAlign.Middle,
                    ),
                )
            }
        }
        val marks = DimensionMarks(sink, plan, thin, font)
        val shapes = scene.elementsInOrder.filter { it.base.layerId.value !in setOf(SketchLayers.OPENINGS, SketchLayers.DIMENSIONS) }
            .filter { it is RectElement || it is EllipseElement || it is DiamondElement }.take(MAX_OVERLAY_SHAPES)
        for (s in shapes) {
            val b = ElementBounds.geometry(s)
            val name = "$OVERLAY_ID.${s.id.value}"
            marks.emit("$name.w", b.minX to b.minY, b.maxX to b.minY, -offset, null)
            marks.emit("$name.h", b.maxX to b.minY, b.maxX to b.maxY, offset, null)
        }
        return out
    }

    private const val PLAN_MIN_EXTENT = 600.0
}
