// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphRemovalTasksTest {
    private val gitKind = TaskKind("git-sync")
    private val syncKind = TaskKind("other-sync")
    private val downloadKind = TaskKind("model-download")
    private val removed = TaskScope("g1")
    private val kept = TaskScope("g2")

    private val config = TaskConfig(true, Schedule.of(60, 0), TaskConstraints())
    private val noop = BackgroundTask { _, _ -> TaskRunResult.Success }

    private val registry = TaskRegistry().apply {
        register(gitKind, TaskSpec(null, "Git sync", TaskShape.Periodic(config.schedule), config, true, 0), noop)
        register(syncKind, TaskSpec(null, "Other sync", TaskShape.Periodic(config.schedule), config, true, 0), noop)
        register(downloadKind, TaskSpec(null, "Model", TaskShape.OneShot, config, false, 3), noop)
    }

    /** Fails reads/writes of one settings key only, so a single tombstone step throws. */
    private class FailingKeySettings(private val delegate: MapSettings, private val failKey: String) : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = delegate.getBoolean(key, defaultValue)
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
        override fun getString(key: String, defaultValue: String) = delegate.getString(key, defaultValue)
        override fun putString(key: String, value: String) {
            if (key == failKey) throw IllegalStateException("write failed for $key")
            delegate.putString(key, value)
        }
        override fun containsKey(key: String) = delegate.containsKey(key)
    }

    /** Delegates to a fake but throws from [cancelAll]. */
    private class ThrowingCancelAll(private val fake: FakeBackgroundTaskScheduler) : BackgroundTaskScheduler by fake {
        override suspend fun cancelAll(scope: TaskScope) {
            fake.calls += "cancelAll:${scope.value}"
            throw IllegalStateException("cancelAll failed")
        }
    }

    private fun fake() = FakeBackgroundTaskScheduler(ledger = AppliedConfigLedger(MapSettings()), registry = registry)

    @Test
    fun onRemoved_should_cancelAllThenTombstonePeriodicKindsOnly_When_GraphRemoved() = runTest {
        val settings = MapSettings()
        val store = TaskSettingsStore(settings, LegacyConfigSeed.None, registry)
        val scheduler = fake()
        store.write(TaskKey(gitKind, removed), TaskEntry(true, null))
        store.write(TaskKey(gitKind, kept), TaskEntry(true, null))

        GraphRemovalTasks(scheduler, store, registry, this).onRemoved(removed)
        advanceUntilIdle()

        assertEquals(listOf("cancelAll:g1"), scheduler.calls)
        assertTrue(store.isTombstoned(TaskKey(gitKind, removed)))
        assertTrue(store.isTombstoned(TaskKey(syncKind, removed)))
        assertFalse(store.isTombstoned(TaskKey(downloadKind, removed)), "one-shot kinds are not tombstoned")
        assertFalse(store.isTombstoned(TaskKey(gitKind, kept)), "other graphs are untouched")
    }

    @Test
    fun onRemoved_should_stillTombstone_When_CancelAllThrows() = runTest {
        val store = TaskSettingsStore(MapSettings(), LegacyConfigSeed.None, registry)
        val scheduler = fake()

        GraphRemovalTasks(ThrowingCancelAll(scheduler), store, registry, this).onRemoved(removed)
        advanceUntilIdle()

        assertEquals(listOf("cancelAll:g1"), scheduler.calls)
        assertTrue(store.isTombstoned(TaskKey(gitKind, removed)))
        assertTrue(store.isTombstoned(TaskKey(syncKind, removed)))
    }

    @Test
    fun onRemoved_should_tombstoneRemainingKinds_When_OneTombstoneThrows() = runTest {
        val backing = MapSettings()
        val failing = FailingKeySettings(backing, TaskSettingsKeys.entry(TaskKey(gitKind, removed)))
        val store = TaskSettingsStore(failing, LegacyConfigSeed.None, registry)
        val scheduler = fake()

        GraphRemovalTasks(scheduler, store, registry, this).onRemoved(removed)
        advanceUntilIdle()

        assertEquals(listOf("cancelAll:g1"), scheduler.calls)
        assertFalse(store.isTombstoned(TaskKey(gitKind, removed)), "the failing write did not land")
        assertTrue(store.isTombstoned(TaskKey(syncKind, removed)), "later kinds still tombstoned")
    }

    @Test
    fun cleanup_should_runWork_When_openAndDoNothing_When_closed() = runTest {
        val store = TaskSettingsStore(MapSettings(), LegacyConfigSeed.None, registry)
        val scheduler = fake()
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val cleanup = GraphRemovalCleanup(scheduler, store, registry, dispatcher)

        cleanup.onRemoved(removed)
        advanceUntilIdle()
        assertEquals(listOf("cancelAll:g1"), scheduler.calls)

        cleanup.close()
        cleanup.onRemoved(kept)
        advanceUntilIdle()
        assertEquals(listOf("cancelAll:g1"), scheduler.calls, "a closed cleanup's scope no longer launches")
        assertFalse(store.isTombstoned(TaskKey(gitKind, kept)))
    }
}
