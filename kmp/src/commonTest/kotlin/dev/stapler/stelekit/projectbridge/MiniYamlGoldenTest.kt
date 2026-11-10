package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inline copy of the sanitised closet fixture, so wasmJs (no resource access) executes it too. */
class MiniYamlGoldenTest {
    private val closet = """
# Closet - Parametric Dimension Config (sanitised fixture)
# All dimensions in inches

niche:
  width: 136          # wall face to wall face
  height_rear: 40.875 # 40 7/8"
  height_front: 43.3125 # 43 5/16" - ceiling slopes front-to-back
  height: 41          # working average used for rod positioning
  depth: 24           # usable
  wall_thickness: 0.75

lower_cabinet:
  width: 136
  height: 23.125      # 23 1/8" usable
  height_total: 25.5625  # 25 9/16" floor to niche
  depth: 24
  bay_width: 30.875   # 30 7/8" per door opening
  sill_height: 0.5    # front sill strip
  sill_depth: 2.375   # 2 3/8"

# Hanging rod dimensions
rod:
  radius: 0.625       # 1.25" OD
  color: [0.8, 0.65, 0.3]

# Shelf plank dimensions
shelf:
  thickness: 0.75     # 3/4"

zones:
  - name: "Left - Long Hang"
    short: "left-long"
    type: hang
    width: 33.4375    # 33 7/16" - left outer bay width
    rod_height: 38
    color: [0.68, 0.82, 0.95]
    pass_through: true
    note: "Combined hang: 41\" niche + 23\" cabinet = 62\" total"

  - name: "Left - Short Hang"
    short: "left-short"
    type: hang
    width: 22.5       # 22 1/2"
    rod_height: 36
    color: [0.80, 0.88, 0.98]

  - name: "Shared Shelves"
    short: "shelves"
    type: shelf
    width: 24.1875    # 24 3/16"
    shelf_count: 4              # 4 shelves -> 8.2" gaps
    color: [0.90, 0.88, 0.80]
    note: "Folded items and bins"

  - name: "Right - Short Hang"
    short: "right-short"
    type: hang
    width: 22.5       # 22 1/2"
    rod_height: 36
    color: [0.98, 0.85, 0.88]

  - name: "Right - Long Hang"
    short: "right-long"
    type: hang
    width: 33.375     # 33 3/8" - right outer bay width
    rod_height: 38
    color: [0.95, 0.75, 0.80]
    pass_through: true
    note: "Combined hang: 41\" niche + 23\" cabinet = 62\" total"

# Second-phase full-length hang math (for reference)
phase2_pass_through:
  niche_hang_depth: 39
  cabinet_depth: 23.125
  total_hang: 62.125        # 39 + 23.125
  full_dress_hang: 57       # 55-58" - clears floor by 5"
  long_coat_hang: 51        # 50-52" - clears floor by 11"
""".trimIndent()

    private fun paths(node: YamlNode, prefix: String = "", out: MutableMap<String, YamlScalar> = LinkedHashMap()): Map<String, YamlScalar> {
        when (node) {
            is YamlMap -> node.entries.forEach { (k, v) -> paths(v, if (prefix.isEmpty()) k else "$prefix.$k", out) }
            is YamlList -> node.items.forEachIndexed { i, v -> paths(v, "$prefix.$i", out) }
            is YamlScalar -> out[prefix] = node
        }
        return out
    }

    private val parsed: Map<String, YamlScalar> by lazy {
        paths(assertIs<MiniYamlResult.Parsed>(MiniYaml.parse(closet)).root)
    }

    @Test
    fun closet_should_ParseToExpectedPaths() {
        assertEquals("136", parsed["niche.width"]?.text)
        assertEquals(ScalarKind.DECIMAL, parsed["niche.height_rear"]?.kind)
        assertEquals("40 7/8\"", parsed["niche.height_rear"]?.comment)
        assertEquals("Left - Long Hang", parsed["zones.0.name"]?.text)
        assertEquals(ScalarKind.BOOL, parsed["zones.0.pass_through"]?.kind)
        assertEquals("shelves", parsed["zones.2.short"]?.text)
        assertEquals("4", parsed["zones.2.shelf_count"]?.text)
        assertEquals("0.95", parsed["zones.4.color.0"]?.text)
        assertEquals(62.125.toString(), parsed["phase2_pass_through.total_hang"]?.text)
    }

    @Test
    fun extractor_should_ReadMappedPathsOnce_andShareTheRefWithTheComment() {
        fun entry(key: String, kind: DimensionKind, path: String) =
            DimensionEntry(key, kind, key, DimensionAliases(yaml = listOf(path)))
        val registry = DimensionRegistry(
            dimensions = listOf(
                entry("niche.height_rear", DimensionKind.LENGTH, "niche.height_rear"),
                entry("shelf.count", DimensionKind.COUNT, "zones.2.shelf_count"),
                entry("zone.left.width", DimensionKind.LENGTH, "zones.0.width"),
            ),
        )
        val result = YamlMeasurementExtractor(registry, RefTable()).extract(closet, "snapshot")
        assertNull(result.unsupportedLine)
        assertEquals(3, result.observations.size)
        val byKey = result.observations.associateBy { it.dimension }
        assertEquals(Rational(327, 8), byKey.getValue("niche.height_rear").value)
        assertEquals(Rational(4), byKey.getValue("shelf.count").value)
        assertEquals(Rational(535, 16), byKey.getValue("zone.left.width").value)
        assertEquals(3, result.observations.map { it.source.ref }.distinct().size)
        assertTrue(result.observations.all { it.source.kind == dev.stapler.stelekit.fusion.SourceKind.YAML })
    }

    @Test
    fun unsupportedSnapshot_should_YieldNoObservationsAndALine() {
        val result = YamlMeasurementExtractor(DimensionRegistry(dimensions = emptyList()), RefTable()).extract("a: &x 1", "s")
        assertEquals(1, result.unsupportedLine)
        assertTrue(result.observations.isEmpty())
        assertNotNull(result)
    }
}
