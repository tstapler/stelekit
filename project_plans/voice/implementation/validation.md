# Validation Plan: voice

**Date**: 2026-05-02

---

## Requirement → Test Mapping

| AC | Requirement | Test File | Test Name | Type | Status | Scenario |
|----|-------------|-----------|-----------|------|--------|----------|
| AC-1 | Rich LLM output: #tags, key:: value, **bold**, TODO | `VoiceNoteBlockFormatTest` | `buildTranscriptPageContent_should_passthrough_LLM_output_verbatim` | Unit | NEW | LLM output containing #tag, key::, **bold**, TODO appears unmodified in transcript page content |
| AC-2 | TODO bullet for action items | `VoiceNoteBlockFormatTest` | `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content` | Unit | NEW | Simulated LLM output `- TODO Call Alice about [[project]]` appears in the `formattedText` section of the transcript page |
| AC-3 | Inline block: `- 📝 Voice note (HH:mm:ss) [[Voice Note YYYY-MM-DD HH:mm:ss]]` + sub-bullets | `VoiceNoteBlockFormatTest` | `block starts with voice note header line` | Unit | UPDATE | Header starts with `- 📝 Voice note (` — update to use 3-param signature `(pageTitle, timeLabel, formattedText)` |
| AC-3 | Inline block timestamp format HH:mm:ss | `VoiceNoteBlockFormatTest` | `timestamp in header has zero-padded hours and minutes` | Unit | UPDATE | Regex updated to `\d{2}:\d{2}:\d{2}` and wikilink pattern `[[Voice Note YYYY-MM-DD HH:mm:ss]]`; call `buildVoiceNoteBlock("Voice Note 2026-05-02 14:35:22", "14:35:22", "- bullet")` |
| AC-3 | Inline block contains wikilink to transcript page | `VoiceNoteBlockFormatTest` | `buildVoiceNoteBlock_should_contain_wikilink_to_transcript_page` | Unit | NEW | `buildVoiceNoteBlock("Voice Note 2026-05-02 14:35:22", "14:35:22", "- formatted.")` contains `[[Voice Note 2026-05-02 14:35:22]]` |
| AC-3 | Inline block has LLM bullets as sub-items | `VoiceNoteBlockFormatTest` | `block contains formatted text` | Unit | UPDATE | Use 3-param `buildVoiceNoteBlock("Test Page", "14:35:22", formatted)` — existing assertion retained |
| AC-3 | Multiline formatted text indented under header | `VoiceNoteBlockFormatTest` | `multiline formatted text has each line indented under header` | Unit | UPDATE | Use 3-param `buildVoiceNoteBlock("Test Page", "14:35:22", formatted)` |
| AC-4 | Transcript page has `source::` property | `VoiceNoteBlockFormatTest` | `buildTranscriptPageContent_should_start_with_source_property` | Unit | NEW | `buildTranscriptPageContent("My Page", "- bullets", "raw", true)` starts with `source:: [[My Page]]` |
| AC-4 | `source::` links to current open page name | `VoiceCaptureViewModelTest` | `when page is open voice note is appended to that page (AC-8)` | Integration | NEW | Transcript page block checked for `source:: [[` pointing to the open page name |
| AC-5 | Transcript page contains LLM-formatted bullets | `VoiceNoteBlockFormatTest` | `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content` | Unit | NEW (shared with AC-2) | `formattedText` argument appears in output between `source::` and `#+BEGIN_QUOTE` |
| AC-5 | Pipeline end-to-end: transcript page created | `VoiceNoteBlockFormatTest` | `success pipeline stores block with correct structure` | Integration | UPDATE | Assert transcript page exists in `pageRepo` with `[[Voice Note` title and no `#+BEGIN_QUOTE` in inline block |
| AC-6 | `includeRawTranscript=true` → page includes `#+BEGIN_QUOTE` | `VoiceNoteBlockFormatTest` | `transcript page includes BEGIN_QUOTE when includeRawTranscript is true` | Unit | NEW (replaces deleted test) | Replaces `block contains raw transcript in BEGIN_QUOTE block`; calls `buildTranscriptPageContent(..., includeRawTranscript=true)` |
| AC-7 | `includeRawTranscript=false` → page omits `#+BEGIN_QUOTE` | `VoiceNoteBlockFormatTest` | `transcript page omits BEGIN_QUOTE when includeRawTranscript is false` | Unit | NEW | Calls `buildTranscriptPageContent(..., includeRawTranscript=false)`; asserts no `#+BEGIN_QUOTE` |
| AC-7 | LLM disabled → transcript page is raw text, no `#+BEGIN_QUOTE` | `VoiceNoteBlockFormatTest` | `buildTranscriptPageContent_should_use_raw_text_without_quote_wrapper_when_llm_disabled` | Unit | NEW | `buildTranscriptPageContent("Source", null, "raw text", true)` has no `#+BEGIN_QUOTE` and contains `raw text` |
| AC-8 | Voice note appended to open page when UUID non-null | `VoiceCaptureViewModelTest` | `when page is open voice note is appended to that page (AC-8)` | Integration | NEW | `currentOpenPageUuid = { targetPage.uuid }`; after pipeline, `blockRepo.getBlocksForPage(targetPage.uuid)` contains block with `📝 Voice note` |
| AC-9 | Falls back to journal when no page open | `VoiceCaptureViewModelTest` | `when no page is open voice note falls back to today journal (AC-9)` | Integration | NEW | `currentOpenPageUuid = { null }`; after pipeline, today's journal page blocks contain `📝 Voice note` |
| AC-10 | `VoiceSettings` persists `includeRawTranscript` | `VoiceSettingsTest` | `getIncludeRawTranscript_should_return_true_by_default` | Unit | NEW | Fresh `VoiceSettings(MockSettings())` returns `true` for `getIncludeRawTranscript()` |
| AC-10 | `VoiceSettings` round-trips persisted value | `VoiceSettingsTest` | `setIncludeRawTranscript_should_persist_value_across_get_calls` | Unit | NEW | `setIncludeRawTranscript(false)` followed by `getIncludeRawTranscript()` returns `false` |
| AC-11 | 2-word transcript reaches `Done` (no minWordCount) | `VoiceCaptureViewModelTest` | `2-word transcript reaches Done state (AC-11)` | Unit | NEW | STT returns `"buy milk"`; pipeline reaches `VoiceCaptureState.Done` |
| AC-11 | `VoicePipelineConfig` has no `minWordCount` | `VoiceCaptureViewModelTest` | `word-count gate under 10 words emits Error at TRANSCRIBING` | Unit | DELETE | Test asserted old behaviour; remove entirely |
| AC-11 | 9-word transcript no longer errors | `VoiceCaptureViewModelTest` | `9-word transcript emits Error at TRANSCRIBING` | Unit | DELETE | Old word-count boundary test; remove entirely |
| AC-11 | 10-word transcript boundary test obsolete | `VoiceCaptureViewModelTest` | `10-word transcript reaches Done state` | Unit | DELETE | Was boundary test for removed guard; remove entirely |
| AC-12 | Android STT: 6s complete-silence timeout | `AndroidSpeechRecognizerProviderTest` | `listenInternal_should_set_completeSilenceTimeout_to_6000ms` | Unit | NEW | Inspects `Intent` extras; `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS == 6000L` |
| AC-12 | Android STT: 3s possibly-complete-silence timeout | `AndroidSpeechRecognizerProviderTest` | `listenInternal_should_set_possiblyCompleteSilenceTimeout_to_3000ms` | Unit | NEW | `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS == 3000L` |
| AC-12 | Android STT: 2s minimum recording length | `AndroidSpeechRecognizerProviderTest` | `listenInternal_should_set_minimumLengthMillis_to_2000ms` | Unit | NEW | `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS == 2000L` |

