package dev.stapler.stelekit.performance

data class DebugMenuState(
    val isFrameOverlayEnabled: Boolean = false,
    val isOtelStdoutEnabled: Boolean = false,
    val isJankStatsEnabled: Boolean = false,
    val isQueryTracingEnabled: Boolean = false,
    val isDebugMenuVisible: Boolean = false,
    /** When true, spans are recorded to the ring buffer and persisted to SQLite. Histograms are always collected. */
    val isSpanCaptureEnabled: Boolean = false,
)
