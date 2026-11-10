package dev.stapler.stelekit.projectbridge

import arrow.core.getOrElse
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Locator
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.Resolution
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.parsing.ParsedTable
import dev.stapler.stelekit.parsing.TableExtraction
import dev.stapler.stelekit.units.LengthContext
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseCount
import dev.stapler.stelekit.units.parseLength

/** Table columns holding a length that no registry alias claims; the user must map them (never guessed). */
data class NeedsMapping(val columns: List<String>)

/**
 * What one extraction pass read. [skippedSelf] counts tables dropped because the page or an
 * enclosing block carries `stelekit-digest::` (our own output must not feed back in).
 */
data class TableExtractionResult(
    val observations: List<Observation> = emptyList(),
    val needsMapping: List<NeedsMapping> = emptyList(),
    val skippedSelf: Int = 0,
) {
    operator fun plus(other: TableExtractionResult) = TableExtractionResult(
        observations + other.observations,
        needsMapping + other.needsMapping,
        skippedSelf + other.skippedSelf,
    )
}

/**
 * Reads length and count observations from the pipe tables of a block of markdown (Story 5.2).
 * Identity is (row key, column header) resolved through [registry]; cells that are not a number,
 * a range, a tolerance or a size list are ignored. Takes text, not pages: reading is Story 5.9b.
 */
class TableMeasurementExtractor(
    private val registry: DimensionRegistry,
    private val refTable: RefTable,
    private val lengthContext: LengthContext = LengthContext(),
) {
    /**
     * [locator] is the human locator kept only in [refTable]; [page] carries the page-level
     * property keys (first-block keys are also detected from [blockContent] itself).
     */
    fun extract(blockContent: String, locator: String, page: SourceOrigin = SourceOrigin()): TableExtractionResult {
        val pageOrigin = page.copy(
            firstBlockPropertyKeys = page.firstBlockPropertyKeys + TableExtraction.firstBlockPropertyKeys(blockContent),
        )
        val tables = TableExtraction.parseTables(blockContent)
        if (pageOrigin.carriesDigestMarker) return TableExtractionResult(skippedSelf = tables.size)

        var result = TableExtractionResult()
        tables.forEachIndexed { index, parsed ->
            val origin = pageOrigin.copy(blockPropertyKeys = parsed.enclosingPropertyKeys)
            result += if (origin.carriesDigestMarker) {
                TableExtractionResult(skippedSelf = 1)
            } else {
                extractTable(parsed, "$locator#table$index", origin)
            }
        }
        return result
    }

    private fun extractTable(parsed: ParsedTable, locator: String, origin: SourceOrigin): TableExtractionResult {
        val headers = parsed.table.headers
        val keyColumn = HeaderAliases.keyColumn(headers)
        val observations = ArrayList<Observation>()
        val unmapped = HashSet<String>()
        for (row in parsed.table.rows) {
            val rowKey = row.getOrNull(keyColumn)?.let(HeaderAliases::clean).orEmpty()
            if (rowKey.isEmpty()) continue
            for (column in row.indices) {
                if (column == keyColumn) continue
                val text = HeaderAliases.clean(row[column])
                val header = HeaderAliases.clean(headers.getOrElse(column) { "" })
                if (text.isEmpty()) continue
                when (val resolution = resolve(rowKey, header)) {
                    is Resolution.Resolved -> observe(resolution.key, text, "$locator|$rowKey|$header", origin)?.let(observations::add)
                    is Resolution.NeedsMapping ->
                        if (parseLength(text, lengthContext).isRight()) unmapped.add(header)
                }
            }
        }
        val needs = if (unmapped.isEmpty()) emptyList() else listOf(NeedsMapping(unmapped.sorted()))
        return TableExtractionResult(observations, needs)
    }

    private fun resolve(rowKey: String, header: String): Resolution {
        val literal = registry.resolve(Locator.Table(rowKey, header))
        val stripped = HeaderAliases.withoutUnit(header)
        if (literal is Resolution.Resolved || stripped == header) return literal
        return registry.resolve(Locator.Table(rowKey, stripped)).takeIf { it is Resolution.Resolved } ?: literal
    }

    private fun observe(dimension: String, text: String, cellLocator: String, origin: SourceOrigin): Observation? {
        val entry = registry.entry(dimension) ?: return null
        val source = Source(SourceKind.TABLE, refTable.refFor(SourceKind.TABLE, cellLocator), origin)
        return when (entry.kind) {
            DimensionKind.COUNT -> parseCount(text).getOrNull()?.let {
                Observation(dimension, Rational(it), Rational.ZERO, entry.kind, source)
            }
            DimensionKind.LENGTH -> {
                val parsed = parseLength(text, lengthContext).getOrElse { return null }
                val measured = when (parsed) {
                    is ParsedLength.Measured -> parsed
                    is ParsedLength.Consistency -> parsed.primary
                    else -> return null
                }
                Observation(dimension, measured.value, measured.roundingPrecision, entry.kind, source, parsed.qualifiers)
            }
        }
    }
}
