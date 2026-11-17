// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.DroppedCanvasFile
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.W6Kit
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import as Strings
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 5.1.5 (UX-50, UX-51, UX-53, UX-54, UX-56, UX-135): the import dialogs over the real controller and a real `HistoryV2`.
 * Robolectric. Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class ImportFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val notices = mutableListOf<SketchNotice>()
    private val copied = mutableListOf<String>()

    private fun controller(
        port: FakeImportSheetPort,
        files: FakeSketchFiles = FakeSketchFiles(),
        storage: V2Storage = V2Storage.Available,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ) = ExcalidrawImportController(
        port = port,
        files = files,
        images = ImportImageSink { ImageAssetStore(FakeFileSystem(), "/graph/assets").store(it) },
        storage = { storage },
        postNotice = { notices += it },
        ids = W6Kit.SeqIds("p"),
        dispatcher = dispatcher,
    )

    private fun show(controller: ExcalidrawImportController, canCreateSheet: Boolean = true) {
        rule.setContent { ExcalidrawImportDialogs(controller, onCopyDetails = { copied += it }, canCreateSheet = canCreateSheet) }
        rule.waitForIdle()
    }

    private fun drop(controller: ExcalidrawImportController, text: String) {
        controller.importDropped(DroppedCanvasFile("architecture.excalidraw", ImportSource.ofBytes(text.encodeToByteArray())))
        rule.waitForIdle()
    }

    private fun file(count: Int, version: Int = 2): String =
        ExcalidrawTestData.scene((1..count).map { element("rectangle", "e$it", it * 3, it * 3) }).replace("\"version\":2", "\"version\":$version")

    @Test
    fun progressDialog_should_NameTheFile_AndCancelLeavesTheSheetUnchanged() {
        val port = FakeImportSheetPort()
        val c = controller(port, dispatcher = StandardTestDispatcher())
        show(c)
        drop(c, file(3))

        rule.onNodeWithTag(ImportTags.PROGRESS).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.PROGRESS_CANCEL).assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(ImportTags.NOTICE).assertIsDisplayed()
        assertEquals(0, port.document().scene.size, "cancel leaves the sheet unchanged (UX-54)")
        assertEquals(ImportUiState.Notice(Strings.CANCELLED), c.state.value)
    }

    @Test
    fun summaryDialog_should_OfferCopyDetailsUndoImportAndDone() {
        val port = FakeImportSheetPort()
        val c = controller(port)
        show(c)
        drop(c, file(41))

        rule.onNodeWithTag(ImportTags.SUMMARY).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.COPY_DETAILS).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.UNDO_IMPORT).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.DONE).assertIsDisplayed()
        assertEquals(41, port.document().scene.size)

        rule.onNodeWithTag(ImportTags.COPY_DETAILS).performClick()
        assertEquals(1, copied.size)
        assertTrue(copied.single().startsWith("imported=41 total=41"), copied.single())
    }

    @Test
    fun undoImport_should_RestoreTheSheet_CloseTheDialog_AndSayImportUndone() {
        val port = FakeImportSheetPort()
        val c = controller(port)
        show(c)
        drop(c, file(41))

        rule.onNodeWithTag(ImportTags.UNDO_IMPORT).performClick()
        rule.waitForIdle()

        assertEquals(0, port.document().scene.size)
        rule.onNodeWithTag(ImportTags.SUMMARY).assertDoesNotExist()
        assertEquals(Strings.UNDONE_SUMMARY, notices.last().text)
    }

    @Test
    fun undoImport_should_BeHiddenWhenReopenedAfterAnotherEdit_AndDoneRemains() {
        val port = FakeImportSheetPort()
        val c = controller(port)
        show(c)
        drop(c, file(2))
        rule.onNodeWithTag(ImportTags.DONE).performClick()
        port.commit(ImportScene(listOf(W6Kit.rect("later", 900, 900))))

        c.reopenSummary()
        rule.waitForIdle()

        rule.onNodeWithTag(ImportTags.UNDO_IMPORT).assertDoesNotExist()
        rule.onNodeWithTag(ImportTags.DONE).assertIsDisplayed()
    }

    @Test
    fun overCapDialog_should_OfferThreeWorkingChoices() {
        val port = FakeImportSheetPort.holding(100) { W6Kit.rect("old$it", it, it) }
        val c = controller(port)
        show(c)
        drop(c, file(OVER_CAP_FILE))

        rule.onNodeWithTag(ImportTags.OVER_CAP_TEXT).assertTextEquals("This drawing has 7,200 elements; the limit is 5,000.")
        rule.onNodeWithTag(ImportTags.IMPORT_FIRST).assertTextEquals("Import first 5,000").assertIsEnabled()
        rule.onNodeWithTag(ImportTags.IMPORT_NEW_SKETCH).assertTextEquals("Import into a new sketch").assertIsEnabled()
        rule.onNodeWithTag(ImportTags.CONFIRM_CANCEL).assertTextEquals("Cancel")

        rule.onNodeWithTag(ImportTags.IMPORT_FIRST).performClick()
        rule.waitForIdle()
        assertEquals(LIMIT, port.document().scene.size)
        rule.onNode(hasText("Imported ${LIMIT - 100} of $OVER_CAP_FILE", substring = true)).assertIsDisplayed()
    }

    @Test
    fun overCapDialog_should_DisableNewSketchWithAReason_WhereNoSheetCanBeAdded() {
        val port = FakeImportSheetPort.holding(100) { W6Kit.rect("old$it", it, it) }
        val c = controller(port)
        show(c, canCreateSheet = false)
        drop(c, file(OVER_CAP_FILE))

        rule.onNodeWithTag(ImportTags.IMPORT_NEW_SKETCH).assertIsNotEnabled()
        rule.onNode(hasText(Strings.OVER_CAP_NEW_SKETCH_UNAVAILABLE)).assertIsDisplayed()
    }

    @Test
    fun newerSchemaFile_should_ShowTheNoticeWithImportAndCancelBeforeCommit() {
        val port = FakeImportSheetPort()
        val c = controller(port)
        show(c)
        drop(c, file(2, version = 3))

        rule.onNodeWithTag(ImportTags.NEWER_NOTICE).assertTextEquals(Strings.NEWER_SCHEMA)
        assertEquals(0, port.document().scene.size, "nothing is added before the choice")
        rule.onNodeWithTag(ImportTags.CONFIRM_CANCEL).performClick()
        assertEquals(0, port.document().scene.size)
    }

    @Test
    fun notExcalidraw_should_ShowReasonCode_WithChooseAnotherFileAndClose() {
        val port = FakeImportSheetPort()
        val c = controller(port)
        show(c)
        drop(c, "not json")

        rule.onNodeWithTag(ImportTags.REFUSED).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.REFUSED_CODE).assertTextContains("Reason: ", substring = true)
        rule.onNodeWithTag(ImportTags.CHOOSE_ANOTHER).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.CLOSE).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(ImportUiState.Idle, c.state.value)
        assertEquals(0, port.document().scene.size)
    }

    @Test
    fun noFileStorage_should_ShowTheReasonForPickDropAndPaste_AndCreateNothing() {
        val storage = V2Storage.PlatformUnsupported
        val port = FakeImportSheetPort()
        val files = FakeSketchFiles()
        val c = controller(port, files, storage)
        show(c)

        c.chooseFile()
        rule.waitForIdle()
        rule.onNodeWithTag(ImportTags.UNAVAILABLE).assertIsDisplayed()
        rule.onNode(hasText(SketchReasonText.v2StorageUnavailable(storage))).assertIsDisplayed()
        rule.onNodeWithTag(ImportTags.CLOSE).performClick()

        drop(c, file(2))
        rule.onNodeWithTag(ImportTags.UNAVAILABLE).assertIsDisplayed()
        assertEquals(0, files.pickCalls)
        assertEquals(0, port.document().scene.size)
        assertEquals(0, port.history.undoDepth)
    }

    @Test
    @Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
    fun overflowImportEntry_should_BeDisabledWithItsReasonShown_WhereNoFileStorageExists() {
        val reason = SketchReasonText.v2StorageUnavailable(V2Storage.NoStore)
        val config = SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly, onImport = {}, importDisabledReason = reason, onExport = {})
        rule.setContent { SketchScreenV2(SketchEditorState(), config, window = WindowDp(1280, 800)) }
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(ShellTags.OVERFLOW_IMPORT).assertIsNotEnabled()
        rule.onNode(hasText(reason)).assertIsDisplayed()
        rule.onNodeWithTag(ShellTags.OVERFLOW_EXPORT).assertIsEnabled()
    }

    @Test
    @Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
    fun overflowImportEntry_should_RunTheImportFlow_WhenEnabled() {
        var opened = 0
        val config = SketchShellConfig(ToolStripModel(true), PointerClass.MouseOnly, onImport = { opened += 1 })
        rule.setContent { SketchScreenV2(SketchEditorState(), config, window = WindowDp(1280, 800)) }
        rule.onNodeWithTag(ShellTags.OVERFLOW).performClick()
        rule.onNodeWithTag(ShellTags.OVERFLOW_IMPORT).assertIsEnabled().performClick()
        rule.waitForIdle()

        assertEquals(1, opened)
    }

    private companion object {
        const val OVER_CAP_FILE = 7_200
        const val LIMIT = 5_000
    }
}
