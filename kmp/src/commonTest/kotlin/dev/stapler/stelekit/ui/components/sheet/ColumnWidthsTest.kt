// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.SortIndicator
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Story 5.6 and ADR-007: width presets, and how a structural edit moves widths and the sort indicator. */
class ColumnWidthsTest {
    private val view = TableViewState().setWidth(1, 100f).setWidth(2, 200f).setWidth(3, 300f).withSort(2, ascending = true)

    @Test
    fun autoFit_should_ClampTheCharacterEstimateBetweenNarrowAndWide() {
        assertEquals(ColumnWidths.AUTO_MIN_DP, ColumnWidths.autoFitDp(0))
        assertEquals(ColumnWidths.AUTO_MIN_DP, ColumnWidths.autoFitDp(3))
        assertEquals(8 * 12 + 24f, ColumnWidths.autoFitDp(12))
        assertEquals(ColumnWidths.AUTO_MAX_DP, ColumnWidths.autoFitDp(500))
    }

    @Test
    fun presets_should_BeTheNarrowMediumWideDpValues() {
        assertEquals(listOf(96f, 160f, 240f, null), WidthPreset.entries.map { it.dp })
        assertEquals(listOf("Narrow", "Medium", "Wide", "Auto-fit"), WidthPreset.entries.map { it.label })
    }

    @Test
    fun insertColumn_should_ShiftWidthsAndTheIndicatorFromTheInsertPoint() {
        val next = ColumnWidths.afterEdit(view, TableEdit.InsertCols(before = 2, count = 1), columnCount = 4)
        assertEquals(mapOf(1 to 100f, 3 to 200f, 4 to 300f), next.columnWidthsDp)
        assertEquals(SortIndicator(3, true), next.sort)
    }

    @Test
    fun deleteColumn_should_DropItsWidthAndClearTheIndicatorWhenTheSortColumnGoes() {
        val next = ColumnWidths.afterEdit(view, TableEdit.DeleteCols(at = 2, count = 1), columnCount = 2)
        assertEquals(mapOf(1 to 100f, 2 to 300f), next.columnWidthsDp)
        assertNull(next.sort)
    }

    @Test
    fun moveColumn_should_CarryWidthsWithTheirColumns() {
        // Column 1 moves to before 3: order becomes 2, 1, 3.
        val next = ColumnWidths.afterEdit(view, TableEdit.MoveCols(from = 1, count = 1, before = 3), columnCount = 3)
        assertEquals(mapOf(2 to 100f, 1 to 200f, 3 to 300f), next.columnWidthsDp)
        assertEquals(SortIndicator(1, true), next.sort)
    }

    @Test
    fun movedOrder_should_MatchTheEnginesMove() {
        assertEquals(listOf(2, 1, 3), ColumnWidths.movedOrder(3, from = 1, count = 1, before = 3))
        assertEquals(listOf(3, 1, 2), ColumnWidths.movedOrder(3, from = 3, count = 1, before = 1))
        assertEquals(listOf(1, 3, 4, 2), ColumnWidths.movedOrder(4, from = 2, count = 1, before = 5))
    }

    @Test
    fun rowOrderEdits_should_ClearTheIndicatorButKeepWidths() {
        listOf(
            TableEdit.InsertRows(2),
            TableEdit.DeleteRows(2),
            TableEdit.MoveRows(2, 1, 4),
            TableEdit.PasteRange(dev.stapler.stelekit.table.CellAddress(2, 1), listOf(listOf("x"))),
        ).forEach { edit ->
            val next = ColumnWidths.afterEdit(view, edit, columnCount = 3)
            assertNull(next.sort, "$edit")
            assertEquals(view.columnWidthsDp, next.columnWidthsDp, "$edit")
        }
    }

    @Test
    fun cellAndAlignmentEdits_should_LeaveTheViewAlone() {
        val next = ColumnWidths.afterEdit(view, TableEdit.SetAlignment(2, dev.stapler.stelekit.table.ColumnAlign.RIGHT), columnCount = 3)
        assertEquals(view, next)
    }
}
