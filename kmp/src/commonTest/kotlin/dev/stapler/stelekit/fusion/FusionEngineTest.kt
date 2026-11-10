package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseLength
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every dimension name and number here is invented. */
class FusionEngineTest {
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("radius", DimensionKind.LENGTH, "Radius"),
            DimensionEntry("diameter", DimensionKind.LENGTH, "Diameter", derivation = "radius*2"),
            DimensionEntry("carrier.count", DimensionKind.COUNT, "Carriers"),
        ),
    )

    private fun reading(
        dimension: String,
        text: String,
        kind: SourceKind,
        id: String = "$dimension-${kind.name}",
        origin: SourceOrigin = SourceOrigin(),
    ): Observation {
        val measured = assertIs<ParsedLength.Measured>(parseLength(text).fold({ error("'$text' failed with $it") }, { it }))
        return Observation(
            dimension, measured.value, measured.roundingPrecision, DimensionKind.LENGTH,
            Source(kind, SourceRef(kind, id), origin),
        )
    }

    private fun count(dimension: String, value: Long, kind: SourceKind, precision: Rational = Rational.ZERO) =
        Observation(dimension, Rational(value), precision, DimensionKind.COUNT, Source(kind, SourceRef(kind, "$dimension-$kind")))

    private fun fuse(input: FusionInput): FusionResult =
        fuseObservations(input, registry).fold({ error("fusion failed: $it") }, { it })

    private fun fuse(vararg observations: Observation) = fuse(FusionInput(observations.toList()))

    @Test
    fun storedLossyDecimal_should_StayExact() {
        val result = fuse(reading("w", "37.9\"", SourceKind.TABLE))
        assertEquals(DimensionStatus.SingleSource(Rational(379, 10), SourceRef(SourceKind.TABLE, "w-TABLE")), result.dimensions.getValue("w"))
    }

    @Test
    fun threeAgreeingSources_should_BeConfirmed() {
        val result = fuse(
            reading("w", "30 3/16\"", SourceKind.TABLE),
            reading("w", "30 3/16\"", SourceKind.YAML),
            reading("w", "30.1875\"", SourceKind.SVG_LABEL),
        )
        assertEquals(DimensionStatus.Confirmed(Rational(483, 16), 3), result.dimensions.getValue("w"))
    }

    @Test
    fun mutatedTableValue_should_ConflictNamingBothSourcesAndFailTheSumInvariant() {
        val zones = listOf("z1", "z2")
        val constraints = listOf(Sum(0, "total", zones))
        val agreed = listOf(
            reading("z1", "20\"", SourceKind.TABLE), reading("z1", "20\"", SourceKind.YAML),
            reading("z2", "30 3/16\"", SourceKind.YAML), reading("z2", "30 3/16\"", SourceKind.SVG_LABEL),
            reading("total", "50 3/16\"", SourceKind.TABLE), reading("total", "50 3/16\"", SourceKind.YAML),
        )
        val clean = fuse(FusionInput(agreed + reading("z2", "30 3/16\"", SourceKind.TABLE), constraints))
        assertEquals(InvariantVerdict.HOLDS, clean.invariants.single().verdict)

        val mutated = fuse(FusionInput(agreed + reading("z2", "30 1/4\"", SourceKind.TABLE), constraints))
        val conflict = assertIs<DimensionStatus.Conflict>(mutated.dimensions.getValue("z2"))
        assertEquals(2, conflict.pairs.size)
        assertTrue(conflict.pairs.all { it.difference == Rational(1, 16) })
        assertTrue(conflict.pairs.all { pair -> SourceKind.TABLE in listOf(pair.first.source.kind, pair.second.source.kind) })
        val invariant = mutated.invariants.single()
        assertEquals(InvariantVerdict.FAILS, invariant.verdict)
        assertEquals(InvariantVerdict.FAILS, invariant.bySource.getValue(SourceKind.TABLE))
        assertEquals(InvariantVerdict.HOLDS, invariant.bySource.getValue(SourceKind.YAML))
    }

    @Test
    fun lossyDecimalAgainstAFraction_should_NotConflictByPrecisionLogic() {
        // 27.31 is 27.3125 to two places; 37.9 is within 0.05 of 37 7/8. Neither is rounded to sixteenths.
        val a = fuse(reading("a", "27.31\"", SourceKind.SVG_LABEL), reading("a", "27 5/16\"", SourceKind.TABLE))
        assertEquals(DimensionStatus.Confirmed(Rational(437, 16), 2), a.dimensions.getValue("a"))
        val b = fuse(reading("b", "37.9\"", SourceKind.SVG_LABEL), reading("b", "37 7/8\"", SourceKind.YAML))
        assertEquals(DimensionStatus.Confirmed(Rational(303, 8), 2), b.dimensions.getValue("b"))
    }

    @Test
    fun lossyDecimalBeyondItsPrecision_and_AdjacentFractions_should_Conflict() {
        val far = fuse(reading("a", "37.9\"", SourceKind.SVG_LABEL), reading("a", "37 13/16\"", SourceKind.TABLE))
        assertIs<DimensionStatus.Conflict>(far.dimensions.getValue("a"))
        val adjacent = fuse(reading("b", "33 7/16\"", SourceKind.TABLE), reading("b", "33 3/8\"", SourceKind.YAML))
        assertEquals(Rational(1, 16), assertIs<DimensionStatus.Conflict>(adjacent.dimensions.getValue("b")).pairs.single().difference)
    }

    @Test
    fun counts_should_ConflictByEqualityIgnoringPrecision() {
        val result = fuse(
            count("carrier.count", 4, SourceKind.YAML),
            count("carrier.count", 5, SourceKind.TABLE, precision = Rational(2)),
            count("carrier.count", 4, SourceKind.PROSE),
        )
        val conflict = assertIs<DimensionStatus.Conflict>(result.dimensions.getValue("carrier.count"))
        assertEquals(setOf(Rational(1)), conflict.pairs.map { it.difference }.toSet())
        val agree = fuse(count("carrier.count", 4, SourceKind.YAML), count("carrier.count", 4, SourceKind.PROSE))
        assertEquals(DimensionStatus.Confirmed(Rational(4), 2), agree.dimensions.getValue("carrier.count"))
    }

    @Test
    fun derivedDimension_should_FuseWithAProseReading() {
        val base = reading("radius", "5/8\"", SourceKind.YAML)
        val base2 = reading("radius", "5/8\"", SourceKind.TABLE)
        val agrees = fuse(base, base2, reading("diameter", "1 1/4\"", SourceKind.PROSE))
        assertEquals(DimensionStatus.Confirmed(Rational(5, 4), 2), agrees.dimensions.getValue("diameter"))
        val conflicts = fuse(base, base2, reading("diameter", "1 3/8\"", SourceKind.PROSE))
        val pair = assertIs<DimensionStatus.Conflict>(conflicts.dimensions.getValue("diameter")).pairs.single()
        assertEquals(Rational(1, 8), pair.difference)
        assertEquals(setOf(SourceKind.PROSE, SourceKind.DERIVED), setOf(pair.first.source.kind, pair.second.source.kind))
        val derivedOnly = fuse(base, base2)
        assertEquals(DimensionStatus.Derivable(Rational(5, 4), true, emptyList()), derivedOnly.dimensions.getValue("diameter"))
    }

    @Test
    fun digestMarkedSources_should_BeDiscardedAtEveryLevelAndCounted() {
        val page = SourceOrigin(pagePropertyKeys = setOf("stelekit-digest"))
        val firstBlock = SourceOrigin(firstBlockPropertyKeys = setOf("stelekit-digest::"))
        val block = SourceOrigin(blockPropertyKeys = setOf("Stelekit-Digest"))
        val result = fuse(
            reading("w", "30\"", SourceKind.YAML),
            reading("w", "31\"", SourceKind.TABLE, id = "a", origin = page),
            reading("w", "32\"", SourceKind.TABLE, id = "b", origin = firstBlock),
            reading("w", "33\"", SourceKind.TABLE, id = "c", origin = block),
            reading("w", "34\"", SourceKind.TABLE, id = "d", origin = SourceOrigin(pagePropertyKeys = setOf("type"))),
        )
        assertEquals(3, result.excludedSelf)
        val conflict = assertIs<DimensionStatus.Conflict>(result.dimensions.getValue("w"))
        assertEquals(Rational(4), conflict.pairs.single().difference)
    }

    @Test
    fun sameNormalisedRef_should_CountAsOneSource() {
        val result = fuse(
            reading("w", "30\"", SourceKind.YAML, id = "ABC"),
            reading("w", "30\"", SourceKind.YAML, id = " abc "),
        )
        assertIs<DimensionStatus.SingleSource>(result.dimensions.getValue("w"))
    }

    @Test
    fun reversedSvgLabels_should_WarnMirroredViewWithoutANumericConflict() {
        val zones = listOf("za", "zb", "zc")
        val table = listOf(20, 30, 25)
        // The drawing is the same wall seen from the other side, so labels bound by position disagree.
        val svgBoundByPosition = listOf(25, 30, 20)
        val observations = zones.indices.flatMap { i ->
            listOf(
                reading(zones[i], "${table[i]}\"", SourceKind.TABLE),
                reading(zones[i], "${table[i]}\"", SourceKind.YAML),
                reading(zones[i], "${svgBoundByPosition[i]}\"", SourceKind.SVG_LABEL),
            )
        }
        val labels = svgBoundByPosition.mapIndexed { i, v -> SvgLabel(Rational(i * 100L), Rational(v.toLong())) }
        val mirrored = fuse(FusionInput(observations, zoneOrder = zones, svgLabels = labels))
        assertEquals(setOf(FusionWarning.MIRRORED_VIEW), mirrored.warnings)
        assertTrue(mirrored.dimensions.values.none { it is DimensionStatus.Conflict })

        val sameOrder = labels.mapIndexed { i, l -> l.copy(value = Rational(table[i].toLong())) }
        val plain = fuse(FusionInput(observations, zoneOrder = zones, svgLabels = sameOrder))
        assertEquals(emptySet(), plain.warnings)
        assertTrue(plain.dimensions.values.any { it is DimensionStatus.Conflict })

        val symmetric = listOf(20, 30, 20).mapIndexed { i, v -> SvgLabel(Rational(i * 100L), Rational(v.toLong())) }
        val symmetricTable = zones.indices.map { i -> reading(zones[i], "${listOf(20, 30, 20)[i]}\"", SourceKind.TABLE) }
        assertEquals(emptySet(), fuse(FusionInput(symmetricTable, zoneOrder = zones, svgLabels = symmetric)).warnings)
    }

    @Test
    fun structure_should_DeriveUnreadDimensionsAndReportNonRepresentableOnes() {
        val sum = fuse(
            FusionInput(
                listOf(reading("a", "20\"", SourceKind.YAML), reading("b", "30\"", SourceKind.YAML)),
                constraints = listOf(Sum(0, "total", listOf("a", "b"))),
            ),
        )
        assertEquals(DimensionStatus.Derivable(Rational(50), false, listOf(0)), sum.dimensions.getValue("total"))

        val split = fuse(
            FusionInput(
                listOf(reading("total", "136\"", SourceKind.YAML)),
                constraints = listOf(Sum(0, "total", List(5) { "zone" })),
            ),
        )
        assertEquals(DimensionStatus.NotRepresentableValue(Rational(136, 5), Rational(1, 80)), split.dimensions.getValue("zone"))

        val free = fuse(FusionInput(listOf(reading("a", "20\"", SourceKind.YAML)), constraints = listOf(Sum(0, "total", listOf("a", "b")))))
        assertEquals(DimensionStatus.UnderConstrained, free.dimensions.getValue("b"))
    }

    // Shuffling observation order never changes the result.
    @Test
    fun result_should_NotDependOnObservationOrder() = runTest {
        val observations = listOf(
            reading("radius", "5/8\"", SourceKind.YAML), reading("radius", "5/8\"", SourceKind.TABLE),
            reading("diameter", "1 3/8\"", SourceKind.PROSE), reading("diameter", "1 1/4\"", SourceKind.TABLE),
            reading("a", "20\"", SourceKind.YAML), reading("a", "20 1/16\"", SourceKind.TABLE), reading("a", "20\"", SourceKind.SVG_LABEL),
            reading("b", "30\"", SourceKind.YAML), reading("w", "37.9\"", SourceKind.SVG_LABEL), reading("w", "37 7/8\"", SourceKind.TABLE),
            reading("x", "9\"", SourceKind.PROSE, origin = SourceOrigin(pagePropertyKeys = setOf("stelekit-digest"))),
            count("carrier.count", 4, SourceKind.YAML), count("carrier.count", 6, SourceKind.TABLE),
        )
        val constraints = listOf(Sum(0, "total", listOf("a", "b")), Equal(1, "q", "a"))
        val expected = fuse(FusionInput(observations, constraints))
        val shuffles: Arb<List<Observation>> = arbitrary { rs -> observations.shuffled(rs.random) }
        checkAll(shuffles) { shuffled ->
            assertEquals(expected, fuse(FusionInput(shuffled, constraints.reversed())))
        }
    }
}
