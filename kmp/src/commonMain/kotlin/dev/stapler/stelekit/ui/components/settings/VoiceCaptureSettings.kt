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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.voice.VoiceSettings

@Composable
fun VoiceCaptureSettings(
    voiceSettings: VoiceSettings,
    onRebuildPipeline: () -> Unit,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
) {
    var whisperKey by remember { mutableStateOf(voiceSettings.getWhisperApiKey() ?: "") }
    var anthropicKey by remember { mutableStateOf(voiceSettings.getAnthropicKey() ?: "") }
    var openAiKey by remember { mutableStateOf(voiceSettings.getOpenAiKey() ?: "") }
    var llmEnabled by remember { mutableStateOf(voiceSettings.getLlmEnabled()) }
    var useDeviceStt by remember { mutableStateOf(voiceSettings.getUseDeviceStt()) }
    var useDeviceLlm by remember { mutableStateOf(voiceSettings.getUseDeviceLlm()) }
    var includeRawTranscript by remember { mutableStateOf(voiceSettings.getIncludeRawTranscript()) }
    var transcriptPageWordThreshold by remember { mutableStateOf(voiceSettings.getTranscriptPageWordThreshold().toString()) }
    var saved by remember { mutableStateOf(false) }

    SettingsSection("Transcription (Speech-to-Text)") {
        if (deviceSttAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Use on-device speech recognition", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = useDeviceStt,
                    onCheckedChange = { useDeviceStt = it; saved = false },
                )
            }
            if (useDeviceStt) {
                Text(
                    "Transcription happens on-device — no API key or network required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (!deviceSttAvailable || !useDeviceStt) {
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
    }

    SettingsSection("LLM Formatting") {
        Text(
            "Formats the raw transcript into Logseq outliner syntax with bullet points and [[wikilinks]].",
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
            if (deviceLlmAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Use on-device LLM (Gemini Nano)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = useDeviceLlm,
                        onCheckedChange = { useDeviceLlm = it; saved = false },
                    )
                }
                if (useDeviceLlm) {
                    Text(
                        "Formatting runs on-device — no API key or network required. " +
                            "256-token output limit; longer notes may be truncated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            if (!deviceLlmAvailable || !useDeviceLlm) {
                Text(
                    "Provide one key — Anthropic is used if both are set.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Include raw transcript in note", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = includeRawTranscript,
                onCheckedChange = { includeRawTranscript = it; saved = false },
            )
        }
        OutlinedTextField(
            value = transcriptPageWordThreshold,
            onValueChange = { transcriptPageWordThreshold = it; saved = false },
            label = { Text("Create transcript page after N words") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
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
                    voiceSettings.setUseDeviceStt(useDeviceStt)
                    voiceSettings.setUseDeviceLlm(useDeviceLlm)
                    voiceSettings.setIncludeRawTranscript(includeRawTranscript)
                    voiceSettings.setTranscriptPageWordThreshold(transcriptPageWordThreshold.toIntOrNull() ?: 20)
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
