package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase

/**
 * CRUD operations over the [debug_flags] SQLDelight table.
 * All reads/writes are synchronous (called from Dispatchers.IO).
 */
class DebugFlagRepository(private val database: SteleDatabase) {
    private val restricted = RestrictedDatabaseQueries(database.steleDatabaseQueries)

    @OptIn(DirectSqlWrite::class)
    fun setFlag(key: String, enabled: Boolean) {
        restricted.upsertDebugFlag(
            key = key,
            value_ = if (enabled) 1L else 0L,
            updated_at = HistogramWriter.epochMs()
        )
    }

    fun getFlag(key: String, default: Boolean = false): Boolean {
        val row = database.steleDatabaseQueries.selectDebugFlag(key).executeAsOneOrNull()
        return if (row != null) row != 0L else default
    }

    fun loadDebugMenuState(): DebugMenuState = DebugMenuState(
        isFrameOverlayEnabled = getFlag("frame_overlay"),
        isOtelStdoutEnabled = getFlag("otel_stdout"),
        isJankStatsEnabled = getFlag("jank_stats"),
        isDebugMenuVisible = false  // never persisted as visible
    )

    fun saveDebugMenuState(state: DebugMenuState) {
        setFlag("frame_overlay", state.isFrameOverlayEnabled)
        setFlag("otel_stdout", state.isOtelStdoutEnabled)
        setFlag("jank_stats", state.isJankStatsEnabled)
    }
}
