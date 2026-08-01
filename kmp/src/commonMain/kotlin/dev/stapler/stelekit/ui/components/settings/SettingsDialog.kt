package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.performance.getDeviceInfo
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.isMobile
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
    // AI Providers settings (Epic 6) — all three non-null together enable the category.
    llmProviderRegistry: LlmProviderRegistry? = null,
    llmSettings: LlmSettings? = null,
    initialCategory: SettingsCategory = SettingsCategory.GENERAL,
    onLlmCredentialsChange: () -> Unit = {},
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
    // web-local-folder-livesync (Task 3.1.1c): "Enable live folder sync" affordance for an
    // already-populated graph — Story 3.1.1, Epic 3.1. hostAccessState/supportsNativeDirectoryPicker
    // default to their "not applicable"/false values on JVM/Android/iOS; onConnectHostDirectory
    // stays null there, which is what actually hides FolderSyncSettings's call site below (its own
    // internal gate on hostAccessState/supportsNativeDirectoryPicker is a second, redundant guard
    // for the web case where a null onConnectHostDirectory is never passed in the first place).
    hostAccessState: HostAccessState = HostAccessState.NotApplicable,
    supportsNativeDirectoryPicker: Boolean = false,
    onConnectHostDirectory: (suspend () -> ReconciliationUiState)? = null,
) {
    if (visible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var selectedCategory by remember { mutableStateOf(initialCategory) }
            // Mobile is a drill-down page (category list -> full-width detail), not a
            // side-by-side pane — a fixed sidebar width leaves too little room for content
            // on phone-sized screens and causes headings like "Tag Suggestions" to wrap
            // mid-word. Deep links (e.g. initialCategory = LLM_PROVIDERS) skip the list.
            var showingCategoryList by remember(initialCategory) { mutableStateOf(initialCategory == SettingsCategory.GENERAL) }

            val visibleCategories = remember(
                onConnectGoogle, audiobookNotesSettingsContent, tagSettings,
                onLibsqlDriverToggle, sectionManifest, llmProviderRegistry, llmSettings,
                llmCredentialStore,
            ) {
                SettingsCategory.entries.filter { category ->
                    when (category) {
                        SettingsCategory.GOOGLE_ACCOUNT -> onConnectGoogle != null
                        SettingsCategory.AUDIOBOOK_NOTES -> audiobookNotesSettingsContent != null
                        SettingsCategory.TAG_SUGGESTIONS -> tagSettings != null
                        SettingsCategory.DEVELOPER -> onLibsqlDriverToggle != null
                        SettingsCategory.SECTIONS -> sectionManifest != null
                        SettingsCategory.DEVICE_SUBSCRIPTIONS -> sectionManifest != null
                        SettingsCategory.LLM_PROVIDERS ->
                            llmProviderRegistry != null && llmSettings != null && llmCredentialStore != null
                        else -> true
                    }
                }
            }

            @Composable
            fun CategoryContent(category: SettingsCategory) {
                when (category) {
                    SettingsCategory.GENERAL -> {
                        GeneralSettings(
                            currentTheme = currentTheme,
                            onThemeChange = onThemeChange,
                            currentLanguage = currentLanguage,
                            onLanguageChange = onLanguageChange,
                            isLeftHanded = isLeftHanded,
                            onLeftHandedChange = onLeftHandedChange
                        )
                        // web-local-folder-livesync Task 3.1.1c: no dedicated "Sync"
                        // category exists in this dialog, so the affordance lives here —
                        // GENERAL is the most discoverable home for a graph-wide toggle,
                        // and FolderSyncSettings itself is a no-op render (returns
                        // nothing) unless onConnectHostDirectory is non-null AND its own
                        // hostAccessState/supportsNativeDirectoryPicker gate passes, so
                        // this never adds an empty section to GENERAL on non-web
                        // platforms or once already connected.
                        if (onConnectHostDirectory != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            FolderSyncSettings(
                                hostAccessState = hostAccessState,
                                supportsNativeDirectoryPicker = supportsNativeDirectoryPicker,
                                onConnect = onConnectHostDirectory,
                            )
                        }
                    }
                    SettingsCategory.EDITOR -> EditorSettings()
                    SettingsCategory.PLUGINS -> PluginsSettings()
                    SettingsCategory.ADVANCED -> AdvancedSettings(onReindex)
                    SettingsCategory.VOICE -> if (voiceSettings != null && onRebuildVoicePipeline != null) {
                        VoiceCaptureSettings(
                            voiceSettings = voiceSettings,
                            onRebuildPipeline = onRebuildVoicePipeline,
                            deviceSttAvailable = deviceSttAvailable,
                            deviceLlmAvailable = deviceLlmAvailable,
                            onNavigateToAiProviders = {
                                selectedCategory = SettingsCategory.LLM_PROVIDERS
                                showingCategoryList = false
                            },
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
                    SettingsCategory.SECTIONS -> {
                        val canShowSections = sectionManifest != null &&
                            onCreateSection != null && onRenameSection != null &&
                            onDeleteSection != null
                        if (canShowSections) {
                            SectionsSettings(
                                manifest = sectionManifest!!,
                                onCreateSection = onCreateSection!!,
                                onRenameSection = onRenameSection!!,
                                onDeleteSection = onDeleteSection!!,
                            )
                        }
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
                    SettingsCategory.LLM_PROVIDERS -> if (llmProviderRegistry != null &&
                        llmSettings != null && llmCredentialStore != null
                    ) {
                        LlmProviderSettings(
                            registry = llmProviderRegistry,
                            llmSettings = llmSettings,
                            llmCredentialStore = llmCredentialStore,
                            onCredentialsChange = onLlmCredentialsChange,
                        )
                    }
                }
            }

            val isMobile = LocalWindowSizeClass.current.isMobile

            if (isMobile) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    if (showingCategoryList) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                            HorizontalDivider()
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(visibleCategories) { category ->
                                    CategoryItem(
                                        category = category,
                                        // Always false: this drill-down list has no persisted
                                        // selection state — tapping a row navigates straight to
                                        // the detail page (see showingCategoryList = false below).
                                        isSelected = false,
                                        onClick = {
                                            selectedCategory = category
                                            showingCategoryList = false
                                        }
                                    )
                                }
                            }
                            val appVersion = remember { getDeviceInfo().appVersion }
                            Text(
                                "v$appVersion",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { showingCategoryList = true }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                                Text(
                                    text = selectedCategory.label,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                            HorizontalDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                CategoryContent(selectedCategory)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .fillMaxHeight(0.8f),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
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
                                CategoryContent(selectedCategory)
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
    LLM_PROVIDERS("AI Providers", Icons.Default.SmartToy),
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
