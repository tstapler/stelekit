// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.GitWriteLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Task 3.3.1b: proves the relocate lock namespace ([relocateLockNameFor]) is genuinely
 * independent of [GitWriteLock]'s remote-URL-derived namespace — run against the real browser Web
 * Locks API (`navigator.locks`), no fakes, mirroring [WebLockTest]'s convention.
 *
 * REQ-8 in `project_plans/app-owned-storage-clone/implementation/validation.md`.
 */
class RelocateLockNamespaceTest {

    @Test
    fun relocateLock_should_NotCrossBlockConcurrentGitPushLock_When_DifferentNamespaces() = runTest {
        val graphId = "g1-${Random.nextInt(0, Int.MAX_VALUE)}"
        val remoteUrl = "https://example.com/tstapler/repo-${Random.nextInt(0, Int.MAX_VALUE)}.git"

        // Given a relocate in progress for graphId, holding the relocate lock for the whole test.
        val relocateLock = WebLock.acquireHeld(relocateLockNameFor(graphId))
        try {
            // When a concurrent git push attempts its own remote-URL-derived lock — run on a real
            // (non-test-scheduler) dispatcher so this is a genuine wall-clock measurement, not
            // something runTest's virtual-time auto-advance could fast-forward through (mirrors
            // WebLockTest.tryWithLock_should_ReturnNull_When_AnotherWithLockCallAlreadyHoldsSameLockName's
            // identical idiom for the same reason).
            var pushRan = false
            val mark = TimeSource.Monotonic.markNow()
            withContext(Dispatchers.Default) {
                GitWriteLock.withLock(GitWriteLock.lockNameFor(remoteUrl)) {
                    pushRan = true
                }
            }
            val elapsed = mark.elapsedNow()

            // Then it succeeds unaffected — proving the namespaces are independent, not
            // accidentally shared. A shared/colliding namespace would have left this call queued
            // behind the still-held relocate lock (never released until this test's `finally`),
            // so it would never complete at all rather than merely completing slowly.
            assertTrue(pushRan, "git push lock must acquire and run even while the relocate lock is held")
            assertTrue(
                elapsed < 2.seconds,
                "git push lock must not be blocked by the relocate lock — took $elapsed",
            )
        } finally {
            relocateLock.release()
        }
    }
}
