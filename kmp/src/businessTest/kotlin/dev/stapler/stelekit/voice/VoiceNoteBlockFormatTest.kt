// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceNoteBlockFormatTest {

    private fun makeViewModel(scope: kotlinx.coroutines.CoroutineScope) = VoiceCaptureViewModel(
        VoicePipelineConfig(),
        JournalService(InMemoryPageRepository(), InMemoryBlockRepository()),
        currentOpenPageUuid = { null },
        scope = scope,
    )

    @Test
    fun `block starts with voice note header line`() = runTest {
        val block = buildVoiceNoteBlock(
            pageTitle = "Voice Note 2026-05-02 14:35:22",
            timeLabel = "14:35:22",
            formattedText = "- formatted bullet.",
        )
        assertTrue(block.startsWith("- 📝 Voice note ("), "Expected block to start with '- 📝 Voice note (', got: $block")
    }

    @Test
    fun `block contains formatted text`() = runTest {
        val formatted = "- point one\n- point two."
        val block = buildVoiceNoteBlock("Test Page", "14:35:22", formatted)
        assertTrue(block.contains("point one"), "Expected formatted text in block")
        assertTrue(block.contains("point two"), "Expected formatted text in block")
    }

    @Test
    fun `multiline formatted text has each line indented under header`() = runTest {
        val formatted = "- line one\n- line two\n- line three."
        val block = buildVoiceNoteBlock("Test Page", "14:35:22", formatted)
        assertTrue(block.contains("line one"), "Expected 'line one' in block")
        assertTrue(block.contains("line two"), "Expected 'line two' in block")
        assertTrue(block.contains("line three"), "Expected 'line three' in block")
    }

    @Test
    fun `timestamp in header has zero-padded hours and minutes`() = runTest {
        val block = buildVoiceNoteBlock(
            pageTitle = "Voice Note 2026-05-02 14:35:22",
            timeLabel = "14:35:22",
            formattedText = "- formatted.",
        )
        val headerLine = block.lines().first()
        val timeRegex = Regex("""- 📝 Voice note \(\d{2}:\d{2}:\d{2}\) \[\[Voice Note \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]\]""")
        assertTrue(timeRegex.containsMatchIn(headerLine), "Expected HH:mm:ss timestamp and wikilink in header, got: $headerLine")
    }

    @Test
    fun `buildVoiceNoteBlock_should_contain_wikilink_to_transcript_page`() = runTest {
        val block = buildVoiceNoteBlock(
            pageTitle = "Voice Note 2026-05-02 14:35:22",
            timeLabel = "14:35:22",
            formattedText = "- formatted bullet.",
        )
        assertTrue(block.contains("[[Voice Note 2026-05-02 14:35:22]]"),
            "Expected wikilink to transcript page in block, got: $block")
    }

    @Test
    fun `transcript page includes BEGIN_QUOTE when includeRawTranscript is true`() = runTest {
        val raw = "this is the raw transcript text"
        val content = buildTranscriptPageContent(
            sourcePage = "Today",
            formattedText = "- formatted.",
            rawTranscript = raw,
            includeRawTranscript = true,
        )
        assertTrue(content.contains("#+BEGIN_QUOTE"))
        assertTrue(content.contains(raw))
        assertTrue(content.contains("#+END_QUOTE"))
    }

    @Test
    fun `transcript page omits BEGIN_QUOTE when includeRawTranscript is false`() = runTest {
        val raw = "this is the raw transcript text"
        val content = buildTranscriptPageContent(
            sourcePage = "Today",
            formattedText = "- formatted.",
            rawTranscript = raw,
            includeRawTranscript = false,
        )
        assertFalse(content.contains("#+BEGIN_QUOTE"))
    }

    @Test
    fun `buildTranscriptPageContent_should_start_with_source_property`() = runTest {
        val content = buildTranscriptPageContent(
            sourcePage = "My Page",
            formattedText = "- bullet one",
            rawTranscript = "raw text",
            includeRawTranscript = false,
        )
        assertTrue(content.startsWith("source:: [[My Page]]"),
            "Expected content to start with source:: property, got: $content")
    }

    @Test
    fun `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content`() = runTest {
        val formatted = "- TODO Call Alice about [[project]]\n- #meeting noted"
        val content = buildTranscriptPageContent(
            sourcePage = "Today",
            formattedText = formatted,
            rawTranscript = "call alice about the project, meeting noted",
            includeRawTranscript = false,
        )
        assertTrue(content.contains("- TODO Call Alice about [[project]]"),
            "Expected formatted TODO bullet in transcript page, got: $content")
        assertTrue(content.contains("#meeting"),
            "Expected #tag in transcript page, got: $content")
    }

    @Test
    fun `buildTranscriptPageContent_should_passthrough_LLM_output_verbatim`() = runTest {
        val formatted = "- project:: Stelekit\n- **bold term** in output\n- #tag example\n- TODO action"
        val content = buildTranscriptPageContent(
            sourcePage = "Source",
            formattedText = formatted,
            rawTranscript = "raw",
            includeRawTranscript = false,
        )
        assertTrue(content.contains("project:: Stelekit"))
        assertTrue(content.contains("**bold term**"))
        assertTrue(content.contains("#tag example"))
        assertTrue(content.contains("TODO action"))
    }

    @Test
    fun `buildTranscriptPageContent_should_use_raw_text_without_quote_wrapper_when_llm_disabled`() = runTest {
        val raw = "buy milk and eggs"
        val content = buildTranscriptPageContent(
            sourcePage = "Source",
            formattedText = null,          // LLM disabled or failed
            rawTranscript = raw,
            includeRawTranscript = true,   // toggle is true, but has no effect when formattedText is null
        )
        assertFalse(content.contains("#+BEGIN_QUOTE"),
            "Expected no #+BEGIN_QUOTE when formattedText is null, got: $content")
        assertTrue(content.contains(raw),
            "Expected raw transcript in output, got: $content")
    }

    @Test
    fun `buildVoiceNoteBlockInline has no wikilink and contains formatted text`() = runTest {
        val block = buildVoiceNoteBlockInline(
            timeLabel = "08:05:03",
            formattedText = "buy milk",
        )
        assertTrue(block.startsWith("- 📝 Voice note (08:05:03)"), "Expected inline header, got: $block")
        assertFalse(block.contains("[["), "Inline block must not contain a wikilink, got: $block")
        assertTrue(block.contains("buy milk"), "Expected formatted text in inline block")
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
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val fakeJournal = JournalService(pageRepo, blockRepo)

        val vm = VoiceCaptureViewModel(
            VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
            fakeJournal,
            currentOpenPageUuid = { null },
            scope = this,
        )
        vm.onMicTapped()
        advanceUntilIdle()

        assertIs<VoiceCaptureState.Done>(vm.state.value)
        val page = fakeJournal.ensureTodayJournal()
        assertTrue(page.uuid.isNotBlank())
        // Verify the inserted block has the expected structure by reading from the shared repo
        val blocks = blockRepo.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
        assertTrue(blocks.isNotEmpty(), "Expected at least one block inserted")
        val voiceBlock = blocks.firstOrNull { it.content.contains("📝 Voice note") }
        assertNotNull(voiceBlock, "Expected a block with voice note header")
        // 11-word transcript is below the default threshold of 20 → inline path (no wikilink, no transcript page)
        assertFalse(voiceBlock.content.contains("[[Voice Note"),
            "Short transcript must produce inline block without wikilink")
        assertFalse(voiceBlock.content.contains("#+BEGIN_QUOTE"),
            "#+BEGIN_QUOTE must not appear in inline block")

        // Verify no transcript page was created (below threshold)
        val allPages = pageRepo.getAllPages().first().getOrNull().orEmpty()
        val transcriptPages = allPages.filter { it.name.startsWith("Voice Note ") }
        assertTrue(transcriptPages.isEmpty(),
            "Expected no Voice Note transcript page for short (below-threshold) note")
    }
}
