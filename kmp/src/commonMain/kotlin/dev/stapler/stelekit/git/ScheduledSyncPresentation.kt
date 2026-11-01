// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** The interval choices the Settings picker offers, in minutes. */
val SCHEDULED_SYNC_INTERVAL_OPTIONS: List<Int> = listOf(5, 15, 30, 60)

/** The interval a device actually runs: the stored value, raised to the platform floor. */
fun effectiveScheduledIntervalMinutes(configuredMinutes: Int, minimumMinutes: Int): Int =
    maxOf(configuredMinutes, minimumMinutes)

/** An option is selectable only when the platform can honour it (Android WorkManager's floor is 15). */
fun isScheduledIntervalSelectable(optionMinutes: Int, minimumMinutes: Int): Boolean = optionMinutes >= minimumMinutes

/** True when the stored interval is below the floor, so the UI must say what really runs. */
fun isScheduledIntervalClamped(configuredMinutes: Int, minimumMinutes: Int): Boolean = configuredMinutes < minimumMinutes

/** What the status line of the Scheduled sync section says. Copy says "check", never "sync", until the pull step ships. */
sealed interface ScheduledSyncLine {
    data object NotScheduled : ScheduledSyncLine
    data object Stopping : ScheduledSyncLine
    data object NeverChecked : ScheduledSyncLine
    data object CheckInProgress : ScheduledSyncLine
    data object Offline : ScheduledSyncLine
    data class Checked(val lastCheckedMinutesAgo: Long, val nextInMinutes: Long?) : ScheduledSyncLine
    data class Paused(val failures: Int, val lastFailure: String?) : ScheduledSyncLine
}

/**
 * @param stoppingVisible the switch went off while a tick was running and that has lasted over 2 s.
 */
fun scheduledSyncLine(
    enabled: Boolean,
    status: ScheduledSyncStatus,
    nowMs: Long,
    stoppingVisible: Boolean,
): ScheduledSyncLine = when {
    !enabled && stoppingVisible && status.tickInFlight -> ScheduledSyncLine.Stopping
    !enabled -> ScheduledSyncLine.NotScheduled
    status.paused -> ScheduledSyncLine.Paused(status.consecutiveFailures, status.lastFailure)
    status.tickInFlight -> ScheduledSyncLine.CheckInProgress
    status.lastSkip == SkipReason.Busy -> ScheduledSyncLine.CheckInProgress
    status.lastSkip == SkipReason.Offline -> ScheduledSyncLine.Offline
    status.lastSuccessAtMs == null -> ScheduledSyncLine.NeverChecked
    else -> ScheduledSyncLine.Checked(
        lastCheckedMinutesAgo = minutesBetween(status.lastSuccessAtMs, nowMs),
        nextInMinutes = status.nextAtMs?.let { minutesBetween(nowMs, it) },
    )
}

/** Whole minutes from [fromMs] to [toMs], never negative. */
private fun minutesBetween(fromMs: Long, toMs: Long): Long = ((toMs - fromMs).coerceAtLeast(0)) / MILLIS_PER_MINUTE

private const val MILLIS_PER_MINUTE = 60_000L
