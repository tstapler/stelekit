package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GridNavigatorTest {
    private val bounds = GridBounds(rows = 4, cols = 3)

    private fun addr(r: Int, c: Int) = CellAddress(r, c)
    private fun sel(r: Int, c: Int) = GridSelection(addr(r, c))
    private fun GridNavigator.moved(s: GridSelection, cmd: GridCommand) =
        assertIs<NavResult.Moved>(handle(s, cmd)).selection

    @Test
    fun tab_should_WrapToNextRow_When_AtLastColumn() {
        val nav = GridNavigator(bounds)
        assertEquals(sel(3, 1), nav.moved(sel(2, 3), GridCommand.Tab()))
        assertEquals(sel(2, 3), nav.moved(sel(3, 1), GridCommand.Tab(back = true)))
        assertEquals(sel(4, 3), nav.moved(sel(4, 3), GridCommand.Tab()))
        assertEquals(sel(1, 1), nav.moved(sel(1, 1), GridCommand.Tab(back = true)))
    }

    @Test
    fun shiftEnter_should_StayInBounds_When_AtHeaderRow() {
        val nav = GridNavigator(bounds)
        val r = assertIs<NavResult.CommitStay>(nav.handle(sel(2, 1), GridCommand.Enter(back = true)))
        assertEquals(sel(2, 1), r.selection)
        assertIs<NavResult.CommitStay>(nav.handle(sel(1, 2), GridCommand.Enter(back = true)))
        assertEquals(sel(2, 2), nav.moved(sel(3, 2), GridCommand.Enter(back = true)))
    }

    @Test
    fun enter_should_CommitStayAndNotAppendRow_When_LastBodyRow() {
        val nav = GridNavigator(bounds)
        val r = assertIs<NavResult.CommitStay>(nav.handle(sel(4, 2), GridCommand.Enter()))
        assertEquals(sel(4, 2), r.selection)
        assertEquals(sel(4, 2), nav.moved(sel(3, 2), GridCommand.Enter()))
    }

    @Test
    fun shiftDownTwiceThenCtrlSpace_should_SelectRangeThenWholeColumn_When_FromB2() {
        val nav = GridNavigator(bounds)
        val down = GridCommand.Move(GridDirection.Down, extend = true)
        val range = nav.moved(nav.moved(sel(2, 2), down), down)
        assertEquals(CellRange(addr(2, 2), addr(4, 2)), range.range)
        val column = nav.moved(range, GridCommand.SelectColumn)
        assertEquals(CellRange(addr(1, 2), addr(4, 2)), column.range)
        assertEquals(GridSelection.Kind.Columns, column.kind)
    }

    @Test
    fun ctrlDown_should_StopAtLastFilledCell_When_BlankFollows() {
        val filled = (2..5).map { addr(it, 1) }.toSet()
        val nav = GridNavigator(GridBounds(8, 3), isBlank = { it !in filled })
        val jump = GridCommand.Move(GridDirection.Down, jump = true)
        assertEquals(sel(5, 1), nav.moved(sel(2, 1), jump))
        // From the last filled cell the next jump skips the blank gap to the grid edge.
        assertEquals(sel(8, 1), nav.moved(sel(5, 1), jump))
    }

    @Test
    fun selection_should_ClampToBounds_When_ExtendedPastEdge() {
        val nav = GridNavigator(bounds)
        val s = nav.moved(sel(4, 3), GridCommand.Move(GridDirection.Down, extend = true))
        assertEquals(addr(4, 3), s.focus)
        val s2 = nav.moved(sel(1, 1), GridCommand.Move(GridDirection.Left))
        assertEquals(sel(1, 1), s2)
    }

    @Test
    fun navigator_should_MirrorOnlyTheArrows_When_Rtl() {
        val nav = GridNavigator(bounds, rtl = true)
        assertEquals(sel(2, 1), nav.moved(sel(2, 2), GridCommand.Move(GridDirection.Right)))
        assertEquals(sel(2, 3), nav.moved(sel(2, 2), GridCommand.Move(GridDirection.Left)))
        // Column 1 is drawn rightmost, so Home is the rightmost cell and Tab walks right to left: both stay logical.
        assertEquals(sel(2, 1), nav.moved(sel(2, 2), GridCommand.LineEdge(end = false)))
        assertEquals(sel(2, 3), nav.moved(sel(2, 2), GridCommand.LineEdge(end = true)))
        assertEquals(sel(2, 3), nav.moved(sel(2, 2), GridCommand.Tab()))
        assertEquals(sel(2, 1), nav.moved(sel(2, 2), GridCommand.Tab(back = true)))
    }

    @Test
    fun rightThenLeft_should_ReturnToStart_When_NotClamped() {
        for (rtl in listOf(false, true)) {
            val nav = GridNavigator(bounds, rtl = rtl)
            val start = sel(3, 2)
            val there = nav.moved(start, GridCommand.Move(GridDirection.Right))
            assertEquals(start, nav.moved(there, GridCommand.Move(GridDirection.Left)), "rtl=$rtl")
        }
    }

    @Test
    fun navigator_should_ReportNotNavigation_When_CommandIsNotMovement() {
        assertEquals(NavResult.NotNavigation, GridNavigator(bounds).handle(sel(2, 2), GridCommand.Copy))
    }

    @Test
    fun home_end_pageKeys_should_MoveWithinBounds() {
        val nav = GridNavigator(GridBounds(30, 3), pageRows = 10)
        assertEquals(sel(2, 3), nav.moved(sel(2, 1), GridCommand.LineEdge(end = true)))
        assertEquals(sel(1, 1), nav.moved(sel(9, 3), GridCommand.TableEdge(end = false)))
        assertEquals(sel(30, 3), nav.moved(sel(9, 1), GridCommand.TableEdge(end = true)))
        assertEquals(sel(12, 2), nav.moved(sel(2, 2), GridCommand.Page(down = true)))
        assertEquals(sel(1, 2), nav.moved(sel(2, 2), GridCommand.Page(down = false)))
    }
}
