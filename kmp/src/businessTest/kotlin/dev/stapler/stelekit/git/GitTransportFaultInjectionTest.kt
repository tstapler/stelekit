// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.MidTransferFaultInjectingProgressMonitor
import dev.stapler.stelekit.resilience.RetryPolicies
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git

/**
 * git-sync-resilience Story 6.1.2 (Epic 6.1, "Deterministic Mid-Transfer Failure Harness") — the
 * ONE integration test in this plan proving [classifyGitFailure]/[runGitTransportOpWithRetry]
 * correctly retry a REAL JGit-thrown transport exception, not the hand-constructed
 * `TransportException(cause = SocketException(...))` every other "Transient" test in this project
 * uses (`GitTransportRetryTest.kt`, `GitOperationSupportClassifierTest.kt`). Deliberately scoped to
 * one test, not a fault matrix, per `research/pitfalls.md` §5.2's own guidance ("a small number of
 * slow/flaky-tolerant integration tests, not the primary coverage mechanism") — this one exercises
 * a real local `file://`-style clone against a real temp-dir origin repo, so it's slower and
 * (marginally) less deterministic than every other test in this suite; it earns that cost by
 * proving the one thing a hand-rolled `op` lambda can't: that JGit's own real exception-propagation
 * path produces something [classifyGitFailure] actually recognizes.
 */
class GitTransportFaultInjectionTest {

    private fun setIdentity(git: Git) {
        val cfg = git.repository.config
        cfg.setString("user", null, "name", "Stelekit Test")
        cfg.setString("user", null, "email", "stelekit-test@example.com")
        cfg.save()
    }

    @Test
    fun `a real JGit clone against a local file remote that aborts mid-transfer throws an exception classifyGitFailure classifies Transient, and runGitTransportOpWithRetry retries it to a successful 2nd attempt`() =
        runTest {
            val originDir = createTempDirectory("stelekit_fault_injection_origin_").toFile()
            Git.init().setDirectory(originDir).call().use { git ->
                setIdentity(git)
                File(originDir, "note.md").writeText("- hello\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("seed commit").call()
            }
            val targetDir = createTempDirectory("stelekit_fault_injection_target_").toFile()

            val faultInjector = MidTransferFaultInjectingProgressMonitor()
            var callCount = 0
            var observedFailureClass: GitFailureClass? = null

            val result: Either<DomainError.GitError, Unit> = runGitTransportOpWithRetry(
                schedule = RetryPolicies.gitTransportTransientImmediate,
                onAttempt = { _, failureClass -> failureClass?.let { observedFailureClass = it } },
                // Story 2.1.3's real beforeRetry cleanup — a retry after an aborted clone must wipe
                // the half-populated target directory, exactly as AndroidGitRepository/
                // JvmGitRepository.clone() do in production.
                beforeRetry = { deleteDirectoryContentsForRetry(targetDir) },
                onAuthFailed = { e -> DomainError.GitError.AuthFailed(e.message ?: "Authentication failed") },
                onFailed = { e -> DomainError.GitError.CloneFailed(e.message ?: "Clone failed") },
                onExhausted = { attempts, last -> DomainError.GitError.RetryExhausted(attempts, last) },
            ) {
                callCount++
                Git.cloneRepository()
                    .setURI(originDir.absolutePath)
                    .setDirectory(targetDir)
                    .setProgressMonitor(faultInjector)
                    .call()
                    .close()
                Unit.right()
            }

            assertEquals(
                GitFailureClass.Transient,
                observedFailureClass,
                "the real JGit-propagated exception from the aborted first attempt must classify as Transient",
            )
            assertEquals(2, callCount, "the first attempt must abort mid-transfer and be retried exactly once, succeeding on the 2nd")
            assertIs<Either.Right<Unit>>(result)
            assertTrue(File(targetDir, "note.md").exists(), "the retried clone must have completed successfully")
        }
}
