package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fixtures.FC
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 1.5b-2, unit level: observations are built by this test from the fixture texts (zone widths
 * and the rod label are read from the loaded text so seeded mutations apply to it) and from
 * `FC.*` constants, then fused. Verdicts are hand-written constants, not read from a golden. The
 * end-to-end golden through the real extractors is Story 5.8.
 */
class ClosetInvariantTest {
    private val registry = ClosetFusion.registry
    private val tables = ClosetFusion.tables
    private val elevation = ClosetFusion.elevation

    private val zones = ClosetFusion.zones
    private val tableRows = ClosetFusion.tableRows

    private fun r(num: Long, den: Long = 1) = ClosetFusion.r(num, den)

    private fun tableZone(markdown: String, row: String) = ClosetFusion.tableZone(markdown, row)

    private fun observations(tablesText: String = tables, elevationText: String = elevation) =
        ClosetFusion.observations(tablesText, elevationText)

    private val invariantIds: Map<String, List<Int>> = mapOf(
        "I1" to listOf(1), "I2" to listOf(2), "I3" to listOf(3), "I4" to (4..8).toList(), "I5" to listOf(9, 10, 11),
        "I6" to (12..20).toList(), "I7" to listOf(21), "I8" to listOf(22), "I9" to listOf(23, 24, 25),
    )

    private fun constraints() = ClosetFusion.constraints()

    private fun fuse(obs: List<Observation>): FusionResult =
        fuseObservations(FusionInput(obs, constraints()), registry).fold({ error("fusion failed: $it") }, { it })

    private fun verdicts(result: FusionResult): Map<String, InvariantVerdict> {
        val byId = result.invariants.associate { it.constraintId to it.verdict }
        return invariantIds.mapValues { (_, ids) ->
            val each = ids.map { byId.getValue(it) }
            when {
                InvariantVerdict.FAILS in each -> InvariantVerdict.FAILS
                each.all { it == InvariantVerdict.HOLDS } -> InvariantVerdict.HOLDS
                else -> InvariantVerdict.UNDETERMINED
            }
        }
    }

    private val allHold = invariantIds.keys.associateWith { InvariantVerdict.HOLDS }

    @Test
    fun `nine invariants all hold on the clean fixtures`() {
        assertEquals(9, invariantIds.size)
        assertEquals(allHold, verdicts(fuse(observations())))
    }

    @Test
    fun `fifteen dimensions each have at least two agreeing sources`() {
        val result = fuse(observations())
        val keys = listOf(
            "niche.width", "zone.left-long.width", "zone.left-short.width", "zone.shelves.width", "zone.right-short.width",
            "zone.right-long.width", "niche.height.rear", "niche.height.front", "rod.short.height", "rod.long.height",
            "niche.depth", "lowercab.height", "lowercab.bay", "shelf.gap",
        ) + (0..5).map { "standards.positions.$it" }
        keys.forEach { key ->
            val status = assertIs<DimensionStatus.Confirmed>(result.dimensions[key], "$key not confirmed: ${result.dimensions[key]}")
            assertTrue(status.sources >= 2, "$key has ${status.sources} source(s)")
        }
        assertEquals(15, keys.map { if (it.startsWith("standards.positions.")) "standards.positions" else it }.toSet().size)
    }

    @Test
    fun `lossy decimal labels do not conflict with their fractions`() {
        val result = fuse(observations())
        assertEquals(Rational(693, 16), assertIs<DimensionStatus.Confirmed>(result.dimensions["niche.height.front"]).value)
        assertEquals(FC.usableCabinetHeight, assertIs<DimensionStatus.Confirmed>(result.dimensions["lowercab.height"]).value)
    }

    @Test
    fun `feet and inches form of the opening equals its inch form`() {
        val ft = assertIs<ParsedLength.Measured>(parseLength("11' 4\"").getOrNull())
        assertEquals(FC.opening, ft.value)
    }

    @Test
    fun `seeded C1 mutates the table zone and I1 I3 I4 fail while the rest hold`() {
        val mutated = tables.replace("**33 7/16\"**", "**33 3/8\"**")
        assertEquals(FC.zoneLeftLongTableMutated, parseLength(tableZone(mutated, tableRows[0])).getOrNull().let { (it as ParsedLength.Measured).value })
        val result = fuse(observations(tablesText = mutated))
        val conflict = assertIs<DimensionStatus.Conflict>(result.dimensions["zone.left-long.width"])
        assertTrue(conflict.pairs.all { it.difference == Rational(1, 16) })
        assertTrue(conflict.pairs.any { SourceKind.TABLE in listOf(it.first.source.kind, it.second.source.kind) })
        val expected = allHold + mapOf("I1" to InvariantVerdict.FAILS, "I3" to InvariantVerdict.FAILS, "I4" to InvariantVerdict.FAILS)
        assertEquals(expected, verdicts(result))
        val tableI1 = result.invariants.single { it.constraintId == 1 }.bySource.getValue(SourceKind.TABLE)
        assertEquals(InvariantVerdict.FAILS, tableI1)
        assertEquals(InvariantVerdict.HOLDS, result.invariants.single { it.constraintId == 1 }.bySource.getValue(SourceKind.YAML))
    }

    @Test
    fun `seeded C2 mutates the long rod label and conflicts by one inch without failing an invariant`() {
        val mutated = elevation.replace(">38\" Rod<", ">${FC.rodLabelMutated}<")
        val result = fuse(observations(elevationText = mutated))
        val conflict = assertIs<DimensionStatus.Conflict>(result.dimensions["rod.long.height"])
        assertTrue(conflict.pairs.all { it.difference == Rational(1) })
        assertEquals(allHold, verdicts(result))
    }

    @Test
    fun `mutations are applied to loaded text exactly once`() {
        assertEquals(1, Regex(Regex.escape("**33 7/16\"**")).findAll(tables).count())
        assertEquals(1, Regex(Regex.escape(">38\" Rod<")).findAll(elevation).count())
    }
}
