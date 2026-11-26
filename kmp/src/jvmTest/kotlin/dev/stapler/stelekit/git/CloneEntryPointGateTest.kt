// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.testsupport.cloningStub
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 1.4d2: the Desktop clone launcher takes the repo gate for the destination's `.git` (Android worker: androidUnitTest). */
class CloneEntryPointGateTest {
    private val gate = RepoOpGate()
    private val dest = Files.createTempDirectory("clone_gate_").toFile()

    private fun key(path: String) = checkNotNull(JvmGitRepository().gateKeyForRoot(path))

    @Test
    fun launcher_should_HoldTheGateDuringCloneAndReleaseAfter() = runTest {
        var heldDuring = false
        val repo = cloningStub { _, path, _, _ ->
            heldDuring = gate.isHeld(key(path))
            Unit.right()
        }
        val launcher = JvmGitCloneWorkerLauncher(JvmKeyedStub(repo), gate)

        val result = launcher.launchClone("g", "https://example.invalid/r.git", dest.path, GitAuth.None, {}, {}, null)

        assertTrue(result.isRight())
        assertTrue(heldDuring, "the clone must run with the destination's gate held")
        assertFalse(gate.isHeld(key(dest.path)))
    }

    @Test
    fun launcher_should_ReturnSyncInProgressAndNotClone_When_TheDestinationIsHeld() = runTest {
        var cloned = false
        val repo = cloningStub { _, _, _, _ -> cloned = true; Unit.right() }
        val hold = checkNotNull(gate.tryAcquire(key(dest.path), SyncOrigin.UiPress))
        val launcher = JvmGitCloneWorkerLauncher(JvmKeyedStub(repo), gate)

        val result = launcher.launchClone("g", "https://example.invalid/r.git", dest.path, GitAuth.None, {}, {}, null)

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress), (result as Either.Left).value)
        assertFalse(cloned)
        gate.releaseOrHandOff(hold)
    }

    @Test
    fun destinationKey_should_EqualTheCanonicalGitDirKey_WithTrailingSlashAndThroughASymlink() {
        val canonical = checkNotNull(RepoOpGate.keyForOrNull(File(dest, ".git")))
        val link = File(dest.parentFile, "clone_gate_link_${dest.name}")
        Files.createSymbolicLink(link.toPath(), dest.toPath())
        try {
            assertEquals(canonical, key(dest.path))
            assertEquals(canonical, key(dest.path + "/"))
            assertEquals(canonical, key(link.path), "a symlinked destination must map to the same key")
        } finally {
            link.delete()
        }
    }

    /** Delegates clone to [delegate] but derives gate keys like the real Desktop repository. */
    private class JvmKeyedStub(private val delegate: GitRepository) : GitRepository by delegate {
        private val real = JvmGitRepository()
        override fun gateKeyForRoot(repoRoot: String): String? = real.gateKeyForRoot(repoRoot)
    }
}
