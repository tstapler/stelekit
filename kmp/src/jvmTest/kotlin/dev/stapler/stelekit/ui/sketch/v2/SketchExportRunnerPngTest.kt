// jvmTest-reason: pixels
// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFile
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportScope
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Story 5.2.3a: the real PNG a scope produces decodes to the size the dialog's preview promised. */
class SketchExportRunnerPngTest {
    private val runner = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))

    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun rect(id: String, x: Int, y: Int, w: Int, h: Int, frame: String? = null, roughness: Int = 1) =
        RectElement(ElementBase(ElementId(id), frameId = frame?.let(::ElementId), style = ElementStyle(roughness = roughness)), u(x), u(y), u(w), u(h))

    private fun scene(vararg elements: SceneElement): SketchSceneV2 =
        elements.fold(SketchSceneV2.empty()) { s, e -> s.add(e).fold({ error("$it") }, { it }) }

    private fun source(scene: SketchSceneV2, selection: Set<ElementId> = emptySet()) =
        ExportSource(scene, selection, SvgImageSource.None, baseName = "plan")

    private fun decodedSize(file: ExportFile): Pair<Int, Int> =
        ImageIO.read(ByteArrayInputStream(file.bytes)).let { it.width to it.height }

    private fun png(source: ExportSource, request: ExportRequest): ExportFile = runner.run(source, request).fold({ error("$it") }, { it })

    @Test
    fun wholeSketch_should_DecodeToThePreviewSizeAtEveryScale() {
        val source = source(scene(rect("a", 0, 0, 200, 100)))
        for (scale in 1..3) {
            val request = ExportRequest(format = ExportFormat.Png, scale = scale)
            val promised = runner.measure(source, request)!!.fold({ error("$it") }, { it })
            assertEquals(promised.widthPx.toInt() to promised.heightPx.toInt(), decodedSize(png(source, request)), "scale $scale")
        }
    }

    @Test
    fun frameScope_should_CropToTheFrameBounds_AndSelectionScope_ToTheSelectedElement() {
        val frame = FrameElement(ElementBase(ElementId("f1")), u(0), u(0), u(100), u(60))
        val scene = scene(frame, rect("in", 10, 10, 20, 20, frame = "f1"), rect("far", 1_000, 1_000, 300, 300))
        val whole = decodedSize(png(source(scene), ExportRequest(format = ExportFormat.Png, scale = 1)))
        val framed = ExportRequest(format = ExportFormat.Png, scale = 1, scope = ExportScope.Frame, frameId = ElementId("f1"))
        val cropped = decodedSize(png(source(scene), framed))
        assertEquals(runner.measure(source(scene), framed)!!.fold({ error("$it") }, { it }).let { it.widthPx.toInt() to it.heightPx.toInt() }, cropped)
        assert(cropped.first < whole.first && cropped.second < whole.second) { "$cropped is not smaller than $whole" }

        val picked = ExportRequest(format = ExportFormat.Png, scale = 1, scope = ExportScope.Selection)
        val one = decodedSize(png(source(scene, setOf(ElementId("far"))), picked))
        val far = ElementBounds.of(scene["far".let(::ElementId)]!!)
        val side = ceil(far.maxX - far.minX + 2 * SvgExportOptions.DEFAULT_PADDING).toInt()
        assertEquals(side to side, one, "the selected element's bounds plus padding, nothing else")
    }

    @Test
    fun style_should_ChangeThePixels_AsDrawnKeepsTheSketchyLook_AndCleanDoesNot() {
        val source = source(scene(rect("a", 0, 0, 120, 80, roughness = 2)))
        val asDrawn = png(source, ExportRequest(format = ExportFormat.Png, scale = 1, style = ExportStyle.AsDrawn))
        val clean = png(source, ExportRequest(format = ExportFormat.Png, scale = 1, style = ExportStyle.Clean))
        assertNotEquals(asDrawn.bytes.toList(), clean.bytes.toList())
    }
}
