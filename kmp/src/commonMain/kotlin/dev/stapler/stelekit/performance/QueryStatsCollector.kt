package dev.stapler.stelekit.performance

import dev.stapler.stelekit.cache.PlatformLock
import dev.stapler.stelekit.cache.withLock
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lock-guarded in-place SQL stats accumulator. Collects calls/errors/latencies per (table, operation)
 * and flushes to [QueryStatsRepository] periodically (30 s default) or on [drainNow] demand.
 *
 * Uses a [PlatformLock]-guarded [HashMap] so [record] accumulates directly without heap allocation
 * beyond the first call for a given key. The earlier channel-based design created two objects per
 * SQL call (StatRecord + ChannelEvent.Stat) which caused measurable GC pressure on Android during
 * graph-load benchmarks.
 */
class QueryStatsCollector(
    private val appVersion: String,
    scope: CoroutineScope,
    private val flushIntervalMs: Long = 30_000L,
) {
    private var repository: QueryStatsRepository? = null

    fun setRepository(repo: QueryStatsRepository) {
        repository = repo
    }

    private val lock = PlatformLock()
    private val accum = HashMap<String, Accum>()

    data class Accum(
        var calls: Long = 0,
        var errors: Long = 0,
        var totalMs: Long = 0,
        var minMs: Long = Long.MAX_VALUE,
        var maxMs: Long = 0,
        var b1: Long = 0,
        var b5: Long = 0,
        var b16: Long = 0,
        var b50: Long = 0,
        var b100: Long = 0,
        var b500: Long = 0,
        var bInf: Long = 0,
    )

    init {
        scope.launch(PlatformDispatcher.DB) {
            while (true) {
                delay(flushIntervalMs)
                drainNow()
            }
        }
    }

    fun record(table: String, operation: String, durationMs: Long, isError: Boolean) {
        val key = "$table:$operation"
        lock.withLock {
            val a = accum.getOrPut(key) { Accum() }
            a.calls++
            if (isError) a.errors++
            a.totalMs += durationMs
            if (durationMs < a.minMs) a.minMs = durationMs
            if (durationMs > a.maxMs) a.maxMs = durationMs
            when {
                durationMs <= 1   -> a.b1++
                durationMs <= 5   -> a.b5++
                durationMs <= 16  -> a.b16++
                durationMs <= 50  -> a.b50++
                durationMs <= 100 -> a.b100++
                durationMs <= 500 -> a.b500++
                else              -> a.bInf++
            }
        }
    }

    /** Flush accumulated stats to the repository immediately, bypassing the periodic timer. */
    suspend fun drainNow() {
        val snapshot = lock.withLock {
            if (accum.isEmpty()) return
            val s = accum.toMap()
            accum.clear()
            s
        }
        try {
            repository?.upsertBatch(snapshot, appVersion)
        } catch (_: Exception) {
            // Stats are best-effort; DB failures (e.g. contention at startup) are silently dropped.
        }
    }
}
