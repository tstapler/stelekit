package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 6.2.3, Task 6.2.3a: log lines carry ids, tokens and counts only. */
class SketchLogTest {
    private val lines = mutableListOf<String>()
    private val log = SketchLog(SketchLogSink { lines += it })

    @Test
    fun solveFailed_should_LogIdsAndNotElementText_When_SheetHoldsSecretText() {
        val sheetText = "Secret"
        log.solveFailed("Conflict", listOf("c1", "c2"))
        assertEquals(listOf("sketch.solve.failed kind=Conflict ids=c1,c2"), lines)
        assertFalse(lines.any { sheetText in it })
    }

    @Test
    fun anyField_should_BecomeQuestionMark_When_ValueIsNotAnIdToken() {
        log.importFailed("My Secret Diagram.excalidraw")
        log.persistFailed("")
        log.sheetRejected("x".repeat(SceneLimits.Default.maxLayerIdChars + 1))
        log.solveFailed("Conflict", listOf("c1", "label with spaces"))
        assertEquals(
            listOf(
                "sketch.import.failed reason=?",
                "sketch.persist.failed reason=?",
                "sketch.sheet.rejected reason=?",
                "sketch.solve.failed kind=Conflict ids=c1,?",
            ),
            lines,
        )
    }

    @Test
    fun solveFailed_should_SummariseTheTail_When_MoreIdsThanTheConstraintCap() {
        val cap = SceneLimits.Default.maxConstraints
        log.solveFailed("Conflict", List(cap + 3) { "c$it" })
        assertTrue(lines.single().endsWith(",+3"), lines.single())
    }

    @Test
    fun scriptedRun_should_EmitEachLineExactlyOnce_When_EveryTriggerFires() {
        log.toolActivated("rectangle")
        log.solveFailed("Conflict", listOf("c1"))
        log.importFailed("NotJson")
        log.importSummary(imported = 4, skipped = 1)
        log.migrateOutcome("Converted")
        log.persistFailed("IoError")
        log.sheetRejected("TooLarge")
        log.saveConflict("s1")
        val names = lines.map { it.substringBefore(' ') }
        assertEquals(
            listOf(
                "sketch.tool.activated", "sketch.solve.failed", "sketch.import.failed", "sketch.import.summary",
                "sketch.migrate.outcome", "sketch.persist.failed", "sketch.sheet.rejected", "sketch.save.conflict",
            ),
            names,
        )
        assertEquals("sketch.import.summary imported=4 skipped=1", lines[3])
    }

    @Test
    fun toolActivated_should_LogOnlyOnChange_When_SameToolIsReselected() {
        repeat(1_000) { log.toolActivated("select") }
        log.toolActivated("hand")
        assertEquals(listOf("sketch.tool.activated tool=select", "sketch.tool.activated tool=hand"), lines)
    }

    @Test
    fun counts_should_NeverBeNegative_When_CallerPassesNegative() {
        log.importSummary(imported = -2, skipped = 0)
        assertEquals("sketch.import.summary imported=0 skipped=0", lines.single())
    }
}
