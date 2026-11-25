package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TabularTextTest {
    private fun grid(text: String, delimiter: Char = TabularText.detectDelimiter(text)): List<List<String>> =
        assertIs<TabularParse.Grid>(TabularText.parse(text, delimiter)).rows

    @Test
    fun parse_should_YieldTwoRowsWithQuotedTabAndNewline_When_ExcelTsv() {
        val rows = grid("a\t\"b\tc\"\t\"line1\nline2\"\r\n1\t2\t3\r\n")
        assertEquals(listOf(listOf("a", "b\tc", "line1\nline2"), listOf("1", "2", "3")), rows)
        assertEquals(rows, grid(TabularText.format(rows)))

        assertEquals(listOf(listOf("x", "y,z", "")), grid("x,\"y,z\",\"\"", ','))
        assertEquals(listOf(listOf("x", "y,z", "")), grid("x,\"y,z\",\"\""))
    }

    @Test
    fun parse_should_NotThrow_When_UnterminatedQuoteOrOverSizeCap() {
        assertEquals(listOf(listOf("a", "b\tc\n1")), grid("a\t\"b\tc\n1"))

        val atCap = (1..PASTE_CELL_CAP).joinToString("\t") { "x" }
        assertEquals(PASTE_CELL_CAP, grid(atCap).single().size)
        assertEquals(TabularParse.TooLarge, TabularText.parse(atCap + "\tx"))
        assertEquals(TabularParse.TooLarge, TabularText.parse("x\n".repeat(PASTE_CELL_CAP + 1)))
    }

    @Test
    fun parse_should_BeBoundedAndIterative_When_PathologicalNesting() {
        val parens = "=" + "(".repeat(10_000) + "1" + ")".repeat(10_000)
        assertEquals(listOf(listOf(parens)), grid(parens))
        assertEquals(listOf(listOf("\"".repeat(5_000))), grid("\"" + "\"\"".repeat(5_000) + "\""))
        val thousand = "=" + "SUM(".repeat(250)
        assertEquals(1, grid(thousand).size)
    }

    @Test
    fun detectDelimiter_should_PreferTabThenComma_When_Mixed() {
        assertEquals('\t', TabularText.detectDelimiter("a,b\tc"))
        assertEquals(',', TabularText.detectDelimiter("a,b"))
        assertEquals('\t', TabularText.detectDelimiter("plain"))
    }

    @Test
    fun parse_should_HandleLineEndingsAndTrailingNewline_When_Variants() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), grid("a\tb\nc\td"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), grid("a\tb\rc\td\r"))
        assertEquals(listOf(listOf("a"), listOf("")), grid("a\n\n"))
        assertEquals(emptyList(), grid(""))
        assertEquals(listOf(listOf("say \"hi\"")), grid("\"say \"\"hi\"\"\""))
        assertEquals(listOf(listOf("a\"b")), grid("a\"b"))
    }

    @Test
    fun format_should_UseCrlfWithoutTrailingNewlineAndQuoteOnlyWhenNeeded_When_Writing() {
        assertEquals("Apple\t3\r\nPear\t5", TabularText.format(listOf(listOf("Apple", "3"), listOf("Pear", "5"))))
        assertEquals("\"a\tb\"\t\"x\"\"y\"\t\"l1\nl2\"", TabularText.format(listOf(listOf("a\tb", "x\"y", "l1\nl2"))))
        assertEquals("x,\"y,z\"", TabularText.format(listOf(listOf("x", "y,z")), ','))
        val blankLast = listOf(listOf("a"), listOf(""))
        assertEquals(blankLast, grid(TabularText.format(blankLast)))
        assertTrue(TabularText.format(listOf(listOf(""))) == "\"\"")
    }

    @Test
    fun parse_should_RefuseACellOverTheCharacterCap_WhateverTheCellCount() {
        assertIs<TabularParse.TooLarge>(TabularText.parse("a\t" + "x".repeat(PASTE_CELL_CHARS + 1)))
        assertIs<TabularParse.TooLarge>(TabularText.parse("\"" + "x".repeat(PASTE_CELL_CHARS + 1) + "\""))
        assertIs<TabularParse.Grid>(TabularText.parse("a\t" + "x".repeat(PASTE_CELL_CHARS)))
    }
}
