package dev.stapler.stelekit.annotate

import androidx.compose.ui.graphics.ImageBitmap
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationExporter
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.ImageEncoder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM integration tests for [AnnotationExporter] and [ImageEncoder].
 *
 * Verifies that baking annotations produces a non-empty JPEG byte array and that
 * the exported image can be decoded back to check dimensions.
 */
class AnnotationExporterTest {

    private fun makeTestAnnotations(): List<MeasurementAnnotation> = listOf(
        MeasurementAnnotation(
            uuid = "meas-001",
            imageUuid = "img-001",
            annotationType = AnnotationType.DISTANCE,
            normalizedPoints = listOf(
                NormalizedPoint(0.1, 0.5),
                NormalizedPoint(0.9, 0.5),
            ),
            valueMeters = 2.5,
            valueDisplay = "2.5 m",
        ),
        MeasurementAnnotation(
            uuid = "meas-002",
            imageUuid = "img-001",
            annotationType = AnnotationType.AREA,
            normalizedPoints = listOf(
                NormalizedPoint(0.2, 0.2),
                NormalizedPoint(0.8, 0.2),
                NormalizedPoint(0.8, 0.8),
                NormalizedPoint(0.2, 0.8),
            ),
            valueMeters = 9.0,
            valueDisplay = "9.0 m²",
        ),
    )

    @Test
    fun bakeAnnotations_returnsNonEmptyBitmap() {
        val source = ImageBitmap(400, 300)
        val measurements = makeTestAnnotations()

        val result = AnnotationExporter.bakeAnnotations(source, measurements)

        assertEquals(400, result.width)
        assertEquals(300, result.height)
    }

    @Test
    fun bakeAndEncode_returnsNonEmptyByteArray() {
        val source = ImageBitmap(400, 300)
        val measurements = makeTestAnnotations()

        val bytes = AnnotationExporter.bakeAndEncode(source, measurements, quality = 85)

        assertTrue(bytes.isNotEmpty(), "Exported JPEG must be non-empty")
    }

    @Test
    fun bakeAndEncode_emptyMeasurements_stillProducesJpeg() {
        val source = ImageBitmap(200, 150)

        val bytes = AnnotationExporter.bakeAndEncode(source, emptyList(), quality = 90)

        assertTrue(bytes.isNotEmpty(), "Even without annotations, JPEG export must succeed")
    }

    @Test
    fun bakeAndEncode_jpegStartsWithExpectedMagicBytes() {
        val source = ImageBitmap(100, 100)
        val bytes = AnnotationExporter.bakeAndEncode(source, emptyList())

        // JPEG files start with FF D8
        assertTrue(bytes.size >= 2, "JPEG output must have at least 2 bytes")
        assertEquals(0xFF.toByte(), bytes[0], "First byte must be 0xFF (JPEG SOI marker)")
        assertEquals(0xD8.toByte(), bytes[1], "Second byte must be 0xD8 (JPEG SOI marker)")
    }

    @Test
    fun viewModelThenExport_endToEnd() {
        // Simulate creating annotations via ViewModel and exporting them
        val repo = InMemoryMeasurementAnnotationRepository()
        val vm = AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 400.0,
            imageHeightPx = 300.0,
        )

        val image = ImageAnnotation(
            uuid = "img-001",
            blockUuid = "blk-001",
            pageUuid = "page-001",
            graphPath = "/test",
            filePath = "/test/image.jpg",
            calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, pixelsPerMeter = 100.0, confidencePercent = 100),
            unit = MeasurementUnit.METERS,
        )
        vm.initialize(image)
        vm.updateCalibration(Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100))

        // Add two line annotations
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.9, 0.5))

        vm.addPoint(NormalizedPoint(0.5, 0.1))
        vm.addPoint(NormalizedPoint(0.5, 0.9))

        val annotations = vm.state.value.committedAnnotations
        assertEquals(2, annotations.size)

        // Export
        val source = ImageBitmap(400, 300)
        val bytes = AnnotationExporter.bakeAndEncode(source, annotations)
        assertTrue(bytes.isNotEmpty())
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }
}
