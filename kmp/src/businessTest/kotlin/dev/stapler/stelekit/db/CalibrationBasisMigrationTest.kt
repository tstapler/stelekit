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
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 6.2a: `calibration_basis_version` is added additively; legacy rows keep their values and read as basis 0. */
@OptIn(DirectRepositoryWrite::class)
class CalibrationBasisMigrationTest {
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

    private fun <T> query(sql: String, map: (app.cash.sqldelight.db.SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val out = mutableListOf<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        }, 0).value

    private fun hasBasisColumn(): Boolean =
        query("PRAGMA table_info(image_annotations)") { it.getString(1) }.contains(COLUMN)

    /** Rebuilds the pre-6.2a state: no column, no migration record, plus two legacy rows. */
    private fun downgradeToLegacySchema() {
        exec("ALTER TABLE image_annotations DROP COLUMN $COLUMN")
        exec("DELETE FROM schema_migrations WHERE name = '$MIGRATION'")
        listOf("legacyaaa" to 187.5, "legacybbb" to 0.0).forEach { (uuid, ppm) ->
            exec(
                "INSERT INTO image_annotations (uuid, block_uuid, page_uuid, graph_path, file_path, " +
                    "calibration_method, pixels_per_meter, calibration_confidence_pct) " +
                    "VALUES ('$uuid', 'blk$uuid', 'pg1', '/g', '/g/$uuid.jpg', 'MANUAL_REFERENCE', $ppm, 100)",
            )
        }
    }

    @Test
    fun freshDatabase_should_DeclareTheColumnWithLegacyDefault() {
        assertTrue(hasBasisColumn())
        val ddl = query("SELECT type, dflt_value, \"notnull\" FROM pragma_table_info('image_annotations') WHERE name = '$COLUMN'") {
            Triple(it.getString(0), it.getString(1), it.getLong(2))
        }.single()
        assertEquals(Triple("INTEGER", "0", 1L), ddl)
    }

    @Test
    fun applyAll_should_AddColumnAndLeaveLegacyRowsUnchanged_When_UpgradingFromPre62a() = runBlocking {
        downgradeToLegacySchema()
        assertTrue(!hasBasisColumn())

        MigrationRunner.applyAll(driver)

        assertTrue(hasBasisColumn())
        val rows = query("SELECT uuid, pixels_per_meter, calibration_method, calibration_confidence_pct, $COLUMN FROM image_annotations ORDER BY uuid") {
            listOf(it.getString(0), it.getDouble(1), it.getString(2), it.getLong(3), it.getLong(4))
        }
        assertEquals(
            listOf(
                listOf("legacyaaa", 187.5, "MANUAL_REFERENCE", 100L, 0L),
                listOf("legacybbb", 0.0, "MANUAL_REFERENCE", 100L, 0L),
            ),
            rows,
        )
    }

    @Test
    fun applyAll_should_BeIdempotent_When_ColumnAlreadyPresent() = runBlocking {
        exec("DELETE FROM schema_migrations WHERE name = '$MIGRATION'")
        MigrationRunner.applyAll(driver)
        MigrationRunner.applyAll(driver)
        assertEquals(1, query("PRAGMA table_info(image_annotations)") { it.getString(1) }.count { it == COLUMN })
    }

    @Test
    fun legacyRow_should_ReadAsLegacyBasis_And_SurviveARewriteThroughTheModel() = runBlocking {
        downgradeToLegacySchema()
        MigrationRunner.applyAll(driver)
        val repo = SqlDelightImageAnnotationRepository(SteleDatabase(driver))

        val legacy = repo.getImageAnnotationByUuid("legacyaaa").first().getOrNull()!!
        assertEquals(CalibrationBasis.LEGACY_DECODE_RELATIVE, legacy.calibration.basisVersion)
        assertTrue(legacy.calibration.isLegacyBasis)
        assertEquals(187.5, legacy.calibration.pixelsPerMeter)

        repo.saveImageAnnotation(legacy.copy(tags = listOf("t")))
        val again = repo.getImageAnnotationByUuid("legacyaaa").first().getOrNull()!!
        assertEquals(CalibrationBasis.LEGACY_DECODE_RELATIVE, again.calibration.basisVersion)
        assertEquals(187.5, again.calibration.pixelsPerMeter)
    }

    @Test
    fun newCalibration_should_PersistItsBasis() = runBlocking {
        val repo = SqlDelightImageAnnotationRepository(SteleDatabase(driver))
        repo.saveImageAnnotation(annotation("nativeaaa", CalibrationBasis.NATIVE_PIXELS))
        repo.saveImageAnnotation(annotation("legacyccc", CalibrationBasis.LEGACY_DECODE_RELATIVE))

        assertEquals(CalibrationBasis.NATIVE_PIXELS, repo.getImageAnnotationByUuid("nativeaaa").first().getOrNull()!!.calibration.basisVersion)
        assertEquals(CalibrationBasis.LEGACY_DECODE_RELATIVE, repo.getImageAnnotationByUuid("legacyccc").first().getOrNull()!!.calibration.basisVersion)
    }

    @Test
    fun sidecar_should_CarryTheMarker_And_ReadOlderSidecarsAsLegacy() {
        val fs = FakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val notes = EffectiveNotesPath("/g")
        manager.writeSidecar(annotation("nativebbb", CalibrationBasis.NATIVE_PIXELS), emptyList())

        val written = fs.readFile(manager.sidecarPath(notes, "nativebbb"))!!
        assertTrue("\"calibrationBasisVersion\":1" in written, written)
        val read = manager.readSidecar(notes, "nativebbb").getOrNull()!!
        assertEquals(CalibrationBasis.NATIVE_PIXELS, read.imageAnnotation.calibrationBasisVersion)

        // A pre-6.2b sidecar has no marker key at all.
        val older = written.replace(",\"calibrationBasisVersion\":1", "")
        assertTrue("calibrationBasisVersion" !in older)
        fs.writeFile(manager.sidecarPath(notes, "nativebbb"), older)
        val legacy = manager.readSidecar(notes, "nativebbb").getOrNull()!!
        assertEquals(CalibrationBasis.LEGACY_DECODE_RELATIVE, legacy.imageAnnotation.calibrationBasisVersion)
    }

    private fun annotation(uuid: String, basis: Int) = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk$uuid",
        pageUuid = "pg1",
        graphPath = "/g",
        filePath = "/g/$uuid.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100, basis),
    )

    private companion object {
        const val COLUMN = "calibration_basis_version"
        const val MIGRATION = "image_annotations_calibration_basis_version"
    }
}
