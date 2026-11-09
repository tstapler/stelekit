// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

/**
 * One `ApplicationExitInfo` entry, flattened to plain ints so the formatter is commonMain and
 * testable without Android. Why Android ended a previous process is the evidence the "excessive CPU
 * usage" kill report needs (foreground vs cached importance decides which rule fired).
 */
data class ExitRecord(
    val reason: Int,
    val importance: Int,
    val status: Int,
    val pssKb: Long,
    val rssKb: Long,
    val timestampMs: Long,
    val description: String?,
) {
    /** Stable across relaunches, so the post-kill banner can remember it has been shown. */
    val id: String get() = "$timestampMs:$reason"
}

/** `ApplicationExitInfo.REASON_*` constants; unknown values keep their number. */
fun exitReasonName(reason: Int): String = when (reason) {
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY"
    4 -> "CRASH"
    5 -> "CRASH_NATIVE"
    6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "REASON_$reason"
}

/** `ActivityManager.RunningAppProcessInfo.IMPORTANCE_*` constants; unknown values keep their number. */
fun processImportanceName(importance: Int): String = when (importance) {
    100 -> "FOREGROUND"
    125 -> "FOREGROUND_SERVICE"
    130 -> "TOP_SLEEPING"
    200 -> "VISIBLE"
    230 -> "PERCEPTIBLE"
    300 -> "SERVICE"
    325 -> "CANT_SAVE_STATE"
    400 -> "CACHED"
    1000 -> "GONE"
    else -> "IMPORTANCE_$importance"
}

private fun kbToMb(kb: Long): String = "${kb / 1024}MB"

/** Prefix of every formatted exit line; the Logs screen and the post-kill banner key off it. */
const val EXIT_LINE_PREFIX = "Previous process exit: "

/** One line for the Logs screen. The OS description is appended verbatim: it carries the kill rule's numbers. */
fun formatExitRecord(record: ExitRecord): String = buildString {
    append(EXIT_LINE_PREFIX).append(exitReasonName(record.reason))
    append(" at=").append(record.timestampMs)
    append(" importance=").append(processImportanceName(record.importance))
    append(" status=").append(record.status)
    append(" pss=").append(kbToMb(record.pssKb))
    append(" rss=").append(kbToMb(record.rssKb))
    val description = record.description
    if (!description.isNullOrEmpty()) append(' ').append(description)
}

/** How many `getHistoricalProcessExitReasons` entries to request (was 3). */
const val EXIT_RECORD_REQUEST_COUNT = 10
