package dev.stapler.stelekit.performance

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun PlatformJankStatsEffect(histogramWriter: HistogramWriter?, isEnabled: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    val manager = remember(histogramWriter) {
        histogramWriter?.let { JankStatsManager(it) }
    }

    DisposableEffect(manager, isEnabled) {
        if (isEnabled && manager != null) {
            manager.register(activity)
            JankStatsHolder.instance = manager
        }
        onDispose {
            manager?.unregister()
            if (JankStatsHolder.instance === manager) JankStatsHolder.instance = null
        }
    }
}
