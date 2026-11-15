// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** Reads the process start time out of a `/proc/<pid>/stat` line. */
object ProcStatParser {
    // Field 1 is the pid and field 2 the parenthesised comm; field 3 (state) is the first after the comm.
    private const val STARTTIME_FIELD = 22
    private const val FIRST_FIELD_AFTER_COMM = 3

    /** Field 22 (`starttime`, clock ticks since boot), or null on any malformed line. */
    fun startTicks(statLine: String): Long? {
        // comm is arbitrary text that may itself contain ") ", so only the LAST ')' ends it.
        val commEnd = statLine.lastIndexOf(')')
        if (commEnd < 0) return null
        val fields = statLine.substring(commEnd + 1).trim().split(' ').filter { it.isNotEmpty() }
        return fields.getOrNull(STARTTIME_FIELD - FIRST_FIELD_AFTER_COMM)?.toLongOrNull()
    }
}
