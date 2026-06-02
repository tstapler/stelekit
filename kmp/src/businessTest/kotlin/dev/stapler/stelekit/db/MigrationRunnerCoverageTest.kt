package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static analysis test: verifies that every table added after the original base schema
 * (pages, blocks, properties, plugin_data, block_references) has a corresponding
 * CREATE TABLE statement in [MigrationRunner.all].
 *
 * Why this matters: [dev.stapler.stelekit.db.DriverFactory] calls Schema.create() then
 * MigrationRunner.applyAll() on every startup. Schema.create() fails at the first
 * CREATE TABLE without IF NOT EXISTS (i.e., on any existing database), so ALL
 * subsequent DDL in Schema.create() is silently skipped. Tables added to the .sq file
 * after the initial schema must therefore appear in MigrationRunner.all to be created
 * on existing databases.
 *
 * Add new table names here when you add a CREATE TABLE IF NOT EXISTS to SteleDatabase.sq.
 */
class MigrationRunnerCoverageTest {

    private val tableNameRegex = Regex(
        """CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val tablesInMigrations: Set<String> by lazy {
        MigrationRunner.all
            .flatMap { it.statements }
            .flatMap { sql -> tableNameRegex.findAll(sql).map { it.groupValues[1].lowercase() } }
            .toSet()
    }

    private fun assertTableCovered(tableName: String) {
        assertTrue(
            tableName.lowercase() in tablesInMigrations,
            "Table '$tableName' is missing from MigrationRunner.all. " +
                "Schema.create() silently skips all DDL on existing databases, so every " +
                "table added after the original schema must be created by a migration."
        )
    }

    @Test fun metadata_table_is_covered() = assertTableCovered("metadata")
    @Test fun operations_table_is_covered() = assertTableCovered("operations")
    @Test fun logical_clock_table_is_covered() = assertTableCovered("logical_clock")
    @Test fun spans_table_is_covered() = assertTableCovered("spans")
    @Test fun page_visits_table_is_covered() = assertTableCovered("page_visits")
    @Test fun perf_histogram_buckets_table_is_covered() = assertTableCovered("perf_histogram_buckets")
    @Test fun debug_flags_table_is_covered() = assertTableCovered("debug_flags")
    @Test fun query_stats_table_is_covered() = assertTableCovered("query_stats")
    @Test fun git_config_table_is_covered() = assertTableCovered("git_config")
    @Test fun image_annotations_table_is_covered() = assertTableCovered("image_annotations")
    @Test fun measurement_annotations_table_is_covered() = assertTableCovered("measurement_annotations")
}
