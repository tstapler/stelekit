// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExportOptions
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.2.2b: the wasmJs `PngEncoder` (skiko) encodes an offscreen render; the bytes are a PNG that decodes to 400 x 200. */
class PngExportWasmTest {
    private object StubMetrics : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded
        override fun lineHeight(style: TextStyle): Double = style.fontSize
        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize
        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    @Test
    fun export_should_ProduceAPngThatDecodesTo400x200_When_A200x100SceneIsExportedAt2x() {
        val rect = RectElement(
            ElementBase(ElementId("r"), style = ElementStyle(strokeColor = "#000000", roughness = 0)),
            SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(WIDTH), SceneUnit.ofInt(HEIGHT),
        )
        val scene = SketchSceneV2.empty().add(rect).getOrNull()!!
        val options = PngExportOptions(scale = 2, padding = 0.0, bounds = Bounds(0.0, 0.0, WIDTH.toDouble(), HEIGHT.toDouble()))

        val png = PngExporter().export(scene, DrawListBuilder(StubMetrics), RenderStyle.Clean, options).getOrNull()!!

        assertEquals(PNG_SIGNATURE, png.take(PNG_SIGNATURE.size))
        val decoded = Image.makeFromEncoded(png)
        assertEquals(2 * WIDTH to 2 * HEIGHT, decoded.width to decoded.height)
        assertTrue(png.size > PNG_SIGNATURE.size)
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 100
        val PNG_SIGNATURE = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }
    }
}
