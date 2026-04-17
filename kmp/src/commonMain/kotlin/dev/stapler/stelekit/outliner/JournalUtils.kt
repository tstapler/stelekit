package dev.stapler.stelekit.outliner

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.char

/**
 * Utility for handling Logseq journal pages.
 * Journal pages are named by date, e.g., "Jan 4th, 2026".
 * Internal format is usually "YYYY_MM_DD".
 */
object JournalUtils {
    private val JOURNAL_NAME_REGEX = Regex("^(\\d{4})[-_](\\d{2})[-_](\\d{2})$")

    fun isJournalName(name: String): Boolean {
        return JOURNAL_NAME_REGEX.matches(name)
    }

    fun parseJournalDate(name: String): LocalDate? {
        val match = JOURNAL_NAME_REGEX.matchEntire(name) ?: return null
        return try {
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            LocalDate(year, month, day)
        } catch (e: Exception) {
            null
        }
    }

    fun formatDateForJournal(date: LocalDate): String {
        return "${date.year}_${(date.month.ordinal + 1).toString().padStart(2, '0')}_${date.day.toString().padStart(2, '0')}"
    }
    
    /**
     * Display format: "Jan 4th, 2026"
     * For now, keep it simple: "2026-01-04" or similar if needed.
     */
    fun getDisplayName(date: LocalDate): String {
        return date.toString() 
    }
}
