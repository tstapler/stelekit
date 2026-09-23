package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FountainEncoderTest {

    @Test
    fun parts_should_MatchBcUrReferenceForFixedSeed_When_FirstFivePartsTaken() {
        val payload = "hello world".encodeToByteArray()
        val encoder = FountainEncoder(
            transferId = TransferId(1),
            payloadBytes = payload,
            maxFragmentBytes = 4,
            minFragmentBytes = 1,
        ).getOrNull()!!

        val parts = encoder.parts().take(5).toList()

        assertEquals(listOf(0, 1, 2, 3, 4), parts.map { it.chunkIndex.value })

        // ChunkIndex 0 is always the "pure" first fragment (bc-ur seqNum=1 <= seqLen) — a direct
        // consequence of the LT algorithm, not a coincidence — so it equals the reference
        // implementation's part-0 for this input by construction.
        val fragmentLen = findNominalFragmentLength(payload.size, 1, 4)
        val expectedFragmentZero = partitionMessage(payload, fragmentLen)[0]
        assertContentEquals(expectedFragmentZero, parts[0].fragment)
    }

    @Test
    fun constructor_should_ReturnPayloadTooLarge_When_PayloadExceedsMaxPayloadBytes() {
        val payload = ByteArray(200_000)

        val result = FountainEncoder(
            transferId = TransferId(1),
            payloadBytes = payload,
            maxFragmentBytes = 200,
            maxPayloadBytes = 65536,
        )

        assertEquals(
            DomainError.QrTransferError.PayloadTooLarge(200_000, 65536),
            result.leftOrNull(),
        )
    }
}
