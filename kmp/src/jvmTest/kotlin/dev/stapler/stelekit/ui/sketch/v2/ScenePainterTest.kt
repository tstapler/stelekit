// jvmTest-reason: pixels
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.test.Test
import kotlin.test.assertTrue

/** Story 2.1.3 on the JVM rasteriser: text runs are drawn at the persisted position, and 500 elements pan within the frame budget. */
class ScenePainterTest {
    /** The draw list needs only `hasGlyph` (the painter draws persisted sizes), so a stub avoids the commonTest fake. */
    private object StubMetrics : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded
        override fun lineHeight(style: TextStyle): Double = style.fontSize
        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize
        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    private fun unit(v: Int) = SceneUnit.ofInt(v)

    private fun renderTo(bitmap: ImageBitmap, painter: ScenePainter, ops: List<DrawOp>, viewport: SketchViewport) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawRect(Color.White)
            painter.paint(this, ops, viewport, null)
        }
    }

    @Test
    fun textRun_should_DrawInkAtItsAnchorAndNothingElsewhere() {
        val text = TextElement(
            ElementBase(ElementId("t1"), style = ElementStyle(strokeColor = "#000000")),
            unit(20), unit(20), unit(120), unit(30), text = "Auth Service",
        )
        val scene = SketchSceneV2.empty().add(text).getOrNull()!!
        val ops = DrawListBuilder(StubMetrics).build(scene, RenderStyle.Clean)
        val painter = ScenePainter(resources = PainterResources(textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)))
        val bitmap = ImageBitmap(WIDTH, HEIGHT, ImageBitmapConfig.Argb8888)
        renderTo(bitmap, painter, ops, SketchViewport.of())
        val px = IntArray(WIDTH * HEIGHT).also { bitmap.readPixels(it) }
        fun inked(x0: Int, y0: Int, x1: Int, y1: Int) = (y0 until y1).sumOf { y -> (x0 until x1).count { x -> px[y * WIDTH + x] != WHITE } }
        assertTrue(inked(20, 20, 140, 50) > MIN_INK, "the text run leaves ink in its box")
        assertTrue(inked(0, 100, WIDTH, HEIGHT) == 0, "nothing is drawn far below the text")
    }

    @Test
    fun pan500Elements_should_StayWithinTheFrameBudget() {
        val scene = (0 until ELEMENTS).fold(SketchSceneV2.empty()) { s, i ->
            val rect = RectElement(
                ElementBase(ElementId("r$i"), style = ElementStyle(backgroundColor = "#e8f0fe", roughness = 0)),
                unit((i % COLUMNS) * STEP_X), unit((i / COLUMNS) * STEP_Y), unit(RECT_W), unit(RECT_H),
            )
            s.add(rect).getOrNull()!!
        }
        val ops = DrawListBuilder(StubMetrics).build(scene, RenderStyle.Clean)
        val painter = ScenePainter()
        val bitmap = ImageBitmap(BENCH_WIDTH, BENCH_HEIGHT, ImageBitmapConfig.Argb8888)
        val reference = referenceFrameMs()
        val measured = ArrayList<Double>()
        for (frame in 0 until WARMUP + FRAMES) {
            val t0 = System.nanoTime()
            renderTo(bitmap, painter, ops, SketchViewport.of(panX = -frame * PAN_X, panY = -frame * PAN_Y))
            if (frame >= WARMUP) measured += (System.nanoTime() - t0) / NANOS_PER_MS
        }
        val p95 = p95(measured)
        val referenceP95 = p95(reference)
        println("SKETCH-PAINTER-RESULT {\"painterP95Ms\":$p95,\"referenceRect500P95Ms\":$referenceP95,\"ratio\":${p95 / referenceP95}}")
        assertTrue(p95 <= referenceP95 * MAX_RATIO_TO_PLAIN_CANVAS, "painter p95 $p95 ms against the plain canvas $referenceP95 ms (x$MAX_RATIO_TO_PLAIN_CANVAS)")
        assertTrue(p95 <= CEILING_MS, "painter p95 $p95 ms is over the $CEILING_MS ms ceiling")
    }

    /**
     * The same 500 rectangles, filled and stroked, drawn straight to a canvas as prebuilt paths with no painter, measured in
     * the same run (Task 0.1.7b's reference, with the primitive the painter uses so only the painter's own overhead is compared).
     */
    private fun referenceFrameMs(): List<Double> {
        val bitmap = ImageBitmap(BENCH_WIDTH, BENCH_HEIGHT, ImageBitmapConfig.Argb8888)
        val scope = CanvasDrawScope()
        val paths = (0 until ELEMENTS).map { i ->
            androidx.compose.ui.graphics.Path().apply {
                addRect(
                    androidx.compose.ui.geometry.Rect(
                        androidx.compose.ui.geometry.Offset(((i % COLUMNS) * STEP_X).toFloat(), ((i / COLUMNS) * STEP_Y).toFloat()),
                        Size(RECT_W.toFloat(), RECT_H.toFloat()),
                    ),
                )
            }
        }
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(REFERENCE_STROKE_WIDTH)
        val times = ArrayList<Double>()
        for (frame in 0 until WARMUP + FRAMES) {
            val t0 = System.nanoTime()
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(BENCH_WIDTH.toFloat(), BENCH_HEIGHT.toFloat())) {
                drawRect(Color.White)
                withTransform({ translate(-frame * PAN_X.toFloat(), -frame * PAN_Y.toFloat()) }) {
                    for (path in paths) {
                        drawPath(path, Color(REFERENCE_FILL))
                        drawPath(path, Color(REFERENCE_STROKE), style = stroke)
                    }
                }
            }
            if (frame >= WARMUP) times += (System.nanoTime() - t0) / NANOS_PER_MS
        }
        return times
    }

    private fun p95(xs: List<Double>): Double = xs.sorted()[(xs.size * P95 - 1).toInt().coerceAtLeast(0)]

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 120
        const val WHITE = 0xFFFFFFFF.toInt()
        const val MIN_INK = 50
        const val ELEMENTS = 500
        const val COLUMNS = 25
        const val STEP_X = 50
        const val STEP_Y = 38
        const val RECT_W = 44
        const val RECT_H = 32
        const val BENCH_WIDTH = 1280
        const val BENCH_HEIGHT = 800
        const val WARMUP = 60
        const val FRAMES = 120
        const val PAN_X = 3.0
        const val PAN_Y = 2.0
        const val NANOS_PER_MS = 1e6
        const val P95 = 0.95
        const val REFERENCE_FILL = 0xFFE8F0FE
        const val REFERENCE_STROKE = 0xFF1E1E1E
        const val REFERENCE_STROKE_WIDTH = 2f
        const val MAX_RATIO_TO_PLAIN_CANVAS = 1.5
        const val CEILING_MS = 24.0
    }
}
