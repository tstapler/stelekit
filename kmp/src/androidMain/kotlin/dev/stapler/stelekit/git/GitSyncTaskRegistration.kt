// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime

/**
 * Registers `git-sync` and its adapter hooks. Called only from `SteleKitApplication.onCreate`, right after
 * `registerAndroidTasks`; `jobs.schedule` stays git-free (ADR-001), so git-sync lives here instead. Idempotent:
 * an identical spec re-registers as a no-op. No Desktop registration: `PeriodicSyncDriver` owns that loop.
 *
 * [cold] is the closed-process fetch-only runner; Story 3.1.2 wires the real one when `GitSyncWorker` becomes
 * the thin shim over this task. Until then a cold tick through the task is a deliberate skip.
 */
fun registerGitSyncTask(registry: TaskRegistry, cold: ColdPathRunner? = null) {
    registry.register(GitSyncTask.KIND, GitSyncTask.spec, GitSyncTask(cold = cold))
    TaskWorkerRuntime.registerHooks(GitSyncTask.KIND, GitSyncAdapterHooks)
}
