package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeveloperSettings(
    isLibsqlDriverEnabled: Boolean,
    onLibsqlDriverToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingsSection("Database Driver") {
            SettingsToggleRow(
                label = "Use libsql JNI driver",
                checked = isLibsqlDriverEnabled,
                onCheckedChange = onLibsqlDriverToggle,
            )
            Text(
                text = if (isLibsqlDriverEnabled)
                    "Active: libsql JNI driver (WAL mode). Reload the graph to apply."
                else
                    "Active: system SQLite. Reload the graph to apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}
