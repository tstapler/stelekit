// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.GeometryCalibrationCodec
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Height offset persists additively (column + sidecar key); existing rows and older sidecars are untouched. */
@OptIn(DirectRepositoryWrite::class)
class CalibrationGeometryPersistenceTest {
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

    private val geometry = GeometryCalibration(heightOffsetMeters = 0.0076, cameraDistanceMeters = 0.62)

    private fun annotation(uuid: String, calibration: Calibration) = ImageAnnotation(
        uuid = uuid, blockUuid = "blk$uuid", pageUuid = "pg1", graphPath = "/g", filePath = "/g/$uuid.jpg",
        calibration = calibration,
    )

    private val withGeometry =
        Calibration(CalibrationMethod.MANUAL_REFERENCE, 6500.0, 100, CalibrationBasis.NATIVE_PIXELS, geometry = geometry)

    @Test
    fun legacyRow_should_StayIdentical_AfterTheGeometryMigration() = runBlocking {
        exec("ALTER TABLE image_annotations DROP COLUMN calibration_geometry_json")
        exec("DELETE FROM schema_migrations WHERE name = 'image_annotations_calibration_geometry_json'")
        exec(
            "INSERT INTO image_annotations (uuid, block_uuid, page_uuid, graph_path, file_path, calibration_method, " +
                "pixels_per_meter, calibration_confidence_pct) VALUES ('legacyaaa', 'blklegacyaaa', 'pg1', '/g', '/g/l.jpg', " +
                "'MANUAL_REFERENCE', 187.5, 100)",
        )
        MigrationRunner.applyAll(driver)
        val cal = SqlDelightImageAnnotationRepository(SteleDatabase(driver))
            .getImageAnnotationByUuid("legacyaaa").first().getOrNull()!!.calibration
        assertEquals(Calibration(CalibrationMethod.MANUAL_REFERENCE, 187.5, 100, CalibrationBasis.LEGACY_DECODE_RELATIVE), cal)
        assertNull(cal.geometry)
    }

    @Test
    fun geometry_should_RoundTripThroughTheDatabase() = runBlocking {
        val repo = SqlDelightImageAnnotationRepository(SteleDatabase(driver))
        repo.saveImageAnnotation(annotation("geoaaaaa", withGeometry))
        repo.saveImageAnnotation(annotation("plainbbb", withGeometry.copy(geometry = null)))
        assertEquals(withGeometry, repo.getImageAnnotationByUuid("geoaaaaa").first().getOrNull()!!.calibration)
        assertNull(repo.getImageAnnotationByUuid("plainbbb").first().getOrNull()!!.calibration.geometry)
    }

    @Test
    fun geometry_should_RoundTripThroughTheSidecar_And_OlderSidecarsReadAsNone() {
        val fs = FakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val notes = EffectiveNotesPath("/g")
        manager.writeSidecar(annotation("geosidecar", withGeometry), emptyList())
        val path = manager.sidecarPath(notes, "geosidecar")
        assertTrue("calibrationGeometry" in fs.readFile(path)!!)
        assertEquals(geometry, manager.readSidecar(notes, "geosidecar").getOrNull()!!.imageAnnotation.calibrationGeometry)

        manager.writeSidecar(annotation("geosidecar", withGeometry.copy(geometry = null)), emptyList())
        assertTrue("calibrationGeometry" !in fs.readFile(path)!!)
        assertNull(manager.readSidecar(notes, "geosidecar").getOrNull()!!.imageAnnotation.calibrationGeometry)
    }

    @Test
    fun codec_should_DegradeToNone_When_TextIsBlankOrCorrupt() {
        assertEquals("", GeometryCalibrationCodec.encode(null))
        assertNull(GeometryCalibrationCodec.decode(""))
        assertNull(GeometryCalibrationCodec.decode("{not json"))
        assertEquals(geometry, GeometryCalibrationCodec.decode(GeometryCalibrationCodec.encode(geometry)))
        assertEquals(geometry, GeometryCalibrationCodec.decode(GeometryCalibrationCodec.encode(geometry).dropLast(1) + ",\"future\":1}"))
    }
}
