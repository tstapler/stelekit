// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.TabularParse
import dev.stapler.stelekit.table.TabularText
import dev.stapler.stelekit.ui.components.TableMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.4b and 5.4c strings and helpers: the slot, the HTML flavour, the paste size estimate and the messages. */
class SheetClipboardPureTest {
    @Test
    fun slot_should_ReturnRawCellsOnlyWhenThePastedTextIsExactlyItsTsv() {
        val slot = TableClipboardSlot()
        val raw = listOf(listOf("Apple", "3", "=B2*2"))
        slot.remember("Apple\t3\t6", raw)
        assertEquals(raw, slot.rawFor("Apple\t3\t6"))
        assertEquals(raw, slot.rawFor("Apple\t3\t6".replace("\t", "\t")), "same text")
        assertNull(slot.rawFor("Apple\t3\t7"))
        assertNull(TableClipboardSlot().rawFor(""), "an empty slot matches nothing")
    }

    @Test
    fun slot_should_IgnoreLineEndingStyle() {
        val slot = TableClipboardSlot()
        slot.remember("a\r\nb", listOf(listOf("=1"), listOf("=2")))
        assertEquals(listOf(listOf("=1"), listOf("=2")), slot.rawFor("a\nb"))
    }

    @Test
    fun html_should_EscapeCellsAndTurnLineBreaksIntoBr() {
        assertEquals("<table><tr><td>a&lt;b&gt;</td><td>x&amp;y<br>z</td></tr></table>", tableHtml(listOf(listOf("a<b>", "x&y\nz"))))
    }

    @Test
    fun pasteEstimate_should_UpperBoundTheParsedCells() {
        listOf("a", "a\tb", "a\tb\nc\td", "1,2,3\n4,5,6\n").forEach { text ->
            val parsed = TabularText.parse(text) as TabularParse.Grid
            assertTrue(estimatePasteCells(text) >= parsed.rows.sumOf { it.size }, text)
        }
    }

    @Test
    fun pastedMessage_should_NameTheGrowthOnlyWhenThereIsAny() {
        assertEquals("Pasted 8 x 5, added 7 rows, 3 columns", TableMessages.pasted(8, 5, 7, 3))
        assertEquals("Pasted 4 x 3 cells", TableMessages.pasted(4, 3, 0, 0))
        assertEquals("Pasted 2 x 2, added 1 row", TableMessages.pasted(2, 2, 1, 0))
        assertEquals("Pasted 2 x 2, added 1 column", TableMessages.pasted(2, 2, 0, 1))
    }

    @Test
    fun transformedLine_should_SayWhatChanged() {
        assertEquals("2 cells changed: \"|\" escaped. Line break stored as <br>", TableMessages.pasteTransformed(2, pipe = true, lineBreak = true))
        assertEquals("1 cell changed: Line break stored as <br>", TableMessages.pasteTransformed(1, pipe = false, lineBreak = true))
    }

    @Test
    fun stats_should_FormatNumbersLikeASpreadsheet() {
        assertEquals("Count 2  Sum 8  Average 4", TableMessages.selectionStats(2, 8.0, 4.0, 0))
        assertEquals("Count 3  Sum 7  Average 2.333333  2 errors", TableMessages.selectionStats(3, 7.0, 7.0 / 3, 2))
        assertEquals("Count 1  Sum 0.5  Average 0.5  1 error", TableMessages.selectionStats(1, 0.5, 0.5, 1))
        assertEquals("1 error", TableMessages.errorsOnly(1))
    }

    @Test
    fun sortMessage_should_ListSuffixesOnlyAboveZero() {
        assertEquals("Sorted by Name", TableMessages.sorted("Name", 0, 0))
        assertEquals("Sorted by Qty. 2 formulas reference other rows. 3 blank cells sorted last", TableMessages.sorted("Qty", 2, 3))
        assertEquals("Sorted by Qty. 1 formula references other rows", TableMessages.sorted("Qty", 1, 0))
        assertEquals("Sorted by Qty. 1 blank cell sorted last", TableMessages.sorted("Qty", 0, 1))
    }

    @Test
    fun refusalTexts_should_MatchTheWording() {
        assertEquals("Sort would change formula results: 4 formulas reference other rows", TableMessages.sortWouldChangeValues(4))
        assertEquals("4 formulas will be rewritten", TableMessages.formulasWillBeRewritten(4))
        assertEquals("A table needs at least one column", TableMessages.LAST_COLUMN)
    }
}
