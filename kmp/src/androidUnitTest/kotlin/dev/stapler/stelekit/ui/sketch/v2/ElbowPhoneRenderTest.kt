// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.binding.ElbowRouting
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.reconciled
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An elbow connector between two boxes on a phone, and again after one box moves: it stays orthogonal and ends on the box. Roborazzi
 * shots go to build/outputs/roborazzi when recording; every assertion is on state, not on those files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class ElbowPhoneRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun unit(i: Int) = SceneUnit.ofInt(i)

    private fun point(x: Int, y: Int) = ScenePoint(unit(x), unit(y))

    private fun box(id: String, x: Int, y: Int) = RectElement(ElementBase(ElementId(id)), unit(x), unit(y), unit(100), unit(60))

    private fun orthogonal(arrow: ArrowElement) =
        arrow.points.zipWithNext().all { (p, q) -> abs(p.x.value - q.x.value) < EPS || abs(p.y.value - q.y.value) < EPS }

    @Test
    fun elbowConnector_should_StayOrthogonalAndBound_WhenABoxMoves() {
        val rig = V2EditorRig()
        val arrow = ArrowElement(
            ElementBase(ElementId("e")), listOf(point(140, 70), point(200, 250)),
            startBinding = Binding(ElementId("a"), 1.0, 0.5), endBinding = Binding(ElementId("b"), 0.0, 0.5), elbowed = true,
        )
        val seeded = AddElements(listOf(box("a", 40, 40), box("b", 200, 220), arrow)).apply(SketchDocumentV2(sheetUuid = rig.sheet.value)).getOrNull()!!.doc
        val routed = ElbowRouting.pointsOf(seeded.scene, seeded.scene[ElementId("e")] as ArrowElement)!!
        val doc = seeded.copy(scene = seeded.scene.replace((seeded.scene[ElementId("e")] as ArrowElement).copy(points = routed)).getOrNull()!!.reconciled())
        runBlocking { rig.seed(doc) }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, HostServices())
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/elbow-1-routed.png")
        assertTrue(orthogonal(session.controller.doc.scene[ElementId("e")] as ArrowElement))

        session.controller.submit(MoveElements(listOf(ElementId("b")), -150.0, 120.0))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/elbow-2-after-move.png")
        val after = session.controller.doc.scene[ElementId("e")] as ArrowElement
        val moved = session.controller.doc.scene[ElementId("b")] as RectElement
        assertTrue(orthogonal(after), "${after.points}")
        assertEquals(moved.x.value, after.points.last().x.value, EPS)
        assertEquals(moved.y.value + 30.0, after.points.last().y.value, EPS)
    }

    private companion object {
        const val EPS = 1e-6
    }
}
