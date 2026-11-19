// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.constraints.ConstraintParts
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.hit.PartHitTester
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Task 4.2.6a: `PartHitTester` priority, tolerance, layer rules, and agreement with a brute-force scan. */
class PartHitTesterTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private fun base(s: String, z: String = "a0", layer: LayerId = LayerId.Default) = ElementBase(id = id(s), zKey = ZKey(z), layerId = layer)

    private fun line(s: String, vararg xy: Double, b: ElementBase = base(s)) = LineElement(b, xy.toList().chunked(2).map { SheetFixtures.pt(it[0], it[1]) })

    private fun scene(vararg elements: SceneElement, layers: List<Layer> = listOf(Layer.DefaultLayer)): SketchSceneV2 =
        elements.fold(SketchSceneV2.empty().withLayers(layers)) { s, e -> s.add(e).fold({ error("fixture $it") }, { it }) }

    private fun testerOf(scene: SketchSceneV2) = PartHitTester(scene, SpatialIndex().also { it.rebuild(scene) })

    private fun PartHitTester.at(x: Double, y: Double, zoom: Double = 1.0, tolerance: Double = PartHitTester.MOUSE_TOLERANCE_PX) =
        hitPart(SheetFixtures.pt(x, y), zoom, tolerance)

    @Test
    fun endpoint_should_BeatTheBodyOfTheSameLine() {
        val t = testerOf(scene(line("l", 0.0, 0.0, 100.0, 0.0)))
        assertEquals(id("l") to TargetPart.End, t.at(97.0, 2.0)?.let { it.elementId to it.part })
        assertEquals(TargetPart.Edge(0), t.at(50.0, 2.0)?.part)
        assertNull(t.at(50.0, 20.0))
    }

    @Test
    fun point_should_BeatEdge_EvenWhenTheEdgeIsNearer() {
        val t = testerOf(scene(line("l", 0.0, 0.0, 100.0, 0.0), PointElement(base("p"), u(50.0), u(5.0))))
        val hit = t.at(50.0, 0.5)
        assertEquals(id("p"), hit?.elementId)
        assertEquals(TargetPart.Whole, hit?.part)
    }

    @Test
    fun sharedEndpoint_should_ReturnTheTopmostFirst_AndListTheOthers() {
        val a = line("a", 0.0, 0.0, 50.0, 0.0, b = base("a", "a1"))
        val b = line("b", 50.0, 0.0, 50.0, 40.0, b = base("b", "a2"))
        val t = testerOf(scene(a, b))
        val all = t.hitParts(SheetFixtures.pt(50.0, 0.0), 1.0)
        assertEquals(listOf(id("b") to TargetPart.Start, id("a") to TargetPart.End), all.take(2).map { it.elementId to it.part })
        assertEquals(id("b"), t.at(50.0, 0.0)?.elementId)
    }

    @Test
    fun tolerance_should_BeSixPixelsForMouseAndLargerForTouch_DividedByZoom() {
        val t = testerOf(scene(PointElement(base("p"), u(100.0), u(100.0))))
        assertEquals(id("p"), t.at(105.0, 100.0)?.elementId)
        assertNull(t.at(110.0, 100.0))
        assertEquals(id("p"), t.at(110.0, 100.0, tolerance = TOUCH_TOLERANCE_PX)?.elementId)
        assertNull(t.at(100.0, 100.0 + TOUCH_TOLERANCE_PX + 1.0, tolerance = TOUCH_TOLERANCE_PX))
        assertEquals(id("p"), t.at(160.0, 100.0, zoom = 0.1)?.elementId, "6 px is 60 units at zoom 0.1")
        assertNull(t.at(100.0, 100.0, zoom = 0.0))
        assertNull(t.at(100.0, 100.0, tolerance = 0.0))
    }

    @Test
    fun hiddenLayer_should_NeverBeATarget_ButALockedOneIs() {
        val hidden = Layer(LayerId("h"), "Hidden", visible = false, order = 1)
        val locked = Layer(LayerId("k"), "Locked", locked = true, order = 2)
        val s = scene(
            PointElement(base("ph", layer = LayerId("h")), u(0.0), u(0.0)),
            PointElement(base("pk", layer = LayerId("k")), u(100.0), u(0.0)),
            layers = listOf(Layer.DefaultLayer, hidden, locked),
        )
        val t = testerOf(s)
        assertNull(t.at(0.0, 0.0))
        assertEquals(id("pk"), t.at(100.0, 0.0)?.elementId)
    }

    @Test
    fun circleAndArcParts_should_BeHit_ByCentreRingAndEnds() {
        val circle = EllipseElement(base("c"), u(0.0), u(0.0), u(50.0), u(50.0))
        val arc = ArcElement(base("a"), u(200.0), u(0.0), u(50.0), 0.0, PI / 2)
        val t = testerOf(scene(circle, arc))
        assertEquals(TargetPart.Centre, t.at(1.0, 1.0)?.part)
        assertEquals(TargetPart.Whole, t.at(0.0, 52.0)?.part)
        assertEquals(TargetPart.ArcStart, t.at(250.0, 1.0)?.part)
        assertEquals(TargetPart.ArcEnd, t.at(200.0, 51.0)?.part)
        assertNull(t.at(200.0, -50.0), "the unswept part of the arc's circle is not a target")
    }

    @Test
    fun rotatedRectangle_should_ExposeItsRotatedCorners() {
        val rect = RectElement(base("r"), u(0.0), u(0.0), u(100.0), u(20.0), angle = PI / 2)
        val corner = PartHitTester.cornersOf(rect)[0]
        val t = testerOf(scene(rect))
        assertEquals(TargetPart.Vertex(0), t.at(corner.first + 1.0, corner.second - 1.0)?.part)
    }

    private fun randomElement(r: Random, i: Int): SceneElement {
        val b = ElementBase(id = ElementId("e$i"), zKey = ZKey("a${r.nextInt(Z_VALUES)}"), layerId = if (r.nextInt(LAYER_ONE_IN) == 0) LayerId("h") else LayerId.Default)
        val x = r.nextDouble() * WORLD
        val y = r.nextDouble() * WORLD
        return when (r.nextInt(KINDS)) {
            0 -> PointElement(b, u(x), u(y))
            1 -> LineElement(b, List(2 + r.nextInt(3)) { SheetFixtures.pt(x + r.nextDouble() * SPAN, y + r.nextDouble() * SPAN) }, polygon = r.nextBoolean())
            2 -> RectElement(b, u(x), u(y), u(1 + r.nextDouble() * SPAN), u(1 + r.nextDouble() * SPAN), angle = if (r.nextBoolean()) 0.0 else r.nextDouble() * PI)
            3 -> u(1 + r.nextDouble() * SPAN / 2).let { radius -> EllipseElement(b, u(x), u(y), radius, radius) }
            else -> ArcElement(b, u(x), u(y), u(1 + r.nextDouble() * SPAN / 2), r.nextDouble() * PI, r.nextDouble() * 2 * PI)
        }
    }

    /** Independent re-statement of the part rules: no index, its own distances and its own ordering. */
    private fun brute(s: SketchSceneV2, px: Double, py: Double, zoom: Double, tolerance: Double): List<Pair<ElementId, TargetPart>> {
        val reach = tolerance / zoom
        data class Cand(val id: ElementId, val part: TargetPart, val tier: Int, val d: Double, val layer: Int, val z: String, val order: Int)
        val found = ArrayList<Cand>()
        for ((order, eid) in s.order.withIndex()) {
            val e = s[eid] ?: continue
            val layer = s.layers.first { it.id == e.base.layerId }
            if (!layer.visible) continue
            for (part in ConstraintParts.partsOf(e)) {
                val d = dist(e, part, px, py)
                if (d > reach) continue
                val tier = if (part.isPoint || e is PointElement) 0 else if (part is TargetPart.Edge) 1 else 2
                found += Cand(eid, part, tier, d, layer.order, e.base.zKey.value, order)
            }
        }
        return found.sortedWith(compareBy<Cand> { it.tier }.thenBy { it.d }.thenByDescending { it.layer }.thenByDescending { it.z }.thenByDescending { it.order })
            .map { it.id to it.part }
    }

    private fun seg(px: Double, py: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (((px - a.first) * dx + (py - a.second) * dy) / len).coerceIn(0.0, 1.0)
        return hypot(px - (a.first + t * dx), py - (a.second + t * dy))
    }

    private fun turn(a: Double): Double = ((a % (2 * PI)) + 2 * PI) % (2 * PI)

    private fun dist(e: SceneElement, part: TargetPart, px: Double, py: Double): Double {
        fun to(p: Pair<Double, Double>) = hypot(px - p.first, py - p.second)
        return when (e) {
            is PointElement -> to(e.x.value to e.y.value)
            is LineElement -> {
                val p = e.points.map { it.x.value to it.y.value }
                when (part) {
                    TargetPart.Start -> to(p.first())
                    TargetPart.End -> to(p.last())
                    is TargetPart.Vertex -> to(p[part.index])
                    is TargetPart.Edge -> seg(px, py, p[part.index], p[(part.index + 1) % p.size])
                    else -> error("unexpected $part")
                }
            }
            is RectElement -> {
                val c = PartHitTester.cornersOf(e)
                when (part) {
                    is TargetPart.Vertex -> to(c[part.index])
                    is TargetPart.Edge -> seg(px, py, c[part.index], c[(part.index + 1) % 4])
                    else -> error("unexpected $part")
                }
            }
            is EllipseElement -> if (part == TargetPart.Centre) to(e.cx.value to e.cy.value) else abs(hypot(px - e.cx.value, py - e.cy.value) - e.rx.value)
            is ArcElement -> arcDist(e, part, px, py)
            else -> error("unexpected $e")
        }
    }

    private fun arcDist(e: ArcElement, part: TargetPart, px: Double, py: Double): Double {
        val c = e.cx.value to e.cy.value
        val r = e.radius.value
        val start = (c.first + r * cos(e.startAngle)) to (c.second + r * sin(e.startAngle))
        val end = (c.first + r * cos(e.endAngle)) to (c.second + r * sin(e.endAngle))
        fun to(p: Pair<Double, Double>) = hypot(px - p.first, py - p.second)
        return when (part) {
            TargetPart.Centre -> to(c)
            TargetPart.ArcStart -> to(start)
            TargetPart.ArcEnd -> to(end)
            else -> if (turn(atan2(py - c.second, px - c.first) - e.startAngle) <= turn(e.endAngle - e.startAngle)) abs(to(c) - r) else min(to(start), to(end))
        }
    }

    @Test
    fun hitParts_should_EqualBruteForce_When_RandomScenesAndPointers() = runTest {
        checkAll(PropTestConfig(iterations = ITERATIONS), Arb.long()) { seed ->
            val r = Random(seed)
            val layers = listOf(Layer.DefaultLayer, Layer(LayerId("h"), "Hidden", visible = false, order = 1))
            var s = SketchSceneV2.empty().withLayers(layers)
            repeat(ELEMENTS) { i -> s = s.add(randomElement(r, i)).fold({ error("fixture $it") }, { it }) }
            val tester = testerOf(s)
            repeat(POINTERS) {
                val zoom = listOf(0.25, 1.0, 4.0)[r.nextInt(3)]
                val tolerance = if (r.nextBoolean()) PartHitTester.MOUSE_TOLERANCE_PX else TOUCH_TOLERANCE_PX
                val px = r.nextDouble() * WORLD
                val py = r.nextDouble() * WORLD
                val got = tester.hitParts(SheetFixtures.pt(px, py), zoom, tolerance).map { it.elementId to it.part }
                assertEquals(brute(s, px, py, zoom, tolerance), got, "seed $seed at ($px, $py) zoom $zoom")
            }
        }
    }

    private companion object {
        const val TOUCH_TOLERANCE_PX = 22.0
        const val ITERATIONS = 60
        const val ELEMENTS = 40
        const val POINTERS = 40
        const val WORLD = 300.0
        const val SPAN = 90.0
        const val KINDS = 5
        const val Z_VALUES = 3
        const val LAYER_ONE_IN = 6
    }
}
