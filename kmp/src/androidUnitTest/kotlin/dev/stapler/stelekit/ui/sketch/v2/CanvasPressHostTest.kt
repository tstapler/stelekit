// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Presses on a phone-sized host reach the canvas: nothing in the chrome (the hint, the name tag, the chips, the top bar and the dock)
 * eats a press meant for the drawing, and a vertex grab works through the real pointer path at the phone's density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class CanvasPressHostTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mount(vararg elements: dev.stapler.stelekit.sketch.v2.scene.SceneElement): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        rule.runOnUiThread { elements.forEach { session.controller.submit(AddElement(it)) } }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session
    }

    @Test
    fun aPressWhereTheHintIsShown_should_SelectTheShapeBeneathIt() {
        val session = mount(filledRectEl("r1", 20, 20, 200, 120))
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(Offset(100f, 60f)); up() }
        rule.waitForIdle()
        assertEquals(setOf("r1"), session.controller.ui.selection)
    }

    @Test
    fun aVertexGrab_should_MoveOnlyThatVertex_ThroughTheRealPointerPath() {
        val line = LineElement(ElementBase(ElementId("l1")), listOf(sp(200, 300), sp(500, 300)))
        val session = mount(line)
        // With Finger drawing off a one-finger drag pans the view; the user in the field had it on, so this does too.
        rule.runOnUiThread { session.controller.ui.fingerDrawing = true; session.controller.tools.select(listOf(ElementId("l1"))) }
        rule.waitForIdle()

        // 22 px off the vertex is well inside the 22 dp reach on a 3x screen and well outside the old 22 px reach of a 1x one.
        rule.onNodeWithTag(ShellTags.CANVAS).performTouchInput { down(Offset(220f, 310f)); moveTo(Offset(260f, 360f)); up() }
        rule.waitForIdle()

        val moved = assertIs<LineElement>(session.controller.doc.scene[ElementId("l1")])
        assertTrue(abs(moved.points[0].x.value - 240.0) < 3.0 && abs(moved.points[0].y.value - 350.0) < 3.0, "first vertex ended at ${moved.points[0]}")
        assertEquals(sp(500, 300), moved.points[1])
    }
}
