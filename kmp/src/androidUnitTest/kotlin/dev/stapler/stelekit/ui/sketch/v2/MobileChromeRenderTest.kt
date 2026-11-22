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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.units.Rational
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

    /** One screen to render: where, with what on the sheet and what open. */
    private data class Scenario(
        val qualifiers: String = PHONE,
        val title: String? = null,
        val fontScale: Float = 1f,
        val elements: List<SceneElement> = emptyList(),
        val select: List<String> = emptyList(),
        val panel: PanelTab? = null,
        val technical: Boolean = false,
        val dark: Boolean = false,
        val touch: Boolean = true,
    )

    private class FakeSource(val bundle: ProjectBundle) : TechnicalDataSource {
        override suspend fun load(): ProjectBundle = bundle
        override suspend fun save(bundle: ProjectBundle): Boolean = true
    }

    private val box = filledRectEl("r1", 40, 40, 160, 90)
    private val line = LineElement(ElementBase(ElementId("l1")), listOf(sp(60, 80), sp(220, 150), sp(300, 90)))

    private fun mount(s: Scenario = Scenario()): SketchEditorSession {
        RuntimeEnvironment.setQualifiers(s.qualifiers)
        RuntimeEnvironment.setFontScale(s.fontScale)
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        // A phone has a touch screen: the Select tool then offers compact corner handles on small shapes.
        val deps = V2EditorDeps(repos = rig.repos, store = { rig.store }, leases = null, settings = rig.deps.settings, graphId = { V2EditorRig.GRAPH }, touchPresent = s.touch)
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val services = if (s.technical) HostServices(technical = FakeSource(bundleOf(sheet))) else HostServices()
        val session = SketchEditorSession(opened.controller, opened.diagnostics, s.title ?: opened.title, null, opened.slug, sheet, services)
        if (s.technical) rule.runOnUiThread { session.controller.setSheetKind(SheetKind.Technical) }
        rule.runOnUiThread { s.elements.forEach { session.controller.submit(AddElement(it)) } }
        val scheme = if (s.dark) darkColorScheme() else lightColorScheme()
        rule.setContent { MaterialTheme(colorScheme = scheme) { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) } }
        rule.waitForIdle()
        if (s.select.isNotEmpty()) rule.runOnUiThread { session.controller.tools.select(s.select.map(::ElementId)) }
        if (s.panel != null) rule.runOnUiThread { session.controller.ui.panelTab = s.panel }
        rule.waitForIdle()
        return session
    }

    private fun bundleOf(sheet: SheetUuid): ProjectBundle {
        val project = ProjectFactory.create("Kitchen", "proj-abc123", ProjectFactory.newUuid(), 1L)
        val width = SketchDimension("d1", project.uuid, "width", "length", Rational(32), null, "user")
        return ProjectBundle(project, listOf(SketchSheet(sheet, project.uuid, "plan", "top", "in", 16)), emptyList(), listOf(width))
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix-$name.png")

    @Test
    fun phone_empty() {
        mount()
        shot("phone-1-empty")
        rule.onNodeWithTag(ShellTags.OVERFLOW).assertExists()
        rule.onNodeWithTag(StripTags.tool(ToolId.Select)).assertExists()
    }

    @Test
    fun phone_selected() {
        mount(Scenario(elements = listOf(box), select = listOf("r1")))
        shot("phone-2-selected")
    }

    @Test
    fun phone_menu() {
        mount(Scenario(elements = listOf(box), select = listOf("r1")))
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        shot("phone-3-menu")
    }

    @Test
    fun phone_panel() {
        mount(Scenario(elements = listOf(box), select = listOf("r1"), panel = PanelTab.Style))
        shot("phone-4-panel")
    }

    @Test
    fun phone_longTitle() {
        mount(Scenario(title = "Kitchen cabinet elevation, north wall, revision 3"))
        shot("phone-5-longtitle")
    }

    @Test
    fun phone_fontScale() {
        mount(Scenario(fontScale = 1.5f, elements = listOf(box), select = listOf("r1")))
        shot("phone-6-fontscale")
    }

    @Test
    fun phone_wide() {
        mount(Scenario(qualifiers = "w412dp-h915dp-xxhdpi", elements = listOf(box), select = listOf("r1")))
        shot("phone-7-wide")
    }

    @Test
    fun phone_fontScale2() {
        mount(Scenario(fontScale = 2f, elements = listOf(box), select = listOf("r1")))
        shot("phone-10-fontscale2")
    }

    @Test
    fun phone_narrow320() {
        mount(Scenario(qualifiers = "w320dp-h640dp-xhdpi", elements = listOf(box), select = listOf("r1")))
        shot("phone-11-narrow320")
    }

    @Test
    fun phone_landscape() {
        mount(Scenario(qualifiers = "w780dp-h360dp-xxhdpi", elements = listOf(box), select = listOf("r1")))
        shot("phone-12-landscape")
    }

    @Test
    fun phone_dark() {
        mount(Scenario(qualifiers = "w360dp-h780dp-night-xxhdpi", elements = listOf(box), select = listOf("r1"), dark = true))
        shot("phone-13-dark")
    }

    @Test
    fun phone_selectedLine_panelOpen() {
        mount(Scenario(elements = listOf(line), select = listOf("l1"), panel = PanelTab.Style))
        shot("phone-14-line-panel")
    }

    @Test
    fun phone_technicalShape_panelOpen() {
        mount(Scenario(technical = true, elements = listOf(box), select = listOf("r1"), panel = PanelTab.Style))
        shot("phone-15-technical-panel")
    }

    @Test
    fun phone_technicalTab() {
        mount(Scenario(technical = true, panel = PanelTab.Technical))
        shot("phone-16-technical-tab")
        rule.onNodeWithTag(TechnicalDataTags.PANEL).assertExists()
    }

    @Test
    fun phone_smallShape_zoomChip() {
        mount(Scenario(elements = listOf(filledRectEl("r1", 40, 40, 40, 30)), select = listOf("r1")))
        shot("phone-17-small-shape")
        rule.onNodeWithTag("handles-hint").assertExists()
    }

    @Test
    fun phone_gridAndSnapOn() {
        mount(Scenario(elements = listOf(box), select = listOf("r1")))
        rule.onNodeWithTag("sketch.grid").performClick()
        rule.onNodeWithTag("sketch.snap").performClick()
        rule.waitForIdle()
        shot("phone-18-grid-snap-on")
    }

    @Test
    fun phone_longPressPopups() {
        mount(Scenario(elements = listOf(box), select = listOf("r1")))
        val popups = listOf("select" to StripTags.tool(ToolId.Select), "delete" to StripTags.DELETE, "snap" to "sketch.snap")
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
        mount(Scenario(qualifiers = "w1280dp-h800dp-mdpi", elements = listOf(box), select = listOf("r1"), panel = PanelTab.Style, touch = false))
        shot("tablet-8-rail")
    }

    private companion object {
        const val PHONE = "w360dp-h780dp-xxhdpi"
        const val ICONS_PER_ROW = 5
        val ICON_CELL = 64.dp
    }
}
