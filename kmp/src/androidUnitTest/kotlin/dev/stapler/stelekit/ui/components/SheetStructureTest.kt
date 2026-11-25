// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.ui.WindowSizeClass
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

/** Story 5.5 on the real sheet body over the real block manager: header and row menus, one undo per operation, keys and guard rails. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w800dp-h780dp-xxhdpi")
class SheetStructureTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val base = "| Name | Qty | Note |\n|---|---|---|\n| Pear | 5 | a |\n| Apple | 3 | b |\n| Fig | 4 | c |"
    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(content: String = base, readOnly: Boolean = false, sizeClass: WindowSizeClass? = null): SheetFixture =
        SheetFixture(rule, content).also {
            fixture = it
            it.show(readOnly = readOnly, realCommands = true, sizeClass = sizeClass)
        }

    private fun addr(text: String) = CellAddress(text.drop(1).toInt(), text[0] - 'A' + 1)

    private fun select(f: SheetFixture, a: String, b: String = a) {
        rule.runOnUiThread { f.s.selectRange(GridSelection(addr(a), addr(b))) }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) {
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            if (alt) keyDown(Key.AltLeft)
            pressKey(key)
            if (alt) keyUp(Key.AltLeft)
            if (shift) keyUp(Key.ShiftLeft)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun doc(f: SheetFixture) = (TableParser.parse(f.h.managerContent()) as TableParseResult.Parsed).doc

    private fun undo(f: SheetFixture) {
        f.h.session!!.undo()
        rule.waitForIdle()
    }

    private fun noticeText() = rule.textOf("noticeText")

    private fun labelOf(tag: String): String? = rule.textOf(tag)

    @Test
    fun columnLetterTap_should_SelectTheColumnAndListEveryColumnMenuEntry() {
        val f = open()
        rule.onNodeWithTag("letter-B").performClick()
        rule.waitForIdle()
        assertEquals(GridSelection.Kind.Columns, f.s.selection.kind)
        val expected = listOf(
            "sortAsc" to "Sort rows A-Z (changes file)", "sortDesc" to "Sort rows Z-A (changes file)", "alignLeft" to "Align left",
            "alignCenter" to "Align center", "alignRight" to "Align right", "insertLeft" to "Insert column left",
            "insertRight" to "Insert column right", "moveLeft" to "Move column left", "moveRight" to "Move column right",
            "deleteColumn" to "Delete column",
        )
        expected.forEach { (id, label) ->
            val text = labelOf("menu-$id")!!.removePrefix("[ ] ").removePrefix("[x] ")
            assertTrue(text.startsWith(label), "$id reads $label, was $text")
        }
        listOf("widthNarrow", "widthMedium", "widthWide", "widthAutoFit", "freezeFirstColumn").forEach { assertTrue(rule.exists("menu-$it"), it) }
    }

    @Test
    fun rowGutterTap_should_ListTheRowMenu_WithHeaderRowEntriesDisabledAndExplained() {
        val f = open()
        rule.onNodeWithTag("gutter-1").performClick()
        rule.waitForIdle()
        assertEquals(GridSelection.Kind.Rows, f.s.selection.kind)
        listOf("insertAbove", "moveUp", "moveDown", "duplicateRow", "deleteRow").forEach {
            val disabled = rule.onNodeWithTag("menu-$it").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            assertTrue(disabled, "$it is disabled on the header row")
        }
        assertTrue(labelOf("menu-deleteRow").orEmpty().contains(TableMessages.HEADER_ROW_REFUSED), "the reason is stated on the entry")
    }

    @Test
    fun insertColumnRight_should_AddOneColumnToEveryRow_AndOneUndoRestoresTheExactString() {
        val f = open()
        rule.onNodeWithTag("letter-B").performClick()
        tap("menu-insertRight")
        val d = doc(f)
        assertEquals(4, d.columnCount)
        assertTrue((1..d.rowCount).all { d.row(it).cells.size == 4 })
        assertEquals("", d.cell(addr("C2"))?.value)
        assertEquals("a", d.cell(addr("D2"))?.value)
        undo(f)
        assertEquals(base, f.h.managerContent())
    }

    @Test
    fun rowOperations_should_EachBeOneUndoStep() {
        val f = open()
        val depth = f.h.session!!.history.value.undoDepth
        fun steps() = f.h.session!!.history.value.undoDepth - depth
        rule.onNodeWithTag("gutter-3").performClick()
        tap("menu-insertAbove")
        assertEquals(1, steps())
        assertEquals(5, doc(f).rowCount)
        assertEquals("", doc(f).cell(addr("A3"))?.value)
        rule.onNodeWithTag("gutter-4").performClick()
        tap("menu-moveDown")
        assertEquals(2, steps())
        rule.onNodeWithTag("gutter-2").performClick()
        tap("menu-duplicateRow")
        assertEquals(3, steps())
        assertEquals(listOf("Pear", "Pear"), listOf("A2", "A3").map { doc(f).cell(addr(it))?.value })
        rule.onNodeWithTag("gutter-2").performClick()
        tap("menu-deleteRow")
        assertEquals(4, steps())
        repeat(4) { undo(f) }
        assertEquals(base, f.h.managerContent())
    }

    @Test
    fun altShiftArrow_should_SwapTheRowWithItsNeighbour_AndFormulasFollowTheData() {
        val content = "| A | B |\n|---|---|\n| 1 | =A2*2 |\n| 5 | =A3*2 |"
        val f = open(content)
        select(f, "A3", "B3")
        press(Key.DirectionUp, shift = true, alt = true)
        val d = doc(f)
        assertEquals(listOf("5", "=A2*2"), d.row(2).cells.map { it.value }, "the formula follows its data (ADR-003)")
        assertEquals(listOf("1", "=A3*2"), d.row(3).cells.map { it.value })
        assertEquals(addr("A2"), f.s.selection.range.topLeft, "the selection moves with the row")
        undo(f)
        assertEquals(content, f.h.managerContent())
    }

    @Test
    fun altShiftArrowAtTheEdges_should_DoNothing() {
        val f = open()
        select(f, "A2")
        press(Key.DirectionUp, shift = true, alt = true)
        assertEquals(base, f.h.managerContent())
        select(f, "A4")
        press(Key.DirectionDown, shift = true, alt = true)
        assertEquals(base, f.h.managerContent())
    }

    @Test
    fun ctrlShiftEquals_and_CtrlMinus_should_InsertAndDeleteTheSelectedRows() {
        val f = open()
        select(f, "A3")
        press(Key.Equals, ctrl = true, shift = true)
        assertEquals(5, doc(f).rowCount)
        assertEquals("", doc(f).cell(addr("A3"))?.value)
        press(Key.Minus, ctrl = true)
        assertEquals(base, f.h.managerContent())
    }

    @Test
    fun ctrlMinus_should_DeleteTheSelectedColumns_AndNeverTheLastOne() {
        val f = open("| A | B |\n|---|---|\n| 1 | 2 |")
        rule.runOnUiThread { f.s.selectColumn(2, openMenu = false) }
        rule.waitForIdle()
        press(Key.Minus, ctrl = true)
        assertEquals(1, doc(f).columnCount)
        rule.onNodeWithTag("noticeDismiss").performClick()
        rule.runOnUiThread { f.s.selectColumn(1, openMenu = false) }
        rule.waitForIdle()
        press(Key.Minus, ctrl = true)
        assertEquals(1, doc(f).columnCount)
        assertEquals("A table needs at least one column", noticeText())
    }

    @Test
    fun lastColumn_should_HaveDeleteDisabledWithTheReasonInTheMenu() {
        val f = open("| A |\n|---|\n| 1 |")
        rule.onNodeWithTag("letter-A").performClick()
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("menu-deleteColumn").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        assertTrue(labelOf("menu-deleteColumn").orEmpty().contains("A table needs at least one column"))
        assertEquals(addr("A1"), f.s.selection.anchor)
    }

    @Test
    fun deletingTheHeaderRow_should_BeRefusedWithANotice() {
        val f = open()
        select(f, "A1")
        press(Key.Minus, ctrl = true)
        assertEquals(TableMessages.HEADER_ROW_REFUSED, noticeText())
        assertEquals(base, f.h.managerContent())
    }

    @Test
    fun deleteRow_should_ShowAnUndoNotice_ThatRestoresTheTable() {
        val f = open()
        select(f, "A3")
        press(Key.Minus, ctrl = true)
        assertEquals("Deleted 1 row", noticeText())
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertEquals(base, f.h.managerContent())
    }

    @Test
    fun deleteColumn_should_ReportFormulasThatNowShowRef() {
        val content = "| A | B |\n|---|---|\n| 1 | =A2 |"
        val f = open(content)
        rule.runOnUiThread { f.s.selectColumn(1, openMenu = false) }
        rule.waitForIdle()
        press(Key.Minus, ctrl = true)
        assertTrue(noticeText().orEmpty().contains("1 formula now shows #REF!"), noticeText())
    }

    @Test
    fun shiftF10_and_MenuKey_should_OpenTheMenuForTheSelection() {
        val f = open()
        rule.runOnUiThread { f.s.selectColumn(1, openMenu = false) }
        rule.waitForIdle()
        press(Key.F10, shift = true)
        assertTrue(rule.exists("menu-sortAsc"), "Shift+F10 on a column opens its menu")
        press(Key.Escape)
        select(f, "B3")
        press(Key.Menu)
        assertTrue(rule.exists("menu-insertAbove") && rule.exists("menu-copy"), "the Menu key on a cell opens the cell menu")
    }

    @Test
    fun back_should_CloseTheMenuBeforeAnythingElse() {
        val f = open()
        rule.onNodeWithTag("letter-B").performClick()
        rule.waitForIdle()
        assertTrue(f.s.menuOpen)
        rule.runOnUiThread { f.s.onBack(dev.stapler.stelekit.table.LadderKey.Back) }
        rule.waitForIdle()
        assertFalse(f.s.menuOpen)
        assertEquals(GridSelection.Kind.Columns, f.s.selection.kind, "the first Back only closed the menu")
    }

    @Test
    fun readOnly_should_DisableEveryMutatingMenuEntryWithTheReason() {
        val f = open(readOnly = true)
        rule.onNodeWithTag("letter-B").performClick()
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("menu-insertLeft").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        assertTrue(labelOf("menu-insertLeft").orEmpty().contains(TableMessages.READ_ONLY))
        assertNotEquals(null, f.s.menu)
    }

    @Test
    fun compactWidth_should_UseABottomSheetAndWideWidthAPopup() {
        val compact = open(sizeClass = WindowSizeClass.COMPACT)
        rule.onNodeWithTag("letter-B").performClick()
        rule.waitForIdle()
        assertTrue(rule.exists("sheetBottomMenu"), "below 600dp the menu is a bottom sheet")
        assertTrue(rule.exists("menu-insertRight"))
        assertEquals("Column B", rule.textOf("sheetBottomMenu")?.lines()?.firstOrNull() ?: "Column B")
        compact.close()
    }

    @Test
    fun wideWidth_should_UseAnAnchoredPopup() {
        open()
        rule.onNodeWithTag("letter-B").performClick()
        rule.waitForIdle()
        assertFalse(rule.exists("sheetBottomMenu"))
        assertTrue(rule.exists("sheetMenu"))
    }

    @Test
    fun cellMenuByRightClick_should_OfferClipboardAndStructureEntries() {
        val f = open()
        rule.runOnUiThread { f.s.openMenu(dev.stapler.stelekit.ui.components.sheet.SheetMenuTarget.Cell(addr("B3"))) }
        rule.waitForIdle()
        listOf("copy", "cut", "paste", "clear", "insertAbove", "insertLeft", "deleteRow", "deleteColumn").forEach { assertTrue(rule.exists("menu-$it"), it) }
        tap("menu-deleteColumn")
        assertEquals(2, doc(f).columnCount)
    }

    @Test
    fun alignmentAndInserts_should_ShiftColumnWidthsWithTheirColumns() {
        val f = open()
        rule.runOnUiThread { f.s.resizeColumn(2, 200f) }
        rule.onNodeWithTag("letter-A").performClick()
        tap("menu-insertLeft")
        assertEquals(200f, f.s.view.widthOf(3), "the width followed its column")
        assertEquals(120f, f.s.view.widthOf(2))
    }
}
