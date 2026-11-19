// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.projectbridge.SketchCopyHost
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.ContainerText
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.ui.sketch.ProjectRow
import dev.stapler.stelekit.ui.sketch.SKETCH_COPY_DIALOG_TAG
import dev.stapler.stelekit.ui.sketch.SKETCH_COPY_PICK_TAG
import dev.stapler.stelekit.ui.sketch.SketchCopyFlow
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.sketchCopyTargetTag
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The engine's surfaces over the canvas: the label dialog (double click or "Edit label..."), the sheet-style row of the Style tab and
 * the sketch copy flow's graph picker. Robolectric, so no display is needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class EngineSurfacesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun rig(): Pair<SketchEditorController, EditorExtras> {
        val rect = RectElement(ElementBase(ElementId("r1")), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(100), SceneUnit.ofInt(100))
        val doc = AddElements(listOf(rect)).apply(SketchDocumentV2(sheetUuid = "s")).fold({ error("fixture: $it") }, { it.doc })
        var n = 0
        val metrics = FixedAdvanceTextMetricsProvider()
        val controller = SketchEditorController(doc, EditorPorts(StandardToolRegistry.create(metrics), null, null, ElementIdSource { ElementId("n${n++}") }), Dispatchers.Unconfined)
        return controller to EditorExtras(controller, HostServices(), ModernPreviewEnvironment(metrics, PainterResources()))
    }

    @Test
    fun labelDialog_should_SaveTheTypedTextAsTheShapesLabel() {
        val (controller, extras) = rig()
        rule.setContent { LabelDialog(extras.engine) }
        extras.engine.openLabel(ElementId("r1"))
        rule.waitForIdle()

        rule.onNodeWithTag(EngineTags.LABEL_FIELD).performTextClearance()
        rule.onNodeWithTag(EngineTags.LABEL_FIELD).performTextInput("Gateway")
        rule.onNodeWithTag(EngineTags.LABEL_SAVE).performClick()
        rule.waitForIdle()

        assertEquals("Gateway", assertNotNull(ContainerText.labelOf(controller.doc.scene, ElementId("r1"))).originalText)
        controller.close()
        extras.close()
    }

    @Test
    fun sheetStyleRow_should_MakeTheSheetSketchyOnTap() {
        val (controller, extras) = rig()
        rule.setContent { SheetStyleRow(controller) }
        assertEquals(RenderStyle.Clean, controller.sheetStyle.renderStyle)

        rule.onNodeWithTag(EngineTags.SHEET_SKETCHY).performClick()
        rule.waitForIdle()

        assertEquals(RenderStyle.Sketchy, controller.sheetStyle.renderStyle)
        controller.close()
        extras.close()
    }

    @Test
    fun copyFlow_should_ListTheOtherGraphsAndShowTheOutcomeOfThePick() {
        val open = GraphId("aaaaaaaaaaaaaaaa")
        val other = GraphId("bbbbbbbbbbbbbbbb")
        val registry = GraphRegistry(open, listOf(GraphInfo(open, "/g/open", "Open", 0L), GraphInfo(other, "/g/other", "Other", 0L)))
        val host = SketchCopyHost({ null }, { null }, StubFileSystem(), { "/g/open" }, { registry }, {})
        rule.setContent { SketchCopyFlow(host, ProjectRow("u1", "proj-1", "Kitchen")) {} }
        rule.onNodeWithTag(SKETCH_COPY_PICK_TAG).assertIsDisplayed()

        rule.onNodeWithTag(sketchCopyTargetTag(other.value)).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(SKETCH_COPY_DIALOG_TAG).assertIsDisplayed()
    }
}
