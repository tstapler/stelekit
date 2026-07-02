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

        init {
            // Raw transaction control (BEGIN/COMMIT/ROLLBACK) cannot be used in migration
            // statements. MigrationRunner.applyAll() issues each statement via driver.execute(),
            // which acquires a potentially different pooled connection per call. BEGIN on
            // connection A holds the SQLite write lock; subsequent DDL on connection B–N then
            // waits busy_timeout (10 s each) — causing a cascade hang that matches the test
            // timeout exactly. Use auto-committing DDL statements only.
            //
            // SqliteStatementAnalyzer.isTransactionControl() uses a comment-stripping tokenizer
            // so "BEGIN TRANSACTION", "-- comment\nBEGIN", etc. are all caught, while "BEGIN"
            // inside a CREATE TRIGGER body is correctly ignored (first keyword is "CREATE").
            val bad = statements.filter { SqliteStatementAnalyzer.isTransactionControl(it) }
            require(bad.isEmpty()) {
                "Migration '$name': raw transaction control statement(s) detected: $bad. " +
                "These deadlock pooled-connection drivers — use auto-committing DDL only."
            }
        }
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
        Migration(
            name = "drop_telemetry_tables_from_content_db",
            statements = listOf(
                // Telemetry tables moved to stelekit-telemetry-{graphId}.db.
                // Drop them from existing content databases to recover disk space and eliminate
                // WAL contention between telemetry background writes and content writes.
                "DROP TABLE IF EXISTS spans",
                "DROP TABLE IF EXISTS query_stats",
                "DROP TABLE IF EXISTS perf_histogram_buckets",
                "DROP TABLE IF EXISTS debug_flags",
            )
        ),
        Migration(
            name = "wikilink_references_table",
            statements = listOf(
                // Wikilink reference index: replaces the O(total_blocks) LIKE scan in
                // recomputeBacklinkCountForPage with O(1) index lookups.
                // ON DELETE CASCADE keeps the index consistent when blocks are deleted.
                """
                CREATE TABLE IF NOT EXISTS wikilink_references (
                    block_uuid TEXT NOT NULL,
                    page_name  TEXT NOT NULL COLLATE NOCASE,
                    PRIMARY KEY (block_uuid, page_name),
                    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)",
            )
        ),
        Migration(
            name = "wikilink_references_without_rowid",
            statements = listOf(
                // Migrate wikilink_references to WITHOUT ROWID to remove the hidden rowid B-tree.
                // All lookups on this table are composite PK lookups — no rowid-dependent queries exist.
                // WITHOUT ROWID stores data directly in the PK B-tree, reducing both insert cost and
                // lookup cost.
                //
                // Technique: create replacement table, copy data, swap names.
                // wikilink_references_new is a transient staging table; it is explicitly dropped
                // at the end so MigrationRunnerSchemaSyncTest can track its lifetime correctly.
                "PRAGMA foreign_keys=OFF",
                // Safety: clean up any leftover staging table from a previously interrupted migration.
                "DROP TABLE IF EXISTS wikilink_references_new",
                """
                CREATE TABLE IF NOT EXISTS wikilink_references_new (
                    block_uuid TEXT NOT NULL,
                    page_name  TEXT NOT NULL COLLATE NOCASE,
                    PRIMARY KEY (block_uuid, page_name),
                    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
                ) WITHOUT ROWID
                """,
                "INSERT OR IGNORE INTO wikilink_references_new SELECT block_uuid, page_name FROM wikilink_references",
                "DROP TABLE IF EXISTS wikilink_references",
                "ALTER TABLE wikilink_references_new RENAME TO wikilink_references",
                "DROP INDEX IF EXISTS idx_wikilink_refs_page_name_new",
                "CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)",
                "PRAGMA foreign_keys=ON",
            )
        ),
        Migration(
            name = "analyze_wikilink_references_post_without_rowid",
            statements = listOf("ANALYZE wikilink_references")
        ),
        Migration(
            name = "blocks_position_fractional_index",
            statements = listOf(
                // Migrate blocks.position from INTEGER to TEXT to support fractional string
                // indices (rocicorp/fractional-indexing algorithm). New insertions call
                // FractionalIndexing.generateKeyBetween(left, right) — zero UPDATE statements
                // for sibling shift. Existing rows are converted to zero-padded 11-digit strings
                // via printf('%011d', ...) which sort correctly under BINARY string order.
                //
                // SQLite cannot ALTER COLUMN type, so we use create/copy/drop/rename.
                // blocks.id AUTOINCREMENT is preserved so FTS5 content_rowid remains valid.
                "PRAGMA foreign_keys=OFF",
                "DROP TABLE IF EXISTS blocks_new",
                """
                CREATE TABLE IF NOT EXISTS blocks_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL UNIQUE,
                    page_uuid TEXT NOT NULL,
                    parent_uuid TEXT,
                    left_uuid TEXT,
                    content TEXT NOT NULL,
                    level INTEGER NOT NULL DEFAULT 0,
                    position TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    properties TEXT,
                    version INTEGER NOT NULL DEFAULT 0,
                    content_hash TEXT,
                    block_type TEXT NOT NULL DEFAULT 'bullet',
                    FOREIGN KEY (page_uuid) REFERENCES pages(uuid) ON DELETE CASCADE,
                    FOREIGN KEY (parent_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE,
                    FOREIGN KEY (left_uuid) REFERENCES blocks(uuid) ON DELETE SET NULL
                )
                """,
                // printf('%011d', CAST(position AS INTEGER)) zero-pads to 11 digits.
                // Valid on SQLite 3.8+ (Android system SQLite 3.18 on API 26).
                // ROW_NUMBER() would require SQLite 3.25+ — NOT available on API 26.
                "INSERT INTO blocks_new SELECT id, uuid, page_uuid, parent_uuid, left_uuid, content, level, printf('%011d', CAST(position AS INTEGER)), created_at, updated_at, properties, version, content_hash, block_type FROM blocks",
                "DROP TABLE blocks",
                "ALTER TABLE blocks_new RENAME TO blocks",
                // Recreate all indexes (dropped with the old blocks table)
                "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)",
                "CREATE INDEX IF NOT EXISTS idx_blocks_parent_position ON blocks(parent_uuid, position)",
                "CREATE INDEX IF NOT EXISTS idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash)",
                "CREATE INDEX IF NOT EXISTS idx_blocks_left_uuid ON blocks(left_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_blocks_content_hash ON blocks(content_hash)",
                // Recreate FTS5 triggers (dropped with blocks table; blocks_fts virtual table
                // itself is NOT dropped — it retains data and rowid references are preserved).
                "DROP TRIGGER IF EXISTS blocks_ai",
                "DROP TRIGGER IF EXISTS blocks_ad",
                "DROP TRIGGER IF EXISTS blocks_au",
                """
                CREATE TRIGGER blocks_ai AFTER INSERT ON blocks BEGIN
                    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
                END
                """,
                """
                CREATE TRIGGER blocks_ad AFTER DELETE ON blocks BEGIN
                    INSERT INTO blocks_fts(blocks_fts, rowid, content)
                    VALUES('delete', old.id, old.content);
                END
                """,
                """
                CREATE TRIGGER blocks_au AFTER UPDATE OF content ON blocks BEGIN
                    INSERT INTO blocks_fts(blocks_fts, rowid, content)
                    VALUES('delete', old.id, old.content);
                    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
                END
                """,
                "PRAGMA foreign_keys=ON",
                "ANALYZE blocks",
            )
        ),
        Migration(
            name = "git_config_oauth_token_key",
            statements = listOf(
                "ALTER TABLE git_config ADD COLUMN oauth_token_key TEXT"
            )
        ),
        Migration(
            name = "fts5_triggers_when_guard",
            // Devices whose system SQLite lacks FTS5 end up with pages_ai / blocks_ai triggers
            // whose bodies reference non-existent FTS5 virtual tables (pages_fts / blocks_fts).
            // SQLite validates trigger body table references at trigger-fire time, BEFORE
            // evaluating the WHEN clause — so even `WHEN 0` does not prevent the body-validation
            // step, and every INSERT INTO pages or blocks fails with "no such table: pages_fts".
            //
            // Fix: drop all six FTS5 triggers here. applyAll() calls ensureFts5TriggerState()
            // after every startup: if pages_fts/blocks_fts exist it recreates the triggers;
            // if they are absent (FTS5 unavailable) it leaves them absent. This is the only
            // approach that works because SQLite offers no way to guard against body-validation
            // errors via WHEN clauses alone.
            statements = listOf(
                "DROP TRIGGER IF EXISTS pages_ai",
                "DROP TRIGGER IF EXISTS pages_ad",
                "DROP TRIGGER IF EXISTS pages_au",
                "DROP TRIGGER IF EXISTS blocks_ai",
                "DROP TRIGGER IF EXISTS blocks_ad",
                "DROP TRIGGER IF EXISTS blocks_au",
            )
        ),
        Migration(
            name = "pages_section_id",
            // SQLite cannot DROP COLUMN or DROP CONSTRAINT, so we use the copy-alter pattern.
            // Auto-index name for inline UNIQUE is unstable across SQLite versions — using
            // DROP INDEX sqlite_autoindex_pages_1 is unsafe. Instead: create new table, copy,
            // drop old, rename. FTS5 triggers (pages_ai/ad/au) are recreated by
            // ensureFts5TriggerState() after applyAll() completes.
            //
            // Do NOT wrap these statements in raw BEGIN/COMMIT. MigrationRunner executes each
            // statement via driver.execute(), which acquires a pooled connection per call. A raw
            // BEGIN on connection A followed by DDL on connection B deadlocks: B waits 10 s for
            // A's write lock (busy_timeout), causing a cascade that hangs the full migration init.
            //
            // allowContentUpdate: original version wrapped statements in BEGIN/COMMIT which
            // caused the above deadlock; hash was updated to remove that wrapper.
            allowContentUpdate = true,
            statements = listOf(
                "DROP TABLE IF EXISTS pages_new",
                """
                CREATE TABLE IF NOT EXISTS pages_new (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL COLLATE NOCASE,
                    namespace TEXT,
                    file_path TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    properties TEXT,
                    version INTEGER NOT NULL DEFAULT 0,
                    is_favorite INTEGER DEFAULT 0,
                    is_journal INTEGER DEFAULT 0,
                    journal_date TEXT,
                    is_content_loaded INTEGER NOT NULL DEFAULT 1,
                    backlink_count INTEGER NOT NULL DEFAULT 0,
                    section_id TEXT NOT NULL DEFAULT '',
                    UNIQUE(name, section_id)
                )
                """,
                "INSERT INTO pages_new SELECT uuid, name, namespace, file_path, created_at, updated_at, properties, version, is_favorite, is_journal, journal_date, is_content_loaded, backlink_count, '' FROM pages",
                "DROP TABLE pages",
                "ALTER TABLE pages_new RENAME TO pages",
                "CREATE INDEX IF NOT EXISTS idx_pages_namespace ON pages(namespace)",
                "CREATE INDEX IF NOT EXISTS idx_pages_journal ON pages(is_journal, journal_date DESC)",
                "CREATE INDEX IF NOT EXISTS idx_pages_updated_at ON pages(updated_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_pages_created_at ON pages(created_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_pages_favorite ON pages(name) WHERE is_favorite = 1",
                "CREATE INDEX IF NOT EXISTS idx_pages_unloaded ON pages(uuid) WHERE is_content_loaded = 0",
                "CREATE INDEX IF NOT EXISTS idx_pages_journal_section ON pages(is_journal, journal_date, section_id)",
                // Refresh query-planner statistics after rebuilding all pages indexes.
                // (Was present in the original version of this migration; accidentally dropped
                // when BEGIN/COMMIT were removed to fix the pooled-connection deadlock.)
                "ANALYZE pages",
            )
        ),
        Migration(
            name = "idx_pages_section_id",
            // allowContentUpdate: ANALYZE pages was added after initial deployment to satisfy
            // the IndexWithoutAnalyze lint rule. Existing databases already have the index;
            // the ANALYZE is a no-op safety net for fresh installs going forward.
            allowContentUpdate = true,
            statements = listOf(
                "CREATE INDEX IF NOT EXISTS idx_pages_section_id ON pages(section_id, name)",
                "ANALYZE pages",
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
        // Ensure FTS5 triggers are present iff the FTS5 virtual tables exist.
        // This handles devices whose SQLite lacks FTS5: the pages_fts_setup migration silently
        // skips pages_fts creation (IF NOT EXISTS swallows the error) but leaves pages_ai/ad/au
        // and blocks_ai/ad/au triggers intact — and SQLite validates trigger body table refs at
        // fire time, so every INSERT into pages/blocks throws "no such table: pages_fts".
        // The fts5_triggers_when_guard migration dropped those triggers; this call recreates them
        // only on FTS5-capable devices, and is idempotent (IF NOT EXISTS / IF EXISTS guards).
        ensureFts5TriggerState(driver)
        // Run a fast sampled ANALYZE on every startup to keep query-planner statistics fresh.
        //
        // Why unconditional: ANALYZE on an empty table writes NOTHING to sqlite_stat1 — there
        // is no "0-row" entry, simply no entry at all. On a fresh install the analyze_blocks
        // migration runs before graph import, so sqlite_stat1 stays empty for blocks. On every
        // subsequent startup the migration is already applied and skipped, and the old code had
        // no further ANALYZE call, so the planner never received statistics. On Android SQLite
        // the planner falls back to SCAN blocks (~1.5 s/query) when no stats exist.
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
        // ANDROID_PRAGMAS via the rawQuery path (DriverFactory.android.kt) and in
        // buildMainDbConnectionProps() for JVM so every pool connection inherits it.
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
     * Ensures FTS5 triggers are present iff the corresponding FTS5 virtual tables exist.
     *
     * Called after every migration run. Idempotent: all statements use IF NOT EXISTS / IF EXISTS.
     *
     * - If [pages_fts] exists → CREATE IF NOT EXISTS pages_ai/ad/au triggers.
     * - If [pages_fts] is absent → DROP IF EXISTS pages_ai/ad/au triggers (stale refs crash INSERT).
     * - Same logic for blocks_fts / blocks_ai/ad/au.
     *
     * Background: SQLite validates all table references in a trigger body at trigger-fire time,
     * before the WHEN clause is evaluated. A trigger body that references a non-existent table
     * will throw "no such table" on every INSERT — regardless of any WHEN guard. Dropping the
     * trigger is the only reliable way to silence the error on FTS5-unavailable devices.
     */
    internal suspend fun ensureFts5TriggerState(driver: SqlDriver) {
        suspend fun tableExists(name: String): Boolean =
            driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$name'",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
                },
                parameters = 0,
            ).await()

        suspend fun exec(sql: String) = driver.execute(null, sql.trimIndent(), 0).await()

        if (tableExists("pages_fts")) {
            exec("CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON pages BEGIN INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name); END")
            exec("CREATE TRIGGER IF NOT EXISTS pages_ad AFTER DELETE ON pages BEGIN INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name); END")
            exec("CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE OF name ON pages BEGIN INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name); INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name); END")
        } else {
            exec("DROP TRIGGER IF EXISTS pages_ai")
            exec("DROP TRIGGER IF EXISTS pages_ad")
            exec("DROP TRIGGER IF EXISTS pages_au")
            logger.warn("SchemaRunner: pages_fts absent — FTS5 triggers for pages dropped (device lacks FTS5)")
        }

        if (tableExists("blocks_fts")) {
            exec("CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks BEGIN INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content); END")
            exec("CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks BEGIN INSERT INTO blocks_fts(blocks_fts, rowid, content) VALUES('delete', old.id, old.content); END")
            exec("CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE OF content ON blocks BEGIN INSERT INTO blocks_fts(blocks_fts, rowid, content) VALUES('delete', old.id, old.content); INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content); END")
        } else {
            exec("DROP TRIGGER IF EXISTS blocks_ai")
            exec("DROP TRIGGER IF EXISTS blocks_ad")
            exec("DROP TRIGGER IF EXISTS blocks_au")
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
