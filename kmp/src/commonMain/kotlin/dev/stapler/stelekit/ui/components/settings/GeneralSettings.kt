package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
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
}
