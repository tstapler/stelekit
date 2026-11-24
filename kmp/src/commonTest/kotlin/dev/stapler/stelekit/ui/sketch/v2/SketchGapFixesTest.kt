// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import dev.stapler.stelekit.sketch.v2.W6Kit
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.GroupElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.SetDimensionValue
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sketch editor's dimension-editing gaps: a rounded typed value, an unasked solver move, a pending field, and the annotation tap's selection rules. */
class SketchGapFixesTest {
    private fun TestScope.runner() = SolveRunner(context = StandardTestDispatcher(testScheduler))

    private fun id(v: String) = ElementId(v)

    private fun whole(element: String) = ConstraintTarget(id(element), TargetPart.Whole)

    private fun part(element: String, part: TargetPart) = ConstraintTarget(id(element), part)

    /** Line "a" pinned at its start and kept horizontal at 5; line "b" hangs off a's end, so a longer "a" must move "b". */
    private fun chained(): SketchDocumentV2 {
        val base = AddElements(listOf(W6Kit.line("a", 0, 0, 5, 0), W6Kit.line("b", 5, 0, 5, 3))).apply(SketchDocumentV2()).get().doc
        val constraints = listOf(
            Constraint(ConstraintId("pin"), ConstraintKind.Fixed, listOf(part("a", TargetPart.Start))),
            Constraint(ConstraintId("h"), ConstraintKind.Horizontal, listOf(whole("a"))),
            Constraint(ConstraintId("d"), ConstraintKind.Distance, listOf(whole("a")), 5.0),
            Constraint(ConstraintId("join"), ConstraintKind.Coincident, listOf(part("a", TargetPart.End), part("b", TargetPart.Start))),
        )
        return constraints.fold(base) { doc, c -> AddConstraint(c).apply(doc).get().doc }
    }

    @Test
    fun interpret_should_RoundALengthBetweenSixteenths_WhereParseRefusesIt() {
        assertTrue(DimensionValueParser.parse(ConstraintKind.Distance, "0.3\"", technical = true).isLeft())
        val read = DimensionValueParser.interpret(ConstraintKind.Distance, "0.3\"", technical = true).getOrNull()
        assertNotNull(read)
        assertEquals(5.0, read.value)
        assertNotNull(read.roundedNotice)
        assertNull(DimensionValueParser.interpret(ConstraintKind.Distance, "1/2\"", technical = true).getOrNull()?.roundedNotice)
        assertTrue(DimensionValueParser.interpret(ConstraintKind.Distance, "0.01\"", technical = true).isLeft(), "rounds to zero stays an error")
    }

    @Test
    fun submit_should_CommitTheRoundedValue_AndKeepTheNoticeForTheHost() = runTest {
        val doc = chained()
        val editor = DimensionEditor(technical = true)
        editor.openExisting(doc, ConstraintId("d"))
        editor.typed("0.7\"")
        val done = assertIs<SolveOutcome.Done>(editor.submit(doc, runner()))
        assertEquals(11.0, done.step.after.constraints.first { it.id == ConstraintId("d") }.value, "0.7 in is 11.2 sixteenths, rounded to 11")
        assertNotNull(editor.roundedNotice)
        editor.openExisting(doc, ConstraintId("d"))
        editor.typed("1/2\"")
        editor.submit(doc, runner())
        assertNull(editor.roundedNotice, "an exact value clears the previous notice")
    }

    @Test
    fun aDimensionEdit_should_NameTheConstrainedShapeTheSolverMoved_ButNotTheOneItTargets() = runTest {
        val doc = chained()
        val done = assertIs<SolveOutcome.Done>(runner().commitEdit(doc, SetDimensionValue(ConstraintId("d"), 8.0)))
        val moved = done.step.changedIds
        assertTrue(id("b") in moved, "line b hangs off a's end and moved with it")
        assertEquals(listOf(ElementNames.nameOf(done.step.after.scene[id("b")]!!)), ConstraintCollateral.adjusted(null, done.step), "only b is collateral; a is what the dimension names")
    }

    @Test
    fun aTypedResize_should_NotCountTheResizedShapeItselfAsCollateral() = runTest {
        val doc = chained()
        val resize = ResizeElement(id("a"), 0.0, 0.0, 96.0, 0.0)
        val done = assertIs<SolveOutcome.Done>(runner().commitEdit(doc, resize))
        val own = ElementNames.nameOf(done.step.after.scene[id("a")]!!)
        assertTrue(own !in ConstraintCollateral.adjusted(resize, done.step))
    }

