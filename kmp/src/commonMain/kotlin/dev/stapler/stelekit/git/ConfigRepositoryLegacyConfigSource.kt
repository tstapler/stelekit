// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CancellationException

/**
 * [GitLegacyConfigSource] over the open graph's [GitConfigRepository] (Desktop graph open, the Settings
 * glue and the Android active-graph path). A repository that errors or throws is
 * [LegacyGitConfigResult.Unavailable], never `Found(hasRemote = false)`: an unreadable config must not
 * look like "no remote", or the migration would record a marker and drop the user's sync.
 *
 * The repository belongs to [boundGraphId]'s database; any other graph's config is not in it, so it reads as
 * [LegacyGitConfigResult.Unavailable] rather than a false "no remote".
 */
class ConfigRepositoryLegacyConfigSource(
    private val configRepository: GitConfigRepository,
    private val boundGraphId: String,
) : GitLegacyConfigSource {
    override suspend fun read(graphId: String): LegacyGitConfigResult = if (graphId != boundGraphId) {
        LegacyGitConfigResult.Unavailable
    } else try {
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
