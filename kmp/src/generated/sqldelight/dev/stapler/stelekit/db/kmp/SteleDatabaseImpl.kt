package dev.stapler.stelekit.db.kmp

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.SteleDatabaseQueries
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass

internal val KClass<SteleDatabase>.schema: SqlSchema<QueryResult.AsyncValue<Unit>>
  get() = SteleDatabaseImpl.Schema

internal fun KClass<SteleDatabase>.newInstance(driver: SqlDriver): SteleDatabase = SteleDatabaseImpl(driver)

private class SteleDatabaseImpl(
  driver: SqlDriver,
) : SuspendingTransacterImpl(driver),
    SteleDatabase {
  override val steleDatabaseQueries: SteleDatabaseQueries = SteleDatabaseQueries(driver)

  public object Schema : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long
      get() = 7

    override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      driver.execute(null, """
          |CREATE TABLE pages (
          |    uuid TEXT NOT NULL PRIMARY KEY,
          |    name TEXT NOT NULL COLLATE NOCASE,
          |    namespace TEXT,
          |    file_path TEXT,
          |    created_at INTEGER NOT NULL,
          |    updated_at INTEGER NOT NULL,
          |    properties TEXT, -- JSON string for page properties
          |    version INTEGER NOT NULL DEFAULT 0,
          |    is_favorite INTEGER DEFAULT 0,
          |    is_journal INTEGER DEFAULT 0,
          |    journal_date TEXT,
          |    is_content_loaded INTEGER NOT NULL DEFAULT 1,
          |    backlink_count INTEGER NOT NULL DEFAULT 0,
          |    section_id TEXT NOT NULL DEFAULT '',
          |    UNIQUE(name, section_id)
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE blocks (
          |    id INTEGER PRIMARY KEY AUTOINCREMENT, -- Hidden numeric ID for FTS5 internal use
          |    uuid TEXT NOT NULL UNIQUE,
          |    page_uuid TEXT NOT NULL,
          |    parent_uuid TEXT,
          |    left_uuid TEXT,
          |    content TEXT NOT NULL,
          |    level INTEGER NOT NULL DEFAULT 0,
          |    position TEXT NOT NULL,
          |    created_at INTEGER NOT NULL,
          |    updated_at INTEGER NOT NULL,
          |    properties TEXT, -- JSON string for block properties
          |    version INTEGER NOT NULL DEFAULT 0,
          |    content_hash TEXT, -- SHA-256 hex digest of normalised content (used for deduplication)
          |    block_type TEXT NOT NULL DEFAULT 'bullet', -- Structural discriminator (bullet, paragraph, heading, etc.) — excluded from FTS intentionally
          |    FOREIGN KEY (page_uuid) REFERENCES pages(uuid) ON DELETE CASCADE,
          |    FOREIGN KEY (parent_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE,
          |    FOREIGN KEY (left_uuid) REFERENCES blocks(uuid) ON DELETE SET NULL
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE properties (
          |    uuid TEXT NOT NULL PRIMARY KEY,
          |    block_uuid TEXT NOT NULL,
          |    key TEXT NOT NULL,
          |    value TEXT NOT NULL,
          |    created_at INTEGER NOT NULL,
          |    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE plugin_data (
          |    id INTEGER PRIMARY KEY AUTOINCREMENT,
          |    plugin_id TEXT NOT NULL,
          |    entity_type TEXT NOT NULL,
          |    entity_uuid TEXT NOT NULL,
          |    key TEXT NOT NULL,
          |    value TEXT NOT NULL,
          |    created_at INTEGER NOT NULL,
          |    updated_at INTEGER,
          |    UNIQUE(plugin_id, entity_type, entity_uuid, key)
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE block_references (
          |    id INTEGER PRIMARY KEY AUTOINCREMENT,
          |    from_block_uuid TEXT NOT NULL,
          |    to_block_uuid TEXT NOT NULL,
          |    created_at INTEGER NOT NULL,
          |    UNIQUE(from_block_uuid, to_block_uuid),
          |    FOREIGN KEY (from_block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE,
          |    FOREIGN KEY (to_block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE migration_changelog (
          |    id              TEXT NOT NULL,
          |    graph_id        TEXT NOT NULL,
          |    description     TEXT NOT NULL,
          |    checksum        TEXT NOT NULL,
          |    applied_at      INTEGER NOT NULL,
          |    execution_ms    INTEGER NOT NULL DEFAULT 0,
          |    status          TEXT NOT NULL DEFAULT 'APPLIED',
          |    applied_by      TEXT NOT NULL DEFAULT '',
          |    execution_order INTEGER NOT NULL DEFAULT 0,
          |    changes_applied INTEGER NOT NULL DEFAULT 0,
          |    error_message   TEXT,
          |    PRIMARY KEY (id, graph_id)
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS page_visits (
          |    page_uuid       TEXT NOT NULL PRIMARY KEY,
          |    visit_count     INTEGER NOT NULL DEFAULT 0,
          |    last_visited_at INTEGER NOT NULL   -- epoch milliseconds (Unix)
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS wikilink_references (
          |    block_uuid TEXT NOT NULL,
          |    page_name  TEXT NOT NULL COLLATE NOCASE,
          |    PRIMARY KEY (block_uuid, page_name),
          |    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
          |) WITHOUT ROWID
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE metadata (
          |    key TEXT NOT NULL PRIMARY KEY,
          |    value TEXT NOT NULL
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE operations (
          |    op_id       TEXT NOT NULL PRIMARY KEY,  -- UUID v7 (time-ordered for efficient range scans)
          |    session_id  TEXT NOT NULL,
          |    seq         INTEGER NOT NULL,            -- Lamport clock value (monotonically increasing per session)
          |    op_type     TEXT NOT NULL,               -- INSERT_BLOCK | UPDATE_BLOCK | DELETE_BLOCK | MOVE_BLOCK | BATCH_START | BATCH_END | SYNC_BARRIER
          |    entity_uuid TEXT,                        -- block UUID affected (NULL for BATCH_START/END and SYNC_BARRIER)
          |    page_uuid   TEXT,                        -- page context (NULL for BATCH_START/END and SYNC_BARRIER)
          |    payload     TEXT NOT NULL DEFAULT '{}',  -- JSON: {"before": {...}, "after": {...}}
          |    created_at  INTEGER NOT NULL
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE logical_clock (
          |    session_id  TEXT NOT NULL PRIMARY KEY,
          |    seq         INTEGER NOT NULL DEFAULT 0
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS git_config (
          |    graph_id                TEXT NOT NULL PRIMARY KEY,
          |    repo_root               TEXT NOT NULL,
          |    wiki_subdir             TEXT NOT NULL DEFAULT '',
          |    remote_name             TEXT NOT NULL DEFAULT 'origin',
          |    remote_branch           TEXT NOT NULL DEFAULT 'main',
          |    auth_type               TEXT NOT NULL DEFAULT 'NONE',
          |    ssh_key_path            TEXT,
          |    ssh_key_passphrase_key  TEXT,
          |    https_token_key         TEXT,
          |    oauth_token_key         TEXT,
          |    poll_interval_minutes   INTEGER NOT NULL DEFAULT 5,
          |    auto_commit             INTEGER NOT NULL DEFAULT 1,
          |    commit_message_template TEXT NOT NULL DEFAULT 'SteleKit: {date}'
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS image_annotations (
          |    uuid                       TEXT NOT NULL PRIMARY KEY,
          |    block_uuid                 TEXT NOT NULL,
          |    page_uuid                  TEXT NOT NULL,
          |    graph_path                 TEXT NOT NULL,
          |    file_path                  TEXT NOT NULL,
          |    thumbnail_path             TEXT,
          |    source                     TEXT NOT NULL DEFAULT 'FILE',
          |    source_uri                 TEXT,
          |    captured_at_ms             INTEGER,
          |    imported_at_ms             INTEGER NOT NULL DEFAULT 0,
          |    calibration_method         TEXT NOT NULL DEFAULT 'NONE',
          |    pixels_per_meter           REAL NOT NULL DEFAULT 0.0,
          |    calibration_confidence_pct INTEGER NOT NULL DEFAULT 0,
          |    unit                       TEXT NOT NULL DEFAULT 'METERS',
          |    tags                       TEXT NOT NULL DEFAULT '[]',
          |    lat_lng                    TEXT,
          |    altitude_m                 REAL,
          |    bearing_deg                REAL,
          |    pitch_deg                  REAL,
          |    roll_deg                   REAL,
          |    focal_length_mm            REAL,
          |    focal_length_35mm_eq       REAL,
          |    camera_make                TEXT,
          |    camera_model               TEXT
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS measurement_annotations (
          |    uuid              TEXT NOT NULL PRIMARY KEY,
          |    image_uuid        TEXT NOT NULL,
          |    annotation_type   TEXT NOT NULL,
          |    normalized_points TEXT NOT NULL DEFAULT '[]',
          |    value_meters      REAL,
          |    value_display     TEXT,
          |    label             TEXT,
          |    color_hex         TEXT NOT NULL DEFAULT '#FF0000',
          |    ble_device_id     TEXT,
          |    FOREIGN KEY (image_uuid) REFERENCES image_annotations(uuid) ON DELETE CASCADE
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS asset_index (
          |    uuid TEXT NOT NULL PRIMARY KEY,
          |    file_path TEXT NOT NULL,
          |    relative_path TEXT NOT NULL,
          |    media_type TEXT NOT NULL,
          |    subfolder TEXT NOT NULL DEFAULT 'files',
          |    tags TEXT NOT NULL DEFAULT '[]',
          |    auto_labels TEXT NOT NULL DEFAULT '[]',
          |    ocr_text TEXT,
          |    cloud_description TEXT,
          |    page_uuids TEXT NOT NULL DEFAULT '[]',
          |    size_bytes INTEGER NOT NULL DEFAULT 0,
          |    imported_at_ms INTEGER NOT NULL,
          |    ml_processed INTEGER NOT NULL DEFAULT 0,
          |    ml_attempted_at INTEGER,
          |    ml_failed INTEGER NOT NULL DEFAULT 0,
          |    content_hash TEXT,
          |    is_orphan INTEGER NOT NULL DEFAULT 0,
          |    ml_tags_source TEXT NOT NULL DEFAULT 'NONE'
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS pending_asset_moves (
          |    id INTEGER PRIMARY KEY AUTOINCREMENT,
          |    asset_uuid TEXT NOT NULL,
          |    old_file_path TEXT NOT NULL,
          |    new_file_path TEXT NOT NULL,
          |    old_relative_path TEXT NOT NULL,
          |    new_relative_path TEXT NOT NULL,
          |    created_at_ms INTEGER NOT NULL
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, "CREATE INDEX idx_pages_namespace ON pages(namespace)", 0).await()
      driver.execute(null, "CREATE INDEX idx_blocks_page_position ON blocks(page_uuid, position)", 0).await()
      driver.execute(null, "CREATE INDEX idx_blocks_parent_position ON blocks(parent_uuid, position)", 0).await()
      driver.execute(null, "CREATE INDEX idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash)", 0).await()
      driver.execute(null, "CREATE INDEX idx_blocks_left_uuid ON blocks(left_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX idx_blocks_content_hash ON blocks(content_hash)", 0).await()
      driver.execute(null, "CREATE INDEX idx_properties_block_uuid ON properties(block_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX idx_properties_key ON properties(key)", 0).await()
      driver.execute(null, "CREATE INDEX idx_plugin_data_plugin_id ON plugin_data(plugin_id)", 0).await()
      driver.execute(null, "CREATE INDEX idx_plugin_data_entity ON plugin_data(entity_type, entity_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX idx_plugin_data_key ON plugin_data(key)", 0).await()
      driver.execute(null, "CREATE INDEX idx_references_from ON block_references(from_block_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX idx_references_to ON block_references(to_block_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX idx_pages_journal ON pages(is_journal, journal_date DESC)", 0).await()
      driver.execute(null, "CREATE INDEX idx_pages_updated_at ON pages(updated_at DESC)", 0).await()
      driver.execute(null, "CREATE INDEX idx_pages_created_at ON pages(created_at DESC)", 0).await()
      driver.execute(null, "CREATE INDEX idx_pages_favorite ON pages(name) WHERE is_favorite = 1", 0).await()
      driver.execute(null, "CREATE INDEX idx_pages_journal_section ON pages(is_journal, journal_date, section_id)", 0).await()
      driver.execute(null, "CREATE INDEX idx_changelog_graph_status ON migration_changelog(graph_id, status)", 0).await()
      driver.execute(null, "CREATE INDEX idx_changelog_applied_at ON migration_changelog(graph_id, applied_at)", 0).await()
      driver.execute(null, """
          |CREATE TRIGGER blocks_ai AFTER INSERT ON blocks BEGIN
          |    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
          |END
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TRIGGER blocks_ad AFTER DELETE ON blocks BEGIN
          |    INSERT INTO blocks_fts(blocks_fts, rowid, content)
          |    VALUES('delete', old.id, old.content);
          |END
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TRIGGER blocks_au AFTER UPDATE OF content ON blocks BEGIN
          |    INSERT INTO blocks_fts(blocks_fts, rowid, content)
          |    VALUES('delete', old.id, old.content);
          |    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
          |END
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN
          |    INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
          |END
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TRIGGER pages_ad AFTER DELETE ON pages BEGIN
          |    INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name);
          |END
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE TRIGGER pages_au AFTER UPDATE OF name ON pages BEGIN
          |    INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name);
          |    INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
          |END
          """.trimMargin(), 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)", 0).await()
      driver.execute(null, "CREATE INDEX idx_operations_session_seq ON operations(session_id, seq)", 0).await()
      driver.execute(null, "CREATE INDEX idx_operations_page ON operations(page_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX idx_operations_entity ON operations(entity_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_block_uuid ON image_annotations(block_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_page_uuid ON image_annotations(page_uuid)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_graph_path ON image_annotations(graph_path)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_imported_at ON image_annotations(imported_at_ms DESC)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_measurement_annotations_image_uuid ON measurement_annotations(image_uuid)", 0).await()
      driver.execute(null, "CREATE UNIQUE INDEX IF NOT EXISTS idx_asset_file_path_unique ON asset_index(file_path)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_asset_unprocessed ON asset_index(ml_processed, ml_failed, imported_at_ms)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_asset_media_type ON asset_index(media_type)", 0).await()
      driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_asset_imported_at ON asset_index(imported_at_ms DESC)", 0).await()
      driver.execute(null, """
          |CREATE VIRTUAL TABLE blocks_fts USING fts5(
          |    content,
          |    content=blocks,
          |    content_rowid=id,
          |    tokenize='porter unicode61'
          |)
          """.trimMargin(), 0).await()
      driver.execute(null, """
          |CREATE VIRTUAL TABLE pages_fts USING fts5(
          |    name,
          |    content=pages,
          |    content_rowid=rowid,
          |    tokenize='porter unicode61'
          |)
          """.trimMargin(), 0).await()
    }

    private fun migrateInternal(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
    ): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      if (oldVersion <= 2 && newVersion > 2) {
        driver.execute(null, "ALTER TABLE blocks ADD COLUMN block_type TEXT NOT NULL DEFAULT 'bullet'", 0).await()
      }
      if (oldVersion <= 3 && newVersion > 3) {
        driver.execute(null, """
            |CREATE TABLE IF NOT EXISTS migration_changelog (
            |    id              TEXT NOT NULL,
            |    graph_id        TEXT NOT NULL,
            |    description     TEXT NOT NULL,
            |    checksum        TEXT NOT NULL,
            |    applied_at      INTEGER NOT NULL,
            |    execution_ms    INTEGER NOT NULL DEFAULT 0,
            |    status          TEXT NOT NULL DEFAULT 'APPLIED',
            |    applied_by      TEXT NOT NULL DEFAULT '',
            |    execution_order INTEGER NOT NULL DEFAULT 0,
            |    changes_applied INTEGER NOT NULL DEFAULT 0,
            |    error_message   TEXT,
            |    PRIMARY KEY (id, graph_id)
            |)
            """.trimMargin(), 0).await()
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_changelog_graph_status ON migration_changelog(graph_id, status)", 0).await()
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_changelog_applied_at ON migration_changelog(graph_id, applied_at)", 0).await()
      }
      if (oldVersion <= 4 && newVersion > 4) {
        driver.execute(null, """
            |CREATE TABLE IF NOT EXISTS image_annotations (
            |    uuid                     TEXT NOT NULL PRIMARY KEY,
            |    block_uuid               TEXT NOT NULL,
            |    page_uuid                TEXT NOT NULL,
            |    graph_path               TEXT NOT NULL,
            |    file_path                TEXT NOT NULL,
            |    thumbnail_path           TEXT,
            |    source                   TEXT NOT NULL DEFAULT 'FILE',
            |    source_uri               TEXT,
            |    captured_at_ms           INTEGER,
            |    imported_at_ms           INTEGER NOT NULL DEFAULT 0,
            |    calibration_method       TEXT NOT NULL DEFAULT 'NONE',
            |    pixels_per_meter         REAL NOT NULL DEFAULT 0.0,
            |    calibration_confidence_pct INTEGER NOT NULL DEFAULT 0,
            |    unit                     TEXT NOT NULL DEFAULT 'METERS',
            |    tags                     TEXT NOT NULL DEFAULT '[]',
            |    lat_lng                  TEXT,
            |    altitude_m               REAL,
            |    bearing_deg              REAL,
            |    pitch_deg                REAL,
            |    roll_deg                 REAL,
            |    focal_length_mm          REAL,
            |    focal_length_35mm_eq     REAL,
            |    camera_make              TEXT,
            |    camera_model             TEXT
            |)
            """.trimMargin(), 0).await()
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_block_uuid ON image_annotations(block_uuid)", 0).await()
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_page_uuid ON image_annotations(page_uuid)", 0).await()
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_image_annotations_graph_path ON image_annotations(graph_path)", 0).await()
        driver.execute(null, """
            |CREATE TABLE IF NOT EXISTS measurement_annotations (
            |    uuid               TEXT NOT NULL PRIMARY KEY,
            |    image_uuid         TEXT NOT NULL,
            |    annotation_type    TEXT NOT NULL,
            |    normalized_points  TEXT NOT NULL DEFAULT '[]',
            |    value_meters       REAL,
            |    value_display      TEXT,
            |    label              TEXT,
            |    color_hex          TEXT NOT NULL DEFAULT '#FF0000',
            |    ble_device_id      TEXT,
            |    FOREIGN KEY (image_uuid) REFERENCES image_annotations(uuid) ON DELETE CASCADE
            |)
            """.trimMargin(), 0).await()
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_measurement_annotations_image_uuid ON measurement_annotations(image_uuid)", 0).await()
      }
      if (oldVersion <= 5 && newVersion > 5) {
        driver.execute(null, """
            |CREATE TABLE IF NOT EXISTS query_stats (
            |    app_version TEXT NOT NULL,
            |    table_name  TEXT NOT NULL,
            |    operation   TEXT NOT NULL,
            |    calls       INTEGER NOT NULL DEFAULT 0,
            |    errors      INTEGER NOT NULL DEFAULT 0,
            |    total_ms    INTEGER NOT NULL DEFAULT 0,
            |    min_ms      INTEGER NOT NULL DEFAULT 9999999,
            |    max_ms      INTEGER NOT NULL DEFAULT 0,
            |    b1          INTEGER NOT NULL DEFAULT 0,
            |    b5          INTEGER NOT NULL DEFAULT 0,
            |    b16         INTEGER NOT NULL DEFAULT 0,
            |    b50         INTEGER NOT NULL DEFAULT 0,
            |    b100        INTEGER NOT NULL DEFAULT 0,
            |    b500        INTEGER NOT NULL DEFAULT 0,
            |    b_inf       INTEGER NOT NULL DEFAULT 0,
            |    first_seen  INTEGER NOT NULL,
            |    last_seen   INTEGER NOT NULL,
            |    PRIMARY KEY (app_version, table_name, operation)
            |)
            """.trimMargin(), 0).await()
        driver.execute(null, """
            |CREATE INDEX IF NOT EXISTS idx_query_stats_version_ms
            |    ON query_stats(app_version, total_ms DESC)
            """.trimMargin(), 0).await()
      }
      if (oldVersion <= 6 && newVersion > 6) {
        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_blocks_page_uuid_position ON blocks(page_uuid, position)", 0).await()
        driver.execute(null, "DROP INDEX IF EXISTS idx_blocks_page_uuid", 0).await()
        driver.execute(null, "ANALYZE blocks", 0).await()
      }
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      var lastVersion = oldVersion

      callbacks.filter { it.afterVersion in oldVersion until newVersion }
      .sortedBy { it.afterVersion }
      .forEach { callback ->
        migrateInternal(driver, oldVersion = lastVersion, newVersion = callback.afterVersion + 1).await()
        callback.block(driver)
        lastVersion = callback.afterVersion + 1
      }

      if (lastVersion < newVersion) {
        migrateInternal(driver, lastVersion, newVersion).await()
      }
    }
  }
}
