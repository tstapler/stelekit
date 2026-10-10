package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.Crc32
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId

/**
 * Fixed-width big-endian binary (de)serializer for [FountainChunk], per ADR-001:
 *
 * ```
 * Offset Size  Field
 * 0      1     magic       0x53 ('S')
 * 1      1     version     0x01
 * 2      4     transferId  uint32
 * 6      4     payloadLen  uint32
 * 10     4     payloadCrc  uint32
 * 14     4     chunkIndex  uint32
 * 18     2     fragLen     uint16
 * 20     n     fragment
 * 20+n   4     chunkCrc    CRC32 of bytes[0 .. 20+n)
 * ```
 *
 * [decode] never throws — a bad `magic`, unknown `version`, or failing `chunkCrc` returns `null`
 * so a corrupted or forged frame can never leak a partially-valid [FountainChunk] across the
 * Layer-1 boundary (no exceptions cross this boundary, per the implementation plan).
 */
object ChunkFrameCodec {
    private const val MAGIC: Byte = 0x53
    private const val VERSION: Byte = 0x01
    private const val HEADER_SIZE = 20
    private const val TRAILER_SIZE = 4

    fun encode(chunk: FountainChunk): ByteArray {
        val fragment = chunk.fragment
        val body = ByteArray(HEADER_SIZE + fragment.size)
        body[0] = MAGIC
        body[1] = VERSION
        writeInt32BE(body, 2, chunk.transferId.value)
        writeInt32BE(body, 6, chunk.payloadLen)
        writeInt32BE(body, 10, chunk.payloadCrc.value)
        writeInt32BE(body, 14, chunk.chunkIndex.value)
        writeUInt16BE(body, 18, fragment.size)
        fragment.copyInto(body, HEADER_SIZE)

        val chunkCrc = Crc32.of(body)
        val result = ByteArray(body.size + TRAILER_SIZE)
        body.copyInto(result)
        writeInt32BE(result, body.size, chunkCrc)
        return result
    }

    fun decode(bytes: ByteArray): FountainChunk? {
        if (bytes.size < HEADER_SIZE + TRAILER_SIZE) return null
        if (bytes[0] != MAGIC) return null
        if (bytes[1] != VERSION) return null

        val fragLen = readUInt16BE(bytes, 18)
        val bodyEnd = HEADER_SIZE + fragLen
        if (bytes.size != bodyEnd + TRAILER_SIZE) return null

        val expectedCrc = readInt32BE(bytes, bodyEnd)
        val actualCrc = Crc32.of(bytes.copyOfRange(0, bodyEnd))
        if (expectedCrc != actualCrc) return null

        return FountainChunk(
            transferId = TransferId(readInt32BE(bytes, 2)),
            chunkIndex = ChunkIndex(readInt32BE(bytes, 14)),
            payloadLen = readInt32BE(bytes, 6),
            payloadCrc = PayloadChecksum(readInt32BE(bytes, 10)),
            fragment = bytes.copyOfRange(HEADER_SIZE, bodyEnd),
        )
    }

    private fun writeInt32BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeUInt16BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readUInt16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
