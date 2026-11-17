// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.telemetry

import dev.stapler.stelekit.platform.Settings

/** How a v2 sheet came to exist. Only an [Authored] sheet can be the numerator of metric 8 (D-48). */
enum class SheetOrigin { Authored, Converted }

/** The five counters of the `sketch.quality` line (requirements.md "Flag and legacy-editor exit criteria"). */
data class QualityCounts(
    val v2Sessions: Long = 0,
    val crashes: Long = 0,
    val solves: Long = 0,
    val noConvergence: Long = 0,
    val conflicts: Long = 0,
)

/** The counters of the `sketch.adoption` line. Imported files and conversions are never added to [authored]. */
data class AdoptionCounts(val authored: Long = 0, val imported: Long = 0, val converted: Long = 0)

/**
 * Local, never-transmitted counters for metric 8 and the flag-exit thresholds (Task 6.2.3b, D-26, D-48), over `Settings`.
 * Nothing here references a network seam, and no value is element text: a sheet id is kept only while the sheet is still
 * short of [AUTHORED_MIN_DRAWN] drawn elements.
 *
 * - **Authored** (metric 8's numerator): a sheet created in-app ([SheetOrigin.Authored], not converted, not imported) in which
 *   [AUTHORED_MIN_DRAWN] elements were drawn with the tools, counted once. A conversion and a `.excalidraw` import are reported
 *   on their own and never added to it.
 * - **Solves** count the solver runs that ended solved or unsatisfiable (`NoConvergence` among them, so `noConvergence <=
 *   solves`); a drag's intermediate poses are runs too.
 * - **Sessions**: [beginSession] counts a v2 editor session; a crash record found at the next launch marks the previous one
 *   through [ingestCrashRecord], once per record id.
 *
 * Also keeps the last [RECENT_SESSIONS] sessions and last [RECENT_SOLVES] solves, which the stop-ship rows of the exit table read
 * (Task 6.2.5d). Single writer: call from the editor's own thread.
 */
class QualitySignals(private val settings: Settings) {
    fun counts(): QualityCounts = QualityCounts(
        v2Sessions = long(SESSIONS_KEY),
        crashes = long(CRASHES_KEY),
        solves = long(SOLVES_KEY),
        noConvergence = long(NO_CONVERGENCE_KEY),
        conflicts = long(CONFLICTS_KEY),
    )

    fun adoption(): AdoptionCounts = AdoptionCounts(long(AUTHORED_KEY), long(IMPORTED_KEY), long(CONVERTED_KEY))

    /** One flag per recent session, oldest first; true = that session ended in a crash. */
    fun recentSessions(): List<Boolean> = settings.getString(RECENT_SESSIONS_KEY, "").map { it == FLAG_SET }

    /** One flag per recent solve, oldest first; true = it was `NoConvergence`. */
    fun recentSolves(): List<Boolean> = settings.getString(RECENT_SOLVES_KEY, "").map { it == FLAG_SET }

    fun beginSession() {
        bump(SESSIONS_KEY)
        append(RECENT_SESSIONS_KEY, FLAG_CLEAR, RECENT_SESSIONS)
    }

    /**
     * Counts the crash behind [recordId] (the id of `last-crash.txt` or of the platform's exit record) once, and marks the most
     * recent session as crashed. Call before [beginSession] at launch; a null or already-seen id changes nothing.
     */
    fun ingestCrashRecord(recordId: String?) {
        if (recordId.isNullOrBlank() || recordId == settings.getString(LAST_CRASH_KEY, "")) return
        settings.putString(LAST_CRASH_KEY, recordId)
        bump(CRASHES_KEY)
        val recent = settings.getString(RECENT_SESSIONS_KEY, "")
        if (recent.isNotEmpty()) settings.putString(RECENT_SESSIONS_KEY, recent.dropLast(1) + FLAG_SET)
    }

    /** A solve that ended committed (or unsatisfiable in a way other than `NoConvergence`). */
    fun recordSolve() {
        bump(SOLVES_KEY)
        append(RECENT_SOLVES_KEY, FLAG_CLEAR, RECENT_SOLVES)
    }

