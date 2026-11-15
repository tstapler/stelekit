// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ValueSource
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import dev.stapler.stelekit.repository.SqlDelightMeasurementAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.2 (I-E3-05): `value_source` and `value_original_meters` are added additively, on fresh and
 * upgraded databases; the plan has no column drop, so "down" means the pre-change statements still work.
 */
@OptIn(DirectRepositoryWrite::class)
class MeasurementValueMigrationTest {
    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setUp() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    }

    @AfterTest
    fun tearDown() = driver.close()

    private fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun <T> query(sql: String, map: (SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val out = mutableListOf<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        }, 0).value

    private fun columns(): List<String> = query("PRAGMA table_info(measurement_annotations)") { it.getString(1)!! }

    private fun columnDdl(name: String) =
        query("SELECT type, dflt_value, \"notnull\" FROM pragma_table_info('measurement_annotations') WHERE name = '$name'") {
            Triple(it.getString(0), it.getString(1), it.getLong(2))
        }.single()

    private fun insertImage() = runBlocking {
        SqlDelightImageAnnotationRepository(SteleDatabase(driver)).saveImageAnnotation(
            ImageAnnotation(
                uuid = "img1aaaa", blockUuid = "blk1aaaa", pageUuid = "pg1", graphPath = "/g", filePath = "/g/a.jpg",
                calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
            ),
        )
    }

    /** Rebuilds the pre-3.2 state: no new columns, no migration records, one legacy row. */
    private fun downgrade() {
        insertImage()
        exec("ALTER TABLE measurement_annotations DROP COLUMN value_source")
        exec("ALTER TABLE measurement_annotations DROP COLUMN value_original_meters")
        exec("DELETE FROM schema_migrations WHERE name IN ('$SOURCE_MIGRATION', '$ORIGINAL_MIGRATION')")
        exec(
            "INSERT INTO measurement_annotations (uuid, image_uuid, annotation_type, normalized_points, value_meters) " +
                "VALUES ('legacyaa', 'img1aaaa', 'DISTANCE', '[{\"x\":0.1,\"y\":0.1},{\"x\":0.5,\"y\":0.1}]', 0.8741)",
        )
    }

    @Test
    fun freshDatabase_should_DeclareBothColumnsWithDefaults() {
        assertTrue("value_source" in columns() && "value_original_meters" in columns())
        assertEquals(Triple("TEXT", "'COMPUTED'", 1L), columnDdl("value_source"))
        assertEquals(Triple("REAL", null, 0L), columnDdl("value_original_meters"))
    }

    @Test
    fun migration_should_be_reversible() = runBlocking {
        downgrade()
        assertTrue("value_source" !in columns())

        MigrationRunner.applyAll(driver)

        assertTrue("value_source" in columns() && "value_original_meters" in columns())
        assertEquals(Triple("TEXT", "'COMPUTED'", 1L), columnDdl("value_source"))
        val repo = SqlDelightMeasurementAnnotationRepository(SteleDatabase(driver))
        val legacy = repo.getMeasurementsForImage("img1aaaa").first().getOrNull()!!.single()
        assertEquals(MeasurementValue.Computed(0.8741), legacy.value)
        assertEquals(ValueSource.COMPUTED, legacy.valueSource)

        // Second run is a no-op (idempotent).
        MigrationRunner.applyAll(driver)
        assertEquals(1, columns().count { it == "value_source" })

        // "Down": the pre-change statements (explicit pre-3.2 column list) still read and write the table.
        exec(
            "INSERT OR REPLACE INTO measurement_annotations (uuid, image_uuid, annotation_type, normalized_points, " +
                "value_meters, value_display, label, color_hex, ble_device_id) " +
                "VALUES ('oldwrite', 'img1aaaa', 'DISTANCE', '[{\"x\":0.1,\"y\":0.1},{\"x\":0.5,\"y\":0.1}]', 1.0, NULL, NULL, '#FF0000', NULL)",
        )
        val oldRead = query(
            "SELECT uuid, image_uuid, annotation_type, normalized_points, value_meters, value_display, label, " +
                "color_hex, ble_device_id FROM measurement_annotations WHERE uuid = 'oldwrite'",
        ) { it.getString(0) }
        assertEquals(listOf("oldwrite"), oldRead)
        val written = repo.getMeasurementsForImage("img1aaaa").first().getOrNull()!!.first { it.uuid == "oldwrite" }
        assertEquals(ValueSource.COMPUTED, written.valueSource)
    }

    @Test
    fun insertStub_should_RoundTripBothNewColumns() = runBlocking {
        insertImage()
        val repo = SqlDelightMeasurementAnnotationRepository(SteleDatabase(driver))
        val pts = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1))
        val overridden = MeasurementAnnotation("ovr1aaaa", "img1aaaa", AnnotationType.DISTANCE, pts)
            .withValue(MeasurementValue.Overridden(0.90, 0.8741))
        repo.saveMeasurementAnnotation(overridden)
        val read = repo.getMeasurementsForImage("img1aaaa").first().getOrNull()!!.single()
        assertEquals(MeasurementValue.Overridden(0.90, 0.8741), read.value)
        assertEquals(overridden, read)
    }

    private companion object {
        const val SOURCE_MIGRATION = "measurement_annotations_value_source"
        const val ORIGINAL_MIGRATION = "measurement_annotations_value_original_meters"
    }
}
