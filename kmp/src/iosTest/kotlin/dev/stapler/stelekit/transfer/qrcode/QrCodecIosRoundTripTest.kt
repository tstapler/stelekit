package dev.stapler.stelekit.transfer.qrcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 4.2.1b: same-process structural round-trip assertion for the iOS `QrCodec.encode` actual.
 *
 * A full encode -> decode cycle isn't available yet (iOS `QrCodec.decode` is deferred to Epic
 * 4.4), so this is the interim automated analogue of the manual cross-device demo referenced by
 * validation.md's `qrCodecIos_should_ProduceStructurallyValidMatrix_When_EncodedAgainstSharedFixture`:
 * it asserts the encoded [QrMatrix] is structurally valid (correct dimensions, a non-trivial bit
 * pattern) and deterministic, rather than asserting decoded bytes match the input.
 */
class QrCodecIosRoundTripTest {

    // Same fixture payload shape as QrCodecJvmRoundTripTest (Task 4.1.1b) so Desktop/iOS send are
    // exercised against comparable inputs.
    private val fixturePayload = (
        "# Meeting Notes\n\n" +
            "- Decided to ship the QR export feature\n" +
            "- Follow up next week on the fps/WCAG tradeoff\n"
        ).encodeToByteArray()

    @Test
    fun qrCodecIos_should_ProduceStructurallyValidMatrix_When_EncodedAgainstSharedFixture() {
        val matrix = QrCodec.encode(fixturePayload)

        assertTrue(matrix.size > 0)
        assertEquals(matrix.size * matrix.size, matrix.bits.size)

        val darkModuleCount = matrix.bits.count { it }
        // A real QR grid is never uniformly dark or uniformly light — a degenerate output here
        // (e.g. an unrasterized/blank CIImage) would show up as one of those two extremes.
        assertTrue(darkModuleCount > 0 && darkModuleCount < matrix.bits.size, "matrix is not a real QR bit pattern")
    }

    @Test
    fun qrCodecIos_should_ProduceIdenticalMatrix_When_EncodedTwiceWithSameInput() {
        val first = QrCodec.encode(fixturePayload)
        val second = QrCodec.encode(fixturePayload)

        assertEquals(first, second)
    }
}
