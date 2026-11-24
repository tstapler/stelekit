// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.platform.PickedImportFile
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.commands.RemoveUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlay
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CoroutineScope
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.scene.CalibrationNote
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.UnderlayPlacement
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.units.LengthFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E5: the underlay controls over a real controller: place, calibrate through the tool and a typed length, move, lock, remove, undo. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnderlayControllerTest {
    private val file = "a".repeat(64)
    private val placed = UnderlayPlacement(0.0, 0.0, 800.0, 600.0, 0.0)

    private fun TestScope.rig(): Pair<SketchEditorController, EditorExtras> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var n = 0
        val metrics = FixedAdvanceTextMetricsProvider()
        val controller = SketchEditorController(
            SketchDocumentV2(sheetUuid = "s"),
            EditorPorts(StandardToolRegistry.create(metrics), null, SolveRunner(context = dispatcher), ElementIdSource { ElementId("n${n++}") }),
            dispatcher,
        )
        val extras = EditorExtras(controller, HostServices(), ModernPreviewEnvironment(metrics, PainterResources()))
        assertTrue(controller.commitNow(SetUnderlay(file, 4000, 3000, placed)).isRight())
        return controller to extras
    }

    private fun point(x: Int, y: Int) = ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y))

    private fun SketchEditorController.touch(type: InputType, x: Int, y: Int, pointer: Int = 0) =
        tools.onPointerActions(listOf(PointerAction.ToolInput(InputEvent(type, point(x, y), null, PointerKind.Touch, pointer))))

    private fun SketchEditorController.tap(x: Int, y: Int) {
        touch(InputType.Down, x, y)
        touch(InputType.Up, x, y)
    }

    @Test
    fun calibrate_should_ScaleThePhoto_ThroughTwoTappedPointsAndATypedLength() = runTest {
        val (controller, extras) = rig()
        val underlay = extras.underlay
        underlay.startCalibrate()
        assertEquals(UnderlayMode.Calibrating, underlay.mode)
        controller.tap(100, 300)
        assertNull(underlay.pending, "one point is not enough")
        controller.tap(500, 300)
        assertNotNull(underlay.pending, "two points open the length field")

        underlay.apply("10'")

        assertEquals(UnderlayMode.Idle, underlay.mode)
        assertNull(underlay.pending)
        val state = assertNotNull(controller.doc.scene.underlay)
        assertEquals(CalibrationNote.Calibrated(1_920L, perspective = false), CalibrationNote.of(state))
        assertEquals("calibrated: 10' 0\" ref", underlay.noteText())
        controller.close()
        extras.close()
    }

    @Test
    fun calibrate_should_KeepTheFieldOpenWithAMessage_When_TheLengthIsNotALength() = runTest {
        val (controller, extras) = rig()
        val underlay = extras.underlay
        underlay.startCalibrate()
        controller.tap(100, 300)
        controller.tap(500, 300)

        underlay.apply("abc")

        assertNotNull(underlay.error)
        assertNotNull(underlay.pending)
        assertEquals(UnderlayMode.Calibrating, underlay.mode)
        assertEquals(CalibrationNote.None, CalibrationNote.of(controller.doc.scene.underlay), "nothing changed")
        underlay.apply("3'")
        assertNull(underlay.error)
        controller.close()
        extras.close()
    }

    @Test
    fun calibrate_should_BeOneUndoStep_AndEscapeShouldEndTheMode() = runTest {
        val (controller, extras) = rig()
        val underlay = extras.underlay
        underlay.startCalibrate()
        controller.tap(100, 300)
        controller.tap(500, 300)
        underlay.apply("10'")
        assertTrue(controller.canUndo)
        controller.undo()
        assertEquals(CalibrationNote.None, CalibrationNote.of(controller.doc.scene.underlay))
        assertEquals(800.0, controller.doc.scene.underlay?.placement?.width)

        underlay.startCalibrate()
        controller.tools.escape()
        assertEquals(UnderlayMode.Idle, underlay.mode)
        controller.close()
        extras.close()
    }

    @Test
    fun adjust_should_NeedAnUnlockedPhoto_AndMoveItInOneUndoStep() = runTest {
        val (controller, extras) = rig()
        val underlay = extras.underlay
        assertTrue(assertNotNull(underlay.state).locked, "placed locked")
        underlay.startAdjust()
        assertEquals(UnderlayMode.Idle, underlay.mode, "a locked photo cannot be moved")

        underlay.setLocked(false)
        underlay.startAdjust()
        assertEquals(UnderlayMode.Adjusting, underlay.mode)
        controller.touch(InputType.Down, 10, 10)
        controller.touch(InputType.Move, 70, 40)
        controller.touch(InputType.Up, 70, 40)

        assertEquals(60.0, controller.doc.scene.underlay?.placement?.x)
        assertEquals(30.0, controller.doc.scene.underlay?.placement?.y)
        controller.undo()
        assertEquals(0.0, controller.doc.scene.underlay?.placement?.x)
        controller.close()
        extras.close()
    }

    @Test
    fun lockAndOpacity_should_BeTheLayersOwn_And_RemoveShouldLeaveNoLayer() = runTest {
        val (controller, extras) = rig()
        val underlay = extras.underlay
        underlay.setOpacity(0.75)
        underlay.setLocked(false)
        val layer = assertNotNull(controller.doc.scene.layer(UnderlayIds.LAYER))
        assertEquals(0.75, layer.opacity)
        assertTrue(!layer.locked)
        assertEquals(0.75, UnderlayOpacitySteps.nearest(0.7))

        underlay.remove()
        assertNull(controller.doc.scene.underlay)
        assertNull(controller.doc.scene.layer(UnderlayIds.LAYER))
        controller.undo()
        assertNotNull(controller.doc.scene.underlay, "removing is undoable")
        controller.close()
        extras.close()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** A PNG that states a size and carries nothing else: enough for the header probe the store and the import check read. */
    private fun fakePng(w: Int, h: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52) + be32(w) + be32(h) + byteArrayOf(8, 6, 0, 0, 0)

    private class Picker(private val bytes: ByteArray) : SketchFilePort {
        override val canPick: Boolean = true
        override suspend fun pickImportFile(title: String, extensions: List<String>) = PickedImportFile("p.png", ImportSource.ofBytes(bytes))
        override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray) = SketchSaveOutcome.Cancelled
    }

    private class Downscaler(private val answer: ByteArray?) : ImageDownscalerPort {
        val asked = mutableListOf<List<Int>>()
        override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
            asked += maxEdges
            return answer?.let { listOf(it) }
        }
    }

    private fun TestScope.pick(photo: ByteArray, downscaler: Downscaler): SketchEditorController {
        val (controller, extras) = rig()
        controller.commitNow(RemoveUnderlay)
        val store = ImageAssetStore(FakeFileSystem(), "/graph/assets")
        val picker = UnderlayController(controller, Picker(photo), store, CoroutineScope(controller.adoption), SceneLimits.Default, downscaler) { LengthFormat.FEET_INCHES }
        picker.choosePhoto()
        testScheduler.advanceUntilIdle()
        extras.close()
        return controller
    }

    @Test
    fun choosePhoto_should_ReduceAPhotoOverTheStoreLimits_AndPlaceTheReducedCopy() = runTest {
        val reduced = fakePng(3840, 2880)
        val downscaler = Downscaler(reduced)
        val controller = pick(fakePng(8000, 6000), downscaler)
        val state = assertNotNull(controller.doc.scene.underlay, "a 48 megapixel photo is placed")
        assertEquals(listOf(listOf(UnderlayController.IMPORT_EDGE_PX)), downscaler.asked)
        assertEquals(3840, state.meta.widthPx)
        assertEquals(ContentHasher.sha256(reduced), state.element.fileId)
        controller.close()
    }

    @Test
    fun choosePhoto_should_StoreAPhotoWithinTheLimitsByteForByte() = runTest {
        val original = fakePng(4000, 3000)
        val downscaler = Downscaler(fakePng(10, 10))
        val controller = pick(original, downscaler)
        assertEquals(ContentHasher.sha256(original), assertNotNull(controller.doc.scene.underlay).element.fileId)
        assertTrue(downscaler.asked.isEmpty(), "nothing was re-encoded")
        controller.close()
    }

    @Test
    fun choosePhoto_should_PlaceNothing_When_AnOversizePhotoCannotBeReduced() = runTest {
        val controller = pick(fakePng(8000, 6000), Downscaler(null))
        assertNull(controller.doc.scene.underlay, "the store refuses it and the sheet is untouched")
        controller.close()
    }

    @Test
    fun noteText_should_ReadInTheSheetsNotation() {
        assertEquals("Not calibrated", UnderlayNoteText.of(CalibrationNote.None, LengthFormat.FEET_INCHES))
        assertEquals("calibrated: 3' 0\" ref", UnderlayNoteText.of(CalibrationNote.Calibrated(576L, perspective = false), LengthFormat.FEET_INCHES))
        assertEquals("calibrated: 36\" ref", UnderlayNoteText.of(CalibrationNote.Calibrated(576L, perspective = false), LengthFormat.INCHES_MIXED))
        assertTrue("perspective" in UnderlayNoteText.of(CalibrationNote.Calibrated(576L, perspective = true), LengthFormat.FEET_INCHES, residualPercent = 12))
        assertTrue("Recalibrate" in UnderlayNoteText.of(CalibrationNote.OutOfDate(576L), LengthFormat.FEET_INCHES))
    }
}
