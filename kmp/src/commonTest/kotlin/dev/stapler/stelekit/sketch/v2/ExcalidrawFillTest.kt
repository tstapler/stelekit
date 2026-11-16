// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.toPatch
import dev.stapler.stelekit.sketch.v2.scene.ExcalidrawFill
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 2.2.2 (import fidelity, Task 2.2.2b): fill names map to styles, unknown ones fall back to solid and survive in extras. */
class ExcalidrawFillTest {
    @Test
    fun import_should_MapEveryKnownName() {
        assertEquals(FillStyle.Hachure, ExcalidrawFill.import("hachure").style)
        assertEquals(FillStyle.CrossHatch, ExcalidrawFill.import("cross-hatch").style)
        assertEquals(FillStyle.Solid, ExcalidrawFill.import("solid").style)
        assertEquals(FillStyle.Zigzag, ExcalidrawFill.import("zigzag").style)
    }

    @Test
    fun import_should_FallBackToSolidAndRetainTheName_When_FillStyleIsUnknown() {
        val imported = ExcalidrawFill.import("dots", JsonObject(mapOf("other" to JsonPrimitive(1))))
        assertEquals(FillStyle.Solid, imported.style)
        assertEquals(JsonPrimitive("dots"), imported.extras[ExcalidrawFill.EXTRAS_KEY])
        assertEquals(JsonPrimitive(1), imported.extras["other"])
        assertEquals("dots", ExcalidrawFill.exportName(imported.style, imported.extras))
    }

    @Test
    fun exportName_should_UseTheStyleName_When_TheUserPicksAnotherFill() {
        val imported = ExcalidrawFill.import("dots")
        assertEquals("hachure", ExcalidrawFill.exportName(FillStyle.Hachure, imported.extras))
        assertEquals("solid", ExcalidrawFill.exportName(FillStyle.Solid, JsonObject(emptyMap())))
    }

    @Test
    fun import_should_DropAStaleRetainedName_When_TheNameIsKnown() {
        val stale = JsonObject(mapOf(ExcalidrawFill.EXTRAS_KEY to JsonPrimitive("dots")))
        assertEquals(JsonObject(emptyMap()), ExcalidrawFill.import("zigzag", stale).extras)
    }

    @Test
    fun panelRow_should_OfferEveryFillStyleOnceAndPatchOnlyTheFill() {
        assertEquals(FillStyle.entries.toSet(), ExcalidrawFill.options.map { it.style }.toSet())
        assertEquals(FillStyle.entries.size, ExcalidrawFill.options.size)
        val patch = ExcalidrawFill.options.first { it.style == FillStyle.Zigzag }.toPatch()
        assertEquals(FillStyle.Zigzag, patch.fillStyle)
        assertEquals(null, patch.strokeColor)
    }
}
