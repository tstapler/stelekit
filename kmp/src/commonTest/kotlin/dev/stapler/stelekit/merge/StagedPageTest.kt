package dev.stapler.stelekit.merge

import arrow.core.Either
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StagedPageTest {
    private fun decode(page: MergePage) = (StagedPage.decode(StagedPage.encode(page)) as Either.Right).value

    @Test
    fun threeBlocksWithUuidsRoundTrip() {
        val page = MergePage(
            name = "Projects",
            isJournal = true,
            journalDate = LocalDate(2026, 10, 7),
            properties = mapOf("alias" to "p", "type" to "x"),
            blocks = listOf(
                MergeBlock("11111111-1111-1111-1111-111111111111", "a", mapOf("collapsed" to "true"), listOf(
                    MergeBlock("22222222-2222-2222-2222-222222222222", "b"),
                )),
                MergeBlock("33333333-3333-3333-3333-333333333333", "c"),
            ),
        )
        val back = decode(page)
        assertEquals(page, back)
        assertEquals(page.properties.keys.toList(), back.properties.keys.toList())
    }

    @Test
    fun nullUuidSurvivesAndIsNotConfusedWithEmpty() {
        val page = MergePage("P", blocks = listOf(MergeBlock(null, "unlabeled"), MergeBlock("", "blank-id")))
        val back = decode(page)
        assertNull(back.blocks[0].uuid)
        assertEquals("", back.blocks[1].uuid)
    }

    @Test
    fun unsupportedVersionAndGarbageAreTypedErrors() {
        assertIs<StagedPageError.UnsupportedVersion>(StagedPage.decode("""{"version":99,"name":"x"}""").leftOrNull())
        assertIs<StagedPageError.Malformed>(StagedPage.decode("not json").leftOrNull())
        assertIs<StagedPageError.Malformed>(StagedPage.decode("""{"name":"x","journalDate":"nope"}""").leftOrNull())
    }

    private fun arbBlock(depth: Int): Arb<MergeBlock> = arbitrary { rs ->
        val r = rs.random
        MergeBlock(
            uuid = if (r.nextBoolean()) Arb.uuid().map { it.toString() }.sample(rs).value else null,
            content = Arb.string(0..20).sample(rs).value,
            properties = if (r.nextBoolean()) mapOf("k${r.nextInt(3)}" to Arb.string(0..8).sample(rs).value) else emptyMap(),
            children = if (depth > 0) Arb.list(arbBlock(depth - 1), 0..2).sample(rs).value else emptyList(),
        )
    }

    @Test
    fun anyPageRoundTripsExactly() = runTest {
        checkAll(Arb.list(arbBlock(2), 0..4), Arb.boolean(), Arb.int(1..28)) { blocks, journal, day ->
            val page = MergePage("n", journal, if (journal) LocalDate(2026, 2, day) else null, mapOf("a" to "b"), blocks)
            assertEquals(page, decode(page))
        }
    }
}
