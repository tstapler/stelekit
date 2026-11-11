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

/**
 * What one sheet's geometry yielded. [measurements] is every value the rules read; [observations]
 * only those the registry resolved; [unmappedRules] are `<sheet>.<alias>` ids it could not (or
 * matched ambiguously); [calibrationError] is set when the sheet could not be calibrated or placed.
 */
data class GeometryExtractionResult(
    val observations: List<Observation> = emptyList(),
    val measurements: List<GeometryMeasurement> = emptyList(),
    val unmappedRules: List<String> = emptyList(),
    val calibrationError: CalibrationError? = null,
) {
    operator fun plus(other: GeometryExtractionResult) = GeometryExtractionResult(
        observations + other.observations,
        measurements + other.measurements,
        (unmappedRules + other.unmappedRules).distinct().sorted(),
        calibrationError ?: other.calibrationError,
    )
}

/**
 * Geometry stage (Story 4.8): measures a sheet with [measureGeometry] and resolves each value
 * through the registry's `svgGeom` aliases. The locator id is `<sheet>.<alias suffix>`, plus
 * `.<tag>` for multi-valued rules; `strip-positions` instead resolves `<sheet>.standards` and
 * writes dimension `<key>.<index>`, the per-position keys the fusion fixtures use.
 */
class GeometryExtractor(
    private val registry: DimensionRegistry,
    private val refTable: RefTable,
) {
    /** [sheet] is the drawing's role (`elevation`); [locator] stays device-local in the [RefTable]. */
    fun extract(doc: SvgDoc, locator: String, sheet: String): GeometryExtractionResult {
        val calibration = calibrateSheet(doc).fold({ return GeometryExtractionResult(calibrationError = it) }, { it })
        val measurements = measureGeometry(doc, calibration).fold({ return GeometryExtractionResult(calibrationError = it) }, { it })
        val observations = ArrayList<Observation>()
        val unmapped = HashSet<String>()
        for (m in measurements) {
            val base = "$sheet.${GeometryRule.aliasSuffix(m.rule)}"
            val perPosition = m.rule == GeometryRule.STRIP_POSITIONS
            val ruleId = if (m.tag.isEmpty() || perPosition) base else "$base.${m.tag}"
            val resolved = registry.resolve(Locator.SvgGeom(ruleId)) as? Resolution.Resolved
            val entry = resolved?.let { registry.entry(it.key) }
            if (entry == null || entry.kind != DimensionKind.LENGTH) {
                unmapped += base
                continue
            }
            val dimension = if (perPosition) "${entry.key}.${m.tag}" else entry.key
            val source = Source(SourceKind.SVG_GEOMETRY, refTable.refFor(SourceKind.SVG_GEOMETRY, "$locator|$ruleId|${m.tag}"))
            observations += Observation(dimension, m.value, m.precision, entry.kind, source)
        }
        return GeometryExtractionResult(observations, measurements, unmapped.sorted())
    }
}
