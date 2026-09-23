package dev.stapler.stelekit.annotate

import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.ui.annotate.ImageEncoder
import dev.stapler.stelekit.ui.annotate.performanceNowMs
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
}
