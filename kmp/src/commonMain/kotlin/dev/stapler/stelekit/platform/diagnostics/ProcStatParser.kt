// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

/** CPU ticks (utime + stime, clock ticks) of one thread-name group; many pool threads share a group. */
data class ThreadCpuShare(val group: String, val ticks: Long)

/** One `/proc/<pid>/task/<tid>/stat` line reduced to what the sampler needs. */
data class ThreadStat(val name: String, val ticks: Long)

/**
 * Parser for `/proc/self/stat` and `/proc/self/task/<tid>/stat`. Pure so it is tested in commonTest;
 * the Android sampler only reads the files.
 */
object ProcStatParser {
    private const val UTIME_INDEX_AFTER_COMM = 11 // field 14, counting from the state field (3)
    private const val STIME_INDEX_AFTER_COMM = 12 // field 15

    /**
     * Parses one stat line. The comm field sits in parentheses and may itself contain spaces and
     * parentheses, so the split starts after the LAST ')'. Returns null for a malformed line.
     */
    fun parseLine(line: String): ThreadStat? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val fields = line.substring(close + 1).trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size <= STIME_INDEX_AFTER_COMM) return null
        val utime = fields[UTIME_INDEX_AFTER_COMM].toLongOrNull() ?: return null
        val stime = fields[STIME_INDEX_AFTER_COMM].toLongOrNull() ?: return null
        if (utime < 0 || stime < 0) return null
        return ThreadStat(name = line.substring(open + 1, close), ticks = utime + stime)
    }

    /** Parses many lines, silently dropping malformed ones. */
    fun parseAll(lines: List<String>): List<ThreadStat> = lines.mapNotNull(::parseLine)

    /**
     * Thread names such as `DefaultDispatcher-worker-3` and `pool-2-thread-1` differ only by index;
     * dropping trailing `-<n>` / digits groups them. Names are class-like, never user content.
     */
    fun groupName(threadName: String): String {
        var name = threadName
        while (true) {
            val trimmed = name.trimEnd { it.isDigit() }.trimEnd('-', '_', ' ', '#', ':')
            if (trimmed == name || trimmed.isEmpty()) break
            name = trimmed
        }
        return name.ifEmpty { threadName }
    }

    /** Sums ticks per [groupName] and returns the [limit] biggest groups, largest first. */
    fun topGroups(threads: List<ThreadStat>, limit: Int = 3): List<ThreadCpuShare> =
        threads.groupBy { groupName(it.name) }
            .map { (group, members) -> ThreadCpuShare(group, members.sumOf { it.ticks }) }
            .sortedWith(compareByDescending<ThreadCpuShare> { it.ticks }.thenBy { it.group })
            .take(limit)
}
