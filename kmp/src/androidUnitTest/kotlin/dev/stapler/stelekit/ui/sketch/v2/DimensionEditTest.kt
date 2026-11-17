// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveOutcome
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tasks 4.2.7b and 4.2.7d (UX-99, UX-115): the label editor, its read-only and failure states, and the suggestion chip.
 * Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel). The runner works on an unconfined context so a
 * commit finishes inside the click that started it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class DimensionEditTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun on(part: TargetPart = TargetPart.Whole) = ConstraintTarget(ElementId("l"), part)

    private fun k(name: String, kind: ConstraintKind, targets: List<ConstraintTarget>, value: Double? = null) = Constraint(ConstraintId(name), kind, targets, value)

    private fun lineDoc(length: Int = 5, vararg more: Constraint): SketchDocumentV2 {
        val base = AddElements(listOf(LineElement(ElementBase(ElementId("l")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(length), u(0)))))).apply(SketchDocumentV2()).fold({ error("$it") }, { it.doc })
        val all = listOf(k("pin", ConstraintKind.Fixed, listOf(on(TargetPart.Start))), k("h", ConstraintKind.Horizontal, listOf(on())), k("d", ConstraintKind.Distance, listOf(on()), length.toDouble())) + more
        return all.fold(base) { doc, c -> AddConstraint(c).apply(doc).fold({ error("$it") }, { it.doc }) }
    }

    private class Host(initial: SketchDocumentV2) {
        var doc by mutableStateOf(initial)
        val editor = DimensionEditor(technical = true)
        val runner = SolveRunner(context = Dispatchers.Unconfined)
    }

    private fun length(doc: SketchDocumentV2): Double {
        val l = doc.scene[ElementId("l")] as LineElement
        return l.points[1].x.value - l.points[0].x.value
    }

    private fun valueOf(doc: SketchDocumentV2, name: String): Double? = doc.constraints.firstOrNull { it.id.value == name }?.value

    @Composable
    private fun Screen(host: Host) {
        val scope = rememberCoroutineScope()
        fun apply(outcome: SolveOutcome?) {
            (outcome as? SolveOutcome.Done)?.let { host.doc = it.step.after }
        }
        val actions = DimensionEditorActions(
            onSubmit = { scope.launch { apply(host.editor.submit(host.doc, host.runner)) } },
            onChoose = { kind -> scope.launch { apply(host.editor.choose(kind, host.doc, host.runner)) } },
            onMakeDriving = { scope.launch { apply(host.editor.makeDriving(host.doc, host.runner)) } },
        )
        val marks = host.doc.constraints.filter { it.isDimensional }.mapIndexed { i, c -> GlyphMark.Glyph(c.id, GLYPH_X + i * GLYPH_STEP, GLYPH_Y) }
        Column {
            ConstraintGlyphOverlay(host.doc, GlyphLayout(marks, 0, MOUSE_HIT), technical = true, onSelect = {}, onEdit = { host.editor.openExisting(host.doc, it) })
            DimensionLabelEditor(host.editor, actions, Modifier)
        }
    }

    private fun show(host: Host) = rule.setContent { Screen(host) }

    @Test
    fun ux99_labelTapOrEnterOrF2_should_OpenInPlaceEditor_AndInvalidKeepsOldValue() {
        val host = Host(lineDoc())
        show(host)
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()

        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed().assertTextContains("5/16\"")

        rule.onNodeWithTag(DimensionEditorTags.FIELD).performTextReplacement("six")
        rule.onNodeWithTag(DimensionEditorTags.SET).performClick()
        rule.onNodeWithTag(DimensionEditorTags.ERROR).assertTextEquals("Not a length. Try 12 3/16\"")
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
        assertEquals(5.0, valueOf(host.doc, "d"))
        assertEquals(5.0, length(host.doc))

        rule.onNodeWithTag(DimensionEditorTags.FIELD).performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()

        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performKeyInput { pressKey(Key.Escape) }

        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performKeyInput { pressKey(Key.F2) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
    }

    @Test
    fun ux99_enter_should_CommitTheValueAndTheMovedGeometry() {
        val host = Host(lineDoc())
        show(host)
        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performTextReplacement("1/2\"")
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(8.0, valueOf(host.doc, "d"))
        assertEquals(8.0, length(host.doc), 1e-6)
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()
    }

    @Test
    fun ux99_enterOrF2_on_ADimensionRow_should_OpenItsLabel() {
        val host = Host(lineDoc())
        val analysis = ConstraintAnalysis.Unknown
        rule.setContent {
            Column {
                ConstraintPanel(
                    ConstraintPanelInput(host.doc, analysis, technical = true, selection = emptyList()),
                    ConstraintPanelActions(onEditDimension = { host.editor.openExisting(host.doc, it) }),
                )
                DimensionLabelEditor(host.editor, DimensionEditorActions())
            }
        }
        rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId("h"))).performKeyInput { pressKey(Key.F2) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()
        rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId("d"))).performKeyInput { pressKey(Key.F2) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
    }

    @Test
    fun ux99_aReferenceDimension_should_BeReadOnly_WithMakeDriving() {
        val host = Host(SetConstraintReference(ConstraintId("d"), true).apply(lineDoc()).fold({ error("$it") }, { it.doc }))
        show(host)
        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performClick()
        rule.onNodeWithTag(DimensionEditorTags.READ_ONLY).assertTextEquals("Reference: driven by other constraints")
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()
        rule.onNodeWithTag(DimensionEditorTags.MAKE_DRIVING).assertTextEquals("Make driving").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
        assertTrue(host.doc.constraints.first { it.id.value == "d" }.reference.not())
    }

    @Test
    fun ux99_anUnsolvableValue_should_KeepTheOldState_AndOfferKeepEditingRevertAndMakeReference() {
        val second = k("d2", ConstraintKind.Distance, listOf(on(TargetPart.Start), on(TargetPart.End)), 5.0)
        val host = Host(lineDoc(5, second))
        val before = host.doc
        show(host)
        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performTextReplacement("1\"")
        rule.onNodeWithTag(DimensionEditorTags.SET).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionEditorTags.FAILURE_MESSAGE).assertTextEquals("Changing 5/16\" to 1\" can't be solved: it conflicts with Distance on Line 1")
        assertEquals(before, host.doc, "the old value and geometry stay")
        for (kind in DimensionFailureKind.entries) rule.onNodeWithTag(DimensionEditorTags.failureAction(kind)).assertIsDisplayed()

        rule.onNodeWithTag(DimensionEditorTags.failureAction(DimensionFailureKind.KeepEditing)).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed().assertTextContains("1\"")
        rule.onNodeWithTag(DimensionEditorTags.SET).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionEditorTags.failureAction(DimensionFailureKind.Revert)).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FAILURE).assertDoesNotExist()
        assertEquals(before, host.doc)

        rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("d"))).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performTextReplacement("1\"")
        rule.onNodeWithTag(DimensionEditorTags.SET).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DimensionEditorTags.failureAction(DimensionFailureKind.MakeReference)).performClick()
        rule.waitForIdle()
        assertTrue(host.doc.constraints.first { it.id.value == "d" }.reference)
    }

    @Test
    fun ux115_chip_should_OpenTheLabelByTapOrEnterOrD_AndAddNothingUntilAValueIsCommitted() {
        val host = Host(AddElements(listOf(LineElement(ElementBase(ElementId("l")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(100), u(0)))))).apply(SketchDocumentV2()).fold({ error("$it") }, { it.doc }))
        val suggestion = assertNotNull(DimensionChips.suggestion(host.doc, listOf(ElementId("l")), null, SolverTier.Full) { ConstraintId("k1") })
        rule.setContent {
            Column {
                DimensionSuggestionChip(suggestion, onAccept = { host.editor.openSuggestion(host.doc, suggestion) })
                DimensionLabelEditor(host.editor, DimensionEditorActions())
            }
        }
        rule.onNodeWithTag(DimensionChipTags.CHIP).assertTextContains("Length 6 1/4 in?").assertTextContains("Dimension")
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()

        rule.onNodeWithTag(DimensionChipTags.CHIP).performClick()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed().assertTextContains("6 1/4\"")
        assertEquals(0, host.doc.constraints.size, "the chip adds no constraint")
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertDoesNotExist()

        rule.onNodeWithTag(DimensionChipTags.CHIP).performKeyInput { pressKey(Key.D) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performKeyInput { pressKey(Key.Escape) }

        rule.onNodeWithTag(DimensionChipTags.CHIP).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag(DimensionEditorTags.FIELD).assertIsDisplayed()
    }

    private companion object {
        const val GLYPH_X = 60.0
        const val GLYPH_STEP = 80.0
        const val GLYPH_Y = 40.0
        const val MOUSE_HIT = 24
    }
}
