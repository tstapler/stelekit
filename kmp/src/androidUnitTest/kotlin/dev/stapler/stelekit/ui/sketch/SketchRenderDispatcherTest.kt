// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Story 6.1.1 (UX-61, UX-135, UX-60): every embed state renders, offers what it can and never crashes. Robolectric, so no
 * display is needed. Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class SketchRenderDispatcherTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val environment = ModernPreviewEnvironment(FixedAdvanceTextMetricsProvider(), PainterResources())

    private fun show(preview: SketchPreview?, actions: SketchEmbedActions = SketchEmbedActions(), modern: ModernPreviewEnvironment? = environment) {
        rule.setContent { MaterialTheme { SketchPreviewContent(preview, actions = actions, modern = modern) } }
        rule.waitForIdle()
    }

    private fun u(v: Double) = SceneUnit.of(v).getOrNull() ?: error("unit")

    private fun scene(): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        s = s.add(RectElement(ElementBase(ElementId("r")), u(0.0), u(0.0), u(120.0), u(60.0))).getOrNull() ?: error("rect")
        s = s.add(EllipseElement(ElementBase(ElementId("e")), u(200.0), u(30.0), u(30.0), u(20.0))).getOrNull() ?: error("ellipse")
        return s
    }

    @Test
    fun missingSketch_should_OfferRemoveEmbedAndChooseAnother() {
        show(SketchPreview.Missing, SketchEmbedActions(onRemoveEmbed = { calls += "remove" }, onChooseAnother = { calls += "choose" }))

        rule.onNodeWithText("Sketch not found").assertIsDisplayed()
        rule.onNodeWithTag(SKETCH_REMOVE_EMBED_TAG).performClick()
        rule.onNodeWithTag(SKETCH_CHOOSE_ANOTHER_TAG).performClick()
        assertEquals(listOf("remove", "choose"), calls)
    }

    @Test
    fun missingSketch_should_HideActionsTheHostCannotDo() {
        show(SketchPreview.Missing)

        rule.onNodeWithText("Sketch not found").assertIsDisplayed()
        rule.onNodeWithTag(SKETCH_REMOVE_EMBED_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SKETCH_CHOOSE_ANOTHER_TAG).assertDoesNotExist()
    }

    @Test
    fun newerSchema_should_ShowUpdateNotice() {
        show(SketchPreview.NewerSchema)

        rule.onNodeWithText("Update SteleKit to view").assertIsDisplayed()
    }

    @Test
    fun noFileStorage_should_ShowThePlaceholderWithOpenPage() {
        show(SketchPreview.StorageUnavailable, SketchEmbedActions(onOpenPage = { calls += "open" }))

        rule.onNodeWithText("New-editor sketch. Open it in the desktop or Android app.").assertIsDisplayed()
        rule.onNodeWithTag(SKETCH_OPEN_PAGE_TAG).performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun v2Sheet_should_DrawThroughTheDrawList_WithoutPlaceholderText() {
        show(SketchPreview.ModernDrawing(scene()))

        rule.onNodeWithTag(SKETCH_PREVIEW_MODERN_TAG).assertIsDisplayed()
        rule.onNodeWithText(EMPTY_SKETCH_TEXT).assertDoesNotExist()
        rule.onNodeWithText(MISSING_SKETCH_TEXT).assertDoesNotExist()
    }

    @Test
    fun v1Sheet_should_StillDrawThroughTheClassicPreview() {
        show(SketchPreview.Drawing(SketchDocument(), notShownCount = 2))

        rule.onNodeWithTag(SKETCH_PREVIEW_NOT_SHOWN_TAG).assertIsDisplayed()
        rule.onNodeWithTag(SKETCH_PREVIEW_MODERN_TAG).assertDoesNotExist()
    }

    @Test
    fun previewAspect_should_BeClampedAndDefaultForNoContent() {
        assertEquals(2f, modernPreviewAspect(null))
        val wide = dev.stapler.stelekit.sketch.v2.view.SceneBounds(0.0, 0.0, 1000.0, 10.0)
        val tall = dev.stapler.stelekit.sketch.v2.view.SceneBounds(0.0, 0.0, 10.0, 1000.0)
        assertEquals(4f, modernPreviewAspect(wide))
        assertEquals(0.4f, modernPreviewAspect(tall))
    }
}
