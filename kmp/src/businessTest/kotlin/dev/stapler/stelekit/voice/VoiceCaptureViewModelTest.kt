// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceCaptureViewModelTest {

    private fun makeJournalService() =
        JournalService(InMemoryPageRepository(), InMemoryBlockRepository())

    @Test
    fun `initial state is Idle`() = runTest {
        val vm = VoiceCaptureViewModel(VoicePipelineConfig(), makeJournalService(), this)
        assertIs<VoiceCaptureState.Idle>(vm.state.first())
    }

    @Test
    fun `success path reaches Done state`() = runTest {
        val transcript = "this is a test transcript with more than ten words total here"
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success(transcript) }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        assertIs<VoiceCaptureState.Done>(vm.state.first())
    }

    @Test
    fun `word-count gate under 10 words emits Error at TRANSCRIBING`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success("too short") }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Error>(state)
        assertEquals(PipelineStage.TRANSCRIBING, state.stage)
    }

    @Test
    fun `permission denied (empty path) emits Error at RECORDING`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("")
            override suspend fun stopRecording() = Unit
        }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Error>(state)
        assertEquals(PipelineStage.RECORDING, state.stage)
    }

    @Test
    fun `cancel during Recording resets to Idle`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile {
                delay(10_000)
                return PlatformAudioFile("")
            }
            override suspend fun stopRecording() = Unit
        }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        // Let coroutine start and reach Recording state
        delay(1)
        assertIs<VoiceCaptureState.Recording>(vm.state.first())

        vm.cancel()
        assertIs<VoiceCaptureState.Idle>(vm.state.first())
    }

    @Test
    fun `dismissError resets to Idle`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("")
            override suspend fun stopRecording() = Unit
        }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()
        assertIs<VoiceCaptureState.Error>(vm.state.first())

        vm.dismissError()
        assertIs<VoiceCaptureState.Idle>(vm.state.first())
    }

    @Test
    fun `STT NetworkError emits Error at TRANSCRIBING`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Failure.NetworkError }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Error>(state)
        assertEquals(PipelineStage.TRANSCRIBING, state.stage)
    }

    @Test
    fun `temp file deleted in finally block on success`() = runTest {
        var deletedPath: String? = null
        val transcript = "this is a test transcript with more than ten words total here long enough"
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/voice.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
            override fun deleteRecording(file: PlatformAudioFile) { deletedPath = file.path }
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success(transcript) }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        assertEquals("/tmp/voice.m4a", deletedPath)
    }

    @Test
    fun `temp file deleted in finally block on cancel`() = runTest {
        var deletedPath: String? = null
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile {
                delay(10_000)
                return PlatformAudioFile("/tmp/voice.m4a")
            }
            override suspend fun stopRecording() = Unit
            override fun deleteRecording(file: PlatformAudioFile) { deletedPath = file.path }
        }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        delay(1)
        vm.cancel()

        // Empty path because startRecording never returned, so no file to delete
        assertEquals(null, deletedPath)
    }

    @Test
    fun `temp file deleted when cancel fires after startRecording returns`() = runTest {
        var deletedPath: String? = null
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile {
                delay(10_000)
                return PlatformAudioFile("/tmp/voice_cancel.m4a")
            }
            override suspend fun stopRecording() {
                // Unblock startRecording by advancing time
            }
            override suspend fun readBytes(file: PlatformAudioFile): ByteArray {
                delay(10_000) // hang during transcription so cancel can fire
                return ByteArray(0)
            }
            override fun deleteRecording(file: PlatformAudioFile) { deletedPath = file.path }
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Empty }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        // Advance past startRecording's delay so the file is assigned, then stop
        advanceTimeBy(10_001)
        // Now pipeline is in Transcribing (readBytes is hanging)
        assertIs<VoiceCaptureState.Transcribing>(vm.state.first())
        vm.cancel()
        advanceUntilIdle()

        assertEquals("/tmp/voice_cancel.m4a", deletedPath)
    }

    @Test
    fun `STT Empty result emits Error at TRANSCRIBING`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Empty }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Error>(state)
        assertEquals(PipelineStage.TRANSCRIBING, state.stage)
    }

    @Test
    fun `STT PermissionDenied emits Error at RECORDING`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Failure.PermissionDenied }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Error>(state)
        assertEquals(PipelineStage.RECORDING, state.stage)
    }

    @Test
    fun `success path passes through Formatting state`() = runTest {
        val transcript = "this is a test transcript with more than ten words total here"
        var formattingObserved = false
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success(transcript) }
        val fakeLlm = LlmFormatterProvider { _, _ ->
            delay(1) // yield so we can observe Formatting state
            LlmResult.Success("- formatted", false)
        }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt, llmProvider = fakeLlm),
            makeJournalService(), this,
        )

        val collectionJob = launch {
            vm.state.collect { if (it == VoiceCaptureState.Formatting) formattingObserved = true }
        }
        vm.onMicTapped()
        advanceUntilIdle()
        collectionJob.cancel()

        assert(formattingObserved) { "Formatting state was never observed" }
        assertIs<VoiceCaptureState.Done>(vm.state.first())
    }

    @Test
    fun `LLM failure falls back to raw transcript in Done state`() = runTest {
        val transcript = "this is a test transcript with more than ten words total here"
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success(transcript) }
        val fakeLlm = LlmFormatterProvider { _, _ -> LlmResult.Failure.NetworkError }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt, llmProvider = fakeLlm),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Done>(state)
        assert(state.insertedText.contains(transcript.trim())) {
            "Expected Done.insertedText to contain raw transcript but got: ${state.insertedText}"
        }
    }

    @Test
    fun `9-word transcript emits Error at TRANSCRIBING`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success("one two three four five six seven eight nine") }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        val state = vm.state.first()
        assertIs<VoiceCaptureState.Error>(state)
        assertEquals(PipelineStage.TRANSCRIBING, state.stage)
    }

    @Test
    fun `10-word transcript reaches Done state`() = runTest {
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success("one two three four five six seven eight nine ten") }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        advanceUntilIdle()

        assertIs<VoiceCaptureState.Done>(vm.state.first())
    }

    @Test
    fun `onMicTapped while Recording calls stopRecording and reaches Transcribing`() = runTest {
        var stopCalled = false
        val fakeRecorder = object : AudioRecorder {
            private var stopped = false
            override suspend fun startRecording(): PlatformAudioFile {
                // Suspend until stop is signalled
                while (!stopped) delay(10)
                return PlatformAudioFile("/tmp/test.m4a")
            }
            override suspend fun stopRecording() {
                stopCalled = true
                stopped = true
            }
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(0)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Empty }
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            makeJournalService(), this,
        )

        vm.onMicTapped()
        delay(1)
        assertIs<VoiceCaptureState.Recording>(vm.state.first())

        vm.onMicTapped() // should call stopRecording
        advanceUntilIdle()

        assert(stopCalled) { "stopRecording was not called" }
        // After stop, startRecording returns the file, pipeline proceeds to Transcribing/Error
        assertIs<VoiceCaptureState.Error>(vm.state.first()) // Empty transcript → Error
    }
}
