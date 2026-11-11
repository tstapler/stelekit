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
import dev.stapler.stelekit.units.LengthContext
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength

/**
 * Reads lengths stated in free text (Story 5.3b). A line is read only when exactly one length
 * dimension's prose anchor appears in it; the value is the length after that anchor (after the
 * last `=` for a sum). A comma list of lengths is never guessed at. These are [SourceKind.PROSE]
 * observations, the lowest-confidence kind, and nothing is read on an owned page or inside a
 * `stelekit-digest::` block.
 */
class ProseMeasurementExtractor(
    private val registry: DimensionRegistry,
    private val refTable: RefTable,
    private val lengthContext: LengthContext = LengthContext(),
) {
    /** [locator] is the human page locator, kept only in [refTable]. */
    fun extract(text: String, locator: String, page: SourceOrigin = SourceOrigin()): ProseExtractionResult {
        val (lines, skipped) = ProseText.eligibleLines(text, page)
        val observations = lines.mapNotNull { observe(it, locator, page) }
        return ProseExtractionResult(observations, skipped)
    }

    private fun observe(line: ProseLine, locator: String, origin: SourceOrigin): Observation? {
        val key = (registry.resolve(Locator.Prose(line.text)) as? Resolution.Resolved)?.key ?: return null
        val entry = registry.entry(key)?.takeIf { it.kind == DimensionKind.LENGTH } ?: return null
        val anchor = ProseText.anchorIndex(entry, line.text) ?: return null
        val span = ProseText.statedLength(line.text.substring(anchor)) ?: return null
        val parsed = parseLength(span.text, lengthContext).getOrElse { return null }
        val measured = when (parsed) {
            is ParsedLength.Measured -> parsed
            is ParsedLength.Consistency -> parsed.primary
            else -> return null
        }
        val source = Source(SourceKind.PROSE, refTable.refFor(SourceKind.PROSE, "$locator#line${line.number}|$key"), origin)
        return Observation(key, measured.value, measured.roundingPrecision, entry.kind, source, parsed.qualifiers)
    }
}
