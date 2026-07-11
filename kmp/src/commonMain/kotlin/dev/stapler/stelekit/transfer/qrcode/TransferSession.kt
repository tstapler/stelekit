package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.TransferId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Aggregate root for one receive session (Story 3.2.2). Wraps the [transferId] being received,
 * its [buffer] accumulator, and timing bookkeeping. Held by [QrTransferCoordinator] as the
 * aggregate root from frame 0 — never built as a bare [ChunkBuffer] first and retrofitted later.
 *
 * [ChunkBuffer] does not expose a raw received-fragment counter (only [ChunkBuffer.coverage], a
 * fraction) — [uniqueFragments] is derived here by detecting when [ChunkBuffer.coverage] measurably
 * increases after a successful [accept], which happens if and only if [chunk] was a genuinely new
 * (previously-unseen) fragment, not a duplicate.
 *
 * Story 3.3.2 (out of scope for this story) adds stall-timer *behavior* on top of
 * [lastNewFragmentAt] — this class carries only the base aggregate shape needed to support that
 * later addition without a retrofit.
 */
class TransferSession(
    val transferId: TransferId,
    maxPayloadBytes: Int,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    val buffer: ChunkBuffer = ChunkBuffer(maxPayloadBytes)

    /** Wall-clock time this session was created (first frame observed). */
    val startedAt: Instant = clock()

    /** Wall-clock time of the most recently admitted NEW (previously-unseen) fragment. */
    var lastNewFragmentAt: Instant = startedAt
        private set

    /** Count of distinct fragments admitted so far — drives `Scanning.uniqueFragments`. */
    var uniqueFragments: Int = 0
        private set

    private var lastCoverage: Double = 0.0

    /**
     * Accepts [chunk] into [buffer]. Advances [uniqueFragments] and [lastNewFragmentAt] only when
     * coverage actually grew (a genuinely new fragment), never on a duplicate.
     */
    fun accept(chunk: FountainChunk): Boolean {
        val accepted = buffer.accept(chunk)
        if (accepted) {
            val newCoverage = buffer.coverage()
            if (newCoverage > lastCoverage) {
                lastCoverage = newCoverage
                uniqueFragments += 1
                lastNewFragmentAt = clock()
            }
        }
        return accepted
    }
}
