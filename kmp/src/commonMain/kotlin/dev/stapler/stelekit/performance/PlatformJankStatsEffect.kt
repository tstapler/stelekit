package dev.stapler.stelekit.performance

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformJankStatsEffect(histogramWriter: HistogramWriter?, isEnabled: Boolean)
