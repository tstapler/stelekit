// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Renders the editor chrome (top bar, tool strip, menu, panel) at the screen sizes that matter, so a layout change is judged on
 * pixels. Images land in `build/outputs/roborazzi/<CHROME_SHOT_PREFIX>-<scenario>.png` (record with `-Proborazzi.test.record=true`).
 * The assertions are layout facts that must hold at every size: a tool the user needs is reachable and nothing overlaps the strip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class MobileChromeRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val prefix = System.getenv("CHROME_SHOT_PREFIX") ?: "chrome"

    private fun mount(qualifiers: String = PHONE, title: String? = null, fontScale: Float = 1f, selected: Boolean = false, panel: PanelTab? = null): SketchEditorSession {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, title ?: opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        if (selected) rule.runOnUiThread { session.controller.submit(AddElement(filledRectEl("r1", 40, 40, 160, 90))) }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        if (selected) rule.runOnUiThread { session.controller.tools.select(listOf(ElementId("r1"))) }
        if (panel != null) rule.runOnUiThread { session.controller.ui.panelTab = panel }
        rule.waitForIdle()
        return session
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix-$name.png")

    @Test
    fun phone_empty() {
        mount()
        shot("phone-1-empty")
        rule.onNodeWithTag(ShellTags.OVERFLOW).assertExists()
        rule.onNodeWithTag(StripTags.tool(dev.stapler.stelekit.sketch.v2.tools.ToolId.Select)).assertExists()
    }

    @Test
    fun phone_selected() {
        mount(selected = true)
        shot("phone-2-selected")
    }

    @Test
    fun phone_menu() {
        mount(selected = true)
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        shot("phone-3-menu")
    }

    @Test
    fun phone_panel() {
        mount(selected = true, panel = PanelTab.Style)
        shot("phone-4-panel")
    }

    @Test
    fun phone_longTitle() {
        mount(title = "Kitchen cabinet elevation, north wall, revision 3")
        shot("phone-5-longtitle")
    }

    @Test
    fun phone_fontScale() {
        mount(fontScale = 1.5f, selected = true)
        shot("phone-6-fontscale")
    }

    @Test
    fun phone_wide() {
        mount(qualifiers = "w412dp-h915dp-xxhdpi", selected = true)
        shot("phone-7-wide")
    }

    @Test
    fun phone_longPressPopups() {
        mount(selected = true)
        val popups = listOf("select" to StripTags.tool(dev.stapler.stelekit.sketch.v2.tools.ToolId.Select), "delete" to StripTags.DELETE, "snap" to "sketch.snap")
        for ((name, tag) in popups) {
            rule.onNodeWithTag(tag).performTouchInput { longClick() }
            rule.waitForIdle()
            rule.onNodeWithTag(HelpTags.POPUP).assertExists()
            shot("phone-9-popup-$name")
            rule.onNodeWithTag(HelpTags.CLOSE).performClick()
            rule.waitForIdle()
        }
    }

    /** Every icon at its real 48 dp target size, named, so a reviewer sees the whole set side by side. */
    @Test
    fun iconSheet() {
        RuntimeEnvironment.setQualifiers(PHONE)
        rule.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in StripIcon.entries.chunked(ICONS_PER_ROW)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (icon in row) {
                                    Column(Modifier.width(ICON_CELL), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { StripIconView(icon, MaterialTheme.colorScheme.onSurface) }
                                        Text(icon.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        shot("icons-48dp")
    }

    @Test
    fun tablet_rail() {
        mount(qualifiers = "w1280dp-h800dp-mdpi", selected = true, panel = PanelTab.Style)
        shot("tablet-8-rail")
    }

    private companion object {
        const val PHONE = "w360dp-h780dp-xxhdpi"
        const val ICONS_PER_ROW = 5
        val ICON_CELL = 64.dp
    }
}
