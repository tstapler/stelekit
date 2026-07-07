package dev.stapler.stelekit.docs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enforces the JTBD-weighted step-count rubric from `docs/journeys/README.md` §"Rubric":
 * functional/in-between-tier journeys carry a concrete numeric step-count ceiling in
 * `step_count_target`; social-tier journeys carry a qualitative discoverability checklist
 * instead and must NOT collapse to a bare number. Also enforces the heuristic-findings
 * concrete-evidence rule and the flagship (insert-tag) before/after step-count improvement.
 */
class JourneyStepCountRubricTest {

    /** A "bare number" is the entire (trimmed, optionally quoted) value being just digits. */
    private val bareNumberRegex = Regex("""^"?\d+"?$""")

    /**
     * Acceptable evidence per README §"Heuristic review": a file:line-shaped citation, a
     * `GAP-###` backlog id, or the project's own idiom for "a specific confirmed-broken
     * behavior" (`CONFIRMED ABSENT` / `CONFIRMED BROKEN` / a confirmed complete no-op).
     */
    private val citationRegex = Regex(
        """(?i)\.kt\b|GAP-\d+|CONFIRMED ABSENT|CONFIRMED BROKEN|confirmed complete no-op"""
    )

    @Test
    fun `stepCountTarget should containNumericCeiling When jtbdTierIsFunctionalOrInBetween`() {
        val docs = JourneyDocs.parseAll().filter { it.fields["jtbd_tier"] in setOf("functional", "in-between") }
        assertTrue(docs.isNotEmpty(), "expected at least one functional/in-between journey doc")

        val failures = docs.mapNotNull { doc ->
            val target = doc.fields["step_count_target"]
            when {
                target.isNullOrBlank() ->
                    "${doc.fileName}: step_count_target missing (required for functional/in-between tier)"
                !target.contains(Regex("""\d""")) ->
                    "${doc.fileName}: step_count_target '$target' has no numeric ceiling"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `stepCountTarget should rejectNumericValue When jtbdTierIsSocial`() {
        val socialDocs = JourneyDocs.parseAll().filter { it.fields["jtbd_tier"] == "social" }
        assertTrue(socialDocs.isNotEmpty(), "expected at least one social-tier journey doc (annotate-asset.md)")

        for (doc in socialDocs) {
            val target = doc.fields["step_count_target"]
            assertTrue(
                !target.isNullOrBlank(),
                "${doc.fileName}: step_count_target must still be present (as a qualitative checklist) for social tier"
            )
            assertFalse(
                bareNumberRegex.matches(target!!.trim()),
                "${doc.fileName}: step_count_target is a bare number ('$target') — social tier must carry a " +
                    "discoverability checklist per docs/journeys/README.md, not a hard step-count ceiling"
            )
        }
    }

    @Test
    fun `bareNumberRegex should distinguishBareNumbersFromDescriptiveText When givenFixtureValues`() {
        assertTrue(bareNumberRegex.matches("2"))
        assertTrue(bareNumberRegex.matches("\"2\""))
        assertFalse(bareNumberRegex.matches("≤2 taps, per criterion 12"))
        assertFalse(bareNumberRegex.matches("discoverability checklist only, no hard step-count number"))
    }

    @Test
    fun `heuristicFindings should citeConcreteEvidence When crossReferencedAgainstCurrentStepCount`() {
        val failures = JourneyDocs.parseAll().mapNotNull { doc ->
            val findings = doc.fields["heuristic_findings"]
            when {
                findings.isNullOrBlank() -> "${doc.fileName}: heuristic_findings missing"
                !citationRegex.containsMatchIn(findings) ->
                    "${doc.fileName}: heuristic_findings has no concrete citation " +
                        "(file:line, GAP-### id, or a confirmed-behavior phrase)"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Pulls the leading integer (or the upper bound of an `N-M` range) preceding "steps". */
    private fun extractStepCount(blob: String, platformKey: String): Int? {
        val platformValue = Regex("""$platformKey:\s*"([^"]*)"""").find(blob)?.groupValues?.get(1)
            ?: return null
        val stepMatch = Regex("""(\d+)(?:-(\d+))?\s+(?:discrete\s+)?steps""").find(platformValue) ?: return null
        val lower = stepMatch.groupValues[1]
        val upper = stepMatch.groupValues[2]
        return (if (upper.isNotBlank()) upper else lower).toInt()
    }

    @Test
    fun `insertTagJourney should recordPostFixStepCountLowerThanCurrent When primaryRowChangeShips`() {
        val doc = JourneyDocs.parseAll().single { it.fileName == "insert-tag.md" }
        val current = doc.fields["current_step_count"].orEmpty()
        val postFix = doc.fields["post_fix_step_count"]
        assertTrue(!postFix.isNullOrBlank(), "insert-tag.md must record post_fix_step_count once its Phase D fix ships")

        val currentAndroidSteps = extractStepCount(current, "android")
        val postFixAndroidSteps = extractStepCount(postFix!!, "android")
        assertTrue(
            currentAndroidSteps != null && postFixAndroidSteps != null,
            "could not parse an android step count from current_step_count ($current) or " +
                "post_fix_step_count ($postFix)"
        )
        assertTrue(
            postFixAndroidSteps!! < currentAndroidSteps!!,
            "expected post_fix_step_count's android step count ($postFixAndroidSteps) to be lower than " +
                "current_step_count's ($currentAndroidSteps) by at least 1"
        )
        assertTrue(currentAndroidSteps - postFixAndroidSteps >= 1)
    }

    @Test
    fun `insertTagJourney should failValidation When postFixStepCountMissingAfterPhaseDShips`() {
        // gap-backlog.md's Phase D implementation section records GAP-003 (insert-tag's flagship
        // row) as FIXED — cross-check that a doc claiming that fix landed still records
        // post_fix_step_count; a doc that omits it despite the backlog saying the fix shipped
        // must fail the doc-completeness check, not pass silently.
        val backlogText = DocRepoLocator.gapBacklogFile.readText()
        val gap003Fixed = Regex("""GAP-003[^\n]*FIXED""").containsMatchIn(backlogText)
        assertTrue(gap003Fixed, "expected gap-backlog.md to record GAP-003 as FIXED")

        fun postFixCompletenessErrors(fields: Map<String, String>, gapFixedForThisJourney: Boolean): List<String> {
            if (!gapFixedForThisJourney) return emptyList()
            return if (fields["post_fix_step_count"].isNullOrBlank()) {
                listOf("post_fix_step_count missing despite an associated gap-backlog fix being recorded as FIXED")
            } else {
                emptyList()
            }
        }

        // Real doc: passes, because insert-tag.md does carry post_fix_step_count.
        val realDoc = JourneyDocs.parseAll().single { it.fileName == "insert-tag.md" }
        assertTrue(postFixCompletenessErrors(realDoc.fields, gapFixedForThisJourney = true).isEmpty())

        // Error-path fixture: a doc missing post_fix_step_count despite the fix having shipped.
        val incompleteFixtureFields = mapOf(
            "journey_id" to "insert-tag",
            "current_step_count" to "android: \"4-5 discrete steps\"",
        )
        val errors = postFixCompletenessErrors(incompleteFixtureFields, gapFixedForThisJourney = true)
        assertTrue(errors.isNotEmpty(), "expected a doc-completeness failure when post_fix_step_count is absent")
    }
}
