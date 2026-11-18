package dev.stapler.stelekit.git

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RepoOpGateTest {
    @Test
    fun tryAcquire_should_ReturnNull_When_HeldAndAwaitFreeShouldWake_When_Released() = runTest {
        val gate = RepoOpGate()
        val lease = assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress))
        assertNull(gate.tryAcquire("k", SyncOrigin.Scheduled))
        assertTrue(gate.isHeld("k"))
        assertEquals(SyncOrigin.UiPress, gate.heldBy("k"))
        assertNotNull(gate.tryAcquire("other", SyncOrigin.Scheduled), "keys are independent")

        var woke = false
        val waiter = launch { gate.awaitFree("k"); woke = true }
        runCurrent()
        assertFalse(woke)

        assertNull(gate.releaseOrHandOff(lease))
        runCurrent()
        waiter.join()
        assertTrue(woke)
        assertFalse(gate.isHeld("k"))
        assertNull(gate.heldBy("k"))
    }

    @Test
    fun awaitFree_should_ReturnImmediately_When_NotHeld() = runTest {
        RepoOpGate().awaitFree("free")
    }

    @Test
    fun acquire_should_WaitForRelease_ThenTakeTheKey() = runTest {
        val gate = RepoOpGate()
        val first = assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress))
        val quiesce = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire("k", SyncOrigin.Quiesce) }
        runCurrent()
        assertFalse(quiesce.isCompleted)

        gate.releaseOrHandOff(first)
        val lease = quiesce.await()
        assertEquals(SyncOrigin.Quiesce, lease.origin)
        assertEquals(SyncOrigin.Quiesce, gate.heldBy("k"))
    }

    @Test
    fun releaseOrHandOff_should_HandTheLeaseToExactlyOneFollowUp_When_RerunWasRequested() {
        val gate = RepoOpGate()
        val lease = assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress))
        assertTrue(gate.requestRerun("k"))
        assertTrue(gate.requestRerun("k"), "a second press is idempotent, still one bit")

        val followUp = assertNotNull(gate.releaseOrHandOff(lease))
        assertTrue(gate.isHeld("k"), "the gate must never become free between run and follow-up")
        assertEquals(SyncOrigin.FollowUp, followUp.lease.origin)
        assertNull(gate.tryAcquire("k", SyncOrigin.UiPress))

        assertNull(gate.releaseOrHandOff(followUp.lease), "the bit was cleared, so no second follow-up")
        assertFalse(gate.isHeld("k"))
    }

    @Test
    fun requestRerun_should_BeRefused_When_HolderIsQuiesceOrKeyIsFree() {
        val gate = RepoOpGate()
        assertFalse(gate.requestRerun("free"))
        val quiesce = assertNotNull(gate.tryAcquire("k", SyncOrigin.Quiesce))
        assertFalse(gate.requestRerun("k"), "a follow-up after a storage move would run against relocated storage")
        assertNull(gate.releaseOrHandOff(quiesce))
        assertFalse(gate.isHeld("k"))
    }

    @Test
    fun releaseOrHandOff_should_BeIdempotent_And_IgnoreAStaleLease() {
        val gate = RepoOpGate()
        val old = assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress))
        gate.releaseOrHandOff(old)
        val next = assertNotNull(gate.tryAcquire("k", SyncOrigin.Scheduled))

        assertNull(gate.releaseOrHandOff(old), "a late release of the previous lease must not free the next holder")
        assertTrue(gate.isHeld("k"))
        gate.releaseOrHandOff(next)
        assertFalse(gate.isHeld("k"))
    }

    @Test
    fun interleavings_should_NeverYieldTwoLiveLeases_And_NeverLoseARerunBit() = runTest {
        val ops = listOf("acquire", "press", "release", "acquireQuiesce")
        checkAll(Arb.list(Arb.element(ops), 1..60)) { script ->
            val gate = RepoOpGate()
            // Reference model: holder origin or null, and whether a rerun bit is pending.
            var holder: GateLease? = null
            var pendingRerun = false
            var heldCount = 0

            for (op in script) {
                when (op) {
                    "acquire", "acquireQuiesce" -> {
                        val origin = if (op == "acquire") SyncOrigin.UiPress else SyncOrigin.Quiesce
                        val got = gate.tryAcquire("k", origin)
                        if (holder == null) {
                            assertNotNull(got, "free key must be acquirable")
                            holder = got
                            pendingRerun = false
                        } else {
                            assertNull(got, "a held key must never yield a second lease")
                        }
                    }
                    "press" -> {
                        val set = gate.requestRerun("k")
                        val expected = holder != null && holder.origin != SyncOrigin.Quiesce
                        assertEquals(expected, set)
                        if (expected) pendingRerun = true
                    }
                    else -> {
                        val current = holder
                        if (current != null) {
                            val follow = gate.releaseOrHandOff(current)
                            assertEquals(pendingRerun, follow != null, "a pending rerun must produce exactly one follow-up")
                            holder = follow?.lease
                            pendingRerun = false
                        }
                    }
                }
                heldCount = if (gate.isHeld("k")) 1 else 0
                assertEquals(holder != null, heldCount == 1)
            }
        }
    }

    @Test
    fun manyWaitersAndHolders_should_RunOneAtATime() = runTest {
        val gate = RepoOpGate()
        var active = 0
        var maxActive = 0
        val jobs = List(8) {
            launch {
                val lease = gate.acquire("k", SyncOrigin.Quiesce)
                active++
                maxActive = maxOf(maxActive, active)
                kotlinx.coroutines.yield()
                active--
                gate.releaseOrHandOff(lease)
            }
        }
        advanceUntilIdle()
        jobs.forEach { it.join() }
        assertEquals(1, maxActive)
    }
}
