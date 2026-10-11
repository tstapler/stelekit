// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.resilience.RetryPolicies
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.api.errors.TransportException
import java.net.SocketException

/**
 * git-sync-resilience Story 2.1.3 — clone's `beforeRetry` directory-content cleanup
 * ([deleteDirectoryContentsForRetry] in `GitOperationSupport.kt`). A separate file from
 * `GitTransportRetryTest.kt` (a different worker is concurrently extending that file with an
 * unrelated Story 6.1.1 test — kept apart to avoid a merge conflict), covering the same
 * `runGitTransportOpWithRetry` behavior these two REQ-4 rows describe: the cleanup only runs on
 * an automatic retry, never a manual cancel, and it actually empties a non-empty directory.
 */
class GitCloneRetryCleanupTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun transientFailure() =
        TransportException("connection reset", SocketException("connection reset"))

    @Test
    fun `deleteDirectoryContentsForRetry empties a non-empty directory without deleting the directory itself`() {
        val dir = createTempDirectory("stelekit_retry_cleanup_").toFile().also { tempDirs += it }
        File(dir, "partial-pack-file").writeText("leftover from an interrupted clone")
        File(dir, "nested").mkdir()
        File(dir, "nested/more-leftovers").writeText("also leftover")
        assertTrue(dir.exists() && dir.listFiles()?.isNotEmpty() == true, "precondition: dir must be non-empty")

        deleteDirectoryContentsForRetry(dir)

        assertTrue(dir.exists(), "the directory itself must survive cleanup")
        assertEquals(emptyList(), dir.listFiles()?.toList().orEmpty(), "expected the directory to be empty after cleanup")
    }

    @Test
    fun `deleteDirectoryContentsForRetry is a no-op on an absent directory`() {
        val dir = File(createTempDirectory("stelekit_retry_cleanup_absent_").toFile().also { tempDirs += it }, "does-not-exist")
        assertTrue(!dir.exists())

        deleteDirectoryContentsForRetry(dir) // must not throw

        assertTrue(!dir.exists())
    }

    /**
     * `beforeRetry cleanup does not run when the failure classifies Cancelled` (REQ-4, Story
     * 2.1.3): a manually cancelled clone must leave a partial target directory intact — proven
     * here the same way `GitTransportRetryTest`'s existing Cancelled-path tests do, by making
     * `beforeRetry` fail the test if it's ever invoked.
     */
    @Test
    fun `beforeRetry directory cleanup never runs for a Cancelled failure`() = runTest {
        var callCount = 0
        val dir = createTempDirectory("stelekit_retry_cleanup_cancel_").toFile().also { tempDirs += it }
        File(dir, "left-alone").writeText("must survive a cancel")

        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            runGitTransportOpWithRetry<Unit, Long>(
                schedule = RetryPolicies.gitTransportTransientImmediate,
                beforeRetry = { deleteDirectoryContentsForRetry(dir) },
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
                onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
                onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
            ) {
                callCount++
                throw CanceledException("clone cancelled")
            }
        }

        assertEquals(1, callCount, "a Cancelled failure must never be retried")
        assertTrue(File(dir, "left-alone").exists(), "beforeRetry must not have run — the directory must be intact after a cancel")
    }

    /**
     * The retry-then-succeed happy path, at the interface level clone() actually uses: the
     * cleanup runs before the 2nd attempt and the retry proceeds normally afterward.
     */
    @Test
    fun `beforeRetry directory cleanup runs before a real automatic retry and the retry still succeeds`() = runTest {
        val dir = createTempDirectory("stelekit_retry_cleanup_success_").toFile().also { tempDirs += it }
        File(dir, "partial").writeText("leftover")
        var callCount = 0
        var cleanupRan = false

        val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
            schedule = RetryPolicies.gitTransportTransientImmediate,
            beforeRetry = {
                cleanupRan = true
                deleteDirectoryContentsForRetry(dir)
            },
            onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
            onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
            onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
        ) {
            callCount++
            if (callCount < 2) throw transientFailure()
            Unit.right()
        }

        assertEquals(2, callCount)
        assertTrue(cleanupRan, "beforeRetry must run ahead of the 2nd attempt")
        assertTrue(dir.listFiles()?.isEmpty() == true, "expected the directory to be cleaned before the successful retry")
        assertIs<Either.Right<Unit>>(result)
    }
}
