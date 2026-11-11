package dev.stapler.stelekit.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySearchTest {
    @Test
    fun oneCharacter_isNoSearch_andZeroIsUnfiltered() {
        assertTrue(LibrarySearch.tokens("a").isEmpty())
        assertTrue(LibrarySearch.tokens("   ").isEmpty())
        assertTrue(LibrarySearch.matches("a", listOf("zzz")))
    }

    @Test
    fun tokensSplitOnWhitespaceAndSeparators() {
        assertEquals(listOf("IMG", "0012"), LibrarySearch.tokens("IMG_0012"))
        assertEquals(listOf("site", "visit"), LibrarySearch.tokens("site-visit"))
        assertEquals(listOf("kit", "sink"), LibrarySearch.tokens("kit sink"))
    }

    @Test
    fun everyTokenMustPrefixAWord_possiblyInDifferentFields() {
        val fields = listOf("IMG_0012.jpg", "site visit", null)
        assertTrue(LibrarySearch.matches("img visit", fields))
        assertFalse(LibrarySearch.matches("img nothing", fields))
        assertFalse(LibrarySearch.matches("mg", fields), "prefix of a word, not a substring")
    }

    @Test
    fun asciiCaseFolded_butNonAsciiCaseIsExact() {
        assertTrue(LibrarySearch.matches("KIT", listOf("kitchen.png")))
        assertTrue(LibrarySearch.matches("ét", listOf("été.png")))
        assertFalse(LibrarySearch.matches("Ét", listOf("été.png")))
    }

    @Test
    fun likeWildcardsAreEscaped() {
        assertEquals(listOf("% 100\\%%", null, null, null, null), LibrarySearch.patterns("100%"))
        assertEquals("%\"a\\_b\"%", LibrarySearch.tagPatterns(listOf("a_b")).first())
        assertFalse(LibrarySearch.matches("100%", listOf("1000.png")))
    }

    @Test
    fun patternsAreCappedAtFiveTokens() {
        assertEquals(5, LibrarySearch.patterns("a1 b2 c3 d4 e5 f6 g7").filterNotNull().size)
    }
}
