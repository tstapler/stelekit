// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Provenance
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.sketch.v2.scene.unreviewed
import dev.stapler.stelekit.sketch.v2.scene.withProvenance
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent-written elements on a phone (epic E8): a dashed ghost and an "AI" badge on each, a bar that says how many wait, and
 * Accept all as one undo step. Roborazzi shots go to build/outputs/roborazzi when recording; every assertion is on state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class ProvenancePhoneRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mount(): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, HostServices())
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session
    }

    private fun agentRect(id: String, x: Int, provenance: Provenance) = RectElement(
        ElementBase(ElementId(id)).withProvenance(provenance),
        SceneUnit.ofInt(x), SceneUnit.ofInt(SIDE), SceneUnit.ofInt(SIDE * 2), SceneUnit.ofInt(SIDE),
    )

    @Test
    fun badges_should_AppearForUnreviewedAgentElements_AndAcceptAllClearsThemInOneUndoStep() {
        val session = mount()
        val controller = session.controller
        assertTrue(controller.commitNow(AddElement(agentRect("island", SIDE, Provenance(confidence = 70, note = "read off the photo")))).isRight())
        assertTrue(controller.commitNow(AddElement(agentRect("pantry", SIDE * 4, Provenance(confidence = 30)))).isRight())
        rule.waitForIdle()
        rule.onNodeWithTag(ProvenanceTags.BAR).assertExists()
        rule.onNodeWithText("2 agent changes to review").assertExists()
        rule.onNodeWithTag(ProvenanceTags.badge(ElementId("island"))).assertExists()
        rule.onNodeWithTag(ProvenanceTags.badge(ElementId("pantry"))).assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/provenance-1-badges.png")

        rule.onNodeWithTag(ProvenanceTags.ACCEPT_ALL).performClick()
        rule.waitForIdle()
        assertTrue(controller.doc.scene.unreviewed().isEmpty())
        assertTrue(assertNotNull(controller.doc.scene[ElementId("island")]).base.provenance!!.accepted, "the marker stays after accepting")
        rule.onNodeWithTag(ProvenanceTags.BAR).assertDoesNotExist()
        rule.onNodeWithTag(ProvenanceTags.badge(ElementId("island"))).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/provenance-2-accepted.png")

        controller.undo()
        rule.waitForIdle()
        assertEquals(2, controller.doc.scene.unreviewed().size, "one undo brings both ghosts back")
        rule.onNodeWithTag(ProvenanceTags.BAR).assertExists()
    }

    private companion object {
        const val SIDE = 96
    }
}
