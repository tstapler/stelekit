// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/**
 * Kind to (spec, task) lookup. Populated once at startup on a single thread; not safe for
 * concurrent [register]. Re-registering an equal spec is a no-op (a second process start builds
 * a new task instance); a different spec for the same kind throws.
 */
class TaskRegistry {
    private val specs = LinkedHashMap<TaskKind, TaskSpec>()
    private val tasks = LinkedHashMap<TaskKind, BackgroundTask>()

    fun register(kind: TaskKind, spec: TaskSpec, task: BackgroundTask) {
        val existing = specs[kind]
        if (existing == null) {
            specs[kind] = spec
            tasks[kind] = task
            return
        }
        check(existing == spec) { "Conflicting TaskSpec registered for kind '${kind.id}'" }
    }

    fun taskFor(kind: TaskKind): BackgroundTask? = tasks[kind]
    fun specFor(kind: TaskKind): TaskSpec? = specs[kind]
    fun kinds(): Set<TaskKind> = specs.keys.toSet()
}
