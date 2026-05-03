# Implementation Plan: voice

**Feature**: Voice note enhancements — rich LLM formatting, transcript page creation, current-page insertion, min-word-count removal, Android STT silence tuning
**Date**: 2026-05-02
**Status**: Ready for implementation
**ADRs**: None (all technology choices are existing patterns; no new dependencies)

---

## Dependency Visualization

```
FR-4 (remove minWordCount)          FR-5 (Android STT timeouts)
        |                                     |
        v                                     v
FR-1 (update system prompt)   [independent — androidMain only]
        |
        v
FR-2a (VoiceSettings + VoicePipelineConfig — includeRawTranscript)
        |
        +-----> FR-2b (JournalService.appendToPage + createTranscriptPage)
        |                  |
        v                  v
FR-3 (currentOpenPageUuid lambda on VoiceCaptureViewModel)
        |
        v
FR-2c (buildVoiceNoteBlock + buildTranscriptPageContent refactor)
        |
        v
FR-2d (VoiceCaptureSettings UI toggle)
        |
        v
Tests (VoiceNoteBlockFormatTest + VoiceCaptureViewModelTest updates)
```

---

## Phase 1: Cleanup and Config Changes

### Epic 1.1: Remove minWordCount guard (FR-4)

**Goal**: Delete the 10-word minimum so short voice notes ("buy milk") complete successfully.

#### Story 1.1.1: Delete minWordCount from config and pipeline logic
**As a** user, **I want** short voice notes to be captured without error, **so that** brief commands like "buy milk" produce a note.
**Acceptance Criteria**:
- `VoicePipelineConfig` no longer has a `minWordCount` field
- `processTranscript()` no longer has the word-count block
- 2-word transcript reaches `Done` state
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

##### Task 1.1.1a: Remove `minWordCount` from VoicePipelineConfig (~2 min)
- In `VoicePipelineConfig.kt`, delete `val minWordCount: Int = 10` from the constructor parameter list (line 22).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`

##### Task 1.1.1b: Remove word-count guard from processTranscript (~3 min)
- In `VoiceCaptureViewModel.kt`, delete lines 116–123:
  ```kotlin
  val wordCount = rawTranscript.split(Regex("\\s+")).count { it.isNotBlank() }
  if (wordCount < pipeline.minWordCount) {
      _state.value = VoiceCaptureState.Error(
          PipelineStage.TRANSCRIBING,
          "Recording too short — try speaking for a few more seconds"
      )
      return
  }
  ```
- The `TranscriptResult.Empty` guard in `startPipeline()` already blocks blank results; no new guard needed.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

---

### Epic 1.2: Android STT silence timeout extension (FR-5)

**Goal**: Users can pause mid-thought without the recognizer cutting them off.

#### Story 1.2.1: Update Intent extras in AndroidSpeechRecognizerProvider
**As a** user on Android, **I want** longer silence tolerance, **so that** I can pause to think without the recording stopping.
**Acceptance Criteria**:
- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` = 6,000 ms
- `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` = 3,000 ms
- `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` = 2,000 ms added
**Files**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/AndroidSpeechRecognizerProvider.kt`

##### Task 1.2.1a: Update three SpeechRecognizer Intent extras (~2 min)
- In `listenInternal()` (lines 108–114), change:
  - `3_000L` → `6_000L` for `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`
  - `1_500L` → `3_000L` for `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`
  - Add `putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)`
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/AndroidSpeechRecognizerProvider.kt`

---

## Phase 2: LLM Prompt Enhancement (FR-1)

### Epic 2.1: Update DEFAULT_VOICE_SYSTEM_PROMPT

**Goal**: LLM output uses #tags, key:: value properties, **bold**, *italic*, and TODO markers.

#### Story 2.1.1: Replace system prompt with rich-formatting version
**As a** user, **I want** voice notes formatted with full Logseq markdown vocabulary, **so that** tags, properties, bold text, and TODO items are extracted automatically.
**Acceptance Criteria**:
- `DEFAULT_VOICE_SYSTEM_PROMPT` includes rules for `#tag`, `key:: value`, `**bold**`, `*italic*`, `TODO`
- Three few-shot examples are included
- "Do not invent" constraints are restated for each new feature
- `{{TRANSCRIPT}}` placeholder is preserved (unchanged `replace()` call in `processTranscript()`)
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`

##### Task 2.1.1a: Replace DEFAULT_VOICE_SYSTEM_PROMPT constant (~3 min)
- Replace the existing `DEFAULT_VOICE_SYSTEM_PROMPT` const val with the following. The `{{TRANSCRIPT}}` placeholder and the `replace()` call in `processTranscript()` are unchanged.

```
You are a Logseq note-taking assistant. Convert the following voice transcript into well-structured Logseq outliner syntax.

