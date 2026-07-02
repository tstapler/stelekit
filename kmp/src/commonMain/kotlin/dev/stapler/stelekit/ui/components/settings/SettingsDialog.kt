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
import arrow.core.Either
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.performance.getDeviceInfo
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.vault.VaultError
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
    llmCredentialStore: LlmCredentialStore? = null,
    onRebuildVoicePipeline: (() -> Unit)? = null,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
    // Google Account settings (Story 7.2)
    isGoogleAuthenticated: Boolean = false,
    googleConnectedEmail: String? = null,
    isGoogleConnecting: Boolean = false,
    googleAuthError: String? = null,
    onConnectGoogle: (() -> Unit)? = null,
    onDisconnectGoogle: (() -> Unit)? = null,
    // Vault / encryption settings
    isParanoidMode: Boolean = false,
    isVaultUnlocked: Boolean = false,
    onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
    onLockVault: (() -> Unit)? = null,
    onListActiveSlots: (suspend () -> List<Int>)? = null,
    // Audiobook Notes settings (Android Auto feature; null hides the category on non-Android platforms)
    audiobookNotesSettingsContent: (@Composable () -> Unit)? = null,
    // Tag Suggestions settings
    tagSettings: TagSettings? = null,
    hasLlmKey: Boolean = false,
    // Developer settings
    isLibsqlDriverEnabled: Boolean = false,
    onLibsqlDriverToggle: ((Boolean) -> Unit)? = null,
    // Section settings
    sectionManifest: SectionManifest? = null,
    sectionStates: Map<String, SectionState> = emptyMap(),
    onCreateSection: ((id: String, displayName: String, color: String?, pagePathPrefix: String, journalPathPrefix: String) -> Unit)? = null,
    onRenameSection: ((id: String, newDisplayName: String) -> Unit)? = null,
    onDeleteSection: ((id: String) -> Unit)? = null,
    onToggleSectionState: ((sectionId: String, newState: SectionState) -> Unit)? = null,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val visibleCategories = remember(onConnectGoogle, audiobookNotesSettingsContent, tagSettings, onLibsqlDriverToggle, sectionManifest) {
                            SettingsCategory.entries.filter { category ->
                                when (category) {
                                    SettingsCategory.GOOGLE_ACCOUNT -> onConnectGoogle != null
                                    SettingsCategory.AUDIOBOOK_NOTES -> audiobookNotesSettingsContent != null
                                    SettingsCategory.TAG_SUGGESTIONS -> tagSettings != null
                                    SettingsCategory.DEVELOPER -> onLibsqlDriverToggle != null
                                    SettingsCategory.SECTIONS -> sectionManifest != null
                                    SettingsCategory.DEVICE_SUBSCRIPTIONS -> sectionManifest != null
                                    else -> true
                                }
                            }
                        }
                        visibleCategories.forEach { category ->
                            CategoryItem(
                                category = category,
                                isSelected = selectedCategory == category,
                                onClick = { selectedCategory = category }
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        val appVersion = remember { getDeviceInfo().appVersion }
                        Text(
                            "v$appVersion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
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
                                SettingsCategory.VOICE -> if (voiceSettings != null && llmCredentialStore != null && onRebuildVoicePipeline != null) {
                                    VoiceCaptureSettings(
                                        voiceSettings = voiceSettings,
                                        llmCredentialStore = llmCredentialStore,
                                        onRebuildPipeline = onRebuildVoicePipeline,
                                        deviceSttAvailable = deviceSttAvailable,
                                        deviceLlmAvailable = deviceLlmAvailable,
                                    )
                                }
                                SettingsCategory.AUDIOBOOK_NOTES -> audiobookNotesSettingsContent?.invoke()
                                SettingsCategory.GOOGLE_ACCOUNT -> GoogleAccountSettings(
                                    isAuthenticated = isGoogleAuthenticated,
                                    connectedEmail = googleConnectedEmail,
                                    isConnecting = isGoogleConnecting,
                                    errorMessage = googleAuthError,
                                    onConnect = { onConnectGoogle?.invoke() },
                                    onDisconnect = { onDisconnectGoogle?.invoke() },
                                )
                                SettingsCategory.VAULT -> VaultSettings(
                                    isParanoidMode = isParanoidMode,
                                    isVaultUnlocked = isVaultUnlocked,
                                    onCreateVault = onCreateVault,
                                    onAddKeyslot = onAddKeyslot,
                                    onRemoveKeyslot = onRemoveKeyslot,
                                    onLockVault = onLockVault,
                                    onListActiveSlots = onListActiveSlots,
                                )
                                SettingsCategory.TAG_SUGGESTIONS -> if (tagSettings != null) {
                                    TagSuggestionSettings(
                                        tagSettings = tagSettings,
                                        hasLlmKey = hasLlmKey,
                                    )
                                }
                                SettingsCategory.DEVELOPER -> if (onLibsqlDriverToggle != null) {
                                    DeveloperSettings(
                                        isLibsqlDriverEnabled = isLibsqlDriverEnabled,
                                        onLibsqlDriverToggle = onLibsqlDriverToggle,
                                    )
                                }
                                SettingsCategory.SECTIONS -> if (sectionManifest != null &&
                                    onCreateSection != null && onRenameSection != null && onDeleteSection != null
                                ) {
                                    SectionsSettings(
                                        manifest = sectionManifest,
                                        onCreateSection = onCreateSection,
                                        onRenameSection = onRenameSection,
                                        onDeleteSection = onDeleteSection,
                                    )
                                }
                                SettingsCategory.DEVICE_SUBSCRIPTIONS -> if (sectionManifest != null &&
                                    onToggleSectionState != null
                                ) {
                                    DeviceSubscriptionsPanel(
                                        manifest = sectionManifest,
                                        sectionStates = sectionStates,
                                        onToggleSection = onToggleSectionState,
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
    TAG_SUGGESTIONS("Tag Suggestions", Icons.Default.Label),
    AUDIOBOOK_NOTES("Audiobook Notes", Icons.Default.Book),
    GOOGLE_ACCOUNT("Google Account", Icons.Default.Cloud),
    VAULT("Vault", Icons.Default.Lock),
    SECTIONS("Sections", Icons.Default.Folder),
    DEVICE_SUBSCRIPTIONS("Device Subscriptions", Icons.Default.Notifications),
    DEVELOPER("Developer", Icons.Default.BugReport),
}

@Composable
private fun DeveloperSettings(
    isLibsqlDriverEnabled: Boolean,
    onLibsqlDriverToggle: (Boolean) -> Unit,
) {
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
            modifier = androidx.compose.ui.Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
    }
}
