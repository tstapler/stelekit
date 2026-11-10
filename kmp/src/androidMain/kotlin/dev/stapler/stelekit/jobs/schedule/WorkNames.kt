// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/**
 * Tags every scheduler-owned request carries. Unique-work names come from `TaskKey.workName()`
 * (commonMain; ADR-005). Periodic requests never carry `JobTags.ANY`, so an enabled graph does not
 * show a permanent `Queued` row in the jobs list.
 */
object SchedTags {
    const val ANY = "stelekit-sched"
    private const val SCOPE_PREFIX = "stelekit-sched-scope:"
    private const val KIND_ID_PREFIX = "stelekit-sched-kind:"

    fun scope(scope: TaskScope) = SCOPE_PREFIX + scope.value
    fun kindId(kind: TaskKind) = KIND_ID_PREFIX + kind.id

    fun scopeOf(tags: Set<String>): TaskScope? =
        tags.firstOrNull { it.startsWith(SCOPE_PREFIX) }?.removePrefix(SCOPE_PREFIX)?.let(::TaskScope)

    fun kindIdOf(tags: Set<String>): TaskKind? =
        tags.firstOrNull { it.startsWith(KIND_ID_PREFIX) }?.removePrefix(KIND_ID_PREFIX)
            ?.takeIf { it.matches(Regex("[a-z0-9-]+")) }?.let(::TaskKind)
}

/** WorkManager input-data keys shared by the adapter and the workers it enqueues. */
object TaskWorkKeys {
    /** `"any"` / `"unmetered"`; absent (a pre-upgrade job) means [NetworkPolicy.Any]. */
    const val KEY_NETWORK_POLICY = "network_policy"
    const val KEY_TASK_KIND = "task_kind"
    const val KEY_TASK_SCOPE = "task_scope"

    fun encode(policy: NetworkPolicy): String = when (policy) {
        NetworkPolicy.Any -> "any"
        NetworkPolicy.Unmetered -> "unmetered"
    }

    fun decode(raw: String?): NetworkPolicy = if (raw == "unmetered") NetworkPolicy.Unmetered else NetworkPolicy.Any
}
