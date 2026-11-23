// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.StrokePaint
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/** What the painter needs to draw text and images; every field is optional so a pure-shape scene needs none. */
class PainterResources(
    val textMeasurer: TextMeasurer? = null,
    /** Maps an Excalidraw `fontFamily` id to the bundled family, or null for the platform default (the fallback path). */
    val fontFamily: (Int) -> FontFamily? = { null },
    /** Maps an image `fileId` to its decoded bitmap, or null while it is missing or loading (a placeholder is drawn). */
    val image: (String) -> ImageBitmap? = { null },
)

/**
 * Paints a draw list (ADR-004) onto a [DrawScope]: `screen = scene * zoom + pan`. Ops of elements outside the visible
 * id set are skipped (culling through `SpatialIndex`), and Compose paths are cached by [PathCache]. Draw phase only.
 */
class ScenePainter(val paths: PathCache = PathCache(), private val resources: PainterResources = PainterResources()) {
    /** How many ops were drawn and skipped by the last [paint], for the culling tests. */
    var drawnOps: Int = 0
        private set
    var culledOps: Int = 0
        private set

    /**
     * Draws [ops] under [viewport]. [visible] is the culling set (null draws everything).
     */
    fun paint(scope: DrawScope, ops: List<DrawOp>, viewport: SketchViewport, visible: Set<ElementId>?) {
        paintCounting(scope, ops, viewport, visible)
    }

    /** [paint] for the full static list: the path cache then keeps only the elements that appear in [ops]. */
    fun paintAndPrune(scope: DrawScope, ops: List<DrawOp>, viewport: SketchViewport, visible: Set<ElementId>?) {
        paths.retain(paintCounting(scope, ops, viewport, visible))
    }

    private fun paintCounting(scope: DrawScope, ops: List<DrawOp>, viewport: SketchViewport, visible: Set<ElementId>?): Set<ElementId> {
        drawnOps = 0
        culledOps = 0
        val slots = HashMap<ElementId, Int>()
        scope.withTransform({
            translate(viewport.panX.toFloat(), viewport.panY.toFloat())
            scale(viewport.zoom.toFloat(), viewport.zoom.toFloat(), pivot = Offset.Zero)
        }) {
            drawOps(ops, visible, slots)
        }
        return slots.keys
    }

    private fun DrawScope.drawOps(ops: List<DrawOp>, visible: Set<ElementId>?, slots: HashMap<ElementId, Int>) {
        for (op in ops) {
            when (op) {
                is DrawOp.ClipOp -> clipRect(op.rect.x.toFloat(), op.rect.y.toFloat(), (op.rect.x + op.rect.width).toFloat(), (op.rect.y + op.rect.height).toFloat()) {
                    drawOps(op.ops, visible, slots)
                }
                is DrawOp.PathOp -> if (shown(op.elementId, visible)) drawPath(op, slots) else cull(op.elementId, slots)
                is DrawOp.TextRun -> if (shown(op.elementId, visible)) drawRun(op) else culledOps++
                is DrawOp.ImageOp -> if (shown(op.elementId, visible)) drawImage(op) else culledOps++
            }
        }
    }

    /** A culled element still counts as present, so [paintAndPrune] keeps its cached paths. */
    private fun cull(id: ElementId, slots: HashMap<ElementId, Int>) {
        if (id !in slots) slots[id] = 0
        culledOps++
    }

    private fun shown(id: ElementId, visible: Set<ElementId>?): Boolean = visible == null || id in visible

    private fun DrawScope.drawPath(op: DrawOp.PathOp, slots: HashMap<ElementId, Int>) {
        val slot = slots[op.elementId] ?: 0
        slots[op.elementId] = slot + 1
        val path = paths.pathFor(op.elementId, slot, op.segments)
        op.fill?.let { color -> drawPath(path, colorOf(color), op.opacity.toFloat(), Fill) }
        op.stroke?.let { s -> drawPath(path, colorOf(s.color), op.opacity.toFloat(), strokeOf(s)) }
        drawnOps++
    }

    private val colors = HashMap<String, Color>()
    private val strokes = HashMap<StrokePaint, Stroke>()

    private fun colorOf(css: String): Color = colors.getOrPut(css) { CssColor.parse(css) }

