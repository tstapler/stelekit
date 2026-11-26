// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.ui.screens.git.CloneMode
import dev.stapler.stelekit.ui.screens.git.GitSetupFormSnapshot
import dev.stapler.stelekit.ui.screens.git.testGitConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 1.4d1: the setup screen's connection test takes the repo gate like any other mutating entry point. */
class EntryPointGateTest {
    private val gate = RepoOpGate()

    private class RecordingRepository(val gate: RepoOpGate) : StubGitRepository() {
        var fetches = 0
        var heldDuringFetch = false
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            fetches++
            heldDuringFetch = gate.isHeld(checkNotNull(gateKeyFor(config)))
            return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        }
    }

    private fun form() = GitSetupFormSnapshot(
        graphId = "graph-1",
        cloneMode = CloneMode.UseExistingClone,
        cloneUrl = "",
        repoRoot = "/tmp/entry-point-gate-repo",
        wikiSubdir = "",
        graphName = "",
        graphDescription = "",
        cloneStorageLocation = null,
        authType = GitAuthType.NONE,
        sshKeyPath = "",
        sshPassphrase = "",
        httpsToken = "",
        oauthConnectedAs = null,
        selectedHttpsConnectionId = null,
        selectedOauthConnectionId = null,
        remoteBranch = "main",
        pollIntervalMinutes = 15,
    )

    @Test
    fun testGitConnection_should_HoldTheGateDuringFetchAndReleaseAfter() = runTest {
        val repo = RecordingRepository(gate)

        val result = testGitConnection(form(), repo, CredentialStore(), gate)

        assertTrue(result.isRight())
        assertTrue(repo.heldDuringFetch, "the fetch must run with the gate held")
        assertFalse(gate.isHeld(checkNotNull(repo.gateKeyForRoot("/tmp/entry-point-gate-repo"))))
    }

    @Test
    fun testGitConnection_should_ReturnSyncInProgressAndNotFetch_When_ASyncHoldsTheRepository() = runTest {
        val repo = RecordingRepository(gate)
        val hold = checkNotNull(gate.tryAcquire(checkNotNull(repo.gateKeyForRoot("/tmp/entry-point-gate-repo")), SyncOrigin.UiPress))

        val result = testGitConnection(form(), repo, CredentialStore(), gate)

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress), (result as Either.Left).value)
        assertEquals(0, repo.fetches)
        gate.releaseOrHandOff(hold)
    }

    @Test
    fun withExclusiveLease_should_RunNothing_When_TheKeyCannotBeResolved() = runTest {
        var ran = false

        val result = gate.withExclusiveLease(null, SyncOrigin.Clone, DomainError.GitError.CloneFailed("no key")) {
            ran = true
            Unit.right()
        }

        assertIs<DomainError.GitError.CloneFailed>((result as Either.Left).value)
        assertFalse(ran)
    }

    @Test
    fun withExclusiveLease_should_ReleaseAndDropAQueuedFollowUp_When_APressCoalescedIntoIt() = runTest {
        val result = gate.withExclusiveLease("k", SyncOrigin.Clone, DomainError.GitError.CloneFailed("x")) {
            assertTrue(gate.requestRerun("k"), "a UI press coalescing into the clone queues one rerun")
            Unit.right()
        }

        assertTrue(result.isRight())
        assertFalse(gate.isHeld("k"), "the queued rerun is dropped, never left holding the key")
    }

    @Test
    fun cloneKey_should_BeTheSameWithAndWithoutTrailingSlash() {
        val repo = StubGitRepository()
        assertEquals(repo.gateKeyForRoot("/tmp/dest"), repo.gateKeyForRoot("/tmp/dest/"))
    }
}
