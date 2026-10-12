package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.right
import dev.stapler.stelekit.capture.CaptureTargetSettings
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.ui.components.settings.AdvancedSettings
import dev.stapler.stelekit.ui.components.settings.CategoryItem
import dev.stapler.stelekit.ui.components.settings.DeveloperSettings
import dev.stapler.stelekit.ui.components.settings.DeviceSubscriptionsPanel
import dev.stapler.stelekit.ui.components.settings.EditorSettings
import dev.stapler.stelekit.ui.components.settings.FolderSyncReconciliationProgress
import dev.stapler.stelekit.ui.components.settings.FolderSyncSettings
import dev.stapler.stelekit.ui.components.settings.GeneralSettings
import dev.stapler.stelekit.ui.components.settings.LlmProviderListScreen
import dev.stapler.stelekit.ui.components.settings.LlmProviderSettings
import dev.stapler.stelekit.ui.components.settings.PerFeatureProviderPicker
import dev.stapler.stelekit.ui.components.settings.PluginsSettings
import dev.stapler.stelekit.ui.components.settings.ProviderStatusIndicator
import dev.stapler.stelekit.ui.components.settings.ReconciliationUiState
import dev.stapler.stelekit.ui.components.settings.SectionsSettings
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsRow
import dev.stapler.stelekit.ui.components.settings.SettingsSection
import dev.stapler.stelekit.ui.components.settings.SettingsToggleRow
import dev.stapler.stelekit.ui.components.settings.TagSuggestionSettings
import dev.stapler.stelekit.ui.components.settings.VaultSettings
import dev.stapler.stelekit.ui.components.settings.VoiceCaptureSettings
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import dev.stapler.stelekit.voice.VoiceSettings

