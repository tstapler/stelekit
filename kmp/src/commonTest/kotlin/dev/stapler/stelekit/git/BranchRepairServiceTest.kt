// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BranchRepairServiceTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** [ignoreWrites] simulates a store that accepts a write and silently keeps the old value. */
    private class FakeConfigRepository(var config: GitConfig?, var ignoreWrites: Boolean = false) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = config.right()
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> {
            if (!ignoreWrites) this.config = config
            return Unit.right()
        }
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = flowOf(config.right())
    }

    private fun config(branch: String) =
        GitConfig(graphId = "g", repoRoot = "/r", wikiSubdir = null, remoteBranch = branch, authType = GitAuthType.NONE)

    private val proposal = BranchRepairProposal("g", from = "main", to = "master", available = listOf("master"))

    @Test
    fun `apply writes the branch, reads it back and marks the first-sync review pending`() = runTest {
        val repo = FakeConfigRepository(config("main"))
        val firstSync = FirstSyncConfirmation(MapSettings())

        val result = BranchRepairService(repo, firstSync).apply(proposal)

        assertEquals(BranchRepairResult.Repaired("main", "master"), result)
        assertEquals("master", repo.config?.remoteBranch)
        assertTrue(firstSync.isReviewPending("g"))
        assertEquals("main", firstSync.previousBranch("g"))
    }

    @Test
    fun `a write that does not stick is reported as a read-back mismatch`() = runTest {
        val repo = FakeConfigRepository(config("main"), ignoreWrites = true)
        val firstSync = FirstSyncConfirmation(MapSettings())

        val result = BranchRepairService(repo, firstSync).apply(proposal)

        assertEquals(BranchRepairResult.ReadBackMismatch(expected = "master", actual = "main"), result)
        assertFalse(firstSync.isReviewPending("g"))
    }

    @Test
    fun `a stale proposal writes nothing`() = runTest {
        val repo = FakeConfigRepository(config("trunk"))

        val result = BranchRepairService(repo).apply(proposal)

        assertEquals(BranchRepairResult.Stale("trunk"), result)
        assertEquals("trunk", repo.config?.remoteBranch)
    }

    @Test
    fun `a target the remote does not have writes nothing`() = runTest {
        val repo = FakeConfigRepository(config("main"))

        val result = BranchRepairService(repo).apply(proposal.copy(to = "nope"))

        assertEquals(BranchRepairResult.TargetNotOnRemote("nope"), result)
        assertEquals("main", repo.config?.remoteBranch)
    }

    @Test
    fun `repair is reversible main to master and back`() = runTest {
        val repo = FakeConfigRepository(config("main"))
        val service = BranchRepairService(repo)

        service.apply(proposal)
        val back = service.apply(BranchRepairProposal("g", from = "master", to = "main", available = listOf("main", "master")))

        assertIs<BranchRepairResult.Repaired>(back)
        assertEquals("main", repo.config?.remoteBranch)
    }

    @Test
    fun `proposalFor uses the detected default only when the remote really has it`() {
        val service = BranchRepairService(FakeConfigRepository(null))
        val error = DomainError.GitError.RemoteBranchNotFound("origin", "main", listOf("master"))

        assertEquals(proposal, service.proposalFor("g", error, DefaultBranchDetection.Detected("master")))
        assertNull(service.proposalFor("g", error, DefaultBranchDetection.Detected("ghost")))
        assertNull(service.proposalFor("g", error, DefaultBranchDetection.Ambiguous(listOf("a", "b"))))
        assertNull(service.proposalFor("g", error, DefaultBranchDetection.Unreachable("offline")))
    }

    @Test
    fun `copy details never include URL credentials`() {
        val error = DomainError.GitError.RemoteBranchNotFound("origin", "main", listOf("master"))

        val text = BranchRepairService(FakeConfigRepository(null))
            .copyDetails(error, "https://user:ghp_secret@github.com/me/notes.git")

        assertFalse("ghp_secret" in text)
        assertTrue("https://github.com/me/notes.git" in text)
    }

    @Test
    fun `first-sync confirmation is per remote and branch and clears the pending review`() {
        val firstSync = FirstSyncConfirmation(MapSettings())
        firstSync.markReviewPending("g", "main")
        assertFalse(firstSync.isConfirmed("g", "origin", "master"))

        firstSync.confirm("g", "origin", "master")

        assertTrue(firstSync.isConfirmed("g", "origin", "master"))
        assertFalse(firstSync.isConfirmed("g", "origin", "main"))
        assertFalse(firstSync.isReviewPending("g"))
        assertNull(firstSync.previousBranch("g"))
    }
}
