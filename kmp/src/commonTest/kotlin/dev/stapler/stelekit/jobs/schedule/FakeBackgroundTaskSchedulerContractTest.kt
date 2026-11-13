// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the adapter contract against the fake with WorkManager-like capabilities. */
class FakeBackgroundTaskSchedulerContractTest : BackgroundTaskSchedulerContract() {
    override fun createScheduler(ledgerSettings: Settings, store: TaskSettingsStore, registry: TaskRegistry): BackgroundTaskScheduler =
        FakeBackgroundTaskScheduler(
            capabilities = FakeBackgroundTaskScheduler.DEFAULT_CAPABILITIES,
            ledger = AppliedConfigLedger(ledgerSettings),
            store = store,
            registry = registry,
        )

    override suspend fun seedExternalPeriodic(scheduler: BackgroundTaskScheduler, key: TaskKey, config: TaskConfig) =
        (scheduler as FakeBackgroundTaskScheduler).seedExternalPeriodic(key, config)

    override suspend fun pendingOneShot(scheduler: BackgroundTaskScheduler, key: TaskKey): PendingOneShot? =
        (scheduler as FakeBackgroundTaskScheduler).oneShot(key)?.let { PendingOneShot(it.requestId, it.effective.network) }

    @Test
    fun fireNow_should_skipWithoutRunningBody_When_UnmeteredPolicyOnMeteredNetwork() = runTest {
        val registry = TaskRegistry()
        var invocations = 0
        val key = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
        val constraints = TaskConstraints(network = NetworkPolicy.Unmetered)
        val defaultConfig = TaskConfig(true, Schedule.of(60, 0), constraints)
        registry.register(
            key.kind,
            TaskSpec(JobKind.GitSync, "Git sync", TaskShape.Periodic(defaultConfig.schedule), defaultConfig, true, 0),
            BackgroundTask { _, _ -> invocations++; TaskRunResult.Success },
        )
        val fake = FakeBackgroundTaskScheduler(ledger = AppliedConfigLedger(ContractSettings()), registry = registry)
        fake.apply(key, defaultConfig)

        val skipped = fake.fireNow(key, ConstraintState(online = true, isMetered = true, charging = null))
        assertEquals(TaskRunResult.Skipped("metered"), skipped)
        assertEquals(0, invocations)

        val ran = fake.fireNow(key, ConstraintState(online = true, isMetered = false, charging = null))
        assertEquals(TaskRunResult.Success, ran)
        assertEquals(1, invocations)
    }
}

/** Same contract against a fake that supports neither unmetered nor a 15-minute floor, exercising the downgrade branches. */
class FakeBackgroundTaskSchedulerMinimalCapabilitiesContractTest : BackgroundTaskSchedulerContract() {
    private val minimal = SchedulerCapabilities(
        minIntervalMinutes = 1,
        minFlexMinutes = 1,
        supportsUnmetered = false,
        supportsCharging = false,
        supportsJitter = false,
        runsWhenClosed = false,
        schedulesWork = true,
    )

    override fun createScheduler(ledgerSettings: Settings, store: TaskSettingsStore, registry: TaskRegistry): BackgroundTaskScheduler =
        FakeBackgroundTaskScheduler(minimal, AppliedConfigLedger(ledgerSettings), store, registry)

    override suspend fun seedExternalPeriodic(scheduler: BackgroundTaskScheduler, key: TaskKey, config: TaskConfig) =
        (scheduler as FakeBackgroundTaskScheduler).seedExternalPeriodic(key, config)

    override suspend fun pendingOneShot(scheduler: BackgroundTaskScheduler, key: TaskKey): PendingOneShot? =
        (scheduler as FakeBackgroundTaskScheduler).oneShot(key)?.let { PendingOneShot(it.requestId, it.effective.network) }
}
