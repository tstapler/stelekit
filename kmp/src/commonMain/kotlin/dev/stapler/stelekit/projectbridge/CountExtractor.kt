package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Locator
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.Resolution
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.units.Rational

/**
 * Reads counts from prose (`qty 8`, `12 carriers`) for count dimensions whose prose anchor appears
 * in the line (Story 5.3b). A number counts only beside `qty` or one to three words before the
 * anchor's noun, so `6 standards` never feeds `rod carriers`; screw gauges (`#10 x 2.5"`), sizes
 * and decimals are ignored. Self-exclusion matches [ProseMeasurementExtractor].
 */
class CountExtractor(
    private val registry: DimensionRegistry,
    private val refTable: RefTable,
) {
    fun extract(text: String, locator: String, page: SourceOrigin = SourceOrigin()): ProseExtractionResult {
        val (lines, skipped) = ProseText.eligibleLines(text, page)
        return ProseExtractionResult(lines.mapNotNull { observe(it, locator, page) }, skipped)
    }

    private fun observe(line: ProseLine, locator: String, origin: SourceOrigin): Observation? {
        val key = (registry.resolve(Locator.Prose(line.text)) as? Resolution.Resolved)?.key ?: return null
        val entry = registry.entry(key)?.takeIf { it.kind == DimensionKind.COUNT } ?: return null
        val anchorStart = ProseText.anchorIndex(entry, line.text) ?: return null
        val alias = entry.aliases.prose.firstOrNull { it.isNotBlank() && line.text.regionMatches(anchorStart, it.trim(), 0, it.trim().length, ignoreCase = true) } ?: return null
        val hit = ProseText.statedCount(line.text, ProseText.nounOf(alias)) ?: return null
        val source = Source(SourceKind.PROSE, refTable.refFor(SourceKind.PROSE, "$locator#line${line.number}|$key"), origin)
        return Observation(key, Rational(hit.value), Rational.ZERO, entry.kind, source)
    }
}
