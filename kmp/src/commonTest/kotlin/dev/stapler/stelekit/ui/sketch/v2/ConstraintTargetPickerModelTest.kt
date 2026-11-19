// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 4.2.6b (UX-98): the armed picker's rules, with no screen. */
class ConstraintTargetPickerModelTest {
    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).get().doc

    private fun at(element: String, part: TargetPart) = ConstraintTarget(ElementId(element), part)

    private val newId = { ConstraintId("k1") }

    private fun scope(doc: SketchDocumentV2) = doc.scene.order

    private val twoLines get() = docOf(line("a", 0, 0, 10, 0), line("b", 10, 0, 10, 10))

    @Test
    fun candidates_should_FollowZOrderThenStartEndCentreEdges_AndNameEachTarget() {
        val doc = docOf(line("a", 0, 0, 10, 0), circle("c", 30, 30, 4))
        val all = TargetPicker.candidates(doc, TargetPicker.arm(ConstraintKind.Distance), scope(doc))
        assertEquals(listOf("Line 1 start", "Line 1 end", "Line 1 edge", "Circle 1 centre"), all.map { it.listName })
        assertEquals(listOf(MarkerShape.Square, MarkerShape.Square, MarkerShape.Bar, MarkerShape.Circle), all.map { it.shape })
        assertEquals(listOf("Endpoint of Line 1", "Endpoint of Line 1", "Edge of Line 1", "Centre of Circle 1"), all.map { it.chip })
    }

    @Test
    fun candidates_should_ShowEdgesAsBars_AndOnlyTargetsAValidConstraintCanUse() {
        val doc = docOf(line("a", 0, 0, 10, 0), rect("r", 20, 20))
        val tangent = TargetPicker.candidates(doc, TargetPicker.arm(ConstraintKind.Parallel), scope(doc))
        assertTrue(tangent.isNotEmpty() && tangent.all { it.shape == MarkerShape.Bar }, "Parallel marks edges only: ${tangent.map { it.listName }}")
        assertEquals(listOf("Line 1 edge", "Rectangle 1 edge 1", "Rectangle 1 edge 2", "Rectangle 1 edge 3", "Rectangle 1 edge 4"), tangent.map { it.listName })
        assertEquals("Edge of Rectangle 1", tangent[1].chip)
    }

    @Test
    fun candidates_should_OnlyCoverTheScopeGiven() {
        val doc = twoLines
        val picks = TargetPicker.candidates(doc, TargetPicker.arm(ConstraintKind.Coincident), listOf(ElementId("b")))
        assertTrue(picks.isNotEmpty() && picks.all { it.target.elementId == ElementId("b") })
    }

    @Test
    fun candidates_should_NameTheOtherEndpointAtTheSamePlace() {
        val doc = twoLines
        val all = TargetPicker.candidates(doc, TargetPicker.arm(ConstraintKind.Coincident), scope(doc))
        val shared = all.first { it.target == at("a", TargetPart.End) }
        assertEquals(listOf("Line 2 start"), shared.also)
        assertEquals("Endpoint of Line 1, also: Line 2 start", shared.chipText)
        assertEquals(emptyList(), all.first { it.target == at("a", TargetPart.Start) }.also)
    }

    @Test
    fun pick_should_CreateOneCoincidentFromTwoPicks_WithTheToastDetails() {
        val doc = twoLines
        val first = assertIs<PickStep.Next>(TargetPicker.pick(doc, TargetPicker.arm(ConstraintKind.Coincident), at("a", TargetPart.End), newId))
        assertEquals(listOf(at("a", TargetPart.End)), first.state.picks)
        val done = assertIs<PickStep.Complete>(TargetPicker.pick(doc, first.state, at("b", TargetPart.Start), newId))
        assertEquals(Constraint(ConstraintId("k1"), ConstraintKind.Coincident, listOf(at("a", TargetPart.End), at("b", TargetPart.Start))), done.constraint)
        assertEquals("Added Coincident: Line 1 end, Line 2 start", done.details)
        val toast = ConstraintToasts.addedByPick(done.details)
        assertEquals("Constraint added.", toast.text)
        assertEquals(done.details, toast.details)
        assertFalse(done.needsValue)
        assertEquals(1, AddConstraint(done.constraint).apply(doc).get().doc.constraints.size)
    }

    @Test
    fun pick_should_RefuseTheSamePartTwice_AndTwoPartsAlreadyCoincident_WithPickADifferentTarget() {
        val doc = twoLines
        val armed = assertIs<PickStep.Next>(TargetPicker.pick(doc, TargetPicker.arm(ConstraintKind.Coincident), at("a", TargetPart.End), newId)).state
        val same = assertIs<PickStep.Rejected>(TargetPicker.pick(doc, armed, at("a", TargetPart.End), newId))
        assertEquals(SketchConstraintStrings.Picker.DIFFERENT_TARGET, same.message)
        assertEquals(armed, same.state, "the first pick is kept")

        val joined = AddConstraint(Constraint(ConstraintId("old"), ConstraintKind.Coincident, listOf(at("b", TargetPart.Start), at("a", TargetPart.End)))).apply(doc).get().doc
        val again = assertIs<PickStep.Rejected>(TargetPicker.pick(joined, armed, at("b", TargetPart.Start), newId))
        assertEquals(SketchConstraintStrings.Picker.DIFFERENT_TARGET, again.message)
        assertEquals(1, joined.constraints.size)
    }

    @Test
    fun pick_should_RefuseAPartTheArmedConstraintCannotUse() {
        val doc = twoLines
        val parallel = TargetPicker.arm(ConstraintKind.Parallel)
        assertIs<PickStep.Rejected>(TargetPicker.pick(doc, parallel, at("a", TargetPart.Start), newId))
        assertIs<PickStep.Rejected>(TargetPicker.pick(doc, parallel, at("missing", TargetPart.Start), newId))
    }

    @Test
    fun pick_should_PinOnePointAtOnce_ForFixed() {
        val doc = twoLines
        val done = assertIs<PickStep.Complete>(TargetPicker.pick(doc, TargetPicker.arm(ConstraintKind.Fixed), at("a", TargetPart.Start), newId))
        assertEquals("Added Fixed: Line 1 start", done.details)
    }

    @Test
    fun pick_should_MeasureACentreToEdgeDistance_AndAskForItsValue() {
        val doc = docOf(line("a", 0, 0, 40, 0), circle("c", 10, 7, 3))
        val armed = assertIs<PickStep.Next>(TargetPicker.pick(doc, TargetPicker.arm(ConstraintKind.Distance), at("c", TargetPart.Centre), newId)).state
        val edges = TargetPicker.candidates(doc, armed, scope(doc))
        assertTrue(edges.any { it.target == at("a", TargetPart.Edge(0)) }, "the line's edge can be picked after a point: ${edges.map { it.listName }}")
        val done = assertIs<PickStep.Complete>(TargetPicker.pick(doc, armed, at("a", TargetPart.Edge(0)), newId))
        assertTrue(done.needsValue)
        assertEquals(7.0, done.constraint.value!!, 1e-6)
        assertEquals("Added Distance: Circle 1 centre, Line 1 edge", done.details)
    }

    @Test
    fun statusText_should_SayWhatIsNeededNext() {
        val doc = twoLines
        val s = TargetPicker.arm(ConstraintKind.Coincident)
        assertEquals("Pick the first point", TargetPicker.statusText(s, TargetPicker.candidates(doc, s, scope(doc))))
        val second = assertIs<PickStep.Next>(TargetPicker.pick(doc, s, at("a", TargetPart.End), newId)).state
        assertEquals("Pick the second point", TargetPicker.statusText(second, TargetPicker.candidates(doc, second, scope(doc))))
        val parallel = TargetPicker.arm(ConstraintKind.Parallel)
        assertEquals("Pick an edge", TargetPicker.statusText(parallel, TargetPicker.candidates(doc, parallel, scope(doc))))
        assertEquals(SketchConstraintStrings.Picker.NO_TARGETS, TargetPicker.statusText(s, emptyList()))
    }

    @Test
    fun moveFocus_should_WrapBothWays_AndStartAtTheEndForShiftTab() {
        val s = TargetPicker.arm(ConstraintKind.Coincident)
        assertEquals(0, TargetPicker.moveFocus(s, 3, FocusStep.Next).focused)
        assertEquals(2, TargetPicker.moveFocus(s, 3, FocusStep.Previous).focused)
        assertEquals(0, TargetPicker.moveFocus(s.copy(focused = 2), 3, FocusStep.Next).focused)
        assertEquals(2, TargetPicker.moveFocus(s.copy(focused = 0), 3, FocusStep.Previous).focused)
        assertEquals(PickerState.NONE, TargetPicker.moveFocus(s.copy(focused = 1), 0, FocusStep.Next).focused)
    }

    @Test
    fun everyShownMarker_should_BePickable_AndNeverRejectedAsInvalid() {
        val doc = docOf(line("a", 0, 0, 10, 0), rect("r", 20, 20), circle("c", 40, 40, 4))
        for (kind in ConstraintKind.entries) {
            val state = TargetPicker.arm(kind)
            for (c in TargetPicker.candidates(doc, state, scope(doc))) {
                val step = TargetPicker.pick(doc, state, c.target, newId)
                assertFalse(step is PickStep.Rejected, "$kind ${c.listName} was shown but refused")
            }
        }
    }
}
