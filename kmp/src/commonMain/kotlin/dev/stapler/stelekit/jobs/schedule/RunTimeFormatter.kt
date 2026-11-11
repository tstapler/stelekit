// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Locale-specific pieces of a run-log time; the relative/today/earlier choice is [formatRunTime]. */
interface RunTimeFormatter {
    /** Clock time only, e.g. "21:15" or "9:15 PM". */
    fun timeOfDay(epochMs: Long, zone: TimeZone): String

    /** Short date plus clock time, e.g. "Nov 9, 21:15". */
    fun dateAndTime(epochMs: Long, zone: TimeZone): String
}

/** Fixed English month names and 24-hour clock, for platforms without a locale formatter. */
object EnglishRunTimeFormatter : RunTimeFormatter {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    override fun timeOfDay(epochMs: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return "${two(t.hour)}:${two(t.minute)}"
    }

    override fun dateAndTime(epochMs: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return "${months[t.monthNumber - 1]} ${t.dayOfMonth}, ${timeOfDay(epochMs, zone)}"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
}

/** The device-locale formatter on JVM and Android; English fixed format elsewhere. */
expect fun platformRunTimeFormatter(): RunTimeFormatter

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

/**
 * Within the last hour "N min ago" ("just now" under a minute, also for a clock-skewed future time);
 * the same local day shows the time only; an earlier day shows date plus time. The one string is both
 * the visible text and the screen-reader text.
 */
fun formatRunTime(startedAtMs: Long, nowMs: Long, zone: TimeZone, formatter: RunTimeFormatter): String {
    val ageMinutes = (nowMs - startedAtMs) / MS_PER_MINUTE
    return when {
        ageMinutes < 1 -> "just now"
        ageMinutes < MINUTES_PER_HOUR -> "$ageMinutes min ago"
        sameLocalDay(startedAtMs, nowMs, zone) -> formatter.timeOfDay(startedAtMs, zone)
        else -> formatter.dateAndTime(startedAtMs, zone)
    }
}

private fun sameLocalDay(aMs: Long, bMs: Long, zone: TimeZone): Boolean =
    Instant.fromEpochMilliseconds(aMs).toLocalDateTime(zone).date == Instant.fromEpochMilliseconds(bMs).toLocalDateTime(zone).date
