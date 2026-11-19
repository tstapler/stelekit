// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.hit.HitTester
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Task 1.3.3b: narrow-phase rules per kind, tolerance of 6 px over zoom, skipped and stacked elements. */
class HitTesterTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private val filled = ElementStyle(backgroundColor = "#ffcc00")

    private fun base(s: String, z: String = "a0", style: ElementStyle = ElementStyle(), layer: LayerId = LayerId.Default, locked: Boolean = false) =
        ElementBase(id = id(s), zKey = ZKey(z), style = style, layerId = layer, locked = locked)

    private fun rect(s: String, x: Double = 0.0, y: Double = 0.0, w: Double = 100.0, h: Double = 100.0, angle: Double = 0.0, b: ElementBase = base(s)) =
        RectElement(b, u(x), u(y), u(w), u(h), angle)

    private fun tester(vararg elements: SceneElement, layers: List<Layer> = listOf(Layer.DefaultLayer)): HitTester {
        var scene = SketchSceneV2.empty().withLayers(layers)
        for (e in elements) scene = scene.add(e).fold({ error("fixture $it") }, { it })
        return HitTester(scene, SpatialIndex().also { it.rebuild(scene) })
    }

    private fun HitTester.at(x: Double, y: Double, zoom: Double = 1.0): ElementId? = hit(SheetFixtures.pt(x, y), zoom)

    @Test
    fun hollowRect_should_HitOnlyNearTheOutline_AtZoomOne() {
        val t = tester(rect("r"))
        assertNull(t.at(50.0, 10.0), "10 units inside the edge misses")
        assertEquals(id("r"), t.at(50.0, 4.0))
        assertEquals(id("r"), t.at(-5.0, 50.0), "just outside the edge, within 6")
        assertNull(t.at(-7.0, 50.0))
        assertNull(t.at(50.0, 50.0))
    }

    @Test
    fun tolerance_should_ScaleWithZoom_SixtyUnits_AtPointOne() {
        val t = tester(rect("r", w = 1000.0, h = 1000.0))
        assertEquals(id("r"), t.at(500.0, 59.0, 0.1))
        assertEquals(id("r"), t.at(-59.0, 500.0, 0.1))
        assertNull(t.at(500.0, 61.0, 0.1))
        assertNull(t.at(-61.0, 500.0, 0.1))
        assertNull(t.at(500.0, 1.0, 10.0), "tolerance is 0.6 at zoom 10")
        assertEquals(id("r"), t.at(500.0, 0.5, 10.0))
        assertNull(t.at(0.0, 0.0, 0.0))
        assertNull(t.at(0.0, 0.0, Double.NaN))
    }

    @Test
    fun filledRect_should_HitAnywhereInside() {
        val t = tester(rect("r", b = base("r", style = filled)))
        assertEquals(id("r"), t.at(50.0, 50.0))
        assertNull(t.at(150.0, 50.0))
    }

    @Test
    fun rotatedRect_should_HitInItsRotatedFrame() {
        val bar = rect("bar", w = 100.0, h = 20.0, angle = PI / 2, b = base("bar", style = filled))
        val t = tester(bar)
        assertEquals(id("bar"), t.at(50.0, -30.0), "the bar now stands up")
        assertNull(t.at(95.0, 10.0), "the old right end is empty")
    }

    @Test
    fun topmost_should_Win_ByZKeyAndLayerOrder() {
        val low = rect("low", b = base("low", "a1", filled))
        val high = rect("high", b = base("high", "a2", filled))
        assertEquals(id("high"), tester(low, high).at(50.0, 50.0))
        assertEquals(listOf(id("high"), id("low")), tester(low, high).hitAll(SheetFixtures.pt(50.0, 50.0), 1.0))

        val upper = Layer(LayerId("up"), "Up", order = 1)
        val onUpper = rect("onUpper", b = base("onUpper", "a0", filled, LayerId("up")))
        assertEquals(id("onUpper"), tester(high, onUpper, layers = listOf(Layer.DefaultLayer, upper)).at(50.0, 50.0))
    }

    @Test
    fun hiddenAndLockedLayers_And_LockedElements_should_BeSkipped() {
        val hidden = Layer(LayerId("h"), "H", visible = false)
        val locked = Layer(LayerId("l"), "L", locked = true)
        val layers = listOf(Layer.DefaultLayer, hidden, locked)
        val under = rect("under", b = base("under", "a0", filled))
        val onHidden = rect("onHidden", b = base("onHidden", "a9", filled, LayerId("h")))
        val onLocked = rect("onLocked", b = base("onLocked", "a9", filled, LayerId("l")))
        val lockedElement = rect("lockedElement", b = base("lockedElement", "a9", filled, locked = true))
        assertEquals(id("under"), tester(under, onHidden, onLocked, lockedElement, layers = layers).at(50.0, 50.0))
    }

    @Test
    fun frame_should_HitOnlyItsBorder_SoMembersStayReachable() {
        val frame = FrameElement(base("fr", "a9"), u(0.0), u(0.0), u(200.0), u(200.0))
        val member = rect("m", 50.0, 50.0, 40.0, 40.0, b = base("m", "a0", filled))
        val t = tester(member, frame)
        assertEquals(id("m"), t.at(60.0, 60.0))
        assertEquals(id("fr"), t.at(100.0, 3.0))
        assertNull(t.at(150.0, 150.0))
    }

    @Test
    fun point_should_HitWithinTheToleranceDisc() {
        val t = tester(PointElement(base("p"), u(10.0), u(10.0)))
        assertEquals(id("p"), t.at(13.0, 14.0))
        assertNull(t.at(17.0, 14.0))
    }

    @Test
    fun arc_should_HitOnTheArcOnly_NotTheRestOfItsCircle() {
        val arc = ArcElement(base("arc"), u(0.0), u(0.0), u(100.0), 0.0, PI / 2)
        val t = tester(arc)
        val s = 100.0 * cos(PI / 4)
        assertEquals(id("arc"), t.at(s, 100.0 * sin(PI / 4)))
        assertNull(t.at(-s, s), "on the circle but outside the sweep")
        assertEquals(id("arc"), t.at(100.0, -4.0), "near the start end point")
        assertNull(t.at(0.0, 0.0))
    }

    @Test
    fun ellipse_should_HitOutlineWhenHollow_And_InteriorWhenFilled() {
        val hollow = tester(EllipseElement(base("e"), u(0.0), u(0.0), u(50.0), u(20.0)))
        assertEquals(id("e"), hollow.at(50.0, 0.0))
        assertEquals(id("e"), hollow.at(0.0, 20.0))
        assertNull(hollow.at(0.0, 0.0))
        val solid = tester(EllipseElement(base("e", style = filled), u(0.0), u(0.0), u(50.0), u(20.0)))
        assertEquals(id("e"), solid.at(0.0, 0.0))
        assertNull(solid.at(0.0, 40.0))
    }

    @Test
    fun diamond_should_FollowItsSlantedEdges() {
        val t = tester(DiamondElement(base("d"), u(0.0), u(0.0), u(100.0), u(100.0)))
        assertEquals(id("d"), t.at(25.0, 25.0), "on the upper-left edge")
        assertNull(t.at(5.0, 5.0), "bounding-box corner is outside the diamond")
        assertNull(t.at(50.0, 50.0))
    }

    @Test
    fun polyline_should_HitNearAnySegment_And_ClosedFilledPolygonInside() {
        val line = LineElement(base("l"), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(100.0, 0.0), SheetFixtures.pt(100.0, 100.0)))
        val t = tester(line)
        assertEquals(id("l"), t.at(50.0, 4.0))
        assertEquals(id("l"), t.at(104.0, 60.0))
        assertNull(t.at(50.0, 60.0))
        val poly = LineElement(base("p", style = filled), listOf(SheetFixtures.pt(0.0, 0.0), SheetFixtures.pt(100.0, 0.0), SheetFixtures.pt(100.0, 100.0)), polygon = true)
        assertEquals(id("p"), tester(poly).at(80.0, 20.0))
        assertNull(tester(poly).at(20.0, 80.0))
    }

    @Test
    fun text_should_HitOverItsWholeBox() {
        val text = TextElement(base("t"), u(0.0), u(0.0), u(100.0), u(30.0), text = "hello")
        val t = tester(text)
        assertEquals(id("t"), t.at(50.0, 15.0))
        assertNull(t.at(50.0, 60.0))
    }
}
