package dev.stapler.stelekit.db

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for [MigrationRunner.applyAll].
 *
 * These tests exercise the real SQLite driver against a real file DB — no mocks.
 * They guard against two specific regressions:
 *
 * 1. **pages_backlink_count repair**: the original migration used `ADD COLUMN IF NOT EXISTS`,
 *    which is not valid SQLite syntax. The syntax error was swallowed, the hash was
 *    falsely recorded, and the column was never added. The pages_backlink_count_fix migration
 *    must add the column on databases where it is missing.
 *
 * 2. **Failing migration not recorded**: a migration whose SQL throws a non-idempotent error
 *    (not "already exists" / "duplicate column") must not be written to schema_migrations,
 *    so the next startup can retry it.
 */
class MigrationRunnerApplyAllTest {

    private val props = Properties().apply {
        setProperty("journal_mode", "WAL")
        setProperty("synchronous", "NORMAL")
        setProperty("busy_timeout", "5000")
    }

    private lateinit var tempFile: File
    private lateinit var driver: PooledJdbcSqliteDriver

    @Before
    fun setUp() {
        tempFile = File.createTempFile("migration-apply-test-", ".db")
        driver = PooledJdbcSqliteDriver("jdbc:sqlite:${tempFile.absolutePath}", props, poolSize = 1)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
        tempFile.delete()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns all column names of [table] from PRAGMA table_info. */
    private fun columnNames(table: String): Set<String> {
        val cols = mutableSetOf<String>()
        val conn = driver.getConnection()
        try {
            val rs = conn.prepareStatement("PRAGMA table_info($table)").executeQuery()
            while (rs.next()) cols += rs.getString("name")
        } finally {
            driver.closeConnection(conn)
        }
        return cols
    }

    /** Returns the set of migration names recorded in schema_migrations. */
    private fun appliedMigrationNames(): Set<String> {
        val names = mutableSetOf<String>()
        val conn = driver.getConnection()
        try {
            val rs = conn.prepareStatement("SELECT name FROM schema_migrations").executeQuery()
            while (rs.next()) names += rs.getString("name")
        } finally {
            driver.closeConnection(conn)
        }
        return names
    }

    /** Creates the pages table without the backlink_count column — the pre-fix schema. */
    private fun createPagesTableWithoutBacklinkCount() {
        val conn = driver.getConnection()
        try {
            conn.prepareStatement(
                """
                CREATE TABLE pages (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    namespace TEXT,
                    file_path TEXT,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    properties TEXT,
                    version INTEGER NOT NULL DEFAULT 0,
                    is_favorite INTEGER DEFAULT 0,
                    is_journal INTEGER DEFAULT 0,
                    journal_date TEXT,
                    is_content_loaded INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            ).execute()
            // Also create blocks table required by the backfill UPDATE in the migration
            conn.prepareStatement(
                """
                CREATE TABLE blocks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL UNIQUE,
                    page_uuid TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    level INTEGER NOT NULL DEFAULT 0,
                    position INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            ).execute()
        } finally {
            driver.closeConnection(conn)
        }
    }

    /**
     * Inserts a row into schema_migrations, simulating a migration that was falsely
     * recorded as applied (the hash matches what the old broken code would have stored).
     */
    private fun seedSchemaMigrationsWithFakeApplied(migrationName: String, hash: String) {
        val conn = driver.getConnection()
        try {
            conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS schema_migrations (hash TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL DEFAULT 0)"
            ).execute()
            conn.prepareStatement(
                "INSERT INTO schema_migrations (hash, name) VALUES ('$hash', '$migrationName')"
            ).execute()
        } finally {
            driver.closeConnection(conn)
        }
    }

    // ── Test 1: repair migration adds missing backlink_count column ────────────

