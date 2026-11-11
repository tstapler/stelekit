// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigRepositoryLegacyConfigSourceTest {
    private fun config(poll: Int) = GitConfig("g1", RepoRoot("/repo"), null, authType = GitAuthType.NONE, pollIntervalMinutes = poll)

    private class FakeConfigRepository(private val get: suspend () -> Either<DomainError, GitConfig?>) : GitConfigRepository {
        override suspend fun getConfig(graphId: String) = get()
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = emptyFlow()
    }

    private fun source(get: suspend () -> Either<DomainError, GitConfig?>) = ConfigRepositoryLegacyConfigSource(FakeConfigRepository(get))

    @Test
    fun read_should_ReturnFoundWithRemoteAndInterval_When_ConfigExists() = runTest {
        assertEquals(LegacyGitConfigResult.Found(true, 30), source { config(30).right() }.read("g1"))
    }

    @Test
    fun read_should_ReturnFoundWithoutRemote_When_RepositoryHasNoConfig() = runTest {
        assertEquals(LegacyGitConfigResult.Found(false, 0), source { null.right() }.read("g1"))
    }

    @Test
    fun read_should_ReturnUnavailable_When_RepositoryThrows() = runTest {
        assertEquals(LegacyGitConfigResult.Unavailable, source { throw IllegalStateException("db closed") }.read("g1"))
    }

    @Test
    fun read_should_ReturnUnavailableNotNoRemote_When_RepositoryReturnsError() = runTest {
        assertEquals(LegacyGitConfigResult.Unavailable, source { DomainError.DatabaseError.ReadFailed("x").left() }.read("g1"))
    }
}
