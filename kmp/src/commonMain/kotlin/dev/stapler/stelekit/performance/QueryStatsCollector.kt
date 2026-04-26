package dev.stapler.stelekit.performance

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class QueryStatsCollector(
    private val appVersion: String,
    scope: CoroutineScope,
    private val flushIntervalMs: Long = 30_000L,
) {
    // Set after construction to break the circular dependency with database lazy init.
    var repository: QueryStatsRepository? = null

    private sealed class ChannelEvent {
        data class Stat(val record: StatRecord) : ChannelEvent()
        class DrainNow(val done: CompletableDeferred<Unit>) : ChannelEvent()
    }

    private val channel = Channel<ChannelEvent>(capacity = Channel.BUFFERED)
    private val accum = mutableMapOf<String, Accum>()

    data class Accum(
        var calls: Long = 0,
        var errors: Long = 0,
        var totalMs: Long = 0,
        var minMs: Long = 9999999,
        var maxMs: Long = 0,
        var b1: Long = 0,
        var b5: Long = 0,
        var b16: Long = 0,
        var b50: Long = 0,
        var b100: Long = 0,
        var b500: Long = 0,
        var bInf: Long = 0,
    )

    private data class StatRecord(
        val table: String,
        val operation: String,
        val durationMs: Long,
        val isError: Boolean,
    )

    init {
        scope.launch(PlatformDispatcher.DB) {
            var lastFlush = HistogramWriter.epochMs()
            for (event in channel) {
                when (event) {
                    is ChannelEvent.Stat -> {
                        val record = event.record
                        val key = "${record.table}:${record.operation}"
                        val a = accum.getOrPut(key) { Accum() }
                        a.calls++
                        if (record.isError) a.errors++
                        a.totalMs += record.durationMs
                        if (record.durationMs < a.minMs) a.minMs = record.durationMs
                        if (record.durationMs > a.maxMs) a.maxMs = record.durationMs
                        when {
                            record.durationMs <= 1   -> a.b1++
                            record.durationMs <= 5   -> a.b5++
                            record.durationMs <= 16  -> a.b16++
                            record.durationMs <= 50  -> a.b50++
                            record.durationMs <= 100 -> a.b100++
                            record.durationMs <= 500 -> a.b500++
                            else                     -> a.bInf++
                        }
                        val now = HistogramWriter.epochMs()
                        if (now - lastFlush >= flushIntervalMs && accum.isNotEmpty()) {
                            flush()
                            lastFlush = now
                        }
                    }
                    is ChannelEvent.DrainNow -> {
                        if (accum.isNotEmpty()) flush()
                        event.done.complete(Unit)
                    }
                }
            }
        }
    }

    fun record(table: String, operation: String, durationMs: Long, isError: Boolean) {
        channel.trySend(ChannelEvent.Stat(StatRecord(table, operation, durationMs, isError)))
    }

    /** Flush accumulated stats to the repository immediately, bypassing the periodic timer. */
    suspend fun drainNow() {
        val done = CompletableDeferred<Unit>()
        channel.send(ChannelEvent.DrainNow(done))
        done.await()
    }

    private suspend fun flush() {
        val snapshot = accum.toMap()
        accum.clear()
        repository?.upsertBatch(snapshot, appVersion)
    }
}