---

## Test File Summary

### `VoiceNoteBlockFormatTest` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceNoteBlockFormatTest.kt`)

| # | Test Name | Action |
|---|-----------|--------|
| 1 | `block starts with voice note header line` | UPDATE — 3-param `buildVoiceNoteBlock` |
| 2 | `block contains formatted text` | UPDATE — 3-param `buildVoiceNoteBlock` |
| 3 | `block contains raw transcript in BEGIN_QUOTE block` | DELETE — replaced by tests 6 & 7 |
| 4 | `multiline formatted text has each line indented under header` | UPDATE — 3-param `buildVoiceNoteBlock` |
| 5 | `timestamp in header has zero-padded hours and minutes` | UPDATE — regex includes seconds + wikilink |
| 6 | `transcript page includes BEGIN_QUOTE when includeRawTranscript is true` | NEW |
| 7 | `transcript page omits BEGIN_QUOTE when includeRawTranscript is false` | NEW |
| 8 | `buildVoiceNoteBlock_should_contain_wikilink_to_transcript_page` | NEW |
| 9 | `buildTranscriptPageContent_should_start_with_source_property` | NEW |
| 10 | `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content` | NEW |
| 11 | `buildTranscriptPageContent_should_passthrough_LLM_output_verbatim` | NEW |
| 12 | `buildTranscriptPageContent_should_use_raw_text_without_quote_wrapper_when_llm_disabled` | NEW |
| 13 | `success pipeline stores block with correct structure` | UPDATE — check `[[Voice Note` wikilink + transcript page exists |
| — | `makeViewModel()` helper | UPDATE — add named `scope =` param; add `currentOpenPageUuid = { null }` |

