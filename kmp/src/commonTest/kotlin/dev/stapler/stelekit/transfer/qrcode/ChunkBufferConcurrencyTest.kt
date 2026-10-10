package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 3.3.4: [ChunkBuffer] bound to a [TransferId] must ignore (not error on) frames carrying a
 * different [TransferId] once a session is active. `ChunkFrameCodec`'s own version-byte rejection
 * is already covered by `ChunkFrameCodecTest.chunkFrameCodec_decode_should_ReturnNull_When_MagicByteOrVersionOrChunkCrcIsInvalid`
 * (Story 1.2.1) — verified, not duplicated here.
 */
class ChunkBufferConcurrencyTest {

    private fun chunkFor(transferId: Int, index: Int, fragment: ByteArray) = FountainChunk(
        transferId = TransferId(transferId),
        chunkIndex = ChunkIndex(index),
        payloadLen = 9,
        payloadCrc = PayloadChecksum(0),
        fragment = fragment,
    )

    @Test
    fun chunkBuffer_should_IgnoreFrame_When_TransferIdDiffersFromActiveSession() {
        val buffer = ChunkBuffer(maxPayloadBytes = 65536)

        // Binds the buffer to TransferId(7) on the first accepted chunk.
        val boundAccepted = buffer.accept(chunkFor(transferId = 7, index = 0, fragment = byteArrayOf(1, 2, 3))).getOrNull()
        assertTrue(boundAccepted == true, "the first chunk must bind and be accepted")
        val coverageAfterBind = buffer.coverage()

        // A frame for a different TransferId(9) while TransferId(7) is active must be ignored.
        val rejected = buffer.accept(chunkFor(transferId = 9, index = 1, fragment = byteArrayOf(4, 5, 6))).getOrNull()
        assertFalse(rejected == true, "a frame for a different TransferId must be rejected, not accepted")
        assertEquals(coverageAfterBind, buffer.coverage(), "a rejected foreign-TransferId frame must not affect coverage")

        // The active session's own stream is unaffected — it can still make progress afterward.
        val stillAccepted = buffer.accept(chunkFor(transferId = 7, index = 1, fragment = byteArrayOf(7, 8, 9))).getOrNull()
        assertTrue(stillAccepted == true, "the active session's own frames must still be accepted after a foreign-TransferId drop")
        assertTrue(buffer.coverage() > coverageAfterBind, "the active session must keep making progress")
    }

    @Test
    fun chunkBuffer_should_AcceptFirstChunk_When_NoTransferIdBoundYet() {
        val buffer = ChunkBuffer(maxPayloadBytes = 65536)

        val accepted = buffer.accept(chunkFor(transferId = 42, index = 0, fragment = byteArrayOf(1, 2, 3))).getOrNull()

        assertTrue(accepted == true, "an unbound buffer must accept the first chunk regardless of its TransferId")
    }
}
