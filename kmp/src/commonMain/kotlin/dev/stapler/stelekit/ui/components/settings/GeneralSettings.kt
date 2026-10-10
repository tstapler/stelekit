package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.capture.CaptureTargetSettings
import dev.stapler.stelekit.model.GraphInfo
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.platform.isDynamicColorSupported
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.ui.i18n.Language

fun themeModeLabel(mode: StelekitThemeMode): String = when (mode) {
    StelekitThemeMode.LIGHT -> "Light"
    StelekitThemeMode.DARK -> "Dark"
    StelekitThemeMode.SYSTEM -> "System"
    StelekitThemeMode.STONE -> "Stone"
    StelekitThemeMode.DYNAMIC -> "Dynamic (Material You)"
}

@Composable
fun GeneralSettings(
    currentTheme: StelekitThemeMode,
    onThemeChange: (StelekitThemeMode) -> Unit,
    currentLanguage: Language,
    onLanguageChange: (Language) -> Unit,
    isLeftHanded: Boolean = false,
    onLeftHandedChange: (Boolean) -> Unit = {},
    // Desktop-only (Story 1.4.2): the quick-capture hotkey combo, revisitable here after the
    // one-time FirstRunHotkeyNotice is dismissed. Null (default) hides the section — Android/
    // iOS/web builds never wire a value in since there's no global hotkey to show.
    hotkeyComboLabel: String? = null,
    // Null hides the capture section; graphs are the registered graphs to pick from.
    captureTargetSettings: CaptureTargetSettings? = null,
    captureGraphs: List<GraphInfo> = emptyList(),
) {
    SettingsSection("Appearance") {
        SettingsRow("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StelekitThemeMode.entries
                    .filter { it != StelekitThemeMode.DYNAMIC || isDynamicColorSupported() }
                    .forEach { mode ->
                        FilterChip(
                            selected = currentTheme == mode,
                            onClick = { onThemeChange(mode) },
                            label = { Text(themeModeLabel(mode)) }
                        )
                    }
            }
        }
    }

    SettingsSection("Localization") {
        SettingsRow("Language") {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(currentLanguage.name.lowercase().replaceFirstChar { it.uppercase() })
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    Language.entries.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                onLanguageChange(language)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    SettingsSection("Accessibility") {
        SettingsToggleRow(
            label = "Left-handed mode",
            checked = isLeftHanded,
            onCheckedChange = onLeftHandedChange
        )
    }

    if (hotkeyComboLabel != null) {
        SettingsSection("Keyboard Shortcuts") {
            SettingsRow("Quick Capture") {
                Text(hotkeyComboLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (captureTargetSettings != null) {
        CaptureTargetSection(captureTargetSettings, captureGraphs)
    }
}

private const val ACTIVE_GRAPH_LABEL = "Active graph"

@Composable
private fun CaptureTargetSection(settings: CaptureTargetSettings, graphs: List<GraphInfo>) {
    var defaultId by remember(settings) { mutableStateOf(settings.defaultGraphId) }
    var rememberLast by remember(settings) { mutableStateOf(settings.rememberLast) }
    // A deleted default shows as "Active graph", matching what the resolver will do.
    val defaultLabel = graphs.firstOrNull { it.id == defaultId }?.displayName ?: ACTIVE_GRAPH_LABEL

    SettingsSection("Capture") {
        Text(
            "Sharing and quick capture on Android and Desktop add to this graph\u2019s journal.",
            style = MaterialTheme.typography.bodySmall,
        )
        SettingsRow("Default capture graph") {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Default capture graph: $defaultLabel. Opens a list of graphs."
                    },
                ) {
                    Text(defaultLabel)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(ACTIVE_GRAPH_LABEL) },
                        onClick = { defaultId = null; settings.defaultGraphId = null; expanded = false },
                    )
                    graphs.forEach { graph ->
                        DropdownMenuItem(
                            text = { Text(graph.displayName) },
                            onClick = { defaultId = graph.id; settings.defaultGraphId = graph.id; expanded = false },
                        )
                    }
                }
            }
        }
        SettingsToggleRow(
            label = "Remember last used",
            checked = rememberLast,
            onCheckedChange = { rememberLast = it; settings.rememberLast = it },
        )
    }
}