### `VoiceCaptureViewModelTest` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModelTest.kt`)

| # | Test Name | Action |
|---|-----------|--------|
| — | `word-count gate under 10 words emits Error at TRANSCRIBING` | DELETE |
| — | `9-word transcript emits Error at TRANSCRIBING` | DELETE |
| — | `10-word transcript reaches Done state` | DELETE |
| 1 | `2-word transcript reaches Done state (AC-11)` | NEW |
| 2 | `when page is open voice note is appended to that page (AC-8)` | NEW |
| 3 | `when no page is open voice note falls back to today journal (AC-9)` | NEW |

### `VoiceSettingsTest` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceSettingsTest.kt`)

> New test file. Use an existing `MockSettings` or `InMemorySettings` if available in the project; otherwise implement a simple map-backed stub inline.

| # | Test Name | Action |
|---|-----------|--------|
| 1 | `getIncludeRawTranscript_should_return_true_by_default` | NEW |
| 2 | `setIncludeRawTranscript_should_persist_value_across_get_calls` | NEW |

### `AndroidSpeechRecognizerProviderTest` (`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/voice/AndroidSpeechRecognizerProviderTest.kt`)

> New or existing test file in `androidUnitTest`. Uses Robolectric to create a real `Intent` and inspect extras.

| # | Test Name | Action |
|---|-----------|--------|
| 1 | `listenInternal_should_set_completeSilenceTimeout_to_6000ms` | NEW |
| 2 | `listenInternal_should_set_possiblyCompleteSilenceTimeout_to_3000ms` | NEW |
| 3 | `listenInternal_should_set_minimumLengthMillis_to_2000ms` | NEW |

---

## Detailed Test Specifications

### UPDATE: `makeViewModel` helper in `VoiceNoteBlockFormatTest`

```kotlin
// BEFORE (broken after constructor change — scope lands in currentOpenPageUuid slot)
private fun makeViewModel(scope: kotlinx.coroutines.CoroutineScope) = VoiceCaptureViewModel(
    VoicePipelineConfig(),
    JournalService(InMemoryPageRepository(), InMemoryBlockRepository()),
    scope,
)

// AFTER
private fun makeViewModel(scope: kotlinx.coroutines.CoroutineScope) = VoiceCaptureViewModel(
    VoicePipelineConfig(),
    JournalService(InMemoryPageRepository(), InMemoryBlockRepository()),
    currentOpenPageUuid = { null },
    scope = scope,
)
```

Note: after the refactor `buildVoiceNoteBlock` and `buildTranscriptPageContent` are free (internal) functions, not VM instance methods. Update call sites from `makeViewModel(this).buildVoiceNoteBlock(...)` to `buildVoiceNoteBlock(...)` directly.

### UPDATE: `block starts with voice note header line`

```kotlin
@Test
fun `block starts with voice note header line`() = runTest {
    val block = buildVoiceNoteBlock(
        pageTitle = "Voice Note 2026-05-02 14:35:22",
        timeLabel = "14:35:22",
        formattedText = "- formatted bullet.",
    )
    assertTrue(block.startsWith("- 📝 Voice note ("),
        "Expected block to start with '- 📝 Voice note (', got: $block")
}
```

