// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Locator
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.Resolution
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceRef
import dev.stapler.stelekit.fusion.SvgLabel
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseLength
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One drawing label. [ref] is opaque (`svg:<id>`); [dimensionKey] is null when the label could not
 * be bound, in which case [candidates] lists the keys it might be and the label goes to manual tagging.
 */
data class BoundLabel(
    val ref: String,
    val text: String,
    val value: Rational,
    val precision: Rational,
    val dimensionKey: String?,
    val confidence: Double,
    val candidates: List<String> = emptyList(),
)

data class LabelBindingResult(
    val bound: List<BoundLabel> = emptyList(),
    val unbound: List<BoundLabel> = emptyList(),
    /** Captions that are not lengths (`Ceiling Heights`); never offered for tagging. */
    val skipped: List<String> = emptyList(),
    /** Zone-width labels in document order with their sheet x, for 1.4's mirrored-view value matching. */
    val zoneLabelOrder: List<SvgLabel> = emptyList(),
    val observations: List<Observation> = emptyList(),
)

/**
 * Binds `<text>` labels to registry dimensions (Story 4.3). A label naming exactly one dimension
 * through an `svgLabel` alias binds on that alone. A length label with no alias is a zone width when
 * a drawn horizontal span under it agrees with its value; zone labels take the registry's
 * `elevation.zone.*` dimensions in the order the sheet's view direction reads them, and only when
 * the label count equals the zone count (the binder never guesses).
 */
class LabelBinder(private val registry: DimensionRegistry, private val refTable: RefTable) {

    fun bind(doc: SvgDoc, locator: String): LabelBindingResult {
        val calibration = calibrateSheet(doc).getOrNull()
        val spans = calibration?.let { horizontalSpans(doc) }.orEmpty()
        val zoneKeys = registry.dimensions
            .filter { e -> e.aliases.svgGeom.any { it.startsWith(ZONE_PREFIX, ignoreCase = true) } }
            .map { it.key }

        val bound = ArrayList<IndexedLabel>()
        val unbound = ArrayList<IndexedLabel>()
        val skipped = ArrayList<String>()
        val zoneCandidates = ArrayList<ZoneCandidate>()
        doc.texts.forEachIndexed { index, text ->
            val measured = measureLabel(text.text) ?: run { skipped += text.text; return@forEachIndexed }
            val ref = labelRef(locator, index)
            when (val resolution = registry.resolve(Locator.SvgLabel(text.text))) {
                is Resolution.Resolved -> bound += IndexedLabel(index, measured.toLabel(ref, text.text, resolution.key, BindingConfidence.ALIAS))
                is Resolution.NeedsMapping -> {
                    val spanConfidence = if (resolution.candidates.isEmpty() && calibration != null) {
                        spanEvidence(text, spans, calibration.pxPerInchTransform, measured.inches)
                    } else {
                        0.0
                    }
                    if (spanConfidence >= BindingConfidence.ACCEPT && zoneKeys.isNotEmpty()) {
                        zoneCandidates += ZoneCandidate(index, text, measured, ref, spanConfidence)
                    } else {
                        val conf = if (resolution.candidates.isEmpty()) 0.0 else BindingConfidence.AMBIGUOUS
                        unbound += IndexedLabel(index, measured.toLabel(ref, text.text, null, conf, resolution.candidates))
                    }
                }
            }
        }

        val zoneOrder = zoneCandidates.sortedBy { it.index }.map { SvgLabel(pxToRational(it.text.x), it.measured.value, it.measured.precision) }
        if (calibration != null && zoneCandidates.size == zoneKeys.size) {
            val ordered = when (calibration.viewDirection) {
                ViewDirection.LEFT_TO_RIGHT -> zoneCandidates.sortedBy { it.text.x }
                ViewDirection.RIGHT_TO_LEFT -> zoneCandidates.sortedByDescending { it.text.x }
            }
            ordered.forEachIndexed { i, c ->
                bound += IndexedLabel(c.index, c.measured.toLabel(c.ref, c.text.text, zoneKeys[i], c.confidence))
            }
        } else {
            zoneCandidates.forEach { c ->
                unbound += IndexedLabel(c.index, c.measured.toLabel(c.ref, c.text.text, null, BindingConfidence.AMBIGUOUS, zoneKeys))
            }
        }

        val boundInOrder = bound.sortedBy { it.index }.map { it.label }
        return LabelBindingResult(
            bound = boundInOrder,
            unbound = unbound.sortedBy { it.index }.map { it.label },
            skipped = skipped,
            zoneLabelOrder = zoneOrder,
            observations = boundInOrder.mapNotNull(::observationFor),
        )
    }

