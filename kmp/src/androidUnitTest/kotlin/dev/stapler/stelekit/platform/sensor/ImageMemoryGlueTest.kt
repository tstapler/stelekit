package dev.stapler.stelekit.platform.sensor

import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import dev.stapler.stelekit.platform.ml.decodeAndResize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Android-side glue for the image-memory limits: capture cap, OOM retry, ML Kit sub-sampled decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageMemoryGlueTest {

    @Test
    fun `capture use case is built with the capped resolution selector`() {
        val capture = buildCappedImageCapture()
        assertEquals(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, capture.captureMode)
        assertNotNull(cappedCaptureResolutionSelector())
    }

    @Test
    fun `out of memory doubles the sample size and retries`() {
        val seen = mutableListOf<Int>()
        val result = retryOnOutOfMemory(2) { sample ->
            seen += sample
            if (seen.size < 3) throw OutOfMemoryError("test")
            "decoded"
        }
        assertEquals("decoded", result)
        assertEquals(listOf(2, 4, 8), seen)
    }

    @Test
    fun `out of memory gives up after three retries`() {
        var calls = 0
        assertFailsWith<OutOfMemoryError> {
            retryOnOutOfMemory(1) { calls++; throw OutOfMemoryError("test") }
        }
        assertEquals(4, calls)
    }

    @Test
    fun `labeler decode returns small images unscaled and shrinks large ones`() {
        assertEquals(64, assertNotNull(decodeAndResize(png(64, 64), maxSize = 512)).width)
        val big = assertNotNull(decodeAndResize(png(2000, 1000), maxSize = 512))
        assertTrue(big.width <= 512 && big.height <= 512, "${big.width}x${big.height}")
    }

    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
}
