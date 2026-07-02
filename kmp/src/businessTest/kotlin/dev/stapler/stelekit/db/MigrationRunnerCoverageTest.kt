package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertFailsWith
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
    @Test fun page_visits_table_is_covered() = assertTableCovered("page_visits")
    @Test fun git_config_table_is_covered() = assertTableCovered("git_config")
    @Test fun image_annotations_table_is_covered() = assertTableCovered("image_annotations")
    @Test fun measurement_annotations_table_is_covered() = assertTableCovered("measurement_annotations")
    // spans, perf_histogram_buckets, debug_flags, query_stats moved to TelemetryDatabase —
    // their entries in MigrationRunner.all are DROP TABLE cleanup migrations, not CREATE TABLE.

    /**
     * Regression guard for the pages_section_id deadlock (CI failure 2026-07).
     *
     * Raw BEGIN/COMMIT in Migration.statements deadlocks PooledJdbcSqliteDriver: BEGIN on
     * connection A holds the write lock while DDL on connections B–N waits busy_timeout (10 s)
     * per statement. Migration.init now rejects this pattern at construction time.
     *
     * Must fail against pre-fix code (before the init {} block was added): the Migration
     * constructed successfully and the deadlock only manifested at runtime.
     */
    @Test fun `Migration rejects raw BEGIN in statement list`() {
        assertFailsWith<IllegalArgumentException>("Migration with raw BEGIN must throw") {
            MigrationRunner.Migration(
                name = "bad_migration",
                statements = listOf("CREATE TABLE foo (id TEXT)", "BEGIN", "DROP TABLE foo"),
            )
        }
    }

    @Test fun `Migration rejects raw COMMIT in statement list`() {
        assertFailsWith<IllegalArgumentException>("Migration with raw COMMIT must throw") {
            MigrationRunner.Migration(
                name = "bad_migration",
                statements = listOf("BEGIN", "CREATE TABLE foo (id TEXT)", "COMMIT"),
            )
        }
    }

    @Test fun `Migration allows BEGIN inside CREATE TRIGGER body`() {
        // Trigger SQL contains the keyword BEGIN as part of the trigger body — must NOT be flagged.
        MigrationRunner.Migration(
            name = "trigger_migration",
            statements = listOf(
                "CREATE TRIGGER foo_ai AFTER INSERT ON foo BEGIN INSERT INTO foo_fts(rowid) VALUES (new.rowid); END"
            ),
        ) // must not throw
    }

    @Test fun `no production migration contains raw transaction control`() {
        // Structural guard: ensure no entry in MigrationRunner.all violates the rule.
        // This test would have caught pages_section_id before it reached CI.
        val txKeywords = setOf("begin", "commit", "rollback")
        val violations = MigrationRunner.all.flatMap { migration ->
            migration.statements
                .filter { it.trim().lowercase() in txKeywords }
                .map { "'${migration.name}': $it" }
        }
        assertTrue(violations.isEmpty(), "Raw transaction control found in migrations: $violations")
    }
}
