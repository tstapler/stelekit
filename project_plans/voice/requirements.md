# Voice Note Feature — Enhancement Requirements

## Context

The voice note feature shipped in three iterations:
1. **Story 1** — Android audio capture → Whisper STT → journal append
2. **Story 2** — LLM formatting (Claude/OpenAI) + settings UI
3. **On-device** — Android SpeechRecognizer + ML Kit as cloud-free fallback

Current block format (to be replaced):
```
- 📝 Voice note (HH:mm)
  - <LLM-formatted bullets>
  #+BEGIN_QUOTE
  <raw transcript>
  #+END_QUOTE
```

Current LLM system prompt (`DEFAULT_VOICE_SYSTEM_PROMPT` in `VoicePipelineConfig.kt`) produces only basic `- bullet` lines and `[[wiki links]]`. It does not use Logseq's richer markdown vocabulary.

**Desired new format (inline — journal or current page):**
```
- 📝 Voice note (14:35:22) [[Voice Note 2026-05-02 14:35:22]]
  - <LLM-formatted summary bullets>
```

**Desired new format (transcript page `Voice Note 2026-05-02 14:35:22`):**
```
source:: [[<page where note was recorded>]]

- <LLM-formatted bullets with #tags, TODO, bold, properties>

#+BEGIN_QUOTE
<raw transcript>
#+END_QUOTE
```

---

## Goals

Improve the voice note feature so that it takes full advantage of Logseq markdown formatting and integrates more naturally into the editor workflow.

---

## Functional Requirements

### FR-1 — Rich LLM Formatting

Update `DEFAULT_VOICE_SYSTEM_PROMPT` (and the formatting logic) so the LLM output exploits all relevant Logseq features:

| Feature | Rule |
|---------|------|
| `#tags` | Add `#tag` for topics/projects explicitly named in the transcript |
| `key:: value` properties | Extract structured properties (`status:: todo`, `priority:: high`, `date:: <date>`) when clearly implied by speech |
| `**bold** / *italic*` | Use bold for key terms, italics for emphasis or titles |
| `- TODO` markers | Detect action items ("I need to", "remember to", "make sure to") and prefix them with `TODO` |

Constraints:
- Do not invent content not present in the transcript
- Only add `#tags` or `[[links]]` for things explicitly named
- Properties block should appear at the top of the inserted block when present
- `TODO` items should be distinguishable from regular bullets in the Logseq outliner

### FR-2 — Transcript Page Creation

Instead of embedding the raw transcript inline, create a dedicated Logseq page for each voice note:

**Page name**: `Voice Note YYYY-MM-DD HH:mm:ss` (e.g. `Voice Note 2026-05-02 14:35:22`) — seconds included to prevent collisions

**Page content**:
```
source:: [[<title of the page where recording was triggered>]]

- <LLM-formatted bullets (rich markdown from FR-1)>

#+BEGIN_QUOTE
<raw transcript>
#+END_QUOTE
```

**Inline block** (inserted into the journal or current open page):
```
- 📝 Voice note (HH:mm) [[Voice Note YYYY-MM-DD HH:mm]]
```