Logseq syntax you may use:
- "- " bullet for each main point (required)
- 2-space indentation for sub-points
- [[Page Name]] wiki links — ONLY for proper nouns or topics explicitly named
- #tag — ONLY for topics or categories explicitly spoken (e.g. "#meeting", "#todo")
- key:: value property blocks — ONLY when the speaker states a clear key/value (e.g. "date:: 2026-05-02", "project:: Stelekit")
- **bold** for words the speaker stressed or called out as important
- *italic* for titles, technical terms, or qualified statements ("*maybe*", "*draft*")
- TODO at the start of a bullet for action items the speaker explicitly commits to
- DONE at the start of a bullet for completed actions explicitly mentioned

Examples of each feature:

Input: "met with Alice today about the Stelekit release, she said to make it a priority"
Output:
- Met with [[Alice]] about [[Stelekit]] release #meeting
  - She flagged this as a priority
- TODO Follow up with Alice on release timeline

Input: "project is stelekit, date is May 2nd, need to review the export feature"
Output:
- project:: Stelekit
- date:: 2026-05-02
- TODO Review the export feature

Input: "I think the new design is okay, maybe try bold colours, definitely update the readme"
Output:
- The new design is acceptable
  - Consider *bold* colours as a possibility
- TODO Update the README

Hard rules (never violate):
- Do NOT invent topics, names, tags, or properties not mentioned in the transcript
- Do NOT add a preamble, summary, or closing line
- Do NOT add content not present in the transcript
- Use TODO only when the speaker explicitly commits to an action

Transcript:
{{TRANSCRIPT}}
```

- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`

---

## Phase 2.5: Transcript Page Word Threshold (FR-6)

### Epic 2.5.1: Add `transcriptPageWordThreshold` to VoiceSettings and VoicePipelineConfig

##### Task 2.5.1a: Add getter/setter to VoiceSettings (~2 min)
- Add after `setIncludeRawTranscript`:
  ```kotlin
  fun getTranscriptPageWordThreshold(): Int =
      platformSettings.getInt(KEY_TRANSCRIPT_PAGE_WORD_THRESHOLD, 20)

  fun setTranscriptPageWordThreshold(threshold: Int) =
      platformSettings.putInt(KEY_TRANSCRIPT_PAGE_WORD_THRESHOLD, threshold)
  ```
- Add to companion object: `private const val KEY_TRANSCRIPT_PAGE_WORD_THRESHOLD = "voice.transcript_page_word_threshold"`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt`

##### Task 2.5.1b: Add `transcriptPageWordThreshold` to VoicePipelineConfig (~1 min)
- Add `val transcriptPageWordThreshold: Int = 20` to `VoicePipelineConfig` constructor.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`

##### Task 2.5.1c: Apply threshold in processTranscript (~3 min)
- After computing `formattedText`, count words: `val wordCount = formattedText.split(Regex("\\s+")).count { it.isNotBlank() }`
- Branch on `wordCount >= pipeline.transcriptPageWordThreshold`:
  - **Short path** (below threshold): inline block is `buildVoiceNoteBlockInline(timeLabel, formattedText)` — no page title, no wikilink, no transcript page created. Append to target page/journal.
  - **Long path** (at/above threshold): existing full flow — create transcript page, build wikilink block, append.
- Add `buildVoiceNoteBlockInline(timeLabel: String, formattedText: String): String` as a private helper:
  ```kotlin
  internal fun buildVoiceNoteBlockInline(timeLabel: String, formattedText: String): String {
      return buildString {
          append("- 📝 Voice note ($timeLabel)")
          append("\n  - ")
          append(formattedText.lines().joinToString("\n  - "))
      }
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

##### Task 2.5.1d: Add numeric input to VoiceCaptureSettings UI (~3 min)
- Add `var transcriptPageWordThreshold by remember { mutableStateOf(voiceSettings.getTranscriptPageWordThreshold().toString()) }` state var.
- Add a labeled `OutlinedTextField` for the threshold below the `includeRawTranscript` toggle.
- Persist in Save handler: `voiceSettings.setTranscriptPageWordThreshold(transcriptPageWordThreshold.toIntOrNull() ?: 20)`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/VoiceCaptureSettings.kt`

