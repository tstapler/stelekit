// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.bridge.DimensionMismatch
import dev.stapler.stelekit.sketch.v2.bridge.ProjectionReport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter

/**
 * The lines a technical sheet's status or diagnostics shows when its projection no longer matches the named dimensions
 * (Task 4.2.4a, ux.md section 9): "Wall A is off by 1/16 in" for each mismatch and "N elements not used for dimensions" for
 * what v1 cannot hold. Pure text; the "Show elements" link is the host's.
 */
object ProjectionMessages {
    fun mismatch(m: DimensionMismatch): String =
        SketchConstraintStrings.Projection.offBy(m.name, LengthFormatter.format(Inches(m.offBy), LengthFormat.INCHES_MIXED).removeSuffix("\""))

    /** Every line to show, mismatches first; empty when the projection is exact and nothing was dropped. */
    fun lines(report: ProjectionReport): List<String> =
        report.mismatches.map(::mismatch) + listOfNotNull(report.dropped.takeIf { it > 0 }?.let(SketchConstraintStrings.Projection::notUsed))
}
