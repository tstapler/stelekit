package dev.stapler.stelekit.transfer

import kotlin.test.Test
import kotlin.test.assertEquals

class TransferTypesTest {

    @Test
    fun transferId_should_ExposeUnderlyingIntValue_When_Constructed() {
        val id = TransferId(42)
        assertEquals(42, id.value)
        assertEquals(TransferId(42), id)
        assertEquals(TransferId(42).hashCode(), id.hashCode())
    }

    @Test
    fun chunkIndex_should_ExposeUnderlyingIntValue_When_Constructed() {
        val index = ChunkIndex(3)
        assertEquals(3, index.value)
        assertEquals(ChunkIndex(3), index)
        assertEquals(ChunkIndex(3).hashCode(), index.hashCode())
    }

    @Test
    fun payloadChecksum_should_ExposeUnderlyingIntValue_When_Constructed() {
        val checksum = PayloadChecksum(0xCBF43926.toInt())
        assertEquals(0xCBF43926.toInt(), checksum.value)
        assertEquals(PayloadChecksum(0xCBF43926.toInt()), checksum)
    }

    @Test
    fun chunkChecksum_should_ExposeUnderlyingIntValue_When_Constructed() {
        val checksum = ChunkChecksum(7)
        assertEquals(7, checksum.value)
        assertEquals(ChunkChecksum(7), checksum)
    }
}
