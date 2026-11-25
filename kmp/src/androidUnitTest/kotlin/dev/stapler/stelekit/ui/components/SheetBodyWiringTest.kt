// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.table.CellAddress
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sheet body against the real session, committer and `BlockStateManager` (no fake hooks): edit, undo, redo, external edit. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetBodyWiringTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val f = SheetFixture(rule, "| Item | Qty |\n|---|---|\n| Apple | 3 |\n| Pear | 5 |")

    @After
    fun tearDown() = f.close()

    private fun ctrl(key: Key, shift: Boolean = false) {
        rule.onNodeWithTag("sheetGridFocus").performKeyInput {
            keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            pressKey(key)
            if (shift) keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private fun editQty(text: String) {
        rule.onNodeWithTag("cell-B2").performClick()
        rule.onNodeWithTag("cell-B2").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("barField").performTextReplacement(text)
        rule.onNodeWithTag("barDone").performClick()
        rule.waitForIdle()
    }

    @Test
    fun editingACell_should_WriteTheBlock_And_UndoRedoRestoreTheGrid() {
        f.show()
        editQty("9")
        assertEquals("| Item | Qty |\n|---|---|\n| Apple | 9 |\n| Pear | 5 |", f.h.managerContent())
        assertEquals("9", f.s.doc!!.cell(CellAddress(2, 2))!!.value)
        ctrl(Key.Z)
        assertEquals(f.h.c0, f.h.managerContent())
        rule.waitUntil(5_000) { f.s.doc!!.cell(CellAddress(2, 2))!!.value == "3" }
        assertEquals("3", f.s.doc!!.cell(CellAddress(2, 2))!!.value, "the grid followed the undo")
        ctrl(Key.Y)
        rule.waitUntil(5_000) { f.s.doc!!.cell(CellAddress(2, 2))!!.value == "9" }
        assertTrue(f.h.managerContent().contains("| Apple | 9 |"))
        assertTrue(f.h.closes.isEmpty())
    }

    @Test
    fun anExternalEdit_should_ReparseTheGridKeepingTheSelection() {
        f.show()
        rule.onNodeWithTag("cell-A3").performClick()
        rule.waitForIdle()
        f.h.foreignWrite(withCell(f.h.c0, CellAddress(3, 2), "50"))
        rule.waitUntil(5_000) { f.s.doc!!.cell(CellAddress(3, 2))!!.value == "50" }
        assertEquals(CellAddress(3, 1), f.s.selection.anchor)
        assertEquals("Updated from disk", rule.textOf("noticeText"))
    }

    @Test
    fun closing_should_CommitAPendingDraftThroughTheRealHooks() {
        f.show()
        rule.onNodeWithTag("cell-B2").performClick()
        rule.onNodeWithTag("cell-B2").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("barField").performTextReplacement("77")
        rule.onNodeWithTag("sheetClose").performClick()
        rule.waitUntil(5_000) { f.h.flushes == 1 }
        assertTrue(f.h.managerContent().contains("| Apple | 77 |"), f.h.managerContent())
    }
}