    private fun observationFor(label: BoundLabel): Observation? {
        val key = label.dimensionKey ?: return null
        if (label.confidence < BindingConfidence.ACCEPT) return null
        val entry = registry.entry(key)?.takeIf { it.kind == DimensionKind.LENGTH } ?: return null
        val sourceRef = SourceRef(SourceKind.SVG_LABEL, label.ref.removePrefix("svg:"))
        return Observation(entry.key, label.value, label.precision, entry.kind, Source(SourceKind.SVG_LABEL, sourceRef))
    }

    private fun labelRef(locator: String, index: Int): String =
        "svg:" + refTable.refFor(SourceKind.SVG_LABEL, "$locator|label:$index").id

    private fun Measured.toLabel(ref: String, text: String, key: String?, confidence: Double, candidates: List<String> = emptyList()) =
        BoundLabel(ref, text, value, precision, key, confidence, candidates)

    private class IndexedLabel(val index: Int, val label: BoundLabel)

    private class ZoneCandidate(val index: Int, val text: SvgText, val measured: Measured, val ref: String, val confidence: Double)

    private companion object {
        const val ZONE_PREFIX = "elevation.zone."
    }
}

/** A label's exact value, its rounding half-width, and the value in inches for geometry comparison. */
internal class Measured(val value: Rational, val precision: Rational) {
    val inches: Double get() = value.num.toDouble() / value.den.toDouble()
}

private val UNIT_MARKED = Regex("""\d[\d /.'\-]*["']""")

/**
 * The length a label states, or null for a caption. The whole text is tried first, then the first
 * unit-marked token (`38" Rod`, `Total Width: 136"`); a bare number inside words is not a length.
 */
internal fun measureLabel(text: String): Measured? {
    val whole = parseLength(text).getOrNull()
    val parsed = whole ?: UNIT_MARKED.find(text.substringAfter(':', text))?.value?.let { parseLength(it.trim()).getOrNull() }
    val measured = when (parsed) {
        is ParsedLength.Measured -> parsed
        is ParsedLength.Consistency -> parsed.primary
        else -> return null
    }
    return Measured(measured.value, measured.roundingPrecision)
}

/** A horizontal drawn segment in sheet pixels, `xLeft <= xRight`. */
internal data class Span(val xLeft: Double, val xRight: Double, val y: Double) {
    val length: Double get() = xRight - xLeft
}

internal fun horizontalSpans(doc: SvgDoc): List<Span> {
    val out = ArrayList<Span>()
    for (path in doc.paths) {
        val m = composeTransforms(path.transforms).getOrNull() ?: continue
        for (seg in path.segments) {
            val (x1, y1) = m.apply(seg.x1, seg.y1)
            val (x2, y2) = m.apply(seg.x2, seg.y2)
            if (abs(y2 - y1) > FLAT_EPSILON_PX) continue
            out += Span(minOf(x1, x2), maxOf(x1, x2), (y1 + y2) / 2)
        }
    }
    return out
}

/**
 * Confidence from the drawn span under [label] (covering its x) whose length is closest to
 * [labelInches]; ties break on the smaller vertical distance.
 */
internal fun spanEvidence(label: SvgText, spans: List<Span>, pxPerInch: Double, labelInches: Double): Double {
    val (lx, ly) = composeTransforms(label.transforms).getOrNull()?.apply(label.x, label.y) ?: return 0.0
    val best = spans
        .filter { lx >= it.xLeft && lx <= it.xRight }
        .minWithOrNull(
            compareBy<Span> { abs(it.length / pxPerInch - labelInches) }
                .thenBy { abs(it.y - ly) },
        ) ?: return 0.0
    return BindingConfidence.forSpan(best.length / pxPerInch, labelInches)
}

private fun pxToRational(px: Double): Rational = Rational((px * PX_SCALE).roundToLong(), PX_SCALE.toLong())

private const val FLAT_EPSILON_PX = 1e-6
private const val PX_SCALE = 100.0
