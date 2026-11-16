// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xxhdpi")
class PlacementCountersLogTest {
    @get:Rule
    val rule = createComposeRule()

    private val img = ImageAnnotation(
        uuid = "img-counter-001",
        blockUuid = "blk-counter-001",
        pageUuid = "page-counter-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
        unit = MeasurementUnit.METERS,
    )

    private val canvasPx = IntSize(1080, 2400)

    private class RecordingSink : LogSink {
        val messages = CopyOnWriteArrayList<String>()
        override fun write(entry: LogEntry, formatted: String) {
            if (entry.tag == "PlacementCounters") messages += entry.message
        }
    }

    @Test
    fun placementGestures_should_LogDragMeasureAimPlaceAndUndoAfterDragMeasureOnce() {
        val vm = AnnotationEditorViewModel(
            measurementRepository = InMemoryMeasurementAnnotationRepository(),
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(img); it.selectTool(AnnotationTool.DISTANCE) }
        val handle = AnnotationViewHandle(
            canvasSize = { canvasPx },
            canvasOrigin = { Offset.Zero },
            view = { AnnotationViewTransform.Fit },
            geometry = { ViewGeometry(1080f, 2400f, 0f, 0f, 1080f, 2400f) },
            setView = {},
        )
        rule.setContent {
            val state by vm.state.collectAsState()
            Box(Modifier.fillMaxSize()) {
                AnnotationGestureLayer(
                    currentTool = state.currentTool,
                    annotations = state.committedAnnotations,
                    handle = handle,
                    callbacks = AnnotationGestureCallbacks(
                        onTap = { p -> p.toNormalized(canvasPx)?.let(vm::addPoint) },
                        onLoupe = {},
                        onRipple = {},
                        onSelect = {},
                        onEditStart = {},
                        onEditPoints = { _, _ -> },
                        onEditEnd = {},
                        onDragMeasure = { s, e ->
                            val a = s.toNormalized(canvasPx)
                            val b = e.toNormalized(canvasPx)
                            if (a != null && b != null) vm.placeDrag(a, b)
                        },
                    ),
                    modifier = Modifier.testTag("layer"),
                    pendingPoints = state.inProgressPoints.size,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("layer").performTouchInput { down(Offset(540f, 1200f)); up() } // first injected gesture is dropped
        rule.waitForIdle()
        vm.cancelInProgress()

        val sink = RecordingSink()
        val handleToSink = LogManager.addSink(sink)
        try {
            // Aim-place: a 30 dp drag then lift.
            rule.onNodeWithTag("layer").performTouchInput { down(Offset(500f, 1000f)); moveTo(Offset(590f, 1000f)); up() }
            rule.waitForIdle()
            vm.cancelInProgress()
            // Drag-measure, then an undo inside the 5 s window.
            rule.onNodeWithTag("layer").performTouchInput {
                down(Offset(200f, 600f)); moveTo(Offset(450f, 1000f)); moveTo(Offset(700f, 1500f)); up()
            }
            rule.waitForIdle()
            vm.undo()
        } finally {
            handleToSink.close()
        }

        assertEquals(
            listOf("placement gesture=aim-place", "placement gesture=drag-measure", "placement undo-after-drag-measure"),
            sink.messages.toList(),
        )
        vm.close()
    }
}
