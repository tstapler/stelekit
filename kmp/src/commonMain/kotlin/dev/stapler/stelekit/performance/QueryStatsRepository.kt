package dev.stapler.stelekit.performance

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedTelemetryQueries
import dev.stapler.stelekit.db.TelemetryDatabase
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

@Serializable
data class QueryStat(
    val appVersion: String,
    val tableName: String,
    val operation: String,
    val calls: Long,
    val errors: Long,
    val totalMs: Long,
    val minMs: Long,
    val maxMs: Long,
    val b1: Long,
    val b5: Long,
    val b16: Long,
    val b50: Long,
    val b100: Long,
    val b500: Long,
    val bInf: Long,
    val firstSeen: Long,
    val lastSeen: Long,
) {
    val meanMs: Long get() = if (calls > 0) totalMs / calls else 0

    fun estimatePercentile(pct: Double): Long {
        val target = (calls * pct).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        val buckets = listOf(1L to b1, 5L to b5, 16L to b16, 50L to b50, 100L to b100, 500L to b500, Long.MAX_VALUE to bInf)
        for ((bound, count) in buckets) {
            cumulative += count
            if (cumulative >= target) return bound
        }
        return Long.MAX_VALUE
    }
}

class QueryStatsRepository(
    private val database: TelemetryDatabase,
    writeMutex: Mutex = Mutex(),
) {
    private val queries = database.telemetryQueries
    private val restricted = RestrictedTelemetryQueries(queries, writeMutex)

    @OptIn(DirectSqlWrite::class)
    suspend fun upsertBatch(stats: Map<String, QueryStatsCollector.Accum>, appVersion: String) {
        if (stats.isEmpty()) return
        val now = HistogramWriter.epochMs()
        try {
            withContext(PlatformDispatcher.DB) {
                restricted.withWriteLock {
                    restricted.transaction {
                        for ((key, a) in stats) {
                            val colonIdx = key.indexOf(':')
                            val tableName = if (colonIdx >= 0) key.substring(0, colonIdx) else key
                            val operation = if (colonIdx >= 0) key.substring(colonIdx + 1) else "unknown"
                            restricted.insertQueryStatIfAbsent(
                                app_version = appVersion,
                                table_name = tableName,
                                operation = operation,
                                first_seen = now,
                                last_seen = now,
                            )
                            restricted.mergeQueryStat(
                                calls = a.calls,
                                errors = a.errors,
                                total_ms = a.totalMs,
                                min_ms = a.minMs,
                                max_ms = a.maxMs,
                                b1 = a.b1,
                                b5 = a.b5,
                                b16 = a.b16,
                                b50 = a.b50,
                                b100 = a.b100,
                                b500 = a.b500,
                                b_inf = a.bInf,
                                last_seen = now,
                                app_version = appVersion,
                                table_name = tableName,
                                operation = operation,
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stats are best-effort; DB failures (e.g. contention at startup) are silently dropped.
        }
    }

    fun getStatsByVersion(appVersion: String): List<QueryStat> =
        queries.selectQueryStatsByVersion(appVersion).executeAsList().map { it.toQueryStat() }

    fun getTopByTotalMs(appVersion: String, limit: Int = 20): List<QueryStat> =
        queries.selectTopQueryStatsByTotalMs(appVersion, limit.toLong()).executeAsList().map { it.toQueryStat() }

    fun getTopByCalls(appVersion: String, limit: Int = 20): List<QueryStat> =
        queries.selectTopQueryStatsByCalls(appVersion, limit.toLong()).executeAsList().map { it.toQueryStat() }

    fun getAllVersions(): List<String> =
        queries.selectAllQueryStatVersions().executeAsList()

    suspend fun deleteVersion(appVersion: String) {
        try {
            withContext(PlatformDispatcher.DB) {
                restricted.withWriteLock {
                    @OptIn(DirectSqlWrite::class)
                    restricted.deleteQueryStatsForVersion(appVersion)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    private fun dev.stapler.stelekit.db.Query_stats.toQueryStat() = QueryStat(
        appVersion = app_version,
        tableName = table_name,
        operation = operation,
        calls = calls,
        errors = errors,
        totalMs = total_ms,
        minMs = min_ms,
        maxMs = max_ms,
        b1 = b1,
        b5 = b5,
        b16 = b16,
        b50 = b50,
        b100 = b100,
        b500 = b500,
        bInf = b_inf,
        firstSeen = first_seen,
        lastSeen = last_seen,
    )
}
