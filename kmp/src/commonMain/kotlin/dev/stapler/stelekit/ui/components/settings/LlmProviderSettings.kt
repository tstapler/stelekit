// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.stapler.stelekit.llm.CustomOpenAiCompatibleLlmProvider
import dev.stapler.stelekit.llm.CustomProviderConfig
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Built-in remote provider types eligible for credential entry (Epic 1/2's v1 set). */
private data class BuiltInProviderCatalogEntry(val id: String, val displayName: String)

private val builtInProviderCatalog = listOf(
    BuiltInProviderCatalogEntry("anthropic", "Anthropic Claude"),
    BuiltInProviderCatalogEntry("openai", "OpenAI"),
)

/**
 * Epic 6 Story 6.5: orchestrates the provider-hub Settings surface — [LlmProviderListScreen]
 * (Story 6.2), [AddEditLlmProviderDialog] for custom instances (Story 6.3), a lightweight
 * built-in-provider credential dialog, and one [PerFeatureProviderPicker] per [LlmFeature]
 * (Story 6.4) — as the content of `SettingsCategory.LLM_PROVIDERS`. A single provider-hub
 * surface per features research's synthesized design, not per-feature config screens.
 */
@Composable
fun LlmProviderSettings(
    registry: LlmProviderRegistry,
    llmSettings: LlmSettings,
    llmCredentialStore: LlmCredentialStore,
    modifier: Modifier = Modifier,
    onCredentialsChange: () -> Unit = {},
) {
    var showAddCustomProvider by remember { mutableStateOf(false) }
    var editingCustomProviderId by remember { mutableStateOf<String?>(null) }
    var editingBuiltInProviderId by remember { mutableStateOf<String?>(null) }

    // buildLlmProviderRegistry() (Epic 1/2) only includes a built-in remote provider once it
    // has a configured key — a not-yet-configured Anthropic/OpenAI has no row in
    // registry.all() at all. Without this catalog, a first-time user would have no way to
    // discover/add a key for a built-in provider through this screen, which would make Story
    // 6.6's "Configure AI provider keys in Settings → AI Providers" redirect note a dead end.
    val configuredIds = registry.all().map { it.id }.toSet()
    val unconfiguredBuiltIns = builtInProviderCatalog.filter { it.id !in configuredIds }

    Column(modifier = modifier.fillMaxWidth()) {
        LlmProviderListScreen(
            registry = registry,
            onAddProvider = { showAddCustomProvider = true },
            onEditProvider = { id ->
                if (id.startsWith("custom:")) {
                    editingCustomProviderId = id
                } else if (registry.find(id)?.kind != LlmProviderKind.ON_DEVICE) {
                    editingBuiltInProviderId = id
                }
                // ON_DEVICE providers have no credentials — click is intentionally a no-op
            },
        )

        if (unconfiguredBuiltIns.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Not yet configured", style = MaterialTheme.typography.titleSmall)
            unconfiguredBuiltIns.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { editingBuiltInProviderId = entry.id }) {
                        Text("Add key")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text("Feature providers", style = MaterialTheme.typography.titleMedium)
        Text(
            "Choose which provider each AI feature uses, or leave on Auto to use the first " +
                "available provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        LlmFeature.entries.forEach { feature ->
            PerFeatureProviderPicker(feature = feature, registry = registry, llmSettings = llmSettings)
        }
    }

    if (showAddCustomProvider) {
        AddEditLlmProviderDialog(
            existingConfig = null,
            onSave = { config, apiKey ->
                llmSettings.setCustomProviderConfig(config)
                apiKey?.let { llmCredentialStore.setApiKey(config.id, it) }
                showAddCustomProvider = false
                onCredentialsChange()
            },
            onCancel = { showAddCustomProvider = false },
            fetchModels = ::fetchCustomProviderModels,
        )
    }

    editingCustomProviderId?.let { id ->
        AddEditLlmProviderDialog(
            existingConfig = llmSettings.getCustomProviderConfig(id),
            onSave = { config, apiKey ->
                llmSettings.setCustomProviderConfig(config)
                apiKey?.let { llmCredentialStore.setApiKey(config.id, it) }
                editingCustomProviderId = null
                onCredentialsChange()
            },
            onCancel = { editingCustomProviderId = null },
            fetchModels = ::fetchCustomProviderModels,
        )
    }

    editingBuiltInProviderId?.let { id ->
        val displayName = registry.find(id)?.displayName
            ?: builtInProviderCatalog.firstOrNull { it.id == id }?.displayName
            ?: id
        EditBuiltInProviderKeyDialog(
            providerId = id,
            displayName = displayName,
            llmCredentialStore = llmCredentialStore,
            onDismiss = {
                editingBuiltInProviderId = null
                onCredentialsChange()
            },
        )
    }
}

/**
 * Simple API-key-only edit dialog for the built-in singleton providers (Anthropic/OpenAI/
 * Gemini) — no type picker, base URL, or model field, since those are fixed for built-ins.
 * On-device providers have no credentials to edit and never open this dialog (see
 * [LlmProviderListScreen]'s row click wiring in the caller).
 */
@Composable
private fun EditBuiltInProviderKeyDialog(
    providerId: String,
    displayName: String,
    llmCredentialStore: LlmCredentialStore,
    onDismiss: () -> Unit,
) {
    var apiKey by remember(providerId) { mutableStateOf(llmCredentialStore.getApiKey(providerId) ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Edit $displayName", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                llmCredentialStore.deleteApiKey(providerId)
                            } else {
                                llmCredentialStore.setApiKey(providerId, apiKey)
                            }
                            onDismiss()
                        },
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

/**
 * MA3 fix: [AddEditLlmProviderDialog]'s "Fetch models" action used to default to a hardcoded
 * "not available yet" stub citing an unlanded Epic 3 — that epic has since landed and
 * [CustomOpenAiCompatibleLlmProvider.fetchAvailableModels] is fully implemented. Both
 * production call sites above now wire this real implementation through instead of relying on
 * the dialog's stub default.
 *
 * Builds a throwaway [CustomOpenAiCompatibleLlmProvider] from the in-progress form fields
 * (not yet a persisted [CustomProviderConfig] — the user may still be editing baseUrl/apiKey
 * before saving) purely to reuse its `/v1/models` probe logic.
 */
private suspend fun fetchCustomProviderModels(baseUrl: String, apiKey: String?): FetchModelsResult {
    val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    return try {
        val provider = CustomOpenAiCompatibleLlmProvider(
            config = CustomProviderConfig(id = "probe", displayName = "", baseUrl = baseUrl, model = ""),
            apiKey = apiKey,
            httpClient = client,
        )
        provider.fetchAvailableModels().fold(
            { error -> FetchModelsResult.Failure(error.message) },
            { models -> FetchModelsResult.Success(models) },
        )
    } finally {
        client.close()
    }
}
