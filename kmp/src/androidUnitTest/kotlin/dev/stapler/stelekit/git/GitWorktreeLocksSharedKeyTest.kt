// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.GitWorktreeLocks
import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for plan.md Task 5.2.1c — [GitShadowWorktree]'s SAF→shadow sync
 * ([GitShadowWorktree.syncFromSafRoot], Task 5.2.1b) and [PlatformFileSystem.flushPendingWrites]'s
 * write-behind flush must contend on the *same* [kotlinx.coroutines.sync.Mutex] instance from
 * [GitWorktreeLocks] for a given graph — not just each acquire *some* lock independently.
 *
 * Proven behaviorally, not just structurally: the test holds the git side's lock (acquired via
 * [GitWorktreeLocks.lockFor] with the exact key [GitShadowWorktree.shadowKeyForSafPath] derives)
 * and confirms a concurrent [PlatformFileSystem.flushPendingWrites] call — configured with a
 * matching [PlatformFileSystem.setGitShadowKeyProvider] — cannot proceed until that lock is
 * released. If the two subsystems were locking on different keys/instances, the flush would
 * complete immediately regardless of the held lock.
 *
 * A deeper concurrent-ordering test (asserting the critical sections never interleave under real
 * contention) is Phase 8's Task 8.4.3a — this test only needs the same-instance/blocking property.
 */
@RunWith(RobolectricTestRunner::class)
class GitWorktreeLocksSharedKeyTest {

    @Test
    fun `GitShadowWorktree sync and PlatformFileSystem flushPendingWrites contend on the same Mutex instance for a configured graph`() =
        runTest {
            val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki"

            // The exact key GitShadowWorktree.syncFromSafRoot() locks on for this repoRoot.
            val gitSideKey = GitShadowWorktree.shadowKeyForSafPath(repoRoot)
            val gitSideLock = GitWorktreeLocks.lockFor(gitSideKey)

            // Wire PlatformFileSystem's flush-side key provider exactly as
            // AndroidGitRepository.shadowWorktreeFor does in production.
            val fileSystem = PlatformFileSystem()
            fileSystem.setGitShadowKeyProvider { GitShadowWorktree.shadowKeyForSafPath(repoRoot) }

            // Hold the git-side lock first.
            gitSideLock.lock()
            try {
                var flushCompleted = false
                val flushJob = launch { fileSystem.flushPendingWrites(); flushCompleted = true }
                advanceUntilIdle()

                // If flushPendingWrites() were locking on a different Mutex instance (or not
                // locking at all), it would have completed already despite gitSideLock being held.
                assertFalse(flushCompleted, "flushPendingWrites() should block while the shared lock is held")

                gitSideLock.unlock()
                flushJob.join()
                assertTrue(flushCompleted, "flushPendingWrites() should complete once the shared lock is released")
            } finally {
                if (gitSideLock.isLocked) gitSideLock.unlock()
            }
        }
}
