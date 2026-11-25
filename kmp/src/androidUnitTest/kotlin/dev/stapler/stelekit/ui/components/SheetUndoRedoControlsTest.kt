// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.TableEdit
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

/** Story 5.1e over the real block manager: the top-bar and strip Undo and Redo are the depth-gated committer's, labelled and never silent. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w400dp-h780dp-xxhdpi")
class SheetUndoRedoControlsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var fixture: SheetFixture? = null
    private val c0 = "| Item | Qty |\n|---|---|\n| Apple | 3 |\n| Pear | 5 |"

    @After
    fun tearDown() {
        fixture?.close()
    }

    private fun open(beforeShow: (SheetFixture) -> Unit = {}): SheetFixture =
        SheetFixture(rule, c0).also {
            fixture = it
            beforeShow(it)
            it.show(realCommands = true)
        }

    private fun edit(f: SheetFixture, cell: CellAddress, text: String) {
        rule.runOnUiThread { f.s.applyEdit(TableEdit.SetCell(cell, CellText(text)), "edit $cell") }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        val node = rule.onNodeWithTag(tag)
        (if (tag.startsWith("strip:")) node.performScrollTo() else node).performClick()
        rule.waitForIdle()
    }

    private fun dimmedNote(tag: String): String? =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun atDepthZero_should_BeDimmedButFocusable_AndExplainThroughANotice() {
        open()
        assertEquals("Undo", rule.textOf("sheetUndo"))
        assertEquals("Redo", rule.textOf("sheetRedo"))
        assertEquals("Nothing more to undo in this table", dimmedNote("sheetUndo"))
        tap("sheetUndo")
        assertEquals("Nothing more to undo in this table", rule.textOf("noticeText"))
        tap("sheetRedo")
        assertEquals("Nothing more to redo in this table", rule.textOf("noticeText"))
    }

    @Test
    fun undoLabel_should_NameTheStep_AndUndoRedoRoundTripAtDepthTwo() {
        val f = open()
        edit(f, CellAddress(2, 2), "7")
        edit(f, CellAddress(3, 2), "8")
        assertEquals("Undo: edit B3", rule.textOf("sheetUndo"))
        assertNull(dimmedNote("sheetUndo"), "no longer dimmed")
        val afterBoth = f.h.managerContent()
        tap("sheetUndo")
        assertEquals("Undone", f.h.session!!.announcement)
        assertEquals("Undo: edit B2", rule.textOf("sheetUndo"))
        assertEquals("Redo: edit B3", rule.textOf("sheetRedo"))
        tap("sheetUndo")
        assertEquals(c0, f.h.managerContent())
        tap("sheetUndo")
        assertEquals("Nothing more to undo in this table", rule.textOf("noticeText"))
        tap("sheetRedo")
        assertEquals("Redone", f.h.session!!.announcement)
        tap("sheetRedo")
        assertEquals(afterBoth, f.h.managerContent())
    }

    @Test
    fun aForeignUndoEntry_should_NeverBeUndoneByTheControls() {
        var foreignUndone = false
        val f = open { it.h.manager.record(undo = { foreignUndone = true }, redo = null) }
        tap("sheetUndo")
        assertFalse(foreignUndone, "the page's own entry is not the sheet's to undo")
        assertEquals("Nothing more to undo in this table", rule.textOf("noticeText"))
        assertEquals(c0, f.h.managerContent())
    }

    @Test
    fun theStripControls_should_MatchTheTopBar() {
        val f = open()
        edit(f, CellAddress(2, 2), "9")
        assertEquals("Undo: edit B2", rule.textOf("strip:Undo"))
        tap("strip:Undo")
        assertEquals(c0, f.h.managerContent())
        assertEquals("Redo: edit B2", rule.textOf("strip:Redo"))
        tap("strip:Redo")
        assertEquals("9", f.s.doc?.cell(CellAddress(2, 2))?.value)
    }

    @Test
    fun inEdit_should_BothControlsActOnTheFieldAndNotTheTable() {
        val f = open()
        edit(f, CellAddress(2, 2), "9")
        rule.runOnUiThread {
            f.s.startEdit(CellAddress(3, 2))
            f.s.onFieldChange(androidx.compose.ui.text.input.TextFieldValue("55", androidx.compose.ui.text.TextRange(2)))
        }
        rule.waitForIdle()
        assertEquals("Undo", rule.textOf("sheetUndo"), "field-scoped, so no table step is named")
        tap("sheetUndo")
        assertEquals("5", f.s.fieldValue.text, "the field's own undo ran")
        assertEquals("9", f.s.doc?.cell(CellAddress(2, 2))?.value, "the table's last step was not undone")
        assertTrue(f.h.session!!.history.value.undoDepth == 1)
    }

    @Test
    @Config(sdk = [29], qualifiers = "w320dp-h780dp-xxhdpi")
    fun atNarrowWidth_should_TheControlsBecomeTheFirstTwoOverflowItems() {
        val f = open()
        edit(f, CellAddress(2, 2), "9")
        assertFalse(rule.exists("sheetUndo"))
        assertFalse(rule.exists("sheetRedo"))
        tap("sheetOverflow")
        assertEquals("Undo: edit B2", rule.textOf("menuUndo"))
        assertEquals("Redo", rule.textOf("menuRedo"))
        assertEquals("Nothing more to redo in this table", rule.onNodeWithTag("menuRedo").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        tap("menuUndo")
        assertEquals(c0, f.h.managerContent())
    }
}
