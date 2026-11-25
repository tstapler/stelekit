package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableShortcutMapTest {
    @Test
    fun map_should_HaveNoDuplicateChordsAndNoConflictWithShippedBindings_When_ComparedToAdr009Table() {
        val keys = TableShortcutMap.bindings.map { it.chord to it.mode }
        assertEquals(keys.size, keys.toSet().size, "duplicate (chord, mode): ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}")

        val chords = TableShortcutMap.bindings.map { it.chord }.toSet()
        // ADR-009 table: shipped App/PageView chords and the two rejected proposals.
        // Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y are omitted on purpose: the grid shadows them while focused.
        val shipped = listOf(
            Chord("P", mod = true, shift = true), Chord("K", mod = true), Chord("B", mod = true),
            Chord("B", mod = true, shift = true), Chord(",", mod = true), Chord("D", mod = true, shift = true),
            Chord("E", mod = true, shift = true), Chord("[", mod = true), Chord("]", mod = true),
            Chord("M", mod = true, alt = true),
        )
        for (c in shipped) assertFalse(c in chords, "$c collides with a shipped or rejected binding")

        assertEquals(
            GridCommand.ToggleMaximize,
            TableShortcutMap.lookup(Chord("Enter", mod = true, shift = true), GridMode.Navigate),
        )
        assertEquals(GridCommand.EditAsMarkdown, TableShortcutMap.lookup(Chord("F2", shift = true), GridMode.Navigate))
    }

    @Test
    fun map_should_NeverCloseSheetOnEsc_And_BindEditModeOnlyToEditKeys() {
        assertEquals(GridCommand.Cancel, TableShortcutMap.lookup(Chord("Escape"), GridMode.Edit))
        assertTrue(TableShortcutMap.bindings.none { it.command == GridCommand.ToggleMaximize && it.chord.key == "Escape" })
        val editCommands = TableShortcutMap.bindings.filter { it.mode == GridMode.Edit }.map { it.command }
        assertTrue(editCommands.none { it is GridCommand.Move || it == GridCommand.Copy || it == GridCommand.Paste })
    }

    @Test
    fun lookup_should_ReturnNull_When_ChordUnbound() {
        assertEquals(null, TableShortcutMap.lookup(Chord("D", mod = true), GridMode.Navigate))
    }
}
