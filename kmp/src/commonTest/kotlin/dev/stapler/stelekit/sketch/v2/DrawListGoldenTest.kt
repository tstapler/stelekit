// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.PathSegment
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
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
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
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 2.1.1: draw-list goldens (identical on every target), frame clip, layer opacity, per-element cache. */
class DrawListGoldenTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)
    private fun p(x: Int, y: Int) = ScenePoint(u(x), u(y))
    private fun base(id: String, frame: String? = null, style: ElementStyle = ElementStyle(), layer: LayerId = LayerId.Default, opacity: Int = 100, version: Int = 1) =
        ElementBase(ElementId(id), layerId = layer, frameId = frame?.let { ElementId(it) }, style = style, opacity = opacity, version = version, seed = 7)

    private fun scene(elements: List<SceneElement>, layers: List<Layer>? = null): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        if (layers != null) s = s.withLayers(layers)
        for (e in elements) s = s.add(e).getOrNull()!!
        return s
    }

    private fun label(id: String, container: String, text: String, x: Int, y: Int, w: Int, h: Int, frame: String?) = TextElement(
        base(id, frame), u(x), u(y), u(w), u(h), text = text, containerId = ElementId(container),
        textAlign = dev.stapler.stelekit.sketch.v2.scene.TextAlign.Center, verticalAlign = dev.stapler.stelekit.sketch.v2.scene.VerticalAlign.Middle,
    )

    /** 15 elements: frame, 3 labelled boxes, 2 bound arrows, ellipse, diamond, line, point, arc, free text. */
    private fun architecture15(): SketchSceneV2 {
        val blue = ElementStyle(backgroundColor = "#a5d8ff", roundness = Roundness(RoundnessKind.AdaptiveRadius))
        val dashed = ElementStyle(strokeStyle = StrokeStyle.Dashed)
        return scene(
            listOf(
                FrameElement(base("frame", style = ElementStyle()).copy(name = "Architecture"), u(0), u(0), u(400), u(220)),
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
                LineElement(base("rule", "frame", dashed), listOf(p(20), p(380))),
                PointElement(base("origin", "frame"), u(20), u(200)),
                ArcElement(base("bend", "frame"), u(100), u(170), u(30), 0.0, 1.5707963267948966),
                TextElement(base("note"), u(20), u(240), u(96), u(25), text = "Note", fontSize = 20.0),
            ),
        )
    }

    private fun p(x: Int) = ScenePoint(u(x), u(110))

    private fun build(scene: SketchSceneV2, metrics: FixedAdvanceTextMetricsProvider = FixedAdvanceTextMetricsProvider()) =
        DrawListBuilder(metrics).build(scene, RenderStyle.Clean)

    @Test
    fun architecture15_should_MatchGolden_When_BuiltWithFixedAdvanceMetrics() {
        val scene = architecture15()
        assertEquals(15, scene.size)
        assertEquals(DrawListGolden.ARCHITECTURE_15, DrawListJson.encode(build(scene)))
    }

    @Test
    fun build_should_NeverMeasureText_When_RenderingPersistedLayout() {
        val metrics = FixedAdvanceTextMetricsProvider()
        build(architecture15(), metrics)
        assertEquals(0, metrics.measureCalls)
    }

    @Test
    fun frameMember_should_BeWrappedInClip_When_ChildExtendsPastFrame() {
        val s = scene(
            listOf(
                FrameElement(base("f"), u(0), u(0), u(100), u(100)),
                RectElement(base("r", "f"), u(80), u(80), u(60), u(60)),
                RectElement(base("free"), u(0), u(0), u(10), u(10)),
            ),
        )
        val ops = build(s)
        val clip = ops.filterIsInstance<DrawOp.ClipOp>().single()
        assertEquals(100.0, clip.rect.width)
        assertEquals("r", assertIs<DrawOp.PathOp>(clip.ops.single()).elementId.value)
        assertTrue(ops.filterIsInstance<DrawOp.PathOp>().any { it.elementId.value == "free" })
    }

    @Test
    fun opacity_should_Multiply_When_LayerAndElementBothHalf() {
        val half = Layer(LayerId("L"), "L", opacity = 0.5, order = 1)
        val s = scene(listOf(RectElement(base("r", layer = LayerId("L"), opacity = 50), u(0), u(0), u(10), u(10))), listOf(Layer.DefaultLayer, half))
        assertEquals(0.25, assertIs<DrawOp.PathOp>(build(s).single()).opacity)
    }

    @Test
    fun hiddenLayer_should_BeOmitted_And_LayerOrderShouldDecidePaintOrder() {
        val top = Layer(LayerId("top"), "top", order = 2)
        val hidden = Layer(LayerId("hid"), "hid", visible = false, order = 3)
        val s = scene(
            listOf(
                RectElement(base("a", layer = LayerId("top")), u(0), u(0), u(1), u(1)),
                RectElement(base("b"), u(0), u(0), u(1), u(1)),
                RectElement(base("c", layer = LayerId("hid")), u(0), u(0), u(1), u(1)),
            ),
            listOf(Layer.DefaultLayer, top, hidden),
        )
        assertEquals(listOf("b", "a"), build(s).map { (it as DrawOp.PathOp).elementId.value })
    }

    @Test
    fun cache_should_RebuildOnlyTheEditedElement_When_VersionBumps() {
        val builder = DrawListBuilder(FixedAdvanceTextMetricsProvider())
        val s1 = scene(listOf(RectElement(base("a"), u(0), u(0), u(5), u(5)), RectElement(base("b"), u(9), u(9), u(5), u(5))))
        val first = builder.build(s1, RenderStyle.Clean)
        assertEquals(2, builder.builtElementCount)
        val again = builder.build(s1, RenderStyle.Clean)
        assertEquals(2, builder.builtElementCount)
        assertSame(first[0], again[0])
        val edited = (s1["b".let { ElementId(it) }] as RectElement)
        val s2 = s1.replace(edited.copy(base = edited.base.copy(version = 2), width = u(7))).getOrNull()!!
        val third = builder.build(s2, RenderStyle.Clean)
        assertEquals(3, builder.builtElementCount)
        assertSame(first[0], third[0])
    }

    @Test
    fun image_should_BecomeAnImageOp_And_RoundedRectShouldCurve() {
        val s = scene(
            listOf(
                ImageElement(base("i"), u(1), u(2), u(3), u(4), fileId = null),
                RectElement(base("r", style = ElementStyle(roundness = Roundness(RoundnessKind.ProportionalRadius))), u(0), u(0), u(40), u(40)),
            ),
        )
        val ops = build(s)
        assertNull(assertIs<DrawOp.ImageOp>(ops[0]).fileId)
        assertTrue(assertIs<DrawOp.PathOp>(ops[1]).segments.any { it is PathSegment.CubicTo })
    }

    @Test
    fun sceneWithNoElements_should_BuildAnEmptyList() {
        assertTrue(build(SketchSceneV2.empty()).isEmpty())
    }
}
