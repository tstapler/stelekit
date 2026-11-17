// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.SketchReasonText
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 4.2.3, Tasks 4.2.3a and 4.2.3b (UX-30, UX-31, UX-33, UX-34, UX-37, UX-83, UX-84): the Constraints tab, its status line,
 * the Constrain flyout and the conflict card. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class ConstraintPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun line(id: String, x0: Int, y0: Int, x1: Int, y1: Int) = LineElement(ElementBase(ElementId(id)), listOf(ScenePoint(u(x0), u(y0)), ScenePoint(u(x1), u(y1))))

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 =
        AddElements(elements.toList()).apply(SketchDocumentV2()).fold({ error("$it") }, { it.doc })

    private fun SketchDocumentV2.with(c: Constraint): SketchDocumentV2 = AddConstraint(c).apply(this).fold({ error("$it") }, { it.doc })

    private fun k(name: String, kind: ConstraintKind, element: String, value: Double? = null) = Constraint(ConstraintId(name), kind, listOf(ConstraintTarget(ElementId(element))), value)

    private val a = line("a", 0, 0, 10, 0)
    private val b = line("b", 0, 5, 10, 9)

    private var input by mutableStateOf(ConstraintPanelInput(SketchDocumentV2(), ConstraintAnalysis.Unknown, technical = false, selection = emptyList()))
    private val chosen = mutableListOf<ConstrainMenuItem>()
    private val selectedRows = mutableListOf<ConstraintId?>()
    private val deleted = mutableListOf<ConstraintId>()
    private val references = mutableListOf<Pair<ConstraintId, Boolean>>()
    private val conflictActions = mutableListOf<ConflictActionKind>()

    private fun show(initial: ConstraintPanelInput) {
        input = initial
        rule.setContent {
            ConstraintPanel(
                input,
                ConstraintPanelActions(
                    onConstrain = { chosen += it },
                    onRowSelected = { selectedRows += it?.id },
                    onDelete = { deleted += it },
                    onSetReference = { id, ref -> references += id to ref },
                    onConflictAction = { conflictActions += it.kind },
                ),
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun emptyTab_should_ExplainItselfAndDisableConstrainWithItsReason_When_NothingIsSelected() {
        show(input)
        rule.onNodeWithTag(ConstraintPanelTags.EMPTY).assertTextEquals(SketchConstraintStrings.Panel.EMPTY)
        rule.onNodeWithTag(ConstraintPanelTags.CONSTRAIN).assertIsNotEnabled()
        rule.onNodeWithTag(ConstraintPanelTags.CONSTRAIN_REASON).assertTextEquals(SketchReasonText.SelectAnElement)
        rule.onNodeWithTag(ConstraintPanelTags.STATUS).assertDoesNotExist()
    }

    @Test
    fun status_should_BeAPoliteLiveRegionWithTheGloss_AndAbsentOnAPlainDiagram() {
        val constrained = docOf(a).with(k("h", ConstraintKind.Horizontal, "a"))
        show(ConstraintPanelInput(constrained, ConstraintAnalysis(dof = 2), technical = false, selection = emptyList()))
        rule.onNodeWithTag(ConstraintPanelTags.STATUS).assertTextEquals("2 degrees of freedom (2 moves still possible)")
        val live = rule.onNodeWithTag(ConstraintPanelTags.STATUS).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertNotNull(live, "the status line is a live region")
        rule.onNodeWithTag(ConstraintPanelTags.EXPLANATION).assertDoesNotExist()
        rule.onNodeWithTag(ConstraintPanelTags.STATUS_DETAILS).performClick()
        rule.onNodeWithTag(ConstraintPanelTags.EXPLANATION).assertTextContains("one way the sketch can still change", substring = true)

        input = ConstraintPanelInput(docOf(a, b), ConstraintAnalysis(dof = 8), technical = false, selection = emptyList())
        rule.waitForIdle()
        rule.onNodeWithTag(ConstraintPanelTags.STATUS).assertDoesNotExist()
    }

    @Test
    fun constrainFlyout_should_ListEveryItem_DisableTheTwoElementKindsWithReasons_AndRunTheEnabledOne() {
        show(ConstraintPanelInput(docOf(a), ConstraintAnalysis.Unknown, technical = false, selection = listOf(a)))
        rule.onNodeWithTag(ConstraintPanelTags.CONSTRAIN).assertIsEnabled().performClick()
        rule.onNodeWithTag(ConstraintPanelTags.item(ConstrainMenuItem.Parallel)).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag(ConstraintPanelTags.itemReason(ConstrainMenuItem.Parallel), useUnmergedTree = true).assertTextEquals("Parallel needs two lines")
        rule.onNodeWithTag(ConstraintPanelTags.item(ConstrainMenuItem.Horizontal)).assertIsEnabled().performClick()
        assertEquals(listOf(ConstrainMenuItem.Horizontal), chosen)
    }

    @Test
    fun constrainFlyout_should_GiveTheOneNotConstrainableReason_WhenAnArrowIsSelected() {
        val arrow = ArrowElement(ElementBase(ElementId("arr")), listOf(ScenePoint(u(0), u(0)), ScenePoint(u(5), u(5))))
        show(ConstraintPanelInput(docOf(arrow), ConstraintAnalysis.Unknown, technical = false, selection = listOf(arrow)))
        rule.onNodeWithTag(ConstraintPanelTags.CONSTRAIN).performClick()
        for (item in listOf(ConstrainMenuItem.Parallel, ConstrainMenuItem.Horizontal, ConstrainMenuItem.Distance)) {
            rule.onNodeWithTag(ConstraintPanelTags.item(item)).assertIsNotEnabled()
            rule.onNodeWithTag(ConstraintPanelTags.itemReason(item), useUnmergedTree = true).assertTextEquals(SketchReasonText.NotConstrainable)
        }
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun hiddenSolverTier_should_ShowNoConstraintUiAtAll() {
        show(ConstraintPanelInput(docOf(a), ConstraintAnalysis.Unknown, technical = false, selection = listOf(a), tier = SolverTier.Hidden))
        rule.onNodeWithTag(ConstraintPanelTags.ROOT).assertDoesNotExist()
        rule.onNodeWithTag(ConstraintPanelTags.CONSTRAIN).assertDoesNotExist()
    }

    @Test
    fun rows_should_ShowStateAsGlyphAndText_HighlightOnSelectionAndOfferDeleteAndReference() {
        val doc = docOf(a).with(k("h", ConstraintKind.Horizontal, "a")).with(k("d", ConstraintKind.Distance, "a", value = 160.0))
        show(ConstraintPanelInput(doc, ConstraintAnalysis(dof = 1, redundant = setOf(ConstraintId("h"))), technical = true, selection = emptyList()))
        rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId("h"))).assertTextContains("⚠", substring = true).assertTextContains("Redundant", substring = true)
        rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId("d"))).assertTextContains("✓", substring = true).assertTextContains("10\"", substring = true)

        rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId("d"))).performClick()
        assertEquals(listOf<ConstraintId?>(ConstraintId("d")), selectedRows, "selecting a row asks the host to highlight its geometry (UX-34)")
        rule.onNodeWithTag(ConstraintPanelTags.MAKE_REFERENCE).performClick()
        assertEquals(listOf(ConstraintId("d") to true), references)
        rule.onNodeWithTag(ConstraintPanelTags.DELETE).performClick()
        assertEquals(listOf(ConstraintId("d")), deleted)
        rule.onNodeWithTag(ConstraintPanelTags.row(ConstraintId("h"))).performClick()
        rule.onNodeWithTag(ConstraintPanelTags.MAKE_REFERENCE).assertDoesNotExist()
    }

    @Test
    fun conflictCard_should_ShowTheMessageAndItsActions() {
        val doc = docOf(a)
        val card = ConflictCard(
            "Parallel conflicts with Horizontal on Line 3",
            listOf(
                ConflictAction(ConflictActionKind.RemoveNew, SketchConstraintStrings.Panel.REMOVE_NEW, null),
                ConflictAction(ConflictActionKind.MakeReference, SketchConstraintStrings.Panel.MAKE_REFERENCE, null),
                ConflictAction(ConflictActionKind.ShowConflicts, SketchConstraintStrings.Panel.SHOW_CONFLICTS, null),
            ),
            emptyList(),
        )
        show(ConstraintPanelInput(doc, ConstraintAnalysis.Unknown, technical = false, selection = listOf(a), card = card))
        rule.onNodeWithTag(ConstraintPanelTags.CARD_MESSAGE).assertTextEquals("Parallel conflicts with Horizontal on Line 3")
        rule.onNodeWithTag(ConstraintPanelTags.cardAction(ConflictActionKind.MakeReference)).performClick()
        rule.onNodeWithTag(ConstraintPanelTags.cardAction(ConflictActionKind.ShowConflicts)).performClick()
        rule.onNodeWithTag(ConstraintPanelTags.cardAction(ConflictActionKind.RemoveNew)).performClick()
        assertEquals(listOf(ConflictActionKind.MakeReference, ConflictActionKind.ShowConflicts, ConflictActionKind.RemoveNew), conflictActions)
        assertEquals(ConflictActionKind.RemoveNew, card.escape?.kind, "Esc counts as Remove (UX-32)")
    }
}
