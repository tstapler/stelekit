package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Doc-untouched and serialized-output cases (REQ-9/12/26) need TableDoc and land with the doc worker's types.
class TableViewStateTest {
    @Test
    fun setWidth_should_StoreDpAndLeaveDocUntouched_When_ColumnResized() {
        val v = TableViewState().setWidth(2, 200f)
        assertEquals(200f, v.widthOf(2))
        assertEquals(TableViewState.DEFAULT_WIDTH_DP, v.widthOf(1))
    }

    @Test
    fun setWidth_should_ClampToMinAndMax_When_DragBeyondRange() {
        val v = TableViewState()
        assertEquals(TableViewState.MIN_WIDTH_DP, v.setWidth(1, -50f).widthOf(1))
        assertEquals(TableViewState.MAX_WIDTH_DP, v.setWidth(1, 9_999f).widthOf(1))
        assertEquals(v, v.setWidth(1, Float.NaN))
        assertEquals(v, v.setWidth(1, Float.POSITIVE_INFINITY))
        assertEquals(v, v.setWidth(0, 100f))
    }

    @Test
    fun freeze_should_DefaultHeaderRowOnInSheet_When_NewViewState() {
        val v = TableViewState()
        assertTrue(v.freezeHeaderRow)
        assertFalse(v.freezeFirstColumn)
        assertTrue(v.withFreezeFirstColumn(true).freezeFirstColumn)
        assertFalse(v.withFreezeHeaderRow(false).freezeHeaderRow)
    }

    @Test
    fun sort_should_ClearIndicator_When_RowOrderChanges() {
        val v = TableViewState().withSort(2, ascending = false)
        assertEquals(SortIndicator(2, false), v.sort)
        assertNull(v.onRowOrderChanged().sort)
        assertEquals(v.withSort(2, false), v)
    }
}
