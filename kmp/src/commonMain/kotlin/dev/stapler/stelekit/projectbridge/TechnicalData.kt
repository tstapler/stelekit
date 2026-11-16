// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import kotlinx.serialization.json.JsonArray

/**
 * Technical data (zones, named dimensions, layer-name mirror, sheet scale) lives in `project-model.json`, never in a v2
 * sheet file (Story 1.1.4). These helpers read it and perform the only v2-initiated write, the layer mirror; every
 * other field of the bundle is carried by reference so the model bytes stay identical.
 */
object TechnicalData {
    fun zones(bundle: ProjectBundle, sheet: SheetUuid): JsonArray =
        bundle.sheets.firstOrNull { it.uuid == sheet }?.zones ?: JsonArray(emptyList())

    fun dimensions(bundle: ProjectBundle): List<SketchDimension> = bundle.dimensions

    fun scaleSixteenthsPerUnit(bundle: ProjectBundle, sheet: SheetUuid): Long? =
        bundle.sheets.firstOrNull { it.uuid == sheet }?.scaleSixteenthsPerUnit

    fun layerNames(bundle: ProjectBundle, sheet: SheetUuid): List<String> =
        bundle.sheets.firstOrNull { it.uuid == sheet }?.layers.orEmpty()

    /** True when the sheet has zones or the project has dimensions (the data the v2 editor cannot yet edit). */
    fun exists(bundle: ProjectBundle, sheet: SheetUuid): Boolean =
        zones(bundle, sheet).isNotEmpty() || bundle.dimensions.isNotEmpty()

    /**
     * Mirrors the v2 layer names (authoritative) into `sheetRef.layers` for v1 consumers, in the model write the caller
     * performs with the returned bundle. Only [sheet]'s `layers` changes; an unchanged list returns [bundle] itself.
     */
    fun withLayerMirror(bundle: ProjectBundle, sheet: SheetUuid, layers: List<String>): ProjectBundle {
        val current = bundle.sheets.firstOrNull { it.uuid == sheet } ?: return bundle
        if (current.layers == layers) return bundle
        return bundle.copy(sheets = bundle.sheets.map { if (it.uuid == sheet) it.copy(layers = layers) else it })
    }
}
