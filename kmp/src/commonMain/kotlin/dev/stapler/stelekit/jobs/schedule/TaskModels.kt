// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.jobs.JobKind

/** Fixed interval plus jitter window. Construct via [of] (throws) or [coerce] (never fails). */
class Schedule private constructor(val intervalMinutes: Int, val jitterMinutes: Int) {
    override fun equals(other: Any?): Boolean =
        other is Schedule && other.intervalMinutes == intervalMinutes && other.jitterMinutes == jitterMinutes

    override fun hashCode(): Int = 31 * intervalMinutes + jitterMinutes
    override fun toString(): String = "Schedule(intervalMinutes=$intervalMinutes, jitterMinutes=$jitterMinutes)"

    companion object {
        fun of(intervalMinutes: Int, jitterMinutes: Int): Schedule {
            require(intervalMinutes >= 1) { "intervalMinutes must be >= 1: $intervalMinutes" }
            require(jitterMinutes in 0..intervalMinutes) { "jitterMinutes must be in 0..$intervalMinutes: $jitterMinutes" }
            return Schedule(intervalMinutes, jitterMinutes)
        }

        /** For decoding untrusted/raw values: interval floors at 1, jitter is clamped into `0..interval`. */
        fun coerce(rawIntervalMinutes: Int, rawJitterMinutes: Int): Schedule {
            val interval = maxOf(1, rawIntervalMinutes)
            return Schedule(interval, rawJitterMinutes.coerceIn(0, interval))
        }
    }
}

/** "Off" is `enabled = false`, not a policy value. */
enum class NetworkPolicy { Any, Unmetered }

data class TaskConstraints(
    val network: NetworkPolicy = NetworkPolicy.Any,
    val requiresCharging: Boolean = false,
    val batteryNotLow: Boolean = false,
)

/**
 * Periodic task configuration. [enabled] is periodic-only: it is not consulted for
 * [TaskShape.OneShot] tasks, where an explicit `runOnce` always runs.
 */
data class TaskConfig(val enabled: Boolean, val schedule: Schedule, val constraints: TaskConstraints)

/** Per-key overrides; [schedule] is null for one-shot kinds or when only constraints are overridden. */
data class TaskOverrides(val schedule: Schedule?, val constraints: TaskConstraints)

/** Per-kind defaults, stored once per kind (never a [TaskScope]); [schedule] is null for one-shot kinds. */
data class TaskDefaults(val schedule: Schedule?, val constraints: TaskConstraints)

/** Persisted per-key state; `overrides == null` means "Using default". */
data class TaskEntry(val enabled: Boolean, val overrides: TaskOverrides?)

/** Lives only in [TaskSpec]; `apply` takes a [TaskConfig], so [OneShot] cannot reach it. */
sealed interface TaskShape {
    data class Periodic(val defaultSchedule: Schedule) : TaskShape
    data object OneShot : TaskShape
}

/**
 * Registration metadata. Equality (used by [TaskRegistry] idempotency) covers exactly these fields;
 * the [BackgroundTask] instance is never compared. [TaskConfig.enabled] is periodic-only: for
 * [TaskShape.OneShot] a worker never consults it, an explicit `runOnce` always runs, and
 * [defaultConfig]'s schedule is a placeholder that only its constraints are read from.
 */
data class TaskSpec(
    val jobKind: JobKind?,
    val title: String,
    val shape: TaskShape,
    val defaultConfig: TaskConfig,
    val allowedInColdPath: Boolean,
    val oneShotRetryCap: Int,
)

/** Live constraint snapshot at run start; logged with every run. */
data class ConstraintState(val online: Boolean, val isMetered: Boolean?, val charging: Boolean?)

data class TaskRunContext(
    val scheduledAtMs: Long?,
    val startedAtMs: Long,
    val appOpen: Boolean,
    val constraintState: ConstraintState,
)

/** What an adapter honours; the UI disables unsupported options with a reason. */
data class SchedulerCapabilities(
    val minIntervalMinutes: Int,
    val minFlexMinutes: Int,
    val supportsUnmetered: Boolean,
    val supportsCharging: Boolean,
    val supportsJitter: Boolean,
    val runsWhenClosed: Boolean,
    /** False for the no-op adapter; the Background tasks UI section is hidden. */
    val schedulesWork: Boolean,
)

data class TaskScheduleInfo(
    val effectiveIntervalMinutes: Int,
    val clamped: Boolean,
    val nextRunAtMs: Long?,
    val minGapMinutes: Int,
    val maxGapMinutes: Int,
    val appliesAfterNextRun: Boolean,
    val downgradeReason: String? = null,
)

enum class OneShotConflict { Keep, Replace }

/** Which writer produced a run-log row; each source has exactly one writer. */
enum class RunSource { Background, Foreground }
