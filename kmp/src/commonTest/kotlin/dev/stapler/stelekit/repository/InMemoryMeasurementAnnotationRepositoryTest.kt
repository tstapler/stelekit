package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
class InMemoryMeasurementAnnotationRepositoryTest {

    private fun repo() = InMemoryMeasurementAnnotationRepository()

    private fun measurement(uuid: String = "meas-001", imageUuid: String = "img-001") = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = imageUuid,
        annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.9, 0.9)),
    )

    @Test
    fun saveMeasurements_should_returnRight_when_listIsNonEmpty() = runBlocking {
        val r = repo()
        val measurements = listOf(
            measurement("meas-001"),
            measurement("meas-002"),
            measurement("meas-003"),
        )
        val result = r.saveMeasurements("img-001", measurements)
        assertIs<Either.Right<Unit>>(result)

        val fetched = r.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(fetched)
        assertEquals(3, fetched.value.size)
    }

    @Test
    fun deleteMeasurementsForImage_should_cascadeDelete_when_imageUuidProvided() = runBlocking {
        val r = repo()
        r.saveMeasurements("img-001", listOf(measurement("m1"), measurement("m2")))
        r.deleteMeasurementsForImage("img-001")

        val result = r.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun saveMeasurements_should_replaceExisting_when_calledTwice() = runBlocking {
        val r = repo()
        r.saveMeasurements("img-001", listOf(measurement("m1"), measurement("m2")))
        r.saveMeasurements("img-001", listOf(measurement("m3")))

        val result = r.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(result)
        assertEquals(1, result.value.size)
        assertEquals("m3", result.value[0].uuid)
    }

    @Test
    fun deleteMeasurementAnnotation_should_removeOnlyTargetMeasurement() = runBlocking {
        val r = repo()
        r.saveMeasurements("img-001", listOf(measurement("m1"), measurement("m2")))
        r.deleteMeasurementAnnotation("m1")

        val result = r.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(result)
        assertEquals(1, result.value.size)
        assertEquals("m2", result.value[0].uuid)
    }
}
