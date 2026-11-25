// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.Micrometers
import dev.stapler.stelekit.sketch.v2.export.Orientation
import dev.stapler.stelekit.sketch.v2.export.PaperSize
import dev.stapler.stelekit.sketch.v2.export.SheetSetup
import dev.stapler.stelekit.sketch.v2.export.TitleBlock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Where a sheet keeps its print setup (E7): one optional `extras` object, `sheetSetup`, written only once someone sets one, so a
 * sheet that never did keeps the exact bytes it has. Every field is a string, boolean or whole number, so it round-trips through
 * the canonical codec on every platform. It is a sheet property, not an edit: not an undo step, saved with the next document.
 * Reading is forgiving: an unknown or malformed field falls back to its default and an unreadable object is no setup.
 */
object SheetSetupExtras {
    const val KEY = "sheetSetup"
    private const val PAPER = "paper"
    private const val ORIENTATION = "orientation"
    private const val MARGIN = "margin"
    private const val SCALE = "scale"
    private const val SCALE_BAR = "scaleBar"
    private const val NORTH = "north"
    private const val TITLE_BLOCK = "titleBlock"
    private const val LANDSCAPE = "landscape"
    private const val PORTRAIT = "portrait"
    private val TITLE_KEYS = listOf("title", "author", "date", "sheet", "notes")

    /** The stored setup, or null when the sheet has none. */
    fun read(extras: JsonObject): SheetSetup? {
        val o = extras[KEY] as? JsonObject ?: return null
        val paper = text(o, PAPER)?.let(PaperSize::parse) ?: PaperSize.Letter
        return SheetSetup(
            paper = paper,
            orientation = if (text(o, ORIENTATION) == LANDSCAPE) Orientation.Landscape else Orientation.Portrait,
            margin = text(o, MARGIN)?.let(Micrometers::parseWithUnit)?.takeIf { it >= 0 } ?: SheetSetup.defaultMargin(paper),
            scale = text(o, SCALE)?.let(DrawingScale::parse) ?: DrawingScale.of(1, SheetSetup.DEFAULT_SCALE_DENOMINATOR),
            scaleBar = (o[SCALE_BAR] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: true,
            northDegrees = (o[NORTH] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull,
            titleBlock = (o[TITLE_BLOCK] as? JsonObject)?.let { t ->
                val v = TITLE_KEYS.map { text(t, it).orEmpty() }
                TitleBlock(v[0], v[1], v[2], v[3], v[4])
            },
        )
    }

    private fun text(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** [extras] with [setup] stored; null removes it. */
    fun write(extras: JsonObject, setup: SheetSetup?): JsonObject {
        if (setup == null) return JsonObject(extras - KEY)
        val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        fields[PAPER] = JsonPrimitive(setup.paper.id)
        fields[ORIENTATION] = JsonPrimitive(if (setup.orientation == Orientation.Landscape) LANDSCAPE else PORTRAIT)
        fields[MARGIN] = JsonPrimitive(Micrometers.withUnit(setup.margin, setup.paper.imperial))
        fields[SCALE] = JsonPrimitive(setup.scale.ratio)
        fields[SCALE_BAR] = JsonPrimitive(setup.scaleBar)
        setup.northDegrees?.let { fields[NORTH] = JsonPrimitive(it) }
        setup.titleBlock?.let { t ->
            fields[TITLE_BLOCK] = JsonObject(TITLE_KEYS.zip(listOf(t.title, t.author, t.date, t.sheet, t.notes)).associate { (k, v) -> k to JsonPrimitive(v) })
        }
        return JsonObject(extras + (KEY to JsonObject(fields)))
    }
}

/** The sheet's print setup, or null when it has none. */
val SketchDocumentV2.sheetSetup: SheetSetup? get() = SheetSetupExtras.read(extras)

/** [this] carrying [setup] in its extras; null clears it. */
fun SketchDocumentV2.withSheetSetup(setup: SheetSetup?): SketchDocumentV2 {
    val written = SheetSetupExtras.write(extras, setup)
    return if (written == extras) this else copy(extras = written)
}