### UPDATE: `timestamp in header has zero-padded hours and minutes`

```kotlin
@Test
fun `timestamp in header has zero-padded hours and minutes`() = runTest {
    val block = buildVoiceNoteBlock(
        pageTitle = "Voice Note 2026-05-02 14:35:22",
        timeLabel = "14:35:22",
        formattedText = "- formatted.",
    )
    val headerLine = block.lines().first()
    val timeRegex = Regex("""- 📝 Voice note \(\d{2}:\d{2}:\d{2}\) \[\[Voice Note \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]\]""")
    assertTrue(timeRegex.containsMatchIn(headerLine),
        "Expected HH:mm:ss timestamp and wikilink in header, got: $headerLine")
}
```

### NEW: `buildVoiceNoteBlock_should_contain_wikilink_to_transcript_page`

```kotlin
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
```

### NEW: `buildTranscriptPageContent_should_start_with_source_property`

```kotlin
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
```

### NEW: `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content`

```kotlin
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
```

### NEW: `buildTranscriptPageContent_should_passthrough_LLM_output_verbatim`

```kotlin
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
```

### NEW: `transcript page includes BEGIN_QUOTE when includeRawTranscript is true` (replaces deleted test)

```kotlin
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
```

### NEW: `transcript page omits BEGIN_QUOTE when includeRawTranscript is false`

```kotlin
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
```

### NEW: `buildTranscriptPageContent_should_use_raw_text_without_quote_wrapper_when_llm_disabled`

```kotlin
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
```

### UPDATE: `success pipeline stores block with correct structure`

Key assertion changes:

```kotlin
// REMOVE these assertions (inline block no longer contains BEGIN_QUOTE or raw transcript):
// assertTrue(voiceBlock.content.contains("#+BEGIN_QUOTE"), ...)
// assertTrue(voiceBlock.content.contains(transcript), ...)

// ADD: inline block has wikilink, no BEGIN_QUOTE
assertTrue(voiceBlock.content.contains("[[Voice Note"),
    "Expected wikilink to transcript page in inline block")
assertFalse(voiceBlock.content.contains("#+BEGIN_QUOTE"),
    "#+BEGIN_QUOTE must not appear in inline block; it belongs on the transcript page")

// ADD: transcript page was created
val allPages = pageRepo.getAllPages().first().getOrNull().orEmpty()
val transcriptPages = allPages.filter { it.name.startsWith("Voice Note ") }
assertTrue(transcriptPages.isNotEmpty(),
    "Expected a Voice Note transcript page to be created")
```

Note: `pageRepo` must be declared at test scope and passed into `JournalService` to be accessible for this assertion. Update the `VoiceCaptureViewModel` constructor call in this test to inject `InMemoryPageRepository` reference.

### NEW: `getIncludeRawTranscript_should_return_true_by_default` (`VoiceSettingsTest`)

```kotlin
@Test
fun `getIncludeRawTranscript_should_return_true_by_default`() {
    val settings = VoiceSettings(MockSettings())   // or MapSettings() if that's the project's test double
    assertTrue(settings.getIncludeRawTranscript(), "Default should be true")
}
```

### NEW: `setIncludeRawTranscript_should_persist_value_across_get_calls` (`VoiceSettingsTest`)

```kotlin
@Test
fun `setIncludeRawTranscript_should_persist_value_across_get_calls`() {
    val settings = VoiceSettings(MockSettings())
    settings.setIncludeRawTranscript(false)
    assertFalse(settings.getIncludeRawTranscript(), "Expected persisted false value")
    settings.setIncludeRawTranscript(true)
    assertTrue(settings.getIncludeRawTranscript(), "Expected persisted true value after re-setting to true")
}
```

### NEW: Android STT timeout tests (`AndroidSpeechRecognizerProviderTest`)

