package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId

/**
 * One fountain-coded frame, per ADR-001's wire layout. `payloadLen` and `fragment.size` let a
 * receiver derive the fragment count (`ceil(payloadLen / fragment.size)`) without a separate
 * on-wire `seqLen` field, because bc-ur's encoder holds fragment length constant across every
 * part — pure and mixed alike (BCR-2020-012).
 */
data class FountainChunk(
    val transferId: TransferId,
    val chunkIndex: ChunkIndex,
    val payloadLen: Int,
    val payloadCrc: PayloadChecksum,
    val fragment: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FountainChunk) return false
        return transferId == other.transferId &&
            chunkIndex == other.chunkIndex &&
            payloadLen == other.payloadLen &&
            payloadCrc == other.payloadCrc &&
            fragment.contentEquals(other.fragment)
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + chunkIndex.hashCode()
        result = 31 * result + payloadLen
        result = 31 * result + payloadCrc.hashCode()
        result = 31 * result + fragment.contentHashCode()
        return result
    }
}
