@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.annotate

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import dev.stapler.stelekit.ui.annotate.ImageEncoder
import dev.stapler.stelekit.ui.annotate.JPEG_DATA_URL_PREFIX
import dev.stapler.stelekit.ui.annotate.performanceNowMs
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real (headless-browser) wasmJs regression coverage for [ImageEncoder]'s Canvas-2D JPEG encoder.
 * Runs in Karma against a real Canvas 2D implementation — `./gradlew :kmp:wasmJsBrowserTest
 * -PenableJs=true` — unlike `jvmTest`, which cannot exercise this platform's actual code path.
 *
 * Fails against the pre-fix stub (`ByteArray(0)` unconditionally) and passes against the real
 * Canvas 2D implementation.
 */
class ImageEncoderWasmJsTest {

    @Test
    fun encodeToJpeg_smallOpaqueBitmap_returnsValidJpegBytes() {
        val bitmap = ImageBitmap(10, 10)

        val bytes = ImageEncoder.encodeToJpeg(bitmap, quality = 90)

        assertTrue(bytes.isNotEmpty(), "Expected non-empty JPEG bytes, got empty ByteArray")
        assertTrue(
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
            "Expected JPEG SOI marker (0xFF 0xD8), got ${bytes.take(2)}",
        )
    }

    // encodeToJpeg's width<=0||height<=0 guard has no test at this boundary: ImageBitmap(0, 0)
    // throws IllegalArgumentException on construction on wasmJs too (measured — same behavior as
    // the JVM target, see AnnotationExporterTest's equivalent note), so the guard is defensive
    // dead code reachable only via a hypothetical negative-dimension ImageBitmap, which the
    // public API also can't construct.

    @Test
    fun encodeToJpeg_realisticImage_completesWithinDocumentedUxFreezeCeiling() {
        // toPixelMap()'s Skia bulk pixel read alone costs ~1.1s at 3000x2000 (measured), so a
        // <=500ms full-pipeline budget isn't achievable synchronously on the main thread — see
        // ADR-001's OffscreenCanvas+Worker escalation path. Asserted here against the harder
        // ">3-4s = true freeze" ceiling instead (research/ux.md); the canvas/JS portion is
        // separately logged at ~50-100ms via println in ImageEncoder.wasmJs.kt.
        val bitmap = ImageBitmap(3000, 2000)
        val start = performanceNowMs()

        val bytes = ImageEncoder.encodeToJpeg(bitmap, quality = 90)

        val elapsedMs = performanceNowMs() - start
        assertTrue(bytes.isNotEmpty(), "Expected non-empty JPEG bytes for a 3000x2000 image")
        assertTrue(
            elapsedMs <= 3000.0,
            "Expected encodeToJpeg for a 3000x2000 image to stay comfortably under the " +
                ">3-4s true-freeze ceiling, took ${elapsedMs}ms",
        )
    }

    @Test
    fun encodeToJpeg_areaExceedsCanvasCeiling_returnsEmptyByteArrayBeforeAnyCanvasCall() {
        // 5000x3356 ~= 16,780,000px, just over Chromium's documented 16,777,216px canvas-area cap.
        val bitmap = ImageBitmap(5000, 3356)

        val bytes = ImageEncoder.encodeToJpeg(bitmap, quality = 90)

        assertTrue(bytes.isEmpty(), "Expected an oversized bitmap to be rejected by the area guard")
    }

    // AC5 / research/pitfalls.md's #1-ranked risk: does toPixelMap() on wasmJs return premultiplied
    // or straight alpha? flattenToOpaqueRgba's own commonTest only proves its formula is internally
    // self-consistent — it can't catch a wrong alpha-convention assumption, since both "expected"
    // and "actual" would derive from the same formula. These two tests instead compare the real
    // encodeToJpeg pipeline's output against an INDEPENDENT ground truth: the browser's own native
    // Canvas 2D SRC_OVER compositing (`ctx.fillStyle = 'rgba(...)'`), which involves no Kotlin math
    // at all. If toPixelMap() were premultiplied and flattenToOpaqueRgba assumed straight alpha, the
    // pipeline's output would be roughly alpha^2-scaled versus this reference — far outside the
    // JPEG-quantization tolerance used here.

