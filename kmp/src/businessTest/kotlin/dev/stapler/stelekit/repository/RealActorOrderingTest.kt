// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Measurement writes through a real [DatabaseWriteActor] with LOW-priority graph-load traffic in
 * between: submission order must survive, and an undone row must not come back.
 */
@OptIn(DirectRepositoryWrite::class)
class RealActorOrderingTest {
    private val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
    private val repo = SqlDelightMeasurementAnnotationRepository(database, MeasurementWrites(actor))

    private fun row(uuid: String) = MeasurementAnnotation(
        uuid = uuid, imageUuid = "img-1", annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1)),
    )

    private suspend fun uuids(): List<String> =
        (repo.getMeasurementsForImage("img-1").first() as Either.Right).value.map { it.uuid }.sorted()

    private fun <T> withActor(block: suspend () -> T): T = try { runBlocking { block() } } finally { actor.close() }

    private fun kotlinx.coroutines.CoroutineScope.submit(op: suspend () -> Either<*, Unit>): Deferred<Any?> =
        async(start = CoroutineStart.UNDISPATCHED) { op() }

    private suspend fun lowTraffic(log: MutableList<String>, n: Int) =
        actor.execute(DatabaseWriteActor.Priority.LOW, false) { log += "low$n"; Unit.right() }

    @Test
    fun undoneRow_should_StayGone_When_CommitUndoCommitAreInterleavedWithLowTraffic() = withActor {
        SqlDelightImageAnnotationRepository(database).saveImageAnnotation(
            ImageAnnotation(
                uuid = "img-1", blockUuid = "b", pageUuid = "p", graphPath = "/g", filePath = "/g/a.jpg",
                calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), unit = MeasurementUnit.METERS,
            ),
        )
        val log = mutableListOf<String>()
        kotlinx.coroutines.coroutineScope {
            val jobs = listOf(
                submit { lowTraffic(log, 1) },
                submit { repo.saveMeasurementAnnotation(row("x")) },
                submit { lowTraffic(log, 2) },
                submit { repo.deleteMeasurementAnnotation("x") },
                submit { lowTraffic(log, 3) },
                submit { repo.saveMeasurementAnnotation(row("y")) },
                submit { lowTraffic(log, 4) },
                submit { repo.saveMeasurements("img-1", listOf(row("y"), row("z"))) },
                submit { lowTraffic(log, 5) },
            )
            jobs.awaitAll()
        }
        assertEquals(listOf("y", "z"), uuids())
        assertEquals(listOf("low1", "low2", "low3", "low4", "low5"), log)
    }

    @Test
    fun rows_should_MatchSubmissionOrder_When_ManyBulkRewritesRace() = withActor {
        SqlDelightImageAnnotationRepository(database).saveImageAnnotation(
            ImageAnnotation(
                uuid = "img-1", blockUuid = "b", pageUuid = "p", graphPath = "/g", filePath = "/g/a.jpg",
                calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), unit = MeasurementUnit.METERS,
            ),
        )
        kotlinx.coroutines.coroutineScope {
            (1..20).map { i -> submit { repo.saveMeasurements("img-1", listOf(row("r$i"))) } }.awaitAll()
        }
        assertEquals(listOf("r20"), uuids())
    }
}
