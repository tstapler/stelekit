// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** The Technical tab of the real host: listed on a technical sheet only, showing the project's named dimensions, saving an edit through its source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h900dp-mdpi")
class TechnicalTabHostTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeSource(val bundle: ProjectBundle) : TechnicalDataSource {
        val saved = mutableListOf<ProjectBundle>()
        override suspend fun load(): ProjectBundle = bundle
        override suspend fun save(bundle: ProjectBundle): Boolean = saved.add(bundle)
    }

    private fun mount(kind: SheetKind): FakeSource {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val project = ProjectFactory.create("Kitchen", "proj-abc123", ProjectFactory.newUuid(), 1L)
        val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16)
        val width = SketchDimension("d1", project.uuid, "width", "length", Rational(32), null, "user")
        val source = FakeSource(ProjectBundle(project, listOf(row), emptyList(), listOf(width)))
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, HostServices(technical = source))
        session.controller.setSheetKind(kind)
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return source
    }

    @Test
    fun technicalTab_should_BeListedAndShowTheNamedDimensions_When_TheSheetIsTechnical() {
        mount(SheetKind.Technical)

        rule.onNodeWithTag(ShellTags.tab(PanelTab.Technical)).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TechnicalDataTags.PANEL).assertExists()
        rule.onNodeWithText("width", substring = true).assertExists()
    }

    @Test
    fun technicalTab_should_NotBeListed_When_TheSheetIsADiagram() {
        mount(SheetKind.Diagram)

        assertEquals(0, rule.onAllNodesWithTagCount(ShellTags.tab(PanelTab.Technical)))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, tag).let {
            onAllNodes(it).fetchSemanticsNodes().size
        }
}
