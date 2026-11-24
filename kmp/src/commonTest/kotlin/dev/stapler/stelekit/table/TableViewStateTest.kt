package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val content = "|  a  | b |\n|:-|-:|\n| 1 | =A2*2 |\n"

    private fun parsed(): TableDoc = (TableParser.parse(content) as TableParseResult.Parsed).doc

    @Test
    fun freeze_should_NeverTouchDoc_When_Toggled() {
        val doc = parsed()
        val before = TableSerializer.serialize(doc)
        // Every TableViewState mutator returns a TableViewState, so no TableEdit can be produced; the doc stays identical.
        val v = TableViewState().withFreezeHeaderRow(false).withFreezeFirstColumn(true).withFreezeHeaderRow(true)
        assertTrue(v.freezeFirstColumn)
        assertEquals(before, TableSerializer.serialize(doc))
    }

    @Test
    fun viewState_should_NeverAppearInSerializedOutput_When_WidthFreezeSortSet() {
        val doc = parsed()
        val before = TableSerializer.serialize(doc)
        val v = TableViewState().setWidth(1, 300f).setWidth(2, 80f).withFreezeFirstColumn(true).withSort(2, true)
        assertEquals(300f, v.widthOf(1))
        assertEquals(before, TableSerializer.serialize(doc))
        assertEquals(content.trimEnd('\n'), before.trimEnd('\n'))
    }
}
