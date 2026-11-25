package dev.stapler.stelekit.table.formula

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointModeRuleTest {
    private fun atEnd(draft: String) = PointModeRule.allows(draft, draft.length)

    @Test
    fun allows_should_BeTrueOnlyAfterOperatorOrParenOrComma_When_Draft() {
        for (d in listOf("=", "=SUM(", "=B2+", "=B2*", "=IF(A2>1, ", "=(", "=-", "=B2<=", "=SUM(B2,")) {
            assertTrue(atEnd(d), "expected armed for '$d'")
        }
        for (d in listOf("=B2", "=SUM(B2:B5)", "abc", "5", "", "=SUM(B2:B5", "=B2%")) {
            assertFalse(atEnd(d), "expected not armed for '$d'")
        }
    }

    @Test
    fun allows_should_BeFalse_When_CaretInsideToken() {
        assertFalse(PointModeRule.allows("=B2+C10", 6))
        assertFalse(PointModeRule.allows("=SUM(B2)", 3))
        assertTrue(PointModeRule.allows("=B2+C10", 4))
        assertFalse(PointModeRule.allows("=B2+", 0))
    }

    @Test
    fun rule_should_NotArm_When_DraftIsNotFormulaPrefix() {
        for (d in listOf("=5 apples", "= total", "==", "=5 apples+", "=\"unterminated", "=B2 B3+")) {
            assertFalse(atEnd(d), "'$d' must not arm point mode")
        }
    }

    @Test
    fun allowsReplace_should_BeTrueOnlyRightAfterInsert_And_FalseAfterTyping() {
        var s = PointModeRule.onEditStarted()
        assertFalse(PointModeRule.allowsReplace(s))
        s = PointModeRule.onReferenceInserted(s)
        assertTrue(s.armed)
        assertTrue(PointModeRule.allowsReplace(s))
        s = PointModeRule.onDraftChanged(s, "=B2+", 4)
        assertFalse(PointModeRule.allowsReplace(s))
        assertTrue(s.armed)
    }

    @Test
    fun stop_should_MakeTapsCommit_When_StoppedForThisEdit() {
        var s = PointModeRule.onDraftChanged(PointModeRule.onEditStarted(), "=B2+", 4)
        assertTrue(PointModeRule.allows("=B2+", 4, s))
        s = PointModeRule.stop(s)
        assertFalse(s.armed)
        assertFalse(PointModeRule.allows("=B2+", 4, s))
        s = PointModeRule.onDraftChanged(s, "=B2+C3*", 7)
        assertFalse(s.armed, "typing does not clear Stop within the same edit")
        assertEquals(PointModeState(), PointModeRule.onEditStarted())
    }

    @Test
    fun transitions_should_DisarmOnDone_And_OnCaretMoveIntoToken() {
        var s = PointModeRule.onDraftChanged(PointModeRule.onEditStarted(), "=SUM(", 5)
        assertTrue(s.armed)
        s = PointModeRule.onDraftChanged(s, "=SUM(B2", 5)
        assertTrue(s.armed)
        s = PointModeRule.onDraftChanged(s, "=SUM(B2", 7)
        assertFalse(s.armed)
        s = PointModeRule.onDone(PointModeRule.onReferenceInserted(s))
        assertEquals(PointModeState(armed = false, justInserted = false, stoppedForThisEdit = false), s)
    }

    @Test
    fun allows_should_NeverBeTrue_When_DraftDoesNotStartWithEquals() = runTest {
        val chars = listOf("=", "+", "(", ",", "B", "2", " ", "S", "U", "M", "*")
        checkAll(Arb.list(Arb.element(chars), 0..10).map { it.joinToString("") }, Arb.int(0..12)) { body, caret ->
            val draft = if (body.startsWith("=")) " $body" else body
            assertFalse(PointModeRule.allows(draft, caret), "'$draft' caret $caret")
        }
    }
}
