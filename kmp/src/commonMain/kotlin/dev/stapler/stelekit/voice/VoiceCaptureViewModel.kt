// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class VoiceCaptureViewModel(
    private val pipeline: VoicePipelineConfig,
    private val journalService: JournalService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    private var pipelineJob: Job? = null

    fun onMicTapped() {
        when (_state.value) {
            is VoiceCaptureState.Idle -> startPipeline()
            is VoiceCaptureState.Recording -> scope.launch {
                pipeline.audioRecorder.stopRecording()
            }
            else -> Unit
        }
    }

    fun cancel() {
        pipelineJob?.cancel()
        pipelineJob = null
        _state.value = VoiceCaptureState.Idle
    }

    fun dismissError() {
        _state.value = VoiceCaptureState.Idle
    }

    private fun startPipeline() {
        pipelineJob = scope.launch {
            var file: PlatformAudioFile? = null
            try {
                _state.value = VoiceCaptureState.Recording
                val result = pipeline.audioRecorder.startRecording()
                file = result

                if (result.isEmpty) {
                    _state.value = VoiceCaptureState.Error(
                        PipelineStage.RECORDING, "Microphone permission denied"
                    )
                    return@launch
                }

                _state.value = VoiceCaptureState.Transcribing
                val audioData = pipeline.audioRecorder.readBytes(result)
                when (val sttResult = pipeline.sttProvider.transcribe(audioData)) {
                    TranscriptResult.Empty -> {
                        _state.value = VoiceCaptureState.Error(
                            PipelineStage.TRANSCRIBING, "Nothing was captured — try again"
                        )
                        return@launch
                    }
                    is TranscriptResult.Failure.ApiError -> {
                        _state.value = VoiceCaptureState.Error(
                            PipelineStage.TRANSCRIBING, sttResult.message
                        )
                        return@launch
                    }
                    TranscriptResult.Failure.NetworkError -> {
                        _state.value = VoiceCaptureState.Error(
                            PipelineStage.TRANSCRIBING, "Network error — check your connection"
                        )
                        return@launch
                    }
                    TranscriptResult.Failure.PermissionDenied -> {
                        _state.value = VoiceCaptureState.Error(
                            PipelineStage.RECORDING, "Microphone permission denied"
                        )
                        return@launch
                    }
                    is TranscriptResult.Success -> {
                        val rawTranscript = sttResult.text.trim()
                        val wordCount = rawTranscript.split(Regex("\\s+")).count { it.isNotBlank() }
                        if (wordCount < pipeline.minWordCount) {
                            _state.value = VoiceCaptureState.Error(
                                PipelineStage.TRANSCRIBING,
                                "Recording too short — try speaking for a few more seconds"
                            )
                            return@launch
                        }

                        _state.value = VoiceCaptureState.Formatting
                        val prompt = pipeline.systemPrompt.replace("{{TRANSCRIPT}}", rawTranscript)
                        var isLikelyTruncated = false
                        val formattedText = when (val llmResult = pipeline.llmProvider.format(rawTranscript, prompt)) {
                            is LlmResult.Success -> {
                                isLikelyTruncated = llmResult.isLikelyTruncated
                                llmResult.formattedText
                            }
                            is LlmResult.Failure -> rawTranscript
                        }

                        journalService.appendToToday(buildVoiceNoteBlock(formattedText, rawTranscript))
                        _state.value = VoiceCaptureState.Done(
                            insertedText = formattedText,
                            isLikelyTruncated = isLikelyTruncated,
                        )
                    }
                }
            } finally {
                file?.takeIf { !it.isEmpty }?.let { pipeline.audioRecorder.deleteRecording(it) }
            }
        }
    }

    private fun buildVoiceNoteBlock(formattedText: String, rawTranscript: String): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val timeLabel = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
        return buildString {
            append("- 📝 Voice note ($timeLabel)")
            append("\n  - ")
            append(formattedText.lines().joinToString("\n  - "))
            append("\n  #+BEGIN_QUOTE\n  ")
            append(rawTranscript)
            append("\n  #+END_QUOTE")
        }
    }
}
