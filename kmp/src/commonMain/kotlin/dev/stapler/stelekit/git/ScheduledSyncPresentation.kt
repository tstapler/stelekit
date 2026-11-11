// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.JitterPreset
import dev.stapler.stelekit.jobs.schedule.LateCause
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import dev.stapler.stelekit.jobs.schedule.RestrictionReason
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScheduleInfo

/** The interval choices the Settings picker offers, in minutes. */
val SCHEDULED_SYNC_INTERVAL_OPTIONS: List<Int> = listOf(5, 15, 30, 60)

/** The interval a device actually runs: the stored value, raised to the platform floor. */
fun effectiveScheduledIntervalMinutes(configuredMinutes: Int, minimumMinutes: Int): Int =
    maxOf(configuredMinutes, minimumMinutes)

/** The interval the scheduler loop runs with; a non-positive value means "off" and must not be raised to the floor. */
fun scheduledLoopIntervalMinutes(configuredMinutes: Int, minimumMinutes: Int): Int =
    if (configuredMinutes <= 0) configuredMinutes else effectiveScheduledIntervalMinutes(configuredMinutes, minimumMinutes)

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
    /** Only fetches have run (SAF graph or merge withheld): never worded as "synced". */
    data class FetchedOnly(val lastFetchedMinutesAgo: Long, val nextInMinutes: Long?) : ScheduledSyncLine
    data class Paused(val failures: Int, val lastFailure: String?) : ScheduledSyncLine

    /** The schedule slipped: nothing succeeded for [sinceMs] (a closed-app notion, see [lateStatusAllowed]). */
    data class Late(val sinceMs: Long, val cause: LateCause) : ScheduledSyncLine

    /** The newest runs were skipped on purpose for [reason]; the OS is not blamed. */
    data class SkippedBecause(val reason: StatusSkip) : ScheduledSyncLine

    /** A background process could not read task settings; never worded as Late. */
    data object SettingsUnreadable : ScheduledSyncLine
}

/** The deliberate skip causes the status line names. */
enum class StatusSkip { Editing, AnotherSyncRunning, VaultLocked, ConflictPending }

/** The status-line skip for a run-log skip code (see `TaskOutcomeMapping`), or null when it has no dedicated copy. */
fun statusSkipFor(code: String): StatusSkip? = when (code) {
    "vault-locked" -> StatusSkip.VaultLocked
    "conflict-pending" -> StatusSkip.ConflictPending
    "editing-in-progress" -> StatusSkip.Editing
    "busy" -> StatusSkip.AnotherSyncRunning
    else -> null
}

private fun statusSkipFor(reason: ScheduledSkipReason): StatusSkip? = when (reason) {
    ScheduledSkipReason.EditingInProgress -> StatusSkip.Editing
    ScheduledSkipReason.VaultLocked -> StatusSkip.VaultLocked
    ScheduledSkipReason.ConflictPending -> StatusSkip.ConflictPending
    else -> null
}

const val SETTINGS_UNREADABLE_TEXT = "Can't read settings. Background sync may be paused."
const val OPEN_BATTERY_SETTINGS_LABEL = "Open battery settings"
const val BATTERY_SETTINGS_MANUAL_PATH = "Open Android Settings > Apps > SteleKit > Battery."

fun skipText(reason: StatusSkip): String = when (reason) {
    StatusSkip.Editing -> "Sync paused while you edit. It continues when you stop."
    StatusSkip.AnotherSyncRunning -> "Another sync is running."
    StatusSkip.VaultLocked -> "Locked. Sync resumes after you unlock."
    StatusSkip.ConflictPending -> "Resolve the conflict to resume."
}

private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_BEFORE_DAYS = 48L
private const val HOURS_PER_DAY = 24L

