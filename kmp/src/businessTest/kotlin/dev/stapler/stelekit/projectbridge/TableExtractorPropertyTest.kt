package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.TableAlias
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Round trip: render generated rows as a markdown table, extract, get the same exact values back. */
class TableExtractorPropertyTest {
    private class Row(val key: String, val value: Rational)

    private class Table(val rows: List<Row>, val bold: Boolean, val bulleted: Boolean)

    private val tables: Arb<Table> = arbitrary { rs ->
        val r = rs.random
        val rows = (0 until r.nextInt(1, 8)).map { i ->
            Row("part$i", Rational(r.nextLong(0, 4_000L), listOf(1L, 2L, 4L, 8L, 16L).random(r)))
        }
        Table(rows, r.nextBoolean(), r.nextBoolean())
    }

    private fun render(value: Rational, bold: Boolean): String {
        val whole = value.num / value.den
        val frac = value.num % value.den
        val text = when {
            frac == 0L -> "$whole\""
            whole == 0L -> "$frac/${value.den}\""
            else -> "$whole $frac/${value.den}\""
        }
        return if (bold) "**$text**" else text
    }

    private fun markdown(t: Table): String {
        val lines = listOf("| Measurement | Value |", "|---|---|") +
            t.rows.map { "| ${it.key} | ${render(it.value, t.bold)} |" }
        return if (t.bulleted) lines.mapIndexed { i, l -> if (i == 0) "- $l" else "  $l" }.joinToString("\n") else lines.joinToString("\n")
    }

    @Test
    fun `render then extract returns every row value exactly`() = runTest {
        checkAll(100, tables) { t ->
            val registry = DimensionRegistry(
                dimensions = t.rows.map {
                    DimensionEntry(it.key, DimensionKind.LENGTH, it.key, DimensionAliases(table = listOf(TableAlias(it.key, "Value"))))
                },
            )
            val result = TableMeasurementExtractor(registry, RefTable()).extract(markdown(t), "p")
            assertEquals(t.rows.associate { it.key to it.value }, result.observations.associate { it.dimension to it.value })
            assertEquals(emptyList(), result.needsMapping)
        }
    }

    @Test
    fun `never throws on arbitrary text`() = runTest {
        checkAll(200, Arb.string(0..200)) { s ->
            TableMeasurementExtractor(DimensionRegistry(dimensions = emptyList()), RefTable()).extract(s, "p")
        }
    }
}
