// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.tools.RotatePivot
import dev.stapler.stelekit.sketch.v2.tools.RotatePlan
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rotating a mixed selection on a phone: the Rotate chip opens the angle dialog, a typed angle turns a rectangle and a line as one
 * step. Roborazzi shots go to build/outputs/roborazzi when recording; every assertion is on state, not on those files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class RotatePhoneRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun unit(i: Int) = SceneUnit.ofInt(i)

    private class Opened(val session: SketchEditorSession, val metrics: FixedAdvanceTextMetricsProvider)

    private fun open(): Opened {
        val rig = V2EditorRig()
        val rect = RectElement(ElementBase(ElementId("r")), unit(40), unit(60), unit(160), unit(100))
        val line = LineElement(ElementBase(ElementId("l")), listOf(ScenePoint(unit(40), unit(200)), ScenePoint(unit(240), unit(260))))
        val seeded = AddElements(listOf(rect, line)).apply(SketchDocumentV2(sheetUuid = rig.sheet.value)).getOrNull()!!.doc
        runBlocking { rig.seed(seeded) }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        return Opened(SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, HostServices()), metrics)
    }

    @Test
    fun rotateChip_should_AppearForAMixedSelection_AndOpenTheAngleDialog() {
        val (session, metrics) = open().let { it.session to it.metrics }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        session.controller.ui.selection = setOf("r", "l")
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/rotate-1-selected.png")
        rule.onNodeWithTag(RotateTags.CHIP).assertExists()
        assertTrue(RotateTags.CHIP in planChips(session.controller).map { it.tag })
        rule.onNodeWithTag(RotateTags.CHIP).performClick()
        assertEquals(EditorOverlay.Rotate, session.controller.overlay)
    }

    @Test
    fun rotateCommand_should_TurnARectangleAndALine_AsOneStep_AndRenderTurned() {
        val (session, metrics) = open().let { it.session to it.metrics }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        session.controller.ui.selection = setOf("r", "l")
        // The angle dialog's text field never lets a Robolectric test idle (see ExportDialogPageTest), so the dialog's own command is
        // built by RotatePlan, the code the dialog calls, and submitted as the dialog does.
        val ids = listOf(ElementId("r"), ElementId("l"))
        val command = assertNotNull(RotatePlan.command(session.controller.doc.scene, ids, 45.0, RotatePivot.Centre))
        session.controller.submit(command)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/rotate-2-turned.png")

        val doc = session.controller.doc
        assertEquals(PI / 4.0, (doc.scene[ElementId("r")] as RectElement).angle, TOLERANCE)
        val points = (doc.scene[ElementId("l")] as LineElement).points
        val before = 200.0 * 200.0 + 60.0 * 60.0
        val dx = points[1].x.value - points[0].x.value
        val dy = points[1].y.value - points[0].y.value
        assertEquals(before, dx * dx + dy * dy, 1e-3)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
