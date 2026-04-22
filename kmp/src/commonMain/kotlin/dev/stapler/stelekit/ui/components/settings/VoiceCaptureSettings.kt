// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.voice.VoiceSettings

@Composable
fun VoiceCaptureSettings(
    voiceSettings: VoiceSettings,
    onRebuildPipeline: () -> Unit,
) {
    var whisperKey by remember { mutableStateOf(voiceSettings.getWhisperApiKey() ?: "") }
    var anthropicKey by remember { mutableStateOf(voiceSettings.getAnthropicKey() ?: "") }
    var openAiKey by remember { mutableStateOf(voiceSettings.getOpenAiKey() ?: "") }
    var llmEnabled by remember { mutableStateOf(voiceSettings.getLlmEnabled()) }
    var saved by remember { mutableStateOf(false) }

    SettingsSection("Transcription (Speech-to-Text)") {
        Text(
            "Whisper API key — used for speech transcription (~\$0.003/min).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = whisperKey,
            onValueChange = { whisperKey = it; saved = false },
            label = { Text("OpenAI / Whisper API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SettingsSection("LLM Formatting") {
        Text(
            "Formats the raw transcript into Logseq outliner syntax with bullet points and [[wikilinks]]. " +
                "Provide one key — Anthropic is used if both are set.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable LLM formatting", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = llmEnabled,
                onCheckedChange = { llmEnabled = it; saved = false },
            )
        }
        if (llmEnabled) {
            OutlinedTextField(
                value = anthropicKey,
                onValueChange = { anthropicKey = it; saved = false },
                label = { Text("Anthropic (Claude) API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = openAiKey,
                onValueChange = { openAiKey = it; saved = false },
                label = { Text("OpenAI / compatible API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    voiceSettings.setWhisperApiKey(whisperKey)
                    voiceSettings.setAnthropicKey(anthropicKey)
                    voiceSettings.setOpenAiKey(openAiKey)
                    voiceSettings.setLlmEnabled(llmEnabled)
                    saved = true
                    onRebuildPipeline()
                },
            ) {
                Text("Save")
            }
            if (saved) {
                Text(
                    "Saved — pipeline updated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
