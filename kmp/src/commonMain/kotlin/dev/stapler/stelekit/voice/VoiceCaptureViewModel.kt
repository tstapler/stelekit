// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val MAX_TRANSCRIPT_CHARS = 10_000

class VoiceCaptureViewModel(
    private val pipeline: VoicePipelineConfig,
    private val journalService: JournalService,
    private val currentOpenPageUuid: () -> String? = { null },
    // Default scope owns its lifecycle; callers in remember{} must not pass rememberCoroutineScope()
    // which is cancelled when the composable leaves composition. Tests inject a TestCoroutineScope.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = Logger("VoiceCaptureViewModel")
    private val _state = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    private var pipelineJob: Job? = null

    fun onMicTapped() {
        when (_state.value) {
            is VoiceCaptureState.Idle -> startPipeline()
            is VoiceCaptureState.Recording -> scope.launch {
                pipeline.directSpeechProvider?.stopListening()
                    ?: pipeline.audioRecorder.stopRecording()
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

    fun resetToIdle() {
        _state.value = VoiceCaptureState.Idle
    }

    private fun startPipeline() {
        pipelineJob = scope.launch {
            _state.value = VoiceCaptureState.Recording
            val transcriptResult = if (pipeline.directSpeechProvider != null) {
                pipeline.directSpeechProvider.listen()
            } else {
                recordAndTranscribe()
            }
            when (transcriptResult) {
                null -> return@launch  // error already set inside recordAndTranscribe
                TranscriptResult.Empty -> {
                    _state.value = VoiceCaptureState.Error(
                        PipelineStage.TRANSCRIBING, "Nothing was captured — try again"
                    )
                }
                is TranscriptResult.Failure.ApiError -> {
                    _state.value = VoiceCaptureState.Error(
                        PipelineStage.TRANSCRIBING, transcriptResult.message
                    )
                }
                TranscriptResult.Failure.NetworkError -> {
                    _state.value = VoiceCaptureState.Error(
                        PipelineStage.TRANSCRIBING, "Network error — check your connection"
                    )
                }
                TranscriptResult.Failure.PermissionDenied -> {
                    _state.value = VoiceCaptureState.Error(
                        PipelineStage.RECORDING, "Microphone permission denied"
                    )
                }
                is TranscriptResult.Success -> processTranscript(transcriptResult.text.trim())
            }
        }
    }

    /** Records via [AudioRecorder] then transcribes; returns null and sets error state on failure. */
    private suspend fun recordAndTranscribe(): TranscriptResult? {
        var file: PlatformAudioFile? = null
        return try {
            val result = pipeline.audioRecorder.startRecording()
            file = result
            if (result.isEmpty) {
                _state.value = VoiceCaptureState.Error(
                    PipelineStage.RECORDING, "Microphone permission denied"
                )
                return null
            }
            _state.value = VoiceCaptureState.Transcribing
            pipeline.sttProvider.transcribe(pipeline.audioRecorder.readBytes(result))
        } finally {
            file?.takeIf { !it.isEmpty }?.let { pipeline.audioRecorder.deleteRecording(it) }
        }
    }

    private suspend fun processTranscript(fullTranscript: String) {
        val inputTruncated = fullTranscript.length > MAX_TRANSCRIPT_CHARS
        val rawTranscript = if (inputTruncated) fullTranscript.take(MAX_TRANSCRIPT_CHARS) else fullTranscript

        _state.value = VoiceCaptureState.Formatting
        val prompt = pipeline.systemPrompt.replace("{{TRANSCRIPT}}", rawTranscript)
        var isLikelyTruncated = inputTruncated
        var llmProducedOutput = false
        val formattedText = when (val llmResult = pipeline.llmProvider.format(rawTranscript, prompt)) {
            is LlmResult.Success -> {
                isLikelyTruncated = isLikelyTruncated || llmResult.isLikelyTruncated
                llmProducedOutput = true
                llmResult.formattedText
            }
            is LlmResult.Failure -> {
                logger.warn("LLM formatting failed ($llmResult), inserting raw transcript")
                rawTranscript
            }
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val timeLabel = now.toTimeLabel()
        val dateLabel = now.toDateLabel()
        val pageTitle = "Voice Note $dateLabel $timeLabel"

        val targetPageUuid = currentOpenPageUuid()
        val wordCount = LlmProviderSupport.wordCount(rawTranscript)
        val useTranscriptPage = wordCount >= pipeline.transcriptPageWordThreshold

        val transcriptPageContent = if (useTranscriptPage) {
            val sourcePage: String = if (targetPageUuid != null) {
                journalService.getPageNameByUuid(targetPageUuid) ?: dateLabel.replace('-', '_')
            } else {
                dateLabel.replace('-', '_')
            }
            buildTranscriptPageContent(
                sourcePage = sourcePage,
                formattedText = if (llmProducedOutput) formattedText else null,
                rawTranscript = rawTranscript,
                includeRawTranscript = pipeline.includeRawTranscript,
            )
        } else null

        val inlineBlock = if (useTranscriptPage) {
            buildVoiceNoteBlock(pageTitle = pageTitle, timeLabel = timeLabel, formattedText = formattedText)
        } else {
            buildVoiceNoteBlockInline(timeLabel = timeLabel, formattedText = formattedText)
        }

        try {
            if (transcriptPageContent != null) {
                journalService.createTranscriptPage(pageTitle, transcriptPageContent)
            }
            if (targetPageUuid != null) {
                journalService.appendToPage(targetPageUuid, inlineBlock)
            } else {
                journalService.appendToToday(inlineBlock)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Journal write failed: ${e.message}")
            _state.value = VoiceCaptureState.Error(PipelineStage.JOURNAL, "Failed to save note")
            return
        }

        _state.value = VoiceCaptureState.Done(
            insertedText = formattedText,
            isLikelyTruncated = isLikelyTruncated,
        )
    }

    fun close() {
        pipeline.close()
        scope.cancel()
    }
}

internal fun buildVoiceNoteBlockInline(timeLabel: String, formattedText: String): String {
    return buildString {
        append("- 📝 Voice note ($timeLabel)")
        append("\n  - ")
        append(formattedText.lines().joinToString("\n  - "))
    }
}

internal fun buildVoiceNoteBlock(pageTitle: String, timeLabel: String, formattedText: String): String {
    return buildString {
        append("- 📝 Voice note ($timeLabel) [[$pageTitle]]")
        append("\n  - ")
        append(formattedText.lines().joinToString("\n  - "))
    }
}

internal fun buildTranscriptPageContent(
    sourcePage: String,
    formattedText: String?,
    rawTranscript: String,
    includeRawTranscript: Boolean,
): String {
    return buildString {
        append("source:: [[$sourcePage]]")
        append("\n\n")
        if (formattedText != null) {
            append(formattedText)
            if (includeRawTranscript) {
                append("\n\n#+BEGIN_QUOTE\n")
                append(rawTranscript)
                append("\n#+END_QUOTE")
            }
        } else {
            // LLM disabled or failed — raw transcript is the full content, no quote wrapper
            append("- ")
            append(rawTranscript)
        }
    }
}

internal fun LocalDateTime.toTimeLabel(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}"

internal fun LocalDateTime.toDateLabel(): String =
    "${year}-${monthNumber.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"
