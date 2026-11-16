package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Spike 0.1.3e: draw the rect + text fixture under Robolectric native graphics; dump pixels for the JVM diff. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPixelSpikeTest {
    private val measurer = TextMeasurer(
        createFontFamilyResolver(ApplicationProvider.getApplicationContext<android.content.Context>()),
        Density(1f), LayoutDirection.Ltr,
    )

    private val bundled: FontFamily by lazy {
        val f = File.createTempFile("spike-dejavu", ".ttf").also { it.deleteOnExit(); it.writeBytes(DeterminismSpikeFont.bytes) }
        FontFamily(Font(f))
    }

    @Test
    fun fixtureRendersAndMetricsAreReported() {
        val lines = DeterminismSpikeSupport.metricsReport(measurer).map { "default|$it" } +
            DeterminismSpikeSupport.metricsReport(measurer, bundled).map { "bundled|$it" }
        println("SPIKE_TEXT_METRICS_ANDROID_ROBOLECTRIC\n" + lines.joinToString("\n"))
        val bmp = ImageBitmap(200, 100, ImageBitmapConfig.Argb8888)
        DeterminismSpikeSupport.drawFixture(bmp, measurer, bundled)
        val frac = DeterminismSpikeSupport.nonBlankFraction(bmp)
        val px = IntArray(200 * 100).also { bmp.readPixels(it) }
        val out = File("build/reports").also { it.mkdirs() }.resolve("spike-pixels-android-robolectric.txt")
        out.writeText(px.joinToString("\n") { it.toUInt().toString(16) })
        println("SPIKE_OFFSCREEN_ANDROID_ROBOLECTRIC nonBlank=$frac pixelHash=${DeterminismSpikeSupport.pixelHash(bmp)}")
        assertTrue(frac > 0.0, "bitmap is blank under Robolectric")
    }
}
