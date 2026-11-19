// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.library.LibraryItem
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFailure
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportScope
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportSource
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.export.SketchExportRunner
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Export as Strings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stories 5.2.3a and b (UX-57, UX-58, UX-114): the export dialog's options, scopes, impossible-export fixes and success feedback. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportDialogModelTest {
    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun rect(id: String, x: Int, y: Int, w: Int = 10, h: Int = 10, frame: String? = null, layer: LayerId = LayerId.Default) =
        RectElement(ElementBase(ElementId(id), layerId = layer, frameId = frame?.let(::ElementId)), u(x), u(y), u(w), u(h))

    private fun frame(id: String, x: Int, y: Int, w: Int, h: Int, name: String? = null) =
        FrameElement(ElementBase(ElementId(id), name = name), u(x), u(y), u(w), u(h))

    private fun scene(vararg elements: SceneElement, layers: List<Layer> = emptyList()): SketchSceneV2 {
        val base = SketchSceneV2.empty().let { if (layers.isEmpty()) it else it.withLayers(layers) }
        return elements.fold(base) { s, e -> s.add(e).fold({ error("$it") }, { it }) }
    }

    private val runner = SketchExportRunner(PngExporter(), DrawListBuilder(FixedAdvanceTextMetricsProvider()))

    private fun source(
        scene: SketchSceneV2,
        selection: Set<ElementId> = emptySet(),
        images: SvgImageSource = SvgImageSource.None,
        library: List<LibraryItem> = emptyList(),
        csv: (() -> ByteArray)? = null,
    ) = ExportSource(scene, selection, images, library, csv, baseName = "architecture")

    private val saved = SketchSaveOutcome.Saved("architecture.png", "Downloads", "/home/u/Downloads/architecture.png")

    private class Harness(val controller: ExportDialogController, val notices: MutableList<SketchNotice>, val files: FakeSketchFiles)

    private fun TestScope.harness(
        source: () -> ExportSource,
        outcome: () -> SketchSaveOutcome,
        runner: SketchExportRunner,
        savedNotice: (SketchSaveOutcome.Saved) -> SketchNotice = ImportExportNotices::exportSaved,
    ): Harness {
        val notices = mutableListOf<SketchNotice>()
        val files = FakeSketchFiles().also { it.outcome = outcome }
        val controller = ExportDialogController(source, runner, files, { notices += it }, savedNotice, StandardTestDispatcher(testScheduler))
        return Harness(controller, notices, files)
    }

    private val huge = scene(rect("a", 0, 0), rect("b", 11_990, 7_990))

    @Test
    fun formats_should_ListCsvOnlyWithDimensions_AndTheLibraryOnlyWhenItHoldsItems() {
        val none = ExportDialogModel(source(scene(rect("a", 0, 0))), runner)
        assertEquals(Strings.CSV_DISABLED, none.disabledReason(ExportFormat.Csv))
        assertEquals(Strings.LIBRARY_EMPTY, none.disabledReason(ExportFormat.Library))
        assertNull(none.disabledReason(ExportFormat.Png))
        assertNull(none.disabledReason(ExportFormat.Svg))
        assertNull(none.disabledReason(ExportFormat.Excalidraw))

        val some = ExportDialogModel(source(scene(rect("a", 0, 0)), csv = { ByteArray(0) }, library = listOf(LibraryItem("l", "n", 0, emptyList(), 0))), runner)
        assertNull(some.disabledReason(ExportFormat.Csv))
        assertNull(some.disabledReason(ExportFormat.Library))
    }

    @Test
    fun selectionScope_should_BeDisabledWithAReason_WhenNothingIsSelected() {
        val model = ExportDialogModel(source(scene(rect("a", 0, 0))), runner)
        assertEquals(Strings.SELECTION_DISABLED, model.disabledReason(ExportScope.Selection))
        assertEquals(Strings.FRAME_DISABLED, model.disabledReason(ExportScope.Frame))
        assertEquals(ExportScope.Whole, model.initial().scope)
        assertEquals(ExportScope.Whole, model.normalised(ExportRequest(scope = ExportScope.Selection)).scope, "a disabled scope falls back")

        val selected = ExportDialogModel(source(scene(rect("a", 0, 0)), selection = setOf(ElementId("a"))), runner)
        assertNull(selected.disabledReason(ExportScope.Selection))
        assertEquals(ExportScope.Selection, selected.initial().scope)
    }

    @Test
    fun layersFlattenedNotice_should_ShowOnlyForExcalidrawWithMoreThanOneLayer() {
        val layers = listOf(Layer.DefaultLayer, Layer(LayerId("two"), "Layer 2", order = 1))
        val two = ExportDialogModel(source(scene(rect("a", 0, 0), layers = layers)), runner)
        assertTrue(two.showsFlattenedNotice(ExportRequest(format = ExportFormat.Excalidraw)))
        assertFalse(two.showsFlattenedNotice(ExportRequest(format = ExportFormat.Png)))
        assertEquals("Layers are flattened in the exported file (ADR-006).", Strings.LAYERS_FLATTENED)

        val one = ExportDialogModel(source(scene(rect("a", 0, 0))), runner)
        assertFalse(one.showsFlattenedNotice(ExportRequest(format = ExportFormat.Excalidraw)))
    }

    @Test
    fun anImpossiblePng_should_KeepTheDialogOpen_WithTheReason_And_UseOneXFixesIt() = runTest {
        val h = harness({ source(huge) }, { saved }, runner)
        h.controller.open()
        h.controller.update(ExportRequest(format = ExportFormat.Png, scale = 3))
        assertIs<ExportPreview.TooLarge>(assertIs<ExportUiState.Open>(h.controller.state.value).preview)

        h.controller.export()
        advanceUntilIdle()

        val open = assertIs<ExportUiState.Open>(h.controller.state.value)
        val error = assertIs<ExportError.TooLarge>(open.error)
        assertTrue(error.message.startsWith("This image would be 36,"), error.message)
        assertTrue(error.message.endsWith("px wide. Choose 1x or export a frame."), error.message)
        assertFalse(open.canExport, "Export stays off until the size is fixed")
        assertTrue(h.files.saved.isEmpty() && h.notices.isEmpty())

        h.controller.useOneX()

        val fixed = assertIs<ExportUiState.Open>(h.controller.state.value)
        assertEquals(1, fixed.request.scale)
        assertNull(fixed.error)
        assertTrue(fixed.canExport, "Export is back once the result fits")
        assertIs<ExportPreview.Size>(fixed.preview)
    }

    @Test
    fun anEncodeFailure_should_ShowTheReasonWithRetry_AndLeaveTheSceneUntouched() = runTest {
        val image = ImageElement(ElementBase(ElementId("i1")), u(0), u(0), u(10), u(10), fileId = "f1")
        var failing = true
        val images = SvgImageSource { if (failing) error("out of memory") else null }
        val h = harness({ source(scene(image), images = images) }, { saved }, runner)
        h.controller.open()
        h.controller.update(ExportRequest(format = ExportFormat.Svg))

        h.controller.export()
        advanceUntilIdle()
        val failed = assertIs<ExportUiState.Open>(h.controller.state.value)
        assertEquals(ExportError.EncodeFailed, failed.error)
        assertEquals("Couldn't create the image. Try a smaller scale.", failed.error?.message)
        assertTrue(h.notices.isEmpty(), "failure never posts the success toast")

        failing = false
        h.controller.retry()
        advanceUntilIdle()
        assertEquals(ExportUiState.Closed, h.controller.state.value)
        assertEquals(listOf("architecture.svg"), h.files.saved)
    }

    @Test
    fun saveFailures_should_OfferChooseAnotherLocation_AndTryAgain_AndSuccessClosesWithTheToast() = runTest {
        var next: SketchSaveOutcome = SketchSaveOutcome.Unwritable
        val h = harness({ source(scene(rect("a", 0, 0))) }, { next }, runner)
        h.controller.open()
        h.controller.update(ExportRequest(format = ExportFormat.Excalidraw))

        h.controller.export()
        advanceUntilIdle()
        assertEquals(ExportError.Unwritable, assertIs<ExportUiState.Open>(h.controller.state.value).error)
        assertEquals("Couldn't save the file.", ExportError.Unwritable.message)

        next = SketchSaveOutcome.Blocked
        h.controller.retry()
        advanceUntilIdle()
        assertEquals(ExportError.Blocked, assertIs<ExportUiState.Open>(h.controller.state.value).error)
        assertEquals("Your browser blocked the download.", ExportError.Blocked.message)

        next = SketchSaveOutcome.Saved("architecture.excalidraw", "Downloads", "/x/architecture.excalidraw")
        h.controller.retry()
        advanceUntilIdle()
        assertEquals(ExportUiState.Closed, h.controller.state.value)
        assertEquals(listOf(Strings.EXPORT_SAVED_KEY), h.notices.map { it.key })
        assertEquals(3, h.files.saved.size)
    }

    @Test
    fun aClosedSaveDialog_should_StayOpenWithoutAnErrorOrAToast() = runTest {
        val h = harness({ source(scene(rect("a", 0, 0))) }, { SketchSaveOutcome.Cancelled }, runner)
        h.controller.open()
        h.controller.update(ExportRequest(format = ExportFormat.Svg))
        h.controller.export()
        advanceUntilIdle()

        val open = assertIs<ExportUiState.Open>(h.controller.state.value)
        assertNull(open.error)
        assertFalse(open.working)
        assertTrue(h.notices.isEmpty())
        h.controller.cancel()
        assertEquals(ExportUiState.Closed, h.controller.state.value)
    }

    @Test
    fun successToast_should_NameTheFileAndPlace_WithOpenOnDesktop_AndNoOpenOnTheWeb() {
        val desktop = ImportExportNotices.exportSaved(saved)
        assertEquals("File exported.", desktop.text)
        assertEquals("Open", desktop.action)
        assertEquals(listOf("Copy path"), desktop.secondaryActions)
        assertEquals("architecture.png in Downloads", desktop.details)

        val noOpener = ImportExportNotices.exportSaved(SketchSaveOutcome.Saved("architecture.png", "Downloads", path = null))
        assertNull(noOpener.action, "no Open where the app cannot open the file")

        val web = ImportExportNotices.exportDownloaded(SketchSaveOutcome.Saved("architecture.png", null, null))
        assertEquals("File downloaded.", web.text)
        assertNull(web.action, "a download cannot be opened by the app (UX-151)")
        assertEquals("architecture.png in Downloads. Check your downloads.", web.details)
    }

    @Test
    fun anExportThatWrotePlaceholders_should_SayHowManyInTheToastDetails() = runTest {
        val image = ImageElement(ElementBase(ElementId("i1")), u(0), u(0), u(10), u(10), fileId = "f1")
        val h = harness({ source(scene(image)) }, { saved }, runner)
        h.controller.open()
        h.controller.update(ExportRequest(format = ExportFormat.Excalidraw))
        h.controller.export()
        advanceUntilIdle()

        assertTrue(h.notices.single().details!!.endsWith("1 missing image exported as placeholder"), h.notices.single().details)
    }

    @Test
    fun scopes_should_ExportOnlyTheSelection_AndAFrameWithItsMembersCroppedToTheFrame() {
        val f = frame("f1", 0, 0, 100, 80, name = "Plan")
        val scene = scene(f, rect("in", 10, 10, frame = "f1"), rect("out", 500, 500), rect("other", 600, 600))

        val selection = runner.run(source(scene, selection = setOf(ElementId("out"))), ExportRequest(format = ExportFormat.Excalidraw, scope = ExportScope.Selection))
        assertEquals(listOf("out"), idsIn(selection))

        val framed = runner.run(source(scene), ExportRequest(format = ExportFormat.Excalidraw, scope = ExportScope.Frame, frameId = ElementId("f1")))
        assertEquals(setOf("f1", "in"), idsIn(framed).toSet(), "the frame and its members only")

        val crop = ElementBounds.of(f)
        val size = runner.measure(source(scene), ExportRequest(format = ExportFormat.Png, scope = ExportScope.Frame, frameId = ElementId("f1"), scale = 2))!!.fold({ error("$it") }, { it })
        assertEquals(ceil((crop.maxX - crop.minX + 32.0) * 2).toLong(), size.widthPx, "cropped to the frame bounds")
        assertEquals(ceil((crop.maxY - crop.minY + 32.0) * 2).toLong(), size.heightPx)
    }

    @Test
    fun libraryAndCsv_should_ExportTheirOwnFiles() {
        val item = LibraryItem("l1", "Box", 0, listOf(rect("a", 0, 0)), 10)
        val lib = runner.run(source(scene(rect("a", 0, 0)), library = listOf(item)), ExportRequest(format = ExportFormat.Library))
        val libFile = lib.fold({ error("$it") }, { it })
        assertEquals("architecture.excalidrawlib", libFile.fileName)
        assertTrue(libFile.bytes.decodeToString().contains("excalidrawlib"))

        val csv = runner.run(source(scene(rect("a", 0, 0)), csv = { "x,y".encodeToByteArray() }), ExportRequest(format = ExportFormat.Csv))
        assertEquals("architecture.csv", csv.fold({ error("$it") }, { it }).fileName)

        val noCsv = runner.run(source(scene(rect("a", 0, 0))), ExportRequest(format = ExportFormat.Csv))
        assertEquals(ExportFailure.NothingToExport, noCsv.swap().getOrNull())
    }

    private fun idsIn(result: arrow.core.Either<ExportFailure, dev.stapler.stelekit.sketch.v2.runtime.export.ExportFile>): List<String> {
        val text = result.fold({ error("$it") }, { it }).bytes.decodeToString()
        return Regex(""""id":"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
    }
}
