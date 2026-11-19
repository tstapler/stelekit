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
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Spike 0.1.3b/c (wasmJs side): same probes as the JVM test, printed with the same labels. */
class DeterminismSpikeWasmTest {
    private val measurer by lazy { TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr) }

    private val bundled by lazy { FontFamily(Font("SpikeDejaVu", DeterminismSpikeFont.bytes)) }

    @Test
    fun textMetricsReport() {
        val lines = DeterminismSpikeSupport.metricsReport(measurer).map { "default|$it" } +
            DeterminismSpikeSupport.metricsReport(measurer, bundled).map { "bundled|$it" }
        println("SPIKE_TEXT_METRICS_WASM\n" + lines.joinToString("\n"))
        assertTrue(lines.isNotEmpty())
    }

    @Test
    fun offscreenBitmapAndPng() {
        val bmp = ImageBitmap(200, 100, ImageBitmapConfig.Argb8888)
        DeterminismSpikeSupport.drawFixture(bmp, measurer, bundled)
        val frac = DeterminismSpikeSupport.nonBlankFraction(bmp)
        val png = Image.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
        println("SPIKE_OFFSCREEN_WASM nonBlank=$frac pngBytes=${png?.size} pixelHash=${DeterminismSpikeSupport.pixelHash(bmp)}")
        assertTrue(frac > 0.0, "bitmap is blank")
    }
}
