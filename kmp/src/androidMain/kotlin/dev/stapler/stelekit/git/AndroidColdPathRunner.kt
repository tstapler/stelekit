// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore

/**
 * The closed-process, fetch-only tick for `git-sync`: a standalone fetch with no [GitSyncService]. The
 * clone-worker yield is a `GitSyncAdapterHooks` step, not here. Needs the DB, driver and JGit, which
 * commonMain cannot import, so `GitSyncTask` receives it as a [ColdPathRunner].
 *
 * A throwaway, uninitialized `PlatformFileSystem()` is deliberately fine: `fetch()` only updates
 * remote-tracking refs and never touches the working tree, so no shadow write-back happens here. The
 * user's next foreground merge surfaces fetched changes into SAF.
 */
class AndroidColdPathRunner(private val context: Context) : ColdPathRunner {
    override suspend fun run(graphId: String): TickOutcome {
        CredentialStore.init(context)
        DriverFactory.setContext(context)
        val outcome = runColdFetch(
            loadConfig = { loadGitConfigFromDb(graphId) },
            gitRepository = AndroidGitRepository(context = context, fileSystem = PlatformFileSystem()),
            staleness = SyncStalenessStore(PlatformSettings()),
            clock = { System.currentTimeMillis() },
        )
        return outcome.toTickOutcome()
    }
}

/** No git config row means there is nothing to fetch: a deliberate skip, not a failure (matches the old `Result.success()`). */
internal fun ColdSyncOutcome.toTickOutcome(): TickOutcome = when (this) {
    is ColdSyncOutcome.Fetched -> TickOutcome.Fetched
    is ColdSyncOutcome.Error -> TickOutcome.Failure(cause::class.simpleName ?: "GitError", cause)
    ColdSyncOutcome.NoConfig -> TickOutcome.Skip(SkipReason.Policy(ScheduledSkipReason.RepoState))
}
