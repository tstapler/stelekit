package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.transfer.TransferId

/**
 * Layer-1 public entry point (ADR-001): encode a page's markdown into fountain-coded chunks, or
 * create a fresh accumulator to decode a received stream of them. Pure `commonMain`, no I/O.
 */
object FountainCodec {

    fun encoder(
        transferId: TransferId,
        payloadBytes: ByteArray,
        maxFragmentBytes: Int,
        maxPayloadBytes: Int = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES,
    ): Either<DomainError.QrTransferError.PayloadTooLarge, FountainEncoder> =
        FountainEncoder(transferId, payloadBytes, maxFragmentBytes, maxPayloadBytes)

    fun decoder(maxPayloadBytes: Int = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES): ChunkBuffer =
        ChunkBuffer(maxPayloadBytes)
}
