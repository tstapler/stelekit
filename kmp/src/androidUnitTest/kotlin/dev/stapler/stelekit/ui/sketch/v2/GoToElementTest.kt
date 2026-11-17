// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.search.ElementLabelIndex
import dev.stapler.stelekit.sketch.v2.search.LabelEntry
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGoToStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 3.6.5 (UX U5): "Go to element..." reaches an element that the 200-node semantics cap leaves out: search by label,
 * jump, select, and the element's semantics node is emitted and focusable. Robolectric; Gradle-only like the other Compose
 * host tests (see BUILD.bazel). The announcement text is asserted; what a screen reader speaks is not claimed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h800dp-mdpi")
class GoToElementTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun rect(id: String, x: Int, layer: LayerId = LayerId.Default, label: String? = null) =
        RectElement(ElementBase(ElementId(id), layerId = layer), u(x), u(0), u(40), u(20), boundElements = label?.let { listOf(BoundRef(ElementId("$id-t"), BoundKind.Text)) }.orEmpty())

    private fun scene(elements: List<SceneElement>, layers: List<Layer>? = null): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        layers?.let { s = s.withLayers(it) }
        for (e in elements) s = s.add(e).fold({ error("fixture $it") }, { it })
        return s
    }

    private val billingScene: SketchSceneV2 by lazy {
        val billing = rect("e2999", 200_000, label = "Billing API")
        val text = TextElement(ElementBase(ElementId("e2999-t")), u(200_005), u(5), u(30), u(10), text = "Billing API", containerId = ElementId("e2999"))
        scene((0 until 2999).map { rect("e$it", it * 50) } + billing + text)
    }

    private var semantics by mutableStateOf<Triple<SemanticsTree, SketchViewport, Map<ElementId, FocusRequester>>?>(null)

    private fun show(scene: SketchSceneV2, onActivate: (LabelEntry) -> Unit = {}) {
        rule.setContent {
            GoToElementDialog(scene, onActivate, onDismiss = {})
            semantics?.let { (tree, viewport, requesters) -> SceneSemanticsLayer(tree, viewport, requesters = requesters) }
        }
        rule.waitForIdle()
    }

    @Test
    fun goToElement_should_PanSelectAndFocus_When_BillingResultActivated() {
        var chosen: LabelEntry? = null
        show(billingScene) { chosen = it }
        rule.onNodeWithTag(GoToElementTags.SEARCH).performTextInput("billing")
        rule.waitForIdle()
        rule.onAllNodesWithTag(GoToElementTags.result(0)).assertCountEquals(1)
        rule.onNodeWithText("Rectangle, Billing API").performClick()
        val entry = assertNotNull(chosen)
        assertEquals(ElementId("e2999"), entry.id)

        val viewport = SketchViewport.of()
        val visible = ScreenRect(0.0, 0.0, 800.0, 600.0)
        val outcome = assertIs<GoToOutcome.Selected>(GoToElementModel.activate(billingScene, entry, viewport, visible))
        assertEquals("Selected: Rectangle, Billing API", outcome.announcement)
        assertEquals(viewport.zoom, outcome.viewport.zoom, "the view pans, it never zooms")
        val left = 200_000 * outcome.viewport.zoom + outcome.viewport.panX
        assertTrue(left >= visible.left && left + 40 * outcome.viewport.zoom <= visible.right, "the element is on screen after the pan")

        // The selected element is emitted first under the semantics cap, and its node takes focus.
        val tree = SceneSemantics.build(billingScene, SemanticsView(outcome.viewport, 800.0, 600.0), setOf(entry.id))
        assertTrue(tree.nodes.size <= billingScene.limits.maxSemanticsNodes)
        val node = assertNotNull(tree.nodes.firstOrNull { it.id == entry.id })
        assertTrue(node.selected)
        val requester = FocusRequester()
        semantics = Triple(tree, outcome.viewport, mapOf(entry.id to requester))
        rule.waitForIdle()
        rule.runOnUiThread { requester.requestFocus() }
        rule.waitForIdle()
        rule.onNode(hasContentDescription(node.label)).assertIsFocused()
    }

    @Test
    fun list_should_ShowAtMostFiftyMatches_AndAnnounceTheRest() {
        show(billingScene)
        rule.onNodeWithTag(GoToElementTags.SEARCH).performTextInput("rectangle")
        rule.waitForIdle()
        val limit = billingScene.limits.maxGoToResults
        assertTrue(rule.onAllNodesWithTag(GoToElementTags.result(limit)).fetchSemanticsNodes().isEmpty(), "never more than $limit result rows exist")
        rule.onNodeWithTag(GoToElementTags.MORE).assertExists()
        rule.onNodeWithText(SketchGoToStrings.more(3000 - limit)).assertExists()
    }

    @Test
    fun list_should_BuildOnlyTheVisibleRows_NotANodePerElement() {
        show(billingScene)
        rule.onNodeWithTag(GoToElementTags.SEARCH).performTextInput("rectangle")
        rule.waitForIdle()
        val built = (0 until billingScene.limits.maxGoToResults).count { rule.onAllNodesWithTag(GoToElementTags.result(it)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(built in 1 until billingScene.size, "the list composed $built rows of ${billingScene.size} elements")
    }

    @Test
    fun resultCount_should_BeAPoliteLiveRegion() {
        show(billingScene)
        rule.onNodeWithTag(GoToElementTags.SEARCH).performTextInput("billing")
        rule.waitForIdle()
        val node = rule.onNodeWithTag(GoToElementTags.COUNT).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("1 match", SketchGoToStrings.resultCount(1, 0))
    }

    @Test
    fun hiddenLayer_should_SelectNothing_AndOfferToShowTheLayer() {
        val hidden = LayerId("h")
        val s = scene(listOf(rect("a", 0, layer = hidden)), layers = listOf(Layer(LayerId.Default, "Default"), Layer(hidden, "Walls", visible = false)))
        val entry = ElementLabelIndex.of(s).search("").shown.single()
        val outcome = GoToElementModel.activate(s, entry, SketchViewport.of(), ScreenRect(0.0, 0.0, 400.0, 400.0))
        assertIs<GoToOutcome.LayerHidden>(outcome)
        assertEquals(SketchGoToStrings.row(entry.kind, entry.name, SketchGoToStrings.HIDDEN_LAYER), GoToElementModel.rowText(entry))
    }

    @Test
    fun overflowMenu_should_OfferGoToElement_WhenTheHostProvidesIt() {
        var opened = 0
        val state = SketchEditorState()
        rule.setContent {
            SketchScreenV2(state, SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly, onGoToElement = { opened++ }), window = WindowDp(1280, 800))
        }
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_GO_TO).performClick()
        assertEquals(1, opened)
        rule.onNodeWithText(SketchGoToStrings.MENU_ITEM).assertDoesNotExist()
    }
}
