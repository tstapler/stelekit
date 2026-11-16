// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.1b / I-E1-03, answers plan Open Question 1: does force-cancelling a coroutine that is inside a
 * blocking JGit add+commit leave a `*.lock` behind? The test records the observed behaviour and asserts
 * the expected outcome (no lock), which Task 1.5a parameterises on.
 */
class JgitCancelLockTest {
    @Test
    fun forcedCancelDuringRealCommit_should_ReleaseOrNextClaimShouldClearIndexLock() = runBlocking<Unit> {
        JgitCancelFixture(fileCount = 2_000).use { fx ->
            val repo = JvmGitRepository()
            val callReturned = AtomicBoolean(false)
            val job = CoroutineScope(Dispatchers.Default).launch {
                repo.stageSubdir(fx.config)
                repo.commit(fx.config, "cancelled")
                callReturned.set(true)
            }
            val indexLock = File(fx.work, ".git/index.lock")
            val deadline = System.nanoTime() + TIMEOUT_NANOS
            while (!indexLock.exists() && job.isActive) {
                assertTrue(System.nanoTime() < deadline, "add never took index.lock")
                Thread.onSpinWait()
            }
            val lockObservedMidCall = indexLock.exists()

            job.cancelAndJoin()

            println("JGIT_CANCEL_RESULT lockObservedMidCall=$lockObservedMidCall callRanToCompletion=${callReturned.get()} locksAfterJoin=${fx.locksUnderGitDir()}")
            assertTrue(lockObservedMidCall, "the cancel must land while add holds index.lock; raise fileCount if this flakes")
            assertEquals(emptyList(), fx.locksUnderGitDir(), "no lock may remain once the cancelled call has returned")
            assertTrue(fx.statusIsHealthy())
        }
    }

    private companion object {
        const val TIMEOUT_NANOS = 60_000_000_000L
    }
}
