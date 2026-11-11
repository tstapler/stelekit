package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.units.LengthQualifier
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inline-text tests so every target (wasmJs included) runs the prose and count extractors. */
class ProseExtractorsTest {
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("rod.dia", DimensionKind.LENGTH, "Rod", DimensionAliases(prose = listOf("chrome rod"))),
            DimensionEntry("span.total", DimensionKind.LENGTH, "Span", DimensionAliases(prose = listOf("three spans"))),
            DimensionEntry("post.list", DimensionKind.LENGTH, "Posts", DimensionAliases(prose = listOf("post positions"))),
            DimensionEntry("bracket.count", DimensionKind.COUNT, "Brackets", DimensionAliases(prose = listOf("wall brackets"))),
        ),
    )
    private val refs = RefTable()
    private val prose = ProseMeasurementExtractor(registry, refs)
    private val counts = CountExtractor(registry, refs)

    private fun lengths(text: String, origin: SourceOrigin = SourceOrigin()) = prose.extract(text, "p", origin).observations
    private fun countsOf(text: String, origin: SourceOrigin = SourceOrigin()) = counts.extract(text, "p", origin).observations

    @Test
    fun `length after the anchor is read exactly as a prose observation`() {
        val o = lengths("- The chrome rod is 1-1/16\" OD.").single()
        assertEquals(Rational(17, 16), o.value)
        assertEquals(SourceKind.PROSE, o.source.kind)
    }

    @Test
    fun `approx marker is kept as a qualifier`() {
        val o = lengths("- chrome rod about ~24\" long").single()
        assertTrue(LengthQualifier.APPROX in o.qualifiers)
    }

    @Test
    fun `a sum reads the total after the equals sign`() {
        val o = lengths("- The three spans sum to 22 1/2\" + 24 3/16\" + 22 1/2\" = 69 3/16\".").single()
        assertEquals(Rational(1107, 16), o.value)
    }

    @Test
    fun `a comma list of lengths is not guessed at`() {
        assertTrue(lengths("- Post positions: 0\", 33 7/16\", 55 15/16\"").isEmpty())
    }

    @Test
    fun `a line naming no anchor or two dimensions yields nothing`() {
        assertTrue(lengths("- The shelf is 22\" deep").isEmpty())
        assertTrue(lengths("- chrome rod and three spans at 5\"").isEmpty())
    }

    @Test
    fun `screw gauge member size and range are not lengths`() {
        assertTrue(lengths("- chrome rod uses #10 x 2.5\" screws").isEmpty())
        assertTrue(lengths("- chrome rod is a 2x4\" part").isEmpty())
        assertTrue(lengths("- chrome rod is 51-54\" long").isEmpty())
    }

    @Test
    fun `table lines and property lines are not prose`() {
        assertTrue(lengths("- | chrome rod | 5\" |\n  |---|---|").isEmpty())
        assertTrue(lengths("- chrome rod\n  size:: 5\"").isEmpty())
    }

    @Test
    fun `digest blocks and their children and owned pages are skipped`() {
        val text = "- Notes\n- Digest\n  stelekit-digest:: x\n\t- chrome rod is 5\"\n- chrome rod is 6\""
        val result = prose.extract(text, "p")
        assertEquals(listOf(Rational(6)), result.observations.map { it.value })
        assertEquals(1, result.skippedSelf)
        val owned = prose.extract("- chrome rod is 5\"", "p", SourceOrigin(pagePropertyKeys = setOf("stelekit-digest")))
        assertTrue(owned.observations.isEmpty())
        assertEquals(1, owned.skippedSelf)
        val firstBlock = prose.extract("stelekit-digest:: x\n- chrome rod is 5\"", "p")
        assertTrue(firstBlock.observations.isEmpty())
        assertTrue(counts.extract("- wall brackets qty 4", "p", SourceOrigin(pagePropertyKeys = setOf("stelekit-digest"))).observations.isEmpty())
    }

    @Test
    fun `counts read qty and number-noun forms but not unrelated numbers`() {
        assertEquals(Rational(8), countsOf("- Wall brackets, qty 8 (4 rods x 2 ends).").single().value)
        assertEquals(Rational(10), countsOf("- Cost: 6 posts + 10 wall brackets.").single().value)
        assertEquals(Rational(12), countsOf("- Wall brackets: two per post, 6 posts gives 12 brackets.").single().value)
        assertTrue(countsOf("- wall brackets use #10 x 2 screws").isEmpty())
        assertTrue(countsOf("- wall brackets 2.5 brackets").isEmpty())
        assertTrue(countsOf("- wall brackets are 6\" brackets").isEmpty())
    }

    @Test
    fun `length extractor ignores count dimensions and the reverse`() {
        assertTrue(lengths("- wall brackets qty 8").isEmpty())
        assertTrue(countsOf("- chrome rod is 5\"").isEmpty())
    }

    @Test
    fun `refs differ per line so repeated statements are independent sources`() {
        val obs = countsOf("- wall brackets qty 8\n- wall brackets qty 8")
        assertEquals(2, obs.map { it.source.ref }.toSet().size)
    }

    @Test
    fun `never throws on arbitrary text`() = runTest {
        checkAll(300, Arb.string(0..300)) { s ->
            lengths(s)
            countsOf(s)
        }
    }

    private fun render(value: Rational): String {
        val whole = value.num / value.den
        val frac = value.num % value.den
        return when {
            frac == 0L -> "$whole\""
            whole == 0L -> "$frac/${value.den}\""
            else -> "$whole $frac/${value.den}\""
        }
    }

    @Test
    fun `rendered lengths and counts round trip exactly`() = runTest {
        val cases = arbitrary { rs ->
            val r = rs.random
            Triple(Rational(r.nextLong(1, 4000), listOf(1L, 2L, 4L, 8L, 16L).random(r)), r.nextLong(0, 500), r.nextInt(3))
        }
        checkAll(200, cases) { (length, count, shape) ->
            val l = lengths("- The chrome rod is ${render(length)} here.").single()
            assertEquals(length, l.value)
            val text = when (shape) {
                0 -> "- wall brackets qty $count"
                1 -> "- $count wall brackets"
                else -> "- wall brackets: $count brackets total"
            }
            assertEquals(Rational(count), countsOf(text).single().value)
        }
    }

    @Test
    fun `collector prose stage runs both extractors`() {
        val result = ProjectSourceCollector(registry).extractProse(
            listOf(TablePageText("page", "- chrome rod is 5\"\n- wall brackets qty 3")),
        )
        assertEquals(setOf(Rational(5), Rational(3)), result.observations.map { it.value }.toSet())
    }
}
