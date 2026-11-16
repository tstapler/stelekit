package dev.stapler.stelekit.ui.annotate

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolPolicyTest {
    @Test
    fun nextToolAfterCommit_should_ReturnSelect_When_OneShotAndAutoReturnOn_AndKeepTool_When_Locked_OrSettingOff() = runTest {
        checkAll(
            Arb.enum<AnnotationTool>(), Arb.enum<ToolStickiness>(), Arb.boolean(), Arb.boolean(),
        ) { tool, stickiness, autoReturn, multiStep ->
            val next = nextToolAfterCommit(tool, stickiness, autoReturn, multiStep)
            val expectSelect = tool == AnnotationTool.SELECT ||
                (autoReturn && stickiness == ToolStickiness.ONE_SHOT && !multiStep)
            assertEquals(expectSelect, next == AnnotationTool.SELECT, "$tool $stickiness $autoReturn $multiStep")
            if (!expectSelect) assertEquals(tool, next)
            if (stickiness == ToolStickiness.LOCKED) assertEquals(tool, next)
        }
    }

    @Test
    fun select_should_BeIdempotent() = runTest {
        checkAll(Arb.enum<ToolStickiness>(), Arb.boolean(), Arb.boolean()) { s, a, m ->
            assertEquals(AnnotationTool.SELECT, nextToolAfterCommit(AnnotationTool.SELECT, s, a, m))
        }
    }

    @Test
    fun multiStepSessionOpen_should_KeepToolArmed() {
        assertEquals(
            AnnotationTool.AREA,
            nextToolAfterCommit(AnnotationTool.AREA, ToolStickiness.ONE_SHOT, true, multiStepSessionOpen = true),
        )
    }

    @Test
    fun rollback_should_RestorePreviousTool_OnlyIfUserHasNotChosenAnother() = runTest {
        assertEquals(
            AnnotationTool.DISTANCE,
            nextToolAfterRollback(AnnotationTool.DISTANCE, AnnotationTool.SELECT, AnnotationTool.SELECT),
        )
        assertEquals(
            AnnotationTool.ANGLE,
            nextToolAfterRollback(AnnotationTool.DISTANCE, AnnotationTool.SELECT, AnnotationTool.ANGLE),
        )
        checkAll(Arb.enum<AnnotationTool>(), Arb.enum<AnnotationTool>(), Arb.enum<AnnotationTool>()) { before, after, cur ->
            assertEquals(if (cur == after) before else cur, nextToolAfterRollback(before, after, cur))
        }
    }
}
