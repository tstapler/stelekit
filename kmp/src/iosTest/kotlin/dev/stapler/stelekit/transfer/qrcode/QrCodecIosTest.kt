package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Story 4.2.1: iOS [QrCodec] actual — encode via CoreImage `CIQRCodeGenerator`; decode
 * unimplemented (Epic 4.4 deferral). See `QrCodecIosRoundTripTest` for the structural
 * same-process assertion (validation.md's interim stand-in for a full decode-back round trip).
 */
class QrCodecIosTest {

    @Test
    fun encode_should_ProduceQrMatrix_When_EncodingFixturePayloadViaCoreImage() {
        val payload = (
            "# Meeting Notes\n\n" +
                "- Decided to ship the QR export feature\n" +
                "- Follow up next week on the fps/WCAG tradeoff\n"
            ).encodeToByteArray()

        val matrix = QrCodec.encode(payload)

        // A QR code's smallest legal version (1) is 21x21 modules; CoreImage never produces a
        // smaller grid. Assert plausible bounds rather than an exact size since the number of
        // modules depends on payload length + "M" error correction (ADR-001).
        assertTrue(matrix.size >= 21, "expected a plausible QR module count, got ${matrix.size}")
        assertTrue(matrix.bits.isNotEmpty())
        assertTrue(matrix.bits.size == matrix.size * matrix.size)
        // A real QR code is never all-light or all-dark — it always has finder patterns.
        assertTrue(matrix.bits.any { it }, "matrix has no dark modules at all")
        assertTrue(matrix.bits.any { !it }, "matrix has no light modules at all")
    }

    @Test
    fun decode_should_ThrowNotImplementedError_When_DecodeCalled_BecauseReceiveIsDeferred() {
        val frame = CameraFrame(luminanceBytes = ByteArray(4), width = 2, height = 2, rotationDegrees = 0)

        assertFailsWith<NotImplementedError> { QrCodec.decode(frame) }
    }
}
