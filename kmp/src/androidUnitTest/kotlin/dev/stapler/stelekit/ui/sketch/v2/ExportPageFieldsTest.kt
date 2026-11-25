// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * E7: the field dialogs of the export page section (custom paper, custom ratio, title block). Legacy Robolectric graphics, like the
 * Go to element test: with native graphics a focused text field kept the window re-measuring. Gradle-only (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h800dp-mdpi")
class ExportPageFieldsTest {
    @get:Rule
    val rule = createComposeRule()

    private val runner = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))
    private val remembered = mutableListOf<dev.stapler.stelekit.sketch.v2.export.SheetSetup>()

    private fun show(): ExportDialogController {
        val scene = SketchSceneV2.empty().add(
            RectElement(ElementBase(ElementId("room")), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(1920), SceneUnit.ofInt(1440)),
        ).getOrNull()!!
        val source = ExportSource(scene, emptySet(), SvgImageSource.None, baseName = "plan")
        val controller = ExportDialogController({ source }, runner, FakeSketchFiles(), {}, dispatcher = Dispatchers.Unconfined, rememberSheet = { remembered += it })
        controller.open()
        rule.setContent { ExportDialog(controller) }
        rule.waitForIdle()
        return controller
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun open(c: ExportDialogController) = c.state.value as ExportUiState.Open

    @Test
    fun theFieldDialogs_should_FlowIntoTheSetup_OnlyWhenValid() {
        val controller = show()
        tap(ExportTags.format(ExportFormat.Pdf))
        tap(ExportPageTags.TITLE_BLOCK)
        tap(ExportPageTags.TITLE_EDIT)
        rule.onNodeWithTag(ExportPageTags.field("title")).performTextReplacement("Kitchen")
        rule.onNodeWithTag(ExportPageTags.field("author")).performTextReplacement("T. S.")
        rule.waitForIdle()
        tap(ExportPageTags.FIELDS_APPLY)
        assertEquals("Kitchen", open(controller).request.sheet!!.titleBlock!!.title)
        assertEquals("T. S.", open(controller).request.sheet!!.titleBlock!!.author)
        rule.onNodeWithTag(ExportPageTags.TITLE_SHOWN).assertTextContains("Kitchen")

        tap(ExportPageTags.SCALE_CUSTOM_BUTTON)
        rule.onNodeWithTag(ExportPageTags.SCALE_CUSTOM).performTextReplacement("nonsense")
        rule.waitForIdle()
        rule.onNodeWithTag(ExportPageTags.FIELDS_APPLY).assertIsNotEnabled()
        rule.onNodeWithTag(ExportPageTags.SCALE_CUSTOM).performTextReplacement("1:37")
        rule.waitForIdle()
        tap(ExportPageTags.FIELDS_APPLY)
        assertEquals(DrawingScale.of(1, 37), open(controller).request.sheet!!.scale)

        tap(ExportPageTags.PAPER_CUSTOM_BUTTON)
        rule.onNodeWithTag(ExportPageTags.PAPER_CUSTOM).performTextReplacement("300x200mm")
        rule.waitForIdle()
        tap(ExportPageTags.FIELDS_APPLY)
        assertEquals("300x200mm", open(controller).request.sheet!!.paper.id)
    }
}