    /**
     * Regression test for the pages_backlink_count bug.
     *
     * Pre-fix behaviour: pages_backlink_count used ADD COLUMN IF NOT EXISTS (invalid syntax).
     * The syntax error was swallowed and the hash was recorded as applied — but the column
     * was never added. The pages_backlink_count_fix migration was absent, so the column
     * remained missing forever.
     *
     * Post-fix behaviour: pages_backlink_count_fix uses valid syntax. It adds the column
     * even when pages_backlink_count is already (falsely) in schema_migrations.
     */
    @Test
    fun `pages_backlink_count_fix adds missing column when original migration was falsely recorded`() = runBlocking {
        // Arrange — a DB that looks like a user who upgraded from 0.23.0 → 0.24.0:
        //   • pages table exists WITHOUT backlink_count
        //   • pages_backlink_count hash is in schema_migrations (falsely applied)
        createPagesTableWithoutBacklinkCount()
        val fakePagesBacklinkCountMigration = MigrationRunner.all
            .first { it.name == "pages_backlink_count" }
        seedSchemaMigrationsWithFakeApplied("pages_backlink_count", fakePagesBacklinkCountMigration.hash)

        assertFalse(
            columnNames("pages").contains("backlink_count"),
            "Precondition failed: pages should NOT have backlink_count before repair"
        )

        // Act
        MigrationRunner.applyAll(driver)

        // Assert
        assertTrue(
            columnNames("pages").contains("backlink_count"),
            "pages must have backlink_count after pages_backlink_count_fix runs"
        )
        assertTrue(
            appliedMigrationNames().contains("pages_backlink_count_fix"),
            "pages_backlink_count_fix must be recorded in schema_migrations"
        )
    }

    // ── Test 2: failing migration is not recorded in schema_migrations ─────────

    /**
     * Regression test for the exception-swallowing bug.
     *
     * Pre-fix behaviour: applyAll swallowed any exception and still recorded the migration
     * hash — a syntax error caused the migration to be permanently marked applied even
     * though its SQL never succeeded.
     *
     * Post-fix behaviour: a migration whose statement throws a non-idempotent error (one
     * whose message does NOT contain "already exists" or "duplicate column") is NOT written
     * to schema_migrations, so it can retry on the next startup.
     */
    @Test
    fun `migration with real error is not recorded in schema_migrations`() = runBlocking {
        // Arrange — a minimal DB with only the schema_migrations table
        val conn = driver.getConnection()
        try {
            conn.prepareStatement(
                "CREATE TABLE schema_migrations (hash TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL DEFAULT 0)"
            ).execute()
        } finally {
            driver.closeConnection(conn)
        }

        // A migration whose SQL is intentionally broken (simulates the IF NOT EXISTS bug)
        val brokenMigration = MigrationRunner.Migration(
            name = "broken_syntax_migration",
            statements = listOf("ALTER TABLE nonexistent_table ADD COLUMN foo TEXT")
        )

        // Act
        MigrationRunner.applyAll(driver, listOf(brokenMigration))

        // Assert — the broken migration must NOT be recorded
        assertFalse(
            appliedMigrationNames().contains("broken_syntax_migration"),
            "A migration whose SQL threw a real error must not be recorded in schema_migrations"
        )
    }

    // ── Test 3: idempotent "already exists" errors do record the migration ─────

    /**
     * Verifies that "duplicate column name" and "already exists" exceptions are still
     * treated as success — these indicate the desired state is already present.
     */
    @Test
    fun `migration with already-exists error IS recorded in schema_migrations`() = runBlocking {
        // Arrange — create a table, then try to create it again in a migration
        val conn = driver.getConnection()
        try {
            conn.prepareStatement(
                "CREATE TABLE schema_migrations (hash TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL DEFAULT 0)"
            ).execute()
            conn.prepareStatement("CREATE TABLE existing_table (id INTEGER PRIMARY KEY)").execute()
        } finally {
            driver.closeConnection(conn)
        }

        val idempotentMigration = MigrationRunner.Migration(
            name = "create_existing_table",
            // No IF NOT EXISTS — will throw "table existing_table already exists"
            statements = listOf("CREATE TABLE existing_table (id INTEGER PRIMARY KEY)")
        )

        // Act
        MigrationRunner.applyAll(driver, listOf(idempotentMigration))

        // Assert — "already exists" is treated as success and the migration is recorded
        assertTrue(
            appliedMigrationNames().contains("create_existing_table"),
            "A migration that hits 'already exists' must still be recorded as applied"
        )
    }
}
