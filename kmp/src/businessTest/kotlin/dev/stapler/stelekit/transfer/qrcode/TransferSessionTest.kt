package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 3.3.2: stall-timer *behavior* added to the existing [TransferSession] aggregate (UQ-3).
 * Uses [TransferSession]'s injectable `clock` constructor parameter — already present since
 * Story 3.2.2 — so no real-time sleep is needed to exercise an 8-second stall.
 */
class TransferSessionTest {

    private fun chunkFor(index: Int, fragment: ByteArray = byteArrayOf(1, 2, 3)) = FountainChunk(
        transferId = TransferId(1),
        chunkIndex = ChunkIndex(index),
        payloadLen = 9,
        payloadCrc = PayloadChecksum(0),
        fragment = fragment,
    )

    @Test
    fun transferSession_should_TrackLastNewFragmentTimeAndEmitStalledHint_When_NoNewFragmentsFor8Seconds() {
        var now = Instant.fromEpochMilliseconds(0)
        val session = TransferSession(TransferId(1), maxPayloadBytes = 65536, clock = { now })

        session.accept(chunkFor(0))
        assertEquals(now, session.lastNewFragmentAt)
        assertFalse(session.isStalled(now), "must not be stalled immediately after a fresh fragment")
        assertNull(session.stallHint(now))

        // Just under the threshold — not yet stalled.
        now = Instant.fromEpochMilliseconds(7_999)
        assertFalse(session.isStalled(now))
        assertEquals(7, session.stalledSeconds(now))
        assertNull(session.stallHint(now))

        // At/over the 8s threshold with no new fragment admitted in between.
        now = Instant.fromEpochMilliseconds(8_000)
        assertTrue(session.isStalled(now), "must be stalled once 8s have elapsed with no new fragment")
        assertEquals(8, session.stalledSeconds(now))
        assertEquals(ScanHint.Stalled, session.stallHint(now))

        // A genuinely new fragment resets the timer.
        session.accept(chunkFor(1, fragment = byteArrayOf(4, 5, 6)))
        assertEquals(now, session.lastNewFragmentAt)
        assertFalse(session.isStalled(now), "a new fragment must reset the stall timer")
        assertNull(session.stallHint(now))
    }

    @Test
    fun accept_should_NotResetStallTimer_When_ChunkIsADuplicate() {
        var now = Instant.fromEpochMilliseconds(0)
        val session = TransferSession(TransferId(1), maxPayloadBytes = 65536, clock = { now })
        val chunk = chunkFor(0)

        session.accept(chunk)
        val firstFragmentTime = session.lastNewFragmentAt

        now = Instant.fromEpochMilliseconds(9_000)
        session.accept(chunk) // duplicate — same fragment identity, coverage does not grow

        assertEquals(firstFragmentTime, session.lastNewFragmentAt, "a duplicate must not reset lastNewFragmentAt")
        assertTrue(session.isStalled(now), "a duplicate-only stream must still read as stalled")
    }
}
