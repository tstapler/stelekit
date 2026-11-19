
package dev.stapler.stelekit.sketch.v2.baseline

import dev.stapler.stelekit.ui.annotate.performanceNowMs

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task 0.1.7b throwaway reference harness: p95 frame time of a 120-frame pan, (a) empty and
 * (b) with 500 rounded rectangles drawn per frame, into a software-rasterised ImageBitmap on the host
 * (no display, no GPU). Figures are relative references for the 1.5x rule, not device frame times.
 * Prints one `SKETCH-REF-RESULT {json}` line; also writes it to -Dsketch.ref.out when set.
 */
class CanvasReferenceWasmBenchmark {
    private val width = 1280
    private val height = 800
    private val frames = 120
    private val warmupFrames = 60

    private fun framesMs(rects: Int): List<Double> {
        val bitmap = ImageBitmap(width, height)
        val scope = CanvasDrawScope()
        val canvas = Canvas(bitmap)
        val size = Size(width.toFloat(), height.toFloat())
        val times = ArrayList<Double>(frames)
        for (frame in 0 until warmupFrames + frames) {
            val panX = frame * 3f
            val panY = frame * 2f
            val t0 = performanceNowMs()
            scope.draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
                drawRect(Color.White)
                for (i in 0 until rects) {
                    val x = (i % 25) * 50f - panX % 50f
                    val y = (i / 25) * 38f - panY % 38f
                    drawRoundRect(Color(0xFFE8F0FE), Offset(x, y), Size(44f, 32f), CornerRadius(6f))
                    drawRoundRect(Color(0xFF1E1E2E), Offset(x, y), Size(44f, 32f), CornerRadius(6f), Stroke(1.5f))
                }
            }
            val ms = performanceNowMs() - t0
            if (frame >= warmupFrames) times += ms
        }
        return times
    }

    private fun p95(xs: List<Double>): Double = xs.sorted()[((xs.size * 0.95).toInt() - 1).coerceAtLeast(0)]
    private fun p50(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    @Test
    fun recordsEmptyCanvasAndRect500Reference() {
        val empty = framesMs(0)
        val rect500 = framesMs(500)
        val json = """{"emptyP95Ms":${p95(empty)},"emptyP50Ms":${p50(empty)},"rect500P95Ms":${p95(rect500)},"rect500P50Ms":${p50(rect500)},"ratioP95":${p95(rect500) / p95(empty)},"frames":$frames,"warmupFrames":$warmupFrames,"canvas":"${width}x$height","host":"wasmJs headless chrome"}"""
        println("SKETCH-REF-RESULT $json")
        assertTrue(p95(empty) > 0.0 && p95(rect500) > 0.0)
    }
}