/** "Hasn't run in 45 min" under an hour, in hours up to 48 h, then whole days. Never says "behind". */
fun lateText(sinceMs: Long): String {
    val hours = sinceMs / MS_PER_HOUR
    val span = when {
        hours < 1 -> "${(sinceMs / MILLIS_PER_MINUTE).coerceAtLeast(1)} min"
        hours <= HOURS_BEFORE_DAYS -> "$hours h"
        else -> "${hours / HOURS_PER_DAY} days"
    }
    return "Hasn't run in $span"
}

/**
 * True when a background process could not read task settings more recently than the last success and
 * the newest run record is the matching skip (or there is none), so the UI says "Can't read settings"
 * instead of a false Late.
 */
fun isSettingsUnreadable(lastFailureMs: Long?, lastSuccessMs: Long?, lastRun: TaskRunResult?): Boolean {
    if (lastFailureMs == null) return false
    if (lastSuccessMs != null && lastFailureMs <= lastSuccessMs) return false
    return lastRun == null || lastRun == TaskRunResult.Skipped("settings-unreadable")
}

/** The one-line status text for every [ScheduledSyncLine]; the Paused line is also rendered with its own actions. */
fun statusLineText(line: ScheduledSyncLine): String = when (line) {
    ScheduledSyncLine.NotScheduled -> "Not scheduled"
    ScheduledSyncLine.Stopping -> "Stopping..."
    ScheduledSyncLine.NeverChecked -> "Not synced yet"
    ScheduledSyncLine.CheckInProgress -> "Sync in progress"
    ScheduledSyncLine.Offline -> "Offline - will sync when you are back online"
    is ScheduledSyncLine.Checked -> {
        val ago = if (line.lastCheckedMinutesAgo < 1) "just now" else "${line.lastCheckedMinutesAgo} min ago"
        val next = line.nextInMinutes?.let { " · next in $it min" }.orEmpty()
        "Last synced $ago$next"
    }
    is ScheduledSyncLine.FetchedOnly -> {
        val ago = if (line.lastFetchedMinutesAgo < 1) "just now" else "${line.lastFetchedMinutesAgo} min ago"
        val next = line.nextInMinutes?.let { " · next in $it min" }.orEmpty()
        "Checked $ago, updates not merged yet$next"
    }
    is ScheduledSyncLine.Paused -> "Paused after ${line.failures} failures - tap to fix"
    is ScheduledSyncLine.Late -> lateText(line.sinceMs)
    is ScheduledSyncLine.SkippedBecause -> skipText(line.reason)
    ScheduledSyncLine.SettingsUnreadable -> SETTINGS_UNREADABLE_TEXT
}

/** What the restricted-background hint row (S4) says and whether it carries the "Open battery settings" button. */
sealed interface RestrictedHint {
    val text: String
    val hasSettingsButton: Boolean

    data object BatterySaver : RestrictedHint {
        override val text = "Battery saver is on. Background sync resumes when you turn it off or charge."
        override val hasSettingsButton = false
    }

    data object Restricted : RestrictedHint {
        override val text = "Android is restricting this app."
        override val hasSettingsButton = true
    }

    /** Late with no probe signal: some vendors stop background apps without telling the app. */
    data object OemSuspected : RestrictedHint {
        override val text = "Some phones stop background apps. Check battery settings."
        override val hasSettingsButton = true
    }
}

/**
 * The hint row for the current [line], or null. Hidden on a platform that cannot run closed (Desktop),
 * when the task is off, and whenever the line already names a different cause (a deliberate skip,
 * unreadable settings), so Android is never blamed for a skip the app chose.
 */
fun restrictedHint(line: ScheduledSyncLine, restriction: RestrictionReason, enabled: Boolean, caps: SchedulerCapabilities?): RestrictedHint? {
    if (!enabled || caps == null || !lateStatusAllowed(caps)) return null
    val otherCause = when (line) {
        is ScheduledSyncLine.SkippedBecause, ScheduledSyncLine.SettingsUnreadable -> true
        is ScheduledSyncLine.Late -> line.cause is LateCause.DeliberateSkip
        else -> false
    }
    if (otherCause) return null
    return when (restriction) {
        RestrictionReason.BatterySaver -> RestrictedHint.BatterySaver
        RestrictionReason.AppRestricted, RestrictionReason.StandbyRestricted -> RestrictedHint.Restricted
        RestrictionReason.None -> if (line is ScheduledSyncLine.Late) RestrictedHint.OemSuspected else null
    }
}

