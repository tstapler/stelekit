package dev.stapler.stelekit.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParseTableContentTest {

    @Test
    fun `parses headers from first pipe-delimited row`() {
        val result = parseTableContent(GFM_TABLE)
        assertEquals(listOf("Scenario", "Prefer", "Reason"), result.headers)
    }

    @Test
    fun `parses left alignment as default when separator has no colons`() {
        val result = parseTableContent(GFM_TABLE)
        assertTrue(result.alignments.all { it == TableColumnAlignment.LEFT })
    }

    @Test
    fun `parses right alignment when separator ends with colon`() {
        val result = parseTableContent(
            """
            | A | B |
            | --- | ---: |
            | x | y |
            """.trimIndent()
        )
        assertEquals(TableColumnAlignment.LEFT, result.alignments[0])
        assertEquals(TableColumnAlignment.RIGHT, result.alignments[1])
    }

    @Test
    fun `parses center alignment when separator starts and ends with colon`() {
        val result = parseTableContent(
            """
            | A |
            | :---: |
            | x |
            """.trimIndent()
        )
        assertEquals(TableColumnAlignment.CENTER, result.alignments[0])
    }

    @Test
    fun `parses body rows`() {
        val result = parseTableContent(GFM_TABLE)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Small collection", "Collection", "Inline functions"), result.rows[0])
        assertEquals(listOf("Large collection", "Sequence", "Avoids allocations"), result.rows[1])
    }

    @Test
    fun `returns empty table when input has fewer than two lines`() {
        val result = parseTableContent("| just a header |")
        assertTrue(result.headers.isEmpty())
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `returns empty table for blank input`() {
        val result = parseTableContent("")
        assertTrue(result.headers.isEmpty())
    }

    @Test
    fun `trims cell whitespace`() {
        val result = parseTableContent(
            """
            |  spaced  |  cells  |
            |---|---|
            |  a  |  b  |
            """.trimIndent()
        )
        assertEquals(listOf("spaced", "cells"), result.headers)
        assertEquals(listOf("a", "b"), result.rows[0])
    }

    @Test
    fun `handles table without leading and trailing pipes`() {
        val result = parseTableContent(
            """
            H1 | H2
            ---|---
            v1 | v2
            """.trimIndent()
        )
        assertEquals(listOf("H1", "H2"), result.headers)
        assertEquals(listOf("v1", "v2"), result.rows[0])
    }

    companion object {
        private val GFM_TABLE = """
            | Scenario | Prefer | Reason |
            |---|---|---|
            | Small collection | Collection | Inline functions |
            | Large collection | Sequence | Avoids allocations |
        """.trimIndent()
    }
}
