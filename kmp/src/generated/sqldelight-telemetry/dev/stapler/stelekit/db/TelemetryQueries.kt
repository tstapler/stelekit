package dev.stapler.stelekit.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.String

public class TelemetryQueries(
  driver: SqlDriver,
) : SuspendingTransacterImpl(driver) {
  public fun <T : Any> selectHistogramForOperation(operation_name: String, mapper: (bucket_ms: Long, count: Long) -> T): Query<T> = SelectHistogramForOperationQuery(operation_name) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun selectHistogramForOperation(operation_name: String): Query<SelectHistogramForOperation> = selectHistogramForOperation(operation_name, ::SelectHistogramForOperation)

  public fun selectAllHistogramOperations(): Query<String> = Query(-399_702_867, arrayOf("perf_histogram_buckets"), driver, "Telemetry.sq", "selectAllHistogramOperations", "SELECT DISTINCT operation_name FROM perf_histogram_buckets ORDER BY operation_name") { cursor ->
    cursor.getString(0)!!
  }

  public fun <T : Any> selectAllHistogramBuckets(mapper: (
    operation_name: String,
    bucket_ms: Long,
    count: Long,
  ) -> T): Query<T> = Query(1_906_960_040, arrayOf("perf_histogram_buckets"), driver, "Telemetry.sq", "selectAllHistogramBuckets", "SELECT operation_name, bucket_ms, count FROM perf_histogram_buckets ORDER BY operation_name, bucket_ms") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!,
      cursor.getLong(2)!!
    )
  }

  public fun selectAllHistogramBuckets(): Query<SelectAllHistogramBuckets> = selectAllHistogramBuckets(::SelectAllHistogramBuckets)

  public fun selectDebugFlag(key: String): Query<Long> = SelectDebugFlagQuery(key) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAllDebugFlags(mapper: (key: String, value_: Long) -> T): Query<T> = Query(-1_176_076_521, arrayOf("debug_flags"), driver, "Telemetry.sq", "selectAllDebugFlags", "SELECT key, value FROM debug_flags ORDER BY key") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun selectAllDebugFlags(): Query<SelectAllDebugFlags> = selectAllDebugFlags(::SelectAllDebugFlags)

  public fun <T : Any> selectRecentSpans(`value`: Long, mapper: (
    id: Long,
    trace_id: String,
    span_id: String,
    parent_span_id: String,
    name: String,
    start_epoch_ms: Long,
    end_epoch_ms: Long,
    duration_ms: Long,
    attributes_json: String,
    status_code: String,
    app_version: String,
    commit_hash: String,
  ) -> T): Query<T> = SelectRecentSpansQuery(value) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getString(8)!!,
      cursor.getString(9)!!,
      cursor.getString(10)!!,
      cursor.getString(11)!!
    )
  }

  public fun selectRecentSpans(value_: Long): Query<Spans> = selectRecentSpans(value_, ::Spans)

  public fun <T : Any> selectSlowSpansByVersionAndName(
    app_version: String,
    name: String,
    `value`: Long,
    mapper: (
      id: Long,
      trace_id: String,
      span_id: String,
      parent_span_id: String,
      name: String,
      start_epoch_ms: Long,
      end_epoch_ms: Long,
      duration_ms: Long,
      attributes_json: String,
      status_code: String,
      app_version: String,
      commit_hash: String,
    ) -> T,
  ): Query<T> = SelectSlowSpansByVersionAndNameQuery(app_version, name, value) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getString(8)!!,
      cursor.getString(9)!!,
      cursor.getString(10)!!,
      cursor.getString(11)!!
    )
  }

  public fun selectSlowSpansByVersionAndName(
    app_version: String,
    name: String,
    value_: Long,
  ): Query<Spans> = selectSlowSpansByVersionAndName(app_version, name, value_, ::Spans)

  public fun selectDistinctVersionsWithSpans(): Query<String> = Query(-1_036_460_818, arrayOf("spans"), driver, "Telemetry.sq", "selectDistinctVersionsWithSpans", "SELECT DISTINCT app_version FROM spans WHERE app_version != '' ORDER BY app_version DESC") { cursor ->
    cursor.getString(0)!!
  }

  public fun <T : Any> selectQueryStatsByVersion(app_version: String, mapper: (
    app_version: String,
    table_name: String,
    operation: String,
    calls: Long,
    errors: Long,
    total_ms: Long,
    min_ms: Long,
    max_ms: Long,
    b1: Long,
    b5: Long,
    b16: Long,
    b50: Long,
    b100: Long,
    b500: Long,
    b_inf: Long,
    first_seen: Long,
    last_seen: Long,
  ) -> T): Query<T> = SelectQueryStatsByVersionQuery(app_version) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13)!!,
      cursor.getLong(14)!!,
      cursor.getLong(15)!!,
      cursor.getLong(16)!!
    )
  }

  public fun selectQueryStatsByVersion(app_version: String): Query<Query_stats> = selectQueryStatsByVersion(app_version, ::Query_stats)

  public fun <T : Any> selectTopQueryStatsByTotalMs(
    app_version: String,
    `value`: Long,
    mapper: (
      app_version: String,
      table_name: String,
      operation: String,
      calls: Long,
      errors: Long,
      total_ms: Long,
      min_ms: Long,
      max_ms: Long,
      b1: Long,
      b5: Long,
      b16: Long,
      b50: Long,
      b100: Long,
      b500: Long,
      b_inf: Long,
      first_seen: Long,
      last_seen: Long,
    ) -> T,
  ): Query<T> = SelectTopQueryStatsByTotalMsQuery(app_version, value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13)!!,
      cursor.getLong(14)!!,
      cursor.getLong(15)!!,
      cursor.getLong(16)!!
    )
  }

  public fun selectTopQueryStatsByTotalMs(app_version: String, value_: Long): Query<Query_stats> = selectTopQueryStatsByTotalMs(app_version, value_, ::Query_stats)

  public fun <T : Any> selectTopQueryStatsByCalls(
    app_version: String,
    `value`: Long,
    mapper: (
      app_version: String,
      table_name: String,
      operation: String,
      calls: Long,
      errors: Long,
      total_ms: Long,
      min_ms: Long,
      max_ms: Long,
      b1: Long,
      b5: Long,
      b16: Long,
      b50: Long,
      b100: Long,
      b500: Long,
      b_inf: Long,
      first_seen: Long,
      last_seen: Long,
    ) -> T,
  ): Query<T> = SelectTopQueryStatsByCallsQuery(app_version, value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13)!!,
      cursor.getLong(14)!!,
      cursor.getLong(15)!!,
      cursor.getLong(16)!!
    )
  }

  public fun selectTopQueryStatsByCalls(app_version: String, value_: Long): Query<Query_stats> = selectTopQueryStatsByCalls(app_version, value_, ::Query_stats)

  public fun selectAllQueryStatVersions(): Query<String> = Query(1_456_176_500, arrayOf("query_stats"), driver, "Telemetry.sq", "selectAllQueryStatVersions", "SELECT DISTINCT app_version FROM query_stats ORDER BY app_version DESC") { cursor ->
    cursor.getString(0)!!
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertHistogramBucketIfAbsent(
    operation_name: String,
    bucket_ms: Long,
    recorded_at: Long,
  ): Long {
    val result = driver.execute(639_437_577, """
        |INSERT OR IGNORE INTO perf_histogram_buckets (operation_name, bucket_ms, count, recorded_at)
        |VALUES (?, ?, 0, ?)
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, operation_name)
          bindLong(parameterIndex++, bucket_ms)
          bindLong(parameterIndex++, recorded_at)
        }.await()
    notifyQueries(639_437_577) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun incrementHistogramBucketCount(
    recorded_at: Long,
    operation_name: String,
    bucket_ms: Long,
  ): Long {
    val result = driver.execute(413_301_774, """
        |UPDATE perf_histogram_buckets
        |SET count = count + 1, recorded_at = ?
        |WHERE operation_name = ? AND bucket_ms = ?
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindLong(parameterIndex++, recorded_at)
          bindString(parameterIndex++, operation_name)
          bindLong(parameterIndex++, bucket_ms)
        }.await()
    notifyQueries(413_301_774) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun incrementHistogramBucketCountBy(
    count: Long,
    recorded_at: Long,
    operation_name: String,
    bucket_ms: Long,
  ): Long {
    val result = driver.execute(2_046_015_749, """
        |UPDATE perf_histogram_buckets
        |SET count = count + ?, recorded_at = ?
        |WHERE operation_name = ? AND bucket_ms = ?
        """.trimMargin(), 4) {
          var parameterIndex = 0
          bindLong(parameterIndex++, count)
          bindLong(parameterIndex++, recorded_at)
          bindString(parameterIndex++, operation_name)
          bindLong(parameterIndex++, bucket_ms)
        }.await()
    notifyQueries(2_046_015_749) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteOldHistogramRows(recorded_at: Long): Long {
    val result = driver.execute(849_443_651, """DELETE FROM perf_histogram_buckets WHERE recorded_at < ?""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, recorded_at)
        }.await()
    notifyQueries(849_443_651) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun upsertDebugFlag(
    key: String,
    value_: Long,
    updated_at: Long,
  ): Long {
    val result = driver.execute(-165_476_050, """
        |INSERT OR REPLACE INTO debug_flags (key, value, updated_at)
        |VALUES (?, ?, ?)
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, key)
          bindLong(parameterIndex++, value_)
          bindLong(parameterIndex++, updated_at)
        }.await()
    notifyQueries(-165_476_050) { emit ->
      emit("debug_flags")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertSpan(
    trace_id: String,
    span_id: String,
    parent_span_id: String,
    name: String,
    start_epoch_ms: Long,
    end_epoch_ms: Long,
    duration_ms: Long,
    attributes_json: String,
    status_code: String,
    app_version: String,
    commit_hash: String,
  ): Long {
    val result = driver.execute(189_654_437, """
        |INSERT INTO spans (trace_id, span_id, parent_span_id, name, start_epoch_ms, end_epoch_ms, duration_ms, attributes_json, status_code, app_version, commit_hash)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 11) {
          var parameterIndex = 0
          bindString(parameterIndex++, trace_id)
          bindString(parameterIndex++, span_id)
          bindString(parameterIndex++, parent_span_id)
          bindString(parameterIndex++, name)
          bindLong(parameterIndex++, start_epoch_ms)
          bindLong(parameterIndex++, end_epoch_ms)
          bindLong(parameterIndex++, duration_ms)
          bindString(parameterIndex++, attributes_json)
          bindString(parameterIndex++, status_code)
          bindString(parameterIndex++, app_version)
          bindString(parameterIndex++, commit_hash)
        }.await()
    notifyQueries(189_654_437) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteSpansOlderThan(end_epoch_ms: Long): Long {
    val result = driver.execute(-1_504_615_303, """DELETE FROM spans WHERE end_epoch_ms < ?""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, end_epoch_ms)
        }.await()
    notifyQueries(-1_504_615_303) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteExcessSpans(`value`: Long): Long {
    val result = driver.execute(1_952_167_175, """DELETE FROM spans WHERE id NOT IN (SELECT id FROM spans ORDER BY start_epoch_ms DESC LIMIT ?)""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, value)
        }.await()
    notifyQueries(1_952_167_175) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteAllSpans(): Long {
    val result = driver.execute(1_740_398_357, """DELETE FROM spans""", 0).await()
    notifyQueries(1_740_398_357) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertQueryStatIfAbsent(
    app_version: String,
    table_name: String,
    operation: String,
    first_seen: Long,
    last_seen: Long,
  ): Long {
    val result = driver.execute(-1_056_921_705, """
        |INSERT OR IGNORE INTO query_stats (app_version, table_name, operation, calls, errors, total_ms, min_ms, max_ms, b1, b5, b16, b50, b100, b500, b_inf, first_seen, last_seen)
        |VALUES (?, ?, ?, 0, 0, 0, 9999999, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?)
        """.trimMargin(), 5) {
          var parameterIndex = 0
          bindString(parameterIndex++, app_version)
          bindString(parameterIndex++, table_name)
          bindString(parameterIndex++, operation)
          bindLong(parameterIndex++, first_seen)
          bindLong(parameterIndex++, last_seen)
        }.await()
    notifyQueries(-1_056_921_705) { emit ->
      emit("query_stats")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun mergeQueryStat(
    calls: Long,
    errors: Long,
    total_ms: Long,
    `value`: Long?,
    value_: Long?,
    b1: Long,
    b5: Long,
    b16: Long,
    b50: Long,
    b100: Long,
    b500: Long,
    b_inf: Long,
    value__: Long?,
    app_version: String,
    table_name: String,
    operation: String,
  ): Long {
    val result = driver.execute(2_123_273_446, """
        |UPDATE query_stats SET
        |    calls    = calls    + ?,
        |    errors   = errors   + ?,
        |    total_ms = total_ms + ?,
        |    min_ms   = MIN(min_ms,  ?),
        |    max_ms   = MAX(max_ms,  ?),
        |    b1       = b1    + ?,
        |    b5       = b5    + ?,
        |    b16      = b16   + ?,
        |    b50      = b50   + ?,
        |    b100     = b100  + ?,
        |    b500     = b500  + ?,
        |    b_inf    = b_inf + ?,
        |    last_seen = MAX(last_seen, ?)
        |WHERE app_version = ? AND table_name = ? AND operation = ?
        """.trimMargin(), 16) {
          var parameterIndex = 0
          bindLong(parameterIndex++, calls)
          bindLong(parameterIndex++, errors)
          bindLong(parameterIndex++, total_ms)
          bindLong(parameterIndex++, value)
          bindLong(parameterIndex++, value_)
          bindLong(parameterIndex++, b1)
          bindLong(parameterIndex++, b5)
          bindLong(parameterIndex++, b16)
          bindLong(parameterIndex++, b50)
          bindLong(parameterIndex++, b100)
          bindLong(parameterIndex++, b500)
          bindLong(parameterIndex++, b_inf)
          bindLong(parameterIndex++, value__)
          bindString(parameterIndex++, app_version)
          bindString(parameterIndex++, table_name)
          bindString(parameterIndex++, operation)
        }.await()
    notifyQueries(2_123_273_446) { emit ->
      emit("query_stats")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteQueryStatsForVersion(app_version: String): Long {
    val result = driver.execute(-1_323_160_013, """DELETE FROM query_stats WHERE app_version = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, app_version)
        }.await()
    notifyQueries(-1_323_160_013) { emit ->
      emit("query_stats")
    }
    return result
  }

  private inner class SelectHistogramForOperationQuery<out T : Any>(
    public val operation_name: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("perf_histogram_buckets", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("perf_histogram_buckets", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(540_078_788, """SELECT bucket_ms, count FROM perf_histogram_buckets WHERE operation_name = ? ORDER BY bucket_ms""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, operation_name)
    }

    override fun toString(): String = "Telemetry.sq:selectHistogramForOperation"
  }

  private inner class SelectDebugFlagQuery<out T : Any>(
    public val key: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("debug_flags", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("debug_flags", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_323_885_569, """SELECT value FROM debug_flags WHERE key = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, key)
    }

    override fun toString(): String = "Telemetry.sq:selectDebugFlag"
  }

  private inner class SelectRecentSpansQuery<out T : Any>(
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("spans", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("spans", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(845_436_912, """SELECT spans.id, spans.trace_id, spans.span_id, spans.parent_span_id, spans.name, spans.start_epoch_ms, spans.end_epoch_ms, spans.duration_ms, spans.attributes_json, spans.status_code, spans.app_version, spans.commit_hash FROM spans ORDER BY start_epoch_ms DESC LIMIT ?""", mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "Telemetry.sq:selectRecentSpans"
  }

  private inner class SelectSlowSpansByVersionAndNameQuery<out T : Any>(
    public val app_version: String,
    public val name: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("spans", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("spans", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_206_268_437, """SELECT spans.id, spans.trace_id, spans.span_id, spans.parent_span_id, spans.name, spans.start_epoch_ms, spans.end_epoch_ms, spans.duration_ms, spans.attributes_json, spans.status_code, spans.app_version, spans.commit_hash FROM spans WHERE app_version = ? AND name = ? ORDER BY duration_ms DESC LIMIT ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
      bindString(parameterIndex++, name)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "Telemetry.sq:selectSlowSpansByVersionAndName"
  }

  private inner class SelectQueryStatsByVersionQuery<out T : Any>(
    public val app_version: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("query_stats", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("query_stats", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_857_160_876, """SELECT query_stats.app_version, query_stats.table_name, query_stats.operation, query_stats.calls, query_stats.errors, query_stats.total_ms, query_stats.min_ms, query_stats.max_ms, query_stats.b1, query_stats.b5, query_stats.b16, query_stats.b50, query_stats.b100, query_stats.b500, query_stats.b_inf, query_stats.first_seen, query_stats.last_seen FROM query_stats WHERE app_version = ? ORDER BY total_ms DESC""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
    }

    override fun toString(): String = "Telemetry.sq:selectQueryStatsByVersion"
  }

  private inner class SelectTopQueryStatsByTotalMsQuery<out T : Any>(
    public val app_version: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("query_stats", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("query_stats", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_927_038_149, """SELECT query_stats.app_version, query_stats.table_name, query_stats.operation, query_stats.calls, query_stats.errors, query_stats.total_ms, query_stats.min_ms, query_stats.max_ms, query_stats.b1, query_stats.b5, query_stats.b16, query_stats.b50, query_stats.b100, query_stats.b500, query_stats.b_inf, query_stats.first_seen, query_stats.last_seen FROM query_stats WHERE app_version = ? ORDER BY total_ms DESC LIMIT ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "Telemetry.sq:selectTopQueryStatsByTotalMs"
  }

  private inner class SelectTopQueryStatsByCallsQuery<out T : Any>(
    public val app_version: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("query_stats", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("query_stats", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_788_418_736, """SELECT query_stats.app_version, query_stats.table_name, query_stats.operation, query_stats.calls, query_stats.errors, query_stats.total_ms, query_stats.min_ms, query_stats.max_ms, query_stats.b1, query_stats.b5, query_stats.b16, query_stats.b50, query_stats.b100, query_stats.b500, query_stats.b_inf, query_stats.first_seen, query_stats.last_seen FROM query_stats WHERE app_version = ? ORDER BY calls DESC LIMIT ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "Telemetry.sq:selectTopQueryStatsByCalls"
  }
}
