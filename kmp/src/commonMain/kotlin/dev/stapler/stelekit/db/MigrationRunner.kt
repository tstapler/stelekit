package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-agnostic, hash-identified incremental migration runner.
 *
 * Each [Migration] is a named group of idempotent SQL statements. Its [Migration.hash]
 * is computed as the FNV-1a-64 digest of its concatenated SQL, so the identity of a
 * migration is tied to its content — not its position in the list. This lets multiple
 * developers add migrations in parallel without sequence-number conflicts, and lets the
 * runner detect if a migration's SQL changes (which would produce a new hash and re-run).
 *
 * Applied hashes are persisted in a `schema_migrations` table that the runner itself
 * creates on first use. The runner is called from each platform's `createDriver()` so
 * it runs before any repository code touches the database.
 *
 * ## Rules for writing migrations
 * - All statements must be idempotent: use `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`,
 *   `DROP TRIGGER IF EXISTS`, etc. For `ALTER TABLE … ADD COLUMN`, use plain SQL without
 *   `IF NOT EXISTS` — that clause is not valid SQLite syntax; [applyAll] swallows the resulting
 *   "duplicate column name" error for idempotency.
 * - Append to [all]; never reorder or remove entries (the hash of an unchanged migration
 *   will already be in `schema_migrations` and will be skipped harmlessly).
 * - Group logically related DDL into one [Migration] so they apply atomically.
 */
object MigrationRunner {

    private val logger = Logger("MigrationRunner")

    data class Migration(val name: String, val statements: List<String>) {
        /** FNV-1a-64 digest of all statements joined with newlines. */
        val hash: String = fnv1a64(statements.joinToString("\n"))
    }

