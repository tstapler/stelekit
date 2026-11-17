// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 4.2.3c (UX-106): glyph collapse into count badges, fan-out of a selected element, the zoom rule and the no-overlap property. */
class ConstraintGlyphLayoutTest {
    private fun anchor(n: Int, x: Double, y: Double, vararg elements: String) = GlyphAnchor(ConstraintId("k$n"), x, y, elements.map { ElementId(it) }.toSet())

    private fun layout(anchors: List<GlyphAnchor>, zoom: Double = 1.0, pointer: PointerClass = PointerClass.MouseOnly, selected: Set<ElementId> = emptySet(), showAll: Boolean = false) =
        ConstraintGlyphLayout.layout(anchors, zoom, pointer, selected, showAll)

    private fun overlap(a: GlyphMark, b: GlyphMark, hit: Int) = abs(a.x - b.x) < hit && abs(a.y - b.y) < hit

    private fun assertNoOverlap(result: GlyphLayout, seed: Long) {
        val marks = result.marks
        for (i in marks.indices) {
            val clash = marks.drop(i + 1).firstOrNull { overlap(marks[i], it, result.hitDp) }
            assertTrue(clash == null, "seed $seed: ${marks[i]} overlaps $clash")
        }
    }

    private fun randomAnchors(seed: Long, count: Int, span: Double): List<GlyphAnchor> {
        val random = Random(seed)
        return List(count) { i -> anchor(i, random.nextDouble(span), random.nextDouble(span), "e${random.nextInt(ELEMENTS)}") }
    }

    @Test
    fun layout_should_KeepAnIsolatedGlyphWhereItIs() {
        val result = layout(listOf(anchor(1, 100.0, 100.0, "a")))
        assertEquals(listOf<GlyphMark>(GlyphMark.Glyph(ConstraintId("k1"), 100.0, 100.0)), result.marks)
        assertEquals(24, result.hitDp)
    }

    @Test
    fun layout_should_CollapseGlyphsWithinOneHitAreaIntoOneCountBadge() {
        val result = layout(listOf(anchor(1, 100.0, 100.0, "a"), anchor(2, 110.0, 105.0, "b"), anchor(3, 105.0, 95.0, "c")))
        val badge = result.marks.single() as GlyphMark.Badge
        assertEquals(3, badge.ids.size)
    }

    @Test
    fun layout_should_UseTheTouchFloorForAHitArea_WhenATouchOrPenPointerIsPresent() {
        val anchors = listOf(anchor(1, 100.0, 100.0, "a"), anchor(2, 130.0, 100.0, "b"))
        assertEquals(2, layout(anchors, pointer = PointerClass.MouseOnly).marks.size, "30 dp apart clears a 24 dp area")
        val touch = layout(anchors, pointer = PointerClass.TouchOrPen)
        assertEquals(44, touch.hitDp)
        assertEquals(1, touch.marks.size, "but not a 44 dp one")
    }

    @Test
    fun layout_should_FanOutASelectedElementsGlyphs_EvenWhereTheyWouldHaveCollapsed() {
        val anchors = listOf(anchor(1, 100.0, 100.0, "a"), anchor(2, 101.0, 100.0, "a"), anchor(3, 102.0, 100.0, "a"), anchor(4, 400.0, 400.0, "z"))
        val result = layout(anchors, pointer = PointerClass.TouchOrPen, selected = setOf(ElementId("a")))
        val fanned = result.marks.filterIsInstance<GlyphMark.Glyph>().filter { it.id.value in setOf("k1", "k2", "k3") }.sortedBy { it.x }
        assertEquals(3, fanned.size, "no badge for a selected element")
        for ((left, right) in fanned.zipWithNext()) {
            assertTrue(right.x - left.x >= 44 + ConstraintGlyphLayout.FAN_GAP_DP - EPSILON, "hit areas keep 4 dp between them")
        }
    }

    @Test
    fun layout_should_ReplaceUnselectedGlyphsWithAPerElementBadge_BelowHalfZoom_UnlessShowAllIsOn() {
        val anchors = listOf(anchor(1, 100.0, 100.0, "a"), anchor(2, 100.0, 300.0, "a"), anchor(3, 300.0, 100.0, "b"))
        val low = layout(anchors, zoom = 0.4)
        val badges = low.marks.filterIsInstance<GlyphMark.ElementBadge>()
        assertEquals(mapOf(ElementId("a") to 2, ElementId("b") to 1), badges.associate { it.elementId to it.ids.size }, "a count badge on the element remains")
        assertTrue(low.marks.none { it is GlyphMark.Glyph })
        val forced = layout(anchors, zoom = 0.4, showAll = true)
        assertEquals(3, forced.marks.filterIsInstance<GlyphMark.Glyph>().size, "Show constraint glyphs overrides")
        val selectedStaysFull = layout(anchors, zoom = 0.4, selected = setOf(ElementId("a")))
        assertEquals(2, selectedStaysFull.marks.filterIsInstance<GlyphMark.Glyph>().size, "a selected element keeps its glyphs")
    }

    @Test
    fun layout_should_NeverLeaveTwoVisibleTargetsOverlapping_For200ConstraintsAtRandom() = runTest {
        checkAll(PROPERTY_RUNS, Arb.long()) { seed ->
            for (pointer in PointerClass.entries) {
                for (span in listOf(SPAN_DENSE, SPAN_SPARSE)) {
                    val result = layout(randomAnchors(seed, CONSTRAINTS, span), pointer = pointer)
                    assertNoOverlap(result, seed)
                    assertEquals(CONSTRAINTS, result.marks.sumOf { it.ids.size }, "every constraint is still reachable from some mark")
                    assertEquals(CONSTRAINTS, result.marks.flatMap { it.ids }.toSet().size)
                }
            }
        }
    }

    @Test
    fun layout_should_KeepEveryConstraintReachable_AtLowZoomToo() = runTest {
        checkAll(PROPERTY_RUNS, Arb.long()) { seed ->
            val result = layout(randomAnchors(seed, CONSTRAINTS, SPAN_DENSE), zoom = 0.3)
            assertNoOverlap(result, seed)
            assertEquals(CONSTRAINTS, result.marks.sumOf { it.ids.size })
        }
    }

    private companion object {
        const val CONSTRAINTS = 200
        const val ELEMENTS = 40
        const val PROPERTY_RUNS = 25
        const val SPAN_DENSE = 400.0
        const val SPAN_SPARSE = 3000.0
        const val EPSILON = 1e-9
    }
}
