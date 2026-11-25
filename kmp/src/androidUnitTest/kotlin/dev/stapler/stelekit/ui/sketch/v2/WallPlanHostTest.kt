// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.annotation.DimensionAnnotationEngine
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.PlanMode
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
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
 * A small floor plan on a phone (360 dp wide, 3 px per dp): the wall and opening chips of the Line tool, a drawn room with a
 * door and a window, and the wall lengths and the area label. State is asserted; the shots land in
 * `build/outputs/roborazzi/wall-plan-*.png` only in record mode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class WallPlanHostTest {
    @get:Rule
    val rule = createComposeRule()

    private fun u(i: Int) = SceneUnit.ofInt(i)

    private fun pt(x: Int, y: Int) = ScenePoint(u(x), u(y))

    private fun mount(): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        session.controller.setSheetKind(SheetKind.Technical)
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session
    }

    private fun screen(session: SketchEditorSession, x: Int, y: Int): Offset {
        val p = session.controller.ui.viewport.toScreen(ScenePoint(u(x), u(y)))
        return Offset(p.x.toFloat(), p.y.toFloat())
    }

    private fun walls(session: SketchEditorSession) = session.controller.doc.scene.elementsInOrder.filterIsInstance<WallElement>()

    private fun openings(session: SketchEditorSession) = session.controller.doc.scene.elementsInOrder.filterIsInstance<OpeningElement>()

    @Test
    fun lineTool_should_OfferTheWallAndOpeningModes_AndDrawARoomByTapping() {
        val session = mount()
        rule.runOnUiThread {
            session.controller.ui.fingerDrawing = true
            session.controller.ui.viewport = SketchViewport.of(zoom = 0.26, panX = 170.0, panY = 300.0)
            session.controller.tools.chooseTool(ToolId.Line)
        }
        rule.waitForIdle()
        PlanMode.entries.forEach { rule.onNodeWithTag(PlanTags.mode(it)).assertExists() }
        assertEquals(PlanMode.Line, session.controller.tools.planMode)

        rule.onNodeWithTag(PlanTags.mode(PlanMode.Wall)).performClick()
        rule.waitForIdle()
        assertEquals(PlanMode.Wall, session.controller.tools.planMode)
        assertEquals(0, rule.onAllNodesWithTag(PlanTags.CHIP_DONE).fetchSemanticsNodes().size, "no chain, no Done chip")

        val corners = listOf(0 to 0, 2688 to 0, 2688 to 2304, 0 to 2304)
        for ((x, y) in corners) {
            rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, x, y)); up() }
            rule.waitForIdle()
        }
        assertEquals(4, session.controller.tools.wallVertexCount)
        rule.onNodeWithTag(PlanTags.CHIP_CLOSE).performScrollTo().assertExists()
        rule.onNodeWithTag(PlanTags.CHIP_LENGTH).performScrollTo().assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/wall-plan-chain.png")

        rule.onNodeWithTag(PlanTags.CHIP_CLOSE).performScrollTo().performClick()
        rule.waitForIdle()
        val room = walls(session).single()
        assertTrue(room.closed)
        assertEquals(4, room.points.size)
        session.close()
    }

    @Test
    fun typedLength_should_PlaceTheNextWallExactly_FromTheKeypad() {
        val session = mount()
        rule.runOnUiThread {
            session.controller.ui.fingerDrawing = true
            session.controller.ui.viewport = SketchViewport.of(zoom = 0.26, panX = 170.0, panY = 300.0)
            session.controller.tools.choosePlanMode(PlanMode.Wall)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 0, 0)); up() }
        rule.waitForIdle()
        assertEquals(1, session.controller.tools.wallVertexCount)
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(screen(session, 1000, 0)); moveTo(screen(session, 1010, 0)); up() }
        rule.waitForIdle()
        assertEquals(2, session.controller.tools.wallVertexCount, "the chain follows the pointer's heading")
        // The Length chip opens the keypad dialog; what it types is read by the same parser as the dimension field.
        rule.onNodeWithTag(PlanTags.CHIP_LENGTH).performScrollTo().assertExists()
        val typed = dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind.Distance.let { DimensionValueParser.parse(it, "12' 3\"", technical = true) }
        assertEquals(12 * 192.0 + 3 * 16.0, typed.getOrNull())
        rule.runOnUiThread { session.controller.tools.typeWallLength(typed.getOrNull()!!) }
        rule.waitForIdle()
        assertEquals(3, session.controller.tools.wallVertexCount)
        rule.runOnUiThread { session.controller.tools.finishWalls() }
        rule.waitForIdle()
        val wall = walls(session).single()
        val last = wall.points.size - 1
        val run = Math.hypot(wall.points[last].x.value - wall.points[last - 1].x.value, wall.points[last].y.value - wall.points[last - 1].y.value)
        assertEquals(12 * 192.0 + 3 * 16.0, run, 1e-6)
        session.close()
    }

    @Test
    fun room_should_ShowWallLengthsAndTheAreaLabel_AndKeepDoorsOnTheirWalls() {
        val session = mount()
        val wall = WallElement(
            ElementBase(ElementId("room"), style = ElementStyle(backgroundColor = "#ced4da", roughness = 0)),
            listOf(pt(0, 0), pt(2688, 0), pt(2688, 2304), pt(0, 2304)), u(72), closed = true,
        )
        val door = OpeningElement(ElementBase(ElementId("door"), style = ElementStyle(roughness = 0)), wall.id, 0, u(600), u(576), OpeningKind.Door, flipSide = true)
        val window = OpeningElement(ElementBase(ElementId("window"), style = ElementStyle(roughness = 0)), wall.id, 2, u(500), u(768), OpeningKind.Window)
        rule.runOnUiThread { listOf(wall, door, window).forEach { session.controller.submit(AddElement(it)) } }
        rule.waitForIdle()
        rule.runOnUiThread { session.controller.ui.viewport = SketchViewport.of(zoom = 0.26, panX = 170.0, panY = 300.0) }
        rule.waitForIdle()
        assertEquals(3, session.controller.doc.scene.size)
        for (tag in listOf("s0", "s1", "s2", "s3", DimensionAnnotationEngine.AREA_TAG)) {
            assertEquals(1, rule.onAllNodesWithTag(DimensionAnnotationTags.value("room", PillKind.Length, tag)).fetchSemanticsNodes().size, "annotation $tag of the room")
        }
        val placedDoor = session.controller.doc.scene[ElementId("door")] as OpeningElement
        assertEquals(0.0, placedDoor.y.value + placedDoor.height.value / 2.0, 1e-6, "the door sits on the north wall")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/wall-plan-room.png")

        rule.runOnUiThread { session.controller.submit(dev.stapler.stelekit.sketch.v2.commands.MoveElements(listOf(ElementId("room")), 96.0, 48.0)) }
        rule.waitForIdle()
        val movedDoor = session.controller.doc.scene[ElementId("door")] as OpeningElement
        assertEquals(placedDoor.x.value + 96.0, movedDoor.x.value, 1e-6, "the door moved with its wall")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/wall-plan-room-moved.png")
        session.close()
    }
}