```kotlin
// These tests capture the Intent built by listenInternal() and inspect extras.
// Implementation approach: extract Intent construction into a testable helper, or
// use a subclass/spy pattern to intercept the Intent before it is dispatched.

@Test
fun `listenInternal_should_set_completeSilenceTimeout_to_6000ms`() {
    // Assert EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS == 6_000L
}

@Test
fun `listenInternal_should_set_possiblyCompleteSilenceTimeout_to_3000ms`() {
    // Assert EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS == 3_000L
}

@Test
fun `listenInternal_should_set_minimumLengthMillis_to_2000ms`() {
    // Assert EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS == 2_000L
}
```

---

## Test Stack

- **Unit**: `kotlin.test` (`assertTrue`, `assertFalse`, `assertIs`, `assertEquals`, `assertNotNull`) + `kotlinx-coroutines-test` (`runTest`, `advanceUntilIdle`, `TestCoroutineScope`)
- **Integration**: Same stack with `InMemoryPageRepository` and `InMemoryBlockRepository` as in-process test doubles — no mocking framework required
- **Android unit tests**: `junit4` + Robolectric (existing project setup) for `AndroidSpeechRecognizerProviderTest`
- **API/E2E**: Not applicable — all new behavior is `commonMain` only

---

## Coverage Targets

- Unit test coverage: ≥80% (line) for `VoiceCaptureViewModel`, `VoiceSettings`, `JournalService` new methods
- All public builder functions (`buildVoiceNoteBlock`, `buildTranscriptPageContent`): happy path + every conditional branch (`includeRawTranscript` true/false, `formattedText` null/non-null)
- All external integrations: `AndroidSpeechRecognizerProvider` Intent extras verified in `androidUnitTest`
- Arrow `Either` error paths: covered by existing `JournalService` and repository tests; new methods follow the same patterns

---

## AC Coverage Summary

| AC | Description | Tests Covering | New | Updated | Deleted |
|----|-------------|---------------|-----|---------|---------|
| AC-1 | Rich LLM output features | `buildTranscriptPageContent_should_passthrough_LLM_output_verbatim` | 1 | 0 | 0 |
| AC-2 | TODO bullet for action items | `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content` | 1 | 0 | 0 |
| AC-3 | Inline block format HH:mm:ss + wikilink + sub-bullets | `block starts with…` (upd), `timestamp…` (upd), `buildVoiceNoteBlock_should_contain_wikilink…` (new), `block contains formatted text` (upd), `multiline…` (upd) | 1 | 4 | 0 |
| AC-4 | `source::` property on transcript page | `buildTranscriptPageContent_should_start_with_source_property` (new), AC-8 integration test (new) | 2 | 0 | 0 |
| AC-5 | Transcript page has LLM bullets | `buildTranscriptPageContent_should_include_formatted_bullets_as_primary_content` (new), `success pipeline…` (upd) | 1 | 1 | 0 |
| AC-6 | `includeRawTranscript=true` → `#+BEGIN_QUOTE` present | `transcript page includes BEGIN_QUOTE when includeRawTranscript is true` | 1 | 0 | 1 |
| AC-7 | `includeRawTranscript=false` → absent; LLM null → no quote | `transcript page omits BEGIN_QUOTE…` (new), `buildTranscriptPageContent_should_use_raw_text…` (new) | 2 | 0 | 0 |
| AC-8 | Append to open page | `when page is open voice note is appended to that page (AC-8)` | 1 | 0 | 0 |
| AC-9 | Fall back to journal | `when no page is open voice note falls back to today journal (AC-9)` | 1 | 0 | 0 |
| AC-10 | `includeRawTranscript` persists in `VoiceSettings` | `getIncludeRawTranscript_should_return_true_by_default` (new), `setIncludeRawTranscript_should_persist_value_across_get_calls` (new) | 2 | 0 | 0 |
| AC-11 | 2-word transcript completes; `minWordCount` removed | `2-word transcript reaches Done state (AC-11)` (new); 3 word-count tests deleted | 1 | 0 | 3 |
| AC-12 | Android STT silence timeouts | `listenInternal_should_set_completeSilenceTimeout_to_6000ms`, `…possiblyComplete…3000ms`, `…minimumLength…2000ms` | 3 | 0 | 0 |
| **Total** | | | **17** | **6** | **4** |

**All 12 ACs covered. Coverage fraction: 12/12.**
