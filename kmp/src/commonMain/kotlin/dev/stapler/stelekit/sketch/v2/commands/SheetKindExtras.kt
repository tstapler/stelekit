// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a sheet keeps its kind: one `extras` key, written only for a technical sheet so a diagram's file is unchanged.
 * Like the sheet style it is a sheet property, not an edit: not an undo step, saved with the next document.
 */
object SheetKindExtras {
    const val KEY = "sheetKind"
    private const val TECHNICAL = "technical"

    /** The kind [extras] name; anything missing or unknown is a diagram. */
    fun read(extras: JsonObject): SheetKind =
        if ((extras[KEY] as? JsonPrimitive)?.contentOrNull == TECHNICAL) SheetKind.Technical else SheetKind.Diagram

    fun write(extras: JsonObject, kind: SheetKind): JsonObject =
        if (kind == SheetKind.Technical) JsonObject(extras + (KEY to JsonPrimitive(TECHNICAL))) else JsonObject(extras - KEY)
}

/** [this] carrying [kind] in its extras. */
fun SketchDocumentV2.withSheetKind(kind: SheetKind): SketchDocumentV2 {
    val written = SheetKindExtras.write(extras, kind)
    return if (written == extras) this else copy(extras = written)
}
