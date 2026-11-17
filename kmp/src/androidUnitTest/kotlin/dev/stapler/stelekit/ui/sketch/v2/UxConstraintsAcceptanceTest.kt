// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.2.3 (UX-30, UX-31, UX-33, UX-34, UX-81, UX-83, UX-84, UX-106) and Task 4.2.2c (UX-82) against the real components:
 * the steps to add a constraint, the status states, the state encoding, the tab's discoverability, glyph targets and contrast, and
 * the auto-constrain toggle in the shell. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class UxConstraintsAcceptanceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun line(id: String, x0: Int, y0: Int, x1: Int, y1: Int) = LineElement(ElementBase(ElementId(id)), listOf(ScenePoint(u(x0), u(y0)), ScenePoint(u(x1), u(y1))))

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        AddElements(elements.toList()).apply(SketchDocumentV2()).fold({ error("$it") }, { it.doc })

    private fun k(name: String, kind: ConstraintKind, vararg elements: String) = Constraint(ConstraintId(name), kind, elements.map { ConstraintTarget(ElementId(it)) })

    private fun SketchDocumentV2.with(c: Constraint): SketchDocumentV2 = AddConstraint(c).apply(this).fold({ error("$it") }, { it.doc })

    private fun contrast(a: Color, b: Color): Double {
        val hi = max(a.luminance(), b.luminance()).toDouble()
        val lo = min(a.luminance(), b.luminance()).toDouble()
        return (hi + LUMA_OFFSET) / (lo + LUMA_OFFSET)
    }

    @Test
    fun ux30_addingAConstraintToOneElement_should_TakeThreeSteps_SelectConstrainPick() {
        val a = line("a", 0, 0, 10, 0)
        val chosen = mutableListOf<ConstrainMenuItem>()
        rule.setContent {
            ConstraintPanel(
                ConstraintPanelInput(docOf(a), ConstraintAnalysis.Unknown, technical = false, selection = listOf(a)),
                ConstraintPanelActions(onConstrain = { chosen += it }),
            )
        }
        rule.onNodeWithTag(ConstraintPanelTags.CONSTRAIN).performClick()
        rule.onNodeWithTag(ConstraintPanelTags.item(ConstrainMenuItem.Horizontal)).performClick()
        assertEquals(listOf(ConstrainMenuItem.Horizontal), chosen, "select (given), Constrain, pick: a single-element kind needs no second selection")
    }

    @Test
    fun ux31_statusLine_should_BeOneOfThreeStates_OnlyWhenTheSheetHasConstraintsOrIsTechnical() {
        val doc = docOf(line("a", 0, 0, 10, 0)).with(k("h", ConstraintKind.Horizontal, "a"))
        val texts = listOf(
            ConstraintAnalysis(dof = 3) to "3 degrees of freedom (3 moves still possible)",
            ConstraintAnalysis(dof = 0) to "Fully constrained (nothing can move)",
            ConstraintAnalysis(conflicts = setOf(ConstraintId("h"))) to "Over-constrained: 1 conflict",
        )
        for ((analysis, text) in texts) assertEquals(text, ConstraintPanelModel.status(doc, analysis, technical = false)!!.text)
        assertEquals(null, ConstraintPanelModel.status(docOf(line("a", 0, 0, 10, 0)), ConstraintAnalysis(dof = 4), technical = false))
        assertTrue(ConstraintPanelModel.status(docOf(line("a", 0, 0, 10, 0)), ConstraintAnalysis(dof = 4), technical = true) != null)
    }

    @Test
    fun ux33_everyRowState_should_CarryAGlyphAndAWord_AndTheGlyphColoursKeepThreeToOne() {
        for (state in ConstraintRowState.entries) {
            assertTrue(state.glyph.isNotEmpty() && state.stateText.isNotEmpty(), "$state is not colour alone")
        }
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= MIN_CONTRAST, "glyph text on its chip keeps 3:1")
        }
    }

    @Test
    fun ux34_selectingGeometry_should_HighlightItsConstraintRows() {
        val doc = docOf(line("a", 0, 0, 10, 0), line("b", 0, 9, 10, 9)).with(k("h", ConstraintKind.Horizontal, "a")).with(k("v", ConstraintKind.Horizontal, "b"))
        val rows = ConstraintPanelModel.rows(doc, ConstraintAnalysis(dof = 2), technical = false)
        assertEquals(listOf(ConstraintId("h")), ConstraintPanelModel.touching(rows, setOf(ElementId("a"))).map { it.id })
        var selection by mutableStateOf(listOf<SceneElement>())
        rule.setContent { ConstraintPanel(ConstraintPanelInput(doc, ConstraintAnalysis(dof = 2), technical = false, selection = selection), ConstraintPanelActions()) }
        selection = listOf(doc.scene[ElementId("a")]!!)
        rule.waitForIdle()
        val selected = { id: String -> rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId(id))).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) }
        assertEquals(true, selected("h"))
        assertEquals(false, selected("v"))
    }

    @Test
    fun ux84_theTabShowsItsEmptyStateWithoutASelection_AndTheShellKeepsTheTab() {
        val state = SketchEditorState(panelTab = PanelTab.Constraints)
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly), window = WindowDp(1280, 800), panel = { tab ->
                if (tab == PanelTab.Constraints) {
                    ConstraintPanel(ConstraintPanelInput(SketchDocumentV2(), ConstraintAnalysis.Unknown, technical = false, selection = emptyList()), ConstraintPanelActions())
                }
            })
        }
        rule.onNodeWithTag(ShellTags.tab(PanelTab.Constraints)).assertIsDisplayed()
        rule.onNodeWithTag(ConstraintPanelTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun hiddenSolverTier_should_RemoveTheConstraintsTabAndTheOverflowEntry() {
        val state = SketchEditorState()
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly, constraintsAvailable = false), window = WindowDp(1280, 800))
        }
        state.panelTab = PanelTab.Style
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.tab(PanelTab.Constraints)).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_CONSTRAIN).assertDoesNotExist()
    }

    @Test
    fun ux106_glyphTargets_should_MeetTheFloor_AndABadgeShouldOpenItsList() {
        val doc = docOf(line("a", 0, 0, 10, 0), line("b", 0, 9, 10, 9))
            .with(k("h", ConstraintKind.Horizontal, "a")).with(k("v", ConstraintKind.Vertical, "a")).with(k("e", ConstraintKind.Equal, "a", "b"))
        val anchors = listOf(
            GlyphAnchor(ConstraintId("h"), 100.0, 100.0, setOf(ElementId("a"))),
            GlyphAnchor(ConstraintId("v"), 104.0, 102.0, setOf(ElementId("a"))),
            GlyphAnchor(ConstraintId("e"), 400.0, 300.0, setOf(ElementId("a"), ElementId("b"))),
        )
        val picked = mutableListOf<ConstraintId>()
        val layout = ConstraintGlyphLayout.layout(anchors, zoom = 1.0, pointer = PointerClass.TouchOrPen, selected = emptySet(), showAll = false)
        rule.setContent { ConstraintGlyphOverlay(doc, layout, technical = false, onSelect = { picked += it }, modifier = Modifier.fillMaxSize()) }

        val single = rule.onNodeWithTag(ConstraintGlyphTags.glyph(ConstraintId("e")))
        single.assertIsDisplayed().assertContentDescriptionEquals("Equal")
        val bounds = single.getBoundsInRoot()
        assertTrue(bounds.width.value >= TOUCH_FLOOR && bounds.height.value >= TOUCH_FLOOR, "hit area is at the 44 dp floor, was $bounds")
        single.performClick()
        assertEquals(listOf(ConstraintId("e")), picked)

        val badge = rule.onNodeWithTag(ConstraintGlyphTags.badge(0))
        badge.assertContentDescriptionEquals(SketchConstraintStrings.Panel.badgeSemantics(2))
        badge.performClick()
        rule.onNodeWithTag(ConstraintGlyphTags.badgeRow(ConstraintId("v"))).assertIsDisplayed().performClick()
        assertEquals(listOf(ConstraintId("e"), ConstraintId("v")), picked)
    }

    @Test
    fun ux82_theToggleAndItsChip_should_FollowTheSheetDefaultAndBeInTheOverflowMenu() {
        val state = AutoConstrainState(SheetKind.Diagram)
        rule.setContent {
            SketchScreenV2(SketchEditorState(), SketchShellConfig(ToolStripModel(true), PointerClass.TouchOrPen, autoConstrain = state), window = WindowDp(1280, 800))
        }
        rule.onNodeWithTag(AutoConstrainTags.FREE_DRAW_CHIP).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(AutoConstrainTags.TOGGLE).assertContentDescriptionEquals(SketchConstraintStrings.AutoConstrain.TOGGLE_OFF)
        rule.onNodeWithTag(AutoConstrainTags.TOGGLE).performClick()
        rule.waitForIdle()
        assertTrue(state.enabled, "a diagram sheet starts OFF and the toggle turns it ON")
        rule.onNodeWithTag(AutoConstrainTags.FREE_DRAW_CHIP).assertIsDisplayed().performClick()
        assertTrue(state.freeDrawArmed, "the Free draw chip arms the one-shot suppression without a keyboard")
    }

    private companion object {
        const val LUMA_OFFSET = 0.05
        const val MIN_CONTRAST = 3.0
        const val TOUCH_FLOOR = 44
    }
}
