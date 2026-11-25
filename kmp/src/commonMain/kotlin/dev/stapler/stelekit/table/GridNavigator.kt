package dev.stapler.stelekit.table

enum class GridMode { Navigate, Edit }

enum class GridDirection { Up, Down, Left, Right }

/** Everything the grid can be asked to do; keyboard chords, strip buttons and the edit bar all emit these. */
sealed interface GridCommand {
    data class Move(val dir: GridDirection, val extend: Boolean = false, val jump: Boolean = false) : GridCommand
    data class Tab(val back: Boolean = false) : GridCommand
    data class Enter(val back: Boolean = false) : GridCommand
    data class LineEdge(val end: Boolean, val extend: Boolean = false) : GridCommand
    data class TableEdge(val end: Boolean, val extend: Boolean = false) : GridCommand
    data class Page(val down: Boolean, val extend: Boolean = false) : GridCommand
    data object SelectColumn : GridCommand
    data object SelectRow : GridCommand
    data object SelectAll : GridCommand

    // Non-navigation commands: the navigator reports NotNavigation, the host acts on them.
    data object StartEdit : GridCommand
    data object Cancel : GridCommand
    data object ClearCells : GridCommand
    data object Copy : GridCommand
    data object Cut : GridCommand
    data object Paste : GridCommand
    data object Undo : GridCommand
    data object Redo : GridCommand
    data object InsertLineBreak : GridCommand
    data object InsertRowOrColumn : GridCommand
    data object DeleteRowOrColumn : GridCommand
    data object ToggleMaximize : GridCommand
    data object EditAsMarkdown : GridCommand
    data object OpenContextMenu : GridCommand

    /** Alt+Shift+Arrow: moves the selected rows or columns one step (the non-drag reorder, ADR-009). */
    data class MoveSelection(val dir: GridDirection) : GridCommand
}

sealed interface NavResult {
    data class Moved(val selection: GridSelection) : NavResult

    /** Enter on the last body row (Shift+Enter on the first): commit, append nothing, stay (UX-89). */
    data class CommitStay(val selection: GridSelection) : NavResult

    data object NotNavigation : NavResult
}

/**
 * Pure spreadsheet navigation. [rtl] mirrors Left/Right, Home/End and Tab only; addresses stay logical.
 * [isBlank] is consulted only by Ctrl+Arrow data-region jumps. Row 1 is the header, row 2 the first body row.
 */
class GridNavigator(
    private val bounds: GridBounds,
    private val rtl: Boolean = false,
    private val pageRows: Int = 10,
    private val isBlank: (CellAddress) -> Boolean = { false },
) {
    fun handle(sel: GridSelection, cmd: GridCommand): NavResult {
        val s = sel.clamp(bounds)
        return when (cmd) {
            is GridCommand.Move -> NavResult.Moved(move(s, cmd))
            is GridCommand.Tab -> NavResult.Moved(tab(s, cmd.back))
            is GridCommand.Enter -> enter(s, cmd.back)
            is GridCommand.LineEdge -> {
                val toEnd = if (rtl) !cmd.end else cmd.end
                val col = if (toEnd) bounds.cols else 1
                NavResult.Moved(go(s, CellAddress(s.focusRowFor(cmd.extend), col), cmd.extend))
            }
            is GridCommand.TableEdge -> {
                val target = if (cmd.end) CellAddress(bounds.rows, bounds.cols) else CellAddress(1, 1)
                NavResult.Moved(go(s, target, cmd.extend))
            }
            is GridCommand.Page -> {
                val step = if (cmd.down) pageRows else -pageRows
                val from = s.origin(cmd.extend)
                NavResult.Moved(go(s, bounds.clamp(CellAddress((from.row + step).coerceAtLeast(1), from.col)), cmd.extend))
            }
            GridCommand.SelectColumn -> NavResult.Moved(s.selectColumns(bounds))
            GridCommand.SelectRow -> NavResult.Moved(s.selectRows(bounds))
            GridCommand.SelectAll -> NavResult.Moved(GridSelection.table(bounds))
            else -> NavResult.NotNavigation
        }
    }

    private fun GridSelection.origin(extend: Boolean) = if (extend) focus else anchor
    private fun GridSelection.focusRowFor(extend: Boolean) = origin(extend).row

    private fun go(s: GridSelection, target: CellAddress, extend: Boolean): GridSelection =
        if (extend) s.extendTo(target, bounds) else GridSelection.single(target, bounds)

    private fun move(s: GridSelection, m: GridCommand.Move): GridSelection {
        val from = s.origin(m.extend)
        val (dr, dc) = when (m.dir) {
            GridDirection.Up -> -1 to 0
            GridDirection.Down -> 1 to 0
            GridDirection.Left -> 0 to (if (rtl) 1 else -1)
            GridDirection.Right -> 0 to (if (rtl) -1 else 1)
        }
        val target = if (m.jump) jump(from, dr, dc) else bounds.clamp(
            CellAddress((from.row + dr).coerceAtLeast(1), (from.col + dc).coerceAtLeast(1)),
        )
        return go(s, target, m.extend)
    }

    /** Excel Ctrl+Arrow: edge of the filled run, or the next filled cell when starting on or before a blank. */
    private fun jump(from: CellAddress, dr: Int, dc: Int): CellAddress {
        fun at(i: Int) = CellAddress(from.row + dr * i, from.col + dc * i)
        fun inside(i: Int): Boolean {
            val r = from.row + dr * i
            val c = from.col + dc * i
            return r in 1..bounds.rows && c in 1..bounds.cols
        }
        if (!inside(1)) return from
        var i = 1
        if (!isBlank(from) && !isBlank(at(1))) {
            while (inside(i + 1) && !isBlank(at(i + 1))) i++
        } else {
            while (inside(i + 1) && isBlank(at(i))) i++
        }
        return at(i)
    }

    private fun tab(s: GridSelection, back: Boolean): GridSelection {
        val a = s.anchor
        val forward = if (rtl) back else !back
        val target = if (forward) {
            when {
                a.col < bounds.cols -> CellAddress(a.row, a.col + 1)
                a.row < bounds.rows -> CellAddress(a.row + 1, 1)
                else -> a
            }
        } else {
            when {
                a.col > 1 -> CellAddress(a.row, a.col - 1)
                a.row > 1 -> CellAddress(a.row - 1, bounds.cols)
                else -> a
            }
        }
        return GridSelection.single(target, bounds)
    }

    private fun enter(s: GridSelection, back: Boolean): NavResult {
        val a = s.anchor
        val single = GridSelection.single(a, bounds)
        return if (!back) {
            if (a.row >= bounds.rows) NavResult.CommitStay(single)
            else NavResult.Moved(GridSelection.single(CellAddress(a.row + 1, a.col), bounds))
        } else {
            if (a.row <= 2) NavResult.CommitStay(single)
            else NavResult.Moved(GridSelection.single(CellAddress(a.row - 1, a.col), bounds))
        }
    }
}
