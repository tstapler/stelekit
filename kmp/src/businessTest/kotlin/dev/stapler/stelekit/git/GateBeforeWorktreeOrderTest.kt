// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lock order is gate, then worktree mutex (ADR-001 decision 7, architecture B2/R1). The quiesce strategy is
 * androidMain-only, so the ordering is audited from source; the behavioural halves live in
 * `AndroidGraphMoveQuiesceStrategyTest` and `QuiesceRealFlushTest`.
 */
class GateBeforeWorktreeOrderTest {
    private val pkg = "dev/stapler/stelekit"
    private val sources get() = WiringInputs.productionSources

    private fun source(key: String): String = checkNotNull(sources[key]) { "source not found: $key" }

    /** Text from the first occurrence of [signature] to [endMarker] (default: the next member-closing brace line). */
    private fun bodyOf(text: String, signature: String, endMarker: String = "\n    }\n"): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "signature not found: $signature" }
        val end = text.indexOf(endMarker, start)
        check(end > start) { "end of $signature not found" }
        return text.substring(start, end)
    }

    @Test
    fun quiesce_should_WaitForGateBeforeTakingWorktreeLock_AndNeverHoldBothWhileWaiting() {
        val quiesce = bodyOf(source("androidMain/$pkg/db/GraphMoveQuiesceStrategy.android.kt"), "override suspend fun quiesce(")
        val gateAt = quiesce.indexOf("gate.acquire(")
        val lockAt = quiesce.indexOf("GitWorktreeLocks.lockFor(")
        assertTrue(gateAt >= 0, "quiesce must take the gate")
        assertTrue(lockAt >= 0, "quiesce must take the worktree lock")
        assertTrue(gateAt < lockAt, "the gate must be acquired before the worktree lock")
    }

    @Test
    fun noWorktreeLockScope_should_CallGateApis_AndDrainShouldNeverCallFlush() {
        val locks = source("androidMain/$pkg/platform/GitWorktreeLocks.kt")
        assertFalse(locks.contains("RepoOpGate"), "GitWorktreeLocks must not reach the gate")

        val strategy = source("androidMain/$pkg/db/GraphMoveQuiesceStrategy.android.kt")
        val drain = bodyOf(strategy, "private suspend fun drainWriteBack(")
        assertTrue(drain.contains("flushWhileLocked"), "drain must use the lock-free flush")
        assertFalse(Regex("""\.flush\(""").containsMatchIn(drain), "drain holds the worktree mutex and must not call flush()")
    }

    @Test
    fun flushPaths_should_TakeOnlyTheWorktreeLock() {
        val actor = source("androidMain/$pkg/git/GitShadowFlushActor.kt")
        val flush = bodyOf(actor, "suspend fun flush()", "\n\n")
        assertTrue(flush.contains("GitWorktreeLocks.lockFor(") && flush.contains("flushLocked()"))
        assertFalse(flush.contains("gate"), "flush must not touch the gate")
        val flushLocked = bodyOf(actor, "suspend fun flushLocked()", "\n\n")
        assertFalse(flushLocked.contains("lockFor("), "flushLocked is called with the mutex held and must not re-take it")

        val fs = source("androidMain/$pkg/platform/PlatformFileSystem.kt")
        assertEquals(0, Regex("""RepoOpGate|gate\.""").findAll(fs).count(), "PlatformFileSystem flush takes only the worktree lock")
    }
}
