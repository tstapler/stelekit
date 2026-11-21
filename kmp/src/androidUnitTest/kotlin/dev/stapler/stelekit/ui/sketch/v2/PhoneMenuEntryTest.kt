// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** On a phone, the three-dot menu is the only way to open the panels and to turn a sheet into a technical one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class PhoneMenuEntryTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeSource(val bundle: ProjectBundle) : TechnicalDataSource {
        override suspend fun load(): ProjectBundle = bundle
        override suspend fun save(bundle: ProjectBundle): Boolean = true
    }

    private fun mount(kind: SheetKind) {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val project = ProjectFactory.create("Kitchen", "proj-abc123", ProjectFactory.newUuid(), 1L)
        val width = SketchDimension("d1", project.uuid, "width", "length", Rational(32), null, "user")
        val bundle = ProjectBundle(project, listOf(SketchSheet(sheet, project.uuid, "plan", "top", "in", 16)), emptyList(), listOf(width))
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, HostServices(technical = FakeSource(bundle)))
        session.controller.setSheetKind(kind)
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
    }

    @Test
    fun menu_should_TurnADiagramIntoATechnicalSheet_And_OpenTheTechnicalTab() {
        mount(SheetKind.Diagram)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/phone-1-start.png")

        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.OVERFLOW_PANEL_STYLE).assertExists()
        rule.onNodeWithTag(ShellTags.OVERFLOW_MAKE_TECHNICAL).assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/phone-2-menu.png")

        rule.onNodeWithTag(ShellTags.OVERFLOW_MAKE_TECHNICAL).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.tab(PanelTab.Technical)).assertExists()
        rule.onNodeWithTag(TechnicalDataTags.PANEL).assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/phone-3-technical.png")
    }

    @Test
    fun menu_should_OpenTheTechnicalTab_When_TheSheetIsAlreadyTechnical() {
        mount(SheetKind.Technical)
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.OVERFLOW_MAKE_DIAGRAM).assertExists()
        rule.onNodeWithTag(ShellTags.OVERFLOW_PANEL_TECHNICAL).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TechnicalDataTags.PANEL).assertExists()
    }
}
