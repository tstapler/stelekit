package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChunkFrameCodecTest {

    private fun sampleChunk() = FountainChunk(
        transferId = TransferId(7),
        chunkIndex = ChunkIndex(3),
        payloadLen = 9,
        payloadCrc = PayloadChecksum(0xAABBCCDD.toInt()),
        fragment = byteArrayOf(1, 2, 3),
    )

    @Test
    fun chunkFrameCodec_should_RoundTripFountainChunk_When_ValidBytesEncodedAndDecoded() {
        val chunk = sampleChunk()

        val decoded = ChunkFrameCodec.decode(ChunkFrameCodec.encode(chunk))

        assertEquals(chunk, decoded)
    }

    @Test
    fun chunkFrameCodec_decode_should_ReturnNull_When_MagicByteOrVersionOrChunkCrcIsInvalid() {
        val encoded = ChunkFrameCodec.encode(sampleChunk())

        val badMagic = encoded.copyOf().also { it[0] = 0x00 }
        assertNull(ChunkFrameCodec.decode(badMagic), "flipped magic byte must decode to null")

        val badVersion = encoded.copyOf().also { it[1] = 0x02 }
        assertNull(ChunkFrameCodec.decode(badVersion), "unknown version byte must decode to null")

        val badCrc = encoded.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        assertNull(ChunkFrameCodec.decode(badCrc), "failing chunkCrc must decode to null")
    }

    @Test
    fun decode_should_ReturnNull_When_ByteArrayShorterThanMinimumFrameSize() {
        assertNull(ChunkFrameCodec.decode(ByteArray(10)))
        assertNull(ChunkFrameCodec.decode(ByteArray(0)))
    }
}
