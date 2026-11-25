package dev.stapler.stelekit.table

import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TableParserTest {
    private fun parsed(s: String): TableDoc = assertIs<TableParseResult.Parsed>(TableParser.parse(s)).doc

    @Test
    fun lineIndexOf_should_SkipDelimiter_When_MappingRowToSourceLine() {
        val doc = parsed("|  a  | b |\n|:-|-:|\n| 1 | 2 | 3 |\n")
        assertEquals(1, doc.lineIndexOf(1))
        assertEquals(3, doc.lineIndexOf(2))
        assertEquals(listOf(ColumnAlign.LEFT, ColumnAlign.RIGHT), doc.delimiter.aligns)
        assertEquals(3, doc.row(2).cells.size, "ragged extra cell is kept")
    }

    @Test
    fun lineIndexOf_should_CountPrefixLines_When_TextPrecedesTable() {
        val doc = parsed("intro\nid:: x\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(listOf("intro", "id:: x"), doc.prefix)
        assertEquals(3, doc.lineIndexOf(1))
        assertEquals(5, doc.lineIndexOf(2))
    }

    @Test
    fun parse_should_KeepTrailingTextAsSuffix_When_TextFollowsTable() {
        val doc = parsed("| a |\n|---|\n| 1 |\nafter the table\n")
        assertEquals(1, doc.body.size)
        assertEquals(listOf("after the table", ""), doc.suffix)
    }

    @Test
    fun parse_should_TakeLongestRun_When_TwoPipeRunsExist() {
        val doc = parsed("| x | y |\n|---|---|\n\n| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(listOf(CellText("a"), CellText("b")), doc.header.cells)
        assertEquals(2, doc.body.size)
        assertEquals(listOf("| x | y |", "|---|---|", ""), doc.prefix)
    }

    @Test
    fun parse_should_StripCarriageReturnPerLine_When_ContentIsCrlf() {
        val doc = parsed("| a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n")
        assertEquals(CellText("b"), doc.header.cells[1])
        assertTrue(doc.header.cr && doc.delimiter.cr && doc.body[0].cr)
    }

    @Test
    fun parse_should_UnescapePipeAndBr_When_CellHasEscapes() {
        val doc = parsed("| a \\| b | x<br/>y | p<BR>q |\n|---|---|---|")
        assertEquals(listOf(CellText("a | b"), CellText("x\ny"), CellText("p\nq")), doc.header.cells)
    }

    @Test
    fun parse_should_AcceptMissingOuterPipes_When_RowsHaveInnerPipes() {
        val doc = parsed("H1 | H2\n---|---\nv1 | v2")
        assertEquals(listOf(CellText("H1"), CellText("H2")), doc.header.cells)
        assertTrue(!doc.style.leadPipe && !doc.style.trailPipe)
    }

    @Test
    fun parse_should_ReturnFailureWithoutThrowing_When_InputIsOddOrEmpty() {
        for (s in listOf("", "\n", "|", "| a |", "plain text", "| a |\n| b |", "---|---\n---|---", "|\n|-|")) {
            val r = TableParser.parse(s)
            if (r is TableParseResult.Parsed) assertEquals(s, TableSerializer.serialize(r.doc), "lossless: '$s'")
        }
        assertIs<TableParseResult.Failure>(TableParser.parse("plain text"))
        assertIs<TableParseResult.Failure>(TableParser.parse(""))
    }

    @Test
    fun parse_should_LogOncePerBlockAndReturnFailure_When_ContentIsNotATable() {
        val seen = ArrayList<LogEntry>()
        val handle = LogManager.addSink(object : LogSink {
            override fun write(entry: LogEntry, formatted: String) {
                if (entry.tag == "TableSheet") seen.add(entry)
            }
        })
        try {
            val log = TableParseFailureLog()
            val first = log.parse("block-1", "not a table")
            log.parse("block-1", "still not a table")
            log.parse("block-2", "nor this")
            assertIs<TableParseResult.Failure>(first)
            assertEquals(2, seen.size, "one entry per block, not per parse")
            assertTrue(seen[0].message.contains("block-1"))
        } finally {
            handle.close()
        }
    }
}
