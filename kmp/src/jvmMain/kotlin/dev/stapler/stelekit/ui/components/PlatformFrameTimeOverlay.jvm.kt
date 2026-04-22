package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.performance.FrameMetric
import kotlinx.coroutines.flow.StateFlow

@Composable
actual fun PlatformFrameTimeOverlay(isEnabled: Boolean, frameMetric: StateFlow<FrameMetric>) {
    if (!isEnabled) return
    val metric by frameMetric.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        val bg = if (metric.isJank) Color(0xCCCC0000) else Color(0xCC005500)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(bg, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${metric.lastFrameMs}ms",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (metric.isJank) {
                Text("JANK", color = Color.Yellow, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
