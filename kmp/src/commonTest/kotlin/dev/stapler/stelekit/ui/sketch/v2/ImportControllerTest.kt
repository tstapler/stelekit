// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.left
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.platform.DroppedCanvasFile
import dev.stapler.stelekit.platform.PickedImportFile
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.W6Kit
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageStoreError
import dev.stapler.stelekit.sketch.v2.runtime.importing.OverCapChoice
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import as Strings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stories 5.1.5a to c (UX-50, UX-51, UX-53, UX-54, UX-56, UX-135): the import flow without a screen. The sheet is a real
 * `HistoryV2`, so "one undo step" and "undo import" are the engine's own, not a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportControllerTest {
    private class Harness(scope: TestScope, val port: FakeImportSheetPort = FakeImportSheetPort(), val files: FakeSketchFiles = FakeSketchFiles(), storage: V2Storage = V2Storage.Available) {
        val notices = mutableListOf<SketchNotice>()
        val fs = FakeFileSystem()
        private val assets = ImageAssetStore(fs, "/graph/assets")
        var storeFails = false
        val controller = ExcalidrawImportController(
            port = port,
            files = files,
            images = ImportImageSink { bytes -> if (storeFails) ImageStoreError.Unreadable.left() else assets.store(bytes) },
            storage = { storage },
            postNotice = { notices += it },
            ids = W6Kit.SeqIds("p"),
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        fun drop(text: String, name: String = "architecture.excalidraw") = controller.importDropped(DroppedCanvasFile(name, ImportSource.ofBytes(text.encodeToByteArray())))
    }

    private fun rects(count: Int) = (1..count).map { element("rectangle", "e$it", it * 3, it * 3, 10, 10) }

    private fun file(count: Int, version: Int = FILE_VERSION): String =
        ExcalidrawTestData.scene(rects(count)).replace("\"version\":$FILE_VERSION", "\"version\":$version")

    private fun fullSheet(count: Int): FakeImportSheetPort = FakeImportSheetPort.holding(count) { W6Kit.rect("old$it", it, it) }

    private fun TestScope.harness(port: FakeImportSheetPort = FakeImportSheetPort(), files: FakeSketchFiles = FakeSketchFiles(), storage: V2Storage = V2Storage.Available) = Harness(this, port, files, storage)

    @Test
    fun import_should_AddAllElementsAtTheViewportCentre_InOneUndoStep_AndSelectThem() = runTest {
        val h = harness()
        h.drop(file(3))
        advanceUntilIdle()

        val summary = assertIs<ImportUiState.Summary>(h.controller.state.value)
        assertEquals(Strings.imported(3, 3), summary.lines.first())
        assertEquals(3, h.port.document().scene.size)
        assertEquals(1, h.port.history.undoDepth, "one undo step (UX-50)")
        assertEquals(h.port.document().scene.order.toSet(), h.port.selected, "the imported group is selected")
        val bounds = h.port.document().scene.elementsInOrder.map { dev.stapler.stelekit.sketch.v2.index.ElementBounds.geometry(it) }
        assertEquals(FakeImportSheetPort.CENTRE_X, (bounds.minOf { it.minX } + bounds.maxOf { it.maxX }) / 2.0, 1e-9)
        assertEquals(FakeImportSheetPort.CENTRE_Y, (bounds.minOf { it.minY } + bounds.maxOf { it.maxY }) / 2.0, 1e-9)
        assertEquals(listOf(Strings.FINISHED_KEY), h.notices.map { it.key })
    }

    @Test
    fun summary_should_CountSkippedTypes_AndKeepUnsupportedAsGreyPlaceholders() = runTest {
        val h = harness()
        h.drop(ExcalidrawTestData.scene(listOf(element("rectangle", "r1"), element("stickynote", "s1"), element("embeddable", "m1"))))
        advanceUntilIdle()

        val summary = assertIs<ImportUiState.Summary>(h.controller.state.value)
        assertTrue(Strings.skipped(listOf("1 stickynote")) in summary.lines, summary.lines.toString())
        assertTrue(Strings.placeholders(listOf("1 embeddable")) in summary.lines, summary.lines.toString())
        assertEquals(2, h.port.document().scene.size, "the rectangle and the embeddable placeholder")
        assertTrue("UnknownElementType:stickynote 1" in summary.details.lines(), summary.details)
    }

    @Test
    fun undoImport_should_RestoreTheSheet_ClearTheSelection_AndSayImportUndone() = runTest {
        val h = harness()
        h.drop(file(41))
        advanceUntilIdle()
        assertEquals(41, h.port.document().scene.size)
        assertTrue(h.controller.canUndoImport())

        h.controller.undoImport()

        assertEquals(0, h.port.document().scene.size)
        assertEquals(emptySet(), h.port.selected)
        assertEquals(ImportUiState.Idle, h.controller.state.value)
        assertEquals(Strings.UNDONE_SUMMARY, h.notices.last().text)
    }

    @Test
    fun undoImport_should_BeHidden_When_AnotherEditFollowed_SoThereIsNoOutOfOrderUndo() = runTest {
        val h = harness()
        h.drop(file(2))
        advanceUntilIdle()
        h.port.commit(ImportScene(listOf(W6Kit.rect("later", 500, 500))))

        assertFalse(h.controller.canUndoImport())
        h.controller.reopenSummary()
        assertFalse(assertIs<ImportUiState.Summary>(h.controller.state.value).canUndo, "Undo import is hidden; Done remains")
        h.controller.undoImport()
        assertEquals(3, h.port.document().scene.size, "nothing was undone")
        assertEquals(2, h.port.history.undoDepth)
    }

    @Test
    fun overCap_should_OfferThreeChoices_And_ImportFirstAddsExactlyTheRoomLeft() = runTest {
        val h = harness(fullSheet(100))
        h.drop(file(OVER_CAP_FILE))
        advanceUntilIdle()

        val confirm = assertIs<ImportUiState.Confirm>(h.controller.state.value)
        assertEquals("This drawing has 7,200 elements; the limit is 5,000.", confirm.overCapText)
        assertEquals(100, h.port.document().scene.size, "nothing is added before the choice")

        h.controller.chooseOverCap(OverCapChoice.ImportFirst)
        advanceUntilIdle()

        assertEquals(LIMIT, h.port.document().scene.size, "exactly maxElements - 100 were added")
        assertEquals(1, h.port.history.undoDepth, "one undo step")
        val lines = assertIs<ImportUiState.Summary>(h.controller.state.value).lines
        assertEquals(Strings.imported(LIMIT - 100, OVER_CAP_FILE), lines.first())
        assertTrue(Strings.skipped(listOf(Strings.overLimit(OVER_CAP_FILE - (LIMIT - 100)))) in lines, lines.toString())
    }

    @Test
    fun overCap_should_ImportIntoANewSketch_WithoutTouchingTheCurrentSheet() = runTest {
        val h = harness(fullSheet(100))
        val before = h.port.document()
        h.drop(file(OVER_CAP_FILE))
        advanceUntilIdle()

        h.controller.chooseOverCap(OverCapChoice.ImportIntoNewSketch)
        advanceUntilIdle()

        assertTrue(h.port.document() === before, "the current sheet is never touched")
        assertEquals(0, h.port.history.undoDepth)
        assertEquals(listOf(LIMIT), h.port.created.map { it.scene.size })
        assertFalse(assertIs<ImportUiState.Summary>(h.controller.state.value).canUndo, "a new sheet has nothing to undo here")
    }

    @Test
    fun overCap_should_ChangeNothing_When_TheUserCancels() = runTest {
        val h = harness(fullSheet(100))
        val before = h.port.document()
        h.drop(file(OVER_CAP_FILE))
        advanceUntilIdle()

        h.controller.chooseOverCap(OverCapChoice.Cancel)
        advanceUntilIdle()

        assertTrue(h.port.document() === before)
        assertEquals(ImportUiState.Notice(Strings.CANCELLED), h.controller.state.value)
        assertTrue(h.port.created.isEmpty())
    }

    @Test
    fun overCap_should_NameTheRoomLeft_When_TheFileWouldFitASheetOfItsOwn() = runTest {
        val h = harness(fullSheet(4_000))
        h.drop(file(3_000))
        advanceUntilIdle()

        val confirm = assertIs<ImportUiState.Confirm>(h.controller.state.value)
        assertEquals(Strings.overRoom(3_000, 1_000), confirm.overCapText)
        assertTrue(assertNotNull(confirm.overCap).fitsNewSketch)
    }

    @Test
    fun newerSchema_should_ProceedWithANotice_AndCancelBeforeCommitChangesNothing() = runTest {
        val cancelled = harness()
        cancelled.drop(file(2, version = 3))
        advanceUntilIdle()
        val confirm = assertIs<ImportUiState.Confirm>(cancelled.controller.state.value)
        assertTrue(confirm.newerSchema)
        assertEquals(null, confirm.overCap)
        cancelled.controller.cancel()
        advanceUntilIdle()
        assertEquals(0, cancelled.port.document().scene.size)

        val proceeded = harness()
        proceeded.drop(file(2, version = 3))
        advanceUntilIdle()
        proceeded.controller.confirmNewer()
        advanceUntilIdle()
        val lines = assertIs<ImportUiState.Summary>(proceeded.controller.state.value).lines
        assertTrue(Strings.NEWER_SCHEMA in lines, lines.toString())
        assertEquals(2, proceeded.port.document().scene.size)
    }

    @Test
    fun notExcalidraw_should_ShowTheReasonCode_AndCreateNothing() = runTest {
        val h = harness()
        h.drop("this is not json at all", name = "notes.txt")
        advanceUntilIdle()

        val refused = assertIs<ImportUiState.Refused>(h.controller.state.value)
        assertEquals(Strings.NOT_EXCALIDRAW, refused.message)
        assertNotNull(refused.code)
        assertTrue(refused.canChooseAnother, "Choose another file is offered (UX-51)")
        assertEquals(0, h.port.document().scene.size)
        assertEquals(0, h.port.history.undoDepth)
    }

    @Test
    fun aFileOver20MB_should_BeRefusedForSize_BeforeAnyByteIsRead() = runTest {
        val h = harness()
        val big = object : ImportSource {
            override val size: Long = 21L * 1_048_576
            override fun read(offset: Long, length: Int): ByteArray = error("a refused file must not be read")
        }
        h.controller.importDropped(DroppedCanvasFile("huge.excalidraw", big))
        advanceUntilIdle()

        val refused = assertIs<ImportUiState.Refused>(h.controller.state.value)
        assertEquals(Strings.tooLarge(20), refused.message)
        assertEquals("FileTooLarge", refused.code)
    }

    @Test
    fun cancelWhileReading_should_LeaveTheSheetUnchanged() = runTest {
        val h = harness()
        h.drop(file(5))
        assertIs<ImportUiState.Reading>(h.controller.state.value)

        h.controller.cancel()
        advanceUntilIdle()

        assertEquals(ImportUiState.Notice(Strings.CANCELLED), h.controller.state.value)
        assertEquals(0, h.port.document().scene.size, "cancel leaves the sheet unchanged (UX-54)")
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun nothingIsAdded_UntilTheCommit_SoAProcessDeathBeforeItLeavesTheSheetUnchanged() = runTest {
        val h = harness(fullSheet(100))
        val before = h.port.document()
        h.drop(file(OVER_CAP_FILE))
        advanceUntilIdle()

        assertIs<ImportUiState.Confirm>(h.controller.state.value)
        assertTrue(h.port.document() === before)
        assertEquals(0, h.port.history.undoDepth)
    }

    @Test
    fun platformWithoutFileStorage_should_ShowTheReason_AndCreateNothing_ForPickDropAndPaste() = runTest {
        val storage = V2Storage.PlatformUnsupported
        val expected = SketchReasonText.v2StorageUnavailable(storage)
        val h = harness(storage = storage)

        h.controller.chooseFile()
        advanceUntilIdle()
        assertEquals(ImportUiState.Unavailable(expected), h.controller.state.value)
        assertEquals(0, h.files.pickCalls, "the picker is never opened")

        h.controller.dismiss()
        h.drop(file(2))
        advanceUntilIdle()
        assertEquals(ImportUiState.Unavailable(expected), h.controller.state.value)

        h.controller.dismiss()
        h.controller.paste(ExcalidrawTestData.scene(rects(2), type = "excalidraw/clipboard"), 0.0, 0.0)
        advanceUntilIdle()
        assertEquals(ImportUiState.Unavailable(expected), h.controller.state.value)
        assertEquals(0, h.port.document().scene.size)
        assertEquals(0, h.port.history.undoDepth)
    }

    @Test
    fun paste_should_PlaceElementsAtTheCursorInOneUndoStep_AndIgnoreOrdinaryText() = runTest {
        val h = harness()
        h.controller.paste("just some words", 1.0, 1.0)
        advanceUntilIdle()
        assertEquals(ImportUiState.Idle, h.controller.state.value, "ordinary text does nothing, with no message")
        assertEquals(0, h.port.history.undoDepth)

        h.controller.paste(ExcalidrawTestData.scene(rects(3), type = "excalidraw/clipboard"), CURSOR_X, CURSOR_Y)
        advanceUntilIdle()
        assertEquals(3, h.port.document().scene.size)
        assertEquals(1, h.port.history.undoDepth)
        assertEquals(3, h.port.selected.size)
        val bounds = h.port.document().scene.elementsInOrder.map { dev.stapler.stelekit.sketch.v2.index.ElementBounds.geometry(it) }
        assertEquals(CURSOR_X, (bounds.minOf { it.minX } + bounds.maxOf { it.maxX }) / 2.0, 1e-9)
    }

    @Test
    fun chooseFile_should_ReadThePickedFile_AndDoNothingWhenThePickerIsClosed() = runTest {
        val h = harness()
        h.files.picks += null
        h.controller.chooseFile()
        advanceUntilIdle()
        assertEquals(ImportUiState.Idle, h.controller.state.value)

        h.files.picks += PickedImportFile("a.excalidraw", ImportSource.ofBytes(file(2).encodeToByteArray()))
        h.controller.chooseFile()
        advanceUntilIdle()
        assertIs<ImportUiState.Summary>(h.controller.state.value)
        assertEquals(2, h.port.document().scene.size)
    }

    @Test
    fun aFailedImageStore_should_RefuseTheImport_AndLeaveTheSheetUnchanged() = runTest {
        val h = harness()
        h.storeFails = true
        val png = ExcalidrawTestData.pngBytes(4, 4)
        val image = element("image", "i1", extra = ""","fileId":"f1","status":"saved","scale":[1,1]""")
        h.drop(ExcalidrawTestData.scene(listOf(image), files = ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("f1", "image/png", png))))
        advanceUntilIdle()

        val refused = assertIs<ImportUiState.Refused>(h.controller.state.value)
        assertEquals(Strings.IMAGE_SAVE_FAILED, refused.message)
        assertEquals(0, h.port.document().scene.size)
    }

    @Test
    fun anEmbeddedImage_should_BeStoredOnceBySha256_AndTheImportBeUndoable() = runTest {
        val h = harness()
        val png = ExcalidrawTestData.pngBytes(4, 4)
        val image = element("image", "i1", extra = ""","fileId":"f1","status":"saved","scale":[1,1]""")
        h.drop(ExcalidrawTestData.scene(listOf(image), files = ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("f1", "image/png", png))))
        advanceUntilIdle()

        assertIs<ImportUiState.Summary>(h.controller.state.value)
        assertEquals(1, h.port.document().scene.size)
        val stored = h.fs.listFiles("/graph/assets")
        assertEquals(1, stored.size, stored.toString())
        assertEquals(64, stored.single().substringBefore('.').length, "named by the sha256 of the bytes")
    }

    private companion object {
        const val FILE_VERSION = 2
                const val CURSOR_X = 200.0
        const val CURSOR_Y = 100.0
        const val OVER_CAP_FILE = 7_200
        const val LIMIT = 5_000
    }
}
