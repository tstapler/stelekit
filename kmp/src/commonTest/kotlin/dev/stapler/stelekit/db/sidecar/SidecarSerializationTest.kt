package dev.stapler.stelekit.db.sidecar

import arrow.core.Either
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SidecarSerializationTest {

    private fun fakeFileSystem(): FakeFileSystem = FakeFileSystem()

    private fun sampleAnnotation(
        uuid: String = "ann-abc123",
        calibrationMethod: CalibrationMethod = CalibrationMethod.MANUAL_REFERENCE,
        pixelsPerMeter: Double = 150.0,
    ) = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/graph",
        filePath = "/graph/assets/images/test.jpg",
        calibration = Calibration(
            method = calibrationMethod,
            pixelsPerMeter = pixelsPerMeter,
            confidencePercent = 100,
        ),
        unit = MeasurementUnit.METERS,
        tags = listOf("site-A", "indoor"),
        sensorData = ImageSensorData(
            latLng = 49.2827 to 123.1207,
            altitudeM = 150.0,
            bearingDeg = 273.0,
            pitchDeg = 5.0,
            rollDeg = 2.0,
            focalLengthMm = 4.7,
            focalLength35mmEq = 28.0,
            cameraMake = "Google",
            cameraModel = "Pixel 8",
        ),
    )

    private fun pointsForType(type: AnnotationType): List<NormalizedPoint> = when (type) {
        AnnotationType.DISTANCE -> listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.5, 0.6))
        AnnotationType.ANGLE -> listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.5, 0.6), NormalizedPoint(0.9, 0.2))
        AnnotationType.AREA -> listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.9, 0.1), NormalizedPoint(0.5, 0.9))
        AnnotationType.LABEL, AnnotationType.GRID_REF -> listOf(NormalizedPoint(0.5, 0.5))
    }

    private fun measurementsForAllTypes(imageUuid: String): List<MeasurementAnnotation> =
        AnnotationType.entries.mapIndexed { i, type ->
            MeasurementAnnotation(
                uuid = "meas-00$i",
                imageUuid = imageUuid,
                annotationType = type,
                normalizedPoints = pointsForType(type),
                valueMeters = (i + 1) * 1.5,
                valueDisplay = "${(i + 1) * 1.5} m",
                label = "Measurement $i",
            )
        }

    @Test
    fun writeSidecar_should_serializeAllAnnotationTypes_when_roundTripped() {
        val fs = fakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val ann = sampleAnnotation()
        val measurements = measurementsForAllTypes(ann.uuid)

        val writeResult = manager.writeSidecar(ann, measurements)
        assertIs<Either.Right<Unit>>(writeResult)

        val readResult = manager.readSidecar("/graph", ann.uuid)
        assertIs<Either.Right<SidecarFile?>>(readResult)
        val sidecar = readResult.value
        assertNotNull(sidecar)

        // All annotation types must survive the round-trip
        val types = sidecar.measurements.map { it.annotationType }
        for (expected in AnnotationType.entries) {
            assertTrue(expected.name in types, "Missing AnnotationType: $expected")
        }
        assertEquals(measurements.size, sidecar.measurements.size)
    }

    @Test
    fun readSidecar_should_returnLeft_when_jsonIsMalformed() {
        val fs = fakeFileSystem()
        fs.writeFile("/graph/.stelekit/images/ann-abc123.measure.json", "NOT_JSON{{{")
        val manager = ImageSidecarManager(fs)
        val result = manager.readSidecar("/graph", "ann-abc123")
        assertIs<Either.Left<*>>(result)
    }

    @Test
    fun sidecarVersion_should_bePreserved_when_deserializingV1Schema() {
        val v1Json = """
            {
              "schemaVersion": 1,
              "imageAnnotation": {
                "uuid": "ann-v1test",
                "blockUuid": "blk-001",
                "pageUuid": "page-001",
                "graphPath": "/graph",
                "filePath": "/graph/assets/images/test.jpg"
              },
              "measurements": []
            }
        """.trimIndent()
        val fs = fakeFileSystem()
        fs.writeFile("/graph/.stelekit/images/ann-v1test.measure.json", v1Json)
        val manager = ImageSidecarManager(fs)
        val result = manager.readSidecar("/graph", "ann-v1test")
        assertIs<Either.Right<SidecarFile?>>(result)
        assertEquals(1, result.value?.schemaVersion)
    }

    @Test
    fun measurementValues_should_beReproducible_when_calibrationDataUnchanged() {
        val fs = fakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val ann = sampleAnnotation(pixelsPerMeter = 200.0)
        val measurements = listOf(
            MeasurementAnnotation(
                uuid = "meas-001",
                imageUuid = ann.uuid,
                annotationType = AnnotationType.DISTANCE,
                normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.9, 0.9)),
                valueMeters = 3.14,
            )
        )
        manager.writeSidecar(ann, measurements)
        val roundTripped = manager.readSidecar("/graph", ann.uuid)
        assertIs<Either.Right<SidecarFile?>>(roundTripped)
        val loaded = roundTripped.value?.measurements?.first()
        assertNotNull(loaded)
        assertEquals(3.14, loaded.valueMeters)
    }
}

// FakeFileSystem is defined in FakeFileSystem.kt in the same package.
