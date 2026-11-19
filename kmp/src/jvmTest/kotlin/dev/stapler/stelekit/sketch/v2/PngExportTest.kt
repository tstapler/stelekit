// jvmTest-reason: pixels
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExportError
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExportOptions
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.2.2 on the JVM encoder: size, per-kind ink, agreement with the in-process render, the side cap and selection export. */
class PngExportTest {
    private object StubMetrics : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded
        override fun lineHeight(style: TextStyle): Double = style.fontSize
        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize
        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    private val picture = ImageBitmap(PICTURE_SIDE, PICTURE_SIDE, ImageBitmapConfig.Argb8888).also { bmp ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(PICTURE_SIDE.toFloat(), PICTURE_SIDE.toFloat())) { drawRect(Color.Red) }
    }
    private val resources = PainterResources(
        textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
        image = { if (it == PICTURE_ID) picture else null },
    )
    private val exporter = PngExporter(resources = resources)

    private fun unit(v: Int) = SceneUnit.ofInt(v)

    private fun base(id: String, roughness: Int = 0) = ElementBase(ElementId(id), style = ElementStyle(strokeColor = "#000000", roughness = roughness))

    private fun points(vararg xy: Int) = xy.toList().chunked(2).map { (x, y) -> ScenePoint(unit(x), unit(y)) }

    /** One of every kind, each in its own 100 x 100 cell of a row. */
    private fun everyKind(roughness: Int): List<SceneElement> = listOf(
        RectElement(base("rect", roughness), unit(10), unit(10), unit(80), unit(60)),
        DiamondElement(base("diamond", roughness), unit(120), unit(10), unit(80), unit(80)),
        EllipseElement(base("ellipse", roughness), unit(270), unit(50), unit(40), unit(30)),
        LineElement(base("line", roughness), points(310, 10, 390, 80)),
        ArrowElement(base("arrow", roughness), points(410, 50, 490, 50)),
        FreedrawElement(base("freedraw", roughness), points(510, 10, 530, 80, 550, 10, 570, 80, 590, 10)),
        TextElement(base("text", roughness), unit(610), unit(30), unit(120), unit(30), text = "Auth Service"),
        ImageElement(base("image", roughness), unit(710), unit(10), unit(80), unit(80), fileId = PICTURE_ID),
        FrameElement(base("frame", roughness), unit(810), unit(10), unit(80), unit(80)),
    )

    private fun sceneOf(elements: List<SceneElement>): SketchSceneV2 = elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }

    private fun decode(png: ByteArray) = ImageIO.read(ByteArrayInputStream(png))

    @Test
    fun export_should_Decode400x200_When_A200x100SceneIsExportedAt2x() {
        val scene = sceneOf(listOf(RectElement(base("r"), unit(0), unit(0), unit(200), unit(100))))
        val options = PngExportOptions(scale = 2, padding = 0.0, bounds = Bounds(0.0, 0.0, 200.0, 100.0))
        val png = exporter.export(scene, DrawListBuilder(StubMetrics), RenderStyle.Clean, options).getOrNull()!!
        assertEquals(PNG_SIGNATURE, png.take(PNG_SIGNATURE.size))
        val image = decode(png)
        assertEquals(400 to 200, image.width to image.height)
    }

    @Test
    fun export_should_DrawInkInsideEveryKindsBounds_And_MatchTheInProcessRender() {
        for (style in listOf(RenderStyle.Clean, RenderStyle.Sketchy)) {
            val roughness = if (style == RenderStyle.Sketchy) 1 else 0
            val scene = sceneOf(everyKind(roughness))
            val builder = DrawListBuilder(StubMetrics)
            val options = PngExportOptions(scale = 2, background = "#ffffff")
            val png = exporter.export(scene, builder, style, options).getOrNull()!!
            val image = decode(png)
            val content = scene.elementsInOrder.map { ElementBounds.of(it) }.reduce { a, b -> Bounds(minOf(a.minX, b.minX), minOf(a.minY, b.minY), maxOf(a.maxX, b.maxX), maxOf(a.maxY, b.maxY)) }
            val originX = content.minX - options.padding
            val originY = content.minY - options.padding

            for (element in scene.elementsInOrder) {
                val b = ElementBounds.of(element)
                val x0 = ((b.minX - originX) * 2).toInt().coerceIn(0, image.width - 1)
                val x1 = ((b.maxX - originX) * 2).toInt().coerceIn(x0 + 1, image.width)
                val y0 = ((b.minY - originY) * 2).toInt().coerceIn(0, image.height - 1)
                val y1 = ((b.maxY - originY) * 2).toInt().coerceIn(y0 + 1, image.height)
                var ink = 0
                for (y in y0 until y1) for (x in x0 until x1) if ((image.getRGB(x, y) and RGB_MASK) != WHITE_RGB) ink++
                val fraction = ink.toDouble() / ((x1 - x0) * (y1 - y0))
                assertTrue(fraction >= MIN_INK_FRACTION, "${element.id} in $style: only ${fraction * 100}% of its bounds is inked")
            }

            val expected = renderInProcess(builder.build(scene, style), originX, originY, image.width, image.height)
            var worst = 0
            for (y in 0 until image.height) for (x in 0 until image.width) worst = maxOf(worst, channelDistance(image.getRGB(x, y), expected[y * image.width + x]))
            assertTrue(worst <= MAX_CHANNEL_DRIFT, "$style: PNG differs from the in-process render by $worst per channel")
        }
    }

    private fun renderInProcess(ops: List<dev.stapler.stelekit.sketch.v2.render.DrawOp>, originX: Double, originY: Double, w: Int, h: Int): IntArray {
        val bitmap = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White)
            ScenePainter(resources = resources).paint(this, ops, SketchViewport.of(zoom = 2.0, panX = -originX * 2, panY = -originY * 2), null)
        }
        return IntArray(w * h).also { bitmap.readPixels(it) }
    }

    private fun channelDistance(a: Int, b: Int): Int =
        maxOf(abs(((a shr 16) and BYTE) - ((b shr 16) and BYTE)), abs(((a shr 8) and BYTE) - ((b shr 8) and BYTE)), abs((a and BYTE) - (b and BYTE)))

    @Test
    fun export_should_RefuseWithTheReason_When_AnImageSideExceedsTheCap() {
        val scene = sceneOf(listOf(RectElement(base("wide"), unit(0), unit(0), unit(WIDE), unit(100))))
        val error = exporter.export(scene, DrawListBuilder(StubMetrics), RenderStyle.Clean, PngExportOptions(scale = 3)).leftOrNull()
        val tooLarge = error as PngExportError.TooLarge
        assertEquals(16_000, tooLarge.maxSidePx)
        assertTrue(tooLarge.widthPx > tooLarge.maxSidePx)
        val atOne = exporter.export(scene, DrawListBuilder(StubMetrics), RenderStyle.Clean, PngExportOptions(scale = 1))
        assertTrue(atOne.isRight(), "the same scene fits at 1x")
    }

    @Test
    fun measure_should_RefuseBeforeAllocating_When_BothSidesFitButTheAreaExceedsTheCap() {
        val side = 15_000
        val scene = sceneOf(listOf(RectElement(base("big"), unit(0), unit(0), unit(side), unit(side))))
        val tooLarge = exporter.measure(scene, PngExportOptions(scale = 1, padding = 0.0)).leftOrNull() as PngExportError.TooLarge
        assertTrue(tooLarge.widthPx <= tooLarge.maxSidePx && tooLarge.heightPx <= tooLarge.maxSidePx, "each side is within the side cap")
        assertTrue(tooLarge.widthPx * tooLarge.heightPx > dev.stapler.stelekit.sketch.v2.scene.SceneLimits.Default.maxPngPixels)
    }

    @Test
    fun export_should_CropToTheSelection_And_KeepTransparencyWhenAskedFor() {
        val scene = sceneOf(listOf(RectElement(base("a"), unit(0), unit(0), unit(40), unit(40)), RectElement(base("b"), unit(500), unit(500), unit(60), unit(30))))
        val options = PngExportOptions(scale = 1, padding = 0.0, background = null, selection = setOf(ElementId("b")))
        val image = decode(exporter.export(scene, DrawListBuilder(StubMetrics), RenderStyle.Clean, options).getOrNull()!!)
        assertEquals(62 to 32, image.width to image.height, "60 x 30 plus the half stroke of 2 on each side")
        assertEquals(0, (image.getRGB(image.width / 2, image.height / 2) ushr ALPHA_SHIFT), "the unfilled middle of a transparent export has alpha 0")
    }

    @Test
    fun export_should_ReportNothingToExportAndAnInvalidScale() {
        val empty = SketchSceneV2.empty()
        assertEquals(PngExportError.NothingToExport, exporter.export(empty, DrawListBuilder(StubMetrics), RenderStyle.Clean).leftOrNull())
        val scene = sceneOf(listOf(RectElement(base("a"), unit(0), unit(0), unit(10), unit(10))))
        assertEquals(PngExportError.InvalidScale, exporter.export(scene, DrawListBuilder(StubMetrics), RenderStyle.Clean, PngExportOptions(scale = 4)).leftOrNull())
        assertEquals(SvgExportOptions.DEFAULT_PADDING, PngExportOptions().padding)
    }

    private companion object {
        const val PICTURE_ID = "picture"
        const val PICTURE_SIDE = 8
        const val WIDE = 6_000
        const val MIN_INK_FRACTION = 0.01
        const val MAX_CHANNEL_DRIFT = 2
        const val RGB_MASK = 0xFFFFFF
        const val WHITE_RGB = 0xFFFFFF
        const val BYTE = 0xFF
        const val ALPHA_SHIFT = 24
        val PNG_SIGNATURE = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }
    }
}
