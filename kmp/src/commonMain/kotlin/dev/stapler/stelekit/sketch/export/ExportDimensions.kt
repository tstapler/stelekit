// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.export

import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.ZoneConstraints
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.toInches

/**
 * One named dimension of the sheet as exported. [value] is the display string (empty when unknown);
 * [sixteenths] is null when the value is not a whole number of sixteenths or not known.
 */
data class ExportDimension(
    val id: String,
    val label: String,
    val value: String,
    val sixteenths: Long?,
    val status: String,
    val sources: String,
) {
    /** The text drawn in the SVG, which `LabelBinder` reads back as the label, a colon, then the value. */
    val labelText: String get() = if (value.isEmpty()) label else "$label: $value"
}

private data class Resolved(val value: Rational?, val status: String, val sources: String)

/** The sheet's zone dimensions with their fusion status, in zone order. */
fun SketchDocument.exportDimensions(): List<ExportDimension> {
    val report = ZoneConstraints.resolve(zones, sheet.viewDirection).getOrNull()
    return zones.zones.map { zone ->
        val resolved = report?.dimensions?.get(zone.key).toResolved()
        val exact = resolved.value
        val inches = exact?.toInches()?.getOrNull()
        val value = when {
            inches != null -> LengthFormatter.format(inches, LengthFormat.FEET_INCHES)
            exact != null -> "$exact\""
            else -> ""
        }
        ExportDimension(zone.key, zone.key, value, inches?.sixteenths, resolved.status, resolved.sources)
    }
}

private fun DimensionStatus?.toResolved(): Resolved = when (this) {
    is DimensionStatus.Confirmed -> Resolved(value, "confirmed", sources.toString())
    is DimensionStatus.SingleSource -> Resolved(value, "single-source", "1")
    is DimensionStatus.Derivable -> Resolved(value, "derived", "0")
    is DimensionStatus.NotRepresentableValue -> Resolved(exact, "not-representable", "0")
    is DimensionStatus.Conflict -> Resolved(null, "conflict", (pairs.size + 1).toString())
    DimensionStatus.UnderConstrained, null -> Resolved(null, "under-constrained", "0")
}
