// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridDirection
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.TableShortcutMap
import dev.stapler.stelekit.ui.components.sheet.SheetMenuTarget
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Story 6.1a/6.1b: the sheet announced as a grid, every drag and shortcut reachable by a semantic action, and every
 * layer left by Esc and Back with focus returning to the grid. Robolectric only: TalkBack itself is Task 8.2a.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w800dp-h780dp-xxhdpi")
class SheetA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(content: String = gridContent(rows = 5, cols = 4), readOnly: Boolean = false, real: Boolean = true, wrap: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { it() }): SheetFixture =
        SheetFixture(rule, content).also {
            fixture = it
            it.show(readOnly = readOnly, realCommands = real, wrap = wrap)
        }

    private fun actionsOf(tag: String): List<CustomAccessibilityAction> =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun labelsOf(tag: String) = actionsOf(tag).map { it.label }

    private fun invoke(tag: String, label: String): Boolean {
        val action = actionsOf(tag).single { it.label == label }
        var ok = false
        rule.runOnUiThread { ok = action.action() }
        rule.waitForIdle()
        return ok
    }

    private fun select(f: SheetFixture, a: CellAddress, b: CellAddress = a) {
        rule.runOnUiThread { f.s.selectRange(GridSelection(a, b)) }
        rule.waitForIdle()
    }

    // ---- 6.1a grid semantics -------------------------------------------------------------------------------------

    @Test
    fun grid_should_CarryTheFullCounts_NotTheLazyWindow_When_200BodyRowsAnd12Columns() {
        open(gridContent(rows = 200, cols = 12))
        val info = rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().config[SemanticsProperties.CollectionInfo]
        assertEquals(201, info.rowCount)
        assertEquals(12, info.columnCount)
        val composedRows = rule.onAllNodes(hasTestTag("sheetRow-30")).fetchSemanticsNodes().size
        assertEquals(0, composedRows, "row 30 is outside the lazy window, so the counts above are not the composed ones")
    }

    @Test
    fun cells_should_ExposePosition_Heading_AndSelected() {
        val f = open()
        select(f, CellAddress(3, 2))
        val b3 = rule.onNodeWithTag("cell-B3").fetchSemanticsNode().config
        val item = b3[SemanticsProperties.CollectionItemInfo]
        assertEquals(2, item.rowIndex)
        assertEquals(1, item.columnIndex)
        assertEquals(true, b3.getOrNull(SemanticsProperties.Selected))
        assertEquals(null, rule.onNodeWithTag("cell-C3").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        val header = rule.onNodeWithTag("cell-B1").fetchSemanticsNode().config
        assertTrue(header.contains(SemanticsProperties.Heading))
        assertFalse(b3.contains(SemanticsProperties.Heading))
        assertEquals(0, header[SemanticsProperties.CollectionItemInfo].rowIndex)
    }

    @Test
    fun formulaAndErrorCells_should_SpeakTheirFormulaValueAndReason() {
        open("| A | B | C |\n|---|---|---|\n| 1 | 2 | =SUM(A2:B2) |\n| =B3 | =A3 | =A3+1 |")
        val sum = rule.onNodeWithTag("cell-C2").fetchSemanticsNode().config
        assertEquals("Formula =SUM(A2:B2), value 3", sum[SemanticsProperties.StateDescription])
        val cycle = rule.onNodeWithTag("cell-A3").fetchSemanticsNode().config
        assertTrue(cycle[SemanticsProperties.Error].startsWith("Circular reference"))
        val downstream = rule.onNodeWithTag("cell-C3").fetchSemanticsNode().config
        assertEquals("Circular reference", downstream[SemanticsProperties.Error])
        assertEquals(null, rule.onNodeWithTag("cell-A2").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun readOnlyGrid_should_SaySo_AndOfferNoMutatingAction() {
        open(readOnly = true)
        assertEquals("Read only", rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val labels = labelsOf("cell-B3")
        assertTrue(TableMessages.EDIT_CELL !in labels && TableMessages.PASTE !in labels && TableMessages.MOVE_ROW_UP !in labels, "$labels")
        assertTrue(TableMessages.COPY in labels && TableMessages.SELECT_ROW in labels)
    }

    // ---- 6.1a custom actions -------------------------------------------------------------------------------------

    @Test
    fun headerCells_should_OfferTheStructureSortAndResizeActionsWithTheFileWarning() {
        open()
        val expected = listOf(
            "Move column left", "Move column right", "Resize column",
            "Sort rows A-Z (changes file)", "Sort rows Z-A (changes file)", "Insert column right",
        )
        val header = labelsOf("cell-B1")
        assertEquals(expected, header.take(expected.size))
        assertEquals(expected, labelsOf("letter-B"))
        assertTrue("Move column left" !in labelsOf("letter-A"))
        assertTrue("Move column right" !in labelsOf("letter-D"))
    }

    @Test
    fun headerActions_should_MoveSortAndResizeThroughTheSameCommandsAsTheMenu() {
        val f = open()
        assertTrue(invoke("letter-B", "Move column right"))
        assertEquals("H2", f.s.rawText(CellAddress(1, 3)))
        assertTrue(invoke("letter-C", "Sort rows Z-A (changes file)"))
        assertEquals("6-2", f.s.rawText(CellAddress(2, 3)), "the moved column is now C")
        assertTrue(invoke("letter-A", "Resize column"))
        assertEquals(SheetMenuTarget.Column(1), f.s.menu)
        rule.runOnUiThread { f.s.closeMenu() }
        assertTrue(invoke("letter-A", "Insert column right"))
        assertEquals(5, f.s.columnCount)
    }

    @Test
    fun cells_should_OfferEditExtendSelectCopyPasteMoveAndInsertActions() {
        open()
        val expected = listOf(
            "Edit cell",
            "Extend selection right", "Extend selection down", "Extend selection to row end",
            "Extend selection to column end", "Extend selection to table end",
            "Select row", "Select column", "Select all", "Copy", "Paste",
            "Move row up", "Move row down", "Insert row below", "Insert column right",
        )
        assertEquals(expected, labelsOf("cell-B3"))
    }

    @Test
    fun cellActions_should_RunTheSameCommandsAsKeysAndMenus() {
        val f = open()
        select(f, CellAddress(3, 2))
        assertTrue(invoke("cell-B3", "Extend selection right"))
        assertEquals(CellAddress(3, 3), f.s.selection.focus)
        assertTrue(invoke("cell-B3", "Select row"))
        assertEquals(GridSelection.Kind.Rows, f.s.selection.kind)
        assertTrue(invoke("cell-B3", "Copy"))
        rule.waitForIdle()
        assertEquals("3-1\t3-2\t3-3\t3-4", f.clipboard.text)
        assertTrue(invoke("cell-B3", "Move row up"))
        assertEquals("3-1", f.s.rawText(CellAddress(2, 1)))
        assertTrue(invoke("cell-B3", "Insert row below"))
        assertEquals(7, f.s.rowCount)
        assertTrue(invoke("cell-B3", "Edit cell"))
        assertEquals(GridMode.Edit, f.s.mode)
    }

    @Test
    fun cellsOnTheEdges_should_DropTheMoveActionsThatCannotRun() {
        open()
        assertTrue("Move row up" !in labelsOf("cell-B2"))
        assertTrue("Move row down" in labelsOf("cell-B2"))
        assertTrue("Move row down" !in labelsOf("cell-B6"))
    }

    @Test
    fun announcements_should_GoThroughOnePoliteLiveRegion() {
        val f = open()
        select(f, CellAddress(3, 2))
        invoke("cell-B3", "Insert row below")
        val node = rule.onNodeWithTag("announcement").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, node[SemanticsProperties.LiveRegion])
        assertEquals(TableMessages.rowsInserted(1), node[SemanticsProperties.ContentDescription].single())
    }

    // ---- 6.1b every shortcut has an alternative ------------------------------------------------------------------

    /** Where a command can also be reached on the sheet; each entry is checked against the live UI below. */
    private sealed interface Alt {
        data class CellAction(val label: String) : Alt
        data class HeaderAction(val label: String) : Alt
        data class Strip(val tag: String) : Alt
        data class MenuItem(val target: SheetMenuTarget, val id: String) : Alt
        data class Control(val tag: String) : Alt
        data class EditAction(val label: String) : Alt
        data class Tap(val why: String) : Alt
    }

    private fun sheetAlternatives(command: GridCommand): List<Alt> = when (command) {
        is GridCommand.Move ->
            if (command.extend || command.jump) {
                listOf(Alt.CellAction(TableMessages.extendLabel(extendWord(command.dir))), Alt.Strip("strip:Extend"))
            } else {
                listOf(Alt.Tap("tap a cell; TalkBack moves between cells by collectionItemInfo"))
            }
        is GridCommand.Tab, is GridCommand.Enter, is GridCommand.Page, is GridCommand.LineEdge, is GridCommand.TableEdge ->
            if ((command as? GridCommand.LineEdge)?.extend == true || (command as? GridCommand.TableEdge)?.extend == true || (command as? GridCommand.Page)?.extend == true) {
                listOf(Alt.Strip("strip:Extend"), Alt.CellAction("Extend selection to row end"))
            } else {
                listOf(Alt.Tap("tap a cell or scroll; the cell onClick and collectionItemInfo cover the move"))
            }
        GridCommand.SelectColumn -> listOf(Alt.CellAction(TableMessages.SELECT_COLUMN), Alt.Strip("strip:SelectColumn"))
        GridCommand.SelectRow -> listOf(Alt.CellAction(TableMessages.SELECT_ROW), Alt.Strip("strip:SelectRow"))
        GridCommand.SelectAll -> listOf(Alt.CellAction(TableMessages.SELECT_ALL), Alt.Strip("strip:SelectAll"))
        GridCommand.StartEdit -> listOf(Alt.CellAction(TableMessages.EDIT_CELL))
        GridCommand.Cancel -> listOf(Alt.Control("sheetClose"))
        GridCommand.ClearCells -> listOf(Alt.Strip("strip:Clear"), Alt.MenuItem(SheetMenuTarget.Cell(CellAddress(3, 2)), "clear"))
        GridCommand.Copy -> listOf(Alt.CellAction(TableMessages.COPY), Alt.Strip("strip:Copy"))
        GridCommand.Cut -> listOf(Alt.Strip("strip:Cut"), Alt.MenuItem(SheetMenuTarget.Cell(CellAddress(3, 2)), "cut"))
        GridCommand.Paste -> listOf(Alt.CellAction(TableMessages.PASTE), Alt.Strip("strip:Paste"))
        GridCommand.Undo -> listOf(Alt.Strip("strip:Undo"), Alt.Control("sheetUndo"))
        GridCommand.Redo -> listOf(Alt.Strip("strip:Redo"), Alt.Control("sheetRedo"))
        GridCommand.InsertLineBreak -> listOf(Alt.EditAction(TableMessages.LINE_BREAK))
        GridCommand.InsertRowOrColumn ->
            listOf(Alt.CellAction(TableMessages.INSERT_ROW_BELOW), Alt.CellAction(TableMessages.INSERT_COLUMN_RIGHT))
        GridCommand.DeleteRowOrColumn ->
            listOf(Alt.MenuItem(SheetMenuTarget.Row(3), "deleteRow"), Alt.MenuItem(SheetMenuTarget.Column(2), "deleteColumn"))
        GridCommand.ToggleMaximize -> listOf(Alt.Control("sheetClose"))
        GridCommand.EditAsMarkdown -> listOf(Alt.Control("sheetOverflow"))
        GridCommand.OpenContextMenu -> listOf(Alt.Strip("strip:ColumnMenu"), Alt.Strip("strip:RowMenu"))
        is GridCommand.MoveSelection -> when (command.dir) {
            GridDirection.Up -> listOf(Alt.CellAction(TableMessages.MOVE_ROW_UP))
            GridDirection.Down -> listOf(Alt.CellAction(TableMessages.MOVE_ROW_DOWN))
            GridDirection.Left -> listOf(Alt.HeaderAction(TableMessages.MOVE_COLUMN_LEFT))
            GridDirection.Right -> listOf(Alt.HeaderAction(TableMessages.MOVE_COLUMN_RIGHT))
        }
    }

    private fun extendWord(dir: GridDirection) = when (dir) {
        GridDirection.Left, GridDirection.Right -> "right"
        GridDirection.Up, GridDirection.Down -> "down"
    }

    @Test
    fun everyShortcutCommand_should_AlsoBeReachableByMenuStripOrCustomAction_OnTheSheet() {
        val f = open()
        select(f, CellAddress(3, 2))
        val cellActions = labelsOf("cell-B3")
        val headerActions = labelsOf("letter-B")
        val commands = TableShortcutMap.bindings.map { it.command }.distinct()
        assertTrue(commands.size > 20, "the map is the input; ${commands.size} distinct commands")
        for (command in commands) {
            val alternatives = sheetAlternatives(command)
            assertTrue(alternatives.isNotEmpty(), "$command has no alternative")
            for (alt in alternatives) {
                when (alt) {
                    is Alt.CellAction -> assertTrue(alt.label in cellActions, "$command: no cell action ${alt.label} in $cellActions")
                    is Alt.HeaderAction -> assertTrue(alt.label in headerActions, "$command: no header action ${alt.label}")
                    is Alt.Strip -> assertTrue(rule.exists(alt.tag), "$command: no strip control ${alt.tag}")
                    is Alt.Control -> assertTrue(rule.exists(alt.tag), "$command: no control ${alt.tag}")
                    is Alt.MenuItem ->
                        assertTrue(f.s.sink.menuFor(alt.target)?.items?.any { it.id == alt.id } == true, "$command: no menu item ${alt.id}")
                    is Alt.EditAction -> {
                        rule.runOnUiThread { f.s.startEdit(CellAddress(3, 2)) }
                        rule.waitForIdle()
                        assertTrue(alt.label in labelsOf("cell-B3"), "$command: no edit action ${alt.label} in ${labelsOf("cell-B3")}")
                        rule.runOnUiThread { f.s.cancelEdit() }
                        rule.waitForIdle()
                    }
                    is Alt.Tap -> assertTrue(
                        rule.onNodeWithTag("cell-B3").fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
                        "$command: cells are not tappable",
                    )
                }
            }
        }
    }

    // ---- 6.1b Back and Esc ----------------------------------------------------------------------------------------

    private fun back(key: LadderKey): LadderAction {
        var action: LadderAction? = null
        rule.runOnUiThread { action = fixture!!.h.session!!.onBack(key) }
        rule.waitForIdle()
        return action!!
    }

    private fun gridHasFocus() = rule.onNodeWithTag("sheetGridFocus").assertIsFocused()

    @Test
    fun everyLayer_should_ExitViaEscAndBack_When_OpenedFromSection3_3List() {
        val f = open()
        for (key in listOf(LadderKey.Esc, LadderKey.Back)) {
            rule.onNodeWithTag("sheetGridFocus").requestFocus()
            select(f, CellAddress(3, 2))

            // Rung 2: a column menu.
            rule.runOnUiThread { f.s.openMenu(SheetMenuTarget.Column(2)) }
            rule.waitForIdle()
            assertTrue(f.s.menuOpen)
            back(key)
            assertFalse(f.s.menuOpen, "$key closes the menu")
            gridHasFocus()

            // Rung 4: edit with the IME hidden. Esc cancels, Back commits; neither closes the sheet.
            rule.runOnUiThread { f.s.startEdit(CellAddress(3, 2)) }
            rule.waitForIdle()
            rule.onNodeWithTag("barField").performTextReplacement("changed-$key")
            rule.waitForIdle()
            back(key)
            assertEquals(GridMode.Navigate, f.s.mode, "$key leaves Edit")
            assertEquals(if (key == LadderKey.Back) "changed-$key" else "3-2", f.s.rawText(CellAddress(3, 2)))
            gridHasFocus()

            // Rung 5: a range collapses.
            select(f, CellAddress(3, 2), CellAddress(4, 3))
            back(key)
            assertTrue(f.s.selection.isSingleCell, "$key collapses the range")
            gridHasFocus()
        }
        assertTrue(f.h.closes.isEmpty(), "no layer above the last rung closed the sheet")
        // The last rung: Back closes the sheet, Esc does nothing.
        assertEquals(LadderAction.NotHandled, back(LadderKey.Esc))
        assertTrue(f.h.closes.isEmpty())
        assertEquals(LadderAction.CloseSheet, back(LadderKey.Back))
        assertEquals(listOf(dev.stapler.stelekit.ui.components.sheet.SheetCloseReason.User), f.h.closes)
    }

    // ---- 6.1b focus ring and Tab ----------------------------------------------------------------------------------

    private fun press(key: Key, shift: Boolean = false) {
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            if (shift) keyDown(Key.ShiftLeft)
            pressKey(key)
            if (shift) keyUp(Key.ShiftLeft)
        }
        rule.waitForIdle()
    }

    @Test
    fun focusRing_should_Contrast3To1_AndTabEnterLeaveOnce_When_TabbedThroughSheet() {
        val f = SheetFixture(rule, gridContent(rows = 2, cols = 3)).also { fixture = it; it.showBare() }
        rule.onNodeWithTag("sheetGridFocus").requestFocus()
        select(f, CellAddress(1, 1))

        // The ring the sheet draws, rendered offscreen: wide enough and 3:1 against every cell background, light and dark.
        for ((name, scheme) in listOf("light" to androidx.compose.material3.lightColorScheme(), "dark" to androidx.compose.material3.darkColorScheme())) {
            val colors = dev.stapler.stelekit.ui.components.sheet.sheetColorsFor(scheme, dev.stapler.stelekit.ui.components.sheet.TableVisualPrefs())
            val (width, edge) = renderedOutline(colors.outline, 2f, scheme.surface)
            assertTrue(width >= 6, "$name ring is $width px at 3x")
            dev.stapler.stelekit.ui.components.sheet.cellBackgrounds(scheme).forEach { assertTrue(ratio(edge, it) >= 3.0, "$name $edge on $it") }
        }

        // Tab walks the 3x3 cells one at a time and leaves the grid once, at the end.
        val visited = mutableListOf(f.s.selection.anchor)
        repeat(8) {
            press(Key.Tab)
            visited += f.s.selection.anchor
        }
        assertEquals(9, visited.distinct().size, "every cell is one Tab apart: $visited")
        gridHasFocus()
        press(Key.Tab)
        rule.waitForIdle()
        assertEquals(CellAddress(3, 3), f.s.selection.anchor, "the last Tab does not wrap")
        rule.onNodeWithTag("sheetGridFocus").assertIsNotFocusedCompat()
        // Shift+Tab from outside re-enters on the grid as one stop, and leaves backwards from the first cell.
        rule.onNodeWithTag("sheetGridFocus").requestFocus()
        select(f, CellAddress(1, 1))
        press(Key.Tab, shift = true)
        rule.onNodeWithTag("sheetGridFocus").assertIsNotFocusedCompat()
        assertEquals(CellAddress(1, 1), f.s.selection.anchor)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotFocusedCompat() {
        assertEquals(false, fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused), "focus left the grid")
    }

    private fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        fun lum(c: androidx.compose.ui.graphics.Color) = c.luminance().toDouble()
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // ---- 6.1b large font ------------------------------------------------------------------------------------------

    @Test
    fun largeFont_should_GrowTheRowAndKeepEveryControlAt48dp_When_A120CharacterCellIsShown() {
        val long = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega"
        val content = "| Name | Note |\n|---|---|\n| short | ${long.take(120).trim()} |\n| two | 2 |"
        val f = open(content, wrap = { inner ->
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) { inner() }
        })
        val dp = rule.density.density
        val row = rule.onNodeWithTag("sheetRow-2").fetchSemanticsNode().boundsInRoot
        assertTrue(row.height > 48 * dp * 1.5f, "the row grew to fit the wrapped text: ${row.height / dp}dp")
        val cell = rule.onNodeWithTag("cell-B2", useUnmergedTree = true).fetchSemanticsNode()
        val text = rule.onNodeWithTag("cell-B2", useUnmergedTree = true).onChildren().fetchSemanticsNodes().first().boundsInRoot
        assertTrue(text.height <= cell.boundsInRoot.height, "the text sits inside its cell: ${text.height} <= ${cell.boundsInRoot.height}")
        for (tag in listOf("strip:Undo", "strip:Redo", "strip:Copy", "strip:SelectRow", "sheetClose", "sheetUndo")) {
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(b.height >= 48 * dp - 1f && b.width >= 48 * dp - 1f, "$tag is ${b.width / dp} x ${b.height / dp} dp")
        }
        select(f, CellAddress(2, 2))
        rule.runOnUiThread { f.s.startEdit(CellAddress(2, 2)) }
        rule.waitForIdle()
        for (tag in listOf("barCancel", "barDone", "barOverflow").filter { rule.exists(it) }) {
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(b.height >= 48 * dp - 1f && b.width >= 48 * dp - 1f, "$tag is ${b.width / dp} x ${b.height / dp} dp")
        }
    }
}
