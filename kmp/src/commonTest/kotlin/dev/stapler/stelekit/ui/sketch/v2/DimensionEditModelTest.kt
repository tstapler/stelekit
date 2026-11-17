// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.DimensionSuggestions
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks 4.2.7b and 4.2.7d (UX-99, UX-115): the dimension label editor's rules, with no screen. */
class DimensionEditModelTest {
    private fun on(part: TargetPart = TargetPart.Whole) = ConstraintTarget(ElementId("l"), part)

    private fun k(name: String, kind: ConstraintKind, targets: List<ConstraintTarget>, value: Double? = null, reference: Boolean = false) =
        Constraint(ConstraintId(name), kind, targets, value, reference)

    private fun TestScope.runner() = SolveRunner(context = StandardTestDispatcher(testScheduler))

    private fun length(doc: SketchDocumentV2): Double {
        val l = doc.scene[ElementId("l")] as LineElement
        return hypot(l.points[1].x.value - l.points[0].x.value, l.points[1].y.value - l.points[0].y.value)
    }

    private fun pinned(vararg more: Constraint): SketchDocumentV2 {
        val base = AddElements(listOf(line("l", 0, 0, 5, 0))).apply(SketchDocumentV2()).get().doc
        val all = listOf(k("pin", ConstraintKind.Fixed, listOf(on(TargetPart.Start))), k("h", ConstraintKind.Horizontal, listOf(on())), k("d", ConstraintKind.Distance, listOf(on()), 5.0)) + more
        return all.fold(base) { doc, c -> AddConstraint(c).apply(doc).get().doc }
    }

    @Test
    fun parse_should_ReadLengthsInSixteenthsOnATechnicalSheet_AndPlainNumbersElsewhere() {
        assertEquals(96.0, DimensionValueParser.parse(ConstraintKind.Distance, "6\"", technical = true).get())
        assertEquals(8.0, DimensionValueParser.parse(ConstraintKind.Radius, "1/2\"", technical = true).get())
        assertEquals(12.5, DimensionValueParser.parse(ConstraintKind.Distance, " 12.5 ", technical = false).get())
        for (bad in listOf("", "abc", "0", "-3", "6 to 8")) {
            assertEquals("Not a length. Try 12 3/16\"", DimensionValueParser.parse(ConstraintKind.Distance, bad, technical = true).fold({ it }, { error("$bad parsed") }), bad)
        }
        assertEquals(SketchConstraintStrings.Dimension.notALength(), DimensionValueParser.parse(ConstraintKind.Distance, "0", technical = false).fold({ it }, { error("parsed") }))
    }

    @Test
    fun parse_should_ReadAnAngleInDegrees() {
        assertEquals(PI / 4, DimensionValueParser.parse(ConstraintKind.Angle, "45", technical = true).get(), 1e-12)
        assertEquals(PI / 2, DimensionValueParser.parse(ConstraintKind.Angle, "90°", technical = false).get(), 1e-12)
        assertEquals(SketchConstraintStrings.Dimension.NOT_AN_ANGLE, DimensionValueParser.parse(ConstraintKind.Angle, "x", technical = true).fold({ it }, { error("parsed") }))
    }

    @Test
    fun openExisting_should_PreselectTheCurrentValue_AndRefuseANonDimension() {
        val doc = pinned()
        val editor = DimensionEditor(technical = true)
        assertTrue(editor.openExisting(doc, ConstraintId("d")))
        val editing = assertIs<DimensionEditState.Editing>(editor.state)
        assertEquals("5/16\"", editing.text)
        assertEquals("Length", editing.subject.fieldLabel)
        assertTrue(!editor.openExisting(doc, ConstraintId("h")), "Horizontal has no value")
        assertTrue(!editor.openExisting(doc, ConstraintId("missing")))
    }

    @Test
    fun submit_should_KeepTheFieldAndTheOldValue_WhenTheTextIsNotALength() = runTest {
        val doc = pinned()
        val runner = runner()
        val editor = DimensionEditor(technical = true)
        editor.openExisting(doc, ConstraintId("d"))
        editor.typed("six")
        assertNull(editor.submit(doc, runner))
        val editing = assertIs<DimensionEditState.Editing>(editor.state)
        assertEquals("Not a length. Try 12 3/16\"", editing.error)
        assertEquals("six", editing.text)
        assertEquals(0, runner.counters.value.solves, "nothing reached the solver")
        assertEquals(5.0, length(doc))
    }

    @Test
    fun submit_should_CommitTheValueAndTheSolverGeometryAsOneUndoStep() = runTest {
        val doc = pinned()
        val runner = runner()
        val editor = DimensionEditor(technical = true)
        editor.openExisting(doc, ConstraintId("d"))
        editor.typed("1/2\"")
        val done = assertIs<SolveOutcome.Done>(editor.submit(doc, runner))
        assertEquals(DimensionEditState.Closed, editor.state)
        assertEquals(8.0, done.step.after.constraints.first { it.id == ConstraintId("d") }.value)
        assertTrue(abs(length(done.step.after) - 8.0) < 1e-6)
        val history = HistoryV2.of(doc).commit(done.step).get()
        assertEquals(doc, history.undo().present, "one undo restores the value and the geometry")
    }

