package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.fixtures.ClosetFixtureText
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
    private val registry = DimensionRegistry.fromJson(ClosetFixtureText.load("dimension-map.json"))
        .fold({ error("dimension-map.json failed to load: $it") }, { it })

    private val tables = ClosetFixtureText.load("closet-tables.md")
    private val elevation = ClosetFixtureText.load("closet-elevation.svg")

    private val zones = listOf(
        "zone.left-long.width", "zone.left-short.width", "zone.shelves.width", "zone.right-short.width", "zone.right-long.width",
    )
    private val tableRows = listOf(
        "Left - long hang (pass-through)", "Left - short hang", "Shelves (shared)", "Right - short hang", "Right - long hang (pass-through)",
    )
    private val yamlZoneWidths = listOf("33.4375", "22.5", "24.1875", "22.5", "33.375")

    private fun len(dimension: String, text: String, kind: SourceKind, ref: String = dimension): Observation {
        val measured = assertIs<ParsedLength.Measured>(parseLength(text).fold({ error("'$text' failed: $it") }, { it }))
        return Observation(dimension, measured.value, measured.roundingPrecision, DimensionKind.LENGTH, Source(kind, SourceRef(kind, ref)))
    }

    private fun exact(dimension: String, value: Rational, kind: SourceKind, ref: String = dimension) =
        Observation(dimension, value, Rational.ZERO, DimensionKind.LENGTH, Source(kind, SourceRef(kind, ref)))

    private fun r(num: Long, den: Long = 1) = Rational(num, den)

    private fun add(a: Rational, b: Rational) = a.add(b).getOrNull()!!

    private fun tableZone(markdown: String, row: String): String =
        ClosetFixtureText.tableCells(markdown).single { it.rowKey == row && it.header == "Width" }.value

    private fun observations(tablesText: String = tables, elevationText: String = elevation): List<Observation> {
        val labels = ClosetFixtureText.svgLabels(elevationText)
        val out = ArrayList<Observation>()
        zones.forEachIndexed { i, key ->
            out += len(key, yamlZoneWidths[i], SourceKind.YAML)
            out += len(key, tableZone(tablesText, tableRows[i]), SourceKind.TABLE)
            out += len(key, labels[i], SourceKind.SVG_LABEL, "$key-elevation") // file order equals table order; x order is mirrored
        }
        val rodLabel = labels.first { it.endsWith(" Rod") }.removeSuffix(" Rod")
        val shortRodLabel = labels.last { it.endsWith(" Rod") }.removeSuffix(" Rod")
        out += listOf(
            len("niche.width", "136", SourceKind.YAML), len("niche.width", "136\"", SourceKind.TABLE), len("niche.width", "136\"", SourceKind.SVG_LABEL),
            len("niche.width.ft", "11' 4\"", SourceKind.TABLE),
            len("niche.height.rear", "40.875", SourceKind.YAML), len("niche.height.rear", "40 7/8\"", SourceKind.TABLE),
            len("niche.height.rear", "40.88\"", SourceKind.SVG_LABEL),
            len("niche.height.front", "43.3125", SourceKind.YAML), len("niche.height.front", "43 5/16\"", SourceKind.TABLE),
            len("niche.height.front", "43.31\"", SourceKind.SVG_LABEL),
            len("rod.short.height", "36", SourceKind.YAML), len("rod.short.height", "36\"", SourceKind.TABLE),
            len("rod.short.height", shortRodLabel, SourceKind.SVG_LABEL), len("rod.short.height", "36\"", SourceKind.PROSE),
            len("rod.long.height", "38", SourceKind.YAML), len("rod.long.height", "38\"", SourceKind.TABLE),
            len("rod.long.height", rodLabel, SourceKind.SVG_LABEL),
            len("niche.depth", "24", SourceKind.YAML), len("niche.depth", "24\"", SourceKind.TABLE), len("niche.depth", "24\"", SourceKind.SVG_LABEL),
            len("lowercab.height", "23.125", SourceKind.YAML), len("lowercab.height", "23 1/8\"", SourceKind.TABLE),
            len("lowercab.height", "23.12\"", SourceKind.SVG_LABEL),
            len("lowercab.bay", "30.875", SourceKind.YAML), len("lowercab.bay", "30 7/8\"", SourceKind.PROSE),
            len("shelf.gap", "8.2\"", SourceKind.TABLE), len("shelf.gap", "8.2\"", SourceKind.PROSE), len("shelf.gap", "8.2\"", SourceKind.SVG_GEOMETRY),
            len("niche.height.avg", "41", SourceKind.YAML),
            len("hang.short.length", "28\"", SourceKind.PROSE),
            len("hang.niche", "39", SourceKind.YAML), len("hang.total", "62.125", SourceKind.YAML),
            len("zone.middle", "69 3/16\"", SourceKind.TABLE),
        )
        val positions = listOf(Rational.ZERO, FC.zoneLeftLong, r(895, 16), r(641, 8), r(821, 8), FC.opening)
        positions.forEachIndexed { i, value ->
            val key = "standards.positions.$i"
            out += exact(key, value, SourceKind.TABLE)
            out += exact(key, value, SourceKind.SVG_GEOMETRY)
        }
        listOf(2 to "55.9\"", 3 to "80.1\"", 4 to "102.6\"").forEach { (i, text) -> out += len("standards.positions.$i", text, SourceKind.PROSE) }
        out += exact("standards.positions.1", FC.zoneLeftLong, SourceKind.PROSE)
        FC.studs.forEachIndexed { i, value -> out += exact("stud.${i + 1}", value, SourceKind.TABLE) }
        listOf("7.825\"", "16.025\"", "24.225\"", "32.425\"").forEachIndexed { i, text -> out += len("shelf.pos.${i + 1}", text, SourceKind.SVG_GEOMETRY) }
        return out
    }

    private val invariantIds: Map<String, List<Int>> = mapOf(
        "I1" to listOf(1), "I2" to listOf(2), "I3" to listOf(3), "I4" to (4..8).toList(), "I5" to listOf(9, 10, 11),
        "I6" to (12..20).toList(), "I7" to listOf(21), "I8" to listOf(22), "I9" to listOf(23, 24, 25),
    )

    private fun constraints(): List<Constraint> = buildList {
        val (ll, ls, sh, rs, rl) = zones
        add(Sum(1, "niche.width", zones))
        add(Sum(2, "zone.middle", listOf(ls, sh, rs)))
        add(Sum(3, "niche.width", listOf(ll, "zone.middle", rl)))
        add(Equal(4, "standards.positions.1", ll))
        add(Sum(5, "standards.positions.2", listOf("standards.positions.1", ls)))
        add(Sum(6, "standards.positions.3", listOf("standards.positions.2", sh)))
        add(Sum(7, "standards.positions.4", listOf("standards.positions.3", rs)))
        add(Sum(8, "standards.positions.5", listOf("standards.positions.4", rl)))
        add(Offset(9, "niche.height.avg", "rod.short.height", r(FC.rodClearances[0].toLong())))
        add(Offset(10, "niche.height.avg", "rod.long.height", r(FC.rodClearances[1].toLong())))
        add(Offset(11, "rod.short.height", "hang.short.length", r(FC.rodClearances[2].toLong())))
        for (i in 1..8) add(Offset(11 + i, "stud.${i + 1}", "stud.$i", r(16)))
        add(Offset(20, "stud.3", "standards.positions.1", Rational(1, 16)))
        add(Equal(21, "niche.width", "niche.width.ft"))
        add(Sum(22, "hang.total", listOf("hang.niche", "lowercab.height")))
        for (i in 1..3) add(Offset(22 + i, "shelf.pos.${i + 1}", "shelf.pos.$i", FC.shelfPitch))
    }

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
