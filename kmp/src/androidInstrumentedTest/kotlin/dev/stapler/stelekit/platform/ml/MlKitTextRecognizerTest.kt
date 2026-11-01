// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.asset.pipeline.OCR_MAX_EDGE_PX
import dev.stapler.stelekit.platform.image.PlatformImageDownscaler
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real ML Kit OCR on the Android runtime (Robolectric cannot run ML Kit). Task 5.3c: a known image
 * returns its printed text, and a 40 MP photo is read after the pipeline's downscale step without
 * running out of heap. Peak heap is written to `filesDir/ocr-measure.txt` because logcat is not
 * available to the owner.
 */
@RunWith(AndroidJUnit4::class)
class MlKitTextRecognizerTest {
    private val recognizer = MlKitTextRecognizer()

    private companion object {
        const val SAMPLE_INTERVAL_MS = 2L
        const val MB_SHIFT = 20
    }

    private fun render(width: Int, height: Int, config: Bitmap.Config, textSizePx: Float, text: String, jpeg: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, config)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = textSizePx
            }
            canvas.drawText(text, textSizePx / 2, height / 2f, paint)
            val out = ByteArrayOutputStream()
            if (jpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun record(line: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "ocr-measure.txt").appendText(line + "\n")
    }

    @Test
    fun knownImage_returnsItsPrintedText() {
        val png = render(900, 300, Bitmap.Config.ARGB_8888, 96f, "Invoice 4821", jpeg = false)

        val text = runBlocking { recognizer.recognize(png) }.getOrNull()

        assertNotNull(text, "recognition must succeed")
        assertTrue("Invoice" in text && "4821" in text, "recognized text was: '$text'")
    }

    /** Peak of Java heap + native allocations (Bitmap pixels are native on API 26+), sampled every 2 ms. */
    private class PeakSampler : Thread("peak-sampler") {
        @Volatile var running = true
        @Volatile var peakBytes = 0L
        @Volatile var peakJavaBytes = 0L
        override fun run() {
            while (running) {
                val rt = Runtime.getRuntime()
                val java = rt.totalMemory() - rt.freeMemory()
                peakJavaBytes = maxOf(peakJavaBytes, java)
                peakBytes = maxOf(peakBytes, java + Debug.getNativeHeapAllocatedSize())
                sleep(SAMPLE_INTERVAL_MS)
            }
        }
    }

    @Test
    fun fortyMegapixelPhoto_isReadAfterDownscale_withoutRunningOutOfHeap() {
        System.gc()
        val baseline = usedHeapBytes() + Debug.getNativeHeapAllocatedSize()
        val sampler = PeakSampler().also { it.start() }
        val photo: ByteArray
        val scaled: ByteArray?
        val text: String?
        try {
            photo = render(7000, 5714, Bitmap.Config.RGB_565, 700f, "Invoice 4821", jpeg = true)
            scaled = PlatformImageDownscaler.downscale(photo, listOf(OCR_MAX_EDGE_PX))?.firstOrNull()
            text = scaled?.let { runBlocking { recognizer.recognize(it) }.getOrNull() }
        } finally {
            sampler.running = false
            sampler.join()
        }

        record(
            "ocr40mp sourceBytes=${photo.size} scaledBytes=${scaled?.size} maxHeapMb=${Runtime.getRuntime().maxMemory() shr MB_SHIFT} " +
                "baselineMb=${baseline shr MB_SHIFT} peakJavaMb=${sampler.peakJavaBytes shr MB_SHIFT} " +
                "peakJavaPlusNativeMb=${sampler.peakBytes shr MB_SHIFT} text='${text?.trim()}'",
        )
        assertNotNull(scaled, "the 40 MP decode must produce a downscaled copy")
        assertNotNull(text, "recognition must succeed")
        assertTrue("Invoice" in text && "4821" in text, "recognized text was: '$text'")
    }

    @Test
    fun undecodableBytes_returnEmptyText_notACrash() {
        val result = runBlocking { recognizer.recognize(byteArrayOf(1, 2, 3)) }

        assertTrue(result.isRight() && result.getOrNull() == "", "garbage bytes decode to nothing, so nothing is read")
    }
}
