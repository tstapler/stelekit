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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 1.4.1: `sketch_sheets.schema_version` is added additively to a database created by the current release. */
class SketchV2MigrationTest {
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

    private fun columns(): List<String> = query("PRAGMA table_info(sketch_sheets)") { it.getString(1)!! }

    private fun tables(): Set<String> = query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(0)!! }.toSet()

    /** The state of a database from the current release: no column, no migration record, one legacy row. */
    private fun downgradeToCurrentRelease() {
        exec("ALTER TABLE sketch_sheets DROP COLUMN $COLUMN")
        exec("DELETE FROM schema_migrations WHERE name = '$MIGRATION'")
        exec(
            "INSERT INTO sketch_sheets (uuid, project_uuid, kind, view_direction, unit, scale_sixteenths_per_unit, " +
                "layer_json, zones_json, position) VALUES ('old1', 'p1', 'plan', 'top', 'in', 16, '[]', '[]', 0)",
        )
    }

    @Test
    fun freshDatabase_should_DeclareTheColumnWithDefaultOne() {
        val ddl = query("SELECT type, dflt_value, \"notnull\" FROM pragma_table_info('sketch_sheets') WHERE name = '$COLUMN'") {
            Triple(it.getString(0), it.getString(1), it.getLong(2))
        }.single()
        assertEquals(Triple("INTEGER", "1", 1L), ddl)
    }

    @Test
    fun applyAll_should_AddTheColumnWithOneForExistingRows_AndNoElementTables() = runBlocking {
        downgradeToCurrentRelease()
        assertFalse(COLUMN in columns())

        MigrationRunner.applyAll(driver)

        assertTrue(COLUMN in columns())
        assertEquals(listOf(1L), query("SELECT $COLUMN FROM sketch_sheets WHERE uuid = 'old1'") { it.getLong(0)!! })
        assertTrue(tables().none { it == "sketch_elements" || it == "sketch_constraints" }, "cache tables stay dropped")
    }

    @Test
    fun applyAll_should_BeIdempotent_AndNeverResetAStoredTwo() = runBlocking {
        downgradeToCurrentRelease()
        MigrationRunner.applyAll(driver)
        exec("UPDATE sketch_sheets SET $COLUMN = 2 WHERE uuid = 'old1'")

        exec("DELETE FROM schema_migrations WHERE name = '$MIGRATION'")
        MigrationRunner.applyAll(driver)
        MigrationRunner.applyAll(driver)

        assertEquals(1, columns().count { it == COLUMN })
        assertEquals(listOf(2L), query("SELECT $COLUMN FROM sketch_sheets WHERE uuid = 'old1'") { it.getLong(0)!! })
    }

    private companion object {
        const val COLUMN = "schema_version"
        const val MIGRATION = "sketch_sheets_schema_version"
    }
}
