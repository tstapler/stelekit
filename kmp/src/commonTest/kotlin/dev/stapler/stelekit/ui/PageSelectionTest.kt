package dev.stapler.stelekit.ui

import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.screens.copy.CopyFilters
import dev.stapler.stelekit.ui.screens.copy.PickedPages
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageSelectionTest {
    private fun id(n: Int) = PageUuid("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

    @Test
    fun `toggle adds then removes`() {
        val one = PickedPages().toggle(id(1))
        assertTrue(id(1) in one)
        assertEquals(1, one.size)
        assertTrue(one.toggle(id(1)).isEmpty())
    }

    @Test
    fun `addAll is a union and idempotent`() {
        val s = PickedPages().toggle(id(1)).addAll(listOf(id(1), id(2), id(3)))
        assertEquals(setOf(id(1), id(2), id(3)), s.uuids)
        assertEquals(s, s.addAll(listOf(id(2))))
    }

    @Test
    fun `clear empties and leaves the original untouched`() {
        val s = PickedPages().addAll(listOf(id(1), id(2)))
        assertTrue(s.clear().isEmpty())
        assertEquals(2, s.size)
    }

    @Test
    fun `default filters map to the default selection filter`() {
        val f = CopyFilters().toSelectionFilter()
        assertTrue(f.journals)
        assertNull(f.dateFrom)
        assertNull(f.namespace)
        assertNull(f.tag)
    }

    @Test
    fun `journals only gets a floor date, pages only excludes journals`() {
        val journalsOnly = CopyFilters(showPages = false).toSelectionFilter()
        assertTrue(journalsOnly.journals)
        assertEquals(LocalDate(1, 1, 1), journalsOnly.dateFrom)
        val pagesOnly = CopyFilters(showJournals = false).toSelectionFilter()
        assertFalse(pagesOnly.journals)
    }

    @Test
    fun `explicit date range wins over the journals-only floor`() {
        val f = CopyFilters(showPages = false, dateFrom = LocalDate(2026, 10, 1), dateTo = LocalDate(2026, 10, 31)).toSelectionFilter()
        assertEquals(LocalDate(2026, 10, 1), f.dateFrom)
        assertEquals(LocalDate(2026, 10, 31), f.dateTo)
    }

    @Test
    fun `blank namespace and tag become null`() {
        val f = CopyFilters(namespace = "  ", tag = "").toSelectionFilter()
        assertNull(f.namespace)
        assertNull(f.tag)
    }
}
