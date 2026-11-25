package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableUndoScopeTest {
    @Test
    fun scope_should_CountOnlyChangedCommitsAndClearRedo_When_Recorded() {
        val s = TableUndoScope()
        s.record("edit B3", changed = true)
        s.record("paste", changed = true)
        s.record("noop", changed = false)
        assertEquals(2, s.undoDepth)
        assertEquals("paste", s.nextUndoLabel)

        assertEquals("paste", s.undo())
        assertEquals(1, s.undoDepth)
        assertEquals(1, s.redoDepth)
        assertEquals("paste", s.nextRedoLabel)

        s.record("sort", changed = true)
        assertEquals(0, s.redoDepth)
        assertNull(s.nextRedoLabel)
    }

    @Test
    fun scope_should_ResetBothDepths_When_ResetOrForeignEntry() {
        for (clear in listOf<(TableUndoScope) -> Unit>({ it.reset() }, { it.foreignEntry() })) {
            val s = TableUndoScope()
            s.record("a", true)
            s.record("b", true)
            s.undo()
            clear(s)
            assertEquals(0, s.undoDepth)
            assertEquals(0, s.redoDepth)
            assertFalse(s.canUndo)
            assertFalse(s.canRedo)
        }
    }

    @Test
    fun scope_should_ReturnNull_When_NothingToUndoOrRedo() {
        val s = TableUndoScope()
        assertNull(s.undo())
        assertNull(s.redo())
    }

    @Test
    fun scope_should_StayWithinZeroAndHundred_When_AnySequence() = runTest {
        // op: 0 changed commit, 1 unchanged commit, 2 undo, 3 redo, 4 reset
        checkAll(Arb.list(Arb.int(0..4), 0..400)) { ops ->
            val s = TableUndoScope()
            val undo = ArrayDeque<Int>()
            val redo = ArrayDeque<Int>()
            var n = 0
            for (op in ops) {
                when (op) {
                    0 -> {
                        s.record("c${++n}", true)
                        undo.addLast(n)
                        if (undo.size > 100) undo.removeFirst()
                        redo.clear()
                    }
                    1 -> s.record("same", false)
                    2 -> {
                        val got = s.undo()
                        val expected = undo.removeLastOrNull()
                        assertEquals(expected?.let { "c$it" }, got)
                        expected?.let { redo.addLast(it) }
                    }
                    3 -> {
                        val got = s.redo()
                        val expected = redo.removeLastOrNull()
                        assertEquals(expected?.let { "c$it" }, got)
                        expected?.let { undo.addLast(it) }
                    }
                    else -> {
                        s.reset()
                        undo.clear()
                        redo.clear()
                    }
                }
                assertEquals(undo.size, s.undoDepth)
                assertEquals(redo.size, s.redoDepth)
                assertTrue(s.undoDepth in 0..TableUndoScope.CAP)
            }
        }
    }
}
