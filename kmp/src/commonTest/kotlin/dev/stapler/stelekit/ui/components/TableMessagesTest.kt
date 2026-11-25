package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.formula.CellError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TableMessagesTest {
    @Test
    fun cannotEditAsGrid_should_MapParserFailure_When_NoDelimiterRow() {
        val failure = assertIs<TableParseResult.Failure>(TableParser.parse("| a | b |\n| c | d |"))
        assertEquals(TableParseFailure.MissingDelimiterRow, TableParseFailure.fromReason(failure.reason))
        assertEquals(
            "This table can't be edited as a grid: missing delimiter row.",
            TableMessages.cannotEditAsGrid(TableParseFailure.fromReason(failure.reason)),
        )
    }

    @Test
    fun errorDetail_should_CoverEveryCellError() {
        assertEquals("Division by zero", TableMessages.errorDetail(CellError.DivZero))
        assertEquals("Not a valid formula", TableMessages.errorDetail(CellError.Parse))
        CellError.entries.forEach { assertTrue(TableMessages.errorDetail(it).isNotBlank()) }
    }

    @Test
    fun copied_should_Pluralize() {
        assertEquals("Copied 1 x 1 cell", TableMessages.copied(1, 1))
        assertEquals("Copied 2 x 3 cells", TableMessages.copied(2, 3))
        assertEquals("Showing the first 50 of 900 rows", TableMessages.showingFirstRows(50, 900))
    }

    @Test
    fun isGridTableContent_should_RequireHeaderAndDelimiter() {
        assertTrue(isGridTableContent("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertFalse(isGridTableContent("| a | b |\n| 1 | 2 |"))
        assertFalse(isGridTableContent("plain text"))
    }
}
