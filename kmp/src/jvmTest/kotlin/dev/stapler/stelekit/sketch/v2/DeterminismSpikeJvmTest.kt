// jvmTest-reason: pixels
package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Spike 0.1.3b/c (JVM side): text metrics report, offscreen bitmap, PNG encode. */
class DeterminismSpikeJvmTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    private val bundled = FontFamily(Font("SpikeDejaVu", DeterminismSpikeFont.bytes))

    @Test
    fun textMetricsReport() {
        val lines = DeterminismSpikeSupport.metricsReport(measurer).map { "default|$it" } +
            DeterminismSpikeSupport.metricsReport(measurer, bundled).map { "bundled|$it" }
        val text = lines.joinToString("\n")
        println("SPIKE_TEXT_METRICS_JVM\n$text")
        File("build/reports").also { it.mkdirs() }.resolve("spike-text-metrics-jvm.txt").writeText(text)
        assertTrue(lines.isNotEmpty())
    }

    @Test
    fun offscreenBitmapAndPng() {
        val bmp = ImageBitmap(200, 100, ImageBitmapConfig.Argb8888)
        DeterminismSpikeSupport.drawFixture(bmp, measurer, bundled)
        val frac = DeterminismSpikeSupport.nonBlankFraction(bmp)
        val png = Image.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
        println("SPIKE_OFFSCREEN_JVM nonBlank=$frac pngBytes=${png.size} pixelHash=${DeterminismSpikeSupport.pixelHash(bmp)}")
        val px = IntArray(200 * 100).also { bmp.readPixels(it) }
        File("build/reports").also { it.mkdirs() }.resolve("spike-pixels-jvm.txt").writeText(px.joinToString("\n") { it.toUInt().toString(16) })
        assertTrue(frac > 0.0, "bitmap is blank")
        assertTrue(png.size > 8 && png[1] == 'P'.code.toByte(), "not a PNG")
    }
}
