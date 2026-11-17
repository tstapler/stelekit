// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.CanvasFocus
import dev.stapler.stelekit.ui.sketch.v2.CanvasTraversal
import dev.stapler.stelekit.ui.sketch.v2.FocusRingContrast
import dev.stapler.stelekit.ui.sketch.v2.FocusRingPlacement
import dev.stapler.stelekit.ui.sketch.v2.FocusRingStyle
import dev.stapler.stelekit.ui.sketch.v2.SceneSemantics
import dev.stapler.stelekit.ui.sketch.v2.SemanticsView
import dev.stapler.stelekit.ui.sketch.v2.TabMove
import dev.stapler.stelekit.ui.sketch.v2.TabStop
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6.2: per-element semantics under the cap (UX-44), the Tab walk, the skip link (UX-117) and the two-tone ring (UX-45). */
class SceneSemanticsModelTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun scene(vararg elements: SceneElement, layers: List<Layer>? = null): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        layers?.let { s = s.withLayers(it) }
        for (e in elements) s = s.add(e).fold({ error("fixture $it") }, { it })
        return s
    }

    private fun rect(id: String, x: Double, y: Double, w: Double = 40.0, h: Double = 20.0, ordinal: Int = 1) =
        RectElement(SheetFixtures.base(id, ordinal), u(x), u(y), u(w), u(h))

    private val wide = SemanticsView(SketchViewport.of(), 800.0, 600.0)

    private fun nodeLabels(scene: SketchSceneV2, view: SemanticsView = wide, selection: Set<ElementId> = emptySet()) =
        SceneSemantics.build(scene, view, selection).nodes.map { it.label }

    // --- UX-44 -----------------------------------------------------------------------------------------------------

    @Test
    fun semantics_should_EmitAtMost200NodesAndLabelArrow_When_3000Elements() {
        val api = RectElement(SheetFixtures.base("api", 1), u(0.0), u(0.0), u(80.0), u(40.0), boundElements = listOf(BoundRef(ElementId("api-label"), BoundKind.Text)))
        val db = RectElement(SheetFixtures.base("db", 2), u(200.0), u(0.0), u(80.0), u(40.0), boundElements = listOf(BoundRef(ElementId("db-label"), BoundKind.Text)))
        val apiLabel = TextElement(SheetFixtures.base("api-label", 3), u(5.0), u(5.0), u(40.0), u(20.0), text = "API", containerId = ElementId("api"))
        val dbLabel = TextElement(SheetFixtures.base("db-label", 4), u(205.0), u(5.0), u(40.0), u(20.0), text = "DB", containerId = ElementId("db"))
        val arrow = ArrowElement(
            SheetFixtures.base("arrow", 5), listOf(SheetFixtures.pt(80.0, 20.0), SheetFixtures.pt(200.0, 20.0)),
            startBinding = Binding(ElementId("api"), 1.0, 0.5), endBinding = Binding(ElementId("db"), 0.0, 0.5),
        )
        var s = scene(api, db, apiLabel, dbLabel, arrow)
        for (i in 0 until 3000) s = s.add(rect("far$i", 10_000.0 + i * 50.0, 5_000.0, ordinal = 10 + i)).fold({ error("fixture") }, { it })

        val tree = SceneSemantics.build(s, wide, selection = emptySet())

        assertEquals(s.size, 3005)
        assertTrue(tree.nodes.size <= s.limits.maxSemanticsNodes, "emitted ${tree.nodes.size}")
        assertTrue("Arrow from API to DB" in tree.nodes.map { it.label })
        assertEquals("Showing 3 of 3,003 elements for screen reader; use Find to reach others", tree.statement)
    }

    @Test
    fun ux44_semantics_should_LabelByKindAndCapAt200WithStatement() {
        var s = SketchSceneV2.empty()
        for (i in 0 until 300) s = s.add(rect("r$i", i * 10.0, 0.0, 8.0, 8.0, ordinal = i + 1)).fold({ error("fixture") }, { it })
        val cap = s.limits.maxSemanticsNodes
        val tree = SceneSemantics.build(s, SemanticsView(SketchViewport.of(), 100_000.0, 600.0), emptySet())
        assertEquals(cap, tree.nodes.size)
        assertEquals("Showing 200 of 300 elements for screen reader; use Find to reach others", tree.statement)
        assertEquals("Rectangle 1, 8 by 8", tree.nodes.first().label)
    }

    @Test
    fun semantics_should_EmitEverythingAndNoStatement_When_TheSheetFitsTheCap() {
        val s = scene(rect("a", 0.0, 0.0), rect("b", 90_000.0, 90_000.0, ordinal = 2))
        val tree = SceneSemantics.build(s, wide, emptySet())
        assertEquals(2, tree.nodes.size)
        assertNull(tree.statement)
    }

    @Test
    fun semantics_should_KeepSelectedElementsOutOfView_When_OverTheCap() {
        var s = SketchSceneV2.empty()
        for (i in 0 until 400) s = s.add(rect("r$i", 5_000.0 + i * 100.0, 0.0, ordinal = i + 1)).fold({ error("fixture") }, { it })
        val tree = SceneSemantics.build(s, wide, setOf(ElementId("r399")))
        assertEquals(listOf("r399"), tree.nodes.map { it.id.value })
        assertTrue(tree.nodes.single().selected)
    }

    @Test
    fun semantics_should_SkipHiddenLayersAndBoundLabels_AndMarkLockedElements() {
        val hidden = Layer(LayerId("hide"), "Hidden", visible = false, order = 1)
        val locked = Layer(LayerId("lock"), "Locked", locked = true, order = 2)
        val container = RectElement(SheetFixtures.base("c", 1), u(0.0), u(0.0), u(80.0), u(40.0), boundElements = listOf(BoundRef(ElementId("t"), BoundKind.Text)))
        val label = TextElement(SheetFixtures.base("t", 2), u(5.0), u(5.0), u(40.0), u(20.0), text = "Kitchen", containerId = ElementId("c"))
        val gone = RectElement(SheetFixtures.base("g", 3, LayerId("hide")), u(0.0), u(100.0), u(10.0), u(10.0))
        val fixed = RectElement(SheetFixtures.base("f", 4, LayerId("lock")), u(0.0), u(200.0), u(10.0), u(10.0))
        val tree = SceneSemantics.build(scene(container, label, gone, fixed, layers = listOf(Layer.DefaultLayer, hidden, locked)), wide, emptySet())
        assertEquals(listOf("Rectangle 1, 80 by 40, labelled \"Kitchen\"", "Rectangle 4, 10 by 10, locked"), tree.nodes.map { it.label })
        assertEquals(listOf(ElementId("c")), tree.tabStops)
    }

    @Test
    fun labels_should_NameEveryKindWithItsDetail() {
        val s = scene(
            EllipseElement(SheetFixtures.base("c", 1), u(0.0), u(0.0), u(15.0), u(15.0)),
            EllipseElement(SheetFixtures.base("e", 1), u(0.0), u(50.0), u(20.0), u(10.0)),
            LineElement(SheetFixtures.base("h", 3), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(120.0, 0.0))),
            LineElement(SheetFixtures.base("v", 4), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(0.0, -30.0))),
            LineElement(SheetFixtures.base("p", 5), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(5.0, 5.0), SheetFixtures.pt(9.0, 1.0))),
            TextElement(SheetFixtures.base("x", 6), u(0.0), u(0.0), u(40.0), u(20.0), text = "first line\nsecond"),
            PointElement(SheetFixtures.base("pt", 4), u(3.0), u(4.0)),
            ArcElement(SheetFixtures.base("arc", 1), u(0.0), u(0.0), u(7.0), 0.0, 1.0),
            ArrowElement(SheetFixtures.base("free", 2), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(1.0, 1.0))),
        )
        assertEquals(
            listOf(
                "Circle 1, radius 15", "Ellipse 1, 40 by 20", "Line 3, horizontal, length 120", "Line 4, vertical, length 30",
                "Line 5, 3 points", "Text 6, \"first line\"", "Point 4, at 3, 4", "Arc 1, radius 7", "Arrow 2",
            ),
            nodeLabels(s),
        )
    }

    @Test
    fun label_should_ReadHalfBoundArrowsAndKeepAUserName() {
        val a = rect("a", 0.0, 0.0)
        val one = ArrowElement(SheetFixtures.base("one", 1), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(5.0, 5.0)), startBinding = Binding(ElementId("a"), 1.0, 0.5))
        val named = ArrowElement(
            SheetFixtures.base("named", 2).copy(name = "Feeds"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(5.0, 5.0)),
            startBinding = Binding(ElementId("a"), 1.0, 0.5), endBinding = Binding(ElementId("a"), 0.0, 0.5),
        )
        assertEquals(listOf("Rectangle 1, 40 by 20", "Arrow from Rectangle 1", "Feeds, arrow from Rectangle 1 to Rectangle 1"), nodeLabels(scene(a, one, named)))
    }

    @Test
    fun label_should_AppendAnAnnotationBeforeLocked() {
        val s = scene(RectElement(SheetFixtures.base("a", 3).copy(locked = true), u(0.0), u(0.0), u(8.0), u(8.0)))
        assertEquals("Rectangle 3, 8 by 8, horizontal, locked", SceneSemantics.label(s, s.elementsInOrder.single(), mapOf(ElementId("a") to "horizontal")))
    }

    // --- Tab traversal (UX-41, UX-117) -----------------------------------------------------------------------------

    private val stops = listOf(ElementId("a"), ElementId("b"))
    private val present = setOf(TabStop.TopBar, TabStop.ToolStrip, TabStop.Canvas, TabStop.ViewCluster, TabStop.StatusLine)

    @Test
    fun tab_should_WalkSkipLinkThenElementsThenLeaveToTheNextStop() {
        val first = CanvasTraversal.tab(CanvasTraversal.enter(backward = false, stops = stops), false, stops, present)
        assertEquals(TabMove.Within(CanvasFocus.Element(ElementId("a"))), first)
        val second = CanvasTraversal.tab(CanvasFocus.Element(ElementId("a")), false, stops, present)
        assertEquals(TabMove.Within(CanvasFocus.Element(ElementId("b"))), second)
        assertEquals(TabMove.Out(TabStop.ViewCluster), CanvasTraversal.tab(CanvasFocus.Element(ElementId("b")), false, stops, present))
    }

    @Test
    fun shiftTab_should_WalkBackAndLeaveToThePreviousStop() {
        assertEquals(CanvasFocus.Element(ElementId("b")), CanvasTraversal.enter(backward = true, stops = stops))
        assertEquals(TabMove.Within(CanvasFocus.SkipLink), CanvasTraversal.tab(CanvasFocus.Element(ElementId("a")), true, stops, present))
        assertEquals(TabMove.Out(TabStop.ToolStrip), CanvasTraversal.tab(CanvasFocus.SkipLink, true, stops, present))
        assertEquals(CanvasFocus.SkipLink, CanvasTraversal.enter(backward = true, stops = emptyList()))
    }

    @Test
    fun tab_should_NeverTrap_WhenTheCanvasHasNoElementsOrNoNeighbour() {
        assertEquals(TabMove.Out(TabStop.ViewCluster), CanvasTraversal.tab(CanvasFocus.SkipLink, false, emptyList(), present))
        assertEquals(TabMove.Out(null), CanvasTraversal.tab(CanvasFocus.SkipLink, false, emptyList(), setOf(TabStop.Canvas)))
    }

    @Test
    fun escapeThenTab_should_LeaveTheCanvasAtOnce() {
        val back = CanvasTraversal.escape(CanvasFocus.Element(ElementId("a")))
        assertEquals(CanvasFocus.Container, back)
        assertEquals(TabMove.Out(TabStop.ViewCluster), CanvasTraversal.tab(back, false, stops, present))
        assertEquals(CanvasFocus.Container, CanvasTraversal.escape(CanvasFocus.Container))
    }

    @Test
    fun skip_should_LandOnTheNextStopAfterTheCanvas_AndF6_OnTheNextRegion() {
        assertEquals(TabStop.ViewCluster, CanvasTraversal.skip(present))
        assertEquals(TabStop.SelectionBar, CanvasTraversal.skip(present + TabStop.SelectionBar))
        assertEquals(TabStop.ActionChipRow, CanvasTraversal.skip(present + TabStop.ActionChipRow + TabStop.SelectionBar))
        assertEquals(TabStop.ViewCluster, CanvasTraversal.f6(false, present))
        assertEquals(TabStop.ToolStrip, CanvasTraversal.f6(true, present))
    }

    // --- UX-45 two-tone ring -----------------------------------------------------------------------------------------

    private val highContrastLight = Pair(Color.Black, Color.White)
    private val highContrastDark = Pair(Color.White, Color.Black)

    private fun themes(): Map<String, FocusRingStyle> {
        val light = lightColorScheme()
        val dark = darkColorScheme()
        return mapOf(
            "light" to FocusRingStyle.of(light.onSurface, light.inverseOnSurface),
            "dark" to FocusRingStyle.of(dark.onSurface, dark.inverseOnSurface),
            "high-contrast light" to FocusRingStyle.of(highContrastLight.first, highContrastLight.second),
            "high-contrast dark" to FocusRingStyle.of(highContrastDark.first, highContrastDark.second),
        )
    }

    @Test
    fun ux45_focusRing_should_Meet3to1AgainstElementAndBackgroundAndNotBeHidden() {
        val fills = listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFFFFFF00), Color(0xFF1E1E1E), Color(0xFFB0B0B0))
        for ((theme, style) in themes()) {
            assertTrue(FocusRingContrast.ratio(style.inner, style.outer) >= FocusRingContrast.NON_TEXT_BAR, "$theme: the two tones must contrast with each other")
            for (fill in fills) assertTrue(FocusRingContrast.someToneContrasts(style, fill), "$theme: neither tone contrasts with $fill")
        }
    }

    @Test
    fun ux45_focusRing_should_UseTheTwoWidthsOfTwoDp() {
        val style = themes().getValue("light")
        assertEquals(2f, style.innerDp.value)
        assertEquals(2f, style.outerDp.value)
    }

    @Test
    fun ux45_focusRing_should_BePannedClearOfAnOverlay_WhenTheElementIsUnderIt() {
        val viewport = SketchViewport.of()
        val element = SceneBounds(100.0, 560.0, 140.0, 590.0)
        val visible = ScreenRect(0.0, 0.0, 800.0, 400.0)
        val ring = FocusRingPlacement.ringBounds(element, themes().getValue("light"), viewport.zoom, density = 1f)
        assertEquals(96.0, ring.minX)
        val revealed = FocusRingPlacement.revealed(viewport, ring, visible)
        assertTrue(ring.maxY * revealed.zoom + revealed.panY <= visible.bottom)
        assertTrue(ring.minY * revealed.zoom + revealed.panY >= visible.top)
        assertEquals(viewport.zoom, revealed.zoom)
    }

    @Test
    fun contrast_should_BeTheWcagRatio() {
        assertEquals(21.0, FocusRingContrast.ratio(Color.Black, Color.White), 1e-6)
        assertEquals(1.0, FocusRingContrast.ratio(Color.Gray, Color.Gray), 1e-9)
        assertFalse(FocusRingContrast.someToneContrasts(FocusRingStyle.of(Color.White, Color.White), Color.White))
        assertNotNull(SketchStrings.Semantics.SKIP_CANVAS_ELEMENTS)
    }
}
