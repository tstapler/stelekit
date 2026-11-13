// jvmTest-reason: pixels
@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.ui.components.ImageBlock
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SketchPreviewRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val slug = "proj-0a1b2c"

    private fun drawingDoc(): SketchDocument {
        val rect = SketchPrimitive.Rect("r", SketchPoint(Inches(0), Inches(0)), SketchPoint(Inches(1920), Inches(960)))
        val line = SketchPrimitive.Line("l", SketchPoint(Inches(0), Inches(0)), SketchPoint(Inches(1920), Inches(960)))
        return SketchDocument(scene = SketchScene.EMPTY.add(rect).getOrNull()!!.add(line).getOrNull()!!)
    }

    @Test
    fun drawingPreviewRendersWithoutPlaceholderText() {
        rule.setContent { MaterialTheme { SketchPreviewContent(SketchPreview.Drawing(drawingDoc())) } }
        rule.waitForIdle()
        rule.onNodeWithText(EMPTY_SKETCH_TEXT).assertDoesNotExist()
    }

    @Test
    fun pendingMissingAndEmptyStates() {
        rule.setContent { MaterialTheme { SketchPreviewContent(null) } }
        rule.setContent { MaterialTheme { SketchPreviewContent(SketchPreview.Missing) } }
        rule.onNodeWithText(MISSING_SKETCH_TEXT).assertIsDisplayed()
    }

    @Test
    fun withoutALoaderTheBlockSaysTheSketchIsNotHere() {
        rule.setContent { MaterialTheme { SketchPreviewBlock(slug) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount(MISSING_SKETCH_TEXT) > 0 }
    }

    @Test
    fun savedSketchLoadsThenReloadsWhenSavedAgain() {
        val repos = inMemorySketchRepositories()
        runBlocking {
            repos.projects.saveProject(SketchProject(ProjectUuid("p1"), slug, "Test", 0))
            repos.sheets.saveSheet(SketchSheet(SheetUuid("s1"), ProjectUuid("p1"), "plan", "top", "in", Inches.SIXTEENTHS_PER_INCH), repos.projects.loadProject(ProjectUuid("p1")).getOrNull()!!.token)
        }
        val loader = SketchPreviewLoader(repos)
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalSketchPreviewLoader provides loader) { SketchPreviewBlock(slug) } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount(EMPTY_SKETCH_TEXT) > 0 }
        loader.notifySaved(slug)
        loader.notifySaved("other")
        rule.waitForIdle()
        rule.onNodeWithTag(SKETCH_PREVIEW_TAG).assertIsDisplayed()
    }

    @Test
    fun sketchEmbedImageBlockDrawsNativelyAndOpensTheSketchOnTap() {
        var opened = 0
        var selected = 0
        rule.setContent {
            MaterialTheme {
                ImageBlock(
                    url = "../assets/stelekit-sketch/$slug/preview.svg", altText = "sketch", onStartEditing = {},
                    onToggleSelect = { selected++ }, onOpenSketch = { opened++ }, sketchSlug = slug,
                )
            }
        }
        rule.onNodeWithText(MISSING_SKETCH_TEXT).performClick()
        assertEquals(1, opened)
        assertEquals(0, selected)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
