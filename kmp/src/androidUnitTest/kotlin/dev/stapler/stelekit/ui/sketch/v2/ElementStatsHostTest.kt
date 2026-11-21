// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
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

/** The element size section of the Style tab in the real host: shown for one selected element, and typing a value moves it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h900dp-mdpi")
class ElementStatsHostTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mount(): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        rule.runOnUiThread { session.controller.submit(AddElement(filledRectEl("r1", 0, 0, 100, 50))) }
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session
    }

    @Test
    fun sizeSection_should_AppearForOneSelectedElement_AndMoveItWhenAValueIsTyped() {
        val session = mount()
        rule.runOnUiThread { session.controller.tools.select(listOf(ElementId("r1"))) }
        rule.waitForIdle()

        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performTextClearance()
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performTextInput("40")
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").performImeAction()
        rule.waitForIdle()

        assertEquals(40.0, (session.controller.doc.scene[ElementId("r1")] as RectElement).x.value)
    }

    @Test
    fun sizeSection_should_ShowLockedInsteadOfFields_When_TheElementIsLocked() {
        val session = mount()
        rule.runOnUiThread {
            session.controller.submit(dev.stapler.stelekit.sketch.v2.commands.LockElements(listOf(ElementId("r1")), true))
            session.controller.tools.select(listOf(ElementId("r1")))
        }
        rule.waitForIdle()

        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "locked").assertExists()
        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").assertDoesNotExist()
    }

    @Test
    fun sizeSection_should_BeAbsent_When_NothingIsSelected() {
        mount()

        rule.onNodeWithTag(StylePanelTags.STATS_PREFIX + "x").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() = assertDoesNotExist()
}
