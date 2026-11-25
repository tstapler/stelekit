// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.sheetName
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Naming a sheet on a phone: the header name and the overflow menu open the rename dialog, Save stores the name in the sheet file
 * and the header shows it. Roborazzi shots go to build/outputs/roborazzi when recording; every assertion is on state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class SheetRenamePhoneTest {
    @get:Rule
    val rule = createComposeRule()

    private class Opened(val rig: V2EditorRig, val session: SketchEditorSession, val metrics: FixedAdvanceTextMetricsProvider)

    private fun open(): Opened {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        return Opened(rig, SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet), metrics)
    }

    @Test
    fun headerName_should_OpenTheRenameDialog_AndOverflowShouldOfferRename() {
        val o = open()
        rule.setContent { SketchEditorV2Host(o.session, ModernPreviewEnvironment(o.metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        val name = rule.onNodeWithTag(TopBarTags.NAME).fetchSemanticsNode()
        assertEquals("Kitchen", name.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single(), "an unnamed sheet shows the sketch's name")
        assertTrue(name.size.height >= with(rule.density) { 48.dp.roundToPx() }, "the name is a 48 dp target, got ${name.size}")
        rule.onNodeWithTag(TopBarTags.NAME).performClick()
        assertEquals(EditorOverlay.RenameSheet, o.session.controller.overlay)

        o.session.controller.overlay = null
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RenameSheetTags.OVERFLOW).performClick()
        assertEquals(EditorOverlay.RenameSheet, o.session.controller.overlay)
    }

    @Test
    fun dialog_should_RefuseBlankAndOverlong_ThenSaveTheNameToTheSheetFile() {
        val o = open()
        val c = o.session.controller
        c.overlay = EditorOverlay.RenameSheet
        // The field's blinking cursor keeps a Robolectric test from idling inside a Dialog window, so the card is composed directly.
        rule.setContent { RenameSheetDialogContent(c, "Kitchen", autoFocus = false) }
        rule.waitForIdle()
        // An unnamed sheet starts with an empty field (the sketch's name is only a placeholder), so Save cannot copy it.
        rule.onNodeWithTag(RenameSheetTags.SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(RenameSheetTags.DIALOG).captureRoboImage("build/outputs/roborazzi/rename-0-empty.png")
        rule.onNodeWithTag(RenameSheetTags.FIELD).performTextInput("   ")
        rule.onNodeWithTag(RenameSheetTags.SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(RenameSheetTags.FIELD).performTextClearance()
        rule.onNodeWithTag(RenameSheetTags.FIELD).performTextInput("x".repeat(c.doc.scene.limits.maxNameChars + 1))
        rule.onNodeWithTag(RenameSheetTags.ERROR).assertExists()
        rule.onNodeWithTag(RenameSheetTags.SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(RenameSheetTags.DIALOG).captureRoboImage("build/outputs/roborazzi/rename-1-too-long.png")
        assertNull(c.sheetName)

        rule.onNodeWithTag(RenameSheetTags.FIELD).performTextClearance()
        rule.onNodeWithTag(RenameSheetTags.FIELD).performTextInput("  Upstairs bath ")
        rule.onNodeWithTag(RenameSheetTags.SAVE).assertIsEnabled()
        rule.onNodeWithTag(RenameSheetTags.DIALOG).captureRoboImage("build/outputs/roborazzi/rename-2-valid.png")
        rule.onNodeWithTag(RenameSheetTags.SAVE).performClick()
        rule.waitForIdle()
        assertNull(c.overlay)
        assertEquals("Upstairs bath", c.sheetName)

        runBlocking { c.flush() }
        val bytes = assertNotNull(runBlocking { o.rig.store.readV2SheetFile(o.rig.slug, o.rig.sheet.value) })
        assertEquals("Upstairs bath", SheetCodecV2.decode(bytes).getOrNull()?.sheetName)
    }

    @Test
    fun cancel_should_LeaveTheNameUnchanged() {
        val o = open()
        val c = o.session.controller
        c.overlay = EditorOverlay.RenameSheet
        rule.setContent { RenameSheetDialogContent(c, "Kitchen", autoFocus = false) }
        rule.waitForIdle()
        rule.onNodeWithTag(RenameSheetTags.FIELD).performTextInput(" more")
        rule.onNodeWithTag(RenameSheetTags.CANCEL).performClick()
        rule.waitForIdle()
        assertNull(c.overlay)
        assertNull(c.sheetName)
    }

    @Test
    fun header_should_ShowTheNewName_AfterARename() {
        val o = open()
        rule.setContent { SketchEditorV2Host(o.session, ModernPreviewEnvironment(o.metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        o.session.controller.renameSheet("Basement")
        rule.waitForIdle()
        val name = rule.onNodeWithTag(TopBarTags.NAME).fetchSemanticsNode()
        assertEquals("Basement", name.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single())
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/rename-3-header.png")
    }
}
