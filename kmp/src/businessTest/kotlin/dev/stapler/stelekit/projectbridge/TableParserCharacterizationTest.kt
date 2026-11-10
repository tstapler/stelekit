package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.parsing.TableExtraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins what the block parser does to table cells so the extractor is built on observed behaviour (plan A-m8). */
class TableParserCharacterizationTest {
    private fun cells(md: String) = TableExtraction.parseTables(md).single().table

    @Test
    fun `top level table keeps bold markers and trims cells`() {
        val t = cells("| a | b |\n|---|---|\n| k | **3\"** |\n")
        assertEquals(listOf("a", "b"), t.headers)
        assertEquals(listOf(listOf("k", "**3\"**")), t.rows)
    }

    @Test
    fun `table under a bullet is found`() {
        val t = cells("- | a | b |\n  |---|---|\n  | k | 1\" |\n")
        assertEquals(listOf(listOf("k", "1\"")), t.rows)
    }

    @Test
    fun `table nested under a heading and bullets is found`() {
        val t = cells("## H\n\t- | a | b |\n\t  |---|---|\n\t  | k | 1\" |\n")
        assertEquals(listOf("a", "b"), t.headers)
    }

    @Test
    fun `wiki link text survives in the cell`() {
        val t = cells("| a | b |\n|---|---|\n| [[Some Page]] | 3\" |\n")
        assertEquals("[[Some Page]]", t.rows.single().first())
    }

    @Test
    fun `escaped pipe is not an escape and splits the cell`() {
        val t = cells("| a | b |\n|---|---|\n| x \\| y | 3\" |\n")
        assertEquals(listOf(listOf("x \\", "y", "3\"")), t.rows)
    }

    @Test
    fun `text without a separator row is not a table`() {
        assertTrue(TableExtraction.parseTables("| a | b |\nplain\n").isEmpty())
    }
}
