// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.SuggestionKind
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 4.2.7d (UX-115, D-21, ADR-003): which tier shows which suggestion chip, and the chip's row entry. */
class DimensionChipsTest {
    private val newId = { ConstraintId("k1") }

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).get().doc

    private fun pick(doc: SketchDocumentV2, tier: SolverTier, dof: Int?, vararg ids: String) =
        DimensionChips.suggestion(doc, ids.map { ElementId(it) }, dof, tier, newId)

    @Test
    fun hidden_should_ShowNoChip_AndGeometricOnlyShouldKeepLengthButDropRadius() {
        val doc = docOf(line("a", 0, 0, 96, 0), circle("c", 40, 40, 8))
        assertNull(pick(doc, SolverTier.Hidden, null, "a"))
        assertEquals(SuggestionKind.Length, pick(doc, SolverTier.GeometricOnly, null, "a")?.kind)
        assertNull(pick(doc, SolverTier.GeometricOnly, null, "c"), "Radius is not exposed on the geometric-only tier")
        assertEquals(SuggestionKind.Radius, pick(doc, SolverTier.Full, null, "c")?.kind)
    }

    @Test
    fun centreToEdge_should_BeOfferedNow_ThatAPointToLineDistanceIsAcceptedByTheModel() {
        val doc = docOf(line("a", 0, 0, 96, 0), circle("c", 40, 40, 8))
        val s = assertNotNull(pick(doc, SolverTier.Full, null, "c", "a"))
        assertEquals(SuggestionKind.CentreToEdge, s.kind)
        assertEquals("Centre to edge 2 1/2 in?", s.label)
        assertTrue(s.addable)
    }

    @Test
    fun noChip_should_AppearForTextlessSelections_TwoLines_OrNoFreedomLeft() {
        val doc = docOf(line("a", 0, 0, 96, 0), line("b", 0, 10, 10, 10))
        assertNull(pick(doc, SolverTier.Full, null, "a", "b"))
        assertNull(pick(doc, SolverTier.Full, 0), "nothing selected and no freedom: no suggestion")
        assertNull(pick(doc, SolverTier.Full, null), "nothing selected and an unknown count: no suggestion")
    }

    @Test
    fun remainingFreedom_should_SuggestTheFirstLengthWithoutADimension() {
        val doc = docOf(line("a", 0, 0, 96, 0), line("b", 0, 10, 10, 10))
            .let { AddConstraint(Constraint(ConstraintId("d"), ConstraintKind.Distance, listOf(ConstraintTarget(ElementId("a"))), 96.0)).apply(it).get().doc }
        val s = assertNotNull(pick(doc, SolverTier.Full, 3))
        assertEquals(SuggestionKind.Remaining, s.kind)
        assertEquals("Suggested: Width of Line 2", s.label)
    }

    @Test
    fun actionChip_should_CarryTheSuggestionAndTheActionWord_AndOnlyAcceptWhenActivated() {
        val doc = docOf(line("a", 0, 0, 96, 0))
        var accepted = 0
        val chip = DimensionChips.actionChip(assertNotNull(pick(doc, SolverTier.Full, null, "a"))) { accepted++ }
        assertEquals("Length 6 in? Dimension", chip.label)
        assertEquals("Length 6 in? Dimension", chip.accessibleName)
        assertEquals(DimensionChipTags.CHIP, chip.tag)
        assertEquals(0, accepted)
        chip.onClick()
        assertEquals(1, accepted)
        assertEquals(0, doc.constraints.size, "the chip itself adds nothing")
    }
}
