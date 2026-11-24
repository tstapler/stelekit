// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.platform.PickedImportFile
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.sketch.v2.scene.CalibrationNote
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.tools.InputType
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The photo underlay on a phone: pick, place, calibrate with the keypad row, and a large photo shown within the display cap.
 * Roborazzi shots go to build/outputs/roborazzi when recording; every assertion is on state, not on those files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")
class UnderlayPhoneRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private class PhotoPicker(private val bytes: ByteArray) : SketchFilePort {
        override val canPick: Boolean = true
        override suspend fun pickImportFile(title: String, extensions: List<String>) = PickedImportFile("kitchen.png", ImportSource.ofBytes(bytes))
        override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray) = SketchSaveOutcome.Cancelled
    }

    /** A grid photo of [width] x [height] pixels, so the picture shows scale and orientation. */
    private fun gridPhoto(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(230, 224, 210))
        val line = Paint().apply { color = Color.rgb(120, 100, 80); strokeWidth = 6f }
        for (x in 0 until width step GRID_PX) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), line)
        for (y in 0 until height step GRID_PX) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), line)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun point(x: Int, y: Int) = ScenePoint(SceneUnit.ofInt(x), SceneUnit.ofInt(y))

    private fun mount(photo: ByteArray): SketchEditorSession {
        val rig = V2EditorRig()
        runBlocking { rig.seed() }
        val metrics = FixedAdvanceTextMetricsProvider()
        val opened = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(rig.deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        val sheet = assertNotNull(opened.sheet)
        val services = HostServices(files = PhotoPicker(photo), images = ImageAssetStore(FakeFileSystem(), "/graph/assets"))
        val session = SketchEditorSession(opened.controller, opened.diagnostics, opened.title, null, opened.slug, sheet, services)
        rule.setContent { SketchEditorV2Host(session, ModernPreviewEnvironment(metrics, PainterResources()), onBack = {}) }
        rule.waitForIdle()
        return session
    }

    @Test
    fun photo_should_BePlacedLocked_ThenCalibratedWithTheKeypad_AndNamedOnTheCanvas() {
        val session = mount(gridPhoto(PHOTO_W, PHOTO_H))
        val extras = assertNotNull(session.extras)
        extras.underlay.choosePhoto()
        rule.waitUntil(WAIT_MS) { extras.underlay.state != null }
        rule.waitForIdle()
        val placed = assertNotNull(extras.underlay.state)
        assertTrue(placed.locked, "placed locked")
        assertEquals(PHOTO_W, placed.meta.widthPx)
        rule.onNodeWithTag(UnderlayTags.NOTE).assertTextEquals("Not calibrated")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/underlay-1-placed.png")

        rule.onNodeWithTag(UnderlayTags.CALIBRATE).performClick()
        rule.waitForIdle()
        assertEquals(UnderlayMode.Calibrating, extras.underlay.mode)
        val centre = placed.placement
        val y = (centre.y + centre.height / 2).toInt()
        val tools = session.controller.tools
        for (x in listOf((centre.x + centre.width * QUARTER).toInt(), (centre.x + centre.width * THREE_QUARTERS).toInt())) {
            tools.onPointerActions(listOf(PointerAction.ToolInput(InputEvent(InputType.Down, point(x, y), null, PointerKind.Touch))))
            tools.onPointerActions(listOf(PointerAction.ToolInput(InputEvent(InputType.Up, point(x, y), null, PointerKind.Touch))))
        }
        rule.waitForIdle()
        rule.onNodeWithTag(UnderlayTags.DISTANCE).assertExists()
        rule.onNodeWithTag(LengthKeyTags.ROW).assertExists()
        rule.onNodeWithTag(UnderlayTags.DISTANCE).performTextInput("3'")
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/underlay-2-length.png")

        rule.onNodeWithTag(UnderlayTags.APPLY).performClick()
        rule.waitForIdle()
        assertEquals(CalibrationNote.Calibrated(576L, perspective = false), CalibrationNote.of(extras.underlay.state))
        rule.onNodeWithTag(UnderlayTags.NOTE).assertTextEquals("calibrated: 3' 0\" ref")
        rule.onNodeWithTag(UnderlayTags.REF_LABEL).assertTextEquals("3' 0\"")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/underlay-3-calibrated.png")
    }

    @Test
    fun largePhoto_should_BeHeldWithinTheDisplayCap_NotAtFullResolution() {
        val photo = gridPhoto(BIG_W, BIG_H)
        val failures = mutableListOf<Throwable>()
        // The real Android downscaler and decoder, on the calling thread so the result is there when image() returns.
        val held = SheetImageBitmaps({ photo }, onFailure = { failures += it }, main = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        held.image("big")
        assertTrue(failures.isEmpty(), "decode failed: $failures")
        val (w, h) = assertNotNull(held.heldSize("big"))
        assertTrue(maxOf(w, h) <= SheetImageBitmaps.DISPLAY_EDGE_PX, "held $w x $h, cap ${SheetImageBitmaps.DISPLAY_EDGE_PX}")
        assertTrue(w.toLong() * h < BIG_W.toLong() * BIG_H / 2, "far fewer pixels than the $BIG_W x $BIG_H original")
        assertEquals(BIG_W.toDouble() / BIG_H, w.toDouble() / h, 0.02)
    }

    private companion object {
        const val PHOTO_W = 1200
        const val PHOTO_H = 900
        const val BIG_W = 4000
        const val BIG_H = 3000
        const val GRID_PX = 200
        const val WAIT_MS = 20_000L
        const val QUARTER = 0.25
        const val THREE_QUARTERS = 0.75
    }
}
