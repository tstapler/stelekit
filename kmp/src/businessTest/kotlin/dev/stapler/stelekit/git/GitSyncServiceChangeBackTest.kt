// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** "Change back" must re-check the remote instead of trusting that the previous branch exists. */
class GitSyncServiceChangeBackTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class MutableConfigRepository(var config: GitConfig) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = config.right()
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> { this.config = config; return Unit.right() }
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = flowOf(config.right())
    }

    private val graphId = "test-graph"

    /** Repaired from "main" to "master"; [remoteBranches] is what the remote has when "Change back" runs. */
    private suspend fun repairedService(remoteBranches: List<String>): Pair<GitSyncService, MutableConfigRepository> {
        val configRepo = MutableConfigRepository(sampleConfig.copy(remoteBranch = "main"))
        val repo = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
                if (config.remoteBranch in remoteBranches) FetchResult(false, 0).right()
                else DomainError.GitError.RemoteBranchNotFound(config.remoteName, config.remoteBranch, remoteBranches).left()
        }
        val fs = StubFileSystem()
        val service = GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = configRepo,
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = graphId,
            settings = MapSettings(),
        )
        service.repairBranch(BranchRepairProposal(graphId, from = "main", to = "master", available = listOf("master")))
        assertEquals("master", configRepo.config.remoteBranch)
        return service to configRepo
    }

    @Test
    fun `change back is refused when the previous branch is still not on the remote`() = runBlocking {
        val (service, configRepo) = repairedService(remoteBranches = listOf("master"))

        val result = service.changeBackBranch()

        assertEquals(BranchRepairResult.TargetNotOnRemote("main"), result)
        assertEquals("master", configRepo.config.remoteBranch)
        service.shutdown()
    }

    @Test
    fun `change back succeeds once the previous branch exists on the remote`() = runBlocking {
        val (service, configRepo) = repairedService(remoteBranches = listOf("master", "main"))

        val result = service.changeBackBranch()

        assertIs<BranchRepairResult.Repaired>(result)
        assertEquals("main", configRepo.config.remoteBranch)
        service.shutdown()
    }
}
