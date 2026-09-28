// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [GitConfigRepository] returning a fixed [configResult] — the same shape repeated, byte-for-byte,
 * across `jvmTest`/`businessTest` `GitSyncService`-adjacent tests before this extraction. See
 * [StubGitRepository]'s KDoc for why `commonTest` is the shared location.
 */
class StubConfigRepository(
    private val configResult: Either<DomainError, GitConfig?>,
) : GitConfigRepository {
    override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = configResult
    override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
    override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
    override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = flowOf(configResult)
}
