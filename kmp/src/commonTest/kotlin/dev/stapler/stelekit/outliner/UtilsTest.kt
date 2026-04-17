package dev.stapler.stelekit.outliner

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtilsTest {
    @Test
    fun testJournalUtils() {
        val date = LocalDate(2026, 1, 4)
        val name = JournalUtils.formatDateForJournal(date)
        assertEquals("2026_01_04", name)
        assertTrue(JournalUtils.isJournalName(name))
        assertEquals(date, JournalUtils.parseJournalDate(name))
        assertFalse(JournalUtils.isJournalName("Not a journal"))
    }

    @Test
    fun testJournalDateFormatPadsMonthAndDay() {
        // Single-digit month and day must be zero-padded
        assertEquals("2026_01_04", JournalUtils.formatDateForJournal(LocalDate(2026, 1, 4)))
        assertEquals("2026_09_09", JournalUtils.formatDateForJournal(LocalDate(2026, 9, 9)))
        assertEquals("2026_10_31", JournalUtils.formatDateForJournal(LocalDate(2026, 10, 31)))
        assertEquals("2026_12_31", JournalUtils.formatDateForJournal(LocalDate(2026, 12, 31)))
        assertEquals("2026_04_11", JournalUtils.formatDateForJournal(LocalDate(2026, 4, 11)))
    }

    @Test
    fun testJournalDateRoundtrip() {
        // formatDateForJournal -> parseJournalDate must round-trip cleanly for all months
        for (month in 1..12) {
            val original = LocalDate(2026, month, 15)
            val formatted = JournalUtils.formatDateForJournal(original)
            assertEquals(original, JournalUtils.parseJournalDate(formatted),
                "Round-trip failed for month $month: formatted='$formatted'")
        }
    }

    @Test
    fun testNamespaceUtils() {
        val name = "Parent/Child/GrandChild"
        assertEquals("Parent/Child", NamespaceUtils.getNamespace(name))
        assertEquals("GrandChild", NamespaceUtils.getShortName(name))
        
        val parents = NamespaceUtils.getParentPages(name)
        assertEquals(2, parents.size)
        assertEquals("Parent", parents[0])
        assertEquals("Parent/Child", parents[1])
    }
}
