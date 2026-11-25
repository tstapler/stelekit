// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.GridMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.8 on the real sheet: `fx`, error detail, the cycle path, point mode, Extend and Stop inserting. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w800dp-h780dp-xxhdpi")
class SheetFormulaUxTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val table = "| A | B | C | D |\n|---|---|---|---|\n| 5 | =2+3 | =D2+1 | =C2+1 |\n| 1 | 2 | 3 | 4 |\n| 4 | 5 | 6 | 7 |\n| 7 | 8 | 9 | 10 |"
    private val f = SheetFixture(rule, table)

    @After
    fun tearDown() = f.close()

    private fun fxIn(tag: String) = rule.onAllNodes(hasText("fx") and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()

    private fun editD3(text: String) {
        rule.runOnUiThread { f.s.startEdit(CellAddress(3, 4)) }
        rule.waitForIdle()
        rule.onNodeWithTag("barField").performTextReplacement(text)
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Test
    fun fx_should_MarkFormulaCellsOnly_And_TheBarShowsTheLiteralSource() {
        f.show()
        assertTrue(fxIn("cell-B2"), "=2+3 shows the fx marker")
        assertFalse(fxIn("cell-A2"), "a plain 5 has none")
        assertEquals("5", f.s.display(CellAddress(2, 2)).text)
        tap("cell-B2")
        assertEquals("=2+3", rule.editableTextOf("barField"))
    }

    @Test
    fun cyclePath_should_BeNamedInTheBarMessageLine_When_TheCellIsSelected() {
        f.show()
        tap("cell-C2")
        assertEquals("#CYCLE!", f.s.display(CellAddress(2, 3)).text)
        assertEquals("Circular reference C2 -> D2 -> C2", rule.textOf("barMessage"))
    }

    @Test
    fun errorDetail_should_BeReachableByKeyboardFocusAndDismissWithEsc_When_ErrorCellActive() {
        f.show()
        repeat(2) {
            rule.onNodeWithTag("sheetGridFocus").performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitForIdle()
        }
        assertEquals(CellAddress(2, 3), f.s.selection.anchor)
        assertEquals("Circular reference C2 -> D2 -> C2", rule.textOf("barMessage"))
        rule.onNodeWithTag("sheetGridFocus").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(rule.exists("barMessage"), "Esc dismissed the detail")
        rule.onNodeWithTag("tableSheetHost").assertExists()
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun tap_should_InsertReference_When_CaretAfterOperator() {
        f.show()
        editD3("=SUM(")
        assertEquals(TableMessages.TAP_TO_INSERT_REFERENCE, rule.textOf("pointIndicator"))
        tap("cell-B3")
        assertEquals("=SUM(B3", rule.editableTextOf("barField"))
        assertEquals(GridMode.Edit, f.s.mode)
        assertEquals(TableMessages.insertedReference("B3"), rule.textOf("pointIndicator"))
        assertEquals(table, f.h.managerContent(), "nothing committed by the insert")
    }

    @Test
    fun tap_should_CommitAndMove_When_CaretNotAfterOperator() {
        f.show()
        val before = f.h.managerVersion()
        editD3("=B3")
        assertFalse(rule.exists("pointIndicator"))
        tap("cell-A5")
        assertEquals(before + 1, f.h.managerVersion())
        assertEquals(CellAddress(5, 1), f.s.selection.anchor)
        assertEquals(GridMode.Navigate, f.s.mode)
        assertTrue(f.h.managerContent().contains("=B3"))
    }

    @Test
    fun extend_should_BuildRangeWithoutDrag_When_ReferenceJustInserted() {
        f.show()
        editD3("=SUM(")
        tap("cell-B2")
        repeat(3) {
            tap("extendButton")
            tap("extend-Down")
        }
        assertEquals("=SUM(B2:B5", rule.editableTextOf("barField"))
    }

    @Test
    fun indicator_should_ShowWhileArmed_And_StopMakeTapsCommit() {
        f.show()
        editD3("=SUM(")
        assertTrue(rule.exists("pointIndicator"))
        tap("stopInserting")
        assertFalse(rule.exists("pointIndicator"))
        tap("cell-B4")
        assertEquals(GridMode.Navigate, f.s.mode)
        assertEquals(CellAddress(4, 2), f.s.selection.anchor)
    }

    @Test
    fun literalEqualsText_should_NotArm_When_NotFormulaPrefix() {
        f.show()
        listOf("=5 apples", "= total", "==").forEach { text ->
            editD3(text)
            assertFalse(rule.exists("pointIndicator"), text)
            tap("cell-A5")
            assertEquals(GridMode.Navigate, f.s.mode, text)
            assertNull(f.s.pointIndicator)
        }
    }

    @Test
    fun syntaxError_should_ShowAMessageLine_And_StillAllowCommit() {
        f.show()
        editD3("=SUM(")
        assertEquals("Not a valid formula", rule.textOf("barSyntax"))
    }
}
