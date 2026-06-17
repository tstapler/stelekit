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

    data class Migration(
        val name: String,
        val statements: List<String>,
        /**
         * When true, a hash mismatch for this migration (recorded hash ≠ current hash) is
         * treated as an intentional update rather than tampering: the old hash is replaced with
         * the new one and the migration is re-applied on next startup. Use ONLY for migrations
         * whose SQL has been deliberately updated to remove a correctness issue (e.g. an
         * expensive side-effect) after the migration was already shipped.
         */
        val allowContentUpdate: Boolean = false,
    ) {
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
        // @Suppress("IndexWithoutAnalyze"): idx_blocks_content_hash is a minor change-detection
        // index; ANALYZE blocks further down in the list (analyze_blocks) refreshes statistics
        // for all blocks indexes, including this one, before it matters at scale.
        @Suppress("IndexWithoutAnalyze")
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
            allowContentUpdate = true,
            statements = listOf(
                // Repair for databases where pages_backlink_count was recorded as applied but
                // the column was never added: the original migration used ADD COLUMN IF NOT EXISTS,
                // which is not valid SQLite syntax. The syntax error was swallowed and the
                // migration hash was falsely marked applied. This migration uses the correct
                // syntax; on databases that already have the column, "duplicate column name"
                // is swallowed by applyAll leaving the column untouched.
                //
                // The O(P×B) UPDATE that used to follow this ADD COLUMN has been intentionally
                // removed (allowContentUpdate = true). On large libraries (500+ pages, 50k+
                // blocks) it took 10–60+ minutes and caused permanent "Initializing…" hangs.
                // Backlink counts start at 0 (the column default) and are recomputed
                // incrementally as the user edits notes, or fully via Search > Rebuild Index.
                "ALTER TABLE pages ADD COLUMN backlink_count INTEGER NOT NULL DEFAULT 0"
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
        Migration(
            name = "covering_indexes_page_blocks",
            statements = listOf(
                // Eliminates filesort for selectBlocksByPageUuidUnpaginated (called every page open).
                // SQLite delivers rows in (page_uuid, position) order — no in-memory sort needed.
                // Also benefits countBlocksByPageUuid (index-only scan, no table fetch).
                "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)",
                // Eliminates filesort for selectBlocksByParentUuidOrdered, selectBlockChildren,
                // selectLastChild — all child-listing queries fired during tree expand/indent/move.
                "CREATE INDEX IF NOT EXISTS idx_blocks_parent_position ON blocks(parent_uuid, position)",
                // True covering index for selectBlocksHashByPageUuid: SELECT uuid, content_hash
                // FROM blocks WHERE page_uuid = ? — zero table lookups, sequential index leaf scan.
                // Replaces 500 random B-tree reads per save on large pages with one index scan.
                "CREATE INDEX IF NOT EXISTS idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash)"
            )
        ),
        Migration(
            name = "analyze_blocks",
            statements = listOf(
                // Refresh SQLite statistics for the blocks table so the query planner uses
                // idx_blocks_page_position, idx_blocks_parent_position, and idx_blocks_page_hash
                // instead of falling back to a full heap scan (SCAN blocks) on large graphs.
                // QueryPlanAuditTest calls ANALYZE before checking plans; without this migration
                // production databases lack statistics and the optimizer picks SCAN blocks (~9 s).
                "ANALYZE blocks",
            )
        ),
        Migration(
            name = "drop_subsumed_blocks_single_column_indexes",
            statements = listOf(
                // idx_blocks_page_uuid(page_uuid) is fully subsumed by idx_blocks_page_position(page_uuid, position).
                // idx_blocks_parent_uuid(parent_uuid) is fully subsumed by idx_blocks_parent_position(parent_uuid, position).
                // Both composites handle all queries the single-column indexes served, with equal or better performance.
                // Dropping them eliminates redundant write overhead on every blocks INSERT/UPDATE/DELETE.
                "DROP INDEX IF EXISTS idx_blocks_page_uuid",
                "DROP INDEX IF EXISTS idx_blocks_parent_uuid"
            )
        ),
        Migration(
            name = "query_stats_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS query_stats (
                    app_version TEXT NOT NULL,
                    table_name  TEXT NOT NULL,
                    operation   TEXT NOT NULL,
                    calls       INTEGER NOT NULL DEFAULT 0,
                    errors      INTEGER NOT NULL DEFAULT 0,
                    total_ms    INTEGER NOT NULL DEFAULT 0,
                    min_ms      INTEGER NOT NULL DEFAULT 9999999,
                    max_ms      INTEGER NOT NULL DEFAULT 0,
                    b1          INTEGER NOT NULL DEFAULT 0,
                    b5          INTEGER NOT NULL DEFAULT 0,
                    b16         INTEGER NOT NULL DEFAULT 0,
                    b50         INTEGER NOT NULL DEFAULT 0,
                    b100        INTEGER NOT NULL DEFAULT 0,
                    b500        INTEGER NOT NULL DEFAULT 0,
                    b_inf       INTEGER NOT NULL DEFAULT 0,
                    first_seen  INTEGER NOT NULL,
                    last_seen   INTEGER NOT NULL,
                    PRIMARY KEY (app_version, table_name, operation)
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_query_stats_version_ms ON query_stats(app_version, total_ms DESC)"
            )
        ),
        Migration(
            name = "perf_histogram_and_debug_flags",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS perf_histogram_buckets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    operation_name TEXT NOT NULL,
                    bucket_ms INTEGER NOT NULL,
                    count INTEGER NOT NULL DEFAULT 0,
                    recorded_at INTEGER NOT NULL
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_perf_hist_op_bucket ON perf_histogram_buckets(operation_name, bucket_ms)",
                """
                CREATE TABLE IF NOT EXISTS debug_flags (
                    key TEXT NOT NULL PRIMARY KEY,
                    value INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
                """
            )
        ),
        Migration(
            name = "git_config_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS git_config (
                    graph_id                TEXT NOT NULL PRIMARY KEY,
                    repo_root               TEXT NOT NULL,
                    wiki_subdir             TEXT NOT NULL DEFAULT '',
                    remote_name             TEXT NOT NULL DEFAULT 'origin',
                    remote_branch           TEXT NOT NULL DEFAULT 'main',
                    auth_type               TEXT NOT NULL DEFAULT 'NONE',
                    ssh_key_path            TEXT,
                    ssh_key_passphrase_key  TEXT,
                    https_token_key         TEXT,
                    poll_interval_minutes   INTEGER NOT NULL DEFAULT 5,
                    auto_commit             INTEGER NOT NULL DEFAULT 1,
                    commit_message_template TEXT NOT NULL DEFAULT 'SteleKit: {date}'
                )
                """
            )
        ),
        Migration(
            name = "image_and_measurement_annotations",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS image_annotations (
                    uuid                       TEXT NOT NULL PRIMARY KEY,
                    block_uuid                 TEXT NOT NULL,
                    page_uuid                  TEXT NOT NULL,
                    graph_path                 TEXT NOT NULL,
                    file_path                  TEXT NOT NULL,
                    thumbnail_path             TEXT,
                    source                     TEXT NOT NULL DEFAULT 'FILE',
                    source_uri                 TEXT,
                    captured_at_ms             INTEGER,
                    imported_at_ms             INTEGER NOT NULL DEFAULT 0,
                    calibration_method         TEXT NOT NULL DEFAULT 'NONE',
                    pixels_per_meter           REAL NOT NULL DEFAULT 0.0,
                    calibration_confidence_pct INTEGER NOT NULL DEFAULT 0,
                    unit                       TEXT NOT NULL DEFAULT 'METERS',
                    tags                       TEXT NOT NULL DEFAULT '[]',
                    lat_lng                    TEXT,
                    altitude_m                 REAL,
                    bearing_deg                REAL,
                    pitch_deg                  REAL,
                    roll_deg                   REAL,
                    focal_length_mm            REAL,
                    focal_length_35mm_eq       REAL,
                    camera_make                TEXT,
                    camera_model               TEXT
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_image_annotations_block_uuid ON image_annotations(block_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_image_annotations_page_uuid ON image_annotations(page_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_image_annotations_graph_path ON image_annotations(graph_path)",
                """
                CREATE TABLE IF NOT EXISTS measurement_annotations (
                    uuid              TEXT NOT NULL PRIMARY KEY,
                    image_uuid        TEXT NOT NULL,
                    annotation_type   TEXT NOT NULL,
                    normalized_points TEXT NOT NULL DEFAULT '[]',
                    value_meters      REAL,
                    value_display     TEXT,
                    label             TEXT,
                    color_hex         TEXT NOT NULL DEFAULT '#FF0000',
                    ble_device_id     TEXT,
                    FOREIGN KEY (image_uuid) REFERENCES image_annotations(uuid) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_measurement_annotations_image_uuid ON measurement_annotations(image_uuid)"
            )
        ),
        // @Suppress("IndexWithoutAnalyze"): idx_pages_unloaded is a small partial index
        // (only unloaded pages qualify); analyze_pages further down refreshes statistics for
        // all pages indexes, including this one, before the temporal/favorite indexes land.
        @Suppress("IndexWithoutAnalyze")
        Migration(
            name = "pages_unloaded_partial_index",
            statements = listOf(
                // Partial index covering only unloaded pages (is_content_loaded = 0).
                // Makes selectUnloadedPagesPaginated and countUnloadedPages O(unloaded) instead
                // of O(total) — on a large graph where most pages are loaded the index is small
                // and both the drain-loop OFFSET scan and the COUNT(*) become index-only ops.
                "CREATE INDEX IF NOT EXISTS idx_pages_unloaded ON pages(uuid) WHERE is_content_loaded = 0"
            )
        ),
        Migration(
            name = "asset_index_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS asset_index (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    file_path TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    media_type TEXT NOT NULL,
                    subfolder TEXT NOT NULL DEFAULT 'files',
                    tags TEXT NOT NULL DEFAULT '[]',
                    auto_labels TEXT NOT NULL DEFAULT '[]',
                    ocr_text TEXT,
                    cloud_description TEXT,
                    page_uuids TEXT NOT NULL DEFAULT '[]',
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    imported_at_ms INTEGER NOT NULL,
                    ml_processed INTEGER NOT NULL DEFAULT 0,
                    ml_attempted_at INTEGER,
                    ml_failed INTEGER NOT NULL DEFAULT 0,
                    content_hash TEXT,
                    is_orphan INTEGER NOT NULL DEFAULT 0,
                    ml_tags_source TEXT NOT NULL DEFAULT 'NONE'
                )
                """
            )
        ),
        Migration(
            name = "pending_asset_moves_table",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS pending_asset_moves (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    asset_uuid TEXT NOT NULL,
                    old_file_path TEXT NOT NULL,
                    new_file_path TEXT NOT NULL,
                    old_relative_path TEXT NOT NULL,
                    new_relative_path TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL
                )
                """
            )
        ),
        Migration(
            name = "asset_index_indexes",
            statements = listOf(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_asset_file_path_unique ON asset_index(file_path)",
                "CREATE INDEX IF NOT EXISTS idx_asset_unprocessed ON asset_index(ml_processed, ml_failed, imported_at_ms)",
                "CREATE INDEX IF NOT EXISTS idx_asset_media_type ON asset_index(media_type)"
            )
        ),
        Migration(
            name = "spans_version_columns",
            statements = listOf(
                // Dedicated columns for app_version and commit_hash enable fast regression
                // queries (WHERE app_version = ? AND name = ? ORDER BY duration_ms DESC)
                // without JSON path extraction on attributes_json.
                "ALTER TABLE spans ADD COLUMN app_version TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE spans ADD COLUMN commit_hash TEXT NOT NULL DEFAULT ''",
                "CREATE INDEX IF NOT EXISTS idx_spans_version_name_duration ON spans(app_version, name, duration_ms DESC)",
            )
        ),
        Migration(
            name = "perf_histogram_recorded_at_index",
            statements = listOf(
                // Covers deleteOldHistogramRows WHERE recorded_at < ? (previously a full table scan)
                "CREATE INDEX IF NOT EXISTS idx_perf_hist_recorded_at ON perf_histogram_buckets(recorded_at)",
            )
        ),
        Migration(
            name = "spans_end_epoch_ms_index",
            statements = listOf(
                // Covers deleteSpansOlderThan WHERE end_epoch_ms < ? (previously a full table scan)
                "CREATE INDEX IF NOT EXISTS idx_spans_end_epoch_ms ON spans(end_epoch_ms)",
            )
        ),
        Migration(
            name = "pages_journal_temporal_favorite_indexes",
            statements = listOf(
                // Covers selectJournalPages, selectJournalPageByDate, selectJournalPagesByDates
                "CREATE INDEX IF NOT EXISTS idx_pages_journal ON pages(is_journal, journal_date DESC)",
                // Covers selectRecentlyUpdatedPages
                "CREATE INDEX IF NOT EXISTS idx_pages_updated_at ON pages(updated_at DESC)",
                // Covers selectRecentlyCreatedPages
                "CREATE INDEX IF NOT EXISTS idx_pages_created_at ON pages(created_at DESC)",
                // Covers selectFavoritePages
                "CREATE INDEX IF NOT EXISTS idx_pages_favorite ON pages(name) WHERE is_favorite = 1",
            )
        ),
        Migration(
            name = "analyze_pages",
            statements = listOf(
                // Refresh SQLite statistics for the pages table so the query planner uses the
                // temporal/favorite/journal composite indexes added above, as well as
                // idx_pages_unloaded (from pages_unloaded_partial_index). Without ANALYZE the
                // planner uses heuristics that can choose full heap scans on large graphs.
                "ANALYZE pages",
            )
        ),
        Migration(
            name = "image_annotations_imported_at_index",
            statements = listOf(
                // Covers selectAllImageAnnotations ORDER BY imported_at_ms DESC
                "CREATE INDEX IF NOT EXISTS idx_image_annotations_imported_at ON image_annotations(imported_at_ms DESC)",
            )
        ),
        Migration(
            name = "asset_index_imported_at_index",
            statements = listOf(
                // Covers selectAssets ORDER BY imported_at_ms DESC
                "CREATE INDEX IF NOT EXISTS idx_asset_imported_at ON asset_index(imported_at_ms DESC)",
            )
        ),
        Migration(
            name = "drop_redundant_and_unused_indexes",
            statements = listOf(
                // idx_blocks_uuid duplicates the implicit index created by UNIQUE uuid constraint —
                // SQLite creates an index for every UNIQUE constraint; the explicit one is dead weight.
                "DROP INDEX IF EXISTS idx_blocks_uuid",
                // idx_pages_uuid duplicates the implicit index created by the PRIMARY KEY uuid —
                // same reason as above.
                "DROP INDEX IF EXISTS idx_pages_uuid",
                // idx_pages_name (BINARY collation) is shadowed by the UNIQUE COLLATE NOCASE constraint
                // index SQLite creates for 'name TEXT NOT NULL UNIQUE COLLATE NOCASE'. Queries on the
                // name column use NOCASE semantics, so the BINARY index is never selected by the planner.
                "DROP INDEX IF EXISTS idx_pages_name",
                // idx_blocks_level has no corresponding WHERE clause in any query — no query in
                // SteleDatabase.sq or QueryPlanAuditTest filters on the level column alone.
                // Every block INSERT/UPDATE/DELETE pays the cost of updating this index for nothing.
                "DROP INDEX IF EXISTS idx_blocks_level",
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
    suspend fun applyAll(driver: SqlDriver) {
        applyAll(driver, all)
        // Run a fast sampled ANALYZE on every startup to keep query-planner statistics fresh.
        //
        // Why unconditional: PRAGMA optimize skips tables whose sqlite_stat1 row count is 0.
        // On a fresh install the analyze_blocks migration runs before graph import, recording
        // 0 rows; PRAGMA optimize then permanently ignores blocks and the planner falls back
        // to SCAN blocks (~1.5 s/query) for the lifetime of the database.
        //
        // Why analysis_limit=400: limits sampling to 400 rows per index (reservoir sample),
        // making each ANALYZE call O(1) in table size and typically under 50 ms on Android
        // even for 50 000-row tables. The 400-sample statistics are accurate enough for the
        // planner to always prefer the composite index over a heap scan.
        // ANALYZE blocks and pages unconditionally so fresh installs get correct statistics
        // on their second launch (after graph import). The analysis_limit=400 PRAGMA is set by
        // DriverFactory per-platform before this runs (rawQuery on Android, driver.execute on JVM)
        // so each ANALYZE reads at most 400 index rows — typically under 50 ms.
        //
        // NOTE: PRAGMA analysis_limit=400 is NOT called here because on Android the Requery
        // driver throws when execSQL is called for result-returning statements. It is set in
        // ANDROID_PRAGMAS via the rawQuery path instead, and in DriverFactory.jvm.kt via
        // driver.execute() which silently discards the returned value in JDBC.
        driver.execute(null, "ANALYZE blocks", 0).await()
        driver.execute(null, "ANALYZE pages", 0).await()
        // PRAGMA optimize is still useful for other tables we don't explicitly analyze above.
        driver.execute(null, "PRAGMA optimize", 0).await()
    }

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

        val pending = migrations.filter { appliedByName[it.name] == null }
        logger.info(
            "SchemaRunner: starting — ${pending.size} pending, " +
            "${appliedByName.size} already applied, ${migrations.size} total"
        )

        var appliedCount = 0
        for (migration in migrations) {
            val recordedHash = appliedByName[migration.name]

            if (recordedHash != null) {
                if (recordedHash != migration.hash) {
                    check(migration.allowContentUpdate) {
                        "Migration '${migration.name}' has been modified after being applied. " +
                        "Recorded hash: $recordedHash, current hash: ${migration.hash}. " +
                        "Never edit a shipped migration — add a new Migration entry instead."
                    }
                    // allowContentUpdate=true: deliberate SQL update — replace the stored hash so
                    // the new version runs on databases that have not yet applied it, and the old
                    // hash passes on databases that already did.
                    logger.warn(
                        "Migration '${migration.name}' content updated (allowContentUpdate). " +
                        "Replacing stored hash $recordedHash → ${migration.hash}."
                    )
                    driver.execute(
                        identifier = null,
                        sql = "UPDATE schema_migrations SET hash = ? WHERE name = ?",
                        parameters = 2
                    ) {
                        bindString(0, migration.hash)
                        bindString(1, migration.name)
                    }.await()
                }
                continue // already applied (or hash updated above), skip
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
                        break
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
            logger.info("SchemaRunner: applied '${migration.name}'")
            appliedCount++
        }
        logger.info("SchemaRunner: complete — applied=$appliedCount skipped=${migrations.size - appliedCount}")
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
