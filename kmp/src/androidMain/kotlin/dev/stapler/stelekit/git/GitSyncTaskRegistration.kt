// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime

/**
 * Registers `git-sync` and its adapter hooks. Called only from `SteleKitApplication.onCreate`, right after
 * `registerAndroidTasks`; `jobs.schedule` stays git-free (ADR-001), so git-sync lives here instead. Idempotent:
 * an identical spec re-registers as a no-op. Desktop registers via [registerGitSync] too, but `PeriodicSyncDriver` stays its executor.
 *
 * [cold] is the closed-process fetch-only runner (`AndroidColdPathRunner` in production); null makes a cold
 * tick through the task a deliberate skip.
 */
fun registerGitSyncTask(registry: TaskRegistry, cold: ColdPathRunner? = null) {
    registry.registerGitSync(GitSyncTask(cold = cold))
    TaskWorkerRuntime.registerHooks(GitSyncTask.KIND, GitSyncAdapterHooks)
}
