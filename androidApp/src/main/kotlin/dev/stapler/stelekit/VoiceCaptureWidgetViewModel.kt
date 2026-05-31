// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stapler.stelekit.platform.PlatformSettings
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

    /**
     * Initializes the voice pipeline and starts recording immediately.
     * Must be called once from the Activity after the permission launcher is registered.
     * Safe to call on re-creation: if already initialized, recording is not restarted.
     */
    fun initialize(requestMicPermission: suspend () -> Boolean) {
        if (inner != null) return

        val steleApp = getApplication<SteleKitApplication>()
        val repoSet = steleApp.graphManager?.getActiveRepositorySet()
        if (repoSet == null) {
            _state.value = VoiceCaptureState.Error(
                dev.stapler.stelekit.voice.PipelineStage.RECORDING,
                "No graph configured — open SteleKit first",
                dev.stapler.stelekit.voice.VoiceErrorKind.NO_GRAPH,
            )
            return
        }

        val ctx = steleApp.applicationContext
        val recorder = AndroidAudioRecorder(ctx, requestMicPermission)
        val settings = VoiceSettings(PlatformSettings())
        val deviceStt = if (AndroidSpeechRecognizerProvider.isAvailable(ctx))
            AndroidSpeechRecognizerProvider(ctx, requestMicPermission) else null
        val pipeline = buildVoicePipeline(
            audioRecorder = recorder,
            settings = settings,
            directSpeechProvider = if (settings.getUseDeviceStt()) deviceStt else null,
        )

        val vm = VoiceCaptureViewModel(
            pipeline = pipeline,
            journalService = repoSet.journalService,
            scope = viewModelScope,
        )

        viewModelScope.launch {
            vm.state.collect { _state.value = it }
        }

        inner = vm
        vm.onMicTapped()   // start recording immediately on widget launch
    }

    fun onMicTapped() { inner?.onMicTapped() }
    fun cancel()      { inner?.cancel() }
    fun dismissError(){ inner?.dismissError() }

    override fun onCleared() {
        inner?.close()
        super.onCleared()
    }
}
