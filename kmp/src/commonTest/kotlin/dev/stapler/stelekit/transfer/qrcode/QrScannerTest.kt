package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Story 2.1.4 (Task 2.1.4b) acceptance criteria: [QrScanner.decode] differentiates "found a valid
 * SteleKit chunk" from "found a QR, but not ours" from "no QR at all" — see [ScanResult].
 *
 * Exercises the real [QrCodec] actual (available on JVM/Android in v1 — see ADR-003/ADR-005) via
 * [QrScanner], not a reimplementation of its two-step composition.
 */
class QrScannerTest {

    @Test
    fun decode_should_ReturnDecoded_When_FrameContainsValidSteleKitChunk() {
        val chunk = FountainChunk(
            transferId = TransferId(1),
            chunkIndex = ChunkIndex(0),
            payloadLen = 3,
            payloadCrc = PayloadChecksum(0),
            fragment = byteArrayOf(1, 2, 3),
        )
        val wireBytes = ChunkFrameCodec.encode(chunk)
        val frame = wireBytes.toCameraFrame()

        val result = QrScanner.decode(frame)

        val decoded = assertIs<ScanResult.Decoded>(result)
        assertEquals(chunk, decoded.chunk)
    }

    @Test
    fun decode_should_ReturnNotSteleKitCode_When_QrFoundButBytesFailChunkFrameCodec() {
        // A real QR is present and decodes fine at the QrCodec layer, but its bytes are not a
        // valid SteleKit wire frame (bad magic byte) — ChunkFrameCodec.decode must reject it.
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val frame = garbage.toCameraFrame()

        val result = QrScanner.decode(frame)

        assertIs<ScanResult.NotSteleKitCode>(result)
    }

    @Test
    fun decode_should_ReturnNoCodeDetected_When_FrameHasNoQrCodeAtAll() {
        // Uniform luminance (no QR pattern present at all).
        val blank = CameraFrame(luminanceBytes = ByteArray(64) { 255.toByte() }, width = 8, height = 8, rotationDegrees = 0)

        val result = QrScanner.decode(blank)

        assertIs<ScanResult.NoCodeDetected>(result)
    }

    private fun ByteArray.toCameraFrame(): CameraFrame {
        val matrix = QrCodec.encode(this)
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
        return CameraFrame(luminanceBytes = luminance, width = scaledSize, height = scaledSize, rotationDegrees = 0)
    }
}
