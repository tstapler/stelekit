// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.ImageCrop
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.2.1: the SVG of the architecture-15 fixture equals its golden on every target; kinds, clips and images. */
class SvgExporterGoldenTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)
    private fun p(x: Int, y: Int) = ScenePoint(u(x), u(y))
    private fun base(id: String, frame: String? = null, style: ElementStyle = ElementStyle()) =
        ElementBase(ElementId(id), frameId = frame?.let { ElementId(it) }, style = style, seed = 7)

    private fun scene(elements: List<SceneElement>): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        for (e in elements) s = s.add(e).getOrNull()!!
        return s
    }

    private fun label(id: String, container: String, text: String, x: Int, y: Int, w: Int, h: Int, frame: String?) = TextElement(
        base(id, frame), u(x), u(y), u(w), u(h), text = text, containerId = ElementId(container),
        textAlign = TextAlign.Center, verticalAlign = VerticalAlign.Middle,
    )

    private fun line(x: Int) = ScenePoint(u(x), u(110))

    /** The same 15 elements as `DrawListGoldenTest`; the draw-list golden check below guards against drift. */
    private fun architecture15(): SketchSceneV2 {
        val blue = ElementStyle(backgroundColor = "#a5d8ff", roundness = Roundness(RoundnessKind.AdaptiveRadius))
        val dashed = ElementStyle(strokeStyle = StrokeStyle.Dashed)
        return scene(
            listOf(
                FrameElement(base("frame").copy(name = "Architecture"), u(0), u(0), u(400), u(220)),
                RectElement(base("web", "frame", blue), u(20), u(40), u(100), u(60)),
                label("web-t", "web", "Web", 20, 40, 100, 60, "frame"),
                RectElement(base("api", "frame", blue), u(160), u(40), u(100), u(60)),
                label("api-t", "api", "API", 160, 40, 100, 60, "frame"),
                RectElement(base("db", "frame", blue), u(300), u(40), u(100), u(60)),
                label("db-t", "db", "DB", 300, 40, 100, 60, "frame"),
                ArrowElement(base("web-api", "frame"), listOf(p(120, 70), p(160, 70)), Binding(ElementId("web"), 1.0, 0.5), Binding(ElementId("api"), 0.0, 0.5)),
                ArrowElement(
                    base("api-db", "frame", ElementStyle(roundness = Roundness(RoundnessKind.ProportionalRadius))),
                    listOf(p(260, 70), p(280, 60), p(300, 70)), Binding(ElementId("api"), 1.0, 0.5), Binding(ElementId("db"), 0.0, 0.5),
                    startArrowhead = Arrowhead.CircleOutline, endArrowhead = Arrowhead.Triangle,
                ),
                EllipseElement(base("cache", "frame", dashed), u(210), u(160), u(40), u(30)),
                DiamondElement(base("gate", "frame"), u(300), u(130), u(80), u(60)),
                LineElement(base("rule", "frame", dashed), listOf(line(20), line(380))),
                PointElement(base("origin", "frame"), u(20), u(200)),
                ArcElement(base("bend", "frame"), u(100), u(170), u(30), 0.0, 1.5707963267948966),
                TextElement(base("note"), u(20), u(240), u(96), u(25), text = "Note", fontSize = 20.0),
            ),
        )
    }

    private fun builder() = DrawListBuilder(FixedAdvanceTextMetricsProvider())

    @Test
    fun architecture15_should_MatchGolden_When_Exported() {
        val scene = architecture15()
        assertEquals(DrawListGolden.ARCHITECTURE_15, DrawListJson.encode(builder().build(scene, RenderStyle.Clean)), "fixture drifted from the draw-list golden")
        assertEquals(SvgGolden.ARCHITECTURE_15, SvgExporter.export(scene, builder(), RenderStyle.Clean))
    }

    @Test
    fun export_should_BeDeterministic_And_UseOneClipPathPerFrameRect() {
        val first = SvgExporter.export(architecture15(), builder(), RenderStyle.Clean)
        assertEquals(first, SvgExporter.export(architecture15(), builder(), RenderStyle.Clean))
        assertEquals(1, Regex("<clipPath ").findAll(first).count())
        assertTrue(first.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""))
    }

    @Test
    fun export_should_DrawSketchyScenesWithTheSameWriter() {
        val rough = scene(listOf(RectElement(base("r", style = ElementStyle(roughness = 1)), u(0), u(0), u(40), u(20))))
        val clean = SvgExporter.export(rough, builder(), RenderStyle.Clean)
        val sketchy = SvgExporter.export(rough, builder(), RenderStyle.Sketchy)
        assertTrue(sketchy.contains("<path "))
        assertTrue(sketchy != clean, "roughness above zero must restyle the geometry")
    }

    @Test
    fun background_should_BeOptional_And_PaddingShouldSetTheViewBox() {
        val s = scene(listOf(RectElement(base("r"), u(0), u(0), u(10), u(10))))
        val transparent = SvgExporter.export(s, builder(), RenderStyle.Clean)
        assertFalse(transparent.contains("fill=\"#ffffff\""))
        val white = SvgExporter.export(s, builder(), RenderStyle.Clean, options = SvgExportOptions(background = "#ffffff", padding = 0.0))
        assertTrue(white.contains("viewBox=\"-1 -1 12 12\""), white)
        assertTrue(white.contains("fill=\"#ffffff\""))
    }

    @Test
    fun image_should_EmbedAsDataUrl_WithFlipAndCrop_And_RefuseSvgAndMissingFiles() {
        val png = SvgImage("image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val svgMime = SvgImage("image/svg+xml", "<svg onload=\"alert(1)\"/>".encodeToByteArray())
        val svgBytes = SvgImage("image/png", "  <?xml version=\"1.0\"?><svg/>".encodeToByteArray())
        val source = SvgImageSource { id -> mapOf("ok" to png, "m" to svgMime, "b" to svgBytes)[id] }
        val s = scene(
            listOf(
                ImageElement(base("ok"), u(0), u(0), u(10), u(10), fileId = "ok", flipX = true, crop = ImageCrop(1.0, 1.0, 4.0, 4.0, 8.0, 8.0)),
                ImageElement(base("m"), u(20), u(0), u(10), u(10), fileId = "m"),
                ImageElement(base("b"), u(40), u(0), u(10), u(10), fileId = "b"),
                ImageElement(base("gone"), u(60), u(0), u(10), u(10), fileId = "gone\"><script>"),
            ),
        )
        val out = SvgExporter.export(s, builder(), RenderStyle.Clean, source)
        assertTrue(out.contains("href=\"data:image/png;base64,iVBORw0KGgo=\""), out)
        assertTrue(out.contains("scale(-1 1)"))
        assertTrue(out.contains("viewBox=\"1 1 4 4\""))
        assertFalse(out.contains("alert"))
        assertFalse(out.contains("<script>"))
        assertEquals(3, Regex("<rect [^>]*stroke-dasharray=\"4 4\"").findAll(out).count(), out)
        assertTrue(out.contains("data-file-id=\"gone&quot;&gt;&lt;script&gt;\""))
    }

    @Test
    fun numbers_should_PrintShortestDigits_AndNeverNegativeZero() {
        val tiny = SceneUnit.of(-0.00001).getOrNull()!!
        val third = SceneUnit.of(0.1 + 0.2).getOrNull()!!
        val s = scene(listOf(LineElement(base("l"), listOf(ScenePoint(u(0), u(0)), ScenePoint(third, tiny)))))
        val out = SvgExporter.export(s, builder(), RenderStyle.Clean)
        assertTrue(out.contains("L0.3 0"), out)
        assertFalse(out.contains("-0 "))
    }
}
