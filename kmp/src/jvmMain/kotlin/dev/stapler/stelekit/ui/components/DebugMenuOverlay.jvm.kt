package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.performance.DebugMenuState

@Composable
actual fun DebugMenuOverlay(
    state: DebugMenuState,
    onStateChange: (DebugMenuState) -> Unit,
    onExportBugReport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Menu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DebugToggleRow("Frame Overlay", state.isFrameOverlayEnabled) {
                    onStateChange(state.copy(isFrameOverlayEnabled = it))
                }
                DebugToggleRow("OTel Stdout", state.isOtelStdoutEnabled) {
                    onStateChange(state.copy(isOtelStdoutEnabled = it))
                }
                DebugToggleRow("JankStats", state.isJankStatsEnabled) {
                    onStateChange(state.copy(isJankStatsEnabled = it))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onExportBugReport, modifier = Modifier.fillMaxWidth()) {
                    Text("Export Bug Report")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun DebugToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
