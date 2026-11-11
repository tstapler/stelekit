// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CancellationException

/**
 * [GitLegacyConfigSource] over the open graph's [GitConfigRepository] (Desktop graph open, the Settings
 * glue and the Android active-graph path). A repository that errors or throws is
 * [LegacyGitConfigResult.Unavailable], never `Found(hasRemote = false)`: an unreadable config must not
 * look like "no remote", or the migration would record a marker and drop the user's sync.
 */
class ConfigRepositoryLegacyConfigSource(
    private val configRepository: GitConfigRepository,
) : GitLegacyConfigSource {
    override suspend fun read(graphId: String): LegacyGitConfigResult = try {
        configRepository.getConfig(graphId).fold(
            { LegacyGitConfigResult.Unavailable },
            { config -> LegacyGitConfigResult.Found(hasRemote = config != null, pollIntervalMinutes = config?.pollIntervalMinutes ?: 0) },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        LegacyGitConfigResult.Unavailable
    }
}
