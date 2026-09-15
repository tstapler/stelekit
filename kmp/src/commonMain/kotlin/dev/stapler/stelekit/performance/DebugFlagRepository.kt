package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedTelemetryQueries
import dev.stapler.stelekit.db.TelemetryDatabase
import kotlinx.coroutines.sync.Mutex

/**
 * CRUD operations over the [debug_flags] SQLDelight table.
 * All reads/writes are synchronous (called from Dispatchers.IO).
 */
class DebugFlagRepository(private val database: TelemetryDatabase, writeMutex: Mutex = Mutex()) {
    private val restricted = RestrictedTelemetryQueries(database.telemetryQueries, writeMutex)

    @OptIn(DirectSqlWrite::class)
    suspend fun setFlag(key: String, enabled: Boolean) {
        restricted.withWriteLock {
            restricted.upsertDebugFlag(
                key = key,
                value_ = if (enabled) 1L else 0L,
                updated_at = HistogramWriter.epochMs()
            )
        }
    }

    fun getFlag(key: String, default: Boolean = false): Boolean {
        val row = database.telemetryQueries.selectDebugFlag(key).executeAsOneOrNull()
        return if (row != null) row != 0L else default
    }

    fun loadDebugMenuState(): DebugMenuState = DebugMenuState(
        isFrameOverlayEnabled = getFlag("frame_overlay"),
        isOtelStdoutEnabled = getFlag("otel_stdout"),
        isJankStatsEnabled = getFlag("jank_stats"),
        isQueryTracingEnabled = getFlag("query_tracing"),
        isDebugMenuVisible = false,  // never persisted as visible
        isSpanCaptureEnabled = getFlag("span_capture"),
    )

    suspend fun saveDebugMenuState(state: DebugMenuState) {
        val now = HistogramWriter.epochMs()
        @OptIn(DirectSqlWrite::class)
        restricted.withWriteLock {
            restricted.transaction {
                restricted.upsertDebugFlag("frame_overlay", if (state.isFrameOverlayEnabled) 1L else 0L, now)
                restricted.upsertDebugFlag("otel_stdout", if (state.isOtelStdoutEnabled) 1L else 0L, now)
                restricted.upsertDebugFlag("jank_stats", if (state.isJankStatsEnabled) 1L else 0L, now)
                restricted.upsertDebugFlag("query_tracing", if (state.isQueryTracingEnabled) 1L else 0L, now)
                restricted.upsertDebugFlag("span_capture", if (state.isSpanCaptureEnabled) 1L else 0L, now)
            }
        }
    }
}
