// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ValueSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** I-E3-07: an override persists across reload and re-save; illegal column pairs normalise on read. */
@OptIn(DirectRepositoryWrite::class)
class MeasurementOverridePersistenceTest {
    private val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val database = SteleDatabase(driver)
    private val repo = SqlDelightMeasurementAnnotationRepository(database)
    private val PTS = "[{\"x\":0.1,\"y\":0.1},{\"x\":0.5,\"y\":0.1}]"
    private val points = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1))

    private fun image() = ImageAnnotation(
        uuid = "img1aaaa", blockUuid = "blk1aaaa", pageUuid = "pg1", graphPath = "/g", filePath = "/g/a.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
    )

    private fun measurement(uuid: String, meters: Double) =
        MeasurementAnnotation(uuid, "img1aaaa", AnnotationType.DISTANCE, points, valueMeters = meters)

    private suspend fun read() = repo.getMeasurementsForImage("img1aaaa").first().getOrNull()!!.associateBy { it.uuid }

    @Test
    fun override_should_PersistAndSurviveRecalibration_AcrossReload() = runBlocking {
        SqlDelightImageAnnotationRepository(database).saveImageAnnotation(image())
        val overridden = measurement("ovr1aaaa", 0.90).withValue(MeasurementValue.Overridden(0.90, 0.8741))
        val computed = measurement("cmp1aaaa", 1.0)
        repo.saveMeasurements("img1aaaa", listOf(overridden, computed))

        // A recalibration re-saves every measurement; the caller keeps the override and recomputes the others.
        val reloaded = read()
        repo.saveMeasurements("img1aaaa", listOf(reloaded.getValue("ovr1aaaa"), reloaded.getValue("cmp1aaaa").copy(valueMeters = 1.04)))

        val after = read()
        assertEquals(MeasurementValue.Overridden(0.90, 0.8741), after.getValue("ovr1aaaa").value)
        assertEquals(MeasurementValue.Computed(1.04), after.getValue("cmp1aaaa").value)
    }

    @Test
    fun illegalColumnPairs_should_NormaliseOnRead() = runBlocking {
        SqlDelightImageAnnotationRepository(database).saveImageAnnotation(image())
        val sql = "INSERT INTO measurement_annotations (uuid, image_uuid, annotation_type, normalized_points, " +
            "value_meters, value_source, value_original_meters) VALUES "
        driver.execute(null, sql + "('bad1aaaa', 'img1aaaa', 'DISTANCE', '"+PTS+"', 1.0, 'COMPUTED', 0.5)", 0).value
        driver.execute(null, sql + "('bad2aaaa', 'img1aaaa', 'DISTANCE', '"+PTS+"', 0.9, 'USER_OVERRIDE', NULL)", 0).value
        driver.execute(null, sql + "('bad3aaaa', 'img1aaaa', 'DISTANCE', '"+PTS+"', 0.9, 'SOMETHING_ELSE', 0.5)", 0).value

        val rows = read()
        assertEquals(MeasurementValue.Computed(1.0), rows.getValue("bad1aaaa").value)
        assertEquals(null, rows.getValue("bad1aaaa").originalValueMeters)
        assertEquals(MeasurementValue.Overridden(0.9, 0.9), rows.getValue("bad2aaaa").value)
        assertEquals(ValueSource.COMPUTED, rows.getValue("bad3aaaa").valueSource)
    }
}
