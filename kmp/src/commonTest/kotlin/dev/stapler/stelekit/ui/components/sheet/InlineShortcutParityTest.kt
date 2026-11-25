// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.TableShortcutMap
import dev.stapler.stelekit.ui.components.TableMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UX-53, UX-70, UX-82 on the inline surface: each chord the inline table consumes has a custom action or strip control,
 * and each one it does not consume is listed, so binding a new one forces a decision here.
 */
class InlineShortcutParityTest {
    private val table = "| Item | Qty | Note |\n|---|---|---|\n| Apple | 3 | red |\n| Pear | 5 | green |"

    private fun state(): InlineTableState {
        val s = InlineTableState(table) { }
        s.hooks.copyToClipboard = { _, done -> done(true) }
        s.hooks.editAsMarkdown = { }
        s.hooks.openSheet = { }
        return s
    }

    private val stripLabels = StripAction.entries.map { it.label }

    /** The labels that reach [command] without a keyboard; empty means a tap or the platform's own cell navigation. */
    private fun alternatives(command: GridCommand): List<String> = when (command) {
        GridCommand.Copy -> listOf(TableMessages.COPY)
        GridCommand.Undo -> listOf(TableMessages.UNDO)
        GridCommand.Redo -> listOf(TableMessages.REDO)
        GridCommand.ToggleMaximize -> listOf(TableMessages.MAXIMIZE)
        GridCommand.EditAsMarkdown -> listOf(TableMessages.EDIT_AS_MARKDOWN)
        GridCommand.SelectRow -> listOf(TableMessages.SELECT_ROW)
        GridCommand.SelectColumn -> listOf(TableMessages.SELECT_COLUMN)
        GridCommand.SelectAll -> listOf(TableMessages.SELECT_TABLE)
        GridCommand.StartEdit -> listOf(TableMessages.EDIT_CELL)
        GridCommand.Cancel -> listOf(StripAction.Close.label)
        is GridCommand.Move -> if (command.extend || command.jump) listOf(TableMessages.extendLabel("right")) else emptyList()
        is GridCommand.LineEdge -> if (command.extend) listOf(TableMessages.extendLabel("to row end")) else emptyList()
        is GridCommand.TableEdge -> if (command.extend) listOf(TableMessages.extendLabel("to table end")) else emptyList()
        is GridCommand.Page -> if (command.extend) listOf(TableMessages.extendLabel("down")) else emptyList()
        is GridCommand.Tab, is GridCommand.Enter -> emptyList()
        else -> error("$command is not bound on the inline table")
    }

    @Test
    fun everyChordTheInlineTableConsumes_should_HaveACustomActionOrStripControl() {
        val consumed = mutableSetOf<GridCommand>()
        val unbound = mutableSetOf<GridCommand>()
        for (binding in TableShortcutMap.bindings.filter { it.mode == GridMode.Navigate }) {
            val s = state()
            s.dragSelect(CellAddress(2, 2), CellAddress(2, 2))
            if (s.handleNavigateKey(binding.chord)) consumed += binding.command else unbound += binding.command
        }
        val s = state()
        val labels = cellCustomActions(s, CellAddress(2, 2), sheetEnabled = true, editing = false).map { it.label } + TableMessages.EDIT_CELL + stripLabels
        assertTrue(consumed.size > 15, "the inline table consumes ${consumed.size} distinct commands")
        for (command in consumed) {
            for (label in alternatives(command)) assertTrue(label in labels, "$command: no custom action or strip control '$label' in $labels")
        }
        assertEquals(
            setOf(
                GridCommand.ClearCells, GridCommand.Cut, GridCommand.Paste, GridCommand.InsertRowOrColumn,
                GridCommand.DeleteRowOrColumn, GridCommand.OpenContextMenu,
            ) + GridDirectionMoves,
            unbound - consumed,
            "commands the inline table does not bind; binding one needs an alternative above",
        )
    }

    private val GridDirectionMoves = dev.stapler.stelekit.table.GridDirection.entries.map { GridCommand.MoveSelection(it) }

    @Test
    fun editingCell_should_OfferNextColumnAndLineBreakAsCustomActions() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Touch)
        val labels = cellCustomActions(s, CellAddress(2, 2), sheetEnabled = true, editing = true).map { it.label }
        assertEquals(listOf(TableMessages.NEXT_COLUMN, TableMessages.LINE_BREAK), labels)
    }

    @Test
    fun heldConflict_should_OfferResolveAsACustomAction_OnlyWhileHeld() {
        val s = state()
        s.startEdit(CellAddress(2, 2), EntryProfile.Hardware)
        s.onFieldChange(androidx.compose.ui.text.input.TextFieldValue("45", androidx.compose.ui.text.TextRange(2)))
        assertTrue(TableMessages.RESOLVE !in cellCustomActions(s, CellAddress(2, 2), true, editing = true).map { it.label })
        s.onContent("| Item | Qty | Note |\n|---|---|---|\n| Apple | 99 | red |\n| Pear | 5 | green |")
        assertTrue(s.conflictHeld)
        assertTrue(TableMessages.RESOLVE in cellCustomActions(s, CellAddress(2, 2), true, editing = true).map { it.label })
        s.dismissBanner()
        assertTrue(!s.conflictBannerOpen)
        val resolve = cellCustomActions(s, CellAddress(3, 1), true, editing = true).single { it.label == TableMessages.RESOLVE }
        assertTrue(resolve.action())
        assertTrue(s.conflictBannerOpen, "the Resolve action reopens the banner")
    }
}
