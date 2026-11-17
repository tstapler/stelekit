// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.u
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.DofWording
import dev.stapler.stelekit.sketch.v2.constraints.SketchReasonText
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.2.3 (UX-30, UX-31, UX-32, UX-33, UX-37, UX-83, UX-84): the pure models behind the Constraints tab, the flyout and the conflict card. */
class ConstraintPanelModelTest {
    private fun id(s: String) = ElementId(s)

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).get().doc

    private fun c(name: String, kind: ConstraintKind, vararg targets: String, value: Double? = null) =
        Constraint(ConstraintId(name), kind, targets.map { ConstraintTarget(id(it)) }, value)

    private fun TestScope.runner() = SolveRunner(context = StandardTestDispatcher(testScheduler))

    private fun SketchDocumentV2.with(vararg cs: Constraint): SketchDocumentV2 = cs.fold(this) { d, k -> AddConstraint(k).apply(d).get().doc }

    private fun flyout(vararg selected: SceneElement, tier: SolverTier = SolverTier.Full): ConstrainFlyout = ConstrainFlyoutModel.of(selected.toList(), tier)

    private fun ConstrainFlyout.open(): ConstrainFlyout.Open = assertIs<ConstrainFlyout.Open>(this)

    private fun ConstrainFlyout.Open.state(item: ConstrainMenuItem) = items.first { it.item == item }

    @Test
    fun status_should_BeAbsent_OnADiagramSheetWithNoConstraints_AndShowThreeStatesOtherwise() {
        val free = docOf(*Array(54) { rect("r$it", it * 20, 0) })
        assertNull(ConstraintPanelModel.status(free, ConstraintAnalysis(dof = 216), technical = false), "54 free elements print no degrees of freedom (D-40)")
        assertEquals(StatusKind.Free, ConstraintPanelModel.status(free, ConstraintAnalysis(dof = 2), technical = true)!!.kind, "a technical sheet always shows it")

        val constrained = docOf(line("a", 0, 0, 10, 0)).with(c("h", ConstraintKind.Horizontal, "a"))
        val two = ConstraintPanelModel.status(constrained, ConstraintAnalysis(dof = 2), technical = false)!!
        assertEquals("2 degrees of freedom (2 moves still possible)", two.text)
        val one = ConstraintPanelModel.status(constrained, ConstraintAnalysis(dof = 1), technical = false)!!
        assertEquals("1 degree of freedom (1 move still possible)", one.text)
        val fully = ConstraintPanelModel.status(constrained, ConstraintAnalysis(dof = 0), technical = false)!!
        assertEquals(StatusKind.Fully, fully.kind)
        assertEquals("Fully constrained (nothing can move)", fully.text)
        val over = ConstraintPanelModel.status(constrained, ConstraintAnalysis(conflicts = setOf(ConstraintId("h"))), technical = false)!!
        assertEquals("Over-constrained: 1 conflict", over.text)
        assertNull(ConstraintPanelModel.status(constrained, ConstraintAnalysis.Unknown, technical = false), "no count yet, so no text")
    }

    @Test
    fun status_should_AgreeWithTheSolver_When_ReadFromTheRunnersAnalysis() = runTest {
        val doc = docOf(line("a", 0, 0, 10, 0)).with(c("h", ConstraintKind.Horizontal, "a"))
        val analysis = ConstraintAnalysis.from(runner().analyze(doc))
        val dof = assertNotNull(analysis.dof)
        assertTrue(dof > 0)
        assertEquals(DofWording.statusWithDetail(dof), ConstraintPanelModel.status(doc, analysis, technical = false)!!.text)
    }

    @Test
    fun rows_should_ShowStateAsGlyphAndWords_AndNameTheirGeometry() {
        val doc = docOf(line("a", 0, 0, 10, 0), line("b", 0, 5, 10, 5))
            .with(c("h", ConstraintKind.Horizontal, "a"), c("p", ConstraintKind.Parallel, "a", "b"), c("d", ConstraintKind.Distance, "a", value = 160.0))
        val analysis = ConstraintAnalysis(dof = 1, redundant = setOf(ConstraintId("p")), conflicts = setOf(ConstraintId("d")))
        val rows = ConstraintPanelModel.rows(doc, analysis, technical = true).associateBy { it.id.value }
        assertEquals(ConstraintRowState.Ok, rows.getValue("h").state)
        assertEquals("✓", rows.getValue("h").state.glyph)
        assertEquals(ConstraintRowState.Redundant, rows.getValue("p").state)
        assertTrue(rows.getValue("p").text.contains("(Redundant)"))
        assertEquals(ConstraintRowState.Conflict, rows.getValue("d").state)
        assertEquals("✖", rows.getValue("d").state.glyph)
        assertTrue(rows.getValue("d").text.contains("10\""), "a technical sheet shows the value in inches: ${rows.getValue("d").text}")
        assertEquals(setOf(id("a"), id("b")), rows.getValue("p").elementIds)
        assertTrue(rows.getValue("d").dimensional)
        for (row in rows.values) assertTrue(row.state.glyph.isNotEmpty() && row.state.stateText.isNotEmpty(), "never colour alone")
    }

    @Test
    fun flyout_should_EnableTheSingleElementKinds_AndDisableTheTwoElementKindsWithReasons_ForOneLine() {
        val open = flyout(line("l1", 0, 0, 10, 0)).open()
        for (item in listOf(ConstrainMenuItem.Horizontal, ConstrainMenuItem.Vertical, ConstrainMenuItem.Fixed, ConstrainMenuItem.Distance)) {
            assertTrue(open.state(item).enabled, "$item is a one-line kind")
        }
        val parallel = open.state(ConstrainMenuItem.Parallel)
        assertFalse(parallel.enabled)
        assertEquals("Parallel needs two lines", parallel.reason)
        assertEquals(SketchReasonText.CoincidentNeedsTwo, open.state(ConstrainMenuItem.Coincident).reason)
        assertEquals(SketchReasonText.TangentNeedsCurve, open.state(ConstrainMenuItem.Tangent).reason)
        assertFalse(open.state(ConstrainMenuItem.RadiusOrDiameter).enabled)
        assertEquals(ConstrainMenuItem.entries.size, open.items.size, "disabled items are shown, not hidden (UX-37)")
    }

    @Test
    fun flyout_should_EnableRadiusAndDiameter_ForACircle_AndTwoElementKindsForTwoLines() {
        assertTrue(flyout(circle("c", 0, 0, 5)).open().state(ConstrainMenuItem.RadiusOrDiameter).enabled)
        val two = flyout(line("a", 0, 0, 10, 0), line("b", 0, 5, 10, 9)).open()
        for (item in listOf(ConstrainMenuItem.Parallel, ConstrainMenuItem.Perpendicular, ConstrainMenuItem.Angle, ConstrainMenuItem.Equal, ConstrainMenuItem.Coincident)) {
            assertTrue(two.state(item).enabled, "$item takes two lines")
        }
        assertTrue(flyout(line("a", 0, 0, 10, 0), circle("c", 5, 5, 2)).open().state(ConstrainMenuItem.Tangent).enabled)
        val three = flyout(line("a", 0, 0, 10, 0), line("b", 0, 5, 10, 9), line("c", 0, 9, 4, 4)).open()
        assertFalse(three.state(ConstrainMenuItem.Parallel).enabled, "three lines are not a Parallel")
    }

    @Test
    fun flyout_should_BeDisabledWithAReason_WhenNothingIsSelected_AndEveryItemDisabledForText() {
        assertEquals(ConstrainFlyout.Disabled("Select an element to constrain"), flyout())
        val arrow = ArrowElement(ElementBase(id("arr")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(5), u(5))))
        val open = flyout(arrow).open()
        for (state in open.items) {
            assertFalse(state.enabled, "${state.item}")
            assertEquals(SketchReasonText.NotConstrainable, state.reason)
        }
        assertEquals(setOf<ConstrainMenuItem>(), open.enabled)
        assertEquals(SketchReasonText.OnlyCircles, flyout(EllipseElement(ElementBase(id("e")), u(0), u(0), u(4), u(2))).open().state(ConstrainMenuItem.Horizontal).reason)
    }

    @Test
    fun flyout_should_FollowTheSolverTier() {
        assertEquals(ConstrainFlyout.Hidden, flyout(line("a", 0, 0, 10, 0), tier = SolverTier.Hidden))
        val geometric = flyout(line("a", 0, 0, 10, 0), tier = SolverTier.GeometricOnly).open().items.map { it.item }.toSet()
        for (hidden in listOf(ConstrainMenuItem.Tangent, ConstrainMenuItem.Symmetric, ConstrainMenuItem.Angle, ConstrainMenuItem.Fixed, ConstrainMenuItem.RadiusOrDiameter, ConstrainMenuItem.Parallel)) {
            assertFalse(hidden in geometric, "$hidden is hidden under the fallback tier (D-114)")
        }
        assertTrue(ConstrainMenuItem.Horizontal in geometric && ConstrainMenuItem.Coincident in geometric && ConstrainMenuItem.Distance in geometric)
    }

    @Test
    fun referenceToggle_should_NeedADimensionOnTheSelection() {
        assertEquals(SketchReasonText.ReferenceNeedsDimension, flyout(line("a", 0, 0, 10, 0)).open().state(ConstrainMenuItem.ReferenceToggle).reason)
        assertTrue(ConstrainFlyoutModel.of(listOf(line("a", 0, 0, 10, 0)), SolverTier.Full, selectionHasDimension = true).open().state(ConstrainMenuItem.ReferenceToggle).enabled)
    }

    @Test
    fun mnemonics_should_PickOnlyEnabledItems() {
        val open = flyout(line("a", 0, 0, 10, 0)).open()
        assertEquals(ConstrainMenuItem.Horizontal, ConstrainMnemonics.pick("h", open.enabled))
        assertNull(ConstrainMnemonics.pick("l", open.enabled), "Parallel is disabled for one line")
        assertEquals(ConstrainMenuItem.Horizontal, ConstrainMnemonics.firstFocus(open.enabled), "focus lands on the first enabled item")
    }

    @Test
    fun conflictCard_should_NameBothConstraints_OfferRemoveMakeReferenceAndShow_AndEscRemovesTheNewOne() = runTest {
        val pinned = docOf(line("L1", 0, 0, 100, 0)).with(c("pin", ConstraintKind.Fixed, "L1"))
        val added = c("len", ConstraintKind.Distance, "L1", value = 50.0)
        val rejected = assertIs<SolveOutcome.Rejected>(runner().commitEdit(pinned, AddConstraint(added)))
        val reason = assertIs<SolveRejection.Unsatisfiable>(rejected.reason)

        val card = ConflictCards.forRejected(pinned, added, reason)

        val name = ElementNames.nameOf(pinned.scene[id("L1")]!!)
        assertEquals("Distance conflicts with Fixed on $name", card.message)
        assertEquals(
            listOf(ConflictActionKind.RemoveNew, ConflictActionKind.MakeReference, ConflictActionKind.ShowConflicts),
            card.actions.map { it.kind },
            "Replace existing only for a same-kind dimension; Make reference for a dimension",
        )
        assertEquals(ConflictActionKind.RemoveNew, card.escape?.kind)
        val makeReference = card.actions.first { it.kind == ConflictActionKind.MakeReference }.command!!
        val done = assertIs<SolveOutcome.Done>(runner().commitEdit(pinned, makeReference))
        assertTrue(done.step.after.constraints.first { it.id == added.id }.reference, "the new dimension is kept as a reference")
        assertEquals(pinned.scene, done.step.after.scene, "geometry unchanged")
    }

    @Test
    fun conflictCard_should_NotOfferMakeReferenceOrReplace_ForAGeometricConflict() = runTest {
        val base = docOf(line("L1", 0, 0, 100, 0), line("L2", 0, 10, 0, 90)).with(
            c("h", ConstraintKind.Horizontal, "L1"), c("v", ConstraintKind.Vertical, "L2"),
            c("l1", ConstraintKind.Distance, "L1", value = 100.0), c("l2", ConstraintKind.Distance, "L2", value = 80.0),
        )
        val added = c("par", ConstraintKind.Parallel, "L1", "L2")
        val rejected = assertIs<SolveOutcome.Rejected>(runner().commitEdit(base, AddConstraint(added)))
        val card = ConflictCards.forRejected(base, added, assertIs<SolveRejection.Unsatisfiable>(rejected.reason))
        assertEquals(listOf(ConflictActionKind.RemoveNew, ConflictActionKind.ShowConflicts), card.actions.map { it.kind })
        assertTrue(card.message.isNotEmpty())
    }

    @Test
    fun conflictCard_should_OfferReplaceExisting_ForADimensionOfTheSameKindOnTheSameGeometry() = runTest {
        val base = docOf(line("L1", 0, 0, 100, 0)).with(c("len", ConstraintKind.Distance, "L1", value = 100.0))
        val between = Constraint(
            ConstraintId("len2"), ConstraintKind.Distance,
            listOf(ConstraintTarget(id("L1"), TargetPart.Start), ConstraintTarget(id("L1"), TargetPart.End)), 150.0,
        )
        val rejected = assertIs<SolveOutcome.Rejected>(runner().commitEdit(base, AddConstraint(between)))
        val card = ConflictCards.forRejected(base, between, assertIs<SolveRejection.Unsatisfiable>(rejected.reason))
        assertTrue(card.actions.any { it.kind == ConflictActionKind.ReplaceExisting }, "kinds were ${card.actions.map { it.kind }}")
        val replace = card.actions.first { it.kind == ConflictActionKind.ReplaceExisting }.command!!
        val done = assertIs<SolveOutcome.Done>(runner().commitEdit(base, replace))
        assertEquals(1, done.step.after.constraints.size, "no second constraint")
        assertEquals(150.0, done.step.after.constraints.single().value)
    }

    @Test
    fun loadedConflict_should_ShowOverConstrainedWithItsCardAndActions() = runTest {
        val clashing = docOf(line("L1", 0, 0, 100, 0)).copy(
            constraints = listOf(c("pin", ConstraintKind.Fixed, "L1"), c("len", ConstraintKind.Distance, "L1", value = 50.0)),
        )
        val outcome = runner().analyze(clashing)
        val analysis = ConstraintAnalysis.from(outcome)
        assertTrue(analysis.conflictCount > 0)
        assertEquals("Over-constrained: ${analysis.conflictCount} ${if (analysis.conflictCount == 1) "conflict" else "conflicts"}", ConstraintPanelModel.status(clashing, analysis, technical = false)!!.text)
        val card = assertNotNull(ConflictCards.forLoaded(clashing, analysis))
        assertTrue(card.actions.any { it.kind == ConflictActionKind.RemoveConflicting })
        assertNull(ConflictCards.forLoaded(clashing, ConstraintAnalysis(dof = 0)))
        assertEquals(SketchConstraintStrings.Panel.SHOW_CONFLICTS, card.actions.first().label)
    }
}
