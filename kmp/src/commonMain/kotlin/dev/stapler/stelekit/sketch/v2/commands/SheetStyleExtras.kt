// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SheetStyleDefaults
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a sheet keeps its style choice (OD-5): one `extras` key, written only for the non-default style so a Clean sheet's file is
 * unchanged. The setting is a sheet property, not an edit: it is not an undo step and the saver writes it with the next document.
 */
object SheetStyleExtras {
    const val KEY = "sheetStyle"
    private const val SKETCHY = "sketchy"

    /** The style [extras] name; anything missing or unknown is the Clean default. */
    fun read(extras: JsonObject): SheetStyleDefaults =
        if ((extras[KEY] as? JsonPrimitive)?.contentOrNull == SKETCHY) SheetStyleDefaults(RenderStyle.Sketchy) else SheetStyleDefaults.Default

    fun write(extras: JsonObject, style: SheetStyleDefaults): JsonObject =
        if (style.renderStyle == RenderStyle.Sketchy) JsonObject(extras + (KEY to JsonPrimitive(SKETCHY))) else JsonObject(extras - KEY)
}

/** [this] carrying [style] in its extras. */
fun SketchDocumentV2.withSheetStyle(style: SheetStyleDefaults): SketchDocumentV2 {
    val written = SheetStyleExtras.write(extras, style)
    return if (written == extras) this else copy(extras = written)
}
