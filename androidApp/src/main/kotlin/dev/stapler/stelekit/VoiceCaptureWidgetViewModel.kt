// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stapler.stelekit.auto.AudiobookAutoSettings
import dev.stapler.stelekit.auto.AudiobookNote
import dev.stapler.stelekit.auto.AudiobookNoteWriter
import dev.stapler.stelekit.auto.BookInfo
import dev.stapler.stelekit.auto.NoOpJournalService
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.buildLlmProviderRegistry
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.voice.AndroidAudioRecorder
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerProvider
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.VoiceSettings
import dev.stapler.stelekit.voice.buildVoicePipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceCaptureWidgetViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    private var inner: VoiceCaptureViewModel? = null
    private val initializing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Initializes the voice pipeline and starts recording immediately.
     * When book context extras are present (bookTitle != null), the note is saved in audiobook
     * format — both today's journal and the book's dedicated Logseq page.
     * Safe to call on re-creation: if already initialized, recording is not restarted.
     */
    fun initialize(
        requestMicPermission: suspend () -> Boolean,
        bookTitle: String? = null,
        bookAuthor: String? = null,
        bookChapter: String? = null,
        bookPositionMs: Long? = null,
    ) {
        if (inner != null) return
        if (!initializing.compareAndSet(false, true)) return

        val hasBookContext = bookTitle != null

        viewModelScope.launch {
            try {
                val steleApp = getApplication<SteleKitApplication>()
                val gm = steleApp.graphManager
                if (gm?.getActiveGraphId() == null) {
                    _state.value = VoiceCaptureState.Error(
                        dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                        "No graph configured — open SteleKit first",
                        dev.stapler.stelekit.voice.VoiceErrorKind.NO_GRAPH,
                    )
                    return@launch
                }

                gm.awaitPendingMigration()

                val repoSet = gm.getActiveRepositorySet()
                if (repoSet == null) {
                    _state.value = VoiceCaptureState.Error(
                        dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                        "Could not load graph — open SteleKit first",
                        dev.stapler.stelekit.voice.VoiceErrorKind.NO_GRAPH,
                    )
                    return@launch
                }

                val ctx = steleApp.applicationContext
                val recorder = AndroidAudioRecorder(ctx, requestMicPermission)
                val settings = VoiceSettings(PlatformSettings())
                val deviceStt = if (AndroidSpeechRecognizerProvider.isAvailable(ctx))
                    AndroidSpeechRecognizerProvider(ctx, requestMicPermission) else null
                // LLM provider registry + settings (Epic 8) — built the same way ui/App.kt and
                // MainActivity build them, so the widget's voice notes get the user's
                // configured LLM provider instead of silently falling back to no-op formatting.
                val llmCredentialStore = LlmCredentialStore(CredentialStore())
                val llmSettings = LlmSettings(PlatformSettings())
                val llmProviderRegistry = buildLlmProviderRegistry(llmCredentialStore, llmSettings)
                val pipeline = buildVoicePipeline(
                    audioRecorder = recorder,
                    settings = settings,
                    directSpeechProvider = if (settings.getUseDeviceStt()) deviceStt else null,
                    registry = llmProviderRegistry,
                    llmSettings = llmSettings,
                )

                // When book context is present: inject NoOpJournalService so VoiceCaptureViewModel
                // does NOT write to the journal itself. AudiobookNoteWriter handles the write after
                // the transcript is done, using the audiobook note format with book attribution.
                val journalServiceForVm = if (hasBookContext) NoOpJournalService() else repoSet.journalService

                val vm = VoiceCaptureViewModel(
                    pipeline = pipeline,
                    journalService = journalServiceForVm,
                    scope = viewModelScope,
                )

                viewModelScope.launch {
                    vm.state.collect { vmState ->
                        _state.value = vmState

                        // On transcription complete with book context: write as audiobook note
                        if (hasBookContext && vmState is VoiceCaptureState.Done) {
                            val bookInfo = BookInfo(
                                title = bookTitle,
                                author = bookAuthor,
                                chapter = bookChapter,
                                positionMs = bookPositionMs,
                                isActive = true,
                            )
                            val noteWriter = AudiobookNoteWriter(
                                journalService = repoSet.journalService,
                                settings = AudiobookAutoSettings(ctx),
                            )
                            noteWriter.writeNote(AudiobookNote.VoiceNote(
                                transcribedText = vmState.insertedText,
                                bookInfo = bookInfo,
                            ))
                            // State already Done — no further action needed
                        }
                    }
                }

                inner = vm
                vm.onMicTapped()   // start recording immediately on widget launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = dev.stapler.stelekit.voice.VoiceCaptureState.Error(
                    dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                    e.message ?: "Initialization failed",
                    dev.stapler.stelekit.voice.VoiceErrorKind.GENERIC,
                )
            } finally {
                initializing.set(false)
            }
        }
    }

    fun onMicTapped() { inner?.onMicTapped() }
    fun cancel()      { inner?.cancel() }
    fun dismissError(){ inner?.dismissError() }

    override fun onCleared() {
        inner?.close()
        super.onCleared()
    }
}
