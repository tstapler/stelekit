// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Where a sheet keeps whether its dimensions are shown: one optional `extras` boolean, written only once someone chooses, so a
 * sheet that never did keeps the exact bytes it has. [on] is what the editor, the exports, the embed preview and the CLI follow:
 * the stored choice, else on for a technical sheet (the editor's default) and off for a diagram. Like the sheet kind it is a sheet
 * property, not an edit: not an undo step, saved with the next document.
 */
object SheetDimensionsExtras {
    const val KEY = "dimensionsOn"

    /** The stored choice, or null when the sheet never made one (or the value is not a boolean). */
    fun read(extras: JsonObject): Boolean? = (extras[KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    /** Whether dimensions show on this sheet. */
    fun on(extras: JsonObject): Boolean = read(extras) ?: (SheetKindExtras.read(extras) == SheetKind.Technical)

    /** [extras] with the choice stored; null clears it back to the default. */
    fun write(extras: JsonObject, choice: Boolean?): JsonObject =
        if (choice == null) JsonObject(extras - KEY) else JsonObject(extras + (KEY to JsonPrimitive(choice)))
}

/** Whether this sheet shows its dimensions (see [SheetDimensionsExtras.on]). */
val SketchDocumentV2.dimensionsOn: Boolean get() = SheetDimensionsExtras.on(extras)

/** [this] carrying the dimensions choice in its extras; null clears it. */
fun SketchDocumentV2.withDimensionsOn(choice: Boolean?): SketchDocumentV2 {
    val written = SheetDimensionsExtras.write(extras, choice)
    return if (written == extras) this else copy(extras = written)
}
