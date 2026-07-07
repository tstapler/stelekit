package dev.stapler.stelekit.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extracts real `GAP-###` id references from free-form planning prose. Requires exactly 3
 * digits (matching this backlog's zero-padded `GAP-001`..`GAP-023` convention) so illustrative
 * placeholders like plan.md's `GAP-00X` (used before Phase A's real backlog existed) are never
 * mistaken for a real reference.
 */
object GapReferenceExtractor {
    private val gapIdRegex = Regex("""GAP-\d{3}\b""")

    fun extractReferencedIds(text: String): Set<String> = gapIdRegex.findAll(text).map { it.value }.toSet()

    fun findUnresolvedReferences(referenced: Set<String>, knownIds: Set<String>): Set<String> =
        referenced - knownIds
}

class GapBacklogTraceabilityTest {

    @Test
    fun `everyGapIdReferencedInPlan should existAsABacklogRow When realDocsAreCrossChecked`() {
        val planText = DocRepoLocator.planFile.readText()
        val referenced = GapReferenceExtractor.extractReferencedIds(planText)
        assertTrue(referenced.isNotEmpty(), "expected plan.md to reference at least one real GAP-### id")

        val knownIds = GapBacklogParser.parseRows(DocRepoLocator.gapBacklogFile.readText())
            .map { it.gapId }
            .toSet()

        val unresolved = GapReferenceExtractor.findUnresolvedReferences(referenced, knownIds)
        assertTrue(
            unresolved.isEmpty(),
            "plan.md references gap ids with no corresponding gap-backlog.md row: $unresolved"
        )
    }

    @Test
    fun `gapReferenceExtractor should ignoreIllustrativePlaceholders When idIsNotThreeDigits`() {
        // plan.md's Story C.2.1/C.2.2 headers deliberately use "GAP-00X" as an illustrative,
        // not-yet-real placeholder (Phase A's backlog didn't exist yet at planning time). This
        // must NOT be extracted as a real reference, or this test would false-positive against
        // real content that is intentionally illustrative, not broken.
        val referenced = GapReferenceExtractor.extractReferencedIds(
            "actual item comes from Phase A's `gap-backlog.md`, e.g. `GAP-00X`"
        )
        assertTrue(referenced.isEmpty(), "illustrative placeholder 'GAP-00X' must not be extracted as a real id")
    }

    @Test
    fun `findUnresolvedReferences should flagMissingId When fixtureBacklogLacksIt`() {
        val referenced = setOf("GAP-999")
        val known = setOf("GAP-001", "GAP-002")
        val unresolved = GapReferenceExtractor.findUnresolvedReferences(referenced, known)
        assertEquals(setOf("GAP-999"), unresolved)
    }
}
