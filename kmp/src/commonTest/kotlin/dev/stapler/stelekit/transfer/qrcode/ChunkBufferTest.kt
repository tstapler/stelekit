package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkBufferTest {

    private val payload = "page body ".repeat(10).encodeToByteArray()

    private fun encoderFor(payload: ByteArray) =
        FountainEncoder(TransferId(1), payload, maxFragmentBytes = 10, minFragmentBytes = 1).getOrNull()!!

    @Test
    fun reassemble_should_ReturnVerifiedTransferPayload_When_PartsArriveOutOfOrderWithOneDuplicate() {
        val encoder = encoderFor(payload)
        val seqLen = encoder.seqLen
        // seqLen pure fragments + 5 redundant mixed parts, so dropping one non-essential part
        // and duplicating another still leaves enough coverage to reconstruct.
        val allParts = encoder.parts().take(seqLen + 5).toList()
        val delivered = (allParts.dropLast(1) + allParts[0]).reversed()

        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        for (chunk in delivered) {
            buffer.accept(chunk)
            if (buffer.isComplete()) break
        }

        assertTrue(buffer.isComplete())
        val result = buffer.reassemble().getOrNull()
        assertEquals(payload.decodeToString(), result?.markdown)
    }

    @Test
    fun accept_should_BeIdempotent_When_SameChunkAcceptedTwice() {
        val encoder = encoderFor(payload)
        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        val firstChunk = encoder.parts().first()

        buffer.accept(firstChunk)
        val coverageAfterFirst = buffer.coverage()
        buffer.accept(firstChunk)
        val coverageAfterDuplicate = buffer.coverage()

        assertEquals(coverageAfterFirst, coverageAfterDuplicate)
    }

    @Test
    fun reassemble_should_ReturnIntegrityCheckFailed_When_ReassembledCrcMismatchesHeaderPayloadCrc() {
        val encoder = encoderFor(payload)
        val seqLen = encoder.seqLen
        val pureParts = encoder.parts().take(seqLen).toList()

        // Tamper with one fragment's raw bytes. In production this class of corruption is caught
        // earlier by ChunkFrameCodec's per-frame chunkCrc; this test exercises ChunkBuffer's own
        // whole-payload proof gate directly as defense in depth.
        val tampered = pureParts.map { chunk ->
            if (chunk.chunkIndex == ChunkIndex(0)) {
                chunk.copy(fragment = chunk.fragment.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })
            } else {
                chunk
            }
        }

        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        for (chunk in tampered) buffer.accept(chunk)

        // isComplete()==true does NOT imply reassemble() succeeds.
        assertTrue(buffer.isComplete())
        assertEquals(DomainError.QrTransferError.IntegrityCheckFailed, buffer.reassemble().leftOrNull())
    }

    @Test
    fun accept_should_ReturnLeftPayloadTooLarge_When_ClaimedPayloadLenExceedsMaxPayloadBytes() {
        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        val oversizedClaim = FountainChunk(
            transferId = TransferId(1),
            chunkIndex = ChunkIndex(0),
            payloadLen = 5_000_000,
            payloadCrc = PayloadChecksum(0),
            fragment = byteArrayOf(1, 2, 3),
        )

        val result = buffer.accept(oversizedClaim)

        assertEquals(
            DomainError.QrTransferError.PayloadTooLarge(sizeBytes = 5_000_000, maxBytes = 65536),
            result.leftOrNull(),
        )
        assertFalse(buffer.isComplete())
        assertEquals(0.0, buffer.coverage())
    }

    @Test
    fun accept_should_ReturnRightFalse_When_FirstChunkFragmentSizeImpliesImplausibleSeqLen() {
        // Gate 2 finding C2: a hostile/corrupted first chunk claims a large payloadLen (just under
        // maxPayloadBytes) with a tiny fragment.size, so the naive
        // expectedSeqLen = ceil(payloadLen / fragment.size) would land in the tens of thousands —
        // large enough to make chooseFragments's O(n^2) shuffled() cost billions of operations for
        // ONE chunk. This must be rejected BEFORE any FountainDecoder is constructed or
        // chooseFragments/shuffled() is ever invoked.
        val maxPayloadBytes = 65536
        val buffer = ChunkBuffer(maxPayloadBytes = maxPayloadBytes)
        val maliciousFirstChunk = FountainChunk(
            transferId = TransferId(1),
            chunkIndex = ChunkIndex(999), // beyond any plausible seqLen — forces the "mixed" path
            payloadLen = maxPayloadBytes - 1,
            payloadCrc = PayloadChecksum(0),
            fragment = byteArrayOf(1), // 1-byte fragment -> derived seqLen ~= maxPayloadBytes
        )

        val result = buffer.accept(maliciousFirstChunk)

        assertEquals(false, result.getOrNull())
        assertFalse(buffer.isComplete())
        assertEquals(0.0, buffer.coverage())

        // Recoverable: the buffer never bound to the malicious chunk, so a subsequent legitimate
        // first chunk (production-shaped fragment size) still succeeds normally.
        val encoder = encoderFor(payload)
        val legitimateFirstChunk = encoder.parts().first()
        val legitimateResult = buffer.accept(legitimateFirstChunk)
        assertEquals(true, legitimateResult.getOrNull())
    }

    @Test
    fun accept_should_ReturnRightFalse_When_ClaimedPayloadLenIsZeroOrNegative() {
        // Distinct from the oversize case above: an invalid-but-not-oversize payloadLen is a
        // generic/recoverable rejection (Right(false)), not a terminal PayloadTooLarge Left.
        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        val invalidClaim = FountainChunk(
            transferId = TransferId(1),
            chunkIndex = ChunkIndex(0),
            payloadLen = 0,
            payloadCrc = PayloadChecksum(0),
            fragment = byteArrayOf(1, 2, 3),
        )

        val result = buffer.accept(invalidClaim)

        assertEquals(false, result.getOrNull())
        assertFalse(buffer.isComplete())
    }
}
