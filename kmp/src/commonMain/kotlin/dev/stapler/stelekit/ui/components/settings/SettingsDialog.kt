package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.voice.VoiceSettings

@Composable
fun SettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    currentTheme: StelekitThemeMode,
    onThemeChange: (StelekitThemeMode) -> Unit,
    currentLanguage: Language,
    onLanguageChange: (Language) -> Unit,
    onReindex: () -> Unit,
    isLeftHanded: Boolean = false,
    onLeftHandedChange: (Boolean) -> Unit = {},
    voiceSettings: VoiceSettings? = null,
    onRebuildVoicePipeline: (() -> Unit)? = null,
) {
    if (visible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .fillMaxHeight(0.8f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                var selectedCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }

                Row(modifier = Modifier.fillMaxSize()) {
                    // Sidebar
                    Column(
                        modifier = Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(vertical = 16.dp)
                    ) {
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SettingsCategory.entries.forEach { category ->
                            CategoryItem(
                                category = category,
                                isSelected = selectedCategory == category,
                                onClick = { selectedCategory = category }
                            )
                        }
                    }

                    // Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedCategory.label,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            when (selectedCategory) {
                                SettingsCategory.GENERAL -> GeneralSettings(
                                    currentTheme = currentTheme,
                                    onThemeChange = onThemeChange,
                                    currentLanguage = currentLanguage,
                                    onLanguageChange = onLanguageChange,
                                    isLeftHanded = isLeftHanded,
                                    onLeftHandedChange = onLeftHandedChange
                                )
                                SettingsCategory.EDITOR -> EditorSettings()
                                SettingsCategory.PLUGINS -> PluginsSettings()
                                SettingsCategory.ADVANCED -> AdvancedSettings(onReindex)
                                SettingsCategory.VOICE -> if (voiceSettings != null && onRebuildVoicePipeline != null) {
                                    VoiceCaptureSettings(
                                        voiceSettings = voiceSettings,
                                        onRebuildPipeline = onRebuildVoicePipeline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = category.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class SettingsCategory(val label: String, val icon: ImageVector) {
    GENERAL("General", Icons.Default.Settings),
    EDITOR("Editor", Icons.Default.Edit),
    PLUGINS("Plugins", Icons.Default.Extension),
    ADVANCED("Advanced", Icons.Default.Build),
    VOICE("Voice Capture", Icons.Default.Mic),
}
