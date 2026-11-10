package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.fixtures.ClosetFixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.5b-1: the closet's `dimension-map.json` loads through [DimensionRegistry] and resolves every fixture key. */
class FixtureRegistryTest {
    private val registry: DimensionRegistry = DimensionRegistry.fromJson(ClosetFixtureText.load("dimension-map.json"))
        .fold({ error("dimension-map.json failed to load: $it") }, { it })

    private val tableCells = ClosetFixtureText.tableCells(ClosetFixtureText.load("closet-tables.md"))
    private val yaml = ClosetFixtureText.load("closet.yaml")
    private val svgLabels = ClosetFixtureText.svgLabels(ClosetFixtureText.load("closet-elevation.svg")) +
        ClosetFixtureText.svgLabels(ClosetFixtureText.load("closet-section.svg"))

    private val fifteenDimensions = listOf(
        "niche.width", "zone.left-long.width", "zone.left-short.width", "zone.shelves.width", "zone.right-short.width",
        "zone.right-long.width", "niche.height.rear", "niche.height.front", "rod.short.height", "rod.long.height",
        "niche.depth", "lowercab.height", "lowercab.bay", "shelf.gap", "standards.positions",
    )

    private fun sourceKinds(entry: DimensionEntry): Int = with(entry.aliases) {
        listOf(table, yaml, svgLabel, svgGeom, prose).count { it.isNotEmpty() }
    }

    @Test
    fun `registry is schema 1 with unique keys and valid derivations`() {
        assertEquals(DimensionRegistry.SCHEMA, registry.schema)
        assertEquals(registry.dimensions.size, registry.dimensions.map { it.key }.toSet().size)
        assertEquals("rod.radius*2", registry.entry("rod.diameter")?.derivation)
        assertEquals(DimensionKind.COUNT, registry.entry("rod-carrier.count")?.kind)
    }

    @Test
    fun `the fifteen corpus dimensions each map at least two source kinds`() {
        fifteenDimensions.forEach { key ->
            val entry = registry.entry(key) ?: error("registry lacks $key")
            assertTrue(sourceKinds(entry) >= 2, "$key maps ${sourceKinds(entry)} source kind(s)")
        }
    }

    @Test
    fun `every table cell the registry names resolves to exactly its dimension`() {
        val resolved = tableCells.mapNotNull { cell ->
            (registry.resolve(Locator.Table(cell.rowKey, cell.header)) as? Resolution.Resolved)?.let { cell to it.key }
        }
        val expected = mapOf(
            ("Opening width" to "Value") to "niche.width",
            ("Left - long hang (pass-through)" to "Width") to "zone.left-long.width",
            ("Left - short hang" to "Width") to "zone.left-short.width",
            ("Shelves (shared)" to "Width") to "zone.shelves.width",
            ("Right - short hang" to "Width") to "zone.right-short.width",
            ("Right - long hang (pass-through)" to "Width") to "zone.right-long.width",
            ("Left - long hang (pass-through)" to "Rod height") to "rod.long.height",
            ("Right - long hang (pass-through)" to "Rod height") to "rod.long.height",
            ("Left - short hang" to "Rod height") to "rod.short.height",
            ("Right - short hang" to "Rod height") to "rod.short.height",
            ("Shelves (shared)" to "Contents") to "shelf.gap",
            ("Height at rear" to "Value") to "niche.height.rear",
            ("Height at front" to "Value") to "niche.height.front",
            ("Usable depth" to "Value") to "niche.depth",
            ("Rod at niche ceiling" to "") to "hang.niche",
            ("+ lower cabinet height" to "") to "lowercab.height",
            ("Total hang depth" to "") to "hang.total",
        )
        assertEquals(expected, resolved.associate { (cell, key) -> (cell.rowKey to cell.header) to key })
    }

    @Test
    fun `every table alias in the registry is realised by a cell in the tables`() {
        registry.dimensions.flatMap { entry -> entry.aliases.table.map { entry.key to it } }.forEach { (key, alias) ->
            val hit = tableCells.any { registry.resolve(Locator.Table(it.rowKey, it.header)) == Resolution.Resolved(key) && it.header == alias.header }
            assertTrue(hit, "$key table alias (${alias.rowKeyPattern}, ${alias.header}) matches no cell")
        }
    }

    @Test
    fun `every yaml alias names a key present in the yaml and resolves`() {
        registry.dimensions.flatMap { entry -> entry.aliases.yaml.map { entry.key to it } }.forEach { (key, path) ->
            assertEquals(Resolution.Resolved(key), registry.resolve(Locator.Yaml(path)), path)
            val segments = path.split('.')
            val leaf = segments.last()
            assertTrue(Regex("(?m)^\\s*$leaf:").containsMatchIn(yaml), "yaml has no key $leaf for $path")
            if (segments.first() == "zones") assertTrue("short: \"${segments[1]}\"" in yaml, "yaml has no zone ${segments[1]}")
        }
    }

    @Test
    fun `every svg label hint matches a label and each label resolves to at most one dimension`() {
        registry.dimensions.flatMap { entry -> entry.aliases.svgLabel.map { entry.key to it } }.forEach { (key, hint) ->
            val label = svgLabels.firstOrNull { hint in it } ?: error("no svg label contains '$hint' for $key")
            assertEquals(Resolution.Resolved(key), registry.resolve(Locator.SvgLabel(label)), label)
        }
        svgLabels.forEach { label ->
            val resolution = registry.resolve(Locator.SvgLabel(label))
            assertTrue(resolution is Resolution.Resolved || (resolution as Resolution.NeedsMapping).candidates.isEmpty(), "ambiguous label '$label'")
        }
    }

    @Test
    fun `an ambiguous or unknown locator needs mapping and is never guessed`() {
        val ambiguous = registry.resolve(Locator.SvgLabel("38\" Rod and 36\" Rod"))
        assertEquals(Resolution.NeedsMapping(listOf("rod.long.height", "rod.short.height")), ambiguous)
        assertEquals(Resolution.NeedsMapping(emptyList()), registry.resolve(Locator.Yaml("niche.unknown")))
        assertIs<Resolution.NeedsMapping>(registry.resolve(Locator.Table("Left - long hang (pass-through)", "Contents")))
    }

    @Test
    fun `the rod diameter derives from the radius and a malformed map is rejected`() {
        val derived = registry.derivedInOrder().map { it.key }
        assertEquals(listOf("rod.diameter"), derived)
        val broken = ClosetFixtureText.load("dimension-map.json").replace("rod.radius*2", "rod.nonexistent*2")
        assertIs<RegistryError.BadDerivation>(DimensionRegistry.fromJson(broken).leftOrNull())
    }
}
