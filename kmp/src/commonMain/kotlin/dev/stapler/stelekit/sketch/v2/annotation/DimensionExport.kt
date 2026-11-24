// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.annotation

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.render.StrokePaint
import dev.stapler.stelekit.sketch.v2.render.TextLine
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.units.LengthFormat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What an export does about dimensions: [Sheet] follows the sheet's own flag, [On] and [Off] override it for this export only. */
enum class DimensionsChoice {
    Sheet, On, Off;

    fun resolve(sheetOn: Boolean): Boolean = when (this) {
        Sheet -> sheetOn
        On -> true
        Off -> false
    }
}

/** How the exported annotations read: the length notation, and the ink (see [ExportInk.forBackground] for the pick that suits a background). */
data class DimensionExportOptions(
    val format: LengthFormat = LengthFormat.FEET_INCHES,
    val ink: String = ExportInk.DARK,
)

/** Ink for annotations over a light or a dark export background (a transparent one is read as light, the usual page colour). */
object ExportInk {
    const val DARK = "#1e1e1e"
    const val LIGHT = "#e9ecef"
    private const val SHORT_HEX = 3
    private const val LONG_HEX = 6
    private const val HEX_RADIX = 16
    private const val RED_WEIGHT = 0.2126
    private const val GREEN_WEIGHT = 0.7152
    private const val BLUE_WEIGHT = 0.0722
    private const val DARK_BELOW = 0.5
    private const val CHANNEL_MAX = 255.0

    fun forBackground(css: String?): String = if (luminance(css) < DARK_BELOW) LIGHT else DARK

    private fun luminance(css: String?): Double {
        val hex = css?.trim()?.removePrefix("#") ?: return 1.0
        val full = if (hex.length == SHORT_HEX) hex.map { "$it$it" }.joinToString("") else hex
        if (full.length != LONG_HEX) return 1.0
        val channels = full.chunked(2).map { it.toIntOrNull(HEX_RADIX) ?: return 1.0 }
        return (RED_WEIGHT * channels[0] + GREEN_WEIGHT * channels[1] + BLUE_WEIGHT * channels[2]) / CHANNEL_MAX
    }
}

/**
 * One annotation in scene units, ready to be drawn by any exporter: [pieces] are the dimension line with the gap its value sits
 * in, [extensions] and [ticks] the other strokes, [textRect] where the value goes. Sizes ([strokeWidth], [fontSize]) are scene
 * units too, so the picture keeps its proportions at any export scale.
 */
