// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Settings key families; every encoded key goes through [TaskKeyEncoding] and is injective. */
object TaskSettingsKeys {
    const val ENTRY = "task_entry"
    const val DEFAULT = "task_default"
    const val MIGRATED = "task_migrated"
    const val APPLIED = "task_applied"

    fun entry(key: TaskKey): String = TaskKeyEncoding.settingsKey(ENTRY, key)
    fun migrated(key: TaskKey): String = TaskKeyEncoding.settingsKey(MIGRATED, key)
    fun applied(key: TaskKey): String = TaskKeyEncoding.settingsKey(APPLIED, key)

    /** One record per kind; the kind charset has no `_`, so this cannot collide with the encoded-key families. */
    fun defaults(kind: TaskKind): String = "${DEFAULT}_${kind.id}"
}

/**
 * Versioned (`v1`) string codec for [TaskEntry] and [TaskDefaults]. The value never embeds the kind
 * (the settings key carries it). Decoders return null for anything unparseable; callers fail closed.
 *
 * Entry: `v1|<0/1>|-` (using default), `v1|<0/1>|O|<schedule or ->|<constraints>`, or `v1|T` (tombstone).
 * Defaults: `v1|<schedule or ->|<constraints>`. Schedule: `interval,jitter`. Constraints: `any|unmetered,<0/1>,<0/1>`.
 */
object TaskConfigCodec {
    private const val VERSION = "v1"
    const val TOMBSTONE = "v1|T"

    fun encodeEntry(entry: TaskEntry): String {
        val enabled = if (entry.enabled) "1" else "0"
        val ov = entry.overrides ?: return "$VERSION|$enabled|-"
        return "$VERSION|$enabled|O|${encodeSchedule(ov.schedule)}|${encodeConstraints(ov.constraints)}"
    }

    fun decodeEntry(raw: String): TaskEntry? {
        if (raw == TOMBSTONE) return DISABLED_ENTRY
        val p = raw.split('|')
        if (p.size < 3 || p[0] != VERSION) return null
        val enabled = decodeBool(p[1]) ?: return null
        if (p.size == 3 && p[2] == "-") return TaskEntry(enabled, null)
        if (p.size != 5 || p[2] != "O") return null
        val schedule = decodeSchedule(p[3]) ?: return null
        val constraints = decodeConstraints(p[4]) ?: return null
        return TaskEntry(enabled, TaskOverrides(schedule.value, constraints))
    }

    fun encodeDefaults(defaults: TaskDefaults): String =
        "$VERSION|${encodeSchedule(defaults.schedule)}|${encodeConstraints(defaults.constraints)}"

    fun decodeDefaults(raw: String): TaskDefaults? {
        val p = raw.split('|')
        if (p.size != 3 || p[0] != VERSION) return null
        val schedule = decodeSchedule(p[1]) ?: return null
        val constraints = decodeConstraints(p[2]) ?: return null
        return TaskDefaults(schedule.value, constraints)
    }

    val DISABLED_ENTRY = TaskEntry(enabled = false, overrides = null)

    private class Box<T>(val value: T)

    private fun encodeSchedule(s: Schedule?): String = s?.let { "${it.intervalMinutes},${it.jitterMinutes}" } ?: "-"

    private fun decodeSchedule(raw: String): Box<Schedule?>? {
        if (raw == "-") return Box(null)
        val p = raw.split(',')
        if (p.size != 2) return null
        val interval = p[0].toIntOrNull() ?: return null
        val jitter = p[1].toIntOrNull() ?: return null
        return Box(Schedule.coerce(interval, jitter))
    }

    private fun encodeConstraints(c: TaskConstraints): String {
        val net = if (c.network == NetworkPolicy.Unmetered) "unmetered" else "any"
        return "$net,${if (c.requiresCharging) 1 else 0},${if (c.batteryNotLow) 1 else 0}"
    }

    private fun decodeConstraints(raw: String): TaskConstraints? {
        val p = raw.split(',')
        if (p.size != 3) return null
        val net = when (p[0]) {
            "any" -> NetworkPolicy.Any
            "unmetered" -> NetworkPolicy.Unmetered
            else -> return null
        }
        return TaskConstraints(net, decodeBool(p[1]) ?: return null, decodeBool(p[2]) ?: return null)
    }

    private fun decodeBool(raw: String): Boolean? = when (raw) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
