package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class GridSelectionPropertyTest {
    private val commandArb: Arb<GridCommand> = Arb.int(0..11).map { commandFor(it) }

    private val dirArb = Arb.enum<GridDirection>()

    private fun commandFor(i: Int): GridCommand = when (i) {
        0 -> GridCommand.Move(GridDirection.Up, extend = true)
        1 -> GridCommand.Move(GridDirection.Down, extend = true, jump = true)
        2 -> GridCommand.Move(GridDirection.Left)
        3 -> GridCommand.Move(GridDirection.Right, jump = true)
        4 -> GridCommand.Tab()
        5 -> GridCommand.Tab(back = true)
        6 -> GridCommand.Enter()
        7 -> GridCommand.Page(down = true, extend = true)
        8 -> GridCommand.SelectColumn
        9 -> GridCommand.SelectRow
        10 -> GridCommand.SelectAll
        else -> GridCommand.TableEdge(end = true, extend = true)
    }

    @Test
    fun selection_should_AlwaysStayInsideBounds_When_AnyCommandSequence() = runTest {
        checkAll(
            Arb.int(1..12), Arb.int(1..8), Arb.boolean(), Arb.int(1..40), Arb.int(1..40),
            Arb.list(commandArb, 1..25), Arb.list(dirArb, 0..8),
        ) { rows, cols, rtl, startRow, startCol, commands, extraDirs ->
            val bounds = GridBounds(rows, cols)
            val nav = GridNavigator(bounds, rtl = rtl, pageRows = 3, isBlank = { (it.row + it.col) % 3 == 0 })
            // The start may lie outside the grid: handle() must clamp it before moving.
            var sel = GridSelection(CellAddress(startRow, startCol))
            val all = commands + extraDirs.map { GridCommand.Move(it, extend = true) }
            for (cmd in all) {
                sel = when (val r = nav.handle(sel, cmd)) {
                    is NavResult.Moved -> r.selection
                    is NavResult.CommitStay -> r.selection
                    NavResult.NotNavigation -> sel
                }
                for (a in listOf(sel.anchor, sel.focus)) {
                    assertTrue(a.row in 1..rows && a.col in 1..cols, "$a outside $rows x $cols after $cmd")
                }
            }
        }
    }
}
