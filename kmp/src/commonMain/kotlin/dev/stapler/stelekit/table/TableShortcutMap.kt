package dev.stapler.stelekit.table

/**
 * Plain-data chord (no androidx types, so `table/` stays Compose-free). [mod] is Ctrl on Windows, Linux
 * and Web and Cmd on macOS. [key] uses DOM-style names: `Enter`, `Tab`, `Escape`, `ArrowDown`, `F2`, `A`, `=`.
 */
data class Chord(
    val key: String,
    val mod: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    override fun toString(): String =
        listOfNotNull("Mod".takeIf { mod }, "Shift".takeIf { shift }, "Alt".takeIf { alt }, key).joinToString("+")
}

data class ShortcutBinding(val chord: Chord, val mode: GridMode, val command: GridCommand)

/** ADR-009: the one source for the grid's chords; the shortcuts panel and the key tests read it. */
object TableShortcutMap {
    private fun nav(key: String, cmd: GridCommand, mod: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        ShortcutBinding(Chord(key, mod, shift, alt), GridMode.Navigate, cmd)

    private fun edit(key: String, cmd: GridCommand, mod: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        ShortcutBinding(Chord(key, mod, shift, alt), GridMode.Edit, cmd)

    private val arrows = listOf(
        "ArrowUp" to GridDirection.Up,
        "ArrowDown" to GridDirection.Down,
        "ArrowLeft" to GridDirection.Left,
        "ArrowRight" to GridDirection.Right,
    )

    val bindings: List<ShortcutBinding> = buildList {
        for ((key, dir) in arrows) {
            add(nav(key, GridCommand.Move(dir)))
            add(nav(key, GridCommand.Move(dir, extend = true), shift = true))
            add(nav(key, GridCommand.Move(dir, jump = true), mod = true))
            add(nav(key, GridCommand.Move(dir, extend = true, jump = true), mod = true, shift = true))
        }
        add(nav("Tab", GridCommand.Tab()))
        add(nav("Tab", GridCommand.Tab(back = true), shift = true))
        add(nav("Enter", GridCommand.StartEdit))
        add(nav("Enter", GridCommand.Enter(back = true), shift = true))
        add(nav("F2", GridCommand.StartEdit))
        add(nav("Home", GridCommand.LineEdge(end = false)))
        add(nav("End", GridCommand.LineEdge(end = true)))
        add(nav("Home", GridCommand.LineEdge(end = false, extend = true), shift = true))
        add(nav("End", GridCommand.LineEdge(end = true, extend = true), shift = true))
        add(nav("Home", GridCommand.TableEdge(end = false), mod = true))
        add(nav("End", GridCommand.TableEdge(end = true), mod = true))
        add(nav("Home", GridCommand.TableEdge(end = false, extend = true), mod = true, shift = true))
        add(nav("End", GridCommand.TableEdge(end = true, extend = true), mod = true, shift = true))
        add(nav("PageUp", GridCommand.Page(down = false)))
        add(nav("PageDown", GridCommand.Page(down = true)))
        add(nav("PageUp", GridCommand.Page(down = false, extend = true), shift = true))
        add(nav("PageDown", GridCommand.Page(down = true, extend = true), shift = true))
        add(nav("Space", GridCommand.SelectColumn, mod = true))
        add(nav("Space", GridCommand.SelectRow, shift = true))
        add(nav("A", GridCommand.SelectAll, mod = true))
        add(nav("C", GridCommand.Copy, mod = true))
        add(nav("X", GridCommand.Cut, mod = true))
        add(nav("V", GridCommand.Paste, mod = true))
        add(nav("Delete", GridCommand.ClearCells))
        add(nav("Backspace", GridCommand.ClearCells))
        add(nav("Z", GridCommand.Undo, mod = true))
        add(nav("Z", GridCommand.Redo, mod = true, shift = true))
        add(nav("Y", GridCommand.Redo, mod = true))
        add(nav("=", GridCommand.InsertRowOrColumn, mod = true, shift = true))
        add(nav("-", GridCommand.DeleteRowOrColumn, mod = true))
        add(nav("Escape", GridCommand.Cancel))
        add(nav("Enter", GridCommand.ToggleMaximize, mod = true, shift = true))
        add(nav("F2", GridCommand.EditAsMarkdown, shift = true))
        add(nav("F10", GridCommand.OpenContextMenu, shift = true))
        add(nav("ContextMenu", GridCommand.OpenContextMenu))

        // Edit mode consumes only these; Ctrl+Z is field-scoped there (UX-86), decided by the host.
        add(edit("Enter", GridCommand.Enter()))
        add(edit("Enter", GridCommand.Enter(back = true), shift = true))
        add(edit("Tab", GridCommand.Tab()))
        add(edit("Tab", GridCommand.Tab(back = true), shift = true))
        add(edit("Escape", GridCommand.Cancel))
        add(edit("Enter", GridCommand.InsertLineBreak, alt = true))
        add(edit("Z", GridCommand.Undo, mod = true))
        add(edit("Z", GridCommand.Redo, mod = true, shift = true))
        add(edit("Y", GridCommand.Redo, mod = true))
        add(edit("Enter", GridCommand.ToggleMaximize, mod = true, shift = true))
    }

    fun lookup(chord: Chord, mode: GridMode): GridCommand? =
        bindings.firstOrNull { it.chord == chord && it.mode == mode }?.command
}
