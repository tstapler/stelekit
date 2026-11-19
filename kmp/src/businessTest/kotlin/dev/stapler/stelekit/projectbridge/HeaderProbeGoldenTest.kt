// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.1.5c (D-105, D-116): `SheetCodec.classifyHeader` decides a sheet's schema from the first
 * `HEADER_PROBE_BYTES` of the REAL bytes the two codecs emit, with `"schema"` required as the first key inside
 * `SCHEMA_WINDOW` bytes.
 */
class HeaderProbeGoldenTest {
    private fun v1Bytes(): ByteArray {
        val sheet = SheetUuid("sheet-v1")
        val prims = (0 until SHEET_SIZE).map { i -> SketchPrimitive("p$i", sheet, "line", i * 16L, 0L, i * 16L + 160L, 160L, name = "wall $i", layer = "Walls", z = i.toLong()) }
        return SheetCodecV1.encode(sheet, prims)
    }

    private fun v2Bytes(): ByteArray {
        var scene = SketchSceneV2.empty()
        for (i in 0 until SHEET_SIZE) {
            val e = RectElement(ElementBase(id = ElementId("r$i"), zKey = ZKey("a" + i.toString().padStart(3, '0'))), SceneUnit.ofInt(i * 10), SceneUnit.ZERO, SceneUnit.ofInt(8), SceneUnit.ofInt(8))
            scene = scene.add(e).fold({ error("fixture") }, { it })
        }
        return SheetCodecV2.encode(SketchDocumentV2(scene, "sheet-v2")).fold({ error("encode $it") }, { it })
    }

    private fun probe(bytes: ByteArray) = bytes.copyOf(minOf(bytes.size, SheetCodec.HEADER_PROBE_BYTES))

    @Test
    fun classifyHeader_should_SayV2_When_ProbeIsTheRealBytesOfA100ElementV2Sheet() {
        val bytes = v2Bytes()
        assertTrue(bytes.size > SheetCodec.HEADER_PROBE_BYTES, "the probe must be a strict prefix of a real sheet")
        assertTrue(bytes.decodeToString().startsWith("{\"schema\":2,\"sheetUuid\":\""))
        assertEquals(HeaderVerdict.V2, SheetCodec.classifyHeader(probe(bytes)))
        assertEquals(HeaderVerdict.V2, SheetCodec.classifyHeader(bytes.copyOf(SheetCodec.SCHEMA_WINDOW)))
    }

    @Test
    fun classifyHeader_should_SayV1_When_ProbeIsTheRealBytesOfAV1Sheet() {
        val bytes = v1Bytes()
        assertTrue(bytes.decodeToString().startsWith("{\n  \"schema\": 1,"))
        assertEquals(HeaderVerdict.V1, SheetCodec.classifyHeader(probe(bytes)))
    }

    @Test
    fun classifyHeader_should_BeUndecided_When_SchemaIsNotTheFirstKeyOrNotWithinTheWindow() {
        fun verdict(text: String) = SheetCodec.classifyHeader(text.encodeToByteArray())
        assertEquals(HeaderVerdict.Undecided, verdict(""))
        assertEquals(HeaderVerdict.Undecided, verdict("{\"schema\":2"), "no byte after the digit to end the number")
        assertEquals(HeaderVerdict.Undecided, verdict("{\"sheetUuid\":\"x\",\"schema\":2,"), "schema is not the first key")
        assertEquals(HeaderVerdict.Undecided, verdict("{\"elements\":[{\"schema\":2,"), "a nested schema key is not accepted")
        assertEquals(HeaderVerdict.Undecided, verdict("{\"schema\":21,"))
        assertEquals(HeaderVerdict.Undecided, verdict("{\"schema\":3,"))
        assertEquals(HeaderVerdict.Undecided, verdict("{\"schema\":\"2\","))
        assertEquals(HeaderVerdict.Undecided, verdict("[{\"schema\":2,"))
        assertEquals(HeaderVerdict.Undecided, verdict(" ".repeat(SheetCodec.SCHEMA_WINDOW) + "{\"schema\":2,"), "schema starts past the window")
    }

    @Test
    fun classifyHeader_should_ToleratePrettyPrintedWhitespace() {
        assertEquals(HeaderVerdict.V2, SheetCodec.classifyHeader("  {\r\n\t\"schema\" :\t2\n}".encodeToByteArray()))
    }

    private companion object {
        const val SHEET_SIZE = 100
    }
}
