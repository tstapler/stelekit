package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Shared helpers for Spikes 0.1.3b/c/e: one fixture and one measurement routine for every target. */
object DeterminismSpikeSupport {
    val latinStrings = listOf("Auth Service", "line one\nline two")
    val cjkStrings = listOf("データ", "你好")
    private val wrapProbe = "The quick brown fox jumps over the lazy dog"

    data class Measured(val text: String, val width: Int, val height: Int, val lines: Int, val lineEnds: List<Int>)

    private fun measure(m: TextMeasurer, text: String, maxWidth: Int?, family: FontFamily?): Measured {
        val c = if (maxWidth == null) Constraints() else Constraints(maxWidth = maxWidth)
        val r = m.measure(text, TextStyle(fontSize = 20.sp, fontFamily = family), overflow = TextOverflow.Clip, constraints = c)
        return Measured(text, r.size.width, r.size.height, r.lineCount, (0 until r.lineCount).map { r.getLineEnd(it) })
    }

    /** One line per probe: `label|width|height|lines|lineEnds`. Identical shape on every target for diffing. */
    fun metricsReport(m: TextMeasurer, family: FontFamily? = null): List<String> {
        val out = ArrayList<String>()
        for (s in latinStrings + cjkStrings) {
            val a = measure(m, s, null, family); out += "free|${s.replace("\n", "\\n")}|${a.width}|${a.height}|${a.lines}|${a.lineEnds}"
        }
        for (s in latinStrings + cjkStrings + wrapProbe) {
            val a = measure(m, s, 120, family); out += "wrap120|${s.replace("\n", "\\n")}|${a.width}|${a.height}|${a.lines}|${a.lineEnds}"
        }
        return out
    }

    fun drawFixture(bitmap: ImageBitmap, m: TextMeasurer, family: FontFamily? = null) {
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawRect(Color.White)
            drawRect(Color.Black, topLeft = Offset(10f, 10f), size = Size(80f, 40f), style = Stroke(width = 2f))
            drawText(m, "Auth", topLeft = Offset(100f, 10f), style = TextStyle(fontSize = 20.sp, color = Color.Black, fontFamily = family))
        }
    }

    /** Fraction of pixels that are not opaque white (0..1). */
    fun nonBlankFraction(bitmap: ImageBitmap): Double {
        val buf = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(buf)
        return buf.count { it != 0xFFFFFFFF.toInt() }.toDouble() / buf.size
    }

    fun pixelHash(bitmap: ImageBitmap): Int {
        val buf = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(buf)
        var h = -0x7ee3623b
        for (p in buf) h = (h xor p) * 16777619
        return h
    }
}
