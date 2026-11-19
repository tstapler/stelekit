// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.platform.PickedImportFile
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.commands.AlignEdge
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.ConstrainMenuItem
import dev.stapler.stelekit.ui.sketch.v2.DimensionEditorTags
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.ExportTags
import dev.stapler.stelekit.ui.sketch.v2.ExtraPanelTags
import dev.stapler.stelekit.ui.sketch.v2.HostPlatform
import dev.stapler.stelekit.ui.sketch.v2.HostTags
import dev.stapler.stelekit.ui.sketch.v2.ImportTags
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.PanelTab
import dev.stapler.stelekit.ui.sketch.v2.PickerTags
import dev.stapler.stelekit.ui.sketch.v2.SelectionAction
import dev.stapler.stelekit.ui.sketch.v2.SelectionMenuTags
import dev.stapler.stelekit.ui.sketch.v2.ShellTags
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorV2Host
import dev.stapler.stelekit.ui.sketch.v2.V2EditorDeps
import dev.stapler.stelekit.ui.sketch.v2.WindowDp
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.0d: the host over a real sheet draws two shapes, adds a constraint through the Constrain menu and the target picker,
 * sets its dimension in the label editor (one solve through `SolveRunner`), exports SVG through the dialog and imports a real
 * `.excalidraw` fixture. Robolectric. Gradle-only: composes @Composable lambdas and needs the commonTest rig (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class SketchEditorHostExtrasTest {
    @get:Rule
    val rule = createComposeRule()

    private class RecordingFiles : SketchFilePort {
        override val canPick: Boolean = true
        val picks = ArrayDeque<PickedImportFile>()
        val saved = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun pickImportFile(title: String, extensions: List<String>): PickedImportFile? = picks.removeFirstOrNull()

        override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): SketchSaveOutcome {
            saved += suggestedName to bytes
            return SketchSaveOutcome.Saved(suggestedName, folder = null, path = null)
        }
    }

    private fun fixture(): ByteArray {
        val relative = "src/commonTest/resources/excalidraw/shapes-text.excalidraw"
        val file = listOf(File(relative), File("kmp/$relative")).firstOrNull { it.isFile } ?: error("fixture not found from ${File(".").absolutePath}")
        return file.readBytes()
    }

    private fun drag(from: Offset, to: Offset) {
        rule.onNodeWithTag(HostTags.CANVAS_INPUT).performMouseInput {
            moveTo(from)
            press()
            moveTo(Offset((from.x + to.x) / 2, (from.y + to.y) / 2))
            moveTo(to)
            release()
        }
        rule.waitForIdle()
    }

    @Test
    fun drawConstrainDimensionExportAndImport_should_RunThroughTheHost() {
        val files = RecordingFiles().also { it.picks += PickedImportFile("shapes-text.excalidraw", ImportSource.ofBytes(fixture())) }
        val session = mount(files)
        val controller = session.controller

        // Two shapes by mouse drag.
        rule.runOnUiThread { controller.tools.chooseTool(ToolId.Rectangle) }
        drag(Offset(20f, 40f), Offset(100f, 100f))
        rule.runOnUiThread { controller.tools.chooseTool(ToolId.Rectangle) }
        drag(Offset(160f, 140f), Offset(240f, 200f))
        val rects = controller.doc.scene.elementsInOrder.filterIsInstance<RectElement>()
        assertEquals(2, rects.size, "two rectangles were drawn")

        // A dimension through the Constrain menu (the K overlay) and the target picker: Distance on one rectangle's edge.
        rule.runOnUiThread { controller.tools.select(listOf(rects[0].id)) }
        rule.runOnUiThread { controller.overlay = dev.stapler.stelekit.ui.sketch.v2.EditorOverlay.ConstrainMenu }
        rule.waitForIdle()
        rule.onNodeWithTag(ExtraPanelTags.constrain(ConstrainMenuItem.Distance)).performClick()
        rule.waitForIdle()
        val edge = controller.constraints.candidates().firstOrNull { it.target.part is dev.stapler.stelekit.sketch.v2.constraints.TargetPart.Edge }
            ?: error("no edge candidate: armed=${controller.constraints.picker}, all=${controller.constraints.candidates().map { it.key }}")
        rule.onNodeWithTag(PickerTags.marker(edge.key)).performClick()
        rule.waitForIdle()

        // The dimension is not committed yet: its label editor is open with the measured value.
        assertTrue(controller.doc.constraints.isEmpty(), "the picker opens the label editor before anything is committed")
        rule.onNodeWithTag(DimensionEditorTags.ROOT).assertExistsOnScreen()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performTextClearance()
        rule.onNodeWithTag(DimensionEditorTags.FIELD).performTextInput("50")
        rule.onNodeWithTag(DimensionEditorTags.SET).performClick()
        rule.waitUntil(TIMEOUT_MS) { controller.doc.constraints.isNotEmpty() }
        val dimension = controller.doc.constraints.single()
        assertEquals(ConstraintKind.Distance, dimension.kind)
        assertEquals(50.0, dimension.value)

        // SVG export through the dialog.
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_EXPORT).performClick()
        rule.onNodeWithTag(ExportTags.format(ExportFormat.Svg)).performClick()
        rule.onNodeWithTag(ExportTags.EXPORT).performClick()
        rule.waitUntil(TIMEOUT_MS) { files.saved.isNotEmpty() }
        val (name, bytes) = files.saved.single()
        assertTrue(name.endsWith(".svg"), "saved $name")
        assertTrue(bytes.decodeToString().contains("<svg"), "the file is an SVG")

        // Import of a real .excalidraw fixture.
        val before = controller.doc.scene.order.size
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_IMPORT).performClick()
        rule.waitUntil(TIMEOUT_MS) { controller.doc.scene.order.size > before }
        rule.waitForIdle()
        rule.onNodeWithTag(ImportTags.DONE).assertExistsOnScreen()
        assertTrue(controller.doc.scene.order.size > before, "the fixture's elements were added")
        session.close()
    }

    private fun mount(files: RecordingFiles, window: WindowDp? = null): dev.stapler.stelekit.ui.sketch.v2.SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val deps = V2EditorDeps(
            repos = rig.repos,
            store = { rig.store },
            leases = null,
            settings = rig.deps.settings,
            graphId = { V2EditorRig.GRAPH },
            platform = HostPlatform(files = files, fileSystem = FakeFileSystem(), notesDirectory = { "/graph" }),
        )
        val metrics = FixedAdvanceTextMetricsProvider()
        val session = (runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) } as EditorOpening.Editor).session
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}, window = window) }
        return session
    }

    @Test
    fun autoConstrainOnDraw_should_CommitTheLineWithItsInferredConstraintThroughTheSolver() {
        val session = mount(RecordingFiles())
        val controller = session.controller
        rule.runOnUiThread { session.extras!!.autoConstrain.toggle() }

        rule.runOnUiThread { controller.tools.chooseTool(ToolId.Line) }
        drag(Offset(20f, 40f), Offset(200f, 41f))
        rule.waitUntil(TIMEOUT_MS) { controller.doc.constraints.isNotEmpty() }

        assertEquals(1, controller.doc.scene.order.size)
        assertEquals(ConstraintKind.Horizontal, controller.doc.constraints.single().kind)
        session.close()
    }

    @Test
    fun alignPanelLibraryTabGridToggleAndClipboardKeys_should_WorkThroughTheirSurfaces() {
        val session = mount(RecordingFiles(), WindowDp(WIDE_DP, TALL_DP))
        val controller = session.controller
        rule.runOnUiThread { controller.tools.chooseTool(ToolId.Rectangle) }
        drag(Offset(20f, 40f), Offset(80f, 90f))
        rule.runOnUiThread { controller.tools.chooseTool(ToolId.Rectangle) }
        drag(Offset(140f, 150f), Offset(200f, 200f))
        val rects = controller.doc.scene.elementsInOrder.filterIsInstance<RectElement>()

        // Align left through the selection menu's panel: both rectangles end on one x.
        rule.runOnUiThread { controller.tools.select(rects.map { it.id }) }
        rule.waitForIdle()
        rule.onNodeWithTag(SelectionMenuTags.BUTTON).performClick()
        rule.onNodeWithTag(SelectionMenuTags.item(SelectionAction.Align)).performClick()
        rule.onNodeWithTag(ExtraPanelTags.align(AlignEdge.Left)).performClick()
        rule.waitForIdle()
        assertEquals(1, controller.doc.scene.elementsInOrder.filterIsInstance<RectElement>().map { it.x.value }.toSet().size, "both share the left edge")

        // Library tab: save the selection, then insert it.
        rule.runOnUiThread { controller.ui.panelTab = PanelTab.Library }
        rule.waitForIdle()
        rule.onNodeWithTag(ExtraPanelTags.LIBRARY_SAVE).performClick()
        rule.waitForIdle()
        val before = controller.doc.scene.order.size
        rule.onNodeWithTag("sketch.host.library.insert." + session.extras!!.library.items.single().id).performClick()
        rule.waitForIdle()
        assertTrue(controller.doc.scene.order.size > before, "the library item inserted")

        // The view cluster's grid button is the host's toggle.
        rule.onNodeWithTag("sketch.grid").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(session.extras!!.grid, "grid toggled by the view cluster button; extras=${session.extras}")

        // Copy then paste through the clipboard keys the canvas owns.
        var clipboardText: String? = null
        rule.runOnUiThread {
            session.extras!!.clipboard.port = dev.stapler.stelekit.ui.sketch.v2.ClipboardPort({ clipboardText }, { clipboardText = it })
            controller.keys.run(dev.stapler.stelekit.ui.sketch.v2.KeyAction.Copy)
            controller.keys.run(dev.stapler.stelekit.ui.sketch.v2.KeyAction.Paste)
        }
        assertTrue(clipboardText.orEmpty().isNotEmpty(), "copy wrote the sketch payload")
        session.close()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsOnScreen() = assertExists()

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val WIDE_DP = 900
        const val TALL_DP = 400
    }
}
