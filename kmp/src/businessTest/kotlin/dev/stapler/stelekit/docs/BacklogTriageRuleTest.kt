package dev.stapler.stelekit.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

enum class TriageVerdict { ALLOW, CUT_LOWEST_PRIORITY_PLACEHOLDER }

data class TriageResult(
    val phase: String,
    val baseline: Int,
    val threshold: Int,
    val realCount: Int,
    val verdict: TriageVerdict,
)

/**
 * Story A.3.3's cut-not-grow triage rule (plan.md's Reconciliation section, mirrored in
 * gap-backlog.md's `## Reconciliation` table): for each phase, if the real P0/P1 backlog row
 * count is at or below 2x that phase's placeholder-story baseline, new sibling stories may be
 * filed; if it exceeds the threshold, a placeholder must be cut instead of silently growing scope.
 */
object BacklogTriageRule {
    fun evaluate(phase: String, baseline: Int, realP0P1Count: Int): TriageResult {
        val threshold = baseline * 2
        val verdict = if (realP0P1Count <= threshold) {
            TriageVerdict.ALLOW
        } else {
            TriageVerdict.CUT_LOWEST_PRIORITY_PLACEHOLDER
        }
        return TriageResult(phase, baseline, threshold, realP0P1Count, verdict)
    }

    fun countRealP0P1(rows: List<BacklogRow>, phase: String): Int =
        rows.count { it.targetPhase == phase && it.priority in setOf("P0", "P1") }
}

class BacklogTriageRuleTest {

    private val decisionLanguage = Regex(
        """(?i)not fixed in this pass|filed for a future pass|cut\b|new sibling story|fixed in epic"""
    )

    @Test
    fun `triageRule should allowNewSiblingStories When p0p1RowCountIsAtOrBelowTwiceBaseline`() {
        val result = BacklogTriageRule.evaluate(phase = "D", baseline = 3, realP0P1Count = 6)
        assertEquals(TriageVerdict.ALLOW, result.verdict)
        assertEquals(6, result.threshold)
    }

    @Test
    fun `triageRule should returnCutVerdict When p0p1RowCountExceedsTwiceBaseline`() {
        val result = BacklogTriageRule.evaluate(phase = "D", baseline = 3, realP0P1Count = 8)
        assertEquals(TriageVerdict.CUT_LOWEST_PRIORITY_PLACEHOLDER, result.verdict)
        assertEquals(6, result.threshold)
        assertTrue(result.realCount > result.threshold)
    }

    @Test
    fun `baselinesInCode should matchDocumentedBaselines When gapBacklogReconciliationIsParsed`() {
        val text = DocRepoLocator.gapBacklogFile.readText()
        val documented = Regex("""\*\*([A-Z0-9]+)=(\d+)\*\*""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

        val hardcodedBaselines = mapOf("C" to 2, "D" to 4, "E" to 1, "F" to 1, "G" to 1, "G2" to 1)
        for ((phase, baseline) in hardcodedBaselines) {
            assertEquals(
                baseline,
                documented[phase],
                "gap-backlog.md's documented baseline for phase $phase drifted from the hardcoded triage baseline"
            )
        }
    }

    @Test
    fun `reconciliationSection should recordCutReasoning When triageRuleReturnsCutVerdict`() {
        val rows = GapBacklogParser.parseRows(DocRepoLocator.gapBacklogFile.readText())
        val baselines = mapOf("C" to 2, "D" to 4, "E" to 1, "F" to 1, "G" to 1, "G2" to 1)

        var sawAtLeastOneCut = false
        for ((phase, baseline) in baselines) {
            val realCount = BacklogTriageRule.countRealP0P1(rows, phase)
            val result = BacklogTriageRule.evaluate(phase, baseline, realCount)
            if (result.verdict != TriageVerdict.CUT_LOWEST_PRIORITY_PLACEHOLDER) continue

            sawAtLeastOneCut = true
            val phaseRows = rows.filter { it.targetPhase == phase && it.priority in setOf("P0", "P1") }
            val hasRecordedDecision = phaseRows.any { decisionLanguage.containsMatchIn(it.gapDescription) }
            assertTrue(
                hasRecordedDecision,
                "Phase $phase real P0/P1 count ($realCount) exceeds its 2x threshold (${result.threshold}) " +
                    "with no recorded cut/defer decision in any of its rows' gap_description"
            )
        }
        // Sanity guard: this project's real backlog does have (at least) one phase that outgrew
        // its Phase-A-time baseline once Phase G's own audit landed rows — if this ever turns
        // false, the CUT arm above is not exercising anything against real data.
        assertTrue(sawAtLeastOneCut, "expected at least one phase to exceed its 2x threshold against the real backlog")
    }

    @Test
    fun `decisionLanguage should distinguishReasonedFromUnreasonedCutFixtures When givenFixtureText`() {
        val docWithNoReasoning = "Phase D shipped 8 P0/P1 rows against a baseline of 3, no further comment."
        assertTrue(!decisionLanguage.containsMatchIn(docWithNoReasoning))

        val docWithReasoning = "Not fixed in this pass — filed for a future pass."
        assertTrue(decisionLanguage.containsMatchIn(docWithReasoning))
    }
}
