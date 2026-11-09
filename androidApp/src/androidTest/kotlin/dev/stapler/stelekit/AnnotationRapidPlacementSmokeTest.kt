// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Crash-class check for `android-smoke` (API 29, `-memory 2048`): 100 rapid presses, press positions
 * sweeping the whole canvas including its edges, must leave no uncaught exception and no editor
 * error. Pass or fail only; the CPU and memory numbers belong to the benchmark job (plan B.6b).
 */
@RunWith(AndroidJUnit4::class)
class AnnotationRapidPlacementSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun hundredRapidPresses_leaveNoUncaughtExceptionAndNoEditorError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SteleKitContext.init(context)
        val file = File(context.cacheDir, "synthetic-smoke-${System.nanoTime()}.png")
        Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val image = ImageAnnotation(
            uuid = "synthetic-smoke", blockUuid = "synthetic-smoke-blk", pageUuid = "synthetic-smoke-page",
            graphPath = "/synthetic", filePath = "file://${file.absolutePath}",
            calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), unit = MeasurementUnit.METERS,
        )
        val vm = AnnotationEditorViewModel(measurementRepository = InMemoryMeasurementAnnotationRepository()).also { it.initialize(image) }
        vm.selectTool(AnnotationTool.DISTANCE)

        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = image) }
            val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
            rule.waitUntil(timeoutMillis = CANVAS_TIMEOUT_MS) { canvas.fetchSemanticsNode().size.height > 0 }
            val size = canvas.fetchSemanticsNode().size
            repeat(PRESSES) { i ->
                val x = size.width * (EDGE + (1f - 2 * EDGE) * ((i * 37) % 100) / 99f)
                val y = size.height * (EDGE + (1f - 2 * EDGE) * ((i * 53) % 100) / 99f)
                canvas.performTouchInput { down(Offset(x, y)); up() }
            }
            rule.waitForIdle()
            vm.state.value.let { assertNull("editor error: ${it.error}", it.error) }
            assertTrue("uncaught: $uncaught", uncaught.isEmpty())
            assertEquals("every pair of presses commits one distance", PRESSES / 2, vm.state.value.committedAnnotations.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            vm.close()
            file.delete()
        }
    }

    private companion object {
        const val PRESSES = 100
        const val IMG_W = 1200
        const val IMG_H = 800
        const val EDGE = 0.01f
        const val CANVAS_TIMEOUT_MS = 20_000L
    }
}
