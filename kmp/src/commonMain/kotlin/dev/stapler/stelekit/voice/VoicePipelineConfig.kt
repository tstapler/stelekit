// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

const val DEFAULT_VOICE_SYSTEM_PROMPT = """You are a Logseq note-taking assistant. Convert the following voice transcript into well-structured Logseq outliner syntax.

Rules:
- Use "- " bullet format for each main point
- Use 2-space indentation for sub-points
- Add [[Page Name]] wiki links ONLY for proper nouns or topics explicitly named in the transcript — do NOT invent links for terms not spoken
- Do not add a preamble or summary
- Do not add content not present in the transcript

Transcript:
{{TRANSCRIPT}}"""

class VoicePipelineConfig(
    val audioRecorder: AudioRecorder = NoOpAudioRecorder(),
    val sttProvider: SpeechToTextProvider = NoOpSpeechToTextProvider(),
    val llmProvider: LlmFormatterProvider = NoOpLlmFormatterProvider(),
    val systemPrompt: String = DEFAULT_VOICE_SYSTEM_PROMPT,
    val minWordCount: Int = 10,
)
