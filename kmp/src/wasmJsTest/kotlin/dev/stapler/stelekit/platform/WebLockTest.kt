// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
