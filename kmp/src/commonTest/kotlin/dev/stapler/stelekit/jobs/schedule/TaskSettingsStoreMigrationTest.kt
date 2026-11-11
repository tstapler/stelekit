// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.git.FakeGitLegacyConfigSource
import dev.stapler.stelekit.git.LegacyGitConfigResult
import dev.stapler.stelekit.git.LegacyGitSyncMigration
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskSettingsStoreMigrationTest {
    private val gitKind = TaskKind("git-sync")
    private val g1 = TaskKey(gitKind, TaskScope("g1"))
    private val g2 = TaskKey(gitKind, TaskScope("g2"))
    private val download = TaskKey(TaskKind("model-download"), TaskScope(""))

    private val gitSpecConfig = TaskConfig(false, Schedule.of(60, JitterPreset.ALittle.minutesFor(60)), TaskConstraints())
    private val registry = TaskRegistry().apply {
        register(gitKind, TaskSpec(null, "Git sync", TaskShape.Periodic(gitSpecConfig.schedule), gitSpecConfig, true, 0), BackgroundTask { _, _ -> TaskRunResult.Success })
        register(
            TaskKind("model-download"),
            TaskSpec(JobKind.entries.firstOrNull(), "Model", TaskShape.OneShot, TaskConfig(false, Schedule.of(60, 0), TaskConstraints(NetworkPolicy.Unmetered)), false, 3),
            BackgroundTask { _, _ -> TaskRunResult.Success },
        )
    }

    private class CountingSeed(var result: TaskConfig? = null, var failure: Exception? = null) : LegacyConfigSeed {
        var calls = 0
        override suspend fun seed(key: TaskKey): TaskConfig? {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    private fun store(settings: MapSettings, seed: LegacyConfigSeed = LegacyConfigSeed.None) = TaskSettingsStore(settings, seed, registry)

    private fun legacy(settings: MapSettings, flag: Boolean?, source: FakeGitLegacyConfigSource): TaskSettingsStore {
        if (flag != null) settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, flag)
        return store(settings, LegacyGitSyncMigration(settings, source))
    }

    @Test
    fun read_should_resolveDefaultsAndReportUsingDefault_When_EntryHasNoOverrides() = runTest {
        val s = store(MapSettings())
        s.writeDefaults(gitKind, TaskDefaults(Schedule.of(120, 0), TaskConstraints(NetworkPolicy.Unmetered)))
        s.write(g1, TaskEntry(true, null))
        val cfg = s.read(g1)
        assertTrue(cfg.enabled)
        assertEquals(Schedule.of(120, 0), cfg.schedule)
        assertEquals(NetworkPolicy.Unmetered, cfg.constraints.network)
        assertNull(s.readEntry(g1).overrides)
    }

    @Test
    fun read_should_useOverrides_When_EntryIsCustom() = runTest {
        val s = store(MapSettings())
        s.writeDefaults(gitKind, TaskDefaults(Schedule.of(120, 0), TaskConstraints()))
        s.write(g1, TaskEntry(true, TaskOverrides(Schedule.of(30, 3), TaskConstraints(NetworkPolicy.Unmetered))))
        assertEquals(Schedule.of(30, 3), s.read(g1).schedule)
        assertNotNull(s.readEntry(g1).overrides)
    }

    @Test
    fun read_should_notCollideWithDefaults_When_ScopeIsAppWide() = runTest {
        val s = store(MapSettings())
        s.writeDefaults(gitKind, TaskDefaults(Schedule.of(120, 0), TaskConstraints()))
        val appWide = TaskKey(gitKind, TaskScope(""))
        assertFalse(s.read(appWide).enabled)
        assertEquals(Schedule.of(120, 0), s.readDefaults(gitKind)?.schedule)
    }

    @Test
    fun read_should_returnDisabledSpecDefault_When_NoEntryAndNoLegacySource() = runTest {
        val cfg = store(MapSettings()).read(g1)
        assertFalse(cfg.enabled)
        assertEquals(gitSpecConfig.schedule, cfg.schedule)
    }

    @Test
    fun read_should_giveNewGitSyncGraphSixMinuteJitter_When_NoEntry() = runTest {
        val cfg = store(MapSettings()).read(g1)
        assertEquals(60, cfg.schedule.intervalMinutes)
        assertEquals(6, cfg.schedule.jitterMinutes)
    }

    @Test
    fun read_should_keepZeroJitterAndLegacyInterval_When_MigratedFromLegacyFlag() = runTest {
        val settings = MapSettings()
        val src = FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30))
        val s = legacy(settings, true, src)
        val cfg = s.read(g1)
        assertTrue(cfg.enabled)
        assertEquals(Schedule.of(30, 0), cfg.schedule)
        assertTrue(settings.store.containsKey(TaskSettingsKeys.entry(g1)))
        assertTrue(settings.store.containsKey(TaskSettingsKeys.migrated(g1)))
        s.read(g1)
        assertEquals(1, src.calls)
    }

    @Test
    fun read_should_leaveLegacyKeysUnchanged_When_MigrationRuns() = runTest {
        val settings = MapSettings()
        val s = legacy(settings, true, FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30)))
        val legacyBefore = settings.store.toMap()
        assertTrue(s.read(g1).enabled)
        s.write(g1, TaskEntry(false, null))
        legacyBefore.forEach { (key, value) -> assertEquals(value, settings.store[key], "legacy key $key must stay for rollback (REQ-9)") }
        assertEquals(true, settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false))
    }

    @Test
    fun read_should_migrateToEnabled_When_RemoteBearingGraphWasNeverComposed() = runTest {
        val src = FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 45))
        assertTrue(legacy(MapSettings(), true, src).read(g2).enabled)
    }

    @Test
    fun read_should_beDisabledAndWriteMarker_When_GraphHasNoRemote() = runTest {
        val settings = MapSettings()
        val s = legacy(settings, true, FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(false, 30)))
        assertFalse(s.read(g2).enabled)
        assertTrue(settings.store.containsKey(TaskSettingsKeys.migrated(g2)))
    }

    @Test
    fun read_should_beDisabled_When_LegacyFlagUnset() = runTest {
        val src = FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30))
        assertFalse(legacy(MapSettings(), null, src).read(g1).enabled)
    }

    @Test
    fun read_should_notWriteMarker_When_SourceUnavailableOrThrowing() = runTest {
        val settings = MapSettings()
        val src = FakeGitLegacyConfigSource(LegacyGitConfigResult.Unavailable)
        val s = legacy(settings, true, src)
        assertFalse(s.read(g1).enabled)
        assertFalse(settings.store.containsKey(TaskSettingsKeys.migrated(g1)))
        assertFalse(settings.store.containsKey(TaskSettingsKeys.entry(g1)))
        src.throwOnRead = true
        assertFalse(s.read(g1).enabled)
        assertFalse(settings.store.containsKey(TaskSettingsKeys.migrated(g1)))
        src.throwOnRead = false
        src.result = LegacyGitConfigResult.Found(true, 20)
        assertTrue(s.read(g1).enabled)
        assertEquals(3, src.calls)
    }

    @Test
    fun read_should_notWriteMarker_When_LegacyFlagReadThrows() = runTest {
        val settings = MapSettings()
        val seed = LegacyGitSyncMigration(settings, FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30)))
        val s = store(settings, seed)
        settings.failReads = true
        assertFalse(s.read(g1).enabled)
        settings.failReads = false
        assertFalse(settings.store.containsKey(TaskSettingsKeys.migrated(g1)))
    }

    @Test
    fun read_should_keepUserChoice_When_MigratedThenDisabledByUser() = runTest {
        val settings = MapSettings()
        val src = FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(true, 30))
        val s = legacy(settings, true, src)
        assertTrue(s.read(g1).enabled)
        s.write(g1, TaskEntry(false, s.readEntry(g1).overrides))
        assertFalse(s.read(g1).enabled)
        assertEquals(1, src.calls)
    }

    @Test
    fun read_should_neverCallSeed_When_EntryOrMarkerExists() = runTest {
        val settings = MapSettings()
        val seed = CountingSeed(TaskConfig(true, Schedule.of(5, 0), TaskConstraints()))
        val s = store(settings, seed)
        s.write(g1, TaskEntry(false, null))
        s.read(g1)
        settings.putString(TaskSettingsKeys.migrated(g2), "1")
        s.read(g2)
        assertEquals(0, seed.calls)
    }

    @Test
    fun read_should_returnSpecConstraintsIgnoringEnabled_When_OneShotKindHasNoEntry() = runTest {
        val s = store(MapSettings())
        assertEquals(NetworkPolicy.Unmetered, s.read(download).constraints.network)
        s.write(download, TaskEntry(false, TaskOverrides(null, TaskConstraints(NetworkPolicy.Any))))
        assertEquals(NetworkPolicy.Any, s.read(download).constraints.network)
        assertEquals(TaskEntry(false, TaskOverrides(null, TaskConstraints(NetworkPolicy.Any))), s.readEntry(download))
    }

    @Test
    fun readResult_should_distinguishReadFailedFromDisabled_When_SettingsThrow() = runTest {
        val settings = MapSettings()
        val s = store(settings)
        settings.failReads = true
        assertIs<TaskReadResult.ReadFailed>(s.readResult(g1))
        val cfg = s.read(g1)
        assertFalse(cfg.enabled)
        settings.failReads = false
        settings.putString(TaskSettingsKeys.entry(g1), "garbage")
        val ok = assertIs<TaskReadResult.Ok>(s.readResult(g1))
        assertFalse(ok.config.enabled)
    }

    @Test
    fun readResult_should_beReadFailed_When_SeedUnavailable() = runTest {
        val seed = CountingSeed(failure = SeedUnavailable("db"))
        assertIs<TaskReadResult.ReadFailed>(store(MapSettings(), seed).readResult(g1))
    }

    @Test
    fun read_should_beDisabledAndNeverSeed_When_KeyTombstoned() = runTest {
        val seed = CountingSeed(TaskConfig(true, Schedule.of(5, 0), TaskConstraints()))
        val s = store(MapSettings(), seed)
        assertTrue(s.tombstone(g1))
        assertFalse(s.read(g1).enabled)
        assertTrue(s.isTombstoned(g1))
        assertEquals(0, seed.calls)
    }

    @Test
    fun write_should_supersedeTombstone_When_GraphReAddedAtSamePath() = runTest {
        val seed = CountingSeed(TaskConfig(true, Schedule.of(5, 0), TaskConstraints()))
        val s = store(MapSettings(), seed)
        s.tombstone(g1)
        assertFalse(s.read(g1).enabled)
        s.write(g1, TaskEntry(true, null))
        assertTrue(s.read(g1).enabled)
        assertFalse(s.isTombstoned(g1))
        assertEquals(0, seed.calls)
    }

    @Test
    fun write_should_returnFalse_When_SettingsWriteFails() = runTest {
        val settings = MapSettings()
        settings.failWrites = true
        assertFalse(store(settings).write(g1, TaskEntry(true, null)))
    }
}
