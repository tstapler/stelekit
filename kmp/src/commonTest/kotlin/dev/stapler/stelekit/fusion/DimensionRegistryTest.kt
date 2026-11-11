package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Inline registry with invented keys and aliases. */
class DimensionRegistryTest {
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry(
                key = "panel.width",
                kind = DimensionKind.LENGTH,
                label = "Panel width",
                aliases = DimensionAliases(
                    table = listOf(TableAlias("panel*", "Width")),
                    yaml = listOf("panel.width"),
                    svgLabel = listOf("Panel W"),
                    svgGeom = listOf("panel-rect"),
                    prose = listOf("the panel is"),
                ),
            ),
            DimensionEntry(
                key = "panel.height",
                kind = DimensionKind.LENGTH,
                label = "Panel height",
                aliases = DimensionAliases(table = listOf(TableAlias("panel*", "Height")), svgLabel = listOf("Panel H")),
            ),
            DimensionEntry(
                key = "shelf.width",
                kind = DimensionKind.LENGTH,
                label = "Shelf width",
                aliases = DimensionAliases(table = listOf(TableAlias("shelf", "Width")), svgLabel = listOf("W")),
            ),
            DimensionEntry(key = "radius", kind = DimensionKind.LENGTH, label = "Radius", aliases = DimensionAliases(yaml = listOf("pipe.radius"))),
            DimensionEntry(key = "diameter", kind = DimensionKind.LENGTH, label = "Diameter", derivation = "radius*2"),
            DimensionEntry(key = "bracket.count", kind = DimensionKind.COUNT, label = "Brackets", aliases = DimensionAliases(yaml = listOf("brackets"))),
        ),
    )

    @Test
    fun lookup_should_ResolveEachSourceKind() {
        assertEquals(Resolution.Resolved("panel.width"), registry.resolve(Locator.Table("Panel A", "width")))
        assertEquals(Resolution.Resolved("panel.height"), registry.resolve(Locator.Table("panel b", "Height")))
        assertEquals(Resolution.Resolved("panel.width"), registry.resolve(Locator.Yaml("Panel.Width")))
        assertEquals(Resolution.Resolved("panel.height"), registry.resolve(Locator.SvgLabel("Panel H 33")))
        assertEquals(Resolution.Resolved("panel.width"), registry.resolve(Locator.SvgGeom("panel-rect")))
        assertEquals(Resolution.Resolved("panel.width"), registry.resolve(Locator.Prose("Note: the panel is 30 wide")))
        assertEquals(Resolution.Resolved("bracket.count"), registry.resolve(Locator.Yaml("brackets")))
    }

    @Test
    fun ambiguousOrUnknownAlias_should_NeedMappingAndNeverGuess() {
        // "Panel W" matches the panel hint and also contains the shelf hint "W".
        assertEquals(
            Resolution.NeedsMapping(listOf("panel.width", "shelf.width")),
            registry.resolve(Locator.SvgLabel("Panel W")),
        )
        assertEquals(Resolution.NeedsMapping(emptyList()), registry.resolve(Locator.Yaml("unknown.path")))
        assertEquals(Resolution.NeedsMapping(emptyList()), registry.resolve(Locator.Table("Other", "Width")))
    }

    @Test
    fun derivation_should_EvaluateExactlyAndCarryPrecision() {
        val expr = Derivation.parse("radius*2").getOrNull()!!
        val result = Derivation.evaluate(expr) { Quantity(Rational(5, 8), Rational(1, 20)) }.getOrNull()!!
        assertEquals(Rational(5, 4), result.value)
        assertEquals(Rational(1, 10), result.precision)
        val mixed = Derivation.parse("({a-b.c} + 1.5) / 4 - -1/2").getOrNull()!!
        val value = Derivation.evaluate(mixed) { Quantity(Rational(5), Rational.ZERO) }.getOrNull()!!
        assertEquals(Rational(17, 8), value.value)
    }

    @Test
    fun derivation_should_RejectBadInput() {
        assertIs<DerivationError.Syntax>(Derivation.parse("radius*").leftOrNull())
        assertIs<DerivationError.Syntax>(Derivation.parse("radius 2").leftOrNull())
        assertIs<DerivationError.Syntax>(Derivation.parse("exec(\"x\")").leftOrNull())
        assertEquals(DerivationError.DivideByZero, Derivation.evaluate(Derivation.parse("1/0").getOrNull()!!) { null }.leftOrNull())
        assertEquals(DerivationError.UnknownKey("q"), Derivation.evaluate(Derivation.parse("q+1").getOrNull()!!) { null }.leftOrNull())
    }

    @Test
    fun validation_should_RejectCyclesUnknownKeysDuplicatesAndOtherSchemas() {
        fun entry(key: String, derivation: String?) = DimensionEntry(key, DimensionKind.LENGTH, key, derivation = derivation)
        val cycle = DimensionRegistry(dimensions = listOf(entry("a", "b+1"), entry("b", "a+1"))).validate().leftOrNull()
        assertEquals(RegistryError.BadDerivation("a", DerivationError.Cycle(listOf("a", "b"))), cycle)
        assertEquals(
            RegistryError.BadDerivation("a", DerivationError.UnknownKey("zzz")),
            DimensionRegistry(dimensions = listOf(entry("a", "zzz*2"))).validate().leftOrNull(),
        )
        assertEquals(RegistryError.DuplicateKey("a"), DimensionRegistry(dimensions = listOf(entry("a", null), entry("a", null))).validate().leftOrNull())
        assertEquals(RegistryError.UnsupportedSchema(2), DimensionRegistry(schema = 2, dimensions = emptyList()).validate().leftOrNull())
        assertTrue(registry.validate().isRight())
    }

    @Test
    fun json_should_RoundTripAndRejectGarbage() {
        val text = DimensionRegistry.toJson(registry)
        assertTrue(text.contains("\"schema\":1") || text.contains("\"schema\": 1"), text)
        assertTrue(text.contains("\"length\"") && text.contains("\"count\""))
        assertEquals(registry, DimensionRegistry.fromJson(text).getOrNull())
        assertEquals(RegistryError.InvalidJson, DimensionRegistry.fromJson("{not json").leftOrNull())
        assertEquals(RegistryError.InvalidJson, DimensionRegistry.fromJson("{\"dimensions\":[{\"key\":\"a\",\"kind\":\"weight\",\"label\":\"A\"}]}").leftOrNull())
        assertEquals(RegistryError.UnsupportedSchema(9), DimensionRegistry.fromJson("{\"schema\":9,\"dimensions\":[]}").leftOrNull())
    }

    @Test
    fun derivedInOrder_should_ListDependenciesFirst() {
        val chained = DimensionRegistry(
            dimensions = listOf(
                DimensionEntry("c", DimensionKind.LENGTH, "c", derivation = "b+1"),
                DimensionEntry("b", DimensionKind.LENGTH, "b", derivation = "a+1"),
                DimensionEntry("a", DimensionKind.LENGTH, "a"),
            ),
        )
        assertEquals(listOf("b", "c"), chained.derivedInOrder().map { it.key })
    }

    @Test
    fun refs_should_BeOpaqueAndResolvableOnlyThroughTheLocalTable() {
        val table = RefTable()
        val locator = "Some Page Title/row 3/Width"
        val ref = table.refFor(SourceKind.TABLE, locator)
        assertEquals(ref, table.refFor(SourceKind.TABLE, locator))
        assertNotEquals(ref, table.refFor(SourceKind.YAML, locator))
        assertTrue(ref.toString().startsWith("table:"))
        assertEquals("table:".length + 12, ref.toString().length)
        assertTrue(listOf("Some", "Page", "Title", "Width").none { ref.toString().contains(it, ignoreCase = true) })
        assertEquals(locator, table.describe(ref))
        assertEquals(null, RefTable().describe(ref))
    }

    private operator fun Rational.plus(other: Rational): Rational = add(other).getOrNull()!!
}
