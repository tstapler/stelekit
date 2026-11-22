// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Every clickable control of the phone chrome, with a shape selected and the panel open, is at least the 44 dp touch floor each way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class ChromeTargetSizeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyClickableControl_should_MeetTheTouchFloor() {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = true)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        rule.runOnUiThread { session.controller.submit(AddElement(filledRectEl("r1", 40, 40, 400, 300))) }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        rule.runOnUiThread {
            session.controller.tools.select(listOf(ElementId("r1")))
            session.controller.ui.panelTab = PanelTab.Style
        }
        rule.waitForIdle()

        val density = rule.density.density        // The laid-out size, not the clipped bounds: a swatch half scrolled out of the panel is still a full-size target.
        val small = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val w = node.size.width / density
            val h = node.size.height / density
            val laidOut = node.size.width > 0 && node.size.height > 0
            // The skip link is a 1 dp keyboard-only anchor by design; a handle's hit area is its own business (HandleLayoutTest).
            laidOut && !tag.startsWith("handle-") && tag != ShellTags.CANVAS && tag != SKIP_LINK && (w < FLOOR_DP - EPS || h < FLOOR_DP - EPS)
        }.map { node ->
            "${node.config.getOrNull(SemanticsProperties.TestTag)} ${node.size.width / density} x ${node.size.height / density} dp"
        }
        assertEquals(emptyList(), small, "controls under the 44 dp touch floor")
    }

    private companion object {
        const val SKIP_LINK = "sketch.canvas.skip"
        const val FLOOR_DP = 44f
        const val EPS = 0.6f
    }
}