##### Task 2.5.1e: Wire threshold in App.kt VoicePipelineConfig construction (~1 min)
- Add `transcriptPageWordThreshold = voiceSettings.getTranscriptPageWordThreshold()` to `VoicePipelineConfig(...)` call in App.kt.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

---

## Phase 3: Settings and Config Extension (FR-2 — settings layer)

### Epic 3.1: Add `includeRawTranscript` to VoiceSettings and VoicePipelineConfig

**Goal**: New setting persists across sessions and controls whether the transcript page includes `#+BEGIN_QUOTE`.

#### Story 3.1.1: Add setting to VoiceSettings
**As a** user, **I want** a persistent setting to control raw transcript inclusion, **so that** my preference survives app restarts.
**Acceptance Criteria**:
- `getIncludeRawTranscript()` returns `true` by default
- `setIncludeRawTranscript(Boolean)` persists value
- Key follows `voice.*` namespace convention
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt`

##### Task 3.1.1a: Add getter, setter, and key constant to VoiceSettings (~2 min)
- Add after `getUseDeviceLlm()`/`setUseDeviceLlm()`:
  ```kotlin
  fun getIncludeRawTranscript(): Boolean =
      platformSettings.getBoolean(KEY_INCLUDE_RAW_TRANSCRIPT, true)

  fun setIncludeRawTranscript(enabled: Boolean) =
      platformSettings.putBoolean(KEY_INCLUDE_RAW_TRANSCRIPT, enabled)
  ```
- Add to companion object:
  ```kotlin
  private const val KEY_INCLUDE_RAW_TRANSCRIPT = "voice.include_raw_transcript"
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt`

#### Story 3.1.2: Add `includeRawTranscript` to VoicePipelineConfig
**As a** developer, **I want** pipeline behavior flags in one place, **so that** `VoiceCaptureViewModel` stays free of a `VoiceSettings` dependency.
**Acceptance Criteria**:
- `VoicePipelineConfig` has `val includeRawTranscript: Boolean = true`
- Default is `true` — existing callers unaffected
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`

