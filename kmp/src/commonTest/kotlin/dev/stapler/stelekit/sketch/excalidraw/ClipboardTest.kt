// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.v2.W6Kit
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.clipboard.ExcalidrawClipboard
import dev.stapler.stelekit.sketch.v2.clipboard.ExcalidrawPaste
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 5.1.4 (UX-59): Excalidraw clipboard JSON pastes at the cursor in one undo step; any other text does nothing. */
class ClipboardTest {
    private val clipboardText = ExcalidrawTestData.scene(
        listOf(element("rectangle", "r1", 0, 0, 100, 50), element("ellipse", "e1", 200, 0, 60, 60), element("diamond", "d1", 0, 120, 40, 40)),
        type = "excalidraw/clipboard",
    )

    private suspend fun read(text: String, x: Double = CURSOR_X, y: Double = CURSOR_Y, limits: SceneLimits = SceneLimits.Default) =
        ExcalidrawClipboard.read(text, x, y, W6Kit.SeqIds("p"), limits)

    @Test
    fun paste_should_PlaceThreeElementsCentredOnTheCursorWithFreshIdsInOneUndoStep() = runTest {
        val ready = assertIs<ExcalidrawPaste.Ready>(read(clipboardText))
        assertEquals(listOf("p1", "p2", "p3"), ready.pastedIds.map { it.value })

        val committed = Transaction.begin(SketchDocumentV2()).apply(ready.command).get().commit().get()
        val scene = committed.after.scene
        assertEquals(3, scene.size)
        assertTrue(scene.elementsInOrder.none { it.id.value in setOf("r1", "e1", "d1") }, "ids are remapped")

        val bounds = scene.elementsInOrder.map { ElementBounds.geometry(it) }
        assertEquals(CURSOR_X, (bounds.minOf { it.minX } + bounds.maxOf { it.maxX }) / 2.0)
        assertEquals(CURSOR_Y, (bounds.minOf { it.minY } + bounds.maxOf { it.maxY }) / 2.0)

        val history = HistoryV2.of().commit(committed).get()
        assertEquals(1, history.undoDepth)
        assertEquals(0, history.undo().present.scene.size)
    }

    @Test
    fun paste_should_KeepTheImportReportSoTheCallerCanSummarise() = runTest {
        val withUnknown = ExcalidrawTestData.scene(listOf(element("rectangle", "r1"), element("stickynote", "s1")), type = "excalidraw/clipboard")
        val ready = assertIs<ExcalidrawPaste.Ready>(read(withUnknown))
        assertEquals("Imported 1 of 2. Skipped: 1 stickynote.", ready.report.summary())
    }

    @Test
    fun otherText_should_DoNothing() = runTest {
        for (text in listOf("hello world", "", "  ", "[1,2,3]", "{not json", """{"a":1}""", """{"type":"excalidraw/clipboard","elements":[]}""", """{"type":"excalidrawlib"}""")) {
            assertIs<ExcalidrawPaste.NotExcalidraw>(read(text), "text: $text")
        }
    }

    @Test
    fun overTheImportCap_should_BeRefusedBeforeAnyParse() = runTest {
        val small = SceneLimits(maxImportBytes = clipboardText.length.toLong() - 1)
        val refused = assertIs<ExcalidrawPaste.Refused>(read(clipboardText, limits = small))
        assertEquals(ImportReason.FileTooLarge, refused.error.reason)
        assertFalse(refused.error.cap == null)
    }

    @Test
    fun overTheElementCap_should_BeRefusedAndAPasteIntoAFullSheetChangesNothing() = runTest {
        val tooMany = assertIs<ExcalidrawPaste.Refused>(read(clipboardText, limits = SceneLimits(maxElements = 2)))
        assertEquals(ImportReason.TooManyElements, tooMany.error.reason)

        val ready = assertIs<ExcalidrawPaste.Ready>(read(clipboardText))
        var scene = SketchSceneV2.empty(SceneLimits(maxElements = 2))
        for (i in 0 until 2) scene = scene.add(W6Kit.rect("x$i", i * 20, 0)).get()
        assertTrue(Transaction.begin(SketchDocumentV2(scene = scene)).apply(ready.command).isLeft())
    }

    @Test
    fun embeddedPictures_should_BeHandedBackForTheCallerToStore() = runTest {
        val png = ExcalidrawTestData.pngBytes(4, 4)
        val text = ExcalidrawTestData.scene(
            listOf(element("image", "i1", 0, 0, 40, 40, ""","fileId":"f1","scale":[1,1]""")),
            files = ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("f1", "image/png", png)),
            type = "excalidraw/clipboard",
        )
        val ready = assertIs<ExcalidrawPaste.Ready>(read(text))
        assertEquals(1, ready.images.size)
        assertTrue(ready.images.single().bytes.contentEquals(png))
    }

    private companion object {
        const val CURSOR_X = 200.0
        const val CURSOR_Y = 100.0
    }
}
