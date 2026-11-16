package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.Settings

@Composable
fun AdvancedSettings(onReindex: () -> Unit, annotatorSettings: Settings? = null) {
    if (annotatorSettings != null) {
        SettingsSection("Image annotator") {
            AnnotatorStickyToolsRow(annotatorSettings)
        }
    }
    SettingsSection("Danger Zone") {
        Text(
            "If your graph data seems inconsistent or missing, you can force a full re-index of your files into the database.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = onReindex,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Re-index Graph")
        }
    }
}
