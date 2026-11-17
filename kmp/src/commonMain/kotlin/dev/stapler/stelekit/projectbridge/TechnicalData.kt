// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.units.Rational
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Why a technical-data edit changed nothing (Story 4.2.5). */
enum class TechnicalEditError { UnknownSheet, UnknownZone, DuplicateZone, UnknownDimension, DimensionLocked }

/**
 * Technical data (zones, named dimensions, layer-name mirror, sheet scale) lives in `project-model.json`, never in a v2
 * sheet file (Story 1.1.4). These helpers read it and perform the v2-initiated writes (the layer mirror and the Story 4.2.5 zone and dimension edits); every
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

    /** True when the sheet has zones or the project has dimensions (the data `ConversionPolicy` refuses to convert while the v2 editor cannot edit it). */
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

    /**
     * Types (or clears, with null) the length written on zone [key] of [sheet]. Only that zone's JSON object changes; every
     * other zone element, every other sheet and every dimension are carried by reference, so their bytes stay identical.
     */
    fun withZoneText(bundle: ProjectBundle, sheet: SheetUuid, key: String, text: String?): Either<TechnicalEditError, ProjectBundle> =
        editZone(bundle, sheet, key) { zone ->
            JsonObject(if (text == null) zone - ZONE_TEXT else zone + (ZONE_TEXT to JsonPrimitive(text)))
        }

    /** Adds an empty zone [key] to [sheet]; a key already in use is refused (the v1 `AddZone` rule). */
    fun withZoneAdded(bundle: ProjectBundle, sheet: SheetUuid, key: String): Either<TechnicalEditError, ProjectBundle> {
        val current = bundle.sheets.firstOrNull { it.uuid == sheet } ?: return TechnicalEditError.UnknownSheet.left()
        if (current.zones.any { zoneKey(it) == key }) return TechnicalEditError.DuplicateZone.left()
        return replaceZones(bundle, sheet, JsonArray(current.zones + JsonObject(mapOf(ZONE_KEY to JsonPrimitive(key))))).right()
    }

    /** Removes zone [key] from [sheet]. */
    fun withZoneRemoved(bundle: ProjectBundle, sheet: SheetUuid, key: String): Either<TechnicalEditError, ProjectBundle> {
        val current = bundle.sheets.firstOrNull { it.uuid == sheet } ?: return TechnicalEditError.UnknownSheet.left()
        if (current.zones.none { zoneKey(it) == key }) return TechnicalEditError.UnknownZone.left()
        return replaceZones(bundle, sheet, JsonArray(current.zones.filterNot { zoneKey(it) == key })).right()
    }

    /** Marks zone [key] as the one the others add up to (at most one total per sheet, like the v1 command), or clears it. */
    fun withZoneTotal(bundle: ProjectBundle, sheet: SheetUuid, key: String, isTotal: Boolean): Either<TechnicalEditError, ProjectBundle> {
        val current = bundle.sheets.firstOrNull { it.uuid == sheet } ?: return TechnicalEditError.UnknownSheet.left()
        if (current.zones.none { zoneKey(it) == key }) return TechnicalEditError.UnknownZone.left()
        val zones = JsonArray(
            current.zones.map { e ->
                val zone = e as? JsonObject ?: return@map e
                val wantsTotal = isTotal && zoneKey(zone) == key
                if ((zone[ZONE_TOTAL]?.jsonPrimitive?.booleanOrNull == true) == wantsTotal) zone
                else JsonObject(if (wantsTotal) zone + (ZONE_TOTAL to JsonPrimitive(true)) else zone - ZONE_TOTAL)
            },
        )
        return replaceZones(bundle, sheet, zones).right()
    }

    /** Sets the value of the named dimension [key] (inches, exact); a locked dimension is refused. Other fields and rows stay as they are. */
    fun withDimensionValue(bundle: ProjectBundle, key: String, value: Rational): Either<TechnicalEditError, ProjectBundle> {
        val target = bundle.dimensions.firstOrNull { it.key == key } ?: return TechnicalEditError.UnknownDimension.left()
        if (target.locked) return TechnicalEditError.DimensionLocked.left()
        if (target.value == value) return bundle.right()
        return bundle.copy(dimensions = bundle.dimensions.map { if (it === target) it.copy(value = value) else it }).right()
    }

    private fun editZone(bundle: ProjectBundle, sheet: SheetUuid, key: String, edit: (JsonObject) -> JsonObject): Either<TechnicalEditError, ProjectBundle> {
        val current = bundle.sheets.firstOrNull { it.uuid == sheet } ?: return TechnicalEditError.UnknownSheet.left()
        if (current.zones.none { zoneKey(it) == key }) return TechnicalEditError.UnknownZone.left()
        val zones = JsonArray(current.zones.map { e -> if (zoneKey(e) == key) edit(e as JsonObject) else e })
        return replaceZones(bundle, sheet, zones).right()
    }

    private fun replaceZones(bundle: ProjectBundle, sheet: SheetUuid, zones: JsonArray): ProjectBundle =
        bundle.copy(sheets = bundle.sheets.map { if (it.uuid == sheet && it.zones != zones) it.copy(zones = zones) else it })

    private fun zoneKey(e: kotlinx.serialization.json.JsonElement): String? = (e as? JsonObject)?.get(ZONE_KEY)?.jsonPrimitive?.contentOrNull

    private const val ZONE_KEY = "key"
    private const val ZONE_TEXT = "text"
    private const val ZONE_TOTAL = "total"
}