    /** A solve that stopped above the residual bar and was never committed; it is also a solve. */
    fun recordNoConvergence() {
        bump(SOLVES_KEY)
        bump(NO_CONVERGENCE_KEY)
        append(RECENT_SOLVES_KEY, FLAG_SET, RECENT_SOLVES)
    }

    fun recordConflict() = bump(CONFLICTS_KEY)

    /** A v2 sheet now exists: an [SheetOrigin.Authored] one starts counting drawn elements, a converted one is only tallied. */
    fun sheetCreated(sheetId: String, origin: SheetOrigin) {
        when (origin) {
            SheetOrigin.Converted -> bump(CONVERTED_KEY)
            SheetOrigin.Authored -> setPending(pending() + (sheetId to 0))
        }
    }

    /** A tool committed one new element on [sheetId]; the third on an authored sheet makes it count, once. */
    fun elementDrawn(sheetId: String) {
        val drawn = (pending()[sheetId] ?: return) + 1
        if (drawn >= AUTHORED_MIN_DRAWN) {
            setPending(pending() - sheetId)
            bump(AUTHORED_KEY)
        } else {
            setPending(pending() + (sheetId to drawn))
        }
    }

    fun excalidrawImported() = bump(IMPORTED_KEY)

    /** Emits the two counter lines, once per session end, counts only. */
    fun endSession(log: SketchLog) {
        val adoption = adoption()
        val quality = counts()
        log.adoption(adoption.authored, adoption.imported)
        log.quality(quality.v2Sessions, quality.crashes, quality.solves, quality.noConvergence, quality.conflicts)
    }

    private fun pending(): Map<String, Int> = settings.getString(PENDING_KEY, "").split(ENTRY_SEPARATOR)
        .mapNotNull { entry -> entry.split(PAIR_SEPARATOR).takeIf { it.size == PAIR_PARTS }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }
        .toMap()

    private fun setPending(value: Map<String, Int>) {
        val kept = value.entries.toList().takeLast(PENDING_SHEETS)
        settings.putString(PENDING_KEY, kept.joinToString(ENTRY_SEPARATOR) { "${it.key}$PAIR_SEPARATOR${it.value}" })
    }

    private fun long(key: String): Long = settings.getString(key, "").toLongOrNull() ?: 0L

    private fun bump(key: String) = settings.putString(key, (long(key) + 1).toString())

    private fun append(key: String, flag: Char, keep: Int) {
        settings.putString(key, (settings.getString(key, "") + flag).takeLast(keep))
    }

    companion object {
        /** A sheet counts as authored once this many elements were drawn on it with the tools (metric 8). */
        const val AUTHORED_MIN_DRAWN = 3

        /** The flag-exit stop-ship windows: the last 20 sessions and the last 100 solves. */
        const val RECENT_SESSIONS = 20
        const val RECENT_SOLVES = 100

        /** Authored sheets still short of [AUTHORED_MIN_DRAWN] that are remembered; the oldest is forgotten first. */
        private const val PENDING_SHEETS = 64

        private const val PREFIX = "sketch.v2.signals."
        private const val SESSIONS_KEY = PREFIX + "v2Sessions"
        private const val CRASHES_KEY = PREFIX + "crashes"
        private const val SOLVES_KEY = PREFIX + "solves"
        private const val NO_CONVERGENCE_KEY = PREFIX + "noConvergence"
        private const val CONFLICTS_KEY = PREFIX + "conflicts"
        private const val AUTHORED_KEY = PREFIX + "authored"
        private const val IMPORTED_KEY = PREFIX + "imported"
        private const val CONVERTED_KEY = PREFIX + "converted"
        private const val RECENT_SESSIONS_KEY = PREFIX + "recentSessions"
        private const val RECENT_SOLVES_KEY = PREFIX + "recentSolves"
        private const val LAST_CRASH_KEY = PREFIX + "lastCrashRecord"
        private const val PENDING_KEY = PREFIX + "pendingAuthored"

        private const val FLAG_SET = '1'
        private const val FLAG_CLEAR = '0'
        private const val ENTRY_SEPARATOR = ","
        private const val PAIR_SEPARATOR = "="
        private const val PAIR_PARTS = 2
    }
}
