// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.GridSelection
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.ui.components.sheet.DraftText
import dev.stapler.stelekit.ui.components.sheet.SheetGrid
import dev.stapler.stelekit.ui.components.sheet.SheetHostPort
import dev.stapler.stelekit.ui.components.sheet.SheetState
import dev.stapler.stelekit.ui.components.sheet.parseSheet
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
import kotlin.test.assertTrue

/** Story 5.2 on a phone-sized (360 x 780dp) real sheet: lazy rows, sticky header and gutter, draft hoisting, keys, Saver. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetGridTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixture: SheetFixture? = null

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(rows: Int = 200, cols: Int = 12): SheetFixture =
        SheetFixture(rule, gridContent(rows, cols)).also {
            fixture = it
            it.show()
        }

    private fun composedRows(): List<Int> =
        rule.onAllNodes(tagStartsWith("sheetRow-")).fetchSemanticsNodes().map {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("sheetRow-").toInt()
        }

    private fun visibleRows(): Int {
        val grid = rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().boundsInRoot
        return rule.onAllNodes(tagStartsWith("sheetRow-")).fetchSemanticsNodes().count {
            it.boundsInRoot.top < grid.bottom && it.boundsInRoot.bottom > grid.top
        }
    }

    private fun scrollRowsTo(index: Int) {
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        rule.waitForIdle()
    }

    @Test
    fun lazyRows_should_ComposeAtMostVisiblePlusTwo_When_200x12() {
        open()
        val composed = composedRows().size
        val visible = visibleRows()
        assertTrue(visible in 5..30, "visible rows $visible")
        assertTrue(composed <= visible + 2, "composed $composed rows for $visible visible")
        assertFalse(composedRows().contains(150))
    }

    @Test
    fun sticky_should_KeepLettersAndGutterAlignedWithCells_When_ScrolledDownAndRight() {
        val f = open()
        scrollRowsTo(100)
        rule.onNodeWithTag("sheetGrid").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        rule.waitForIdle()
        assertTrue(f.s.horizontalScroll > 0, "the grid scrolled right")
        val gridLeft = rule.onNodeWithTag("sheetGrid").fetchSemanticsNode().boundsInRoot.left
        val row = composedRows().filter { it >= 100 }.min()
        assertTrue(row >= 100, "scrolled to row $row")
        rule.onNodeWithTag("sheetLetters").assertExists()
        assertEquals(gridLeft, rule.onNodeWithTag("gutter-$row").fetchSemanticsNode().boundsInRoot.left, "the gutter stays at the left edge")
        val horizontal = rule.onNodeWithTag("sheetRow-$row").fetchSemanticsNode().positionInRoot.x
        assertTrue(horizontal < gridLeft, "the row content moved left while the gutter stayed")
        val visibleCol = (1..12).first { rule.exists("cell-${CellAddress.columnLabel(it)}$row") && rule.onNodeWithTag("cell-${CellAddress.columnLabel(it)}$row").fetchSemanticsNode().boundsInRoot.left >= gridLeft + 1f }
        val label = CellAddress.columnLabel(visibleCol)
        assertEquals(
            rule.onNodeWithTag("letter-$label").fetchSemanticsNode().boundsInRoot.left,
            rule.onNodeWithTag("cell-$label$row").fetchSemanticsNode().boundsInRoot.left,
            "column letter $label aligns with its cell",
        )
        rule.onNodeWithTag("sheetRow-1").assertExists()
    }

    @Test
    fun tapOnTheStickyGutter_should_SelectTheRowAndNeverTheCellScrolledUnderIt() {
        val f = open(rows = 20, cols = 12)
        rule.onNodeWithTag("sheetGrid").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
        rule.waitForIdle()
        assertTrue(f.s.horizontalScroll > 0)
        rule.onNodeWithTag("gutter-4").performClick()
        rule.waitForIdle()
        assertEquals(GridSelection.Kind.Rows, f.s.selection.kind)
        assertEquals(4, f.s.selection.anchor.row)
        assertEquals(12, f.s.selection.cellCount / f.s.selection.range.rows)
    }

    @Test
    fun draft_should_SurviveRecycleWithTextAndSelection_And_CommitNothing() {
        val f = open()
        val versionBefore = f.h.managerVersion()
        rule.runOnUiThread {
            f.s.startEdit(CellAddress(150, 2))
            f.s.onFieldChange(TextFieldValue("abc", TextRange(1, 2)))
        }
        rule.waitForIdle()
        assertTrue(rule.exists("cell-B150"), "scrolled into view for editing")
        scrollRowsTo(0)
        assertFalse(rule.exists("cell-B150"), "row 150 left composition")
        assertEquals("abc", f.s.fieldValue.text)
        assertEquals(TextRange(1, 2), f.s.fieldValue.selection)
        assertEquals(GridMode.Edit, f.s.mode)
        scrollRowsTo(147)
        assertEquals("abc", rule.textOf("cellDraft"))
        assertEquals(versionBefore, f.h.managerVersion(), "nothing was written")
        assertEquals(f.h.c0, f.h.managerContent())
    }

    @Test
    fun rowKey_should_KeepTheEditingFieldOnItsRow_When_RowInsertedAbove() {
        val f = open(rows = 20, cols = 3)
        rule.runOnUiThread {
            f.s.startEdit(CellAddress(5, 2))
            f.s.onFieldChange(TextFieldValue("zz", TextRange(2)))
        }
        rule.waitForIdle()
        rule.runOnUiThread { f.s.applyEdit(TableEdit.InsertRows(before = 2), "insert row") }
        rule.waitForIdle()
        assertEquals(CellAddress(6, 2), f.s.editingAddress)
        val drafts = rule.onAllNodes(hasTestTag("cellDraft")).fetchSemanticsNodes()
        assertEquals(1, drafts.size, "exactly one row shows the draft")
        rule.onNode(hasTestTag("cellDraft") and hasAnyAncestor(hasTestTag("sheetRow-6"))).assertExists()
        assertEquals("zz", f.s.fieldValue.text)
    }

    private class NullHost : SheetHostPort {
        override val readOnly = false
        override fun commit(text: String, label: String, draft: DraftText?) = true
        override fun undo() = Unit
        override fun redo() = Unit
        override fun closeSheet() = Unit
        override fun editAsMarkdown() = Unit
    }

    @Test
    fun state_should_SurviveConfigChange_When_RecreateActivity() {
        val parsed = parseSheet(gridContent(30, 4))
        val host = NullHost()
        val tester = StateRestorationTester(rule)
        var captured: SheetState? = null
        tester.setContent {
            MaterialTheme {
                val st = rememberSaveable(saver = SheetState.saver(parsed, host)) { SheetState(parsed, host) }
                captured = st
                SheetGrid(st)
            }
        }
        rule.runOnUiThread {
            captured!!.startEdit(CellAddress(7, 3))
            captured!!.onFieldChange(TextFieldValue("abc", TextRange(1, 2)))
            captured!!.updateView(captured!!.view.setWidth(2, 200f))
        }
        rule.waitForIdle()
        val before = captured
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        val after = assertNotNull(captured)
        assertTrue(after !== before, "a new state object was created")
        assertEquals(GridMode.Edit, after.mode)
        assertEquals("abc", after.fieldValue.text)
        assertEquals(TextRange(1, 2), after.fieldValue.selection)
        assertEquals(CellAddress(7, 3), after.editingAddress)
        assertEquals(200f, after.view.widthOf(2))
        assertEquals("abc", rule.textOf("cellDraft"))
    }
}
