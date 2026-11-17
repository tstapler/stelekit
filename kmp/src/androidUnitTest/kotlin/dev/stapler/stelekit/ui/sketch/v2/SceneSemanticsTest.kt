// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.6.2 (UX-44, UX-45, UX-108, UX-117): the semantics layer over the canvas, the skip link and the region keys.
 * Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class SceneSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(d: Double) = SceneUnit.of(d).fold({ error("unit $d") }, { it })

    private fun base(id: String, ordinal: Int = 1, layer: LayerId = LayerId.Default) = ElementBase(ElementId(id), layerId = layer, ordinal = ordinal)

    private fun box(id: String, x: Double, y: Double, ordinal: Int, layer: LayerId = LayerId.Default, bound: List<BoundRef> = emptyList()) =
        RectElement(base(id, ordinal, layer), u(x), u(y), u(40.0), u(20.0), boundElements = bound)

    private fun diagram(extraRects: Int): SketchSceneV2 {
        val locked = Layer(LayerId("lock"), "Locked", locked = true, order = 1)
        var scene = SketchSceneV2.empty().withLayers(listOf(Layer.DefaultLayer, locked))
        val elements = listOf(
            box("api", 0.0, 0.0, 1, bound = listOf(BoundRef(ElementId("api-t"), BoundKind.Text))),
            box("db", 200.0, 0.0, 2, bound = listOf(BoundRef(ElementId("db-t"), BoundKind.Text))),
            TextElement(base("api-t", 3), u(2.0), u(2.0), u(30.0), u(10.0), text = "API", containerId = ElementId("api")),
            TextElement(base("db-t", 4), u(202.0), u(2.0), u(30.0), u(10.0), text = "DB", containerId = ElementId("db")),
            ArrowElement(
                base("arrow", 5), listOf(ScenePoint(u(40.0), u(10.0)), ScenePoint(u(200.0), u(10.0))),
                startBinding = Binding(ElementId("api"), 1.0, 0.5), endBinding = Binding(ElementId("db"), 0.0, 0.5),
            ),
            box("fixed", 0.0, 100.0, 6, layer = LayerId("lock")),
        )
        for (e in elements) scene = scene.add(e).fold({ error("fixture") }, { it })
        for (i in 0 until extraRects) scene = scene.add(box("x$i", 300.0 + i * 50.0, 300.0, 10 + i)).fold({ error("fixture") }, { it })
        return scene
    }

    private val viewport = SketchViewport.of()
    private val view = SemanticsView(viewport, 1280.0, 800.0)

    private fun show(scene: SketchSceneV2, over: SemanticsView = view) {
        val tree = SceneSemantics.build(scene, over, emptySet())
        rule.setContent { SceneSemanticsLayer(tree, viewport) }
        rule.waitForIdle()
    }

    @Test
    fun semantics_should_LabelTheArrowAndEmitEveryNodeOfTheTree() {
        show(diagram(extraRects = 0))

        rule.onNodeWithContentDescription("Arrow from API to DB").assertExists()
        rule.onNodeWithContentDescription("Rectangle 1, 40 by 20, labelled \"API\"").assertExists()
        rule.onAllNodesWithContentDescription("Rectangle", substring = true).assertCountEquals(3)
    }

    @Test
    fun semantics_should_CapTheNodesAndSayWhy_When_TheSheetHasMoreElementsThanTheCap() {
        show(diagram(extraRects = 300), SemanticsView(viewport, 100_000.0, 800.0))

        val cap = SketchSceneV2.empty().limits.maxSemanticsNodes
        rule.onNodeWithContentDescription("Arrow from API to DB").assertExists()
        rule.onNodeWithContentDescription("Showing $cap of 304 elements for screen reader; use Find to reach others").assertExists()
        rule.onAllNodesWithContentDescription("Rectangle", substring = true).assertCountEquals(cap - 1)
    }

    @Test
    fun lockedElements_should_BeAnnouncedButNotFocusable() {
        show(diagram(extraRects = 0))

        rule.onNodeWithContentDescription("Rectangle 6, 40 by 20, locked").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        rule.onNodeWithContentDescription("Arrow from API to DB").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
    }

    @Test
    fun canvas_should_ExposeApplicationRole() {
        show(diagram(extraRects = 0))

        rule.onNode(SemanticsMatcher.expectValue(CanvasApplicationRole, true)).assertExists()
        rule.onNode(hasContentDescriptionExactly(SketchStrings.Semantics.CANVAS)).assertExists()
    }

    @Test
    fun focusingANode_should_ReportItsElement() {
        val scene = diagram(extraRects = 0)
        val tree = SceneSemantics.build(scene, view, emptySet())
        val requesters = tree.nodes.associate { it.id to FocusRequester() }
        var focused: ElementId? = null
        rule.setContent { SceneSemanticsLayer(tree, viewport, requesters = requesters, onFocusElement = { focused = it }) }
        rule.waitForIdle()

        rule.runOnUiThread { requesters.getValue(ElementId("arrow")).requestFocus() }
        rule.waitForIdle()

        assertEquals(ElementId("arrow"), focused)
    }

    @Test
    fun skipLink_should_BeInvisibleUntilFocused_ThenVisible_AndRunTheSkip() {
        var skips = 0
        rule.setContent { SkipCanvasLink(onSkip = { skips++ }) }
        rule.waitForIdle()

        rule.onNodeWithText(SketchStrings.Semantics.SKIP_CANVAS_ELEMENTS).assertDoesNotExist()
        rule.onNodeWithTag(CanvasFocusTags.SKIP_LINK).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithText(SketchStrings.Semantics.SKIP_CANVAS_ELEMENTS).assertIsDisplayed()

        rule.onNodeWithTag(CanvasFocusTags.SKIP_LINK).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, skips)
        rule.onNodeWithTag(CanvasFocusTags.SKIP_LINK).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, skips)
    }

    @Test
    fun regionKeys_should_WalkTabF6AndEscapeThroughTheTraversal() {
        val present = setOf(TabStop.TopBar, TabStop.ToolStrip, TabStop.Canvas, TabStop.ViewCluster, TabStop.StatusLine)
        val stops = listOf(ElementId("a"), ElementId("b"))
        var focus: CanvasFocus by mutableStateOf(CanvasFocus.SkipLink)
        val left = mutableListOf<TabStop>()
        rule.setContent {
            Box(
                Modifier.fillMaxSize().testTag("region")
                    .canvasRegionKeys({ focus }, { stops }, { present }, { focus = it }, { left += it })
                    .focusable(),
            )
        }
        rule.onNodeWithTag("region").performSemanticsAction(SemanticsActions.RequestFocus)

        rule.onNodeWithTag("region").performKeyInput { pressKey(Key.Tab) }
        assertEquals(CanvasFocus.Element(ElementId("a")), focus)
        rule.onNodeWithTag("region").performKeyInput { pressKey(Key.Escape) }
        assertEquals(CanvasFocus.Container, focus)
        rule.onNodeWithTag("region").performKeyInput { pressKey(Key.Tab) }
        assertEquals(listOf(TabStop.ViewCluster), left)

        rule.onNodeWithTag("region").performKeyInput { pressKey(Key.F6) }
        assertEquals(listOf(TabStop.ViewCluster, TabStop.ViewCluster), left)
        rule.onNodeWithTag("region").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.F6); keyUp(Key.ShiftLeft) }
        assertEquals(TabStop.ToolStrip, left.last())
    }

    @Test
    fun focusRingStyle_should_FollowTheMaterialTheme() {
        var style: FocusRingStyle? = null
        rule.setContent { style = rememberFocusRingStyle() }
        rule.waitForIdle()
        val ring = style!!
        assertEquals(2f, ring.innerDp.value)
        assertEquals(2f, ring.outerDp.value)
        assertTrue(FocusRingContrast.ratio(ring.inner, ring.outer) >= FocusRingContrast.NON_TEXT_BAR)
    }
}