    @Test
    fun encodeToJpeg_areaAlphaFill_matchesNativeCanvasCompositingWithinJpegTolerance() = runTest {
        assertSemiTransparentFillMatchesNativeReference(alpha = 0.3f) // AnnotationExporter's AREA fill alpha
    }

    @Test
    fun encodeToJpeg_labelBackgroundAlpha_matchesNativeCanvasCompositingWithinJpegTolerance() = runTest {
        assertSemiTransparentFillMatchesNativeReference(alpha = 0xCC / 255f) // AnnotationExporter's LABEL background alpha
    }

    private suspend fun assertSemiTransparentFillMatchesNativeReference(alpha: Float) {
        val r = 0x1E
        val g = 0x88
        val b = 0xE5
        val size = 8

        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.style = PaintingStyle.Fill
        paint.color = Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = alpha)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        val encoded = ImageEncoder.encodeToJpeg(bitmap, quality = 95)
        assertTrue(encoded.isNotEmpty(), "Expected non-empty JPEG bytes")

        val actual = decodedCenterPixel(encoded, size)
        val reference = nativeReferencePixel(r, g, b, alpha.toDouble())

        val tolerance = 20
        for (channel in 0..2) {
            assertTrue(
                abs(actual[channel] - reference[channel]) <= tolerance,
                "Channel $channel mismatch at alpha=$alpha: pipeline produced ${actual.toList()}, " +
                    "native Canvas2D reference is ${reference.toList()} (tolerance $tolerance) — " +
                    "possible straight/premultiplied alpha mismatch in flattenToOpaqueRgba",
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun decodedCenterPixel(jpegBytes: ByteArray, size: Int): IntArray {
        val dataUrl = JPEG_DATA_URL_PREFIX + Base64.Default.encode(jpegBytes)
        val pixel = decodedPixelPromise(dataUrl, size / 2, size / 2).await<JsAny>()
        return IntArray(3) { jsArrayIntAt(pixel, it) }
    }

    private fun nativeReferencePixel(r: Int, g: Int, b: Int, alpha: Double): IntArray {
        val pixel = nativeReferencePixelSync(r, g, b, alpha)
        return IntArray(3) { jsArrayIntAt(pixel, it) }
    }
}

/** Loads [dataUrl] as an `<img>`, draws it to a canvas, and reads back the pixel at ([x], [y]). */
private fun decodedPixelPromise(dataUrl: String, x: Int, y: Int): kotlin.js.Promise<JsAny> = js(
    """
    (function() {
        return new Promise(function(resolve, reject) {
            const img = new Image();
            img.onload = function() {
                try {
                    const c = document.createElement('canvas');
                    c.width = img.naturalWidth;
                    c.height = img.naturalHeight;
                    const ctx = c.getContext('2d');
                    ctx.drawImage(img, 0, 0);
                    const d = ctx.getImageData(x, y, 1, 1).data;
                    resolve([d[0], d[1], d[2]]);
                } catch (e) {
                    reject(e);
                }
            };
            img.onerror = function(e) { reject(e); };
            img.src = dataUrl;
        });
    })()
    """,
)

/**
 * Composites `rgba(r, g, b, alpha)` over opaque black using the browser's own native Canvas 2D
 * SRC_OVER implementation — an independent reference with zero Kotlin compositing math involved.
 */
private fun nativeReferencePixelSync(r: Int, g: Int, b: Int, alpha: Double): JsAny = js(
    """
    (function() {
        const c = document.createElement('canvas');
        c.width = 1;
        c.height = 1;
        const ctx = c.getContext('2d');
        ctx.fillStyle = 'black';
        ctx.fillRect(0, 0, 1, 1);
        ctx.fillStyle = 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
        ctx.fillRect(0, 0, 1, 1);
        const d = ctx.getImageData(0, 0, 1, 1).data;
        return [d[0], d[1], d[2]];
    })()
    """,
)

private fun jsArrayIntAt(arr: JsAny, index: Int): Int = js("arr[index]")
