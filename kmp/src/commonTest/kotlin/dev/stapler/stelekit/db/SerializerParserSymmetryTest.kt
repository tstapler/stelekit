package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ParseMode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Parser/serializer asymmetries found by the cross-graph round-trip spike (ADR-001 Story 0.1.4):
 * each case is a real-graph shape where `serialize(parse(f))` rewrote bullet content.
 */
class SerializerParserSymmetryTest {

    private val now = Clock.System.now()

    private fun parse(text: String): List<Block> {
        val parsed = MarkdownParser().parsePage(text, ParseMode.FULL)
        val built = MarkdownPageParser.buildPageModel(
            filePath = "pages/t.md", name = "t", isJournal = false, journalDate = null,
            existingPage = null, now = now, mode = ParseMode.FULL, parsedPage = parsed, fileModTime = null,
        )
        val roots = if (built.firstBlockSkipped) parsed.blocks.drop(1) else parsed.blocks
        val blocks = mutableListOf<Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = roots, pagePath = "pages/t.md", pageUuid = built.page.uuid, parentUuid = null,
            baseLevel = 0, now = now, destinationList = blocks, mode = ParseMode.FULL,
        )
        return blocks
    }

    private fun roundTrip(text: String): String {
        val parsed = MarkdownParser().parsePage(text, ParseMode.FULL)
        val built = MarkdownPageParser.buildPageModel(
            filePath = "pages/t.md", name = "t", isJournal = false, journalDate = null,
            existingPage = null, now = now, mode = ParseMode.FULL, parsedPage = parsed, fileModTime = null,
        )
        return LogseqPageSerializer.serialize(built.page, parse(text))
    }

    private fun shape(blocks: List<Block>) =
        blocks.map { listOf(it.content, it.level, it.properties, it.parentUuid) }

    // Task markers: the inline reconstruct re-added a space after the marker on top of the source's own.

    @Test
    fun taskMarker_followedBySpace_roundTripsExactly() {
        assertEquals("- DONE x\n", roundTrip("- DONE x\n"))
        assertEquals("- Top\n\t- DONE Scope doc\n", roundTrip("- Top\n\t- DONE Scope doc\n"))
    }

    @Test
    fun taskMarker_followedByColon_roundTripsExactly() {
        assertEquals("- TODO: x\n", roundTrip("- TODO: x\n"))
    }

    @Test
    fun taskMarker_alone_roundTripsExactly() {
        assertEquals("- TODO\n", roundTrip("- TODO\n"))
    }

    @Test
    fun property_taskMarkerBullets_roundTripExactly() = runTest {
        val markers = Arb.element("TODO", "DONE", "NOW", "LATER", "WAITING", "CANCELLED", "DOING", "WAIT", "STARTED")
        val seps = Arb.element(" ", ": ", ":", "  ", " - ")
        val words = Arb.string(1..12, Codepoint.alphanumeric())
        checkAll(markers, seps, words) { m, sep, w ->
            val file = "- $m$sep$w\n"
            assertEquals(file, roundTrip(file), "input=$file")
        }
    }

    // Bare "-" (empty bullet with no trailing space) must stay a bullet, not become a paragraph "- -".

    @Test
    fun bareDash_childBullet_staysChildBulletAtSameLevel() {
        val file = "- Parent\n\t- Child\n\t-\n"
        val blocks = parse(file)
        assertEquals(listOf("Parent", "Child", ""), blocks.map { it.content })
        assertEquals(listOf(0, 1, 1), blocks.map { it.level })
        assertEquals(blocks[0].uuid, blocks[2].parentUuid)
        assertEquals("- Parent\n\t- Child\n\t- \n", roundTrip(file))
    }

    @Test
    fun bareDash_afterIdProperty_doesNotSwallowPropertyOrReLevel() {
        val file = "- Working\n\t- The query\n\t  id:: 62717a7e-9100-47d3-846b-c30fcbf4476a\n\t\t-\n"
        val blocks = parse(file)
        assertEquals(listOf(0, 1, 2), blocks.map { it.level })
        assertEquals("62717a7e-9100-47d3-846b-c30fcbf4476a", blocks[1].properties["id"])
        val again = parse(roundTrip(file))
        assertEquals(shape(blocks), shape(again))
    }

    // Code fences inside bullets: the body kept its source indent and the serializer indented it again.

    @Test
    fun fenceInBullet_isStableAcrossRepeatedRoundTrips() {
        val file = "- Run\n\t- ```\n\t  ❯ # Tests\n\t  guitar run \\\n\t  ```\n"
        val once = roundTrip(file)
        val twice = roundTrip(once)
        assertEquals(once, twice)
        assertEquals(shape(parse(file)), shape(parse(once)))
        assertTrue(!once.contains("\t\t\t"), "fence body indent must not inflate: $once")
    }

    @Test
    fun fenceInTopLevelBullet_isStableAcrossRepeatedRoundTrips() {
        val file = "- ```kotlin\n  val a = 1\n    val b = 2\n  ```\n"
        val once = roundTrip(file)
        assertEquals(once, roundTrip(once))
        assertEquals(shape(parse(file)), shape(parse(once)))
    }

    @Test
    fun fenceInBullet_keepsRelativeCodeIndentation() {
        val file = "- ```\n  a\n    b\n  ```\n"
        val content = parse(file).single().content
        val a = content.lines().first { it.trim() == "a" }
        val b = content.lines().first { it.trim() == "b" }
        assertEquals(2, (b.length - b.trimStart().length) - (a.length - a.trimStart().length), "relative indent lost: $content")
    }
}