    val all: List<Migration> = listOf(
        Migration(
            name = "create_metadata_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS metadata (
                    key   TEXT NOT NULL PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
            )
        ),
        Migration(
            name = "blocks_content_hash",
            statements = listOf(
                "ALTER TABLE blocks ADD COLUMN IF NOT EXISTS content_hash TEXT",
                "CREATE INDEX IF NOT EXISTS idx_blocks_content_hash ON blocks(content_hash)"
            )
        ),
        Migration(
            name = "pages_is_content_loaded",
            statements = listOf(
                "ALTER TABLE pages ADD COLUMN IF NOT EXISTS is_content_loaded INTEGER NOT NULL DEFAULT 1"
            )
        ),
        Migration(
            name = "operations_and_logical_clock",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS operations (
                    op_id       TEXT NOT NULL PRIMARY KEY,
                    session_id  TEXT NOT NULL,
                    seq         INTEGER NOT NULL,
                    op_type     TEXT NOT NULL,
                    entity_uuid TEXT,
                    page_uuid   TEXT,
                    payload     TEXT NOT NULL DEFAULT '{}',
                    created_at  INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS logical_clock (
                    session_id  TEXT NOT NULL PRIMARY KEY,
                    seq         INTEGER NOT NULL DEFAULT 0
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_operations_session_seq ON operations(session_id, seq)",
                "CREATE INDEX IF NOT EXISTS idx_operations_page ON operations(page_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_operations_entity ON operations(entity_uuid)"
            )
        ),
        Migration(
            name = "spans_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS spans (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    trace_id         TEXT NOT NULL DEFAULT '',
                    span_id          TEXT NOT NULL DEFAULT '',
                    parent_span_id   TEXT NOT NULL DEFAULT '',
                    name             TEXT NOT NULL,
                    start_epoch_ms   INTEGER NOT NULL,
                    end_epoch_ms     INTEGER NOT NULL,
                    duration_ms      INTEGER NOT NULL,
                    attributes_json  TEXT NOT NULL DEFAULT '{}',
                    status_code      TEXT NOT NULL DEFAULT 'OK'
                )
                """,
                "CREATE INDEX IF NOT EXISTS spans_start_epoch_ms_idx ON spans(start_epoch_ms DESC)",
                "CREATE INDEX IF NOT EXISTS spans_trace_id_idx ON spans(trace_id)"
            )
        ),
        Migration(
            name = "pages_fts_setup",
            statements = listOf(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS pages_fts USING fts5(
                    name,
                    content=pages,
                    content_rowid=rowid,
                    tokenize='porter unicode61'
                )
                """,
                """
                CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON pages BEGIN
                    INSERT INTO pages_fts(rowid, name) VALUES (last_insert_rowid(), new.name);
                END
                """,
                """
                CREATE TRIGGER IF NOT EXISTS pages_ad AFTER DELETE ON pages BEGIN
                    INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name);
                END
                """,
                """
                CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE OF name ON pages BEGIN
                    INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name);
                    INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
                END
                """,
                // Backfill existing rows — INSERT OR IGNORE is a no-op if already indexed
                "INSERT OR IGNORE INTO pages_fts(rowid, name) SELECT rowid, name FROM pages"
            )
        ),
        Migration(
            name = "fix_pages_ai_trigger",
            statements = listOf(
                // Fix pages_ai to use new.rowid instead of last_insert_rowid(), which is
                // incorrect in trigger context and can point to a stale row.
                "DROP TRIGGER IF EXISTS pages_ai",
                """
                CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON pages BEGIN
                    INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
                END
                """
            )
        ),
        Migration(
            name = "page_visits_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS page_visits (
                    page_uuid       TEXT NOT NULL PRIMARY KEY,
                    visit_count     INTEGER NOT NULL DEFAULT 0,
                    last_visited_at INTEGER NOT NULL
                )
                """
            )
        ),
        Migration(
            name = "pages_backlink_count",
            statements = listOf(
                // NOTE: ADD COLUMN IF NOT EXISTS is not valid SQLite syntax. This statement
                // always throws a syntax error, which the applyAll catch block swallows, and
                // the hash is incorrectly recorded as applied without the column being added.
                // The pages_backlink_count_fix migration below repairs affected databases.
                "ALTER TABLE pages ADD COLUMN IF NOT EXISTS backlink_count INTEGER NOT NULL DEFAULT 0",
                """
                UPDATE pages SET backlink_count = (
                    SELECT COUNT(*) FROM blocks
                    WHERE blocks.content LIKE '%[[' || pages.name || ']]%'
                )
                """
            )
        ),
        Migration(
            name = "pages_backlink_count_fix",
            statements = listOf(
                // Repair for databases where pages_backlink_count was recorded as applied but
                // the column was never added: the original migration used ADD COLUMN IF NOT EXISTS,
                // which is not valid SQLite syntax. The syntax error was swallowed and the
                // migration hash was falsely marked applied. This migration uses the correct
                // syntax; on databases that already have the column, "duplicate column name"
                // is swallowed by applyAll leaving the column untouched.
                "ALTER TABLE pages ADD COLUMN backlink_count INTEGER NOT NULL DEFAULT 0",
                """
                UPDATE pages SET backlink_count = (
                    SELECT COUNT(*) FROM blocks
                    WHERE blocks.content LIKE '%[[' || pages.name || ']]%'
                )
                """
            )
        ),
        Migration(
            name = "fix_blocks_au_trigger_content_only",
            statements = listOf(
                // The original blocks_au trigger fired on ANY column update, causing O(n)
                // FTS5 delete+insert pairs when splitBlock/indentBlock/moveBlock shift sibling
                // positions via updateBlockPositionOnly / updateBlockHierarchy / updateBlockLeftUuid.
                // Limiting it to UPDATE OF content makes structural-only edits FTS5-free.
                "DROP TRIGGER IF EXISTS blocks_au",
                """
                CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE OF content ON blocks BEGIN
                    INSERT INTO blocks_fts(blocks_fts, rowid, content)
                    VALUES('delete', old.id, old.content);
                    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
                END
                """
            )
        ),
    )

    /**
     * Applies all unapplied migrations to [driver] and records each one in
     * `schema_migrations`. Safe to call on every startup — already-applied hashes
     * are skipped instantly.
     *
     * @throws IllegalStateException if a migration's SQL has been edited after it was
     * previously applied. Migrations are identified by name; if the recorded hash for
     * that name no longer matches the current hash, someone modified a shipped migration
     * (which would leave the schema in an indeterminate state). The correct fix is always
     * to add a new migration entry rather than edit an existing one.
     */
    suspend fun applyAll(driver: SqlDriver) = applyAll(driver, all)

    internal suspend fun applyAll(driver: SqlDriver, migrations: List<Migration>) {
        // Bootstrap the tracking table — must succeed before anything else.
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    hash       TEXT NOT NULL PRIMARY KEY,
                    name       TEXT NOT NULL,
                    applied_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent(),
            parameters = 0
        ).await()

        // Load both name and hash so we can detect tampering.
        // Use QueryResult.Value (not AsyncValue) so synchronous drivers (AndroidSqliteDriver)
        // can call .getValue() on the mapper result without throwing.
        // Both AndroidCursor.next() and JsRowCursor.next() return QueryResult.Value, so
        // cursor.next().value is safe across all platforms.
        val appliedByName: Map<String, String> = driver.executeQuery(
            identifier = null,
            sql = "SELECT name, hash FROM schema_migrations",
            mapper = { cursor ->
                val map = mutableMapOf<String, String>()
                while (cursor.next().value) {
                    val name = cursor.getString(0)
                    val hash = cursor.getString(1)
                    if (name != null && hash != null) map[name] = hash
                }
                QueryResult.Value(map as Map<String, String>)
            },
            parameters = 0
        ).await()

        for (migration in migrations) {
            val recordedHash = appliedByName[migration.name]

            if (recordedHash != null) {
                // Migration was previously applied — verify it hasn't been edited.
                check(recordedHash == migration.hash) {
                    "Migration '${migration.name}' has been modified after being applied. " +
                    "Recorded hash: $recordedHash, current hash: ${migration.hash}. " +
                    "Never edit a shipped migration — add a new Migration entry instead."
                }
                continue // already applied and untampered, skip
            }

            var encounteredRealError = false
            for (sql in migration.statements) {
                try {
                    driver.execute(null, sql.trimIndent(), 0).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val trimmed = sql.trim()
                    val msg = e.message?.lowercase() ?: ""
                    // Statements written with IF NOT EXISTS / IF EXISTS declare intent to be
                    // idempotent — any exception from them means the desired state is already
                    // reached (or the DB version doesn't support the syntax, as with
                    // ADD COLUMN IF NOT EXISTS which is not valid SQLite). Always swallow.
                    val statementIsIdempotent = trimmed.contains("IF NOT EXISTS", ignoreCase = true) ||
                        trimmed.contains("IF EXISTS", ignoreCase = true)
                    // Statements without those guards may still produce "already exists" /
                    // "duplicate column name" errors that indicate the state is already correct.
                    val isExpectedIdempotentError = msg.contains("already exists") ||
                        msg.contains("duplicate column")
                    if (statementIsIdempotent || isExpectedIdempotentError) {
                        // Desired state already reached for this statement.
                    } else {
                        // Unexpected error (DB locked, permission denied, etc.).
                        // Log it and skip recording so the next startup can retry.
                        logger.error(
                            "Migration '${migration.name}' statement failed: ${e.message} " +
                            "| SQL: ${trimmed.take(120)}"
                        )
                        encounteredRealError = true
                    }
                }
            }

            if (encounteredRealError) continue

            // Record the hash only when all statements succeeded (or hit expected idempotent
            // errors). A real failure above leaves the migration unrecorded so it retries
            // on the next startup.
            driver.execute(
                identifier = null,
                sql = "INSERT OR IGNORE INTO schema_migrations (hash, name) VALUES (?, ?)",
                parameters = 2
            ) {
                bindString(0, migration.hash)
                bindString(1, migration.name)
            }.await()
        }
    }

    /**
     * FNV-1a 64-bit hash over UTF-8 bytes of [input].
     * Deterministic across all Kotlin targets; no platform dependencies.
     */
    private fun fnv1a64(input: String): String {
        var hash = 0xcbf29ce484222325uL
        for (byte in input.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x00000100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
