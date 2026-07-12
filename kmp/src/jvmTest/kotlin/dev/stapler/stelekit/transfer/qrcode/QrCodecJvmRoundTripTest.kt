package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Integration-level round trip through the real ZXing library (Task 2.1.2's automated analogue
 * of a manual cross-device demo; also referenced by Story 4.1.1's desktop send path) — a page-
 * markdown-sized payload, same-process encode -> decode.
 */
class QrCodecJvmRoundTripTest {

    @Test
    fun qrCodec_should_MatchOriginalBytes_When_EncodedRenderedAndDecodedSameProcess() {
        val payload = (
            "# Meeting Notes\n\n" +
                "- Decided to ship the QR export feature\n" +
                "- Follow up next week on the fps/WCAG tradeoff\n"
            ).encodeToByteArray()

        val matrix = QrCodec.encode(payload)
        // Render each module as a 6x6 pixel block — HybridBinarizer needs several real pixels per
        // module to binarize reliably (see QrCodecJvmTest KDoc).
        val moduleScale = 6
        val scaledSize = matrix.size * moduleScale
        val luminance = ByteArray(scaledSize * scaledSize)
        for (y in 0 until scaledSize) {
            for (x in 0 until scaledSize) {
                luminance[y * scaledSize + x] = if (matrix[x / moduleScale, y / moduleScale]) 0 else 255.toByte()
            }
        }
        val frame = CameraFrame(luminanceBytes = luminance, width = scaledSize, height = scaledSize, rotationDegrees = 0)

        val decoded = QrCodec.decode(frame)

        assertContentEquals(payload, decoded)
    }
}