##### Task 3.1.2a: Add `includeRawTranscript` parameter to VoicePipelineConfig (~2 min)
- Add `val includeRawTranscript: Boolean = true` to the `VoicePipelineConfig` constructor, after `val directSpeechProvider: DirectSpeechProvider? = null`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineConfig.kt`

---

## Phase 4: JournalService Extensions (FR-2 + FR-3 — repository layer)

### Epic 4.1: Add `appendToPage`, `createTranscriptPage`, and `getPageNameByUuid` to JournalService

**Goal**: Voice pipeline can write to any page (not just today's journal) and create the transcript page.

#### Story 4.1.1: Add `appendToPage` method
**As a** developer, **I want** `JournalService` to append a block to any page by UUID, **so that** voice notes can target the currently-open page.
**Acceptance Criteria**:
- `appendToPage(pageUuid: String, content: String)` appends a block to the given page
- When `pageUuid` doesn't resolve to a real page, falls back to `appendToToday`
- Follows the same `writeActor`-first / `blockRepository.saveBlock` fallback pattern as `appendToToday`
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

##### Task 4.1.1a: Implement `appendToPage` (~4 min)
- Add the following method to `JournalService` after `appendToToday`:
  ```kotlin
  /**
   * Appends a new block with [content] to the page identified by [pageUuid].
   * Falls back to today's journal if [pageUuid] resolves to no page.
   */
  @OptIn(DirectRepositoryWrite::class)
  suspend fun appendToPage(pageUuid: String, content: String) {
      val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull()
      if (page == null) {
          appendToToday(content)
          return
      }
      val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
      val nextPosition = (blocks.maxOfOrNull { it.position } ?: -1) + 1
      val newBlock = Block(
          uuid = UuidGenerator.generateV7(),
          pageUuid = page.uuid,
          content = content,
          position = nextPosition,
          createdAt = Clock.System.now(),
          updatedAt = Clock.System.now(),
      )
      if (writeActor != null) {
          writeActor.saveBlock(newBlock)
      } else {
          blockRepository.saveBlock(newBlock)
      }
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

#### Story 4.1.2: Add `createTranscriptPage` method
**As a** developer, **I want** `JournalService` to create a named page with content, **so that** voice notes get a dedicated transcript page.
**Acceptance Criteria**:
- `createTranscriptPage(title: String, content: String): Page` creates a page and populates its first block
- If a page with that title already exists, appends to it rather than creating a duplicate
- Returns the `Page` that was created or found
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

##### Task 4.1.2a: Implement `createTranscriptPage` (~4 min)
- Add after `appendToPage`:
  ```kotlin
  /**
   * Creates a new page with [title] and inserts [content] as its first block.
   * If a page with that exact title already exists, appends [content] to it instead.
   *
   * @return the [Page] that was created or found.
   */
  @OptIn(DirectRepositoryWrite::class)
  suspend fun createTranscriptPage(title: String, content: String): Page {
      val existing = pageRepository.getPageByName(title).first().getOrNull()
      if (existing != null) {
          appendToPage(existing.uuid, content)
          return existing
      }
      val pageUuid = UuidGenerator.generateV7()
      val newPage = Page(
          uuid = pageUuid,
          name = title,
          createdAt = Clock.System.now(),
          updatedAt = Clock.System.now(),
          isJournal = false,
      )
      if (writeActor != null) {
          writeActor.savePage(newPage)
      } else {
          pageRepository.savePage(newPage)
      }
      val newBlock = Block(
          uuid = UuidGenerator.generateV7(),
          pageUuid = pageUuid,
          content = content,
          position = 0,
          createdAt = Clock.System.now(),
          updatedAt = Clock.System.now(),
      )
      if (writeActor != null) {
          writeActor.saveBlock(newBlock)
      } else {
          blockRepository.saveBlock(newBlock)
      }
      return newPage
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

#### Story 4.1.3: Add `getPageNameByUuid` convenience method
**As a** developer, **I want** to resolve a page name from a UUID inside `VoiceCaptureViewModel`, **so that** the `source::` property on the transcript page can name the originating page.
**Acceptance Criteria**:
- `getPageNameByUuid(uuid: String): String?` returns the page name or `null` if not found
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

##### Task 4.1.3a: Add `getPageNameByUuid` (~2 min)
- Add:
  ```kotlin
  /** Returns the name of the page with [uuid], or null if not found. */
  suspend fun getPageNameByUuid(uuid: String): String? =
      pageRepository.getPageByUuid(uuid).first().getOrNull()?.name
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`

---

## Phase 5: VoiceCaptureViewModel Refactor (FR-2 + FR-3 — core logic)

### Epic 5.1: Add `currentOpenPageUuid` lambda and refactor block/page builders

**Goal**: ViewModel targets the current page, creates a transcript page, and produces the new two-part block format.

#### Story 5.1.1: Add `currentOpenPageUuid` lambda constructor parameter
**As a** developer, **I want** `VoiceCaptureViewModel` to accept a `() -> String?` lambda, **so that** it can read the currently-open page UUID at insertion time without a `StateFlow` dependency.
**Acceptance Criteria**:
- Constructor accepts `currentOpenPageUuid: () -> String? = { null }` as 3rd parameter (before `scope`)
- Existing tests still compile (default `{ null }` preserves journal fallback)
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

##### Task 5.1.1a: Add lambda parameter to constructor (~2 min)
- Add `private val currentOpenPageUuid: () -> String? = { null }` to the `VoiceCaptureViewModel` constructor after `journalService` and before `scope`:
  ```kotlin
  class VoiceCaptureViewModel(
      private val pipeline: VoicePipelineConfig,
      private val journalService: JournalService,
      private val currentOpenPageUuid: () -> String? = { null },
      scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  )
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

#### Story 5.1.2: Replace `buildVoiceNoteBlock` and add `buildTranscriptPageContent`
**As a** developer, **I want** the two builder functions to produce the new format, **so that** the inline block is a link header with sub-bullets and the transcript page has the full content.
**Acceptance Criteria**:
- `buildVoiceNoteBlock(pageTitle: String, timeLabel: String, formattedText: String): String` returns the header line + indented sub-bullets
- `buildTranscriptPageContent(sourcePage: String, formattedText: String?, rawTranscript: String, includeRawTranscript: Boolean): String` returns the full page content
- When `formattedText` is `null` (LLM disabled/failed): transcript page body is raw transcript with no `#+BEGIN_QUOTE` wrapper
- When `formattedText` is non-null and `includeRawTranscript=true`: transcript page includes `#+BEGIN_QUOTE` block
- When `formattedText` is non-null and `includeRawTranscript=false`: transcript page omits `#+BEGIN_QUOTE`
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

##### Task 5.1.2a: Replace `buildVoiceNoteBlock` and add `buildTranscriptPageContent` (~5 min)
- Remove the existing `buildVoiceNoteBlock(formattedText: String, rawTranscript: String)` method entirely.
- Add two new `internal` functions:

```kotlin
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
```

- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

#### Story 5.1.3: Update `processTranscript` to use new format and current-page routing
**As a** user, **I want** completed recordings inserted into the page I'm viewing, **so that** I don't have to navigate to the journal to find my note.
**Acceptance Criteria**:
- `processTranscript` computes `timeLabel`, `dateLabel`, and `pageTitle` at insertion time
- Creates transcript page via `journalService.createTranscriptPage(pageTitle, transcriptPageContent)`
- Appends inline block to `currentOpenPageUuid()` target when non-null, else today's journal
- `sourcePage` resolved from the target page UUID name (falls back to today's journal name if UUID lookup fails)
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

##### Task 5.1.3a: Rewrite insertion logic in `processTranscript` (~5 min)
- Track whether LLM succeeded explicitly. Replace the existing `formattedText` assignment block so that:
  ```kotlin
  var llmProducedOutput = false
  val formattedText = when (val llmResult = pipeline.llmProvider.format(rawTranscript, prompt)) {
      is LlmResult.Success -> {
          isLikelyTruncated = isLikelyTruncated || llmResult.isLikelyTruncated
          llmProducedOutput = true
          llmResult.formattedText
      }
      is LlmResult.Failure -> {
          println("[VoiceCaptureViewModel] LLM formatting failed ($llmResult), inserting raw transcript")
          rawTranscript
      }
  }
  ```
- Replace the `journalService.appendToToday(...)` call and everything after it (through the `_state.value = ...Done` line) with:
  ```kotlin
  val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
  val timeLabel = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}:${now.second.toString().padStart(2, '0')}"
  val dateLabel = "${now.year}-${now.monthNumber.toString().padStart(2, '0')}-${now.dayOfMonth.toString().padStart(2, '0')}"
  val pageTitle = "Voice Note $dateLabel $timeLabel"

  val targetPageUuid = currentOpenPageUuid()
  val sourcePage: String = if (targetPageUuid != null) {
      journalService.getPageNameByUuid(targetPageUuid) ?: dateLabel.replace('-', '_')
  } else {
      dateLabel.replace('-', '_')
  }

  val transcriptPageContent = buildTranscriptPageContent(
      sourcePage = sourcePage,
      formattedText = if (llmProducedOutput) formattedText else null,
      rawTranscript = rawTranscript,
      includeRawTranscript = pipeline.includeRawTranscript,
  )
  journalService.createTranscriptPage(pageTitle, transcriptPageContent)

  val inlineBlock = buildVoiceNoteBlock(
      pageTitle = pageTitle,
      timeLabel = timeLabel,
      formattedText = formattedText,
  )
  if (targetPageUuid != null) {
      journalService.appendToPage(targetPageUuid, inlineBlock)
  } else {
      journalService.appendToToday(inlineBlock)
  }

  _state.value = VoiceCaptureState.Done(
      insertedText = formattedText,
      isLikelyTruncated = isLikelyTruncated,
  )
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

---

## Phase 6: Settings UI Toggle (FR-2 — UI layer)

### Epic 6.1: Add `includeRawTranscript` toggle to VoiceCaptureSettings

**Goal**: Users can disable raw transcript inclusion from the settings panel.

#### Story 6.1.1: Add toggle row to VoiceCaptureSettings
**As a** user, **I want** a toggle in the voice settings panel to control raw transcript inclusion, **so that** I can keep my transcript pages clean.
**Acceptance Criteria**:
- Toggle labelled "Include raw transcript in note" appears in the "LLM Formatting" section
- State initialised from `voiceSettings.getIncludeRawTranscript()`
- Save button persists value via `voiceSettings.setIncludeRawTranscript(...)`
- Toggle is always visible (not gated on `llmEnabled`) because it also affects the no-LLM path
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/VoiceCaptureSettings.kt`

##### Task 6.1.1a: Add state variable for includeRawTranscript (~2 min)
- After `var useDeviceLlm by remember { ... }` add:
  ```kotlin
  var includeRawTranscript by remember { mutableStateOf(voiceSettings.getIncludeRawTranscript()) }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/VoiceCaptureSettings.kt`

##### Task 6.1.1b: Add Switch row to LLM Formatting section (~3 min)
- At the bottom of the `SettingsSection("LLM Formatting")` block (before the closing `}`), add:
  ```kotlin
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
  ) {
      Text("Include raw transcript in note", style = MaterialTheme.typography.bodyMedium)
      Switch(
          checked = includeRawTranscript,
          onCheckedChange = { includeRawTranscript = it; saved = false },
      )
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/VoiceCaptureSettings.kt`

##### Task 6.1.1c: Persist in Save button handler (~2 min)
- Inside the `Button(onClick = { ... })` handler, add after `voiceSettings.setUseDeviceLlm(useDeviceLlm)`:
  ```kotlin
  voiceSettings.setIncludeRawTranscript(includeRawTranscript)
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/VoiceCaptureSettings.kt`

---

## Phase 7: App.kt Wiring (FR-3 — call site)

### Epic 7.1: Thread `currentOpenPageUuid` and `includeRawTranscript` through App.kt

**Goal**: VoiceCaptureViewModel is constructed with the live page-UUID lambda and updated pipeline config.

#### Story 7.1.1: Update VoiceCaptureViewModel construction in App.kt
**As a** developer, **I want** App.kt to pass the live `currentOpenPageUuid` lambda and `includeRawTranscript`, **so that** the new routing and settings take effect in production.
**Acceptance Criteria**:
- `voiceCaptureViewModel` receives `currentOpenPageUuid = { viewModel.uiState.value.currentPage?.uuid }`
- `VoicePipelineConfig` includes `includeRawTranscript = voiceSettings.getIncludeRawTranscript()` when built
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 7.1.1a: Update VoiceCaptureViewModel construction site (~3 min)
- Locate the `remember(voicePipeline)` block that constructs `VoiceCaptureViewModel` (around line 462–463).
- Add the lambda parameter:
  ```kotlin
  val voiceCaptureViewModel = remember(voicePipeline) {
      VoiceCaptureViewModel(
          voicePipeline,
          repos.journalService,
          currentOpenPageUuid = { viewModel.uiState.value.currentPage?.uuid },
      )
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 7.1.1b: Add `includeRawTranscript` to VoicePipelineConfig construction (~2 min)
- Locate where `VoicePipelineConfig` is rebuilt from `VoiceSettings` (in the `onRebuildVoicePipeline` lambda or equivalent).
- Add `includeRawTranscript = voiceSettings.getIncludeRawTranscript()` to the `VoicePipelineConfig(...)` call.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

---

## Phase 8: Test Updates

### Epic 8.1: Update and add unit tests

**Goal**: Test suite covers the new behavior and removes obsolete tests.

#### Story 8.1.1: Update VoiceNoteBlockFormatTest
**As a** developer, **I want** the format tests to reflect the new two-function API, **so that** the test suite stays green.
**Acceptance Criteria**:
- All `buildVoiceNoteBlock` calls use the new 3-parameter signature `(pageTitle, timeLabel, formattedText)`
- `block contains raw transcript in BEGIN_QUOTE block` is replaced by two tests covering `includeRawTranscript=true` and `=false`
- `success pipeline stores block with correct structure` checks for `[[Voice Note` wikilink in inline block (not `#+BEGIN_QUOTE`)
- Timestamp regex updated to match `- 📝 Voice note (HH:mm) [[Voice Note YYYY-MM-DD HH:mm]]`
- `makeViewModel` helper uses named `scope =` parameter to avoid positional collision with new `currentOpenPageUuid` parameter
**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceNoteBlockFormatTest.kt`

##### Task 8.1.1a: Fix makeViewModel helper (~1 min)
- Update `makeViewModel` to use named `scope` parameter:
  ```kotlin
  private fun makeViewModel(scope: kotlinx.coroutines.CoroutineScope) = VoiceCaptureViewModel(
      VoicePipelineConfig(),
      JournalService(InMemoryPageRepository(), InMemoryBlockRepository()),
      currentOpenPageUuid = { null },
      scope = scope,
  )
  ```
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceNoteBlockFormatTest.kt`

##### Task 8.1.1b: Update `buildVoiceNoteBlock` call-sites in existing tests (~3 min)
- `block starts with voice note header line`: change to `makeViewModel(this).buildVoiceNoteBlock("Test Page", "14:35", "- formatted bullet.")` and assert starts with `"- 📝 Voice note (14:35) [[Test Page]]"`.
- `block contains formatted text`: change to `makeViewModel(this).buildVoiceNoteBlock("Test Page", "14:35", formatted)`.
- `multiline formatted text has each line indented under header`: change to `makeViewModel(this).buildVoiceNoteBlock("Test Page", "14:35", formatted)`.
- `timestamp in header has zero-padded hours and minutes`: change assertion regex to `"""- 📝 Voice note \(\d{2}:\d{2}:\d{2}\) \[\[Voice Note \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]\]"""` (the method now takes explicit `timeLabel` so the real-time path is not exercised here; pass a fixed `timeLabel = "14:35:22"` and check the literal).
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceNoteBlockFormatTest.kt`

##### Task 8.1.1c: Replace `block contains raw transcript in BEGIN_QUOTE block` test (~3 min)
- Delete the existing test and replace with two:
  ```kotlin
  @Test
  fun `transcript page includes BEGIN_QUOTE when includeRawTranscript is true`() = runTest {
      val raw = "this is the raw transcript text"
      val vm = makeViewModel(this)
      val content = vm.buildTranscriptPageContent("Today", "- formatted.", raw, includeRawTranscript = true)
      assertTrue(content.contains("#+BEGIN_QUOTE"))
      assertTrue(content.contains(raw))
      assertTrue(content.contains("#+END_QUOTE"))
  }

  @Test
  fun `transcript page omits BEGIN_QUOTE when includeRawTranscript is false`() = runTest {
      val raw = "this is the raw transcript text"
      val vm = makeViewModel(this)
      val content = vm.buildTranscriptPageContent("Today", "- formatted.", raw, includeRawTranscript = false)
      assertFalse(content.contains("#+BEGIN_QUOTE"))
  }
  ```
- Add import `kotlin.test.assertFalse` if not present.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceNoteBlockFormatTest.kt`

##### Task 8.1.1d: Update `success pipeline stores block with correct structure` test (~3 min)
- Change the assertion from checking `#+BEGIN_QUOTE` in the inline block to checking for `[[Voice Note` wikilink.
- Also assert a transcript page exists by checking `pageRepo` (add `InMemoryPageRepository` reference to test scope).
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceNoteBlockFormatTest.kt`

#### Story 8.1.2: Update VoiceCaptureViewModelTest
**As a** developer, **I want** the VM tests to reflect FR-3, FR-4, and the new format, **so that** CI passes.
**Acceptance Criteria**:
- Three obsolete word-count tests deleted
- New test: 2-word transcript reaches `Done` state (AC-11)
- New test: `currentOpenPageUuid` non-null → block appended to that page (AC-8)
- New test: `currentOpenPageUuid` null → block appended to today's journal (AC-9)
**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModelTest.kt`

##### Task 8.1.2a: Delete three obsolete word-count tests (~2 min)
- Delete the entire test bodies for:
  - `word-count gate under 10 words emits Error at TRANSCRIBING` (lines 51–69)
  - `9-word transcript emits Error at TRANSCRIBING` (lines 377–395)
  - `10-word transcript reaches Done state` (lines 397–414)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModelTest.kt`

##### Task 8.1.2b: Add 2-word transcript test (AC-11) (~3 min)
- Add after `success path reaches Done state`:
  ```kotlin
  @Test
  fun `2-word transcript reaches Done state (AC-11)`() = runTest {
      val fakeRecorder = object : AudioRecorder {
          override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
          override suspend fun stopRecording() = Unit
          override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
      }
      val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success("buy milk") }
      val vm = VoiceCaptureViewModel(
          VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
          makeJournalService(), scope = this,
      )
      vm.onMicTapped()
      advanceUntilIdle()
      assertIs<VoiceCaptureState.Done>(vm.state.first())
  }
  ```
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModelTest.kt`

##### Task 8.1.2c: Add current-page routing tests (AC-8 and AC-9) (~5 min)
- Add the following two tests:
  ```kotlin
  @Test
  fun `when page is open voice note is appended to that page (AC-8)`() = runTest {
      val blockRepo = InMemoryBlockRepository()
      val pageRepo = InMemoryPageRepository()
      val journalService = JournalService(pageRepo, blockRepo)
      val targetPage = journalService.ensureTodayJournal()
      val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success("buy milk") }
      val fakeRecorder = object : AudioRecorder {
          override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
          override suspend fun stopRecording() = Unit
          override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
      }
      val vm = VoiceCaptureViewModel(
          VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
          journalService,
          currentOpenPageUuid = { targetPage.uuid },
          scope = this,
      )
      vm.onMicTapped()
      advanceUntilIdle()
      assertIs<VoiceCaptureState.Done>(vm.state.first())
      val blocks = blockRepo.getBlocksForPage(targetPage.uuid).first().getOrNull().orEmpty()
      assertTrue(blocks.any { it.content.contains("📝 Voice note") })
  }

  @Test
  fun `when no page is open voice note falls back to today journal (AC-9)`() = runTest {
      val blockRepo = InMemoryBlockRepository()
      val pageRepo = InMemoryPageRepository()
      val journalService = JournalService(pageRepo, blockRepo)
      val fakeStt = SpeechToTextProvider { _ -> TranscriptResult.Success("buy milk") }
      val fakeRecorder = object : AudioRecorder {
          override suspend fun startRecording(): PlatformAudioFile = PlatformAudioFile("/tmp/test.m4a")
          override suspend fun stopRecording() = Unit
          override suspend fun readBytes(file: PlatformAudioFile) = ByteArray(100)
      }
      val vm = VoiceCaptureViewModel(
          VoicePipelineConfig(audioRecorder = fakeRecorder, sttProvider = fakeStt),
          journalService,
          currentOpenPageUuid = { null },
          scope = this,
      )
      vm.onMicTapped()
      advanceUntilIdle()
      assertIs<VoiceCaptureState.Done>(vm.state.first())
      val journalPage = journalService.ensureTodayJournal()
      val blocks = blockRepo.getBlocksForPage(journalPage.uuid).first().getOrNull().orEmpty()
      assertTrue(blocks.any { it.content.contains("📝 Voice note") })
  }
  ```
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModelTest.kt`

---

## Summary Table

| Phase | Epics | Stories | Tasks |
|-------|-------|---------|-------|
| 1 — Cleanup & Config | 2 | 2 | 3 |
| 2 — LLM Prompt | 1 | 1 | 1 |
| 3 — Settings Layer | 1 | 2 | 2 |
| 4 — JournalService | 1 | 3 | 3 |
| 5 — ViewModel Core | 1 | 3 | 4 |
| 6 — Settings UI | 1 | 1 | 3 |
| 7 — App.kt Wiring | 1 | 1 | 2 |
| 8 — Tests | 1 | 2 | 7 |
| **Total** | **9** | **15** | **25** |

---

## Key Implementation Notes

### `makeViewModel` positional parameter collision
After adding `currentOpenPageUuid` as the 3rd constructor parameter (before `scope`), the existing `makeViewModel(this)` helper in `VoiceNoteBlockFormatTest` passes `this` (the `TestScope`) positionally — it will land in the new `currentOpenPageUuid` parameter slot and fail to compile. Task 8.1.1a fixes this with named `scope = scope` syntax.

### `llmProducedOutput` flag
The current code reuses `formattedText == rawTranscript` as an implicit LLM-failure signal. Task 5.1.3a introduces an explicit `llmProducedOutput: Boolean` to distinguish "LLM ran and returned formatted output" from "LLM was skipped or failed". This is cleaner and avoids false matches when the LLM happens to reproduce the raw transcript verbatim.

### `source::` placement
Logseq parses `key:: value` lines at the top of a page as page properties when they appear before any blank line. The `buildTranscriptPageContent` implementation in Task 5.1.2a places `source:: [[...]]` first, followed by `\n\n`, which matches this expectation.

### Transcript page `#+BEGIN_QUOTE` — LLM disabled/failed
When `formattedText` is `null` (passed as `null` when `!llmProducedOutput`), both the inline sub-bullets and the transcript page body use the raw transcript. No `#+BEGIN_QUOTE` wrapper is added even if `includeRawTranscript=true`, because the raw text is already the primary content (not a supplemental reference). This matches FR-2 requirements.

### Flagged choices
**None.** All changes use existing project patterns: Arrow `Either` at repository boundaries, `DirectRepositoryWrite` opt-in annotation, `writeActor`-first / direct fallback pattern, `() -> String?` lambda for late-bound read-only access to VM state, `remember { }` with own internal scope. No new dependencies, no new platform-specific source sets.
