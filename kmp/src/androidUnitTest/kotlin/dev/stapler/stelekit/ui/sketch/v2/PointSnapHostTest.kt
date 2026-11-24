// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.snap.GridPreset
import dev.stapler.stelekit.sketch.v2.snap.PointSnapHit
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings
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
 * The point-snap markers, the coarse grid and the Snap menu section on a phone (360 dp wide, 3 px per dp), through the real
 * pointer path. Shots land in `build/outputs/roborazzi/point-snap-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class PointSnapHostTest {
    @get:Rule
    val rule = createComposeRule()

    private fun line(id: String, ax: Int, ay: Int, bx: Int, by: Int) =
        LineElement(ElementBase(ElementId(id)), listOf(ScenePoint(SceneUnit.ofInt(ax), SceneUnit.ofInt(ay)), ScenePoint(SceneUnit.ofInt(bx), SceneUnit.ofInt(by))))

    private fun mount(): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        session.controller.setSheetKind(SheetKind.Technical)
        rule.runOnUiThread { listOf(line("a", 100, 200, 500, 500), line("b", 100, 500, 500, 200)).forEach { session.controller.submit(AddElement(it)) } }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        rule.runOnUiThread { session.controller.ui.fingerDrawing = true; session.controller.tools.chooseTool(ToolId.Line) }
        rule.waitForIdle()
        return session
    }

    private fun screen(session: SketchEditorSession, x: Int, y: Int): Offset {
        val p = session.controller.ui.viewport.toScreen(ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y)))
        return Offset(p.x.toFloat(), p.y.toFloat())
    }

    private fun markers() = rule.onAllNodesWithTag(SnapMarkerTags.MARKER).fetchSemanticsNodes().size

    private fun dragToCrossing(session: SketchEditorSession) {
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 600, 700)); moveTo(screen(session, 306, 346)) }
        rule.waitForIdle()
    }

    @Test
    fun drag_should_ShowTheIntersectionMarkerAndName_AndCommitOnTheCrossing() {
        val session = mount()
        rule.onNodeWithTag("sketch.grid").performClick()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag(SnapMarkerTags.GRID).fetchSemanticsNodes().size, "the grid toggle draws the grid")

        assertEquals(0, markers())
        dragToCrossing(session)
        assertEquals(1, markers())
        rule.onNodeWithText(SketchHostStrings.Snap.kindName(PointSnapKind.Intersection), useUnmergedTree = true).assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/point-snap-intersection-grid.png")

        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, markers(), "the marker goes with the drag")
        val drawn = session.controller.doc.scene.elementsInOrder.filterIsInstance<LineElement>().first { it.base.id.value !in setOf("a", "b") }
        assertEquals(listOf(300.0, 350.0), listOf(drawn.points.last().x.value, drawn.points.last().y.value))
        session.close()
    }

    @Test
    fun menu_should_ListEveryKind_AndTurningOneOffStopsThatSnap() {
        val session = mount()
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SnapMenuTags.SECTION).performClick()
        rule.waitForIdle()
        PointSnapKind.entries.forEach { rule.onNodeWithTag(SnapMenuTags.kind(it)).assertExists() }
        rule.onNodeWithTag(SnapMenuTags.ALIGNMENT).assertExists()
        rule.onNodeWithTag(SnapMenuTags.GRID_STEP).assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/point-snap-menu.png")

        rule.onNodeWithTag(SnapMenuTags.GRID_STEP).performClick()
        rule.waitForIdle()
        assertEquals(GridPreset.Quarter, session.extras!!.snapChoices.gridPreset)
        rule.onNodeWithTag(SnapMenuTags.kind(PointSnapKind.Intersection)).performClick()
        rule.waitForIdle()
        assertTrue(PointSnapKind.Intersection !in session.extras!!.snapChoices.kinds)
        rule.onNodeWithTag(SnapMenuTags.kind(PointSnapKind.Intersection)).performClick()
        rule.waitForIdle()
        assertTrue(PointSnapKind.Intersection in session.extras!!.snapChoices.kinds)
        session.close()
    }

    @Test
    fun markers_should_ShowEveryGlyphWithItsName_OnAPhone() {
        val viewport = SketchViewport.of()
        val hits = PointSnapKind.entries.mapIndexed { i, kind -> PointSnapHit(60.0 + (i % 2) * 500.0, 80.0 + (i / 2) * 120.0, kind, 0.0) }
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) { hits.forEach { SnapPointMarker(it, viewport) } }
            }
        }
        rule.waitForIdle()
        PointSnapKind.entries.forEach { rule.onNodeWithText(SketchHostStrings.Snap.kindName(it), useUnmergedTree = true).assertExists() }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/point-snap-glyphs.png")
    }
}
