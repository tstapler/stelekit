// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 2.1: the sketch tables, their indexes and unique key, and their migration. */
class SketchMigrationTest {
    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setUp() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun exec(sql: String, vararg args: Any?) {
        driver.execute(null, sql, args.size) {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> bindString(i, null)
                    is Long -> bindLong(i, a)
                    is Int -> bindLong(i, a.toLong())
                    else -> bindString(i, a.toString())
                }
            }
        }.value
    }

    private fun <T> query(sql: String, map: (app.cash.sqldelight.db.SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val out = mutableListOf<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        }, 0).value

    private fun columns(table: String): Map<String, Pair<String, Boolean>> =
        query("PRAGMA table_info($table)") { c -> c.getString(1)!! to (c.getString(2)!! to (c.getLong(3) == 1L)) }.toMap()

    private fun insertProject(uuid: String = "u1", slug: String = "proj-0a1b2c", name: String = "Kitchen") =
        exec(
            "INSERT INTO sketch_projects (uuid, slug, name, created_at, settings_json, fusion_results_json, " +
                "source_hash, last_digest_hash, model_revision, base_revision) VALUES (?, ?, ?, 0, '{}', '{}', 'h', 'd', 1, 1)",
            uuid, slug, name,
        )

    private fun insertDimension(
        uuid: String, sourceRef: String = "r1", precNum: Long? = 1, precDen: Long? = 20,
    ) = exec(
        "INSERT INTO sketch_dimensions (uuid, project_uuid, key, kind, value_num, value_den, precision_num, precision_den, " +
            "source_kind, source_ref, tolerance_num, tolerance_den, locked) VALUES (?, 'u1', 'width', 'length', 379, 10, ?, ?, 'sheet', ?, 0, 1, 0)",
        uuid, precNum, precDen, sourceRef,
    )

    private fun plan(sql: String): String = query("EXPLAIN QUERY PLAN $sql") { it.getString(3) ?: "" }.joinToString("\n")

    @Test
    fun applyAll_should_DeclareEveryColumnTheSidecarReconcileNeeds() {
        val projects = columns("sketch_projects")
        assertEquals(
            setOf(
                "uuid", "slug", "name", "created_at", "settings_json", "fusion_results_json", "source_hash",
                "last_digest_hash", "model_revision", "base_revision", "last_synced_sidecar_hash",
            ),
            projects.keys,
        )
        assertTrue(projects.getValue("base_revision").second, "base_revision is NOT NULL")
        assertEquals(false, projects.getValue("last_synced_sidecar_hash").second, "NULL until the first sync")
        val dims = columns("sketch_dimensions")
        assertEquals(false, dims.getValue("precision_num").second, "a count has no precision")
        assertEquals(false, dims.getValue("precision_den").second)
        assertEquals(
            setOf("uuid", "sheet_uuid", "kind", "x1", "y1", "x2", "y2", "name", "layer", "z"),
            columns("sketch_primitives").keys,
        )
        assertEquals(
            setOf(
                "uuid", "project_uuid", "kind", "view_direction", "unit", "scale_sixteenths_per_unit",
                "source_asset_uuid", "layer_json", "zones_json", "position",
            ),
            columns("sketch_sheets").keys,
        )
    }

    @Test
    fun lastSyncedSidecarHash_should_BeNull_When_ProjectIsNew() {
        insertProject()
        val hash = query("SELECT last_synced_sidecar_hash FROM sketch_projects WHERE uuid = 'u1'") { it.getString(0) }
        assertEquals(listOf<String?>(null), hash)
    }

    @Test
    fun slug_should_RejectNameDerivedValues_When_NotProjSixHex() {
        insertProject(slug = "proj-0a1b2c")
        assertFailsWith<Exception> { insertProject(uuid = "u2", slug = "kitchen", name = "Kitchen") }
        assertFailsWith<Exception> { insertProject(uuid = "u3", slug = "proj-KITCHEN") }
        assertFailsWith<Exception> { insertProject(uuid = "u4", slug = "proj-0a1b2c") } // UNIQUE
    }

    @Test
    fun dimension_should_RoundTripExactly_When_ValueIs37Point9Inches() {
        insertProject()
        insertDimension("d1")
        val row = query("SELECT value_num, value_den, precision_num, precision_den FROM sketch_dimensions WHERE uuid = 'd1'") {
            listOf(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3))
        }.single()
        assertEquals(listOf(379L, 10L, 1L, 20L), row)
    }

    @Test
    fun dimension_should_AllowNullPrecision_When_ValueIsACount() {
        insertDimension("d1", precNum = null, precDen = null)
        val precision = query("SELECT precision_num FROM sketch_dimensions WHERE uuid = 'd1'") { it.getLong(0) }
        assertEquals(listOf<Long?>(null), precision)
        assertNull(precision.single())
    }

    @Test
    fun dimensionKey_should_Reject_When_SameKeySourceRepeats() {
        insertDimension("d1", sourceRef = "r1")
        assertFailsWith<Exception> { insertDimension("d2", sourceRef = "r1") }
        insertDimension("d3", sourceRef = "r2")
        exec(
            "INSERT OR REPLACE INTO sketch_dimensions (uuid, project_uuid, key, kind, value_num, value_den, source_kind, source_ref, " +
                "tolerance_num, tolerance_den, locked) VALUES ('d4', 'u1', 'width', 'length', 5, 1, 'sheet', 'r1', 0, 1, 0)",
        )
        val uuids = query("SELECT uuid FROM sketch_dimensions WHERE source_ref = 'r1'") { it.getString(0) }
        assertEquals(listOf<String?>("d4"), uuids)
    }

    @Test
    fun accessPaths_should_UseIndexes_When_ReadingBySheetProjectOrDimensionKey() {
        val sheets = plan("SELECT * FROM sketch_sheets WHERE project_uuid = 'u1' ORDER BY position")
        assertTrue("idx_sketch_sheets_project" in sheets, sheets)
        val prims = plan("SELECT * FROM sketch_primitives WHERE sheet_uuid = 's1' ORDER BY z")
        assertTrue("idx_sketch_primitives_sheet_z" in prims, prims)
        assertTrue("TEMP B-TREE" !in prims, "z order must come from the index:\n$prims")
        val dims = plan("SELECT * FROM sketch_dimensions WHERE project_uuid = 'u1' AND key = 'width' AND source_kind = 'sheet' AND source_ref = 'r1'")
        assertTrue("idx_sketch_dimensions_key" in dims, dims)
        val byProject = plan("SELECT * FROM sketch_dimensions WHERE project_uuid = 'u1'")
        assertTrue("idx_sketch_dimensions" in byProject, byProject)
    }

    @Test
    fun migration_should_CreateTablesAndBeNoOp_When_AppliedToDatabaseFromPreviousSchema() {
        insertProject()
        listOf("sketch_projects", "sketch_sheets", "sketch_primitives", "sketch_dimensions").forEach { exec("DROP TABLE $it") }
        exec("DELETE FROM schema_migrations WHERE name = 'sketch_tables'")

        runBlocking { MigrationRunner.applyAll(driver) }
        insertProject()

        runBlocking { MigrationRunner.applyAll(driver) }
        val rows = query("SELECT count(*) FROM sketch_projects") { it.getLong(0) }
        assertEquals(listOf(1L), rows, "second apply keeps the data and creates nothing new")
        val indexes = query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_sketch_%'") { it.getString(0) }
        assertEquals(
            setOf("idx_sketch_sheets_project", "idx_sketch_primitives_sheet_z", "idx_sketch_dimensions_key", "idx_sketch_dimensions_project"),
            indexes.toSet(),
        )
    }
}
