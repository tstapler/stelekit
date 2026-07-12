package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.transfer.Crc32
import dev.stapler.stelekit.transfer.TransferId
import kotlin.math.ceil

/**
 * Stateful accumulator for a single transfer's fountain-coded chunks (ADR-001, Story 1.2.3).
 *
 * `reassemble()`'s success branch is the ONLY place a [VerifiedTransferPayload] is constructed —
 * "passed the whole-payload CRC32 proof gate" is therefore a type-level fact, not a runtime flag
 * a caller could forget to check (Parse-Don't-Validate). [isComplete] only reports that decoding
 * has *resolved* (success or integrity failure) — it does not imply [reassemble] will succeed.
 *
 * Story 3.3.4: once bound to a [TransferId] (by the first accepted chunk), a frame carrying a
 * different [TransferId] is ignored (logged, not errored) rather than corrupting the in-progress
 * decode — defense in depth alongside [QrTransferCoordinator]'s own session-level binding check.
 */
class ChunkBuffer(private val maxPayloadBytes: Int) {
    private val logger = Logger("ChunkBuffer")

    private var decoder: FountainDecoder? = null
    private var expectedTransferId: TransferId? = null
    private var expectedSeqLen = 0
    private var expectedMessageLen = 0
    private var expectedChecksum = 0
    private var expectedFragmentLen = 0
    private var integrityFailed = false
    private var resultBytes: ByteArray? = null

    /**
     * Idempotent for duplicates, order-independent. Returns `Right(false)` if the chunk is
     * rejected for a generic/recoverable reason (already complete, `payloadLen<=0`, an empty
     * fragment, inconsistent parameters vs. the bound transfer, or [chunk] carries a different
     * [TransferId] than the one this buffer bound to on its first chunk) — the caller should just
     * keep scanning. Returns `Left(PayloadTooLarge)` for the ONE rejection reason that is NOT
     * recoverable by scanning more frames: the claimed `payloadLen` alone already exceeds
     * [maxPayloadBytes], so no amount of additional frames will ever complete this transfer
     * (Story 1.2.3 AC) — surfaced distinctly so [QrTransferCoordinator] can end the session with a
     * terminal [dev.stapler.stelekit.error.DomainError.QrTransferError.PayloadTooLarge] failure
     * instead of leaving the user stuck in `Scanning` forever with no diagnosability.
     */
    fun accept(chunk: FountainChunk): Either<DomainError.QrTransferError.PayloadTooLarge, Boolean> {
        if (isComplete()) return false.right()

        // Bound allocation BEFORE touching any per-transfer state — OOM guard (ADR-001). A
        // genuine size violation, not a generic malformed/duplicate frame — surfaced as a
        // terminal Left, not folded into the generic `false` rejection below.
        if (chunk.payloadLen > maxPayloadBytes) {
            return DomainError.QrTransferError.PayloadTooLarge(chunk.payloadLen, maxPayloadBytes).left()
        }
        if (chunk.payloadLen <= 0) return false.right()
        if (chunk.fragment.isEmpty()) return false.right()

        val boundTransferId = expectedTransferId
        if (boundTransferId != null && chunk.transferId != boundTransferId) {
            logger.warn(
                "ignoring frame for transferId=${chunk.transferId.value} — buffer is bound to " +
                    "transferId=${boundTransferId.value}",
            )
            return false.right()
        }

        val existingDecoder = decoder
        if (existingDecoder == null) {
            expectedTransferId = chunk.transferId
            expectedMessageLen = chunk.payloadLen
            expectedChecksum = chunk.payloadCrc.value
            expectedFragmentLen = chunk.fragment.size
            expectedSeqLen = ceil(chunk.payloadLen.toDouble() / chunk.fragment.size).toInt().coerceAtLeast(1)
        } else {
            if (chunk.payloadLen != expectedMessageLen) return false.right()
            if (chunk.payloadCrc.value != expectedChecksum) return false.right()
            if (chunk.fragment.size != expectedFragmentLen) return false.right()
        }
        val activeDecoder = existingDecoder ?: FountainDecoder(expectedSeqLen).also { decoder = it }

        val seqNum = chunk.chunkIndex.value + 1
        val indexes = chooseFragments(seqNum, expectedSeqLen, expectedChecksum)
        activeDecoder.receive(FountainDecoder.Part(indexes, chunk.fragment))

        resolveIfDecoded(activeDecoder)
        return true.right()
    }

    private fun resolveIfDecoded(activeDecoder: FountainDecoder) {
        val fragments = activeDecoder.resultFragments ?: return
        if (resultBytes != null || integrityFailed) return

        val joined = ByteArray(fragments.sumOf { it.size })
        var offset = 0
        for (fragment in fragments) {
            fragment.copyInto(joined, offset)
            offset += fragment.size
        }
        val finalBytes = joined.copyOfRange(0, expectedMessageLen)

        if (Crc32.of(finalBytes) == expectedChecksum) {
            resultBytes = finalBytes
        } else {
            integrityFailed = true
        }
    }

    /** Fraction (0.0-1.0) of distinct fragment indexes recovered so far. */
    fun coverage(): Double {
        val d = decoder ?: return 0.0
        if (expectedSeqLen == 0) return 0.0
        return d.receivedCount.toDouble() / expectedSeqLen
    }

    /** `true` once decoding has resolved — success OR integrity failure. Necessary, not sufficient. */
    fun isComplete(): Boolean = resultBytes != null || integrityFailed

    /**
     * Only two outcomes exist by construction: success, or the whole-payload CRC32 proof gate
     * failed. "Not enough coverage yet" is deliberately NOT a third outcome here — it is
     * represented by the caller simply not calling [reassemble] yet (see [isComplete]), never by
     * a `Left`. `DomainError.QrTransferError.IncompleteTransfer` existed for exactly this
     * "partial coverage" case and was removed as dead code (audit, Story 1.1.2 follow-up): the
     * only production caller ([QrTransferCoordinator.finishReassembly]) already gates on
     * [isComplete] before ever calling this, so that branch could never be reached.
     */
    fun reassemble(): Either<DomainError, VerifiedTransferPayload> {
        check(isComplete()) { "reassemble() called before decoding resolved — check isComplete() first" }
        val bytes = resultBytes
        return if (bytes != null) {
            VerifiedTransferPayload(bytes.decodeToString()).right()
        } else {
            DomainError.QrTransferError.IntegrityCheckFailed.left()
        }
    }
}
