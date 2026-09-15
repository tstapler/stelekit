// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.components.VOICE_CAPTURE_UNSUPPORTED_DESCRIPTION
import dev.stapler.stelekit.ui.components.VoiceCaptureButton
import dev.stapler.stelekit.voice.AudioRecorder
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import dev.stapler.stelekit.voice.PipelineStage
import dev.stapler.stelekit.voice.PlatformAudioFile
import dev.stapler.stelekit.voice.SpeechToTextProvider
import dev.stapler.stelekit.voice.TranscriptResult
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.VoicePipelineConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * REQ-13 (`docs/journeys/voice-capture.md`, Phase G2 / GAP-002, design/ux.md criterion 15):
 * proves the `VoiceCaptureButton`/`VoiceCaptureViewModel` state machine has no dead end.
 *
 * Two properties are verified:
 * 1. The G2 fix itself: on an unsupported platform (`isSupported = false`, the `NoOpAudioRecorder`
 *    default) the Idle button renders disabled and a tap never dispatches `onMicTapped` — the
 *    state machine never leaves `Idle` to enter a misleading `Recording`/`Error` state at all.
 *    (`VoiceCaptureButtonScreenshotTest.voiceCaptureButton_idle_unsupported_isDisabled` already
 *    covers the composable-level tap-is-a-no-op assertion; this test additionally confirms the
 *    underlying `VoiceCaptureViewModel` state truly never leaves `Idle`.)
 * 2. On a supported platform, every state in the machine
 *    (`Idle → Recording → Transcribing → Formatting → Done | Error`) still has at least one
 *    reachable outgoing transition — dismiss, auto-reset, or corrective action — matching
 *    `VoiceCaptureButtonScreenshotTest`'s per-state screenshot coverage but walking the actual
 *    `VoiceCaptureViewModel` transitions rather than only asserting on hard-coded `state` inputs.
 */
class VoiceCaptureStateTransitionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun journalService() = JournalService(InMemoryPageRepository(), InMemoryBlockRepository())

    /**
     * Polls [dev.stapler.stelekit.voice.VoiceCaptureViewModel.state] until it satisfies
     * [predicate] or times out. Needed because these tests run the view model against a real
     * [CoroutineScope] (not a virtual-time `TestScope`), so a fake that suspends via real
     * `delay()` resumes on a background executor thread rather than synchronously in-line.
     */
    private fun waitForState(
        vm: VoiceCaptureViewModel,
        timeoutMillis: Long = 5_000,
        predicate: (VoiceCaptureState) -> Boolean,
    ): VoiceCaptureState {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val s = vm.state.value
            if (predicate(s)) return s
            Thread.sleep(5)
        }
        error("VoiceCaptureViewModel state ${vm.state.value} never satisfied predicate within ${timeoutMillis}ms")
    }

    // ─── G2 fix: unsupported platforms never leave Idle ──────────────────────────

    @Test
    fun `voiceCaptureButton unsupported never enters Recording or Error when tapped`() {
        assertTrue(
            !VoicePipelineConfig().isSupported,
            "Default pipeline config (NoOpAudioRecorder, no DirectSpeechProvider) must be unsupported",
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(VoicePipelineConfig(), journalService(), scope = scope)

        composeTestRule.setContent {
            MaterialTheme {
                val state by vm.state.collectAsState()
                VoiceCaptureButton(
                    state = state,
                    onTap = { vm.onMicTapped() },
                    onDismissError = { vm.dismissError() },
                    onAutoReset = { vm.resetToIdle() },
                    isSupported = false,
                )
            }
        }
        composeTestRule.waitForIdle()

        val node = composeTestRule.onNodeWithContentDescription(VOICE_CAPTURE_UNSUPPORTED_DESCRIPTION)
        node.assertIsNotEnabled()
        node.performClick()
        composeTestRule.waitForIdle()

        assertIs<VoiceCaptureState.Idle>(
            vm.state.value,
            "Unsupported platform must never leave Idle -- no misleading Recording/Error state should ever be reached",
        )
        scope.cancel()
    }

    // ─── Full happy-path walk: Idle -> Recording -> Transcribing -> Formatting -> Done -> Idle ──

    @Test
    fun `voiceCaptureState has no unreachable state when the full happy-path transition is walked`() {
        // Gate startRecording() so the pipeline pauses in Recording until the test explicitly
        // taps to stop, letting us assert each transient state along the way.
        var recordingGate = CompletableDeferred<Unit>()
        val recorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile {
                recordingGate.await()
                return PlatformAudioFile("/tmp/test.m4a")
            }
            override suspend fun stopRecording() { recordingGate.complete(Unit) }
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val stt = SpeechToTextProvider { _ ->
            TranscriptResult.Success("this is a sufficiently long transcript for the happy path test")
        }
        val llm = LlmFormatterProvider { _, _ -> LlmResult.Success("- formatted note", false) }

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = recorder, sttProvider = stt, llmProvider = llm),
            journalService(),
            scope = scope,
        )

        assertIs<VoiceCaptureState.Idle>(vm.state.value, "Initial state must be Idle")

        // Idle -> Recording
        vm.onMicTapped()
        assertIs<VoiceCaptureState.Recording>(vm.state.value, "Idle must have a reachable exit to Recording (tap)")

        // Recording -> Transcribing -> Formatting -> Done (tap stops recording, which unblocks
        // startRecording() and lets the rest of the pipeline run to completion). Transcribing
        // and Formatting are each independently proven reachable-with-forward-exit by the two
        // gated tests below; this test proves the full chain composes end to end.
        vm.onMicTapped()
        val finalState = waitForState(vm) { it is VoiceCaptureState.Done }
        assertIs<VoiceCaptureState.Done>(
            finalState,
            "Recording must have a reachable exit through Transcribing/Formatting to Done",
        )

        // Done -> Idle (the exit VoiceCaptureButton wires as its DONE_AUTO_RESET_MS timeout)
        vm.resetToIdle()
        assertIs<VoiceCaptureState.Idle>(vm.state.value, "Done must have a reachable exit back to Idle (auto-reset)")

        scope.cancel()
    }

    @Test
    fun `Transcribing is genuinely reachable and has a reachable forward exit`() {
        val transcribeGate = CompletableDeferred<Unit>()
        val recorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val stt = SpeechToTextProvider { _ ->
            transcribeGate.await()
            TranscriptResult.Success("this is a sufficiently long transcript for the test")
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = recorder, sttProvider = stt),
            journalService(),
            scope = scope,
        )

        // A single tap: recording completes synchronously (no gate on it here), so the pipeline
        // proceeds straight through to Transcribing, where it pauses on transcribeGate.
        vm.onMicTapped()
        assertIs<VoiceCaptureState.Transcribing>(
            vm.state.value,
            "Transcribing must be genuinely reached (not skipped over) once recording completes",
        )

        transcribeGate.complete(Unit)
        val finalState = waitForState(vm) { it !is VoiceCaptureState.Transcribing }
        assertIs<VoiceCaptureState.Done>(
            finalState,
            "Transcribing must have a reachable forward exit through Formatting to Done",
        )
        scope.cancel()
    }

    @Test
    fun `Formatting is genuinely reachable and has a reachable forward exit`() {
        val formatGate = CompletableDeferred<Unit>()
        val recorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val stt = SpeechToTextProvider { _ ->
            TranscriptResult.Success("this is a sufficiently long transcript for the test")
        }
        val llm = LlmFormatterProvider { _, _ ->
            formatGate.await()
            LlmResult.Success("- formatted note", false)
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = recorder, sttProvider = stt, llmProvider = llm),
            journalService(),
            scope = scope,
        )

        // A single tap: recording and transcription both complete synchronously, so the pipeline
        // proceeds straight through to Formatting, where it pauses on formatGate.
        vm.onMicTapped()
        assertIs<VoiceCaptureState.Formatting>(
            vm.state.value,
            "Formatting must be genuinely reached (not skipped over) once transcription completes",
        )

        formatGate.complete(Unit)
        val finalState = waitForState(vm) { it !is VoiceCaptureState.Formatting }
        assertIs<VoiceCaptureState.Done>(finalState, "Formatting must have a reachable forward exit to Done")
        scope.cancel()
    }

    // ─── Corrective-action exits from every stage Error can occur at ────────────

    @Test
    fun `Recording has a corrective-action exit to Error at RECORDING stage`() {
        val recorder = object : AudioRecorder {
            // Empty path is the recorder's documented "permission denied" signal.
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("")
            override suspend fun stopRecording() = Unit
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(VoicePipelineConfig(audioRecorder = recorder), journalService(), scope = scope)

        vm.onMicTapped()
        val state = vm.state.value
        assertIs<VoiceCaptureState.Error>(state, "Recording must have a reachable exit to Error on recorder failure")
        assertEquals(PipelineStage.RECORDING, state.stage)

        vm.dismissError()
        assertIs<VoiceCaptureState.Idle>(vm.state.value, "Error must have a reachable dismiss exit back to Idle")
        scope.cancel()
    }

    @Test
    fun `Transcribing has a corrective-action exit to Error at TRANSCRIBING stage`() {
        val recorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val stt = SpeechToTextProvider { _ -> TranscriptResult.Failure.NetworkError }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = recorder, sttProvider = stt),
            journalService(),
            scope = scope,
        )

        vm.onMicTapped()
        val state = vm.state.value
        assertIs<VoiceCaptureState.Error>(state, "Transcribing must have a reachable exit to Error on STT failure")
        assertEquals(PipelineStage.TRANSCRIBING, state.stage)

        vm.dismissError()
        assertIs<VoiceCaptureState.Idle>(vm.state.value)
        scope.cancel()
    }

    @Test
    fun `Formatting has a corrective-action exit to Error at JOURNAL stage`() {
        val recorder = object : AudioRecorder {
            override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val stt = SpeechToTextProvider { _ -> TranscriptResult.Success("buy milk") }

        // Fail the journal write (the block save that happens after Formatting completes) so the
        // pipeline reaches Error at PipelineStage.JOURNAL rather than Done.
        val delegate = InMemoryBlockRepository()
        var saveCount = 0
        val failingBlockRepo = object : BlockRepository by delegate {
            @OptIn(DirectRepositoryWrite::class)
            override suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
                saveCount++
                if (saveCount <= 1) return delegate.saveBlock(block)
                throw RuntimeException("disk full")
            }
        }
        val failingJournalService = JournalService(InMemoryPageRepository(), failingBlockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = recorder, sttProvider = stt),
            failingJournalService,
            scope = scope,
        )

        vm.onMicTapped()
        val state = vm.state.value
        assertIs<VoiceCaptureState.Error>(state, "Formatting must have a reachable exit to Error on journal-write failure")
        assertEquals(PipelineStage.JOURNAL, state.stage)

        vm.dismissError()
        assertIs<VoiceCaptureState.Idle>(vm.state.value)
        scope.cancel()
    }
}
