package dev.stapler.stelekit.db

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.test.assertTrue

/**
 * Two-layer enforcement that prevents the "no such table X" class of startup crashes.
 *
 * ## Why this class of bug exists
 *
 * [DriverFactory] calls [SteleDatabase.Schema.create] then [MigrationRunner.applyAll] on
 * every startup. `Schema.create` generates `CREATE TABLE pages` **without** `IF NOT EXISTS`
 * as its first statement. On any existing database that already has a `pages` table, SQLite
 * throws immediately, the exception is swallowed by `DriverFactory`, and **every subsequent
 * DDL statement in Schema.create — including tables added in later schema revisions — is
 * silently skipped**. `MigrationRunner.applyAll` is therefore the only mechanism that can
 * create new tables on existing databases. Both systems must be kept in sync.
 *
 * ## Test 1 — static schema sync (auto-derived, zero maintenance)
 *
 * Reads `SteleDatabase.sq` at test time (path injected via the `stelekit.sq.file` Gradle
 * system property) and extracts every `CREATE TABLE IF NOT EXISTS <name>`. Asserts that
 * each name appears in at least one SQL statement inside [MigrationRunner.all].
 *
 * **Would have caught the original bug**: `image_annotations` was in the `.sq` file with
 * `IF NOT EXISTS` but absent from [MigrationRunner.all] → this test would have FAILED.
 *
 * **No manual maintenance**: adding a new `CREATE TABLE IF NOT EXISTS` to the `.sq` file
 * automatically causes this test to fail until a corresponding migration is added.
 *
 * ## Test 2 — integration (verifies SQL correctness of each migration)
 *
 * Creates a minimal SQLite database that mirrors the original base schema (pages, blocks,
 * blocks_fts — no later-added tables), then runs [MigrationRunner.applyAll] and asserts
 * that every table declared in any migration's SQL actually exists in the database.
 *
 * **Catches a different failure mode** from Test 1: a migration whose SQL is syntactically
 * wrong or creates the table under a different name would slip through the static check but
 * fail here.
 */
class MigrationRunnerSchemaSyncTest {

    private val sqFilePath: String? = System.getProperty("stelekit.sq.file")

    private lateinit var tempFile: File
    private lateinit var driver: PooledJdbcSqliteDriver

    private val props = Properties().apply {
        setProperty("journal_mode", "WAL")
        setProperty("busy_timeout", "5000")
        setProperty("foreign_keys", "false")
    }

    @Before
    fun setUp() {
        tempFile = File.createTempFile("migration-schema-sync-", ".db")
        driver = PooledJdbcSqliteDriver("jdbc:sqlite:${tempFile.absolutePath}", props, poolSize = 1)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
        tempFile.delete()
    }

    // ── Test 1: static schema sync ────────────────────────────────────────────────

    @Test
    fun `all IF NOT EXISTS tables in SteleDatabase schema have a MigrationRunner entry`() {
        checkNotNull(sqFilePath) {
            "System property 'stelekit.sq.file' not set — run via Gradle (./gradlew jvmTest). " +
                "The property is injected by the jvmTest task in kmp/build.gradle.kts."
        }

        val sqContent = File(sqFilePath).readText()
        val tablesInSchema: Set<String> = Regex(
            """CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+(\w+)""",
            RegexOption.IGNORE_CASE
        ).findAll(sqContent).map { it.groupValues[1].lowercase() }.toSet()

        val tablesInMigrations: Set<String> = MigrationRunner.all
            .flatMap { it.statements }
            .flatMap { sql ->
                Regex("""CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""", RegexOption.IGNORE_CASE)
                    .findAll(sql).map { it.groupValues[1].lowercase() }
            }
            .toSet()

        val uncovered = tablesInSchema - tablesInMigrations
        assertTrue(uncovered.isEmpty(),
            "Tables in SteleDatabase.sq with 'CREATE TABLE IF NOT EXISTS' but missing from " +
                "MigrationRunner.all:\n  ${uncovered.sorted().joinToString(", ")}\n\n" +
                "Schema.create() silently exits after failing on 'CREATE TABLE pages' (no IF " +
                "NOT EXISTS) on existing databases, so every table added after the original " +
                "base schema must be created by a migration in MigrationRunner.all.\n" +
                "Fix: add a Migration(...) entry to MigrationRunner.all in " +
                "kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt"
        )
    }

    // ── Test 2: integration — SQL correctness of each migration ───────────────────

    @Test
    fun `MigrationRunner applyAll actually creates all migration-declared tables on a base schema database`() =
        runBlocking {
            createMinimalBaseSchema(driver)

            MigrationRunner.applyAll(driver)

            val existingTables = queryTableNames(driver)
            val expectedFromMigrations: Set<String> = MigrationRunner.all
                .flatMap { it.statements }
                .flatMap { sql ->
                    Regex(
                        """CREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""",
                        RegexOption.IGNORE_CASE
                    ).findAll(sql).map { it.groupValues[1].lowercase() }
                }
                .toSet()

            val missing = expectedFromMigrations - existingTables
            assertTrue(missing.isEmpty(),
                "Tables declared in MigrationRunner.all SQL but NOT present in the database " +
                    "after applyAll:\n  ${missing.sorted().joinToString(", ")}\n\n" +
                    "The migration's CREATE TABLE statement may be using the wrong table name, " +
                    "have a syntax error, or be wrapped in a condition that prevented it from running."
            )
        }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Creates just the base tables that existed before any custom migrations were added.
     * This simulates the on-disk state of an existing user database that predates later
     * schema additions. Notably absent: image_annotations, perf_histogram_buckets, git_config,
     * debug_flags, and any other tables added via MigrationRunner after the initial release.
     */
    private fun createMinimalBaseSchema(driver: PooledJdbcSqliteDriver) {
        val conn = driver.getConnection()
        try {
            listOf(
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
                """,
                """
                CREATE TABLE blocks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL UNIQUE,
                    page_uuid TEXT NOT NULL,
                    parent_uuid TEXT,
                    left_uuid TEXT,
                    content TEXT NOT NULL DEFAULT '',
                    level INTEGER NOT NULL DEFAULT 0,
                    position INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    properties TEXT,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE VIRTUAL TABLE blocks_fts USING fts5(
                    content, content=blocks, content_rowid=id, tokenize='porter unicode61'
                )
                """
            ).forEach { conn.prepareStatement(it.trimIndent()).execute() }
        } finally {
            driver.closeConnection(conn)
        }
    }

    private fun queryTableNames(driver: PooledJdbcSqliteDriver): Set<String> {
        val names = mutableSetOf<String>()
        val conn = driver.getConnection()
        try {
            val rs = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%_fts_%'"
            ).executeQuery()
            while (rs.next()) names += rs.getString(1).lowercase()
        } finally {
            driver.closeConnection(conn)
        }
        return names
    }
}
