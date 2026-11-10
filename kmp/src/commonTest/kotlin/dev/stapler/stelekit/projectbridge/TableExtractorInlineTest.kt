package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.TableAlias
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals

/** Inline-string table so wasmJs and every other target execute the extractor once (no classpath resources). */
class TableExtractorInlineTest {
    @Test
    fun `inline table yields exact fraction`() {
        val registry = DimensionRegistry(
            dimensions = listOf(
                DimensionEntry("panel.width", DimensionKind.LENGTH, "Panel", DimensionAliases(table = listOf(TableAlias("panel", "Width")))),
            ),
        )
        val md = "- | Zone | Width |\n  |---|---|\n  | Panel | **33 7/16\"** |"
        val result = TableMeasurementExtractor(registry, RefTable()).extract(md, "inline")
        assertEquals(Rational(535, 16), result.observations.single().value)
    }
}
