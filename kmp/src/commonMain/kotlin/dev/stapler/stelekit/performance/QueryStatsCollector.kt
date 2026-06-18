package dev.stapler.stelekit.performance

import dev.stapler.stelekit.cache.PlatformLock
import dev.stapler.stelekit.cache.withLock
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Lock-guarded in-place SQL stats accumulator. Collects calls/errors/latencies per (table, operation)
 * and flushes to [QueryStatsRepository] via request coalescing: each [record] call signals a
 * [Channel.CONFLATED] channel; the consumer waits [debounceMs] after the last signal before
 * flushing, so a burst of queries produces one DB write shortly after the burst ends rather than
 * waiting up to [debounceMs] from the first call.
 *
 * Uses a [PlatformLock]-guarded [HashMap] so [record] accumulates directly without heap allocation
 * beyond the first call for a given key. The earlier channel-based design created two objects per
 * SQL call (StatRecord + ChannelEvent.Stat) which caused measurable GC pressure on Android during
 * graph-load benchmarks.
 */
class QueryStatsCollector(
    private val appVersion: String,
    scope: CoroutineScope,
    private val debounceMs: Long = 2_000L,
) {
    private var repository: QueryStatsRepository? = null

    fun setRepository(repo: QueryStatsRepository) {
        repository = repo
    }

    // CONFLATED: only one pending signal at a time — multiple record() calls during the debounce
    // window collapse to a single flush trigger, with no heap allocation per SQL call.
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    private val lock = PlatformLock()
    private val accum = HashMap<String, Accum>()
    // Permanent SQL sample per key — survives drainNow() clears so the Plans tab always
    // has SQL to explain, even after the 30 s flush has reset the accumulator.
    private val persistentSqlSamples = HashMap<String, String>()

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
        var sampleSql: String? = null,
    )

    init {
        scope.launch(PlatformDispatcher.DB) {
            for (signal in flushSignal) {
                // Wait for activity to settle before writing — absorbs any signal that arrives
                // during the quiet window so we don't immediately re-trigger.
                delay(debounceMs)
                flushSignal.tryReceive()
                drainNow()
            }
        }
    }

    fun record(table: String, operation: String, durationMs: Long, isError: Boolean, sql: String? = null) {
        val key = "$table:$operation"
        lock.withLock {
            val a = accum.getOrPut(key) { Accum() }
            if (sql != null) {
                if (a.sampleSql == null) a.sampleSql = sql
                if (!persistentSqlSamples.containsKey(key)) persistentSqlSamples[key] = sql
            }
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
        flushSignal.trySend(Unit)
    }

    /**
     * Returns a snapshot of one sample SQL string per key (table:operation).
     * Drawn from the persistent sample map — survives 30 s drain cycles so the
     * Plans tab always has SQL to explain regardless of when it is opened.
     */
    fun getSqlSamples(): Map<String, String> = lock.withLock {
        persistentSqlSamples.toMap()
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stats are best-effort; DB failures (e.g. contention at startup) are silently dropped.
        }
    }
}
