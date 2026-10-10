package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.LogseqPageSerializer
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Block markup (heading, SCHEDULED/DEADLINE, logbook, task markers, tables, code, lists) survives render and re-read. */
class BlockMarkupRoundTripTest {
    private val path = MergeFixtures.PATH

    // Each entry is a complete top-level bullet as it appears in a file.
    private val constructs = listOf(
        "- ## Heading two",
        "- # Heading one",
        "- ### Deep heading",
        "- TODO t\n  SCHEDULED: <2026-01-01 Thu>",
        "- DOING d\n  SCHEDULED: <2026-01-01 Thu>\n  DEADLINE: <2026-02-01 Sun>",
        "- DEADLINE: <2026-01-01 Thu>",
        "- DOING x\n  :LOGBOOK:\n  CLOCK: [2026-01-01 Thu 10:00]--[2026-01-01 Thu 11:00] =>  01:00:00\n  :END:",
        "- | a | b |\n  |---|---|\n  | 1 | 2 |",
        "- ```kotlin\n  fun a() {\n    x()\n  }\n  ```",
        "- 1. first item",
        "- DONE finished",
        "- plain with status\n  status:: done",
    )

    private fun page(text: String) = MergeConverters.parseMarkdown(text, path, "P", false)

    private fun nest(snippets: List<String>, depth: Int): String =
        snippets.mapIndexed { i, s -> indent(s, if (depth == 0) 0 else i % (depth + 1)) }.joinToString("\n") + "\n"

    // A child's level is at most the previous bullet's level + 1, which `i % (depth + 1)` can break; clamp to keep the tree valid.
    private fun indent(snippet: String, level: Int): String = snippet.lines().joinToString("\n") { "\t".repeat(level) + it }

    private fun assertRenderRoundTrips(text: String) {
        val original = page(text).mergePage
        val rendered = MergeRenderer.renderNewPage(original)
        assertEquals(original, page(rendered).mergePage, "render changed the blocks of:\n$text\n-> rendered:\n$rendered")
    }

    private fun assertSerializerRoundTrips(text: String) {
        val parsed = page(text)
        val written = LogseqPageSerializer.serialize(parsed.page, parsed.blocks)
        assertEquals(parsed.mergePage, page(written).mergePage, "serializer changed the blocks of:\n$text\n-> written:\n$written")
    }

    @Test
    fun everyConstructRoundTripsThroughTheMergeRenderer() {
        constructs.forEach { assertRenderRoundTrips(it + "\n") }
    }

    @Test
    fun everyConstructRoundTripsThroughTheProductionSerializer() {
        constructs.forEach { assertSerializerRoundTrips(it + "\n") }
    }

    @Test
    fun scheduleStaysAnOrgLineNotAProperty() {
        val text = MergeRenderer.renderNewPage(page("- TODO t\n  SCHEDULED: <2026-01-01 Thu>\n").mergePage)
        assertTrue("SCHEDULED: <2026-01-01 Thu>" in text, text)
        assertTrue("scheduled::" !in text, text)
    }

    @Test
    fun headingKeepsItsMarker() {
        val text = MergeRenderer.renderNewPage(page("- ## Heading\n").mergePage)
        assertEquals("- ## Heading\n", text)
    }

    @Test
    fun storedShapeOfAHeadingIsTheLoadersView() {
        val shape = checkNotNull(MergeConverters.storedShape(MergeBlock(null, "## Heading"), path))
        assertEquals("Heading", shape.content)
        assertIs<dev.stapler.stelekit.model.BlockType.Heading>(shape.blockType)
    }

    @Test
    fun randomMixesOfConstructsRoundTripThroughBothWriters() = runTest {
        checkAll(300, Arb.int(1..6), Arb.int(0..2)) { count, depth ->
            val picks = List(count) { constructs[(it * 7 + count * 3 + depth) % constructs.size] }
            val text = nest(picks, 0)
            assertRenderRoundTrips(text)
            assertSerializerRoundTrips(text)
        }
    }

    @Test
    fun spliceCarriesAnyConstructIntoAnExistingPageFaithfully() = runTest {
        checkAll(Arb.element(constructs)) { snippet ->
            val incoming = page(snippet + "\n").mergePage.blocks.single().copy(uuid = "55555555-5555-5555-5555-555555555555")
            val request = SpliceRequest(listOf(BlockInsertion(emptyList(), incoming)))
            val spliced = assertIs<Either.Right<SpliceResult>>(RoundTripGuard.splice(MergeFixtures.UNLABELED_FLAT, request, path, false)).value
            assertEquals(incoming, page(spliced.text).mergePage.blocks.last(), "inserted block changed:\n${spliced.text}")
        }
    }

    @Test
    fun guardRefusesAnInsertedBlockThatReReadsDifferently() {
        // A continuation line shaped like a property is re-read as a property, not content.
        val lossy = MergeBlock(null, "intro\nkey:: value-looking text")
        val request = SpliceRequest(listOf(BlockInsertion(emptyList(), lossy)))

        val failure = RoundTripGuard.splice(MergeFixtures.UNLABELED_FLAT, request, path, false).leftOrNull()

        assertIs<NotRoundTrippable.InsertedContentChanged>(failure)
    }

    @Test
    fun guardRefusesALossyBlockOnANewPage() {
        val lossy = MergeBlock(null, "intro\nkey:: value-looking text")
        val failure = RoundTripGuard.verifyRendered(MergeRenderer.renderNewPage(MergePage("P", blocks = listOf(lossy))), MergePage("P", blocks = listOf(lossy)), path, false)
        assertIs<NotRoundTrippable.InsertedContentChanged>(failure)
    }
}
