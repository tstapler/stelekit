package dev.stapler.stelekit.fixtures

import dev.stapler.stelekit.fusion.Constraint
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Equal
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.Offset
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceRef
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseLength
import kotlin.test.assertIs

/** Closet observations and constraints built from the fixture texts; shared by the fusion and digest tests. */
object ClosetFusion {
    val registry: DimensionRegistry = DimensionRegistry.fromJson(ClosetFixtureText.load("dimension-map.json"))
        .fold({ error("dimension-map.json failed to load: $it") }, { it })

    val tables: String = ClosetFixtureText.load("closet-tables.md")
    val elevation: String = ClosetFixtureText.load("closet-elevation.svg")

    val zones = listOf(
        "zone.left-long.width", "zone.left-short.width", "zone.shelves.width", "zone.right-short.width", "zone.right-long.width",
    )
    val tableRows = listOf(
        "Left - long hang (pass-through)", "Left - short hang", "Shelves (shared)", "Right - short hang", "Right - long hang (pass-through)",
    )
    private val yamlZoneWidths = listOf("33.4375", "22.5", "24.1875", "22.5", "33.375")

    fun len(dimension: String, text: String, kind: SourceKind, ref: String = dimension): Observation {
        val measured = assertIs<ParsedLength.Measured>(parseLength(text).fold({ error("'$text' failed: $it") }, { it }))
        return Observation(dimension, measured.value, measured.roundingPrecision, DimensionKind.LENGTH, Source(kind, SourceRef(kind, ref)))
    }

    fun exact(dimension: String, value: Rational, kind: SourceKind, ref: String = dimension) =
        Observation(dimension, value, Rational.ZERO, DimensionKind.LENGTH, Source(kind, SourceRef(kind, ref)))

    fun r(num: Long, den: Long = 1) = Rational(num, den)

    fun add(a: Rational, b: Rational) = a.add(b).getOrNull()!!

    fun tableZone(markdown: String, row: String): String =
        ClosetFixtureText.tableCells(markdown).single { it.rowKey == row && it.header == "Width" }.value

    fun observations(tablesText: String = tables, elevationText: String = elevation): List<Observation> {
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

    fun constraints(): List<Constraint> = buildList {
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
}
