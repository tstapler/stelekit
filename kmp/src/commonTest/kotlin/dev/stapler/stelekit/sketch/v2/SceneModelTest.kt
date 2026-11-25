// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PersistentHashTrie
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneModelTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun base(id: String) = ElementBase(id = ElementId(id))

    private fun rect(id: String, x: Int = 0) = RectElement(base(id), u(x), u(0), u(10), u(10))

    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    @Test
    fun add_should_RejectWithCapacityExceeded_When_SceneHolds5000Elements() {
        var scene = SketchSceneV2.empty()
        repeat(SceneLimits.Default.maxElements) { scene = scene.add(rect("e$it")).right() }
        assertEquals(SceneLimits.Default.maxElements, scene.size)
        val result = scene.add(rect("one-more"))
        assertEquals(CommandError.CapacityExceeded(SceneLimits.Default.maxElements), result.left())
        assertEquals(SceneLimits.Default.maxElements, scene.size)
    }

    @Test
    fun sceneUnit_should_ReturnNonFinite_When_NaNOrInfinity() {
        assertTrue(SceneUnit.of(Double.NaN).left() is ValueError.NonFinite)
        assertTrue(SceneUnit.of(Double.POSITIVE_INFINITY).left() is ValueError.NonFinite)
        assertTrue(SceneUnit.of(Double.NEGATIVE_INFINITY).left() is ValueError.NonFinite)
    }

    @Test
    fun sceneUnit_should_ReturnOutOfRange_When_BeyondMaxCoord() {
        assertTrue(SceneUnit.of(SceneLimits.MAX_COORD).isRight())
        assertTrue(SceneUnit.of(SceneLimits.MAX_COORD + 1.0).left() is ValueError.OutOfRange)
        assertTrue(SceneUnit.of(-SceneLimits.MAX_COORD - 1.0).left() is ValueError.OutOfRange)
    }

    @Test
    fun sceneUnit_should_NormaliseNegativeZero() {
        assertEquals(SceneUnit.ZERO, SceneUnit.of(-0.0).right())
        assertEquals(0.0.toRawBits(), SceneUnit.of(-0.0).right().value.toRawBits())
    }

    @Test
    fun ellipse_should_BeCircle_When_RadiiAreEqual() {
        val circle = EllipseElement(base("c"), u(0), u(0), u(10), u(10))
        val oval = EllipseElement(base("o"), u(0), u(0), u(10), u(6))
        assertTrue(circle.isCircle)
        assertFalse(oval.isCircle)
    }

    @Test
    fun ellipse_should_RejectNonPositiveRadius_When_CreatedThroughFactory() {
        assertTrue(EllipseElement.create(base("z"), u(0), u(0), u(0), u(5)).left() is ValueError.NotPositive)
        assertFailsWith<IllegalArgumentException> { EllipseElement(base("z"), u(0), u(0), u(5), u(0)) }
    }

    @Test
    fun arc_should_RequirePositiveRadiusAndFiniteAngles() {
        assertTrue(ArcElement.create(base("a"), u(0), u(0), u(5), 0.0, 1.5).isRight())
        assertTrue(ArcElement.create(base("a"), u(0), u(0), u(0), 0.0, 1.5).left() is ValueError.NotPositive)
        assertTrue(ArcElement.create(base("a"), u(0), u(0), u(5), Double.NaN, 1.5).left() is ValueError.NonFinite)
        assertTrue(ArcElement.create(base("a"), u(0), u(0), u(5), 0.0, Double.POSITIVE_INFINITY).left() is ValueError.NonFinite)
    }

    @Test
    fun pointAndArc_should_CarryElementBase_And_JoinTheScene() {
        val point = PointElement(base("p"), u(3), u(4))
        val arc = ArcElement(base("a"), u(0), u(0), u(7), 0.0, 1.0)
        val scene = SketchSceneV2.empty().add(point).right().add(arc).right()
        assertEquals(ElementId("p"), scene[ElementId("p")]!!.base.id)
        assertEquals(listOf(ElementId("p"), ElementId("a")), scene.order)
    }

    @Test
    fun when_should_BeExhaustive_OverEverySceneElementKind() {
        val all: List<SceneElement> = listOf(
            rect("r"), DiamondElement(base("d"), u(0), u(0), u(1), u(1)), EllipseElement(base("e"), u(0), u(0), u(1), u(1)),
            LineElement(base("l"), emptyList()), ArrowElement(base("ar"), emptyList()), FreedrawElement(base("f"), emptyList()),
            TextElement(base("t"), u(0), u(0), u(1), u(1), text = "hi"), ImageElement(base("i"), u(0), u(0), u(1), u(1), fileId = null),
            FrameElement(base("fr"), u(0), u(0), u(1), u(1)), PointElement(base("p"), u(0), u(0)),
            ArcElement(base("arc"), u(0), u(0), u(1), 0.0, 1.0),
            WallElement(base("w"), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(10), u(0))), u(2)),
            OpeningElement(base("o"), ElementId("w"), 0, u(1), u(3)),
        )
        val names = all.map {
            when (it) {
                is RectElement -> "rect"
                is DiamondElement -> "diamond"
                is EllipseElement -> "ellipse"
                is LineElement -> "line"
                is ArrowElement -> "arrow"
                is FreedrawElement -> "freedraw"
                is TextElement -> "text"
                is ImageElement -> "image"
                is FrameElement -> "frame"
                is PointElement -> "point"
                is ArcElement -> "arc"
                is WallElement -> "wall"
                is OpeningElement -> "opening"
            }
        }
        assertEquals(all.size, names.toSet().size)
    }

    @Test
    fun scene_should_ShareStructure_And_LeaveOldSnapshotUntouched() {
        val s0 = SketchSceneV2.empty().add(rect("a")).right().add(rect("b")).right()
        val s1 = s0.replace(rect("a", x = 99)).right()
        assertEquals(u(0), (s0[ElementId("a")] as RectElement).x)
        assertEquals(u(99), (s1[ElementId("a")] as RectElement).x)
        val s2 = s1.remove(ElementId("b")).right()
        assertEquals(1, s2.size)
        assertEquals(2, s1.size)
        assertNull(s2[ElementId("b")])
    }

    @Test
    fun scene_should_RejectDuplicateUnknownAndNonPermutation() {
        val s = SketchSceneV2.empty().add(rect("a")).right()
        assertTrue(s.add(rect("a")).left() is CommandError.DuplicateElement)
        assertTrue(s.replace(rect("zz")).left() is CommandError.UnknownElement)
        assertTrue(s.remove(ElementId("zz")).left() is CommandError.UnknownElement)
        assertTrue(s.reorder(listOf(ElementId("zz"))).left() is CommandError.UnknownElement)
        val two = s.add(rect("b")).right()
        assertTrue(two.reorder(listOf(ElementId("a"), ElementId("a"))).isLeft())
        assertEquals(listOf(ElementId("b"), ElementId("a")), two.reorder(listOf(ElementId("b"), ElementId("a"))).right().order)
    }

    @Test
    fun scene_should_AlwaysKeepTheDefaultLayer() {
        assertEquals(1, SketchSceneV2.empty().layers.size)
        assertTrue(SketchSceneV2.empty().withLayers(emptyList()).layers.isNotEmpty())
    }

    @Test
    fun trie_should_HandleFullHashCollisions() {
        // "Aa" and "BB" share String.hashCode(), so they chain in one leaf.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        var t = PersistentHashTrie.empty<Int>().put("Aa", 1).put("BB", 2)
        assertEquals(2, t.size)
        assertEquals(1, t.get("Aa"))
        assertEquals(2, t.get("BB"))
        t = t.put("Aa", 10)
        assertEquals(2, t.size)
        assertEquals(10, t.get("Aa"))
        t = t.remove("Aa")
        assertNull(t.get("Aa"))
        assertEquals(2, t.get("BB"))
        assertEquals(1, t.size)
    }

    @Test
    fun trie_should_MatchAHashMapModel_ForRandomPutAndRemoveSequences() = runTest {
        val ops = Arb.list(Arb.pair(Arb.string(0..4), Arb.int(0..1)), 0..120)
        checkAll(ops) { sequence ->
            var trie = PersistentHashTrie.empty<Int>()
            val model = HashMap<String, Int>()
            sequence.forEachIndexed { i, (key, op) ->
                if (op == 0) {
                    trie = trie.put(key, i)
                    model[key] = i
                } else {
                    trie = trie.remove(key)
                    model.remove(key)
                }
            }
            assertEquals(model.size, trie.size)
            assertEquals(model, trie.entries().toMap())
            for ((k, v) in model) assertEquals(v, trie.get(k))
        }
    }

    @Test
    fun trie_should_NotChangeOlderVersions_When_Updated() = runTest {
        checkAll(Arb.list(Arb.string(1..5).map { it }, 1..40)) { keys ->
            val base = keys.fold(PersistentHashTrie.empty<Int>()) { acc, k -> acc.put(k, k.length) }
            val before = base.entries().toMap()
            base.put("extra-key", 1).remove(keys.first())
            assertEquals(before, base.entries().toMap())
        }
    }
}
