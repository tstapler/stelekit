// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The always-on dimensions of a technical sheet at phone size (360x780 dp, xxhdpi): a small floor plan with neighbours, a
 * circle and a line, drawn like an elevation, with a selection, zoomed out and zoomed in. Images land in
 * `build/outputs/roborazzi/dims-*.png` (record with `-Proborazzi.test.record=true`). jvmTest-reason: pixels.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class DimensionAnnotationsHostTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeSource(val bundle: ProjectBundle) : TechnicalDataSource {
        override suspend fun load(): ProjectBundle = bundle
        override suspend fun save(bundle: ProjectBundle): Boolean = true
    }

    private val living = filledRectEl("living", 30, 60, 160, 120)
    private val kitchen = filledRectEl("kitchen", 190, 60, 120, 70)
    private val hall = filledRectEl("hall", 190, 130, 120, 50)
    private val table = EllipseElement(ElementBase(ElementId("table")), su(90), su(290), su(50), su(50))
    private val bed = filledRectEl("bed", 190, 240, 120, 110)
    private val wall = LineElement(ElementBase(ElementId("wall")), listOf(sp(30, 400), sp(310, 430)))
    private val plan: List<SceneElement> = listOf(living, kitchen, hall, table, bed, wall)

    private fun mount(
        elements: List<SceneElement> = plan,
        select: List<String> = emptyList(),
        technical: Boolean = true,
        zoom: Double = 3.0,
        panX: Double = 0.0,
        panY: Double = 0.0,
        colors: ColorScheme = lightColorScheme(),
    ): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val project = ProjectFactory.create("Plan", "proj-abc123", ProjectFactory.newUuid(), 1L)
        val bundle = ProjectBundle(project, listOf(SketchSheet(sheet, project.uuid, "plan", "top", "in", 16)), emptyList(), listOf(SketchDimension("d1", project.uuid, "width", "length", Rational(32), null, "user")))
        val services = if (technical) HostServices(technical = FakeSource(bundle)) else HostServices()
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, services)
        if (technical) rule.runOnUiThread { session.controller.setSheetKind(SheetKind.Technical) }
        rule.runOnUiThread { elements.forEach { session.controller.submit(AddElement(it)) } }
        rule.setContent {
            MaterialTheme(colorScheme = colors) { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            session.controller.ui.viewport = SketchViewport.of(zoom, panX, panY)
            session.controller.tools.select(select.map(::ElementId))
        }
        rule.waitForIdle()
        return session
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/dims-$name.png")

    private fun value(id: String, kind: PillKind) = rule.onNodeWithTag(DimensionAnnotationTags.value(id, kind))

    @Test
    fun everyShape_should_ShowItsDimensions_WithoutBeingSelected() {
        mount()
        value("living", PillKind.Width).assertIsDisplayed()
        value("living", PillKind.Height).assertIsDisplayed()
        value("table", PillKind.Diameter).assertIsDisplayed()
        value("wall", PillKind.Length).assertIsDisplayed()
        shot("1-default")
    }

    @Test
    fun aSelectedShape_should_KeepItsPills_AndTheOthersStayAnnotated() {
        mount(select = listOf("living"))
        rule.onNodeWithTag(DimensionPillTags.pill(PillKind.Width)).assertIsDisplayed()
        rule.onAllNodesWithTag(DimensionAnnotationTags.value("living", PillKind.Width)).assertCountEquals(0)
        value("kitchen", PillKind.Width).assertIsDisplayed()
        shot("2-selected")
    }

    @Test
    fun zoomedOut_should_DropTheAnnotationsThatWouldBeUnreadable() {
        mount(zoom = 1.2)
        shot("3-zoomed-out")
        value("living", PillKind.Width).assertIsDisplayed()
        rule.onAllNodesWithTag(DimensionAnnotationTags.value("table", PillKind.Diameter)).assertCountEquals(0)
    }

    @Test
    fun zoomedIn_should_ShowTheShapesThatStillFit() {
        mount(zoom = 6.0, panX = -60.0, panY = -120.0)
        value("living", PillKind.Width).assertIsDisplayed()
        shot("4-zoomed-in")
    }

    @Test
    fun aDarkTheme_should_DrawReadableDimensions() {
        mount(select = listOf("table"), colors = darkColorScheme())
        shot("5-dark")
    }

    @Test
    fun tappingAValue_should_SelectTheShape_AndOpenItsEditor_WithAFingerSizedTarget() {
        val session = mount()
        value("wall", PillKind.Length).assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
        value("kitchen", PillKind.Width).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(setOf("kitchen"), session.controller.ui.selection)
        rule.onNodeWithTag(DimensionPillTags.FIELD).assertIsDisplayed()
        rule.onAllNodesWithTag(DimensionAnnotationTags.value("kitchen", PillKind.Width)).assertCountEquals(0)
        rule.onAllNodesWithTag(DimensionAnnotationTags.value("kitchen", PillKind.Height)).assertCountEquals(0)
        shot("6-editing")
    }

    @Test
    fun aDragOnEmptyCanvas_should_Pan_SoTheValueDragTestMeansSomething() {
        val session = mount(technical = false)
        val before = session.controller.ui.viewport.panX
        rule.onRoot().performTouchInput {
            down(Offset(300f, 1500f))
            moveBy(Offset(-120f, 0f))
            moveBy(Offset(-120f, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue(session.controller.ui.viewport.panX != before)
    }

    @Test
    fun aDragStartingOnAValue_should_StillReachTheCanvas_AndNeverOpenAnEditor() {
        val session = mount()
        val before = session.controller.ui.viewport.panX
        val wallBefore = session.controller.doc.scene[ElementId("wall")]
        value("wall", PillKind.Length).performTouchInput {
            down(center)
            moveBy(Offset(-120f, 0f))
            moveBy(Offset(-120f, 0f))
            up()
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag(DimensionPillTags.FIELD).assertCountEquals(0)
        val reachedCanvas = session.controller.ui.viewport.panX != before || session.controller.doc.scene[ElementId("wall")] != wallBefore
        assertTrue(reachedCanvas, "swallowed: pan ${session.controller.ui.viewport.panX} vs $before, sel ${session.controller.ui.selection}, tool ${session.controller.ui.activeTool}, preview ${session.controller.tools.preview}")
    }

    @Test
    fun theValue_should_ReadAsAFullSentence_ForAScreenReader() {
        mount()
        val node = value("living", PillKind.Width).fetchSemanticsNode()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
        assertTrue(description.startsWith("Width of ") && (description.contains("inches") || description.contains("feet")), description)
    }

    @Test
    fun theScopeChip_should_LimitTheDimensionsToTheSelection() {
        mount(select = listOf("kitchen"))
        rule.onNodeWithTag(DimensionPillTags.CHIP_DIMENSION_SCOPE).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(DimensionAnnotationTags.value("living", PillKind.Width)).assertCountEquals(0)
        rule.onNodeWithTag(DimensionPillTags.pill(PillKind.Width)).assertIsDisplayed()
        shot("7-selected-only")
        rule.onNodeWithTag(DimensionPillTags.CHIP_DIMENSIONS).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(DimensionPillTags.pill(PillKind.Width)).assertCountEquals(0)
    }

    @Test
    fun aDiagramSheet_should_ShowNoDimensions() {
        mount(technical = false)
        rule.onAllNodesWithTag(DimensionAnnotationTags.LAYER).assertCountEquals(0)
    }

    @Test
    fun theChipRow_should_OfferTheDimensionsToggle_WithNothingSelected() {
        mount()
        rule.onNodeWithTag(DimensionPillTags.CHIP_DIMENSIONS).assertIsDisplayed()
        rule.onNodeWithTag(DimensionPillTags.CHIP_DIMENSION_SCOPE).assertIsDisplayed()
    }
}
