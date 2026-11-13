// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Injective string encoding of a [TaskKey] for settings and run-log keys (ADR-005). */
object TaskKeyEncoding {
    /** `<kind>.<len(scope)>.<scope>`: the kind has no `.`, so the first two dots always delimit the length. */
    fun encode(key: TaskKey): String = "${key.kind.id}.${key.scope.value.length}.${key.scope.value}"

    fun settingsKey(prefix: String, key: TaskKey): String = "${prefix}_${encode(key)}"

    /**
     * Run-log settings key per (key, source). Foreground uses the `task_run_log_fg` prefix; since a
     * kind never contains `_`, `task_run_log_fg_<encoded>` can never equal `task_run_log_<encoded>`.
     */
    fun runLogKey(source: RunSource, key: TaskKey): String = when (source) {
        RunSource.Background -> settingsKey("task_run_log", key)
        RunSource.Foreground -> settingsKey("task_run_log_fg", key)
    }
}
