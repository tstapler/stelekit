package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellDraftTest {
    private val b3 = CellAddress(3, 2)
    private fun sel(n: Int) = TextSelection(n)

    @Test
    fun draftHistory_should_RevertTypedStepsWithoutCommit_When_Undo() {
        var d = CellDraft.start(b3, "3")
        d = d.edit("4", sel(1), composing = false, atMs = 0)
        d = d.edit("45", sel(2), composing = false, atMs = 2_000)
        d = d.undo()
        assertEquals("4", d.text)
        assertEquals("3", d.committedContent)
        d = d.redo()
        assertEquals("45", d.text)
        assertEquals("3", d.committedContent)
        assertEquals("3", d.undo().undo().text)
    }

    @Test
    fun draftHistory_should_CoalesceKeystrokesWithinWordAndPause() {
        var d = CellDraft.start(b3, "")
        d = d.edit("h", sel(1), false, 0).edit("he", sel(2), false, 100).edit("hel", sel(3), false, 200)
        assertEquals(1, d.undoDepth)
        d = d.edit("hel ", sel(4), false, 300).edit("hel w", sel(5), false, 400)
        assertEquals(2, d.undoDepth, "a new word starts a new step")
        assertEquals("hel ", d.undo().text)
    }

    @Test
    fun draftHistory_should_ClearOnCommitAndCancel() {
        var d = CellDraft.start(b3, "3").edit("4", sel(1), false, 0)
        assertTrue(d.canUndo)
        d = d.clearHistory()
        assertFalse(d.canUndo)
        assertFalse(d.canRedo)
        assertEquals("4", d.text)
        assertEquals(d, d.undo())
    }

    @Test
    fun draftHistory_should_RecordNoStep_When_Composing() {
        var d = CellDraft.start(b3, "")
        d = d.edit("k", sel(1), composing = true, atMs = 0).edit("ka", sel(2), composing = true, atMs = 50)
        assertFalse(d.canUndo)
        assertTrue(d.composing)
        d = d.edit("か", sel(1), composing = false, atMs = 100)
        assertEquals(1, d.undoDepth)
        assertEquals("", d.undo().text)
    }

    @Test
    fun draftHistory_should_ClearRedo_When_NewEditAfterUndo() {
        var d = CellDraft.start(b3, "a").edit("ab", sel(2), false, 0).undo()
        assertTrue(d.canRedo)
        d = d.edit("ax", sel(2), false, 10)
        assertFalse(d.canRedo)
    }

    @Test
    fun draftHistory_should_CapAtOneHundred() {
        var d = CellDraft.start(b3, "")
        for (i in 1..150) d = d.edit("x".repeat(i), sel(i), false, i * 2_000L)
        assertEquals(CellDraft.HISTORY_CAP, d.undoDepth)
    }

    @Test
    fun draft_should_SurviveRecycle_When_HoistedInSheetState() {
        val d = CellDraft.start(b3, "3").edit("42", TextSelection(1, 2), false, 0)
        val hoisted = mapOf(d.address to d)
        val back = hoisted.getValue(b3)
        assertEquals("42", back.text)
        assertEquals(TextSelection(1, 2), back.selection)
        assertTrue(back.isDirty)
    }
}
