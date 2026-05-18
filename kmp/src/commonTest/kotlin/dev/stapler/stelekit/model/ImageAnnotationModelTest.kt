package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageAnnotationModelTest {

    private fun sampleAnnotation(uuid: String = "abc123") = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/tmp/graph",
        filePath = "/tmp/graph/assets/images/2026-05-16-abc12345.jpg",
    )

    @Test
    fun imageAnnotation_should_constructWithDefaults_when_minimalFieldsProvided() {
        val ann = sampleAnnotation()
        assertEquals(CalibrationMethod.NONE, ann.calibration.method)
        assertEquals(MeasurementUnit.METERS, ann.unit)
        assertEquals(emptyList(), ann.tags)
    }

    @Test
    fun imageAnnotation_should_rejectBlankUuid_when_uuidIsEmpty() {
        assertFailsWith<IllegalArgumentException> {
            ImageAnnotation(
                uuid = "",
                blockUuid = "blk-001",
                pageUuid = "page-001",
                graphPath = "/tmp/graph",
                filePath = "/tmp/image.jpg",
            )
        }
    }

    @Test
    fun imageAnnotation_should_rejectBlankGraphPath() {
        assertFailsWith<IllegalArgumentException> {
            ImageAnnotation(
                uuid = "abc123",
                blockUuid = "blk-001",
                pageUuid = "page-001",
                graphPath = "",
                filePath = "/tmp/image.jpg",
            )
        }
    }

    @Test
    fun calibration_should_holdDefaultValues_when_constructedWithNoArgs() {
        val cal = Calibration()
        assertEquals(CalibrationMethod.NONE, cal.method)
        assertEquals(0.0, cal.pixelsPerMeter)
        assertEquals(0, cal.confidencePercent)
    }

    @Test
    fun normalizedPoint_should_rejectOutOfRange_when_xExceedsOne() {
        assertFailsWith<IllegalArgumentException> {
            NormalizedPoint(1.5, 0.5)
        }
    }

    @Test
    fun normalizedPoint_should_rejectNegative_when_yIsNegative() {
        assertFailsWith<IllegalArgumentException> {
            NormalizedPoint(0.5, -0.1)
        }
    }
}