    @Test
    fun submit_should_KeepTheLastGoodState_AndOfferKeepEditingRevertAndMakeReference_WhenUnsolvable() = runTest {
        val doc = pinned(k("d2", ConstraintKind.Distance, listOf(on(TargetPart.Start), on(TargetPart.End)), 5.0))
        val runner = runner()
        val editor = DimensionEditor(technical = false)
        editor.openExisting(doc, ConstraintId("d"))
        editor.typed("9")
        val rejected = assertIs<SolveOutcome.Rejected>(editor.submit(doc, runner))
        assertEquals(doc, rejected.lastGood)
        val failed = assertIs<DimensionEditState.Failed>(editor.state)
        assertEquals("Changing 5 to 9 can't be solved: it conflicts with Distance on Line 1", failed.message)
        assertEquals(listOf("Keep editing", "Revert", "Make reference"), failed.actions.map { it.label })

        editor.choose(DimensionFailureKind.KeepEditing, doc, runner)
        assertEquals("9", assertIs<DimensionEditState.Editing>(editor.state).text)

        editor.typed("9")
        editor.submit(doc, runner)
        editor.choose(DimensionFailureKind.Revert, doc, runner)
        assertEquals(DimensionEditState.Closed, editor.state)

        editor.openExisting(doc, ConstraintId("d"))
        editor.typed("9")
        editor.submit(doc, runner)
        val made = assertIs<SolveOutcome.Done>(editor.choose(DimensionFailureKind.MakeReference, doc, runner))
        assertTrue(made.step.after.constraints.first { it.id == ConstraintId("d") }.reference)
        assertEquals(DimensionEditState.Closed, editor.state)
    }

    @Test
    fun openExisting_should_BeReadOnlyForAReference_AndMakeDrivingReopensItForEditing() = runTest {
        val doc = SetConstraintReference(ConstraintId("d"), true).apply(pinned()).get().doc
        val runner = runner()
        val editor = DimensionEditor(technical = true)
        editor.openExisting(doc, ConstraintId("d"))
        val readOnly = assertIs<DimensionEditState.ReadOnly>(editor.state)
        assertEquals("Reference: driven by other constraints", readOnly.message)
        assertEquals("Make driving", readOnly.actionLabel)
        editor.typed("1\"")
        assertIs<DimensionEditState.ReadOnly>(editor.state)
        assertNull(editor.submit(doc, runner), "a reference label takes no value")

        assertIs<SolveOutcome.Done>(editor.makeDriving(doc, runner))
        assertIs<DimensionEditState.Editing>(editor.state)
    }

    @Test
    fun openNew_should_RedirectADuplicateToTheExistingLabel() {
        val doc = pinned()
        val editor = DimensionEditor(technical = true)
        editor.openNew(doc, k("again", ConstraintKind.Distance, listOf(on()), 5.0), "5/16\"")
        val editing = assertIs<DimensionEditState.Editing>(editor.state)
        assertTrue(!editing.subject.isNew)
        assertEquals(ConstraintId("d"), editing.subject.constraint.id)
    }

    @Test
    fun aSuggestion_should_OnlyOpenTheLabel_AndAddNothingUntilAValueIsCommitted() = runTest {
        val doc = AddElements(listOf(line("l", 0, 0, 100, 0))).apply(SketchDocumentV2()).get().doc
        val runner = runner()
        val editor = DimensionEditor(technical = true)
        val suggestion = assertNotNull(DimensionSuggestions.suggest(doc, listOf(ElementId("l")), null, { ConstraintId("k1") }))
        assertTrue(editor.openSuggestion(doc, suggestion))
        val editing = assertIs<DimensionEditState.Editing>(editor.state)
        assertEquals("6 1/4\"", editing.text, "the current value, prefilled")
        assertTrue(editing.subject.isNew)
        assertEquals(0, doc.constraints.size)

        editor.cancel()
        assertEquals(DimensionEditState.Closed, editor.state)
        assertEquals(0, runner.counters.value.solves, "Esc commits nothing")

        editor.openSuggestion(doc, suggestion)
        editor.typed("6\"")
        val done = assertIs<SolveOutcome.Done>(editor.submit(doc, runner))
        val added = done.step.after.constraints.single()
        assertEquals(ConstraintKind.Distance, added.kind)
        assertEquals(96.0, added.value)
    }

    @Test
    fun aRefusedNewDimension_should_RaiseTheConflictCardWithReplaceExisting() = runTest {
        val doc = pinned()
        val runner = runner()
        val editor = DimensionEditor(technical = false)
        editor.openNew(doc, k("second", ConstraintKind.Distance, listOf(on(TargetPart.Start), on(TargetPart.End)), 5.0), "5")
        editor.typed("7")
        assertIs<SolveOutcome.Rejected>(editor.submit(doc, runner))
        assertEquals(DimensionEditState.Closed, editor.state)
        val card = assertNotNull(editor.card)
        assertTrue(card.actions.any { it.kind == ConflictActionKind.ReplaceExisting }, "Replace existing is offered: ${card.actions.map { it.kind }}")
        editor.dismissCard()
        assertNull(editor.card)
    }
}