private class FixedCredentialAccess : CredentialAccess {
    private val map = mutableMapOf<String, String>()
    override fun retrieve(key: String): String? = map[key]
    override fun store(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
}

private class FixedProvider(
    override val id: String,
    override val displayName: String,
    override val kind: LlmProviderKind,
    private val availability: LlmProviderAvailability,
) : LlmProvider {
    override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
    override suspend fun checkAvailability(): LlmProviderAvailability = availability
}

private fun providerRegistry() = LlmProviderRegistry(
    listOf(
        FixedProvider("anthropic", "Anthropic Claude", LlmProviderKind.REMOTE, LlmProviderAvailability.Available),
        FixedProvider("openai", "OpenAI", LlmProviderKind.REMOTE, LlmProviderAvailability.Unavailable("Invalid API key")),
        FixedProvider("ondevice", "On-device model", LlmProviderKind.ON_DEVICE, LlmProviderAvailability.Preparing("Downloading model")),
    ),
)

private val galleryManifest = SectionManifest(
    sections = listOf(
        SectionDefinition("work", "Work", "#3B82F6", "pages/work/", "journals/work/"),
        SectionDefinition("personal", "Personal", "#10B981", "pages/personal/", "journals/personal/"),
        SectionDefinition("plain", "No colour", null, "pages/plain/", "journals/plain/"),
    ),
)

private val galleryGraphs = listOf(
    GraphInfo(GraphId("g1"), "/home/user/notes", "Notes", 0L),
    GraphInfo(GraphId("g2"), "/home/user/work", "Work", 0L),
)

private fun general(theme: StelekitThemeMode, extras: Boolean = false): @androidx.compose.runtime.Composable () -> Unit = {
  Column {
    GeneralSettings(
        currentTheme = theme,
        onThemeChange = {},
        currentLanguage = Language.ENGLISH,
        onLanguageChange = {},
        isLeftHanded = extras,
        onLeftHandedChange = {},
        hotkeyComboLabel = if (extras) "Ctrl+Alt+N" else null,
        captureTargetSettings = if (extras) CaptureTargetSettings(InMemorySettings()).also {
            it.defaultGraphId = GraphId("g1")
            it.rememberLast = true
        } else null,
        captureGraphs = if (extras) galleryGraphs else emptyList(),
    )
  }
}

private fun folderSync(state: HostAccessState) = GalleryStory("settings-folder-sync-${state::class.simpleName!!.lowercase()}") {
    FolderSyncSettings(
        hostAccessState = state,
        supportsNativeDirectoryPicker = true,
        onConnect = { ReconciliationUiState.Connecting },
        onUnlink = {},
    )
}

private fun reconciliation(id: String, state: ReconciliationUiState) = GalleryStory("settings-folder-sync-progress-$id") {
    FolderSyncReconciliationProgress(state = state, onDone = {}, onRetry = {}, onCancel = {})
}

internal val settingsStories: List<GalleryStory> = listOf(
    GalleryStory("settings-components") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSection("Section title") {
                SettingsRow("Plain row") { Text("Value") }
                SettingsToggleRow("Toggle on", true) {}
                SettingsToggleRow("Toggle off", false) {}
            }
        }
    },
    GalleryStory("settings-general-light-theme", general(StelekitThemeMode.LIGHT)),
    GalleryStory("settings-general-dark-theme", general(StelekitThemeMode.DARK)),
    GalleryStory("settings-general-system-theme", general(StelekitThemeMode.SYSTEM)),
    GalleryStory("settings-general-capture-hotkey", general(StelekitThemeMode.SYSTEM, extras = true)),
    GalleryStory("settings-editor") { EditorSettings() },
    GalleryStory("settings-advanced") { AdvancedSettings(onReindex = {}) },
    GalleryStory("settings-developer-libsql-off") { DeveloperSettings(false, {}) },
    GalleryStory("settings-developer-libsql-on") { DeveloperSettings(true, {}) },
    GalleryStory("settings-plugins-empty") { PluginsSettings() },

    folderSync(HostAccessState.NotApplicable),
    // Granted/PromptNeeded/Denied/Disconnected render identically here (their badges live elsewhere);
    // Unlinked and Connecting render nothing/an animated spinner, so they are not snapshotted.
    folderSync(HostAccessState.Granted),
    reconciliation("summary", ReconciliationUiState.Summary(identical = 42, hostChangedConflict = 2, hostOnlyNew = 5, browserOnlyNeedsPush = 3)),
    reconciliation("summary-all-identical", ReconciliationUiState.Summary(identical = 120, hostChangedConflict = 0, hostOnlyNew = 0, browserOnlyNeedsPush = 0)),
    reconciliation("failed", ReconciliationUiState.Failed("Reconciliation stopped: permission to the folder was revoked")),

    GalleryStory("settings-llm-provider-list") {
        LlmProviderListScreen(registry = providerRegistry(), onAddProvider = {}, onEditProvider = {})
    },
    GalleryStory("settings-llm-provider-list-empty") {
        LlmProviderListScreen(registry = LlmProviderRegistry(emptyList()), onAddProvider = {}, onEditProvider = {})
    },
    GalleryStory("settings-llm-provider-status") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderStatusIndicator(LlmProviderAvailability.Available)
            ProviderStatusIndicator(LlmProviderAvailability.Preparing("Downloading model"))
            ProviderStatusIndicator(LlmProviderAvailability.Unavailable("Unsupported hardware"))
            ProviderStatusIndicator(LlmProviderAvailability.Unavailable("Still initializing", retryable = true), onRetry = {})
        }
    },
    GalleryStory("settings-llm-providers-no-keys") {
        LlmProviderSettings(
            registry = LlmProviderRegistry(emptyList()),
            llmSettings = LlmSettings(InMemorySettings()),
            llmCredentialStore = LlmCredentialStore(FixedCredentialAccess()),
        )
    },
    GalleryStory("settings-llm-providers-with-key") {
        val credentials = LlmCredentialStore(FixedCredentialAccess()).also { it.setApiKey("anthropic", "sk-ant-gallery") }
        LlmProviderSettings(
            registry = providerRegistry(),
            llmSettings = LlmSettings(InMemorySettings()),
            llmCredentialStore = credentials,
        )
    },
    GalleryStory("settings-llm-per-feature-picker") {
        val llmSettings = LlmSettings(InMemorySettings())
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PerFeatureProviderPicker(LlmFeature.VOICE_FORMATTING, providerRegistry(), llmSettings)
            PerFeatureProviderPicker(LlmFeature.TAG_SUGGESTION, providerRegistry(), llmSettings)
        }
    },

    GalleryStory("settings-sections-populated") {
        SectionsSettings(galleryManifest, { _, _, _, _, _ -> }, { _, _ -> }, {})
    },
    GalleryStory("settings-sections-empty") {
        SectionsSettings(SectionManifest(), { _, _, _, _, _ -> }, { _, _ -> }, {})
    },
    GalleryStory("settings-device-subscriptions") {
        DeviceSubscriptionsPanel(
            manifest = galleryManifest,
            sectionStates = mapOf(
                "work" to SectionState.ACTIVE,
                "personal" to SectionState.HIDDEN,
                "plain" to SectionState.REMOVED,
            ),
            onToggleSection = { _, _ -> },
        )
    },

    GalleryStory("settings-tag-suggestions-with-key") {
        TagSuggestionSettings(TagSettings(InMemorySettings()), hasLlmKey = true)
    },
    GalleryStory("settings-tag-suggestions-no-key") {
        TagSuggestionSettings(TagSettings(InMemorySettings()), hasLlmKey = false)
    },
    GalleryStory("settings-tag-suggestions-disabled") {
        val settings = TagSettings(InMemorySettings()).also { it.setEnabled(false); it.setLlmTierEnabled(false) }
        TagSuggestionSettings(settings, hasLlmKey = true)
    },

    GalleryStory("settings-vault-not-created") {
        VaultSettings(isParanoidMode = false, isVaultUnlocked = false, onCreateVault = { Unit.right() })
    },
    GalleryStory("settings-vault-locked") {
        VaultSettings(isParanoidMode = true, isVaultUnlocked = false)
    },
    GalleryStory("settings-vault-unlocked") {
        VaultSettings(
            isParanoidMode = true,
            isVaultUnlocked = true,
            onAddKeyslot = { Unit.right() },
            onRemoveKeyslot = { Unit.right() },
            onLockVault = {},
            onListActiveSlots = { listOf(0, 1) },
        )
    },

    GalleryStory("settings-voice-capture-default") {
        Column { VoiceCaptureSettings(VoiceSettings(InMemorySettings()), onRebuildPipeline = {}) }
    },
    GalleryStory("settings-voice-capture-device-ai") {
        Column {
            VoiceCaptureSettings(
                VoiceSettings(InMemorySettings()),
                onRebuildPipeline = {},
                deviceSttAvailable = true,
                deviceLlmAvailable = true,
                onNavigateToAiProviders = {},
            )
        }
    },

    GalleryStory("settings-dialog-category-items") {
        Column(Modifier.fillMaxWidth()) {
            SettingsCategory.entries.forEachIndexed { index, category ->
                CategoryItem(category = category, isSelected = index == 0, onClick = {})
                if (index < SettingsCategory.entries.lastIndex) HorizontalDivider()
            }
        }
    },
)
