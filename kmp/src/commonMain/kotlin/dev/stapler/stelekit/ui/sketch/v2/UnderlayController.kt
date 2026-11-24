// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.platform.image.PlatformImageDownscaler
import dev.stapler.stelekit.platform.image.ExifOrientationReader
import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.v2.commands.CalibrateUnderlay
import dev.stapler.stelekit.sketch.v2.commands.RemoveUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetLayerOpacity
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlay
import dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbe
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageAssetStore
import dev.stapler.stelekit.sketch.v2.runtime.image.ImageStoreError
import dev.stapler.stelekit.sketch.v2.scene.CalibrationNote
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.UnderlayCalibration
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.UnderlayPlacement
import dev.stapler.stelekit.sketch.v2.scene.UnderlayState
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.sketch.v2.tools.AdjustUnderlayCommand
import dev.stapler.stelekit.sketch.v2.tools.CalibrationPointsCommand
import dev.stapler.stelekit.sketch.v2.tools.EndUnderlayModeCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.UnderlayAdjustTool
import dev.stapler.stelekit.sketch.v2.tools.UnderlayCalibrateTool
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchUnderlayStrings
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlin.math.roundToInt

/** What the underlay controls are doing: nothing, waiting for two calibration points and a length, or moving the photo. */
enum class UnderlayMode { Idle, Calibrating, Adjusting }

/** The opacity steps of the bar, as a share of full: faint enough to trace, solid enough to read the photo. */
object UnderlayOpacitySteps {
    val ALL = listOf(0.25, 0.5, 0.75, 1.0)

    /** The step nearest [opacity], so a layer set to 0.6 elsewhere still shows one step as current. */
    fun nearest(opacity: Double): Double = ALL.minBy { kotlin.math.abs(it - opacity) }

    fun percent(step: Double): Int = (step * PERCENT).roundToInt()

    private const val PERCENT = 100
}

/** Turns calibration state into the words the bar and the canvas note show. Pure. */
object UnderlayNoteText {
    fun of(note: CalibrationNote, format: LengthFormat, residualPercent: Int = 0): String = when (note) {
        CalibrationNote.None -> SketchUnderlayStrings.NOT_CALIBRATED
        is CalibrationNote.Calibrated -> if (note.perspective) SketchUnderlayStrings.perspective(residualPercent) else SketchUnderlayStrings.calibrated(length(note.lengthUnits, format))
        is CalibrationNote.OutOfDate -> SketchUnderlayStrings.outOfDate(length(note.lengthUnits, format))
    }

    fun length(units: Long, format: LengthFormat): String = LengthFormatter.format(Inches(units), format)
}

/**
 * The photo underlay of one open sheet (epic E5): picks the photo, places it locked on its own bottom layer, calibrates it from two
 * tapped points and a typed length, and moves it. It holds only the interaction state (which step the user is on, the typed text);
 * every document change is one undoable command through the controller, and the photo itself is an ordinary image element.
 */
