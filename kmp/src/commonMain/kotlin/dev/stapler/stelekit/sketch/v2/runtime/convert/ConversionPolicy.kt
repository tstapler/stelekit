// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.convert

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SketchSheet

/** Why a v1 sheet is not converted: the project holds zones or dimensions the v2 editor cannot edit (before Story 4.2.5). */
data object HasTechnicalData

/**
 * Conversion refusal (Story 1.1.4). A v1 sheet is refused iff its own zones are non-empty or its project has any
 * dimension (dimensions carry no sheet reference). Refusal never writes. Story 4.2.5 flipped [TECHNICAL_DATA_EDITABLE], so [refusal] is null; the predicate stays for the override.
 */
object ConversionPolicy {
    /** True: the v2 editor edits zones and dimensions (`TechnicalDataPanel`, Story 4.2.5), so nothing is refused. */
    const val TECHNICAL_DATA_EDITABLE: Boolean = true

    fun refusal(project: ProjectBundle, sheet: SketchSheet): HasTechnicalData? =
        refusal(project, sheet, TECHNICAL_DATA_EDITABLE)

    fun refusal(project: ProjectBundle, sheet: SketchSheet, technicalDataEditable: Boolean): HasTechnicalData? = when {
        technicalDataEditable -> null
        sheet.zones.isNotEmpty() || project.dimensions.isNotEmpty() -> HasTechnicalData
        else -> null
    }
}