Rules:
- The `source::` property links back to the page that was open when the recording was made (today's journal title if no page was open)
- When LLM formatting is disabled or fails, the transcript page body is the raw transcript text (no formatted bullets section — `#+BEGIN_QUOTE` wrapper is also omitted since the raw text is already the full content)
- The transcript page is created via `PageRepository` / `BlockRepository` — same pattern as `JournalService.ensureTodayJournal()`
- `buildVoiceNoteBlock()` now returns only the single-line link block
- `buildVoiceNoteBlock()` returns the header line + indented formatted summary bullets
- A new method `buildTranscriptPageContent()` builds the full transcript page content

**Raw transcript toggle** (`includeRawTranscript: Boolean`, default `true` in `VoiceSettings`):
- When `true`: transcript page includes the `#+BEGIN_QUOTE` section
- When `false`: transcript page omits it (formatted bullets only)
- Surface as a toggle in `VoiceCaptureSettings` UI panel

### FR-3 — Insert into Current Open Page

Change the voice note insert target from "always today's journal" to "current open page, falling back to today's journal".

- `VoiceCaptureViewModel` needs access to the currently-open page UUID (or `null` if no page is open)
- When a page is open in the editor: append the voice block to that page instead of the journal
- When no page is open (home screen, search, etc.): fall back to today's journal (current behavior)
- The insertion target should be resolved at the moment the pipeline completes, not when recording starts

### FR-6 — Configurable Inline vs. Transcript Page Threshold

Short voice notes (e.g. "buy milk") should stay inline to avoid clutter. Longer notes should automatically get their own transcript page. The threshold should be user-configurable.

Add `transcriptPageWordThreshold: Int` (default: 20) to `VoiceSettings`.

**Below threshold** (short content — inline only):
```
- 📝 Voice note (HH:mm:ss)
  - <formatted content or raw text>
```
No transcript page is created. No wikilink in the header.

**At or above threshold** (long content — transcript page created):
```
- 📝 Voice note (HH:mm:ss) [[Voice Note YYYY-MM-DD HH:mm:ss]]
  - <formatted summary bullets>
```
Transcript page created as described in FR-2.

Rules:
- Word count is measured on the **formatted output** (or raw transcript if LLM is off/failed)
- `transcriptPageWordThreshold` is surfaced as a numeric input in `VoiceCaptureSettings` UI
- Default of 20 words means a quick command like "remind me to call Alice tomorrow" stays inline while a proper note gets its own page

---

### FR-4 — Remove Minimum Word Count Guard

Remove the `minWordCount` check from `VoiceCaptureViewModel.processTranscript()`. The current 10-word minimum is disruptive for short but valid voice notes (e.g. "buy milk", "call Alice").

- Delete the `minWordCount: Int = 10` field from `VoicePipelineConfig`
- Delete the word-count check and its error state from `processTranscript()`
- Keep the existing check for truly empty/blank transcripts (that is handled by `TranscriptResult.Empty` upstream)

### FR-5 — Extend Android SpeechRecognizer Silence Timeout

In `AndroidSpeechRecognizerProvider`, increase the silence tolerance so users can pause to think without the recognizer auto-stopping.

Current values:
- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`: 3,000 ms
- `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`: 1,500 ms

Target values (configurable via `VoicePipelineConfig` or hardcoded to sensible defaults):
- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`: 6,000 ms
- `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`: 3,000 ms
- Add `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`: 2,000 ms (prevents premature cutoff before user has spoken)

### FR-7 — Continuous Recording with Silence Stripping (FUTURE — separate PR)

> **Out of scope for this PR.** Requires dedicated research into platform VAD APIs.

Users want to record indefinitely (manual stop only, no auto-stop on silence) while the submitted audio has silence stripped to reduce Whisper processing time and improve accuracy on long recordings.

Requirements:
- Recording continues until the user explicitly taps stop — no automatic cutoff on silence
- Before submitting to Whisper (or storing), silence frames are removed from the audio, keeping a ~200 ms buffer before and after each detected voice segment (VAD — Voice Activity Detection)
- For the AndroidSpeechRecognizer path: auto-restart the recognizer when it naturally terminates, accumulating partial transcript results until the user taps stop
- Configurable silence buffer duration (default 200 ms)

Implementation approach (to be researched):
- **Whisper path**: Energy-threshold VAD applied to the PCM frames in `AndroidAudioRecorder` before encoding to M4A
- **SpeechRecognizer path**: Auto-restart loop in `AndroidSpeechRecognizerProvider` that concatenates `onResults` partial transcripts

---

## Non-Functional Requirements

- All changes must preserve the Arrow `Either` error-handling pattern at repository boundaries
- New settings fields must be persisted alongside existing `VoiceSettings` fields
- All new code paths must have corresponding unit tests in `businessTest`
- No new platform-specific code required — changes are `commonMain` only
- `DEFAULT_VOICE_SYSTEM_PROMPT` update must not break existing `VoiceNoteBlockFormatTest` tests (adjust tests as needed)

---

## Out of Scope

- Post-insert navigation to the block (user confirmed: stay in place)
- Page picker before recording
- LLM-driven target page selection
- Desktop / iOS / web platform specifics (changes target commonMain only)

---

## Acceptance Criteria

| ID | Criterion |
|----|-----------|
| AC-1 | Transcript page may contain `#tags`, `key:: value`, `**bold**`, and `TODO` bullets when the transcript warrants them |
| AC-2 | A voice note with "I need to call Alice about the project" produces a `- TODO Call Alice about [[project]]` bullet on the transcript page |
| AC-3 | The inline block starts with `- 📝 Voice note (HH:mm:ss) [[Voice Note YYYY-MM-DD HH:mm:ss]]` followed by the LLM-formatted summary bullets as sub-items |
| AC-4 | The transcript page contains a `source::` property linking back to the originating page |
| AC-5 | The transcript page contains the LLM-formatted bullets |
| AC-6 | With `includeRawTranscript = true` (default), transcript page includes `#+BEGIN_QUOTE` raw transcript |
| AC-7 | With `includeRawTranscript = false`, transcript page omits `#+BEGIN_QUOTE` section |
| AC-8 | When a page is open in the editor, the inline link block is appended to that page |
| AC-9 | When no page is open, the inline link block falls back to today's journal |
| AC-10 | `VoiceSettings` persists `includeRawTranscript` across sessions |
| AC-11 | A 2-word voice note (e.g. "buy milk") completes successfully — transcript page created, link inserted |
| AC-12 | `AndroidSpeechRecognizerProvider` uses 6s complete silence timeout and 2s minimum recording length |
| AC-13 | A 5-word note is inserted inline (no transcript page created, no wikilink in header) when threshold is 20 |
| AC-14 | A 25-word note creates a transcript page and inserts a wikilink header when threshold is 20 |
| AC-15 | `VoiceSettings` persists `transcriptPageWordThreshold` across sessions |
