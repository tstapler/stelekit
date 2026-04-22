package dev.stapler.stelekit.performance

data class DebugMenuState(
    val isFrameOverlayEnabled: Boolean = false,
    val isOtelStdoutEnabled: Boolean = false,
    val isJankStatsEnabled: Boolean = false,
    val isQueryTracingEnabled: Boolean = false,
    val isDebugMenuVisible: Boolean = false
)