class ExportedDimension(
    val elementId: ElementId,
    val kind: MeasureKind,
    val sixteenths: Long,
    val text: String,
    val alpha: Double,
    val pieces: List<AnnotationSegment>,
    val extensions: List<AnnotationSegment>,
    val ticks: List<AnnotationSegment>,
    val textRect: AnnotationRect,
    val strokeWidth: Double,
    val fontSize: Double,
) {
    val strokes: List<AnnotationSegment> get() = extensions + pieces + ticks

    fun bounds(): Bounds {
        val points = strokes.flatMap { listOf(it.a, it.b) } + listOf(AnnotationPoint(textRect.left, textRect.top), AnnotationPoint(textRect.right, textRect.bottom))
        return Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}

/**
 * The dimension annotations of a sheet as they are exported: the editor's own layout ([DimensionAnnotationEngine]) run on a fixed
 * fit of the sheet instead of the viewport. The fit depends on the shapes alone, so panning or zooming the editor never changes
 * an export, and the same shapes always give the same picture.
 */
object DimensionExport {
    /** The longer side of the sheet's content spans this many dp on the layout canvas (a phone screen is about 360). */
    const val FIT_DP = 480.0

    /** The layout zooms in on a sheet of small shapes until its typical shape is this many dp across, but never more than [BOOST_MAX] times the fit. */
    const val REQUIRED_SPAN_DP = 100.0
    const val BOOST_MAX = 3.0

    /** Room around the content, in dp, for the stacked rows of lines and the values outside the shapes. */
    const val MARGIN_DP = 200.0

    /** A sheet smaller than this many scene units is laid out as if it were this large, so a tiny shape does not get a huge value. */
    const val MIN_EXTENT_UNITS = 256.0

    private const val LINE_DP = 1.0
    private const val TICK_DP = 4.0
    private const val TEXT_TOP_RATIO = 0.44
    private const val FULL_OPACITY_PERCENT = 100
    private const val TICK_DIRECTION_SLACK = 0.0

    /** The annotations of every visible shape of [scene], nearest the sheet's centre first; empty for a scene with no measurable shape. */
    fun layout(scene: SketchSceneV2, options: DimensionExportOptions = DimensionExportOptions(), fitDp: Double = FIT_DP): List<ExportedDimension> {
        val visible = scene.elementsInOrder.filter { scene.layer(it.base.layerId)?.visible != false }
        val content = visible.map { ElementBounds.of(it) }.reduceOrNull(::union) ?: return emptyList()
        val extent = max(max(content.maxX - content.minX, content.maxY - content.minY), MIN_EXTENT_UNITS)
        val fit = fitDp / extent
        val dpPerUnit = typicalSpan(visible)?.let { REQUIRED_SPAN_DP / it }?.coerceIn(fit, fit * BOOST_MAX) ?: fit
        val canvas = AnnotationCanvas(
            toScreenDp = { x, y -> ((x - content.minX) * dpPerUnit + MARGIN_DP) to ((y - content.minY) * dpPerUnit + MARGIN_DP) },
            dpPerUnit = dpPerUnit,
            width = (content.maxX - content.minX) * dpPerUnit + 2 * MARGIN_DP,
            height = (content.maxY - content.minY) * dpPerUnit + 2 * MARGIN_DP,
        )
        val placed = DimensionAnnotationEngine.layout(visible, visible, canvas, emptyList(), fontScale = 1.0, tapMinDp = 0.0, format = options.format)
        val toScene = { p: AnnotationPoint -> AnnotationPoint((p.x - MARGIN_DP) / dpPerUnit + content.minX, (p.y - MARGIN_DP) / dpPerUnit + content.minY) }
        return placed.map { it.inScene(toScene, dpPerUnit) }
    }

    /** The median of the shorter sides of the shapes that carry a dimension (a line's length), so one big room does not hide every piece of furniture. */
    private fun typicalSpan(visible: List<SceneElement>): Double? {
        val spans = visible.mapNotNull { e ->
            when {
                Measurements.of(e).isEmpty() -> null
                e is LineElement -> Measurements.lineLength(e)
                else -> ElementFrame.of(e)?.let { min(it.width, it.height) }
            }
        }.filter { it > 0.0 }.sorted()
        return spans.getOrNull(spans.size / 2)
    }

    private fun PlacedDimension.inScene(toScene: (AnnotationPoint) -> AnnotationPoint, dpPerUnit: Double): ExportedDimension {
        val seg = { s: AnnotationSegment -> AnnotationSegment(toScene(s.a), toScene(s.b)) }
        val rect = textRect.let { r -> toScene(AnnotationPoint(r.left, r.top)).let { a -> toScene(AnnotationPoint(r.right, r.bottom)).let { b -> AnnotationRect(a.x, a.y, b.x, b.y) } } }
        val sceneLine = seg(line)
        val tick = TICK_DP / dpPerUnit
        return ExportedDimension(
            elementId, kind, sixteenths, text, alpha,
            pieces = rect.clipOutside(sceneLine),
            extensions = extensions.map(seg),
            ticks = ticksAt(sceneLine.a, sceneLine.b, tick) + ticksAt(sceneLine.b, sceneLine.a, tick),
            textRect = rect,
            strokeWidth = LINE_DP / dpPerUnit,
            fontSize = DimensionAnnotationEngine.TEXT_SP / dpPerUnit,
        )
    }

    /** An architectural tick: a short stroke across the line's [end] at 45 degrees. */
    private fun ticksAt(end: AnnotationPoint, other: AnnotationPoint, half: Double): List<AnnotationSegment> {
        val len = hypot(other.x - end.x, other.y - end.y).takeIf { it > TICK_DIRECTION_SLACK } ?: return emptyList()
        val dx = (other.x - end.x) / len
        val dy = (other.y - end.y) / len
        val sx = (dx - dy) * half
        val sy = (dx + dy) * half
        return listOf(AnnotationSegment(AnnotationPoint(end.x - sx, end.y - sy), AnnotationPoint(end.x + sx, end.y + sy)))
    }

    /** The union of the annotations' extents, or null when there are none. */
    fun bounds(dimensions: List<ExportedDimension>): Bounds? = dimensions.map { it.bounds() }.reduceOrNull(::union)

    /** The annotations as draw ops in scene space: one stroked path per annotation (its lines and ticks) and one text run for the value. */
    fun drawOps(dimensions: List<ExportedDimension>, ink: String): List<DrawOp> = dimensions.flatMap { d ->
        val segments = d.strokes.flatMap { listOf(PathSegment.MoveTo(it.a.x, it.a.y), PathSegment.LineTo(it.b.x, it.b.y)) }
        val opacity = d.alpha
        listOf(
            DrawOp.PathOp(d.elementId, segments, StrokePaint(ink, d.strokeWidth), null, opacity),
            DrawOp.TextRun(
                d.elementId,
                listOf(TextLine(d.text, d.textRect.centreX, d.textRect.centreY - d.fontSize * TEXT_TOP_RATIO)),
                fontFamily = SANS_FAMILY, fontSize = d.fontSize, color = ink, align = TextAlign.Center,
                angle = 0.0, pivotX = d.textRect.centreX, pivotY = d.textRect.centreY, usesFallbackFont = false, opacity = opacity,
            ),
        )
    }

    /**
     * The annotations as plain elements (lines and a text) on a `dimensions` layer added to [scene], for formats that have no
     * draw list (the Excalidraw file). An annotation that would not fit under the scene's element cap is left out.
     */
    fun withElements(scene: SketchSceneV2, dimensions: List<ExportedDimension>, ink: String): SketchSceneV2 {
        if (dimensions.isEmpty()) return scene
        val layer = LayerId(LAYER_ID)
        var out = if (scene.layer(layer) == null) scene.withLayers(scene.layers + Layer(layer, LAYER_NAME, order = Int.MAX_VALUE)) else scene
        for (d in dimensions) {
            val parts = elementsOf(d, layer, ink)
            if (out.size + parts.size > out.limits.maxElements) continue
            out = parts.fold(out) { s, e -> s.add(e).getOrElse { s } }
        }
        return out
    }

    private fun elementsOf(d: ExportedDimension, layer: LayerId, ink: String): List<SceneElement> {
        val id = "$ID_PREFIX${d.elementId.value}.${d.kind.name.lowercase()}"
        val style = ElementStyle(strokeColor = ink, strokeWidth = d.strokeWidth, roughness = 0)
        val opacity = (d.alpha * FULL_OPACITY_PERCENT).toInt().coerceIn(0, FULL_OPACITY_PERCENT)
        fun base(suffix: String, s: ElementStyle = style) = ElementBase(ElementId("$id.$suffix"), layer, style = s, opacity = opacity)
        val unit = { v: Double -> SceneUnit.of(v).getOrElse { SceneUnit.ZERO } }
        val lines = d.strokes.mapIndexed { i, s ->
            LineElement(base("s$i"), listOf(ScenePoint(unit(s.a.x), unit(s.a.y)), ScenePoint(unit(s.b.x), unit(s.b.y))))
        }
        val r = d.textRect
        val text = TextElement(
            base("t", style.copy(strokeColor = ink)), unit(r.left), unit(r.top), unit(r.width), unit(r.height), 0.0, d.text, d.text, d.fontSize, SANS_FAMILY,
            TextAlign.Center, VerticalAlign.Middle,
        )
        return lines + text
    }

    private fun union(a: Bounds, b: Bounds) = Bounds(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY))

    private const val SANS_FAMILY = 2
    const val LAYER_ID = "dimensions"
    private const val LAYER_NAME = "Dimensions"
    private const val ID_PREFIX = "dim."
}
