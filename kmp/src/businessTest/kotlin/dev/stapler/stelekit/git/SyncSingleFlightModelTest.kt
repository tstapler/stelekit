// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * U-E1-15: model-based check of [RepoOpGate]'s press/release protocol under random interleavings. A press either
 * takes the key or queues a rerun on the current holder; a holder's release either frees the key or hands it to
 * one follow-up. Invariants per seed: never two holders, a coalesced press is never lost (a follow-up exists), at
 * most one follow-up per queued press, and the key ends free.
 */
class SyncSingleFlightModelTest {
    private val key = "repo"

    private enum class Outcome { Acquired, Coalesced }

    /** The press loop a service runs: acquire, else queue a rerun, else the holder just left so retry. */
    private fun press(gate: RepoOpGate, hold: (GateLease) -> Unit): Outcome {
        while (true) {
            gate.tryAcquire(key, SyncOrigin.UiPress)?.let { lease ->
                hold(lease)
                return Outcome.Acquired
            }
            if (gate.requestRerun(key)) return Outcome.Coalesced
        }
    }

    @Test
    fun rerunBit_should_YieldExactlyOneFollowUp_When_AnyInterleavingOfPressesAndRelease() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val gate = RepoOpGate()
            val holders = AtomicInteger(0)
            val maxHolders = AtomicInteger(0)
            val followUps = AtomicInteger(0)

            // Releases a lease and runs the chain of follow-ups it hands over, as the service's launcher does.
            fun runHeld(first: GateLease) {
                var lease: GateLease? = first
                while (lease != null) {
                    maxHolders.updateAndGet { maxOf(it, holders.incrementAndGet()) }
                    spin(random.nextInt(50))
                    holders.decrementAndGet()
                    lease = gate.releaseOrHandOff(lease)?.also { followUps.incrementAndGet() }?.lease
                }
            }

            val holder = checkNotNull(gate.tryAcquire(key, SyncOrigin.UiPress))
            val pressCount = 1 + random.nextInt(MAX_PRESSES)
            val outcomes = CopyOnWriteArrayList<Outcome>()
            val done = CountDownLatch(pressCount + 1)
            val delays = List(pressCount) { random.nextInt(200) }
            val holderDelay = random.nextInt(200)

            Thread { spin(holderDelay); runHeld(holder); done.countDown() }.start()
            delays.forEach { delay ->
                Thread { spin(delay); outcomes += press(gate) { runHeld(it) }; done.countDown() }.start()
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "seed $seed did not finish") // real-clock hang guard: awaits real threads

            val coalesced = outcomes.count { it == Outcome.Coalesced }
            assertEquals(pressCount, outcomes.size, "seed $seed: every press must end acquired or coalesced")
            assertEquals(1, maxHolders.get(), "seed $seed: two holders at once")
            assertTrue(followUps.get() <= coalesced, "seed $seed: more follow-ups than queued presses")
            if (coalesced > 0) assertTrue(followUps.get() >= 1, "seed $seed: a coalesced press was lost")
            assertFalse(gate.isHeld(key), "seed $seed: key left held")
        }
    }

    private fun spin(n: Int) {
        var x = 0
        repeat(n * SPIN_UNIT) { x += it }
        if (x == -1) throw IllegalStateException()
    }

    private companion object {
        const val SEEDS = 500
        const val MAX_PRESSES = 4
        const val SPIN_UNIT = 500
    }
}
