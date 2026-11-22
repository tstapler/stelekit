// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.1b / I-E1-04: cancels a real JGit add, commit, fetch and merge at eight instants each after the
 * call starts (32 cases) and asserts no `*.lock` survives the join and `git status` still works.
 * JvmGitRepository exposes no per-call progress callback, so the instants are spin-count offsets after a
 * latch signals the call began. After each, `endOwnedOperation` plus a fresh claim must return NoLock/ClearedOwn (Task 1.2g).
 */
class JgitCancelSweepTest {
    private enum class Op { Add, Commit, Fetch, Merge }

    private fun runCase(op: Op, spins: Int): List<String> = runBlocking {
        JgitCancelFixture(fileCount = FILES).use { fx ->
            val repo = JvmGitRepository()
            when (op) {
                Op.Commit -> fx.advanceOrigin()
                Op.Fetch -> fx.advanceOrigin()
                Op.Merge -> { fx.commitLocally(); fx.advanceOrigin(); repo.fetch(fx.config) }
                Op.Add -> Unit
            }
            val info = OwnedOperationInfo(op.name, SyncOrigin.UiPress)
            repo.beginOwnedOperation(fx.config, info)
            val started = CountDownLatch(1)
            val job = CoroutineScope(Dispatchers.Default).launch {
                started.countDown()
                when (op) {
                    Op.Add -> repo.stageSubdir(fx.config)
                    Op.Commit -> { repo.stageSubdir(fx.config); repo.commit(fx.config, "cancelled") }
                    Op.Fetch -> repo.fetch(fx.config)
                    Op.Merge -> repo.merge(fx.config)
                }
            }
            assertTrue(started.await(30, TimeUnit.SECONDS), "call never started")
            repeat(spins) { Thread.onSpinWait() }
            job.cancelAndJoin()
            assertTrue(fx.statusIsHealthy(), "git status failed after cancelling $op at $spins spins")
            repo.endOwnedOperation(fx.config)
            val fresh = repo.beginOwnedOperation(fx.config, info).getOrNull()
            repo.endOwnedOperation(fx.config)
            fx.locksUnderGitDir() + listOfNotNull(
                "fresh claim returned $fresh".takeIf { fresh != LockVerdict.NoLock && fresh != LockVerdict.ClearedOwn },
            )
        }
    }

    @Test
    fun cancelAtEveryProgressPoint_should_NeverLeaveUnclearableLock_AndGateHeldUntilCallReturns() {
        val leftovers = mutableListOf<String>()
        var cases = 0
        for (op in Op.entries) for (spins in OFFSETS) {
            cases++
            runCase(op, spins).forEach { leftovers += "$op@$spins: $it" }
        }
        println("JGIT_CANCEL_SWEEP cases=$cases leftoverLocks=$leftovers")
        assertTrue(cases >= 30, "the sweep must cover at least 30 cancel points, ran $cases")
        assertEquals(emptyList(), leftovers)
    }

    private companion object {
        const val FILES = 300
        val OFFSETS = listOf(0, 1_000, 10_000, 50_000, 200_000, 1_000_000, 5_000_000, 20_000_000)
    }
}
