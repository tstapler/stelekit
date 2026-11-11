// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Writes of the SQLDelight measurement repository, with and without the actor in front. */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightMeasurementAnnotationWritesTest {
    private val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val database = SteleDatabase(driver)
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())

    private fun image() = ImageAnnotation(
        uuid = "img-1", blockUuid = "blk-1", pageUuid = "page-1", graphPath = "/g", filePath = "/g/a.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), unit = MeasurementUnit.METERS,
    )

    private fun row(uuid: String) = MeasurementAnnotation(
        uuid = uuid, imageUuid = "img-1", annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1)),
    )

    private suspend fun SqlDelightMeasurementAnnotationRepository.uuids(): List<String> =
        (getMeasurementsForImage("img-1").first() as Either.Right).value.map { it.uuid }.sorted()

    private fun runWith(writes: MeasurementWrites) = runBlocking {
        SqlDelightImageAnnotationRepository(database).saveImageAnnotation(image())
        val repo = SqlDelightMeasurementAnnotationRepository(database, writes)

        repo.saveMeasurementAnnotation(row("a"))
        repo.saveMeasurementAnnotation(row("b"))
        assertEquals(listOf("a", "b"), repo.uuids())

        repo.saveMeasurements("img-1", listOf(row("c"), row("d")))
        assertEquals(listOf("c", "d"), repo.uuids())

        repo.deleteMeasurementAnnotation("c")
        assertEquals(listOf("d"), repo.uuids())

        repo.deleteMeasurementsForImage("img-1")
        assertEquals(emptyList(), repo.uuids())
    }

    @Test
    fun writes_should_ApplyInOrder_When_RoutedThroughTheActor() {
        try { runWith(MeasurementWrites(actor)) } finally { actor.close() }
    }

    @Test
    fun writes_should_ApplyInline_When_NoActor() = runWith(MeasurementWrites(null))

    @Test
    fun write_should_ReturnWriteFailed_When_TheDatabaseIsClosed() = runBlocking {
        val repo = SqlDelightMeasurementAnnotationRepository(database, MeasurementWrites(null))
        driver.close()
        assertIs<DomainError.DatabaseError.WriteFailed>((repo.saveMeasurementAnnotation(row("x")) as Either.Left).value)
        assertIs<DomainError.DatabaseError.WriteFailed>((repo.saveMeasurements("img-1", listOf(row("x"))) as Either.Left).value)
        assertIs<DomainError.DatabaseError.WriteFailed>((repo.deleteMeasurementAnnotation("x") as Either.Left).value)
        assertIs<DomainError.DatabaseError.WriteFailed>((repo.deleteMeasurementsForImage("img-1") as Either.Left).value)
        actor.close()
    }
}
