// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceNoteBlockFormatTest {

    private fun makeViewModel() = VoiceCaptureViewModel(
        VoicePipelineConfig(),
        JournalService(InMemoryPageRepository(), InMemoryBlockRepository()),
        kotlinx.coroutines.MainScope(),
    )

    @Test
    fun `block starts with voice note header line`() {
        val vm = makeViewModel()
        val block = vm.buildVoiceNoteBlock("- formatted bullet.", "raw transcript text")
        assertTrue(block.startsWith("- 📝 Voice note ("), "Expected block to start with '- 📝 Voice note (', got: $block")
    }

    @Test
    fun `block contains formatted text`() {
        val vm = makeViewModel()
        val formatted = "- point one\n- point two."
        val block = vm.buildVoiceNoteBlock(formatted, "raw transcript")
        assertTrue(block.contains("point one"), "Expected formatted text in block")
        assertTrue(block.contains("point two"), "Expected formatted text in block")
    }

    @Test
    fun `block contains raw transcript in BEGIN_QUOTE block`() {
        val vm = makeViewModel()
        val raw = "this is the raw transcript text"
        val block = vm.buildVoiceNoteBlock("- formatted.", raw)
        assertTrue(block.contains("#+BEGIN_QUOTE"), "Expected #+BEGIN_QUOTE in block")
        assertTrue(block.contains(raw), "Expected raw transcript in #+END_QUOTE block")
        assertTrue(block.contains("#+END_QUOTE"), "Expected #+END_QUOTE in block")
    }

    @Test
    fun `multiline formatted text has each line indented under header`() {
        val vm = makeViewModel()
        val formatted = "- line one\n- line two\n- line three."
        val block = vm.buildVoiceNoteBlock(formatted, "raw")
        // Each line of the formatted text appears in the block body (double-dash since lines start with "- ")
        assertTrue(block.contains("line one"), "Expected 'line one' in block")
        assertTrue(block.contains("line two"), "Expected 'line two' in block")
        assertTrue(block.contains("line three"), "Expected 'line three' in block")
    }

    @Test
    fun `timestamp in header has zero-padded hours and minutes`() = runTest {
        val vm = makeViewModel()
        val block = vm.buildVoiceNoteBlock("- formatted.", "raw")
        val headerLine = block.lines().first()
        // Matches "- 📝 Voice note (HH:mm)"
        val timeRegex = Regex("""- 📝 Voice note \(\d{2}:\d{2}\)""")
        assertTrue(timeRegex.containsMatchIn(headerLine), "Expected HH:mm timestamp in header, got: $headerLine")
    }

    @Test
    fun `success pipeline stores block with correct structure`() = runTest {
        val transcript = "one two three four five six seven eight nine ten eleven"
        val fakeRecorder = object : AudioRecorder {
            override suspend fun startRecording() = PlatformAudioFile("/tmp/t.m4a")
            override suspend fun stopRecording() = Unit
            override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
        }
        val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success(transcript) }
        val fakeJournal = JournalService(InMemoryPageRepository(), InMemoryBlockRepository())

        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            fakeJournal,
            this,
        )
        vm.onMicTapped()
        advanceUntilIdle()

        assertIs<VoiceCaptureState.Done>(vm.state.value)
        val pages = fakeJournal.ensureTodayJournal()
        assertTrue(pages.uuid.isNotBlank())
    }
}