/**
 * Precedence: Paused > Disabled (not scheduled) > Can't-read-settings > Late > check in progress > Checked.
 *
 * @param stoppingVisible the switch went off while a tick was running and that has lasted over 2 s.
 * @param lateness from `lateness(key, schedule, GitLastSuccessSource, now)`, the only source of "last success" for Late.
 * @param capabilities Late shows only where [lateStatusAllowed]; null (unknown) never shows Late.
 */
fun scheduledSyncLine(
    enabled: Boolean,
    status: ScheduledSyncStatus,
    nowMs: Long,
    stoppingVisible: Boolean,
    lateness: ScheduleLateness = ScheduleLateness.OnTime,
    capabilities: SchedulerCapabilities? = null,
    settingsUnreadable: Boolean = false,
): ScheduledSyncLine = when {
    !enabled && stoppingVisible && status.tickInFlight -> ScheduledSyncLine.Stopping
    !enabled -> ScheduledSyncLine.NotScheduled
    status.paused -> ScheduledSyncLine.Paused(status.consecutiveFailures, status.lastFailure)
    settingsUnreadable -> ScheduledSyncLine.SettingsUnreadable
    lateness is ScheduleLateness.Late && capabilities != null && lateStatusAllowed(capabilities) -> lateLine(lateness)
    status.tickInFlight -> ScheduledSyncLine.CheckInProgress
    status.lastSkip == SkipReason.Busy -> ScheduledSyncLine.CheckInProgress
    status.lastSkip is SkipReason.Policy && statusSkipFor(status.lastSkip.reason) != null ->
        ScheduledSyncLine.SkippedBecause(checkNotNull(statusSkipFor(status.lastSkip.reason)))
    status.lastSkip == SkipReason.Offline -> ScheduledSyncLine.Offline
    status.lastSuccessAtMs == null && status.lastFetchedAtMs != null -> ScheduledSyncLine.FetchedOnly(
        lastFetchedMinutesAgo = minutesBetween(status.lastFetchedAtMs, nowMs),
        nextInMinutes = status.nextAtMs?.let { minutesBetween(nowMs, it) },
    )
    status.lastSuccessAtMs == null -> ScheduledSyncLine.NeverChecked
    else -> ScheduledSyncLine.Checked(
        lastCheckedMinutesAgo = minutesBetween(status.lastSuccessAtMs, nowMs),
        nextInMinutes = status.nextAtMs?.let { minutesBetween(nowMs, it) },
    )
}

private fun lateLine(late: ScheduleLateness.Late): ScheduledSyncLine {
    val cause = late.cause
    val skip = if (cause is LateCause.DeliberateSkip) statusSkipFor(cause.reason) else null
    return if (skip != null) ScheduledSyncLine.SkippedBecause(skip) else ScheduledSyncLine.Late(late.sinceMs, cause)
}

/** Whole minutes from [fromMs] to [toMs], never negative. */
private fun minutesBetween(fromMs: Long, toMs: Long): Long = ((toMs - fromMs).coerceAtLeast(0)) / MILLIS_PER_MINUTE

private const val MILLIS_PER_MINUTE = 60_000L

// ---- Per-graph, per-kind task settings (Story 4.1.1): pure copy and presets ----

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1_440

/** Interval choices every platform can offer; 5 min is added only where the scheduler honours it. */
private val TASK_INTERVAL_PRESETS = listOf(15, 30, 60, 120, 360, MINUTES_PER_DAY)
private const val FIVE_MINUTES = 5

