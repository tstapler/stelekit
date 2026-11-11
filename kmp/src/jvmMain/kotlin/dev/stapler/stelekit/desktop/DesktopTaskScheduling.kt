// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.desktop

import dev.stapler.stelekit.git.registerGitSync
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.InProcessTaskScheduler
import dev.stapler.stelekit.jobs.schedule.LegacyConfigSeed
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.platform.Settings

/** Desktop's task registry: `git-sync` is listed in Settings, while `PeriodicSyncDriver` remains the executor. */
fun createDesktopTaskRegistry(): TaskRegistry = TaskRegistry().also { it.registerGitSync() }

/**
 * Desktop's in-process scheduler over [settings], the one instance the UI also reads, so run-log rows the
 * scheduler writes are visible to Settings (a second `PlatformSettings` keeps its own stale copy of the file).
 */
fun createDesktopTaskScheduler(registry: TaskRegistry, settings: Settings): InProcessTaskScheduler =
    InProcessTaskScheduler(
        registry = registry,
        store = TaskSettingsStore(settings, LegacyConfigSeed.None, registry),
        ledger = AppliedConfigLedger(settings),
        runLog = SettingsTaskRunLog(settings),
        clock = System::currentTimeMillis,
    )
