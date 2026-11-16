// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter

/**
 * Pixel check of the 15-element "architecture-15" scene in Sketchy (Task 2.2.3a), shared by the JVM and wasmJs tests:
 * the same scene is drawn offscreen at [WIDTH] x [HEIGHT] and reduced to an ink mask, which is compared with
 * [Architecture15Golden.INK_MASK] (rendered on the JVM) within `SketchyAvailability.PIXEL_TOLERANCE_FRACTION`.
 * Text is not painted (no text measurer), so the check does not depend on fonts.
 */
object Architecture15Pixels {
    const val WIDTH = 200
    const val HEIGHT = 140
    const val ZOOM = 0.5

    private fun u(i: Int) = SceneUnit.ofInt(i)
    private fun p(x: Int, y: Int) = ScenePoint(u(x), u(y))
    private fun base(id: String, frame: String? = null, style: ElementStyle = ElementStyle()) =
        ElementBase(ElementId(id), frameId = frame?.let { ElementId(it) }, style = style, seed = 7)

    private fun label(id: String, container: String, text: String, x: Int, y: Int, w: Int, h: Int) = TextElement(
        base(id, "frame"), u(x), u(y), u(w), u(h), text = text, containerId = ElementId(container),
        textAlign = TextAlign.Center, verticalAlign = VerticalAlign.Middle,
    )

    /** The same 15 elements as `DrawListGoldenTest`. */
    fun elements(): List<SceneElement> {
        val blue = ElementStyle(backgroundColor = "#a5d8ff", roundness = Roundness(RoundnessKind.AdaptiveRadius))
        val dashed = ElementStyle(strokeStyle = StrokeStyle.Dashed)
        val rule = listOf(p(20, 110), p(380, 110))
        return listOf(
            FrameElement(base("frame").copy(name = "Architecture"), u(0), u(0), u(400), u(220)),
            RectElement(base("web", "frame", blue), u(20), u(40), u(100), u(60)),
            label("web-t", "web", "Web", 20, 40, 100, 60),
            RectElement(base("api", "frame", blue), u(160), u(40), u(100), u(60)),
            label("api-t", "api", "API", 160, 40, 100, 60),
            RectElement(base("db", "frame", blue), u(300), u(40), u(100), u(60)),
            label("db-t", "db", "DB", 300, 40, 100, 60),
            ArrowElement(base("web-api", "frame"), listOf(p(120, 70), p(160, 70)), Binding(ElementId("web"), 1.0, 0.5), Binding(ElementId("api"), 0.0, 0.5)),
            ArrowElement(
                base("api-db", "frame", ElementStyle(roundness = Roundness(RoundnessKind.ProportionalRadius))),
                listOf(p(260, 70), p(280, 60), p(300, 70)), Binding(ElementId("api"), 1.0, 0.5), Binding(ElementId("db"), 0.0, 0.5),
                startArrowhead = Arrowhead.CircleOutline, endArrowhead = Arrowhead.Triangle,
            ),
            EllipseElement(base("cache", "frame", dashed), u(210), u(160), u(40), u(30)),
            DiamondElement(base("gate", "frame"), u(300), u(130), u(80), u(60)),
            LineElement(base("rule", "frame", dashed), rule),
            PointElement(base("origin", "frame"), u(20), u(200)),
            ArcElement(base("bend", "frame"), u(100), u(170), u(30), 0.0, 1.5707963267948966),
            TextElement(base("note"), u(20), u(240), u(96), u(25), text = "Note", fontSize = 20.0),
        )
    }

    fun scene(): SketchSceneV2 = elements().fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }

    fun ops(style: RenderStyle = RenderStyle.Sketchy): List<DrawOp> = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene(), style)

    fun render(ops: List<DrawOp> = ops()): ImageBitmap {
        val bitmap = ImageBitmap(WIDTH, HEIGHT, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
            drawRect(Color.White)
            ScenePainter().paint(this, ops, SketchViewport.of(zoom = ZOOM), null)
        }
        return bitmap
    }

    /** One bit per pixel, row by row, 1 where the pixel is not white; as hex, one row per line. */
    fun inkMask(bitmap: ImageBitmap): String {
        val px = ScenePainterFixture.pixels(bitmap)
        val white = 0xFFFFFFFF.toInt()
        return (0 until bitmap.height).joinToString("\n") { y ->
            (0 until bitmap.width step NIBBLE).joinToString("") { x0 ->
                var nibble = 0
                for (i in 0 until NIBBLE) if (x0 + i < bitmap.width && !ScenePainterFixture.close(white, px[y * bitmap.width + x0 + i])) nibble = nibble or (1 shl (NIBBLE - 1 - i))
                "0123456789abcdef"[nibble].toString()
            }
        }
    }

    /** Fraction of the [WIDTH] x [HEIGHT] pixels whose ink bit differs between two masks from [inkMask]. */
    fun differingFraction(a: String, b: String): Double {
        val rowsA = a.lines()
        val rowsB = b.lines()
        require(rowsA.size == rowsB.size && rowsA.first().length == rowsB.first().length) { "masks differ in size" }
        var different = 0
        for (r in rowsA.indices) for (c in rowsA[r].indices) different += (rowsA[r][c].digitToInt(HEX) xor rowsB[r][c].digitToInt(HEX)).countOneBits()
        return different.toDouble() / (WIDTH * HEIGHT)
    }

    private const val NIBBLE = 4
    private const val HEX = 16
}
