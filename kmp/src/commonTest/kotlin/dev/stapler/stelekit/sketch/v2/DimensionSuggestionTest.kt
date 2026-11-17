// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.u
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.DimensionSuggestions
import dev.stapler.stelekit.sketch.v2.constraints.SuggestionKind
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 4.2.7c (UX-115): which selection suggests which dimension, with the current value prefilled. */
class DimensionSuggestionTest {
    private val newId = { ConstraintId("k1") }

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).get().doc

    private fun suggest(doc: SketchDocumentV2, vararg ids: String, dof: Int? = null, exposed: Set<ConstraintKind> = DimensionSuggestions.DefaultKinds) =
        DimensionSuggestions.suggest(doc, ids.map { ElementId(it) }, dof, newId, exposed)

    private fun id(s: String) = ElementId(s)

    @Test
    fun suggestion_should_OfferLengthForLine_RadiusForCircle_CentreToEdgeForCircleAndLine_AndNothingForTextOrArrow() {
        val doc = docOf(
            line("l1", 0, 0, 96, 0),
            circle("c1", 40, 40, 8),
            TextElement(ElementBase(id("t1")), u(0), u(0), u(10), u(10), text = "x"),
            ArrowElement(ElementBase(id("a1")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(5), u(5)))),
            line("l2", 0, 10, 10, 10),
        )
        val length = assertNotNull(suggest(doc, "l1"))
        assertEquals(SuggestionKind.Length, length.kind)
        assertEquals("Length 6 in?", length.label)
        assertEquals(Constraint(ConstraintId("k1"), ConstraintKind.Distance, listOf(ConstraintTarget(id("l1"))), 96.0), length.constraint)
        assertTrue(length.addable)

        val radius = assertNotNull(suggest(doc, "c1"))
        assertEquals("Radius 1/2 in?", radius.label)
        assertEquals(ConstraintKind.Radius, radius.constraint.kind)
        assertEquals(8.0, radius.constraint.value)
        assertTrue(radius.addable)

        val centre = assertNotNull(suggest(doc, "c1", "l1"))
        assertEquals(SuggestionKind.CentreToEdge, centre.kind)
        assertEquals("Centre to edge 2 1/2 in?", centre.label)
        assertEquals(listOf(ConstraintTarget(id("c1"), TargetPart.Centre), ConstraintTarget(id("l1"), TargetPart.Edge(0))), centre.constraint.targets)

        assertNull(suggest(doc, "t1"))
        assertNull(suggest(doc, "a1"))
        assertNull(suggest(doc, "l1", "l2"))
        assertNull(suggest(doc, "l1", "c1", "t1"))
        assertNull(suggest(doc, "missing"))
    }

    @Test
    fun suggestion_should_PrefillTextThatDimensionInputParsesBackToTheMeasure() {
        val doc = docOf(line("l1", 0, 0, 0, 27))
        val s = assertNotNull(suggest(doc, "l1"))
        assertEquals("1 11/16\"", s.prefill)
        assertEquals(27L, DimensionInput.parse(s.prefill).get().sixteenths)
    }

    @Test
    fun suggestion_should_MeasureCentreToEdgeToTheInfiniteLineAndRefuseADegenerateLine() {
        val doc = docOf(line("l1", 0, 0, 32, 0), circle("c1", 100, -48, 4), line("zero", 5, 5, 5, 5))
        val s = assertNotNull(suggest(doc, "c1", "l1"))
        assertEquals(48.0, s.constraint.value)
        assertEquals("Centre to edge 3 in?", s.label)
        assertNull(suggest(doc, "c1", "zero"))
    }

    @Test
    fun suggestion_should_ReportCentreToEdgeAddable_NowThatTheModelHasAPointToLineDistance() {
        val doc = docOf(line("l1", 0, 0, 32, 0), circle("c1", 10, 10, 4))
        assertTrue(assertNotNull(suggest(doc, "c1", "l1")).addable)
    }

    @Test
    fun suggestion_should_OfferRadiusForAnArc() {
        val arc = ArcElement(ElementBase(id("arc")), u(0), u(0), u(24), 0.0, 1.5)
        val s = assertNotNull(suggest(docOf(arc), "arc"))
        assertEquals("Radius 1 1/2 in?", s.label)
    }

    @Test
    fun suggestion_should_OfferNothingForAPolylineOrAPolygon() {
        val poly = line("p", 0, 0, 10, 0).copy(points = listOf(ScenePoint(u(0), u(0)), ScenePoint(u(5), u(5)), ScenePoint(u(9), u(0))))
        assertNull(suggest(docOf(poly), "p"))
    }

    @Test
    fun suggestion_should_NotDependOnSelectionOrder() {
        val doc = docOf(line("l1", 0, 0, 96, 0), circle("c1", 40, 40, 8))
        assertEquals(suggest(doc, "c1", "l1")?.constraint?.targets, suggest(doc, "l1", "c1")?.constraint?.targets)
    }

    @Test
    fun remaining_should_OfferTheFirstElementWithoutADimension_OnlyWhileFreedomRemains() {
        val doc = docOf(line("l1", 0, 0, 96, 0), circle("c1", 40, 40, 8), rect("r1", 0, 0, 64, 32))
        val first = assertNotNull(suggest(doc, dof = 3))
        assertEquals(SuggestionKind.Remaining, first.kind)
        assertEquals("Suggested: Width of Line 1", first.label)
        assertNull(suggest(doc, dof = 0))
        assertNull(suggest(doc, dof = null))
    }

    @Test
    fun remaining_should_SkipElementsThatAlreadyHaveADrivingDimension() {
        val base = docOf(line("l1", 0, 0, 96, 0), circle("c1", 40, 40, 8))
        val dimensioned = listOf<Command>(AddConstraint(Constraint(ConstraintId("d"), ConstraintKind.Distance, listOf(ConstraintTarget(id("l1"))), 96.0)))
            .fold(base) { d, c -> c.apply(d).get().doc }
        val next = assertNotNull(suggest(dimensioned, dof = 2))
        assertEquals(ConstraintKind.Radius, next.constraint.kind)
        assertTrue(next.label.startsWith("Suggested: Radius of "))
    }

    @Test
    fun remaining_should_NameAVerticalLineHeightAndASlantedOneLength() {
        assertTrue(assertNotNull(suggest(docOf(line("v", 0, 0, 0, 32)), dof = 1)).label.startsWith("Suggested: Height of "))
        assertTrue(assertNotNull(suggest(docOf(line("s", 0, 0, 30, 40)), dof = 1)).label.startsWith("Suggested: Length of "))
    }

    @Test
    fun exposedKinds_should_NarrowWhatMaySuggest() {
        val doc = docOf(line("l1", 0, 0, 96, 0), circle("c1", 40, 40, 8))
        assertNull(suggest(doc, "l1", exposed = setOf(ConstraintKind.Radius)))
        assertNotNull(suggest(doc, "c1", exposed = setOf(ConstraintKind.Radius)))
        assertNull(suggest(doc, "c1", exposed = emptySet()))
    }
}
