// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.llm.CustomProviderConfig
import dev.stapler.stelekit.llm.CustomProviderUrlValidation
import kotlinx.coroutines.launch

/** A base-URL quick-pick preset, per pitfalls §4's explicit UX recommendation (Story 6.3). */
data class BaseUrlPreset(val label: String, val baseUrl: String)

val DEFAULT_BASE_URL_PRESETS = listOf(
    BaseUrlPreset("Ollama (default)", "http://localhost:11434/v1"),
    BaseUrlPreset("LM Studio (default)", "http://localhost:1234/v1"),
)

/** Outcome of a "Fetch models" probe. */
sealed interface FetchModelsResult {
    data class Success(val models: List<String>) : FetchModelsResult
    data class Failure(val message: String) : FetchModelsResult
}

/**
 * Epic 6 Story 6.3: provider-type-aware add/edit form for a **custom OpenAI-compatible**
 * provider instance (built-in providers — Anthropic/OpenAI/Gemini/on-device — are singletons
 * with no "type" picker and are edited via [EditBuiltInProviderKeyDialog] instead).
 *
 * [fetchModels] both populates the model dropdown and doubles as connectivity validation
 * (Obsidian AI Providers pattern, per features research §1) — resolves requirements Open
 * Question 2. It is injected rather than calling `CustomOpenAiCompatibleLlmProvider` directly
 * so this file stays UI-only and testable without a real HTTP client; production call sites
 * (`LlmProviderSettings.kt`) wire the real `CustomOpenAiCompatibleLlmProvider.fetchAvailableModels`
 * implementation through. The default implementation below is only a fallback for callers (and
 * tests) that don't supply one — it returns a clear "not yet available" failure without
 * blocking manual model-name entry (per Story 6.3 acceptance criteria).
 */
@Composable
fun AddEditLlmProviderDialog(
    existingConfig: CustomProviderConfig?,
    onSave: (CustomProviderConfig, apiKey: String?) -> Unit,
    onCancel: () -> Unit,
    fetchModels: suspend (baseUrl: String, apiKey: String?) -> FetchModelsResult = { _, _ ->
        FetchModelsResult.Failure(
            "Fetching models for custom providers isn't available yet — enter the model name manually.",
        )
    },
) {
    var displayName by remember { mutableStateOf(existingConfig?.displayName ?: "") }
    var baseUrl by remember { mutableStateOf(existingConfig?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(existingConfig?.model ?: "") }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchResult by remember { mutableStateOf<FetchModelsResult?>(null) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val urlValidationError = CustomProviderUrlValidation.validate(baseUrl)
    val canSave = displayName.isNotBlank() && baseUrl.isNotBlank() && urlValidationError == null

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.6f).padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    if (existingConfig == null) "Add custom provider" else "Edit custom provider",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "OpenAI-compatible endpoint — covers Ollama, LM Studio, Azure OpenAI, " +
                        "OpenRouter, and similar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; fetchResult = null },
                        label = { Text("Base URL") },
                        singleLine = true,
                        isError = urlValidationError != null,
                        supportingText = {
                            val message = urlValidationError
                            if (message != null) {
                                Text(message, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        trailingIcon = {
                            TextButton(onClick = { presetMenuExpanded = true }) { Text("Presets") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                        DEFAULT_BASE_URL_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = {
                                    baseUrl = preset.baseUrl
                                    presetMenuExpanded = false
                                    fetchResult = null
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key (optional — not required for local servers)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = urlValidationError == null && baseUrl.isNotBlank() && !isFetchingModels,
                        onClick = {
                            isFetchingModels = true
                            fetchResult = null
                            scope.launch {
                                val result = fetchModels(baseUrl, apiKey.ifBlank { null })
                                fetchResult = result
                                if (result is FetchModelsResult.Success && result.models.isNotEmpty()) {
                                    model = result.models.first()
                                }
                                isFetchingModels = false
                            }
                        },
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Fetch models")
                        }
                    }
                }

                if (fetchResult is FetchModelsResult.Success && (fetchResult as FetchModelsResult.Success).models.size > 1) {
                    Box {
                        TextButton(onClick = { modelMenuExpanded = true }) {
                            Text("Choose from ${(fetchResult as FetchModelsResult.Success).models.size} models")
                        }
                        DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                            (fetchResult as FetchModelsResult.Success).models.forEach { m ->
                                DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelMenuExpanded = false })
                            }
                        }
                    }
                }

                fetchResult?.let { result ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        when (result) {
                            is FetchModelsResult.Success -> {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Connected — ${result.models.size} model(s) available.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            is FetchModelsResult.Failure -> {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = canSave,
                        onClick = {
                            val id = existingConfig?.id ?: "custom:${generateCustomProviderId()}"
                            val config = CustomProviderConfig(
                                id = id,
                                displayName = displayName.trim(),
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                            )
                            onSave(config, apiKey.ifBlank { null })
                        },
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

/** Local, dependency-free UUID-shaped id generator — mirrors [dev.stapler.stelekit.ui.NotificationManager]'s. */
private fun generateCustomProviderId(): String {
    val chars = "0123456789abcdef"
    fun randomHex(length: Int) = (1..length).map { chars.random() }.joinToString("")
    return "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"
}
