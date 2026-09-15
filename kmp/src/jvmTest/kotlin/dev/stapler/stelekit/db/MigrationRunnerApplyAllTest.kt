package dev.stapler.stelekit.db

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ── Test 3: FTS5 trigger WHEN guard ───────────────────────────────────────

    /**
     * Regression test for the Android "no such module: fts5" / "no such table: pages_fts" crash.
     *
     * Root cause: on devices whose SQLite lacks FTS5, pages_fts_setup silently fails to create
     * pages_fts (IF NOT EXISTS swallows the error) but successfully creates pages_ai/ad/au
     * triggers. SQLite validates trigger body table references at fire time, BEFORE any WHEN
     * clause — so every INSERT INTO pages throws "no such table: pages_fts".
     *
     * Fix: the fts5_triggers_when_guard migration drops all six FTS5 triggers.
     * ensureFts5TriggerState() then recreates them only when pages_fts/blocks_fts actually exist.
     *
     * This test exercises ensureFts5TriggerState directly: given stale FTS5 triggers and
     * no pages_fts, it must drop the triggers so INSERT works.
     */
    @Test
    fun `ensureFts5TriggerState drops broken FTS5 triggers when pages_fts is absent`() = runBlocking {
        // Arrange: pages table + broken pages_ai trigger referencing non-existent pages_fts.
        val conn = driver.getConnection()
        try {
            conn.prepareStatement(
                "CREATE TABLE pages (uuid TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)"
            ).execute()
            conn.prepareStatement(
                "CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name); END"
            ).execute()
        } finally {
            driver.closeConnection(conn)
        }

        // Pre-condition: INSERT fails because the trigger fires and pages_fts doesn't exist.
        // (SQLite validates trigger body table refs before the WHEN clause — even `WHEN 0` fails.)
        val preFixError = runCatching {
            val c = driver.getConnection()
            try { c.createStatement().execute("INSERT INTO pages VALUES ('u1','TestPage')") }
            finally { driver.closeConnection(c) }
        }.exceptionOrNull()
        assertNotNull(preFixError, "Pre-fix INSERT must fail (trigger body references missing pages_fts)")

        // Act: ensureFts5TriggerState sees pages_fts is absent → drops pages_ai/ad/au
        MigrationRunner.ensureFts5TriggerState(driver)

        // Assert 1: pages_ai must be gone (not recreated since pages_fts is absent)
        val pagesAiAfter = run {
            val c = driver.getConnection()
            try {
                val rs = c.createStatement().executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='pages_ai'"
                )
                if (rs.next()) rs.getString(1) else null
            } finally { driver.closeConnection(c) }
        }
        assertNull(pagesAiAfter, "pages_ai must be absent; got: $pagesAiAfter")

        // Assert 2: INSERT now succeeds
        val postFixError = runCatching {
            val c = driver.getConnection()
            try { c.createStatement().execute("INSERT INTO pages VALUES ('u2','AnotherPage')") }
            finally { driver.closeConnection(c) }
        }.exceptionOrNull()
        assertNull(postFixError, "INSERT must succeed after ensureFts5TriggerState; error: $postFixError")
    }

    /**
     * Complementary test: ensureFts5TriggerState must RECREATE triggers when pages_fts exists
     * (ensures normal FTS5 operation is not broken on capable devices).
     */
    @Test
    fun `ensureFts5TriggerState keeps FTS5 triggers present when pages_fts exists`() = runBlocking {
        // Arrange: pages table + pages_fts virtual table (FTS5 available) — no triggers yet.
        val conn = driver.getConnection()
        try {
            conn.prepareStatement(
                "CREATE TABLE pages (uuid TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)"
            ).execute()
            conn.prepareStatement(
                "CREATE VIRTUAL TABLE pages_fts USING fts5(name, content=pages, content_rowid=rowid)"
            ).execute()
            // No triggers created yet — simulates the state AFTER fts5_triggers_when_guard dropped them.
        } finally {
            driver.closeConnection(conn)
        }

        // Act: ensureFts5TriggerState sees pages_fts IS present → recreates pages_ai/ad/au
        MigrationRunner.ensureFts5TriggerState(driver)

        // Assert: pages_ai exists and references pages_fts correctly
        val pagesAiSql = run {
            val c = driver.getConnection()
            try {
                val rs = c.createStatement().executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='pages_ai'"
                )
                if (rs.next()) rs.getString(1) else null
            } finally { driver.closeConnection(c) }
        }
        assertNotNull(pagesAiSql, "pages_ai must be recreated when pages_fts exists")
        assertTrue(pagesAiSql.contains("pages_fts"), "pages_ai body must reference pages_fts")

        // Assert: INSERT works and the FTS5 index is updated
        val insertError = runCatching {
            val c = driver.getConnection()
            try { c.createStatement().execute("INSERT INTO pages VALUES ('u1','TestPage')") }
            finally { driver.closeConnection(c) }
        }.exceptionOrNull()
        assertNull(insertError, "INSERT must succeed on FTS5-capable device; error: $insertError")
    }

    // ── Test 4: idempotent "already exists" errors do record the migration ──────

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

    // ── Test 5: copy-alter migration completes without deadlock on poolSize=8 ──────

    /**
     * Regression test for the pages_section_id deadlock.
     *
     * Root cause: the original pages_section_id migration contained raw BEGIN/COMMIT statements.
     * MigrationRunner.applyAll() executes each statement via driver.execute(), which acquires
     * a DIFFERENT pooled connection per call. BEGIN on connection A held the write lock while
     * subsequent DDL on connection B waited busy_timeout (10 s) — causing a cascade that
     * totalled ~60 s until test timeout.
     *
     * Post-fix behaviour: migrations use auto-committing DDL only (no raw BEGIN/COMMIT).
     * A copy-alter sequence (create-new / copy / drop-old / rename) must complete on a
     * pooled driver with poolSize=8 in well under busy_timeout (i.e., < 5 s).
     *
     * Pre-fix behaviour: this test would hang for the full busy_timeout (5 s per blocked
     * statement) and then fail because the migration is not recorded.
     */
    @Test(timeout = 5_000)
    fun `copy-alter migration completes without deadlock on pooled driver`() = runBlocking {
        // Use poolSize=8 (production value) so BEGIN on one connection would block subsequent
        // DDL on other connections — reproducing the original deadlock condition.
        val pooledDriver = PooledJdbcSqliteDriver(
            "jdbc:sqlite:${tempFile.absolutePath}",
            props,
            poolSize = 8,
        )

        // Arrange — create a pages-like source table with some data.
        val conn = pooledDriver.getConnection()
        try {
            conn.prepareStatement(
                "CREATE TABLE schema_migrations (hash TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL DEFAULT 0)"
            ).execute()
            conn.prepareStatement(
                "CREATE TABLE items (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)"
            ).execute()
            conn.prepareStatement("INSERT INTO items VALUES ('a','alpha')").execute()
            conn.prepareStatement("INSERT INTO items VALUES ('b','beta')").execute()
        } finally {
            pooledDriver.closeConnection(conn)
        }

        // A copy-alter migration that mirrors the pages_section_id pattern — no BEGIN/COMMIT.
        val copyAlterMigration = MigrationRunner.Migration(
            name = "items_copy_alter",
            statements = listOf(
                "DROP TABLE IF EXISTS items_new",
                "CREATE TABLE IF NOT EXISTS items_new (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, tag TEXT NOT NULL DEFAULT '')",
                "INSERT INTO items_new SELECT id, name, '' FROM items",
                "DROP TABLE items",
                "ALTER TABLE items_new RENAME TO items",
            )
        )

        try {
            // Act — must finish well within 5 s (5 s is the test timeout above).
            // Pre-fix code with BEGIN/COMMIT would block for busy_timeout per statement.
            MigrationRunner.applyAll(pooledDriver, listOf(copyAlterMigration))

            // Assert 1: migration recorded
            val names = mutableSetOf<String>()
            val nc = pooledDriver.getConnection()
            try {
                val rs = nc.prepareStatement("SELECT name FROM schema_migrations").executeQuery()
                while (rs.next()) names += rs.getString(1)
            } finally { pooledDriver.closeConnection(nc) }
            assertTrue(names.contains("items_copy_alter"), "copy-alter migration must be recorded")

            // Assert 2: data survived
            val rows = mutableListOf<String>()
            val rc = pooledDriver.getConnection()
            try {
                val rs = rc.prepareStatement("SELECT id FROM items ORDER BY id").executeQuery()
                while (rs.next()) rows += rs.getString(1)
            } finally { pooledDriver.closeConnection(rc) }
            assertEquals(listOf("a", "b"), rows, "All rows must survive the copy-alter migration")

            // Assert 3: new column present
            val cols = mutableSetOf<String>()
            val cc = pooledDriver.getConnection()
            try {
                val rs = cc.prepareStatement("PRAGMA table_info(items)").executeQuery()
                while (rs.next()) cols += rs.getString("name")
            } finally { pooledDriver.closeConnection(cc) }
            assertTrue(cols.contains("tag"), "New 'tag' column must be present after copy-alter")
        } finally {
            pooledDriver.close()
        }
    }
}