    @Test
    fun adjusted_should_ReadAsOneSentence() {
        assertEquals("Constraint adjusted Line 2", SketchConstraintStrings.Dimension.adjusted(listOf("Line 2")))
        assertEquals("Constraints adjusted 4 shapes", SketchConstraintStrings.Dimension.adjusted(listOf("a", "b", "c", "d")))
    }

    @Test
    fun clickSelection_should_TakeAWholeGroup_AndLetShiftAddAndRemove() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElements(listOf(W6Kit.rect("r1", 0, 0), W6Kit.rect("r2", 20, 0), W6Kit.rect("r3", 40, 0))), GroupElements(listOf(id("r1"), id("r2")), GroupId("g")))
        val scene = doc.scene
        assertEquals(listOf(id("r1"), id("r2")), SelectTool.clickSelection(id("r1"), emptyList(), shift = false, clickCount = 1, scene = scene))
        assertEquals(listOf(id("r3"), id("r1"), id("r2")), SelectTool.clickSelection(id("r1"), listOf(id("r3")), shift = true, clickCount = 1, scene = scene))
        assertEquals(listOf(id("r3")), SelectTool.clickSelection(id("r1"), listOf(id("r3"), id("r1"), id("r2")), shift = true, clickCount = 1, scene = scene))
        assertEquals(listOf(id("r1")), SelectTool.clickSelection(id("r1"), listOf(id("r1"), id("r2")), shift = false, clickCount = 2, scene = scene))
        assertEquals(listOf(id("r3")), SelectTool.clickSelection(id("r3"), emptyList(), shift = false, clickCount = 1, scene = scene))
    }

    @Test
    fun pendingFieldText_should_BeAppliedBeforeAnotherShapeIsPicked() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElements(listOf(W6Kit.rect("r1", 0, 0, 160, 80))))
        val state = DimensionPillsState()
        state.open(doc.scene[id("r1")]!!, PillKind.Width)
        val edit = assertNotNull(state.edit)
        state.draft = PillDraft(edit, PillKind.Width, "2'", touched = true)
        var applied: ResizeElement? = null
        assertNull(state.commitPending { applied = it; null })
        assertEquals(384.0, applied?.width, "the typed 2 feet (384 sixteenths) was applied")
        assertEquals(80.0, applied?.height, "the untouched Height field kept its size")
    }

    @Test
    fun pendingFieldText_should_KeepTheEditOpen_WhenItIsNotALength() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElements(listOf(W6Kit.rect("r1", 0, 0, 160, 80))))
        val state = DimensionPillsState()
        state.open(doc.scene[id("r1")]!!, PillKind.Width)
        val edit = assertNotNull(state.edit)
        state.draft = PillDraft(edit, PillKind.Width, "wide", touched = true)
        var called = false
        assertNotNull(state.commitPending { called = true; null })
        assertTrue(!called)
        assertEquals(edit, state.edit)
    }

    @Test
    fun pendingFieldText_should_BeIgnored_WhenTheUserNeverTouchedTheField() {
        val doc = W6Kit.run(SketchDocumentV2(), AddElements(listOf(W6Kit.rect("r1", 0, 0, 160, 80))))
        val state = DimensionPillsState()
        state.open(doc.scene[id("r1")]!!, PillKind.Width)
        val edit = assertNotNull(state.edit)
        state.draft = PillDraft(edit, PillKind.Width, "10", touched = false)
        assertNull(state.commitPending { error("nothing was typed") })
    }

    @Test
    fun annotationInk_should_MeetTheContrastBars_InEveryTheme() {
        val schemes = mapOf("light" to lightColorScheme(), "dark" to darkColorScheme(), "high-contrast light" to SketchTokens.highContrast(dark = false), "high-contrast dark" to SketchTokens.highContrast(dark = true))
        for ((name, scheme) in schemes) {
            assertTrue(AnnotationInk.textContrast(scheme) >= TokenKind.Text.bar, "$name value text ${AnnotationInk.textContrast(scheme)}:1 under 4.5:1")
            assertTrue(AnnotationInk.lineContrast(scheme) >= TokenKind.NonText.bar, "$name dimension line ${AnnotationInk.lineContrast(scheme)}:1 under 3:1")
        }
    }
}
