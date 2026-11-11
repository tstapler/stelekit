package dev.stapler.stelekit.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryStatusLineTest {
    private fun line(n: Long?) = countLine(LibraryState(totalCount = n))

    @Test
    fun emptyUntilTheFirstCountLands() = assertEquals("", line(null))

    @Test
    fun singularAndPlural() {
        assertEquals("1 item", line(1))
        assertEquals("0 items", line(0))
        assertEquals("7 items", line(7))
    }

    @Test
    fun thousandsAreGrouped_andTheCapShowsAPlus() {
        assertEquals("1,234 items", line(1_234))
        assertEquals("10,000 items", line(10_000))
        assertEquals("10,000+ items", line(10_001))
    }
}
