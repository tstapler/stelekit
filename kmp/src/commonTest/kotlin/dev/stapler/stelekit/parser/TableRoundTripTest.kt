package dev.stapler.stelekit.parser

import dev.stapler.stelekit.parsing.ParseMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** A table's stored text must be the user's text byte for byte, so saving never rewrites it. */
class TableRoundTripTest {

    private fun tableContent(markdown: String): String =
        MarkdownParser().parsePage(markdown, ParseMode.FULL).blocks.single().content

    @Test
    fun compactDelimiterRowIsPreserved() {
        val src = "| Load | Qty |\n|---|---:|\n| Pixel | 140 |"
        assertEquals(src, tableContent(src))
    }

    @Test
    fun cellPaddingAndColumnAlignmentOfSourceIsPreserved() {
        val src = "| Load       | Qty |\n| :--------- | --: |\n| Uplight    |   6 |"
        assertEquals(src, tableContent(src))
    }

    @Test
    fun centerMarkerAndEmptyCellsArePreserved() {
        val src = "| A | B | C |\n|:-:|:--|--:|\n| **x** |  | 3 |"
        assertEquals(src, tableContent(src))
    }

    @Test
    fun bulletedTableKeepsItsRowsVerbatim() {
        val src = "- | Load | Qty |\n|---|---|\n| a | 1 |"
        assertEquals("| Load | Qty |\n|---|---|\n| a | 1 |", tableContent(src))
    }
}
