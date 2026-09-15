package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver

object TelemetryMigrationRunner {

    val all: List<MigrationRunner.Migration> = listOf(
        MigrationRunner.Migration(
            name = "telemetry_spans",
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
        MigrationRunner.Migration(
            name = "telemetry_query_stats",
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
        MigrationRunner.Migration(
            name = "telemetry_perf_histogram_and_debug_flags",
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
                "CREATE INDEX IF NOT EXISTS idx_perf_hist_recorded_at ON perf_histogram_buckets(recorded_at)",
                """
                CREATE TABLE IF NOT EXISTS debug_flags (
                    key TEXT NOT NULL PRIMARY KEY,
                    value INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
                """
            )
        ),
        MigrationRunner.Migration(
            name = "telemetry_spans_version_columns",
            statements = listOf(
                "ALTER TABLE spans ADD COLUMN app_version TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE spans ADD COLUMN commit_hash TEXT NOT NULL DEFAULT ''",
                "CREATE INDEX IF NOT EXISTS idx_spans_version_name_duration ON spans(app_version, name, duration_ms DESC)",
            )
        ),
        MigrationRunner.Migration(
            name = "telemetry_spans_end_epoch_ms_index",
            statements = listOf(
                "CREATE INDEX IF NOT EXISTS idx_spans_end_epoch_ms ON spans(end_epoch_ms)",
            )
        ),
    )

    suspend fun applyAll(driver: SqlDriver) {
        MigrationRunner.applyAll(driver, all)
    }
}