class UnderlayController(
    private val controller: SketchEditorController,
    private val files: SketchFilePort?,
    private val images: ImageAssetStore?,
    private val scope: CoroutineScope,
    private val limits: SceneLimits,
    private val downscaler: ImageDownscalerPort = PlatformImageDownscaler,
    private val io: CoroutineDispatcher = PlatformDispatcher.IO,
    private val format: () -> LengthFormat,
) {
    var mode: UnderlayMode by mutableStateOf(UnderlayMode.Idle)
        private set

    /** The two points the user placed, once both are down; the length field is open while this is set. */
    var pending: CalibrationPointsCommand? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    /** Turn the photo so the reference runs along the nearest axis (when it is already close to one). */
    var level: Boolean by mutableStateOf(true)

    /** True while the next reference is a further one (a check for perspective), not a replacement. */
    var addingReference: Boolean by mutableStateOf(false)
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    /** The underlay as the document holds it now, or null. Reading it observes the document revision. */
    val state: UnderlayState?
        get() {
            controller.revision
            return controller.doc.scene.underlay
        }

    val lengthFormat: LengthFormat get() = format()

    val canPick: Boolean get() = files?.canPick == true && images != null && !controller.readOnly

    val readOnly: Boolean get() = controller.readOnly

    // ---- photo

    fun choosePhoto() {
        val port = files ?: return
        val store = images ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val picked = port.pickImportFile(SketchUnderlayStrings.PICK_TITLE, PHOTO_EXTENSIONS) ?: return@launch
                if (picked.source.size > limits.maxImageBytes) {
                    controller.notifier.toast(SketchUnderlayStrings.STORE_FAILED_KEY, ImageStoreError.TooLarge(picked.source.size, limits.maxImageBytes).message)
                    return@launch
                }
                val (original, bytes, storing) = withContext(io) {
                    val raw = picked.source.read(0, picked.source.size.toInt())
                    val reduced = reducedForStore(raw)
                    Triple(raw, reduced, store.store(reduced))
                }
                val stored = storing.fold({ failure -> controller.notifier.toast(SketchUnderlayStrings.STORE_FAILED_KEY, failure.message).let { null } }, { it })
                    ?: return@launch
                val turned = ExifOrientationReader.read(bytes) in TRANSPOSING_ORIENTATIONS
                val w = if (turned) stored.header.height else stored.header.width
                val h = if (turned) stored.header.width else stored.header.height
                place(stored.fileId, w, h, reduced = bytes !== original)
            } finally {
                busy = false
            }
        }
    }

    /**
     * A phone photo can be 50 megapixels, past what an image may be in the asset store. Such a photo is reduced once, here, to
     * [IMPORT_EDGE_PX] on its longer side (upright, as a JPEG) and the reduced copy is what the sheet stores and references; a photo
     * within the limits is stored byte for byte. Where the platform cannot reduce, the store refuses it with its own message.
     */
    private fun reducedForStore(bytes: ByteArray): ByteArray {
        val header = ImageHeaderProbe.probe(bytes).getOrNull() ?: return bytes
        val over = header.pixels > limits.maxImagePixels || header.width > limits.maxImageSidePx || header.height > limits.maxImageSidePx
        if (!over) return bytes
        return downscaler.downscale(bytes, listOf(IMPORT_EDGE_PX))?.firstOrNull() ?: bytes
    }

    private fun place(fileId: String, widthPx: Int, heightPx: Int, reduced: Boolean) {
        val viewport = controller.ui.viewport
        val canvas = controller.view.canvasSize
        val fit = minOf(canvas.x, canvas.y).takeIf { it > 0.0 } ?: DEFAULT_FIT_PX
        val longer = maxOf(widthPx, heightPx).toDouble()
        val k = fit * FIT_SHARE / viewport.zoom / longer
        val centre = viewport.toScene(ScreenPx(canvas.x / HALF, canvas.y / HALF))
        val placement = UnderlayPlacement(0.0, 0.0, widthPx * k, heightPx * k, 0.0).centredAt(centre.x.value, centre.y.value)
        val applied = controller.commitNow(SetUnderlay(fileId, widthPx, heightPx, placement))
        if (applied.isRight()) controller.notifier.toast(SketchUnderlayStrings.PLACED_TOAST_KEY, if (reduced) SketchUnderlayStrings.placedReduced(IMPORT_EDGE_PX) else SketchUnderlayStrings.PLACED)
    }

    fun remove() {
        endMode()
        controller.commitNow(RemoveUnderlay)
    }

    // ---- lock and opacity (the underlay layer's own)

    fun setLocked(locked: Boolean) {
        if (locked) endMode()
        controller.commitNow(SetLayerLocked(UnderlayIds.LAYER, locked))
    }

    fun setOpacity(opacity: Double) {
        controller.commitNow(SetLayerOpacity(UnderlayIds.LAYER, opacity))
    }

    // ---- move, turn, scale

    fun startAdjust() {
        val s = state ?: return
        if (s.locked) return
        reset()
        mode = UnderlayMode.Adjusting
        controller.tools.beginUnderlay(UnderlayAdjustTool())
    }

    // ---- calibrate

    fun startCalibrate(addReference: Boolean = false) {
        if (state == null) return
        reset()
        addingReference = addReference
        mode = UnderlayMode.Calibrating
        controller.tools.beginUnderlay(UnderlayCalibrateTool())
    }

    /** Done or Cancel: the tool goes back to Select and the length field closes. */
    fun endMode() {
        if (mode == UnderlayMode.Idle) return
        val adjusting = mode == UnderlayMode.Adjusting
        controller.tools.select(emptyList())
        reset()
        // A photo left unlocked would take presses meant for the drawing; moving it is over, so it goes back under lock.
        if (adjusting) controller.commitNow(SetLayerLocked(UnderlayIds.LAYER, true))
    }

    private fun reset() {
        mode = UnderlayMode.Idle
        pending = null
        error = null
        addingReference = false
    }

    /** What the host does with the underlay tools' own commands: they never reach the document directly except an adjust. */
    fun intercept(command: ToolCommand): ToolCommand? = when (command) {
        is CalibrationPointsCommand -> {
            pending = command
            error = null
            null
        }
        EndUnderlayModeCommand -> {
            val adjusting = mode == UnderlayMode.Adjusting
            reset()
            if (adjusting) controller.commitNow(SetLayerLocked(UnderlayIds.LAYER, true))
            null
        }
        is AdjustUnderlayCommand -> command
        else -> command
    }

    /** Applies the typed length [text] to the pending pair. A bad length keeps the field open with a message. */
    fun apply(text: String) {
        val ask = pending ?: return
        val typed = DimensionInput.interpret(text).getOrNull()
        if (typed == null) {
            error = SketchUnderlayStrings.NOT_A_LENGTH
            return
        }
        val command = CalibrateUnderlay(ask.a.x.value, ask.a.y.value, ask.b.x.value, ask.b.y.value, typed.inches.sixteenths, level, addingReference)
        controller.commitNow(command).fold(
            { failure -> error = SketchUnderlayStrings.calibrationFailed(failure.toString()) },
            {
                controller.notifier.toast(SketchUnderlayStrings.CALIBRATED_TOAST_KEY, noteText())
                endMode()
            },
        )
    }

    /** The calibration note as the bar and the canvas label show it, in the sheet's length notation. */
    fun noteText(): String {
        val s = state
        val residual = (UnderlayCalibration.residual(s?.meta?.refs.orEmpty()) * PERCENT_SCALE).roundToInt()
        return UnderlayNoteText.of(CalibrationNote.of(s), format(), residual)
    }

    companion object {
        private val PHOTO_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

        /** EXIF orientations 5 to 8 turn the picture a quarter turn, so width and height swap. */
        private val TRANSPOSING_ORIENTATIONS = 5..8

        /** Longer side of a photo too big for the asset store: 3840 x 2880 is 11 megapixels, and even a square one (14.7 MP) stays under the store's pixel limit. */
        const val IMPORT_EDGE_PX = 3840
        private const val FIT_SHARE = 0.8
        private const val DEFAULT_FIT_PX = 800.0
        private const val HALF = 2.0
        private const val PERCENT_SCALE = 100.0
    }
}
