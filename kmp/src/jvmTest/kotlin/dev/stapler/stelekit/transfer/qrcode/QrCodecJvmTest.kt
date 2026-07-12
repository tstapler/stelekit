package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Story 2.1.2 acceptance criteria: real ZXing encode -> render -> decode round trip, plus the
 * rotation-drift guard (ADR-002's documented failure mode).
 *
 * Renders each QR module as a [MODULE_SCALE]x[MODULE_SCALE] pixel block (not 1:1) — ZXing's
 * [com.google.zxing.common.HybridBinarizer] adaptively thresholds in pixel blocks and needs
 * several real pixels per module to binarize reliably, matching how an actual camera captures a
 * displayed QR code (never exactly 1 sensor pixel per module).
 */
class QrCodecJvmTest {
    private val moduleScale = 6

    @Test
    fun decode_should_ReturnOriginalBytes_When_EncodedMatrixReRenderedAndDecodedSameProcess() {
        val payload = byteArrayOf(1, 2, 3)
        val matrix = QrCodec.encode(payload)
        val frame = matrix.toCameraFrame()

        val decoded = QrCodec.decode(frame)

        assertContentEquals(payload, decoded)
    }

    @Test
    fun decode_should_ApplyRotationBeforeScanning_When_CameraFrameRotationDegreesIs90() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val matrix = QrCodec.encode(payload)

        // Pad the (always-square) QR matrix onto a wider, non-square canvas, mimicking a camera
        // sensor frame whose aspect ratio differs from the QR region. This makes an un-rotated
        // decode genuinely dimension-mismatched (not just visually sideways, which ZXing's
        // finder-pattern search tolerates on its own for a plain square image) — so the test
        // actually exercises rotateLuminanceClockwise rather than QR's inherent rotation
        // symmetry.
        val scaledSize = matrix.size * moduleScale
        val padding = moduleScale * 20
        val canvasWidth = scaledSize + padding
        val canvasHeight = scaledSize
        val upright = ByteArray(canvasWidth * canvasHeight) { 255.toByte() }
        for (y in 0 until canvasHeight) {
            for (x in 0 until scaledSize) {
                upright[y * canvasWidth + x] = if (matrix[x / moduleScale, y / moduleScale]) 0 else 255.toByte()
            }
        }

        // Simulate what the raw sensor captured: the upright canvas rotated -90 (== 270 CW).
        val (rawBytes, rawWidth, rawHeight) = rotateLuminanceClockwise(upright, canvasWidth, canvasHeight, 270)
        val frame = CameraFrame(luminanceBytes = rawBytes, width = rawWidth, height = rawHeight, rotationDegrees = 90)

        val decoded = QrCodec.decode(frame)

        assertContentEquals(payload, decoded)
    }

    @Test
    fun qrCodec_encode_should_RejectOrFail_When_PayloadExceedsFrameCapacity() {
        // Story 4.1.1 encode-time size guard: a payload beyond a single frame's capacity fails
        // cleanly (before ZXing ever attempts to render), not with an unclear WriterException or a
        // silently-produced larger/less-scannable matrix. Defense-in-depth only — the real upstream
        // guard is FountainEncoder's own per-fragment size check (Story 1.2.2).
        val oversized = ByteArray(700) { it.toByte() }

        val exception = assertFailsWith<IllegalArgumentException> { QrCodec.encode(oversized) }

        assertTrue(exception.message!!.contains("700"), "expected the actual size in the message, got: ${exception.message}")
        assertTrue(exception.message!!.contains("666"), "expected the max capacity in the message, got: ${exception.message}")
    }

    private fun QrMatrix.toCameraFrame(rotationDegrees: Int = 0): CameraFrame {
        val scaledSize = size * moduleScale
        val luminance = ByteArray(scaledSize * scaledSize)
        for (y in 0 until scaledSize) {
            for (x in 0 until scaledSize) {
                luminance[y * scaledSize + x] = if (get(x / moduleScale, y / moduleScale)) 0 else 255.toByte()
            }
        }
        return CameraFrame(luminanceBytes = luminance, width = scaledSize, height = scaledSize, rotationDegrees = rotationDegrees)
    }
}
