// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only (the Robolectric twin in androidUnitTest cannot lay out a text field inside a dialog window, and only JVM tests feed the coverage gate)

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.units.LengthFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlineDialogsDesktopTest {
    @get:Rule
    val rule = createComposeRule()

    private fun SemanticsNodeInteraction.setText(text: String) = performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }

    private fun filledRectEl(id: String, x: Int, y: Int, w: Int, h: Int) =
        RectElement(ElementBase(ElementId(id)), SceneUnit.ofInt(x), SceneUnit.ofInt(y), SceneUnit.ofInt(w), SceneUnit.ofInt(h))

    private fun controller(): SketchEditorController {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, FixedAdvanceTextMetricsProvider()) }).session
        return opened.controller
    }

    @Test
    fun outlineHost_should_SelectTheTappedRow_AndCloseTheOverlay() {
        val c = controller()
        c.submit(AddElement(filledRectEl("low", 0, 400, 100, 50)))
        c.submit(AddElement(filledRectEl("top", 0, 0, 100, 50)))
        c.overlay = EditorOverlay.Outline
        rule.setContent { MaterialTheme { OutlineHost(c) } }
        rule.onNodeWithTag(OutlineTags.row(1)).performClick()
        rule.waitForIdle()
        assertEquals(setOf("top"), c.ui.selection)
        assertNull(c.overlay)
    }

    @Test
    fun outlineDialog_should_RenameThroughTheCustomAction_AndReportARefusal() {
        val c = controller()
        c.submit(AddElement(filledRectEl("a", 0, 0, 100, 50)))
        val entries = SketchOutlineModel.build(c.doc.scene, emptySet())
        var saved: String? = null
        rule.setContent {
            MaterialTheme {
                SketchOutlineDialog(entries, onSelect = {}, onRename = { _, text -> saved = text; if (text == "dup") "Name taken" else null }, onDismiss = {}, autoFocus = false)
            }
        }
        val action = rule.onNodeWithTag(OutlineTags.row(1)).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Rename" }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
        rule.onNodeWithTag(OutlineTags.RENAME_FIELD).setText("dup")
        rule.onNodeWithTag(OutlineTags.RENAME_SAVE).performClick()
        rule.waitForIdle()
        assertEquals("dup", saved)
        rule.onNodeWithTag(OutlineTags.RENAME_FIELD).assertExists()
        rule.onNodeWithTag(OutlineTags.RENAME_FIELD).setText("Kitchen")
        rule.onNodeWithTag(OutlineTags.RENAME_SAVE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(OutlineTags.RENAME_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(OutlineTags.CLOSE).assertExists()
    }

    @Test
    fun outlineDialog_should_HideRename_When_TheSheetIsReadOnly() {
        val c = controller()
        c.submit(AddElement(filledRectEl("a", 0, 0, 100, 50)))
        val entries = SketchOutlineModel.build(c.doc.scene, emptySet())
        rule.setContent { MaterialTheme { SketchOutlineDialog(entries, onSelect = {}, onRename = { _, _ -> null }, onDismiss = {}, canRename = false) } }
        val labels = rule.onNodeWithTag(OutlineTags.row(1)).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Select"), labels)
    }

    @Test
    fun addShapeHost_should_AddTheTypedShape_AndRefuseABadField() {
        val c = controller()
        c.overlay = EditorOverlay.AddShapeAt
        rule.setContent { MaterialTheme { AddShapeAtHost(c, LengthFormat.FEET_INCHES) } }
        rule.onNodeWithTag(ShapeAtTags.kind(ShapeAtKind.Rectangle)).performClick()
        rule.onNodeWithTag(ShapeAtTags.kind(ShapeAtKind.Ellipse)).performClick()
        rule.onNodeWithTag(ShapeAtTags.kind(ShapeAtKind.Rectangle)).performClick()
        for ((field, value) in listOf(ShapeAtField.X to "10", ShapeAtField.Y to "20", ShapeAtField.Width to "0", ShapeAtField.Height to "40")) {
            rule.onNodeWithTag(ShapeAtTags.field(field)).setText(value)
        }
        rule.onNodeWithTag(ShapeAtTags.CONFIRM).performClick()
        rule.waitForIdle()
        assertEquals(EditorOverlay.AddShapeAt, c.overlay)
        rule.onNodeWithTag(ShapeAtTags.field(ShapeAtField.Width)).setText("5")
        rule.onNodeWithTag(ShapeAtTags.CONFIRM).performClick()
        rule.waitForIdle()
        assertNull(c.overlay)
        val added = c.doc.scene.elementsInOrder.filterIsInstance<RectElement>().single()
        assertEquals(listOf(10.0, 20.0, 5.0, 40.0), listOf(added.x.value, added.y.value, added.width.value, added.height.value))
        assertTrue(c.ui.selection.contains(added.id.value))
    }

    @Test
    fun addShapeDialog_should_CancelWithoutAdding() {
        var dismissed = false
        rule.setContent {
            MaterialTheme {
                AddShapeAtDialog(
                    technical = true, format = LengthFormat.FEET_INCHES,
                    plan = { ShapeAtModel.plan(it, true, LengthFormat.FEET_INCHES, ElementId("n"), ElementStyle()) },
                    onAdd = { error("not added") }, onDismiss = { dismissed = true }, autoFocus = false,
                )
            }
        }
        rule.onNodeWithTag(ShapeAtTags.CANCEL).performClick()
        assertTrue(dismissed)
    }
}
