// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintEligibility
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.DofWording
import dev.stapler.stelekit.sketch.v2.constraints.Eligibility
import dev.stapler.stelekit.sketch.v2.constraints.ElementKind
import dev.stapler.stelekit.sketch.v2.constraints.SketchReasonText
import dev.stapler.stelekit.sketch.v2.constraints.SolverTierGate
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
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 4.2.1d (UX-81, UX-83): the one eligibility table, its reason strings, the command error and the DOF wording. */
class ConstraintEligibilityTest {
    private fun u(i: Int) = SceneUnit.ofInt(i)
    private fun base(id: String) = ElementBase(id = ElementId(id))
    private val twoPoints = listOf(ScenePoint(u(0), u(0)), ScenePoint(u(5), u(5)))

    private val elements: Map<ElementKind, SceneElement> = mapOf(
        ElementKind.Line to LineElement(base("line"), twoPoints),
        ElementKind.Rect to RectElement(base("rect"), u(0), u(0), u(10), u(10)),
        ElementKind.Circle to EllipseElement(base("circle"), u(5), u(5), u(3), u(3)),
        ElementKind.Ellipse to EllipseElement(base("ellipse"), u(5), u(5), u(3), u(4)),
        ElementKind.Arc to ArcElement(base("arc"), u(0), u(0), u(5), 0.0, 1.0),
        ElementKind.Point to PointElement(base("point"), u(1), u(1)),
        ElementKind.Diamond to DiamondElement(base("diamond"), u(0), u(0), u(10), u(10)),
        ElementKind.Arrow to ArrowElement(base("arrow"), twoPoints),
        ElementKind.Freedraw to FreedrawElement(base("free"), twoPoints),
        ElementKind.Text to TextElement(base("text"), u(0), u(0), u(10), u(10), text = "t"),
        ElementKind.Image to ImageElement(base("image"), u(0), u(0), u(10), u(10), fileId = null),
        ElementKind.Frame to FrameElement(base("frame"), u(0), u(0), u(10), u(10)),
    )

    private val notConstrainable = listOf(ElementKind.Text, ElementKind.Arrow, ElementKind.Image, ElementKind.Freedraw, ElementKind.Frame, ElementKind.Diamond)

    @Test
    fun elementKind_should_MapEverySceneElement_AndSplitCircleFromEllipse() {
        for ((kind, element) in elements) assertEquals(kind, ElementKind.of(element), element::class.simpleName)
    }

    @Test
    fun reason_should_GiveTheOneNotConstrainableString_ForEveryNonTechnicalKindAndEveryConstraint() {
        for (kind in notConstrainable) for (constraint in ConstraintKind.entries) {
            assertEquals(Eligibility.Ineligible(SketchReasonText.NotConstrainable), ConstraintEligibility.reason(kind, constraint), "$kind $constraint")
        }
        assertTrue(SketchReasonText.NotConstrainable.startsWith("Text, arrows, images, freehand and frames can't be constrained."))
    }

    @Test
    fun reason_should_RejectAnUnequalEllipseWithOnlyCircles() {
        for (constraint in ConstraintKind.entries) {
            assertEquals(Eligibility.Ineligible("Only circles can be constrained"), ConstraintEligibility.reason(ElementKind.Ellipse, constraint))
        }
    }

    @Test
    fun reason_should_NameWhatEachKindNeeds_WhenTheElementCannotTakeIt() {
        assertEquals(Eligibility.Ineligible("Radius needs a circle or arc"), ConstraintEligibility.reason(ElementKind.Line, ConstraintKind.Radius))
        assertEquals(Eligibility.Ineligible("Radius needs a circle or arc"), ConstraintEligibility.reason(ElementKind.Point, ConstraintKind.Radius))
        assertEquals(Eligibility.Ineligible("Parallel needs two lines"), ConstraintEligibility.reason(ElementKind.Circle, ConstraintKind.Parallel))
        assertEquals(Eligibility.Eligible, ConstraintEligibility.reason(ElementKind.Circle, ConstraintKind.Radius))
        assertEquals(Eligibility.Eligible, ConstraintEligibility.reason(ElementKind.Arc, ConstraintKind.Diameter))
        assertEquals(Eligibility.Eligible, ConstraintEligibility.reason(ElementKind.Line, ConstraintKind.Parallel))
        assertEquals(Eligibility.Eligible, ConstraintEligibility.reason(ElementKind.Rect, ConstraintKind.Horizontal))
        assertEquals(Eligibility.Eligible, ConstraintEligibility.reason(ElementKind.Point, ConstraintKind.Coincident))
    }

    @Test
    fun everyTechnicalKind_should_TakeTheKindsThatAreNotKindSpecific() {
        for (kind in listOf(ElementKind.Line, ElementKind.Rect, ElementKind.Circle, ElementKind.Arc, ElementKind.Point)) {
            for (constraint in listOf(ConstraintKind.Coincident, ConstraintKind.Fixed, ConstraintKind.Distance)) {
                assertEquals(Eligibility.Eligible, ConstraintEligibility.reason(kind, constraint), "$kind $constraint")
            }
            assertTrue(ConstraintEligibility.isConstrainable(kind))
        }
        for (kind in notConstrainable + ElementKind.Ellipse) assertTrue(!ConstraintEligibility.isConstrainable(kind), "$kind")
    }

    @Test
    fun reasonOfAnElement_should_AgreeWithReasonOfItsKind() {
        for ((kind, element) in elements) for (constraint in ConstraintKind.entries) {
            assertEquals(ConstraintEligibility.reason(kind, constraint), ConstraintEligibility.reason(element, constraint))
        }
    }

    @Test
    fun notConstrainableCommandError_should_CarryTheKind() {
        val error: CommandError = CommandError.NotConstrainable(ElementKind.Text)
        assertEquals(ElementKind.Text, (error as CommandError.NotConstrainable).kind)
    }

    @Test
    fun dofWording_should_AlwaysCarryItsGloss() {
        assertEquals("2 degrees of freedom (2 moves still possible)", DofWording.statusWithDetail(2))
        assertEquals("1 degree of freedom (1 move still possible)", DofWording.statusWithDetail(1))
        assertEquals("Fully constrained (nothing can move)", DofWording.statusWithDetail(0))
        assertEquals("A degree of freedom is one way the sketch can still change shape or move.", DofWording.Explanation)
    }

    @Test
    fun tierGate_should_ExposeAllKindsOnFull_TheFiveGeometricOnesOnGeometricOnly_AndNoneWhenHidden() {
        val geometric = setOf(ConstraintKind.Coincident, ConstraintKind.Horizontal, ConstraintKind.Vertical, ConstraintKind.Equal, ConstraintKind.Distance)
        for (kind in ConstraintKind.entries) {
            assertTrue(SolverTierGate.exposes(SolverTier.Full, kind))
            assertEquals(kind in geometric, SolverTierGate.exposes(SolverTier.GeometricOnly, kind), "$kind")
            assertTrue(!SolverTierGate.exposes(SolverTier.Hidden, kind))
        }
    }
}
