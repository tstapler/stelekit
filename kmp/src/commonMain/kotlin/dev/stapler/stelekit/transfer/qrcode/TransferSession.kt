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
 * Story 3.3.2 adds stall-timer *behavior* on top of [lastNewFragmentAt]: [stalledSeconds]/[isStalled]/
 * [stallHint] let [QrTransferCoordinator] surface [ScanHint.Stalled] through the existing
 * `Scanning(hint=...)` state without any new aggregate type.
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

    /**
     * Seconds since [lastNewFragmentAt]. validation.md's `TransferSessionTest` exercises the
     * timer at [STALL_THRESHOLD_SECONDS]=8s; `design/ux.md`'s S9 copy references
     * `stalledSeconds>=9` for display — that 1s gap is display-layer rounding (a value computed at
     * 8.0s elapsed can already read "9" once whole-second UI formatting rounds up), not a second
     * independent threshold.
     */
    fun stalledSeconds(now: Instant = clock()): Int =
        (now - lastNewFragmentAt).inWholeSeconds.toInt().coerceAtLeast(0)

    /** `true` once [stalledSeconds] reaches [STALL_THRESHOLD_SECONDS] with no genuinely new fragment. */
    fun isStalled(now: Instant = clock()): Boolean = stalledSeconds(now) >= STALL_THRESHOLD_SECONDS

    /** [ScanHint.Stalled] once [isStalled], else `null` — the stall-specific half of hint derivation. */
    fun stallHint(now: Instant = clock()): ScanHint? = if (isStalled(now)) ScanHint.Stalled else null

    companion object {
        /** Stall-timer threshold (Story 3.3.2); kept in sync with `TransferSessionTest`'s 8s scenario. */
        const val STALL_THRESHOLD_SECONDS = 8
    }
}