const val NETWORK_ANY_LABEL = "Any network"
const val NETWORK_UNMETERED_LABEL: String = NetworkPolicyCopy.UNMETERED_ONLY_LABEL
const val UNMETERED_DESKTOP_REASON = "Not available on desktop: SteleKit can't tell if a network is metered"
const val ANDROID_MIN_INTERVAL_HELPER = "Android runs at most every 15 minutes."
const val USING_DEFAULT_LABEL = "Using default"
const val LOADING_SETTINGS_REASON = "Loading settings..."
const val COULDNT_READ_SETTINGS = "Couldn't read settings. Background sync is paused until this loads."
const val COULDNT_SAVE = "Couldn't save. Your change was not applied."
const val BACK_TO_DEFAULT = "Back to the default schedule"

/** The interval presets a scheduler offers; no permanently disabled radio, so 5 min is absent when [SchedulerCapabilities.minIntervalMinutes] > 5. */
fun scheduledIntervalPresets(caps: SchedulerCapabilities): List<Int> =
    (if (caps.minIntervalMinutes <= FIVE_MINUTES) listOf(FIVE_MINUTES) else emptyList()) +
        TASK_INTERVAL_PRESETS.filter { it >= caps.minIntervalMinutes }

/** Whether the Android-floor helper line belongs under the interval group. */
fun showsMinIntervalHelper(caps: SchedulerCapabilities): Boolean = caps.minIntervalMinutes > FIVE_MINUTES

fun intervalLabel(minutes: Int): String = when {
    minutes == MINUTES_PER_DAY -> "Daily"
    minutes == MINUTES_PER_HOUR -> "1 hour"
    minutes % MINUTES_PER_HOUR == 0 && minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} hours"
    else -> "$minutes min"
}

/** "about every hour" / "about every 15 min": the effective interval, never a clock time. */
fun aboutEveryPhrase(minutes: Int): String = when {
    minutes == MINUTES_PER_DAY -> "about every day"
    minutes == MINUTES_PER_HOUR -> "about every hour"
    minutes % MINUTES_PER_HOUR == 0 && minutes < MINUTES_PER_DAY -> "about every ${minutes / MINUTES_PER_HOUR} hours"
    else -> "about every $minutes min"
}

/** The compact interval phrase used inside "Custom: ...": "every 6 h", "every 15 min", "daily". */
fun everyPhrase(minutes: Int): String = when {
    minutes == MINUTES_PER_DAY -> "daily"
    minutes % MINUTES_PER_HOUR == 0 && minutes < MINUTES_PER_DAY -> "every ${minutes / MINUTES_PER_HOUR} h"
    else -> "every $minutes min"
}

fun networkLabel(policy: NetworkPolicy): String = when (policy) {
    NetworkPolicy.Any -> NETWORK_ANY_LABEL
    NetworkPolicy.Unmetered -> NETWORK_UNMETERED_LABEL
}

fun spreadLabel(preset: JitterPreset): String = when (preset) {
    JitterPreset.Off -> "Off"
    JitterPreset.ALittle -> "A little"
    JitterPreset.ALot -> "A lot"
}

/** The schedule after the user picks [interval]: the spread preset is kept and its minutes recomputed. */
fun scheduleForInterval(current: Schedule, interval: Int): Schedule =
    Schedule.coerce(interval, JitterPreset.presetOf(current).minutesFor(interval))

/** The schedule after the user picks [preset] at the current interval. */
fun scheduleForSpread(current: Schedule, preset: JitterPreset): Schedule =
    Schedule.coerce(current.intervalMinutes, preset.minutesFor(current.intervalMinutes))

/** "Runs every 15 min on this device" when the platform raised the requested interval, else null. */
fun clampSummary(requestedMinutes: Int, caps: SchedulerCapabilities): String? =
    if (requestedMinutes < caps.minIntervalMinutes) "Runs every ${caps.minIntervalMinutes} min on this device" else null

/**
 * The effective spread window, from the adapter's own [TaskScheduleInfo] gap fields (never a global
 * `interval + offset` formula). A closed-app adapter (WorkManager) runs flex windows, so its wording
 * is the pending default until Spike 0.1.1 finalises it. Null when there is no spread to describe.
 */
