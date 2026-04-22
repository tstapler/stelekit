package dev.stapler.stelekit.performance

data class FrameMetric(val lastFrameMs: Long = 0L, val isJank: Boolean = false)