    private fun strokeOf(s: StrokePaint): Stroke = strokes.getOrPut(s) {
        Stroke(
            width = s.width.toFloat(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (s.dash.isEmpty()) null else PathEffect.dashPathEffect(FloatArray(s.dash.size) { s.dash[it].toFloat() }),
        )
    }

    private fun DrawScope.drawRun(run: DrawOp.TextRun) {
        val measurer = resources.textMeasurer ?: return
        val family = if (run.usesFallbackFont) null else resources.fontFamily(run.fontFamily)
        // The canvas is already scaled by the zoom, so one sp must be one scene unit whatever the screen density.
        val style = ComposeTextStyle(
            color = colorOf(run.color).copy(alpha = run.opacity.toFloat()),
            fontSize = (run.fontSize / (density * fontScale)).sp,
            fontFamily = family,
        )
        withTransform({ rotateRad(run.angle.toFloat(), Offset(run.pivotX.toFloat(), run.pivotY.toFloat())) }) {
            for (line in run.lines) {
                val width = measurer.measure(line.text, style, softWrap = false, maxLines = 1).size.width
                val left = when (run.align) {
                    TextAlign.Left -> line.x
                    TextAlign.Center -> line.x - width / 2.0
                    TextAlign.Right -> line.x - width
                }
                drawText(measurer, line.text, Offset(left.toFloat(), line.y.toFloat()), style, softWrap = false, maxLines = 1)
            }
        }
        drawnOps++
    }

    private fun DrawScope.drawImage(op: DrawOp.ImageOp) {
        val r = op.rect
        val centre = Offset((r.x + r.width / 2.0).toFloat(), (r.y + r.height / 2.0).toFloat())
        val bitmap = op.fileId?.let { resources.image(it) }
        withTransform({ rotateRad(op.angle.toFloat(), centre) }) {
            if (bitmap == null) {
                drawRect(PLACEHOLDER, Offset(r.x.toFloat(), r.y.toFloat()), Size(r.width.toFloat(), r.height.toFloat()), op.opacity.toFloat())
            } else {
                drawBitmap(op, bitmap, centre)
            }
        }
        drawnOps++
    }

    private fun DrawScope.drawBitmap(op: DrawOp.ImageOp, bitmap: ImageBitmap, centre: Offset) {
        val r = op.rect
        val crop = op.crop
        val srcSize = if (crop == null) IntSize(bitmap.width, bitmap.height) else IntSize(crop.width.toInt(), crop.height.toInt())
        val srcOffset = if (crop == null) IntOffset.Zero else IntOffset(crop.x.toInt(), crop.y.toInt())
        withTransform({ scale(if (op.flipX) -1f else 1f, if (op.flipY) -1f else 1f, centre) }) {
            drawImage(bitmap, srcOffset, srcSize, IntOffset(r.x.toInt(), r.y.toInt()), IntSize(r.width.toInt(), r.height.toInt()), alpha = op.opacity.toFloat())
        }
    }

    companion object {
        private val PLACEHOLDER = Color(0xFFE0E0E0)

        /** Scene-space margin added around the view when culling, so strokes, arrowheads and frame titles at the edge still draw. */
        const val CULL_MARGIN_PX = 48.0

        /** Ids of the elements whose bounds meet the [widthPx] x [heightPx] screen area under [viewport]. */
        fun visibleIds(index: SpatialIndex, viewport: SketchViewport, widthPx: Double, heightPx: Double): Set<ElementId> =
            visibleIdsIn(index, viewport, ScreenRect(0.0, 0.0, widthPx, heightPx))

        /** Ids of the elements whose bounds meet the screen [area] under [viewport] (the touch loupe draws only the area under its bubble). */
        fun visibleIdsIn(index: SpatialIndex, viewport: SketchViewport, area: ScreenRect): Set<ElementId> {
            val m = CULL_MARGIN_PX / viewport.zoom
            val minX = (area.left - viewport.panX) / viewport.zoom - m
            val minY = (area.top - viewport.panY) / viewport.zoom - m
            return index.query(Bounds(minX, minY, minX + (area.right - area.left) / viewport.zoom + 2 * m, minY + (area.bottom - area.top) / viewport.zoom + 2 * m))
        }
    }
}

/** CSS colour strings of the sheet format (`#rgb`, `#rrggbb`, `#rrggbbaa`, `transparent`); anything else draws black. */
object CssColor {
    private const val SHORT_LENGTH = 3
    private const val RGB_LENGTH = 6
    private const val RGBA_LENGTH = 8
    private const val HEX = 16
    private const val NIBBLE_SPREAD = 17
    private const val BYTE = 255f

    fun parse(css: String): Color {
        if (css == "transparent") return Color.Transparent
        val hex = css.removePrefix("#")
        if (hex.length == css.length) return Color.Black
        val digits = hex.mapNotNull { it.digitToIntOrNull(HEX) }
        if (digits.size != hex.length) return Color.Black
        return when (hex.length) {
            SHORT_LENGTH -> Color(digits[0] * NIBBLE_SPREAD / BYTE, digits[1] * NIBBLE_SPREAD / BYTE, digits[2] * NIBBLE_SPREAD / BYTE)
            RGB_LENGTH -> Color(byte(digits, 0) / BYTE, byte(digits, 2) / BYTE, byte(digits, 4) / BYTE)
            RGBA_LENGTH -> Color(byte(digits, 0) / BYTE, byte(digits, 2) / BYTE, byte(digits, 4) / BYTE, byte(digits, 6) / BYTE)
            else -> Color.Black
        }
    }

    private fun byte(digits: List<Int>, at: Int): Float = (digits[at] * HEX + digits[at + 1]).toFloat()
}
