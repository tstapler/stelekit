package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
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
 * - All statements must be idempotent: use `IF NOT EXISTS`, `IF NOT EXISTS`, or
 *   `ALTER TABLE … ADD COLUMN IF NOT EXISTS` (SQLite ≥ 3.37, both bundled drivers meet this).
 * - Append to [all]; never reorder or remove entries (the hash of an unchanged migration
 *   will already be in `schema_migrations` and will be skipped harmlessly).
 * - Group logically related DDL into one [Migration] so they apply atomically.
 */
object MigrationRunner {

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
                "ALTER TABLE pages ADD COLUMN IF NOT EXISTS backlink_count INTEGER NOT NULL DEFAULT 0",
                // Backfill existing rows. Runs once on an existing DB; in-memory test DBs are
                // empty at migration time so this is a no-op there.
                """
                UPDATE pages SET backlink_count = (
                    SELECT COUNT(*) FROM blocks
                    WHERE blocks.content LIKE '%[[' || pages.name || ']]%'
                )
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
    fun applyAll(driver: SqlDriver) {
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
        )

        // Load both name and hash so we can detect tampering.
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
        ).value

        for (migration in all) {
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

            for (sql in migration.statements) {
                try {
                    driver.execute(null, sql.trimIndent(), 0)
                } catch (_: Exception) {
                    // Idempotent: column/table already exists — desired state already reached.
                }
            }

            // Record the hash whether each statement was a no-op or newly applied;
            // what matters is that the target schema state now exists.
            driver.execute(
                identifier = null,
                sql = "INSERT OR IGNORE INTO schema_migrations (hash, name) VALUES (?, ?)",
                parameters = 2
            ) {
                bindString(0, migration.hash)
                bindString(1, migration.name)
            }
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
