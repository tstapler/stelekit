// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SeedUnavailable
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.platform.Settings

/**
 * Seeds `git-sync` from the legacy global `scheduled_sync_enabled` flag plus the graph's remote and
 * `poll_interval_minutes`: `enabled = flag && graphHasRemote` (ADR-003). Keeps jitter 0 and the legacy
 * interval (behaviour-preserving). Reads [Settings] directly so a throwing read is not swallowed into
 * "off" (which would record a marker and lose the user's choice). Legacy keys are never modified.
 */
class LegacyGitSyncMigration(
    private val settings: Settings,
    private val source: GitLegacyConfigSource,
) : LegacyConfigSeed {
    override suspend fun seed(key: TaskKey): TaskConfig? {
        if (key.kind.id != GIT_SYNC) return null
        if (!settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false)) return null
        return when (val result = source.read(key.scope.value)) {
            LegacyGitConfigResult.Unavailable -> throw SeedUnavailable("git config unavailable for ${key.scope.value}")
            is LegacyGitConfigResult.Found -> when {
                !result.hasRemote -> null
                // A zero poll interval means "no loop" (PR #591); do not turn it into a 1-minute loop.
                result.pollIntervalMinutes <= 0 -> null
                else -> TaskConfig(true, Schedule.coerce(result.pollIntervalMinutes, 0), TaskConstraints())
            }
        }
    }

    private companion object {
        const val GIT_SYNC = "git-sync"
    }
}
