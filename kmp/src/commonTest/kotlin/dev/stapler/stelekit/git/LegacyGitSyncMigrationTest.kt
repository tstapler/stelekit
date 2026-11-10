// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SeedUnavailable
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LegacyGitSyncMigrationTest {
    private val key = TaskKey(TaskKind("git-sync"), TaskScope("g1"))

    private fun migration(flag: Boolean, source: FakeGitLegacyConfigSource): LegacyGitSyncMigration {
        val settings = MapSettings()
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, flag)
        return LegacyGitSyncMigration(settings, source)
    }

    @Test
    fun seed_should_returnEnabledLegacyInterval_When_FlagOnAndRemotePresent() = runTest {
        val m = migration(true, FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30)))
        assertEquals(TaskConfig(true, Schedule.of(30, 0), TaskConstraints()), m.seed(key))
    }

    @Test
    fun seed_should_returnNull_When_NoRemoteOrFlagOffOrOtherKind() = runTest {
        assertNull(migration(true, FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(false, 30))).seed(key))
        val src = FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30))
        assertNull(migration(false, src).seed(key))
        assertEquals(0, src.calls)
        assertNull(migration(true, src).seed(TaskKey(TaskKind("model-download"), TaskScope(""))))
        assertNull(migration(true, FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 0))).seed(key))
    }

    @Test
    fun seed_should_throwSeedUnavailable_When_SourceCannotRead() = runTest {
        assertFailsWith<SeedUnavailable> { migration(true, FakeGitLegacyConfigSource(LegacyGitConfigResult.Unavailable)).seed(key) }
    }
}
