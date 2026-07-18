// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Epic 1.1 (Story 1.1.1): focused coverage for [WebLock.withLock]'s own basic semantics, run
 * against the real browser Web Locks API (`navigator.locks`) — no fakes. Covers
 * `WebLockTest.kt`'s two `withLock_should_...` rows in
 * `project_plans/web-local-folder-livesync/implementation/validation.md`. Independent of
 * `web-git-writeback`'s existing `GitWriteLock` coverage, which is untouched by this project.
 *
 * `WebLock.tryWithLock` gets its own, more thorough coverage later in Epic 6.3 — this file is
 * extended, not replaced, at that point.
 */
class WebLockTest {

    private fun freshLockName(prefix: String): String = "$prefix-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun withLock_should_NotBlockEachOther_When_TwoCallsUseDistinctLockNames() = runTest {
        val lockNameA = freshLockName("wl-a")
        val lockNameB = freshLockName("wl-b")
        val events = mutableListOf<String>()

        // lock-a is acquired and held; while it is held, a completely independent lock-b request
        // must still be able to acquire and complete immediately — distinct names never contend.
        val heldA = async {
            WebLock.withLock(lockNameA) {
                events += "a-acquired"
                // Give lock-b every opportunity to run first if (incorrectly) blocked.
                kotlinx.coroutines.yield()
                events += "a-released"
            }
        }
        val heldB = async {
            WebLock.withLock(lockNameB) {
                events += "b-acquired"
            }
        }

        heldA.await()
        heldB.await()

        assertEquals(true, events.contains("a-acquired"))
        assertEquals(true, events.contains("a-released"))
        assertEquals(true, events.contains("b-acquired"))
    }

    @Test
    fun withLock_should_SerializeExecution_When_TwoCallsUseTheSameLockNameConcurrently() = runTest {
        val lockName = freshLockName("wl-serial")
        val events = mutableListOf<String>()

        // Two concurrent withLock() calls against the SAME lock name must never interleave: the
        // second call's block must not start until the first call's block (and its release) has
        // fully completed.
        val first = async {
            WebLock.withLock(lockName) {
                events += "first-start"
                kotlinx.coroutines.yield()
                events += "first-end"
            }
        }
        val second = async {
            WebLock.withLock(lockName) {
                events += "second-start"
                events += "second-end"
            }
        }

        first.await()
        second.await()

        assertEquals(
            listOf("first-start", "first-end", "second-start", "second-end"),
            events,
            "same-name withLock() calls must serialize: the second block must not begin until the " +
                "first block (including its release) has fully completed",
        )
    }

    // ── Epic 6.3 (Task 6.3.1a): WebLock.tryWithLock non-blocking semantics ─────────────────────

    @Test
    fun tryWithLock_should_ReturnBlockResult_When_LockIsFree() = runTest {
        val lockName = freshLockName("wl-try-free")

        val result = WebLock.tryWithLock(lockName) { "block-ran" }

        assertEquals("block-ran", result)
    }

    @Test
    fun tryWithLock_should_ReturnNull_When_AnotherWithLockCallAlreadyHoldsSameLockName() = runTest {
        val lockName = freshLockName("wl-try-busy")
        val holderAcquired = kotlinx.coroutines.CompletableDeferred<Unit>()

        // The holder runs its 1000ms hold on a real (non-test-scheduler) dispatcher so this is a
        // genuine wall-clock hold, not something runTest's virtual-time auto-advance would skip —
        // otherwise the "tryWithLock returns well before the hold completes" assertion below would
        // be meaningless (both would appear to complete "instantly" in virtual time).
        val holder = async(kotlinx.coroutines.Dispatchers.Default) {
            WebLock.withLock(lockName) {
                holderAcquired.complete(Unit)
                delay(1000)
            }
        }

        holderAcquired.await()

        val mark = TimeSource.Monotonic.markNow()
        val result = WebLock.tryWithLock(lockName) { "should not run" }
        val elapsed = mark.elapsedNow()

        assertNull(result, "tryWithLock must return null when another withLock call already holds the lock")
        assertTrue(
            elapsed < 100.milliseconds,
            "tryWithLock must not block waiting for the lock to free up — took $elapsed while the " +
                "competing withLock() holds for 1000ms",
        )

        holder.await()
    }
}
