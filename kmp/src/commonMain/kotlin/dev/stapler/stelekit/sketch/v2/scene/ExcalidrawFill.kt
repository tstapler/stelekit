// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One entry of the style panel's fill row: the stored [style], its Excalidraw name and the label shown. */
data class FillStyleOption(val style: FillStyle, val excalidrawName: String, val label: String)

/**
 * Excalidraw `fillStyle` names (Story 2.2.2). An unknown name imports as solid and its original text stays in the
 * element's `extras` under [EXTRAS_KEY], so exporting the element again writes the name back unchanged.
 */
object ExcalidrawFill {
    const val EXTRAS_KEY = "fillStyle"

    /** The style panel's fill row, in Excalidraw's order. */
    val options: List<FillStyleOption> = listOf(
        FillStyleOption(FillStyle.Hachure, "hachure", "Hachure"),
        FillStyleOption(FillStyle.CrossHatch, "cross-hatch", "Cross-hatch"),
        FillStyleOption(FillStyle.Solid, "solid", "Solid"),
        FillStyleOption(FillStyle.Zigzag, "zigzag", "Zigzag"),
    )

    data class Imported(val style: FillStyle, val extras: JsonObject)

    fun import(name: String?, extras: JsonObject = JsonObject(emptyMap())): Imported {
        val known = options.firstOrNull { it.excalidrawName == name }
        return when {
            known != null -> Imported(known.style, JsonObject(extras - EXTRAS_KEY))
            name == null -> Imported(FillStyle.Solid, extras)
            else -> Imported(FillStyle.Solid, JsonObject(extras + (EXTRAS_KEY to JsonPrimitive(name))))
        }
    }

    /** The name to write on export: the retained unknown name while the style is still solid, else the style's own name. */
    fun exportName(style: FillStyle, extras: JsonObject): String {
        val retained = (extras[EXTRAS_KEY] as? JsonPrimitive)?.contentOrNull
        if (style == FillStyle.Solid && retained != null) return retained
        return options.first { it.style == style }.excalidrawName
    }
}
