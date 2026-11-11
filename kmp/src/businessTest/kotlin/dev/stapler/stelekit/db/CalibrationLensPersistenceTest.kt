// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.LensCalibrationCodec
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

/** Basis v2: lens data persists additively (column + sidecar); legacy rows and older sidecars are untouched. */
@OptIn(DirectRepositoryWrite::class)
class CalibrationLensPersistenceTest {
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

    private val lens = LensCalibration(
        k = -0.0123,
        ticks = listOf(CalibrationTick(0.1, 0.8, 0.0), CalibrationTick(0.9, 0.7, 0.3)),
        residualPercent = 0.4,
        kEstimated = true,
    )

    private fun annotation(uuid: String, calibration: Calibration) = ImageAnnotation(
        uuid = uuid, blockUuid = "blk$uuid", pageUuid = "pg1", graphPath = "/g", filePath = "/g/$uuid.jpg",
        calibration = calibration,
    )

    private val v2 = Calibration(CalibrationMethod.MANUAL_REFERENCE, 6500.0, 100, CalibrationBasis.NATIVE_PIXELS_LENS, lens)

    @Test
    fun legacyRow_should_StayByteIdentical_AfterTheLensMigration() = runBlocking {
        exec("ALTER TABLE image_annotations DROP COLUMN calibration_lens_json")
        exec("DELETE FROM schema_migrations WHERE name = 'image_annotations_calibration_lens_json'")
        exec(
            "INSERT INTO image_annotations (uuid, block_uuid, page_uuid, graph_path, file_path, calibration_method, " +
                "pixels_per_meter, calibration_confidence_pct) VALUES ('legacyaaa', 'blklegacyaaa', 'pg1', '/g', '/g/l.jpg', " +
                "'MANUAL_REFERENCE', 187.5, 100)",
        )
        MigrationRunner.applyAll(driver)
        val cal = SqlDelightImageAnnotationRepository(SteleDatabase(driver))
            .getImageAnnotationByUuid("legacyaaa").first().getOrNull()!!.calibration
        assertEquals(Calibration(CalibrationMethod.MANUAL_REFERENCE, 187.5, 100, CalibrationBasis.LEGACY_DECODE_RELATIVE, null), cal)
    }

    @Test
    fun lens_should_RoundTripThroughTheDatabase() = runBlocking {
        val repo = SqlDelightImageAnnotationRepository(SteleDatabase(driver))
        repo.saveImageAnnotation(annotation("lensaaa", v2))
        repo.saveImageAnnotation(annotation("basicbbb", Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100)))
        assertEquals(v2, repo.getImageAnnotationByUuid("lensaaa").first().getOrNull()!!.calibration)
        assertNull(repo.getImageAnnotationByUuid("basicbbb").first().getOrNull()!!.calibration.lens)
    }

    @Test
    fun lens_should_RoundTripThroughTheSidecar_And_OlderSidecarsReadAsNoLens() {
        val fs = FakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val notes = EffectiveNotesPath("/g")
        manager.writeSidecar(annotation("lenssidecar", v2), emptyList())
        val path = manager.sidecarPath(notes, "lenssidecar")
        val written = fs.readFile(path)!!
        assertTrue("\"calibrationBasisVersion\":2" in written, written)
        assertEquals(lens, manager.readSidecar(notes, "lenssidecar").getOrNull()!!.imageAnnotation.calibrationLens)

        // A sidecar without the key (written before basis v2, or by a basic calibration) reads as no lens.
        manager.writeSidecar(annotation("lenssidecar", v2.copy(basisVersion = 1, lens = null)), emptyList())
        assertTrue("calibrationLens" !in fs.readFile(path)!!)
        assertNull(manager.readSidecar(notes, "lenssidecar").getOrNull()!!.imageAnnotation.calibrationLens)
    }

    @Test
    fun codec_should_DegradeToNoLens_When_TextIsBlankOrCorrupt() {
        assertEquals("", LensCalibrationCodec.encode(null))
        assertNull(LensCalibrationCodec.decode(""))
        assertNull(LensCalibrationCodec.decode("{not json"))
        assertEquals(lens, LensCalibrationCodec.decode(LensCalibrationCodec.encode(lens)))
    }
}
