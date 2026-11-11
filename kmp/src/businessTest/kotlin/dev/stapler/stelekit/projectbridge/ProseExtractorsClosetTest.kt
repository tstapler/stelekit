package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.3b against the closet fixture's prose lines and counts (hand-written expectations). */
class ProseExtractorsClosetTest {
    private val refs = RefTable()
    private val prose = ProseMeasurementExtractor(ClosetFusion.registry, refs)
    private val counts = CountExtractor(ClosetFusion.registry, refs)

    @Test
    fun `closet prose yields the middle-zone sum and the rod diameter and nothing else`() {
        val found = prose.extract(ClosetFusion.tables, "closet").observations
        assertEquals(
            mapOf("zone.middle" to Rational(1107, 16), "rod.diameter" to Rational(17, 16)),
            found.associate { it.dimension to it.value },
        )
        assertTrue(found.all { it.source.kind == SourceKind.PROSE })
    }

    @Test
    fun `closet counts are the three conflicting carrier statements`() {
        val found = counts.extract(ClosetFusion.tables, "closet").observations
        assertEquals(listOf(Rational(12), Rational(10), Rational(8)), found.map { it.value })
        assertEquals(setOf("rod-carrier.count"), found.map { it.dimension }.toSet())
        assertEquals(3, found.map { it.source.ref }.toSet().size)
    }
}
