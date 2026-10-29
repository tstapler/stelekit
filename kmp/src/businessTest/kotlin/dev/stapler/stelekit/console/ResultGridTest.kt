// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.console.views.SqlLimits
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.2 / Task 3.2f: grid model, CSV export, row-cap marker and EXPLAIN chip logic. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultGridTest {
    private class TableCapture : ConsoleOutput {
        val tables = mutableListOf<ResultTable>()
        val lines = mutableListOf<String>()
        override fun info(text: String) { lines += text }
        override fun error(text: String) { lines += "ERR $text" }
        override fun table(table: ResultTable) { tables += table }
    }

    /** Minimal RFC 4180 reader used only to prove the encoder round-trips. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                c == '"' -> quoted = true
                c == ',' -> { row += cell.toString(); cell.clear() }
                c == '\r' && text.getOrNull(i + 1) == '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf(); i++ }
                else -> cell.append(c)
            }
            i++
        }
        return rows
    }

    @Test
    fun csv_should_RoundTrip_ForAnyCells_WithFormulaStartersNeutralized() = runTest {
        val cell = Arb.string(0..12)
        checkAll(Arb.list(Arb.list(cell, 3..3), 0..6)) { rows ->
            val table = ResultTable(listOf("a", "b", "c"), rows)
            val parsed = parseCsv(table.toCsv())
            val expected = listOf(listOf("a", "b", "c")) + rows.map { r -> r.map { CsvEscape.neutralize(it) } }
            assertEquals(expected, parsed)
        }
    }

    @Test
    fun csv_should_QuoteAndEscape_CommasQuotesNewlinesAndUnicode() {
        val table = ResultTable(listOf("name", "note"), listOf(listOf("a,b", "say \"hi\"\nthere"), listOf("caf\u00e9 \uD83D\uDE00", null)))
        assertEquals("name,note\r\n\"a,b\",\"say \"\"hi\"\"\nthere\"\r\ncaf\u00e9 \uD83D\uDE00,\r\n", table.toCsv())
    }

    @Test
    fun csv_should_PrefixFormulaStarters_ButKeepPlainNegativeNumbers() {
        listOf("=1+1", "+cmd", "-2+3", "@SUM(A1)", "\tx", "\rx").forEach {
            assertEquals("'$it", CsvEscape.neutralize(it), "formula starter must be neutralized: ${it.replace("\t", "\\t").replace("\r", "\\r")}")
        }
        listOf("-5", "-5.25", "-1e9", "abc", "5-3", "").forEach { assertEquals(it, CsvEscape.neutralize(it)) }
        assertEquals("", CsvEscape.field(null))
    }

    @Test
    fun cappedCell_should_CutAtTheCellCap_AndLeaveNullAndShortValuesAlone() {
        assertNull(ResultTable.cappedCell(null))
        assertEquals("abc", ResultTable.cappedCell("abc"))
        val cut = ResultTable.cappedCell("x".repeat(ResultTable.MAX_CELL_CHARS + 50))!!
        assertEquals(ResultTable.MAX_CELL_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("\u2026"))
    }

    @Test
    fun columnChars_should_StayWithinBounds_ForAnyInput() = runTest {
        checkAll(Arb.list(Arb.list(Arb.string(0..80), 2..2), 0..8), Arb.int(1..30)) { rows, headerLen ->
            val table = ResultTable(listOf("h".repeat(headerLen), "x"), rows)
            val widths = ResultGridLayout.columnChars(table)
            assertEquals(2, widths.size)
            widths.forEach { assertTrue(it in ResultGridLayout.MIN_COLUMN_CHARS..ResultGridLayout.MAX_COLUMN_CHARS) }
        }
    }

    @Test
    fun displayCell_should_FlattenNewlinesCutWithEllipsis_AndShowNull() {
        assertEquals("NULL", ResultGridLayout.displayCell(null, 10))
        assertEquals("a b", ResultGridLayout.displayCell("a\nb", 10))
        assertEquals("abcd\u2026", ResultGridLayout.displayCell("abcdefgh", 5))
    }

    @Test
    fun descriptions_should_AnnounceRowColumnAndPosition() {
        val table = ResultTable(listOf("name", "n"), listOf(listOf("Alpha", "1"), listOf(null, "2")), truncatedAtRows = 2)
        assertEquals("Row 2 of 2, name: empty, n: 2", ResultGridLayout.rowDescription(table, 1))
        assertEquals("Columns 2: name, n", ResultGridLayout.headerDescription(table))
        assertEquals("Result: 2 rows, 2 columns, truncated at 2 rows", ResultGridLayout.summary(table))
        val long = ResultTable(listOf("c"), listOf(listOf("y".repeat(500))))
        assertTrue(ResultGridLayout.rowDescription(long, 0).length < 120)
    }

    @Test
    fun explainTarget_should_ApplyOnlyToReadStatements_AndSurviveTokenizing() {
        assertEquals("select 1", ResultTable.explainTarget("  select 1;  "))
        assertNull(ResultTable.explainTarget("EXPLAIN QUERY PLAN select 1"))
        assertNull(ResultTable.explainTarget("pragma table_info(pages)"))
        val statement = "select \"a\\b\" from t where x = 'q'"
        val line = ResultTable.explainCommandLine(statement)
        val tokens = CommandLineTokenizer.tokenize(line).getOrNull()
        assertEquals(listOf("sql", "EXPLAIN QUERY PLAN $statement"), tokens)
    }

    @Test
    fun sqlCommand_should_HandTheOutputOneGrid_WithNoTruncationChip_When_QueryCompletes() = kotlinx.coroutines.runBlocking {
        SqlConsoleFixture(pages = 3).use { fixture ->
            val out = TableCapture()
            runSqlInto(fixture, "select name from pages order by name", out)
            val table = out.tables.single()
            assertEquals(listOf("name"), table.columns)
            assertEquals(3, table.rows.size)
            assertFalse(table.truncated)
            assertNull(table.explainSql)
            assertTrue(out.lines.single().startsWith("OK: 3 rows"), out.lines.toString())
        }
    }

    @Test
    fun sqlCommand_should_MarkTruncationAndOfferExplain_When_RowCapHit() = kotlinx.coroutines.runBlocking {
        SqlConsoleFixture(pages = 5).use { fixture ->
            val out = TableCapture()
            runSqlInto(fixture, "select name from pages", out, SqlLimits(maxRows = 2))
            val table = out.tables.single()
            assertEquals(2, table.rows.size)
            assertEquals(2, table.truncatedAtRows)
            assertEquals("select name from pages", table.explainSql)
            assertTrue(out.lines.single().startsWith("TRUNCATED at 2 rows"), out.lines.toString())
        }
    }

    @Test
    fun sqlCommand_should_CutOversizedCells_BeforeTheyReachTheGrid() = kotlinx.coroutines.runBlocking {
        SqlConsoleFixture(pages = 1).use { fixture ->
            val out = TableCapture()
            runSqlInto(fixture, "select printf('%.*c', 5000, 'z') as big", out, SqlLimits(maxCellChars = 100))
            val cell = out.tables.single().rows.single().single()!!
            assertTrue(cell.length <= 101, "cell was ${cell.length} chars")
        }
    }

    private suspend fun runSqlInto(fixture: SqlConsoleFixture, statement: String, out: ConsoleOutput, limits: SqlLimits = SqlLimits()) {
        val reader = fixture.reader()
        try {
            dev.stapler.stelekit.console.commands.SqlCommand(limits) { reader }
                .run(listOf(statement), ConsoleContext(ConsoleRegistry(emptyList())), out)
        } finally {
            reader.close()
        }
    }

    @Test
    fun scrollback_should_KeepOnlyTheNewestTables_AndNoteTheDroppedOnes() {
        val buffer = ScrollbackBuffer(maxTables = 2)
        fun table(n: Int) = ResultTable(listOf("c"), List(n) { listOf("v$it") })
        buffer.appendEntries(listOf(1, 2, 3).map { ScrollbackEntry(ScrollbackKind.OUTPUT, "", table(it)) })
        val lines = buffer.lines.value
        assertEquals(listOf(false, true, true), lines.map { it.table != null })
        assertEquals("(earlier result: 1 rows x 1 columns, not kept)", lines[0].text)
        assertEquals(listOf(2, 3), lines.drop(1).map { it.table!!.rows.size })
    }

    @Test
    fun session_should_RedactTableCells_KeepGridForUi_AndExportItAsText() = runTest {
        val settings = object : dev.stapler.stelekit.platform.Settings {
            override fun getBoolean(key: String, defaultValue: Boolean) = true
            override fun putBoolean(key: String, value: Boolean) = Unit
            override fun getString(key: String, defaultValue: String) = defaultValue
            override fun putString(key: String, value: String) = Unit
            override fun containsKey(key: String) = true
        }
        val probe = object : ConsoleCommand {
            override val name = "probe"
            override val summary = "probe"
            override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
                out.table(ResultTable(listOf("k"), listOf(listOf("topsecret-value")), explainSql = "select 'topsecret-value'"))
                return Unit.right()
            }
        }
        val session = ConsoleSession(
            DeveloperMode(settings), ConsoleRegistry(listOf(probe)), exactSecrets = { listOf("topsecret-value") },
            batchMillis = 5, dispatcher = StandardTestDispatcher(testScheduler),
        )
        session.submit("probe")
        advanceUntilIdle()
        val tableLine = session.scrollback.lines.value.single { it.table != null }
        val table = tableLine.table!!
        assertFalse(table.rows.single().single()!!.contains("topsecret"), table.rows.toString())
        assertFalse(table.explainSql!!.contains("topsecret"), table.explainSql)
        assertFalse(table.toCsv().contains("topsecret"))
        assertFalse(session.exportText().contains("topsecret"))
        assertTrue(session.exportText().contains("k"))
        assertTrue(session.exportFileBaseName(kotlin.time.Instant.fromEpochSeconds(0), kotlinx.datetime.TimeZone.UTC, "sql").startsWith("sql-19700101-"))
        session.close()
    }
}
