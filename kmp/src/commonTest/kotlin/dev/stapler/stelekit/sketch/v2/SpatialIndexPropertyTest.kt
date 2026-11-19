// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 1.3.3a: the grid index answers exactly what a flat scan of `ElementBounds` answers, through any edit sequence. */
class SpatialIndexPropertyTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun randomElement(r: Random, i: Int): SceneElement {
        val base = ElementBase(id = ElementId("e$i"))
        val x = (r.nextDouble() - 0.5) * WORLD
        val y = (r.nextDouble() - 0.5) * WORLD
        return when (r.nextInt(KINDS)) {
            0 -> RectElement(base, u(x), u(y), u(1 + r.nextDouble() * MAX_SIDE), u(1 + r.nextDouble() * MAX_SIDE))
            1 -> EllipseElement(base, u(x), u(y), u(1 + r.nextDouble() * MAX_SIDE), u(1 + r.nextDouble() * MAX_SIDE))
            2 -> LineElement(base, listOf(SheetFixtures.pt(x, y), SheetFixtures.pt(x + r.nextDouble() * MAX_SIDE, y - r.nextDouble() * MAX_SIDE)))
            3 -> PointElement(base, u(x), u(y))
            4 -> ArcElement(base, u(x), u(y), u(1 + r.nextDouble() * MAX_SIDE), 0.0, 1.0 + r.nextDouble())
            else -> FrameElement(base, u(x), u(y), u(r.nextDouble() * HUGE_SIDE), u(r.nextDouble() * HUGE_SIDE))
        }
    }

    private fun brute(scene: SketchSceneV2, rect: Bounds): Set<ElementId> =
        scene.elementsInOrder.filter { ElementBounds.of(it).intersects(rect) }.mapTo(LinkedHashSet()) { it.id }

    private fun randomRect(r: Random): Bounds {
        val x = (r.nextDouble() - 0.5) * WORLD
        val y = (r.nextDouble() - 0.5) * WORLD
        val w = if (r.nextInt(WIDE_ONE_IN) == 0) WORLD else r.nextDouble() * VIEWPORT
        return Bounds(x, y, x + w, y + r.nextDouble() * VIEWPORT)
    }

    private fun randomCommand(r: Random, scene: SketchSceneV2): Command {
        val ids = scene.order
        val id = ids[r.nextInt(ids.size)]
        val e = scene[id]!!
        return when (r.nextInt(OPS)) {
            0 -> DeleteElements(listOf(id))
            1 -> if (e is RectElement || e is EllipseElement) RotateElement(id, r.nextDouble() * ANGLES) else MoveElements(listOf(id), 1.0, 1.0)
            2 -> if (e is RectElement) ResizeElement(id, e.x.value, e.y.value, r.nextDouble() * HUGE_SIDE, r.nextDouble() * HUGE_SIDE) else MoveElements(listOf(id), -1.0, 2.0)
            else -> MoveElements(listOf(id), (r.nextDouble() - 0.5) * MOVE, (r.nextDouble() - 0.5) * MOVE)
        }
    }

    @Test
    fun query_should_EqualBruteForce_When_5000ElementsAndRandomEdits() = runTest {
        checkAll(PropTestConfig(iterations = ITERATIONS), Arb.long()) { seed ->
            val r = Random(seed)
            var scene = SketchSceneV2.empty()
            for (i in 0 until ELEMENTS) scene = scene.add(randomElement(r, i)).fold({ error("fixture") }, { it })
            val index = SpatialIndex().also { it.rebuild(scene) }
            assertEquals(ELEMENTS, index.size)

            var doc = SketchDocumentV2(scene)
            repeat(EDITS) { step ->
                val applied = randomCommand(r, doc.scene).apply(doc).fold({ null }, { it }) ?: return@repeat
                doc = applied.doc
                index.update(doc.scene, applied.changedIds)
                if (step % CHECK_EVERY == 0) {
                    repeat(QUERIES) {
                        val rect = randomRect(r)
                        assertEquals(brute(doc.scene, rect), index.query(rect), "seed $seed step $step")
                    }
                }
            }
            assertEquals(doc.scene.size, index.size)
            val fresh = SpatialIndex().also { it.rebuild(doc.scene) }
            repeat(QUERIES) {
                val rect = randomRect(r)
                assertEquals(fresh.query(rect), index.query(rect), "incremental equals rebuilt, seed $seed")
            }
        }
    }

    @Test
    fun query_should_FindOversizedElements_And_ForgetRemovedOnes() {
        val huge = FrameElement(ElementBase(id = ElementId("huge")), u(-HUGE_WORLD), u(-HUGE_WORLD), u(2 * HUGE_WORLD), u(2 * HUGE_WORLD))
        val small = RectElement(ElementBase(id = ElementId("small")), u(10.0), u(10.0), u(5.0), u(5.0))
        var scene = SketchSceneV2.empty().add(huge).fold({ error("x") }, { it }).add(small).fold({ error("x") }, { it })
        val index = SpatialIndex().also { it.rebuild(scene) }
        val probe = Bounds(11.0, 11.0, 12.0, 12.0)
        assertEquals(setOf(ElementId("huge"), ElementId("small")), index.query(probe))
        assertEquals(setOf(ElementId("huge")), index.query(Bounds(100.0, 100.0, 101.0, 101.0)))

        scene = scene.remove(ElementId("huge")).fold({ error("x") }, { it })
        index.update(scene, listOf(ElementId("huge")))
        assertEquals(setOf(ElementId("small")), index.query(probe))
        assertTrue(index.query(Bounds(100.0, 100.0, 101.0, 101.0)).isEmpty())
        assertTrue(index.query(Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)).size == 1)
    }

    private companion object {
        const val ITERATIONS = 3
        const val ELEMENTS = 5_000
        const val EDITS = 120
        const val CHECK_EVERY = 10
        const val QUERIES = 12
        const val KINDS = 6
        const val OPS = 4
        const val WORLD = 40_000.0
        const val MAX_SIDE = 400.0
        const val HUGE_SIDE = 60_000.0
        const val HUGE_WORLD = 1.0e7
        const val VIEWPORT = 3_000.0
        const val WIDE_ONE_IN = 8
        const val ANGLES = 6.28
        const val MOVE = 5_000.0
    }
}
