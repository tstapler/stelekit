// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review fix C4: an image used by thousands of elements is read and embedded once, not once per element. */
class SvgExporterScaleTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)

    @Test
    fun export_should_EmbedAndReadASharedImageOnce_When_ManyElementsUseTheSameFile() {
        var scene = SketchSceneV2.empty()
        repeat(COPIES) { scene = scene.add(ImageElement(ElementBase(ElementId("i$it"), seed = 7), u(it), u(0), u(10), u(10), fileId = "shared")).getOrNull()!! }
        var loads = 0
        val bytes = ByteArray(IMAGE_BYTES) { (it % BYTE_RANGE).toByte() }
        val source = SvgImageSource { loads++; SvgImage("image/png", bytes) }

        val out = SvgExporter.export(scene, DrawListBuilder(FixedAdvanceTextMetricsProvider()), RenderStyle.Clean, source)

        assertEquals(1, loads, "the file was read once per element")
        assertEquals(1, Regex("base64,").findAll(out).count(), "the bytes were embedded more than once")
        assertEquals(COPIES, Regex("<use href=\"#img0\"").findAll(out).count())
        assertTrue(out.length < IMAGE_BYTES * 2 + COPIES * PER_USE_CHARS, "output is ${out.length} characters for one ${IMAGE_BYTES}-byte image")
    }

    private companion object {
        const val COPIES = 2_000
        const val IMAGE_BYTES = 50_000
        const val BYTE_RANGE = 251
        const val PER_USE_CHARS = 400
    }
}
