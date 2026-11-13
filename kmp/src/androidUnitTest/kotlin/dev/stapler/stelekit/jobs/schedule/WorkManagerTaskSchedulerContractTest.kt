// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.git.GitSyncWorkerBinding
import dev.stapler.stelekit.platform.Settings
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the shared adapter contract against the WorkManager adapter (capabilities: 15 min floor). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerTaskSchedulerContractTest : BackgroundTaskSchedulerContract() {
    private lateinit var harness: WorkManagerTestHarness

    @Before
    fun setUpWorkManager() {
        harness = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
    }

    override fun createScheduler(ledgerSettings: Settings, store: TaskSettingsStore, registry: TaskRegistry): BackgroundTaskScheduler =
        WorkManagerTaskScheduler(harness.context, registry, store, AppliedConfigLedger(ledgerSettings), workers = GitSyncWorkerBinding.resolver)

    override suspend fun seedExternalPeriodic(scheduler: BackgroundTaskScheduler, key: TaskKey, config: TaskConfig) =
        (scheduler as WorkManagerTaskScheduler).seedExternalPeriodic(key, config)

    override suspend fun pendingOneShot(scheduler: BackgroundTaskScheduler, key: TaskKey): PendingOneShot? =
        (scheduler as WorkManagerTaskScheduler).pendingOneShot(key)?.let { (id, network) -> PendingOneShot(id, network) }
}
