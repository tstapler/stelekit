// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads a graph's pre-existing git config straight from its SQLite file (the promoted cold read), for the
 * lazy `git-sync` migration in a process where that graph is not open. One graph per call; never invoked
 * for all graphs at startup. A database that does not exist or cannot be opened or read is
 * [LegacyGitConfigResult.Unavailable], never "no remote": mistaking an unreadable DB for "no remote" would
 * silently drop the user's sync. A missing file is checked first so an unknown graph id never creates one.
 */
class AndroidGitLegacyConfigSource internal constructor(
    private val context: Context,
    private val databaseExists: (graphId: String) -> Boolean,
    private val load: (graphId: String) -> GitConfig?,
) : GitLegacyConfigSource {

    constructor(context: Context) : this(context, ::databaseFileExists, ::loadGitConfigFromDb)

    override suspend fun read(graphId: String): LegacyGitConfigResult = withContext(Dispatchers.IO) {
        try {
            DriverFactory.setContext(context)
            if (!databaseExists(graphId)) return@withContext LegacyGitConfigResult.Unavailable
            val config = load(graphId)
            LegacyGitConfigResult.Found(hasRemote = config != null, pollIntervalMinutes = config?.pollIntervalMinutes ?: 0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            LegacyGitConfigResult.Unavailable
        }
    }
}

private fun databaseFileExists(graphId: String): Boolean {
    val factory = DriverFactory()
    return File(factory.getDatabaseUrl(graphId).substringAfter("jdbc:sqlite:")).exists()
}