fun spreadSummary(info: TaskScheduleInfo?, schedule: Schedule, caps: SchedulerCapabilities): String? {
    if (info == null || schedule.jitterMinutes == 0 || !caps.supportsJitter) return null
    val core = if (caps.runsWhenClosed) {
        "${aboutEveryPhrase(info.effectiveIntervalMinutes).replaceFirstChar { it.uppercase() }}; start times vary by up to ${schedule.jitterMinutes} min"
    } else {
        "Runs ${info.minGapMinutes}-${info.maxGapMinutes} min apart"
    }
    return if (info.appliesAfterNextRun) "$core. Takes effect after the next run" else core
}

/**
 * "Custom: <summary>" listing only the values that differ from [base] (the kind default), in the
 * order interval, network, spread. More than two differences collapse to "Custom: N changes".
 */
fun customSummary(overrides: TaskOverrides, base: TaskConfig): String {
    val phrases = buildList {
        val schedule = overrides.schedule
        if (schedule != null && schedule.intervalMinutes != base.schedule.intervalMinutes) add(everyPhrase(schedule.intervalMinutes))
        if (overrides.constraints.network != base.constraints.network) add(networkLabel(overrides.constraints.network).lowercase())
        if (schedule != null && JitterPreset.presetOf(schedule) != JitterPreset.presetOf(base.schedule)) {
            add(
                when (JitterPreset.presetOf(schedule)) {
                    JitterPreset.Off -> "no spread"
                    else -> "spread out ${spreadLabel(JitterPreset.presetOf(schedule)).lowercase()}"
                },
            )
        }
    }
    return when {
        phrases.isEmpty() -> "Custom"
        phrases.size > 2 -> "Custom: ${phrases.size} changes"
        else -> "Custom: ${phrases.joinToString(", ")}"
    }
}

/** The graph-row second line: "Using default" when nothing is overridden, else the custom summary. */
fun graphRowSummary(overrides: TaskOverrides?, base: TaskConfig): String =
    if (overrides == null) USING_DEFAULT_LABEL else customSummary(overrides, base)

/** "Git sync, Work notes, on, about every hour, unmetered networks only": the one merged accessibility node. */
fun graphRowDescription(kindTitle: String, graphName: String, config: TaskConfig, enabled: Boolean, summary: String): String {
    val head = "$kindTitle, $graphName, ${if (enabled) "on" else "off"}"
    if (!enabled) return "$head, $summary"
    return "$head, ${aboutEveryPhrase(config.schedule.intervalMinutes)}, ${networkLabel(config.constraints.network).lowercase()}, $summary"
}

/** "First check in about N min": N is the platform-clamped interval, never a clock time. */
fun firstCheckText(effectiveIntervalMinutes: Int): String = "First check in about $effectiveIntervalMinutes min"

/** One-line kind summary for the Background tasks list, e.g. "About every hour - Any network - on for 2 of 3 graphs". */
fun kindSummary(base: TaskConfig, onCount: Int, graphCount: Int): String {
    val every = aboutEveryPhrase(base.schedule.intervalMinutes).replaceFirstChar { it.uppercase() }
    return "$every - ${networkLabel(base.constraints.network)} - on for $onCount of $graphCount graphs"
}

/** The unmetered option is selectable only where the scheduler can tell a network is metered. */
fun isUnmeteredSelectable(caps: SchedulerCapabilities): Boolean = caps.supportsUnmetered

/**
 * "Late" (4.1.2) is a closed-app notion: Desktop's in-process loop only runs while the app is open,
 * so it never has a schedule to slip behind.
 */
fun lateStatusAllowed(caps: SchedulerCapabilities): Boolean = caps.runsWhenClosed

/** Graph search appears from this many graphs; below it the field is absent. */
const val GRAPH_SEARCH_THRESHOLD = 8
