// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.flow.Flow

/**
 * Persists per-graph git configuration in SQLDelight.
 */
interface GitConfigRepository {
    suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?>
    suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit>
    suspend fun deleteConfig(graphId: String): Either<DomainError, Unit>
    fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>>
}
