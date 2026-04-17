package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ParseTableContentTest {

    @Test
    fun parsesHeadersAndRows() {
        val raw = """
            | Name  | Age |
            |-------|-----|
            | Alice | 30  |
            | Bob   | 25  |
        """.trimIndent()
        val result = parseTableContent(raw)
        assertEquals(listOf("Name", "Age"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30"), result.rows[0])
        assertEquals(listOf("Bob", "25"), result.rows[1])
    }

    @Test
    fun parsesColumnAlignments() {
        val raw = """
            | Left | Center | Right |
            |:-----|:------:|------:|
            | a    | b      | c     |
        """.trimIndent()
        val result = parseTableContent(raw)
        assertEquals(TableColumnAlignment.LEFT, result.alignments[0])
        assertEquals(TableColumnAlignment.CENTER, result.alignments[1])
        assertEquals(TableColumnAlignment.RIGHT, result.alignments[2])
    }

    @Test
    fun defaultAlignmentIsLeft() {
        val raw = """
            | Col |
            |-----|
            | val |
        """.trimIndent()
        val result = parseTableContent(raw)
        assertEquals(TableColumnAlignment.LEFT, result.alignments[0])
    }

    @Test
    fun returnsEmptyTableForTooFewLines() {
        val result = parseTableContent("| only one line |")
        assertEquals(emptyList(), result.headers)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun returnsEmptyTableForBlankInput() {
        val result = parseTableContent("")
        assertEquals(emptyList(), result.headers)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parsesTableWithNoDataRows() {
        val raw = """
            | Header |
            |--------|
        """.trimIndent()
        val result = parseTableContent(raw)
        assertEquals(listOf("Header"), result.headers)
        assertEquals(emptyList(), result.rows)
    }
}
