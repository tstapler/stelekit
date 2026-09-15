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
     * fragment, a first chunk whose `payloadLen`/`fragment.size` combination implies an
     * implausibly large fragment count (see the `maxPlausibleSeqLen` guard below — Gate 2 finding
     * C2), inconsistent parameters vs. the bound transfer, or [chunk] carries a different
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
            val derivedSeqLen = ceil(chunk.payloadLen.toDouble() / chunk.fragment.size).toInt().coerceAtLeast(1)

            // Ceiling on the derived seqLen, checked BEFORE any FountainDecoder is created or
            // chooseFragments()/shuffled() is ever called (Gate 2 finding C2). Nothing previously
            // enforced FountainEncoder.DEFAULT_MIN_FRAGMENT_BYTES on the RECEIVE side — only the
            // encoder applies it when SENDING — so a hostile/corrupted first chunk claiming a
            // large payloadLen with a tiny (or 1-byte) fragment.size could drive
            // expectedSeqLen = ceil(payloadLen / fragment.size) up to tens of thousands.
            // chooseFragments's shuffled() is an intentionally-exact O(n^2) port of bc-ur's
            // list-removeAt Fisher-Yates (kept for reference-vector bit-parity, see
            // FountainCodecVectorTest) — at n≈65535 that's ~4x10^9 ops from ONE crafted chunk,
            // enough to peg a CPU core on Dispatchers.Default and starve other app-wide coroutine
            // work for as long as the malicious stream keeps scanning.
            //
            // maxPlausibleSeqLen is derived, not a magic number: it's the largest seqLen ANY
            // honest encoder could ever produce for this buffer's own maxPayloadBytes ceiling,
            // given the encoder's DEFAULT_MIN_FRAGMENT_BYTES floor on the send side. A fragment
            // size below that floor is legitimate in tests with tiny payloads (see
            // QrRoundTripFidelityTest's minFragmentBytes=1 case) — their resulting seqLen stays
            // far below this ceiling because their payloadLen is tiny too — so bounding the
            // *derived seqLen* here (not the raw fragment size) rejects only the implausible
            // large-payload/tiny-fragment combination that actually drives the O(n^2) blowup.
            // This is recoverable: the decoder hasn't bound yet, so a subsequent legitimate first
            // chunk still succeeds.
            val maxPlausibleSeqLen = ceil(maxPayloadBytes.toDouble() / FountainEncoder.DEFAULT_MIN_FRAGMENT_BYTES).toInt()
            if (derivedSeqLen > maxPlausibleSeqLen) return false.right()

            expectedTransferId = chunk.transferId
            expectedMessageLen = chunk.payloadLen
            expectedChecksum = chunk.payloadCrc.value
            expectedFragmentLen = chunk.fragment.size
            expectedSeqLen = derivedSeqLen
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
