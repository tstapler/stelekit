package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
fun DeveloperSettings(
    isLibsqlDriverEnabled: Boolean,
    onLibsqlDriverToggle: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    // graph-mcp-server (Story 4.1.2): null hides this section entirely — same
    // optional-row convention every other settings param in this file already uses.
    mcpServerEnabled: Boolean = false,
    onMcpServerToggle: ((Boolean) -> Unit)? = null,
    mcpLauncherCommand: String = "",
    pendingMcpSuggestionCount: Int = 0,
    onOpenMcpSuggestions: () -> Unit = {},
) {
    Column(modifier = modifier) {
        if (onLibsqlDriverToggle != null) SettingsSection("Database Driver") {
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
        if (onMcpServerToggle != null) {
            SettingsSection("MCP Server") {
                SettingsToggleRow(
                    label = "Allow local MCP clients (Claude Desktop/Code) to search this graph",
                    checked = mcpServerEnabled,
                    onCheckedChange = onMcpServerToggle,
                )
                if (mcpServerEnabled) {
                    val clipboardManager = LocalClipboardManager.current
                    Row {
                        Text(
                            text = mcpLauncherCommand,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(mcpLauncherCommand)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy MCP launcher command")
                        }
                    }
                    Text(
                        text = "Paste into your MCP host's config (e.g. Claude Desktop: " +
                            "claude_desktop_config.json) and restart it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextButton(onClick = onOpenMcpSuggestions) {
                        Text(
                            if (pendingMcpSuggestionCount == 1) "1 pending suggestion from MCP — review"
                            else "$pendingMcpSuggestionCount pending suggestions from MCP — review",
                        )
                    }
                }
            }
        }
    }
}
