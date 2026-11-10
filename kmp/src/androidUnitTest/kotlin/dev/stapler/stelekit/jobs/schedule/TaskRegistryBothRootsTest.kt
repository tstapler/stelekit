// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The Application registers kinds once; the activity-side root only builds a scheduler over the same registry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TaskRegistryBothRootsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun activitySideRoot(registry: TaskRegistry): WorkManagerTaskScheduler {
        val settings = InMemoryRootSettings()
        return WorkManagerTaskScheduler(context, registry, TaskSettingsStore(settings, LegacyConfigSeed.None, registry), AppliedConfigLedger(settings))
    }

    @Test
    fun registration_should_notThrowAndKeepOneRegistry_When_applicationThenActivityRootsRunTwice() {
        val registry = TaskRegistry()
        registerAndroidTasks(registry)
        registerAndroidTasks(registry) // second process start / re-entry: idempotent
        val before = registry.kinds()
        activitySideRoot(registry)
        activitySideRoot(registry)
        assertEquals(before, registry.kinds())
        assertSame(registry, TaskWorkerRuntime.registry)
    }

    @Test
    fun register_should_beIdempotent_When_equalSpecRegisteredFromTwoProcessStarts() {
        val registry = TaskRegistry()
        val spec = TaskSpec(
            null, "T", TaskShape.OneShot,
            TaskConfig(false, Schedule.of(15, 0), TaskConstraints()), allowedInColdPath = true, oneShotRetryCap = 5,
        )
        registry.register(TaskKind("model-download"), spec, { _, _ -> TaskRunResult.Success })
        registry.register(TaskKind("model-download"), spec, { _, _ -> TaskRunResult.Success })
        assertEquals(setOf(TaskKind("model-download")), registry.kinds())
    }

    private class InMemoryRootSettings : dev.stapler.stelekit.platform.Settings {
        private val m = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = m[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { m[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = m[key] ?: defaultValue
        override fun putString(key: String, value: String) { m[key] = value }
        override fun containsKey(key: String) = m.containsKey(key)
    }
}
