// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.export

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SheetSetupExtras
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.sheetSetup
import dev.stapler.stelekit.sketch.v2.commands.withSheetSetup
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E7: the print setup is a sheet property: parsed exactly, stored additively, and invisible to a sheet that never set one. */
class SheetSetupTest {
    private fun doc() = SketchDocumentV2(
        scene = dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2.empty().add(filledRectEl("room", 0, 0, 1920, 1440)).getOrNull()!!,
        sheetUuid = "s1",
    )

    private fun encode(d: SketchDocumentV2) = SheetCodecV2.encode(d).getOrElse { error("$it") }.decodeToString()

    @Test
    fun scales_should_ParseEveryDocumentedSpelling() {
        assertEquals(DrawingScale.of(1, 48), DrawingScale.parse("1/4\" = 1'"))
        assertEquals(DrawingScale.of(1, 48), DrawingScale.parse("1/4in=1ft"))
        assertEquals(DrawingScale.of(1, 8), DrawingScale.parse("1 1/2\"=1'"))
        assertEquals(DrawingScale.of(1, 8), DrawingScale.parse("1-1/2in = 1ft"))
        assertEquals(DrawingScale.of(1, 12), DrawingScale.parse("1\" = 1'"))
        assertEquals(DrawingScale.of(1, 50), DrawingScale.parse("1:50"))
        assertEquals(DrawingScale.of(2, 1), DrawingScale.parse("4:2"))
        assertEquals(DrawingScale.of(1, 37), DrawingScale.parse(" 1 : 37 "))
        for (bad in listOf("", "1:0", "0:5", "abc", "1/0\"=1'", "1/4\"=0'", "1:-2")) assertNull(DrawingScale.parse(bad), bad)
        assertEquals("1/4\" = 1'", DrawingScale.of(1, 48).label)
        assertEquals("1:50", DrawingScale.of(1, 50).label)
        assertEquals("1:48", DrawingScale.of(2, 96).ratio, "stored in lowest terms")
    }

    @Test
    fun everyPresetScale_should_RoundTripThroughItsRatioText() {
        for (p in ScalePresets.architectural + ScalePresets.metric) {
            assertEquals(p.scale, DrawingScale.parse(p.scale.ratio), p.label)
            assertEquals(p.scale, DrawingScale.parse(p.label), p.label)
        }
    }

    @Test
    fun papers_should_ParsePresetsAndCustomSizes_Exactly() {
        assertEquals(PaperSize.A4, PaperSize.parse("a4"))
        assertEquals(PaperSize.Tabloid, PaperSize.parse("Tabloid"))
        val custom = assertNotNull(PaperSize.parse("300x200mm"))
        assertEquals(300_000L, custom.widthMicrometers)
        assertEquals(200_000L, custom.heightMicrometers)
        assertEquals("300x200mm", custom.id)
        assertEquals(PaperSize.custom(203_200, 254_000, true), PaperSize.parse("8x10in"))
        assertEquals(215_900L, assertNotNull(PaperSize.parse("8.5x11in")).widthMicrometers)
        for (bad in listOf("", "A9", "0x5mm", "10x10", "10x10cm")) assertNull(PaperSize.parse(bad), bad)
        assertEquals(12_700L, Micrometers.parseWithUnit("0.5in"))
        assertEquals(10_000L, Micrometers.parseWithUnit("10 mm"))
        assertEquals("0.5in", Micrometers.withUnit(12_700, true))
    }

    @Test
    fun aSetup_should_SurviveTheCodec_AndAnOldSheetsBytesStayTheSame() {
        val old = doc()
        val oldBytes = encode(old)
        assertFalse(oldBytes.contains(SheetSetupExtras.KEY), "a sheet that never set one carries no key")
        assertNull(old.sheetSetup)
        assertEquals(old, old.withSheetSetup(null))
        val setup = SheetSetup(
            PaperSize.A3, Orientation.Landscape, 10_000, DrawingScale.parse("1:50")!!, scaleBar = false, northDegrees = 45,
            titleBlock = TitleBlock("Kitchen", "T. S.", "2026-11-24", "A-1", "Metric"),
        )
        val withSetup = old.withSheetSetup(setup)
        val bytes = SheetCodecV2.encode(withSetup).getOrElse { error("$it") }
        val back = SheetCodecV2.decode(bytes).getOrElse { error("$it") }
        assertEquals(setup, back.sheetSetup)
        assertEquals(bytes.decodeToString(), SheetCodecV2.encode(back).getOrElse { error("$it") }.decodeToString(), "canonical JSON is stable")
        assertEquals(oldBytes, encode(withSetup.withSheetSetup(null)), "clearing it restores the exact bytes")
    }

    @Test
    fun anyValidSetup_should_RoundTripThroughExtras() = runTest {
        checkAll(Arb.element(PaperSize.presets), Arb.element(ScalePresets.architectural + ScalePresets.metric), Arb.int(0..MAX_NORTH), Arb.int(0..BITS)) { paper, preset, north, bits ->
            val setup = SheetSetup(
                paper, if (bits and 1 == 0) Orientation.Portrait else Orientation.Landscape, SheetSetup.margins(paper)[bits % 3], preset.scale,
                scaleBar = bits and 2 == 0, northDegrees = north.takeIf { bits and 4 == 0 },
                titleBlock = if (bits and 8 == 0) null else TitleBlock("t$bits", "", "d", "s", "n\"q"),
            )
            assertEquals(setup, SheetSetupExtras.read(SheetSetupExtras.write(JsonObject(emptyMap()), setup)))
        }
    }

    @Test
    fun aMalformedSetup_should_FallBackToDefaultsAndNeverThrow() {
        val junk = JsonObject(mapOf(SheetSetupExtras.KEY to JsonObject(mapOf("paper" to JsonPrimitive("nonsense"), "scale" to JsonPrimitive("??"), "margin" to JsonPrimitive("wide"), "scaleBar" to JsonPrimitive("yes")))))
        val read = assertNotNull(SheetSetupExtras.read(junk))
        assertEquals(PaperSize.Letter, read.paper)
        assertEquals(DrawingScale.of(1, SheetSetup.DEFAULT_SCALE_DENOMINATOR), read.scale)
        assertEquals(SheetSetup.DEFAULT_MARGIN_IMPERIAL, read.margin)
        assertTrue(read.scaleBar)
        assertNull(SheetSetupExtras.read(JsonObject(mapOf(SheetSetupExtras.KEY to JsonPrimitive(1)))))
    }

    private companion object {
        const val MAX_NORTH = 359
        const val BITS = 15
    }
}
