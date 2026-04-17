package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.performance.JankStatsManager

/**
 * Debug overlay shown in the top-right corner when [DebugMenuState.isFrameOverlayEnabled] is true.
 * Displays the last frame duration and whether it was classified as janky.
 */
@Composable
fun FrameTimeOverlay(jankStatsManager: JankStatsManager) {
    val frameMetric by jankStatsManager.frameMetric.collectAsState()
    val bg = if (frameMetric.isJank) Color(0xCCFF4444) else Color(0xCC222222)
    Box(
        modifier = Modifier
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${frameMetric.lastFrameMs}ms${if (frameMetric.isJank) " JANK" else ""}",
            color = Color.White,
            fontSize = 11.sp
        )
    }
}
