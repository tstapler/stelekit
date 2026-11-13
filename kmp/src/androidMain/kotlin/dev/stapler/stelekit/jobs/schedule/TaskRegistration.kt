// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/**
 * Registers every Android background task kind. Called ONLY from `SteleKitApplication.onCreate` (inside
 * its `catch (e: Throwable)`) before any worker can run; `MainActivity` never registers because it runs on
 * every Activity creation. Idempotent: re-registering an equal spec is a no-op.
 *
 * No kind is registered yet: `git-sync` (Story 3.1.1) and `model-download` (Story 3.2.1) add theirs here.
 */
fun registerAndroidTasks(registry: TaskRegistry) {
    TaskWorkerRuntime.install(registry)
}
