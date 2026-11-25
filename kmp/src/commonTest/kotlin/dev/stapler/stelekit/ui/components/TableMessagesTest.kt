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

    @Test
    fun sheetNotices_should_EachMapToNonBlankText() {
        val texts = listOf(
            TableMessages.TABLE_REMOVED, TableMessages.NOT_A_TABLE, TableMessages.EDITED_ELSEWHERE,
            TableMessages.RELOADED_TO_STAY_SAFE, TableMessages.EDITOR_OPENED, TableMessages.SAVE_FAILED_REMOVED,
            TableMessages.UPDATED_FROM_DISK, TableMessages.COMMIT_FAILED, TableMessages.RETRY, TableMessages.READ_ONLY,
            TableMessages.COLUMN_WIDTHS_RESET, TableMessages.COPY_AS_FORMULAS, TableMessages.SHORTCUTS,
            TableMessages.CONFLICT_TITLE, TableMessages.KEEP_MINE, TableMessages.TAKE_THEIRS, TableMessages.DISCARDED_EDIT,
            TableMessages.RECOVERED_DRAFT, TableMessages.APPLY, TableMessages.DISCARD, TableMessages.SHEET_UNDO_SCOPE_NOTE,
            TableMessages.moreNotices(1), TableMessages.recoveredDraft("B3"), TableMessages.applyTo("B3"),
            TableMessages.conflictBannerDraft("B3"),
        )
        texts.forEach { assertTrue(it.isNotBlank(), "blank sheet message") }
        assertEquals("Apply to B3", TableMessages.applyTo("B3"))
        assertEquals("1 more", TableMessages.moreNotices(1))
        assertEquals(texts.size, texts.toSet().size, "every sheet message is distinct")
    }
}
