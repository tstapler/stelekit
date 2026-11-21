// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchChromeStrings
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

/**
 * A long press on an icon-only control shows its name with its shortcut and a one-line hint, exposes the name as the accessibility
 * label, and never triggers the control (so a hold does not fight a drag, a scroll or a tap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class ControlHelpPopupTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mount(): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, assertNotNull(opened.sheet), HostServices())
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session
    }

    private fun hold(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    @Test
    fun longPressOnSelect_should_ShowItsNameShortcutAndHint() {
        mount()
        hold(StripTags.tool(ToolId.Select))
        rule.onNodeWithTag(HelpTags.POPUP).assertContentDescriptionContains("Select (V)", substring = true)
        rule.onNodeWithTag(HelpTags.POPUP).assertContentDescriptionContains(SketchChromeStrings.toolHint(ToolId.Select), substring = true)
    }

    @Test
    fun longPressOnUndo_should_ExplainItInWords_And_NotUndoAnything() {
        val session = mount()
        rule.runOnUiThread { session.controller.submit(AddElement(filledRectEl("r1", 40, 40, 160, 90))) }
        rule.waitForIdle()
        hold(StripTags.UNDO)
        rule.onNodeWithTag(HelpTags.POPUP).assertContentDescriptionContains(SketchChromeStrings.UNDO_HINT, substring = true)
        assertNotNull(session.controller.doc.scene[ElementId("r1")], "a long press must not undo")
    }

    @Test
    fun longPressOnDelete_should_SayWhyItIsUnavailable_InsteadOfPromisingAnAction() {
        mount()
        hold(StripTags.DELETE)
        rule.onNodeWithTag(HelpTags.POPUP).assertContentDescriptionContains(SketchChromeStrings.DELETE_UNAVAILABLE, substring = true)
    }

    @Test
    fun longPressOnAGroup_should_OpenItsListAndNotChangeTheTool() {
        val session = mount()
        hold(StripTags.groupMain(StripGroup.Shapes))
        rule.onNodeWithTag(StripTags.groupItem(StripGroup.Shapes, ToolId.Diamond)).assertExists()
        assertEquals(ToolId.Select, session.controller.ui.activeTool)
    }

    @Test
    fun longPressOnAViewButton_should_ShowItsHint() {
        mount()
        hold("sketch.snap")
        rule.onNodeWithTag(HelpTags.POPUP).assertContentDescriptionContains(SketchChromeStrings.SNAP_HINT, substring = true)
    }

    @Test
    fun theAccessibilityLabel_should_BeTheNameAlone_SoTheHintIsNotReadOnEveryFocus() {
        mount()
        rule.onNodeWithTag(StripTags.UNDO).assertContentDescriptionEquals(SketchChromeStrings.UNDO)
        rule.onNodeWithTag(StripTags.tool(ToolId.Select)).assertContentDescriptionEquals("Select (V)")
    }
}
