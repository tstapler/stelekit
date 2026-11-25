// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.ui.components.sheet.STATS_PERIOD_MS
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 5.4a to 5.4c on the real sheet body over the real block manager: range gestures, copy, cut, paste and the status line. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetSelectionClipboardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val formulas = "| Item | Qty | Dbl |\n|---|---|---|\n| Apple | 3 | =B2*2 |\n| Pear | 5 | =B3*2 |\n| a | b | c |\n| d | e | f |"
    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(content: String, readOnly: Boolean = false): SheetFixture =
        SheetFixture(rule, content).also {
            fixture = it
            it.show(readOnly = readOnly, realCommands = true)
        }

    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    private fun select(f: SheetFixture, a: String, b: String = a) = ui { f.s.selectRange(GridSelection(addr(a), addr(b))) }

    private fun addr(text: String) = CellAddress(text.drop(1).toInt(), text[0] - 'A' + 1)

    private fun press(key: Key, ctrl: Boolean = false) {
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            pressKey(key)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun cellText(f: SheetFixture, a: String): String? = f.s.doc?.cell(addr(a))?.value

    private fun parsedContent(f: SheetFixture) = (TableParser.parse(f.h.managerContent()) as TableParseResult.Parsed).doc

    private fun noticeText() = rule.textOf("noticeText")

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** The status line lags a selection change by up to one 500 ms period; this lets it publish the latest. */
    private fun settleStats() {
        rule.mainClock.advanceTimeBy(STATS_PERIOD_MS + 16)
    }

    @Test
    fun copy_should_PutDisplayedValuesAsTsvOnTheClipboard_AndTheInAppPasteRestoresFormulas() {
        val f = open(formulas)
        select(f, "A2", "C2")
        press(Key.C, ctrl = true)
        assertEquals("Apple\t3\t6", f.clipboard.text, "a formula cell copies its value, as Sheets does")
        assertEquals("Copied 1 x 3 cells", noticeText())
        select(f, "A5")
        press(Key.V, ctrl = true)
        assertEquals("=B2*2", cellText(f, "C5"), "the slot matched, so the formula text is back")
        assertEquals("Apple", cellText(f, "A5"))
        assertEquals("3", cellText(f, "B5"))
        assertEquals(formulaTableAfterPaste(), f.h.managerContent())
    }

    private fun formulaTableAfterPaste() =
        "| Item | Qty | Dbl |\n|---|---|---|\n| Apple | 3 | =B2*2 |\n| Pear | 5 | =B3*2 |\n| a | b | c |\n| Apple | 3 | =B2*2 |"

    @Test
    fun pasteOfDifferentText_should_BeLiteral_NotTheSlotsFormulas() {
        val f = open(formulas)
        select(f, "A2", "C2")
        press(Key.C, ctrl = true)
        f.clipboard.text = "Apple\t3\t7"
        select(f, "A5")
        press(Key.V, ctrl = true)
        assertEquals("7", cellText(f, "C5"))
    }

    @Test
    fun paste_should_GrowAThreeByThreeTable_Announce_AndOneUndoRestoresTheExactString() {
        val original = "| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |"
        val f = open(original)
        f.clipboard.text = (1..8).joinToString("\r\n") { r -> (1..5).joinToString("\t") { c -> "r${r}c$c" } }
        select(f, "B3")
        press(Key.V, ctrl = true)
        val doc = parsedContent(f)
        assertEquals(10, doc.rowCount)
        assertEquals(6, doc.columnCount)
        assertEquals("Pasted 8 x 5, added 7 rows, 3 columns", f.s.announcement)
        assertEquals("Pasted 8 x 5, added 7 rows, 3 columns", noticeText())
        assertEquals(GridSelection(addr("B3"), CellAddress(10, 6)), f.s.selection, "the selection becomes the pasted range")
        f.h.session!!.undo()
        rule.waitForIdle()
        assertEquals(original, f.h.managerContent(), "one Ctrl+Z restores the exact original string")
    }

    @Test
    fun pasteNotice_should_CarryUndo_AndUndoRestoresTheTable() {
        val original = "| A | B |\n|---|---|\n| 1 | 2 |"
        val f = open(original)
        f.clipboard.text = "x\ty\nz\tw"
        select(f, "A2")
        press(Key.V, ctrl = true)
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertEquals(original, f.h.managerContent())
    }

    @Test
    fun pasteOfPipesAndNewlines_should_SayHowManyCellsChanged() {
        val f = open("| A | B |\n|---|---|\n| 1 | 2 |")
        f.clipboard.text = "\"a\nb\"\tc|d"
        select(f, "A2")
        press(Key.V, ctrl = true)
        val text = noticeText().orEmpty()
        assertTrue(text.contains("2 cells changed"), text)
        assertTrue(text.contains("Line break stored as <br>"), text)
        assertEquals("a\nb", cellText(f, "A2"))
        assertEquals("c|d", cellText(f, "B2"))
    }

    @Test
    fun singleCellPaste_should_FillTheWholeSelection() {
        val f = open("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n| 5 | 6 |")
        f.clipboard.text = "0"
        select(f, "B2", "B4")
        press(Key.V, ctrl = true)
        assertEquals(listOf("0", "0", "0"), listOf("B2", "B3", "B4").map { cellText(f, it) })
    }

    @Test
    fun cut_should_PutTsvOnTheClipboardAndClearInOneUndoStep() {
        val original = "| A | B |\n|---|---|\n| Apple | 1 |\n| Pear | 2 |"
        val f = open(original)
        select(f, "A2", "A3")
        val depth = f.h.session!!.history.value.undoDepth
        press(Key.X, ctrl = true)
        assertEquals("Apple\r\nPear", f.clipboard.text)
        assertEquals(listOf("", ""), listOf(cellText(f, "A2"), cellText(f, "A3")))
        assertEquals(depth + 1, f.h.session!!.history.value.undoDepth, "cut is one undo step")
        f.h.session!!.undo()
        rule.waitForIdle()
        assertEquals(original, f.h.managerContent())
    }

    @Test
    fun clipboardWriteFailure_should_OfferShowAsTextWithTheTsvSelected_AndCutMustNotClear() {
        val f = open("| A | B |\n|---|---|\n| Apple | 1 |\n| Pear | 2 |")
        f.clipboard.failCopy = true
        select(f, "A2", "A3")
        press(Key.X, ctrl = true)
        assertEquals("Couldn't copy. Select the text and copy manually", noticeText())
        assertEquals("Apple", cellText(f, "A2"), "a failed cut keeps the cells")
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertTrue(rule.exists("showAsTextDialog"))
        assertEquals("Apple\r\nPear", rule.editableTextOf("showAsText"))
    }

    @Test
    fun pasteOverTheCap_should_BeRefusedWithNothingChanged() {
        val original = "| A | B |\n|---|---|\n| 1 | 2 |"
        val f = open(original)
        f.clipboard.text = (1..101).joinToString("\n") { (1..100).joinToString("\t") { "x" } }
        select(f, "A2")
        press(Key.V, ctrl = true)
        rule.waitUntil(10_000) { noticeText() != null }
        assertEquals("Paste is too large (limit 10,000 cells). Nothing changed.", noticeText())
        assertEquals(original, f.h.managerContent())
    }

    @Test
    fun largePaste_should_ParseOffTheMainDispatcherAndStillBeOneUndoStep() {
        val original = "| A | B |\n|---|---|\n| 1 | 2 |"
        val f = open(original)
        f.clipboard.text = (1..60).joinToString("\n") { r -> (1..50).joinToString("\t") { "$r" } }
        select(f, "A2")
        val depth = f.h.session!!.history.value.undoDepth
        press(Key.V, ctrl = true)
        rule.waitUntil(10_000) { f.s.rowCount == 61 }
        rule.waitForIdle()
        assertFalse(f.s.applying, "the applying flag clears once the paste is in")
        assertEquals(depth + 1, f.h.session!!.history.value.undoDepth)
    }

    @Test
    fun unreadableClipboard_should_OpenThePasteHereBox_AndInsertPastesIt() {
        val original = "| A | B |\n|---|---|\n| 1 | 2 |"
        val f = open(original)
        f.clipboard.failPaste = true
        select(f, "A2")
        press(Key.V, ctrl = true)
        assertTrue(rule.exists("pasteHereDialog"))
        rule.onNodeWithTag("pasteHereField").performTextInput("p\tq")
        rule.onNodeWithTag("pasteHereInsert").performClick()
        rule.waitForIdle()
        assertEquals(listOf("p", "q"), listOf(cellText(f, "A2"), cellText(f, "B2")))
        assertFalse(rule.exists("pasteHereDialog"))
    }

    @Test
    fun emptyClipboard_should_SayNothingToPasteWithAPasteHereAction_AndCancelChangesNothing() {
        val original = "| A | B |\n|---|---|\n| 1 | 2 |"
        val f = open(original)
        f.clipboard.text = null
        press(Key.V, ctrl = true)
        assertEquals("Nothing to paste", noticeText())
        rule.onNodeWithTag("noticeAction").performClick()
        rule.waitForIdle()
        assertTrue(rule.exists("pasteHereDialog"))
        rule.onNodeWithTag("pasteHereCancel").performClick()
        rule.waitForIdle()
        assertFalse(rule.exists("pasteHereDialog"))
        assertEquals(original, f.h.managerContent())
    }

    @Test
    fun readOnly_should_RefusePaste() {
        val original = "| A | B |\n|---|---|\n| 1 | 2 |"
        val f = open(original, readOnly = true)
        f.clipboard.text = "9"
        select(f, "A2")
        press(Key.V, ctrl = true)
        assertEquals(original, f.h.managerContent())
        assertEquals(TableMessages.READ_ONLY, f.s.announcement)
    }

    @Test
    fun copyAsFormulas_should_PutRawCellTextOnTheClipboardFromTheOverflowMenu() {
        val f = open(formulas)
        select(f, "A2", "C2")
        rule.onNodeWithTag("sheetOverflow").performClick()
        rule.onNodeWithTag("menuCopyAsFormulas").performClick()
        rule.waitForIdle()
        assertEquals("Apple\t3\t=B2*2", f.clipboard.text)
    }

    // ---- range gestures -------------------------------------------------------------------------------------------

    private fun center(tag: String): Offset = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    /** [tag]'s centre in the grid focus box's own coordinates, which is where mouse input is injected. */
    private fun local(tag: String): Offset = center(tag) - rule.onNodeWithTag("sheetGridFocus").fetchSemanticsNode().boundsInRoot.topLeft

    @Test
    fun longPress_should_EnterRangeMode_AndDraggingTheEndHandleSelectsAFourByTwoRange() {
        val f = open(gridContent(rows = 5, cols = 3))
        rule.onNodeWithTag("cell-A2").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(f.s.rangeMode)
        assertEquals(addr("A2"), f.s.selection.anchor)
        rule.onNodeWithTag("handleEnd").assertWidthIsEqualTo(48.dp)
        rule.onNodeWithTag("handleStart").assertWidthIsEqualTo(48.dp)
        val delta = center("cell-B4") - center("handleEnd")
        rule.onNodeWithTag("handleEnd").performTouchInput {
            down(this.center)
            moveBy(delta)
            up()
        }
        rule.waitForIdle()
        assertEquals(GridSelection(addr("A2"), addr("B4")), f.s.selection)
        assertTrue(f.s.rangeMode, "handles stay while the range is adjusted")
    }

    @Test
    fun draggingTheStartHandle_should_KeepTheOppositeCornerFixed() {
        val f = open(gridContent(rows = 5, cols = 3))
        select(f, "A2", "B4")
        ui { f.s.enterRangeMode(addr("B3")) }
        ui { f.s.dragHandle(addr("B3"), addr("B4")) }
        val delta = center("cell-A3") - center("handleStart")
        rule.onNodeWithTag("handleStart").performTouchInput {
            down(this.center)
            moveBy(delta)
            up()
        }
        rule.waitForIdle()
        assertEquals(addr("B4"), f.s.selection.anchor, "the bottom-right corner stays")
        assertEquals(addr("A3"), f.s.selection.focus)
    }

    @Test
    fun touchDragOnTheGrid_should_NeverSelectARange() {
        val f = open(gridContent(rows = 5, cols = 3))
        rule.onNodeWithTag("cell-A2").performTouchInput { down(center); moveBy(Offset(0f, 200f)); up() }
        rule.waitForIdle()
        assertTrue(f.s.selection.isSingleCell)
        assertFalse(f.s.rangeMode)
    }

    @Test
    fun mouseDrag_should_SelectARange_AndShiftClickExtendsIt() {
        val f = open(gridContent(rows = 5, cols = 3))
        rule.onNodeWithTag("sheetGridFocus").performMouseInput {
            moveTo(local("cell-A2"))
            press()
            moveTo((local("cell-A2") + local("cell-B3")) / 2f)
            moveTo(local("cell-B3"))
            release()
        }
        rule.waitForIdle()
        assertEquals(GridSelection(addr("A2"), addr("B3")), f.s.selection)
        ui { f.s.selectRange(GridSelection.single(addr("A2"), f.s.bounds)) }
        rule.onNodeWithTag("sheetGridFocus").performKeyInput { keyDown(Key.ShiftLeft) }
        rule.onNodeWithTag("sheetGridFocus").performMouseInput { click(local("cell-B4")) }
        rule.onNodeWithTag("sheetGridFocus").performKeyInput { keyUp(Key.ShiftLeft) }
        rule.waitForIdle()
        assertEquals(GridSelection(addr("A2"), addr("B4")), f.s.selection)
    }

    @Test
    fun headerTaps_should_SelectTheWholeColumnAndRow() {
        val f = open(gridContent(rows = 3, cols = 3))
        rule.onNodeWithTag("letter-B").performClick()
        assertEquals(GridSelection.Kind.Columns, f.s.selection.kind)
        assertEquals(addr("B1"), f.s.selection.range.topLeft)
        ui { f.s.closeMenu() }
        rule.onNodeWithTag("gutter-3").performClick()
        assertEquals(GridSelection.Kind.Rows, f.s.selection.kind)
        assertEquals(3, f.s.selection.range.topLeft.row)
    }

    @Test
    fun extendPicker_should_GrowTheRangeWithoutADrag() {
        val f = open(gridContent(rows = 5, cols = 3))
        select(f, "A2")
        tap("strip:Extend")
        tap("extend:Down")
        tap("strip:Extend")
        tap("extend:Right")
        assertEquals(GridSelection(addr("A2"), addr("B3")), f.s.selection)
        tap("strip:Extend")
        tap("extend:TableEnd")
        assertEquals(addr("C6"), f.s.selection.focus)
    }

    @Test
    fun stripCopyCutClearPaste_should_ActOnTheSelectionLikeTheKeys() {
        val f = open("| A | B |\n|---|---|\n| x | 1 |\n| y | 2 |")
        select(f, "A2", "A3")
        tap("strip:Copy")
        assertEquals("x\r\ny", f.clipboard.text)
        tap("strip:Clear")
        assertEquals(listOf("", ""), listOf(cellText(f, "A2"), cellText(f, "A3")))
        select(f, "B2")
        tap("strip:Paste")
        assertEquals("x", cellText(f, "B2"))
    }

    // ---- status line ----------------------------------------------------------------------------------------------

    @Test
    fun statusLine_should_ShowCountSumAverageOfTheNumbers_AndHideWhenThereAreNone() {
        val f = open("| A | B | C |\n|---|---|---|\n| 3 | 5 | x |\n| n | m | z |")
        select(f, "A2", "C2")
        settleStats()
        assertEquals("Count 2  Sum 8  Average 4", rule.textOf("statusLine"))
        select(f, "A3", "C3")
        settleStats()
        assertNull(rule.textOf("statusLine"), "no numbers, no stats")
    }

    @Test
    fun statusLine_should_AppendTheErrorCount() {
        val f = open("| A | B | C |\n|---|---|---|\n| 3 | 5 | =1/0 |")
        select(f, "A2", "C2")
        settleStats()
        assertEquals("Count 2  Sum 8  Average 4  1 error", rule.textOf("statusLine"))
    }

    @Test
    fun statusLine_should_RecomputeAtMostOncePer500msDuringADrag() {
        val f = open("| A | B | C |\n|---|---|---|\n| 1 | 2 | 4 |")
        rule.mainClock.autoAdvance = false
        ui2 { f.s.selectRange(GridSelection(addr("A2"), addr("A2"))) }
        rule.mainClock.advanceTimeBy(16)
        assertEquals("Count 1  Sum 1  Average 1", rule.textOf("statusLine"), "the first value is immediate")
        ui2 { f.s.selectRange(GridSelection(addr("A2"), addr("B2"))) }
        rule.mainClock.advanceTimeBy(200)
        assertEquals("Count 1  Sum 1  Average 1", rule.textOf("statusLine"), "a change inside the window waits")
        ui2 { f.s.selectRange(GridSelection(addr("A2"), addr("C2"))) }
        rule.mainClock.advanceTimeBy(200)
        assertEquals("Count 1  Sum 1  Average 1", rule.textOf("statusLine"))
        rule.mainClock.advanceTimeBy(300)
        assertEquals("Count 3  Sum 7  Average 2.333333", rule.textOf("statusLine"), "the latest selection shows once the window ends")
        rule.mainClock.autoAdvance = true
    }

    private fun ui2(block: () -> Unit) {
        rule.runOnUiThread(block)
    }

    @Test
    fun statusLine_should_BeAPoliteLiveRegion() {
        val f = open("| A | B |\n|---|---|\n| 3 | 5 |")
        select(f, "A2", "B2")
        settleStats()
        val config = rule.onNodeWithTag("statusLine").fetchSemanticsNode().config
        assertNotNull(config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion))
    }
}
