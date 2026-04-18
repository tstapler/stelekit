# Implementation Plan: Mobile Voice Mode

**Feature**: Voice capture → STT → LLM format → Logseq journal insert
**Branch**: `stelekit-mobile-mode`
**Status**: Story 1 complete (PR #2, CI passing) — Story 2 ready to begin
**Date**: 2026-04-18
**Last Updated**: 2026-04-18

---

## Overview

Users tap a mic button in the Android bottom bar, speak freely for any duration, and a correctly
formatted Logseq outliner entry (`- bullets`, `[[wikilinks]]`) appears in today's daily journal
page. No typing required from tap to saved entry.

The pipeline: `AudioRecorder` → `.m4a` temp file → `SpeechToTextProvider` → `LlmFormatterProvider`
→ `JournalService.appendToToday()`.

All three pipeline seams are `suspend fun interface` with `NoOp` defaults, injected via
`VoicePipelineConfig` into `StelekitApp`. This is the same pattern as `TopicEnricher` (ADR-002,
import-topic-suggestions) and `UrlFetcher`.

---

## Architecture Decisions

| ADR | Decision |
|-----|----------|
| ADR-001 | Plain `interface AudioRecorder`; Android uses `AudioRecord`+`MediaCodec`→`.m4a`; iOS uses `AVAudioRecorder`→`.m4a` |
| ADR-002 | `suspend fun interface SpeechToTextProvider`; v1 default = `WhisperSpeechToTextProvider` (Ktor); sealed `TranscriptResult` |
| ADR-003 | `suspend fun interface LlmFormatterProvider`; v1 defaults = `NoOpLlmFormatterProvider` (Story 1) then `ClaudeLlmFormatterProvider`/`OpenAiLlmFormatterProvider` (Story 2) |
| ADR-004 | Constructor injection via `VoicePipelineConfig`; wired in platform entry points |
| ADR-005 | `sealed interface VoiceCaptureState`; `VoiceCaptureButton` FAB slot in `PlatformBottomBar`; single cancellable `Job` in `VoiceCaptureViewModel` |

---

## File Map

New files to create, grouped by source set:

```
kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  voice/
    AudioRecorder.kt              — interface + NoOpAudioRecorder + PlatformAudioFile
    SpeechToTextProvider.kt       — interface + NoOpSpeechToTextProvider + TranscriptResult
    LlmFormatterProvider.kt       — interface + NoOpLlmFormatterProvider + LlmResult
    VoicePipelineConfig.kt        — data class bundling all three providers + systemPrompt
    VoiceCaptureState.kt          — sealed interface VoiceCaptureState + PipelineStage enum
    VoiceCaptureViewModel.kt      — orchestration ViewModel
    WhisperSpeechToTextProvider.kt
    ClaudeLlmFormatterProvider.kt  (Story 2)
    OpenAiLlmFormatterProvider.kt  (Story 2)
    VoiceSettings.kt               (Story 2 — API key storage abstraction)
  ui/components/
    VoiceCaptureButton.kt          — Compose composable observing VoiceCaptureState

kmp/src/androidMain/kotlin/dev/stapler/stelekit/
  voice/
    AndroidAudioRecorder.kt        — AudioRecord + MediaCodec AAC encoder

kmp/src/iosMain/kotlin/dev/stapler/stelekit/
  voice/
    IosAudioRecorder.kt            (Story 3)
    IosSpeechToTextProvider.kt     (Story 3)
```

Modified files:

```
kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt
  — StelekitApp gains voicePipeline: VoicePipelineConfig parameter
  — GraphContent creates VoiceCaptureViewModel
  — GraphContent passes voiceCaptureButton slot to PlatformBottomBar

kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/PlatformBottomBar.android.kt
  — Add voiceCaptureButton: @Composable () -> Unit = {} slot
  — Add VoiceCaptureButton to bottom bar layout

kmp/src/androidMain/.../MainActivity.kt (or equivalent entry point)
  — Wire AndroidAudioRecorder, WhisperSpeechToTextProvider, ClaudeLlmFormatterProvider

kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SettingsDialog.kt  (Story 2)
  — Add Voice API Keys section (Whisper key, Claude/OpenAI key)
```

---

## Epic: Mobile Voice Mode

Deliver a hands-free voice capture pipeline that transcribes speech, formats it into Logseq
outliner syntax, and appends it to today's daily journal. Fully extensible via pluggable STT
and LLM provider interfaces.

---

## Story 1: Core pipeline — commonMain + Android, raw transcript [STATUS: COMPLETE]

**Goal**: End-to-end wire from mic tap to journal insert. No LLM formatting. Raw transcript
appended verbatim. Validates the entire pipeline plumbing before adding complexity.

**Acceptance criteria**: All met. PR #2 open, CI passing.
- User taps mic button in Android bottom bar → `Recording` state (pulsing red indicator)
- User taps stop → `Transcribing` state (spinner)
- After transcription → `Done` state; transcript appended to today's journal as a timestamped
  block with the raw text
- `VoiceCaptureState.Error` surfaces a dismissible message for permission denied, network error,
  and empty transcript scenarios
- Pipeline cancels cleanly on back press or app backgrounding
- Temp `.m4a` file is deleted in all exit paths (success, failure, cancel)

### Tasks

**T1.1 — Define core interfaces and value types** [Micro, 1h] [STATUS: COMPLETE]

Create `AudioRecorder.kt`, `SpeechToTextProvider.kt`, `LlmFormatterProvider.kt`,
`VoiceCaptureState.kt` in `commonMain/voice/`. Each file contains:
- The `suspend fun interface` or `sealed interface`
- The `NoOp` default implementation
- The sealed result type (`TranscriptResult`, `LlmResult`)
- `PlatformAudioFile` value class in `AudioRecorder.kt`

No logic — pure interface definitions. Tests can be written against these immediately.

**T1.2 — `VoicePipelineConfig` data class** [Micro, 1h] [STATUS: COMPLETE]

Create `VoicePipelineConfig.kt` bundling `audioRecorder`, `sttProvider`, `llmProvider`, and
`systemPrompt`. Default constructs all `NoOp` providers. Define
`DEFAULT_VOICE_SYSTEM_PROMPT` constant here.

**T1.3 — `VoiceCaptureViewModel`** [Medium, 3h] [STATUS: COMPLETE]

Create `VoiceCaptureViewModel` in `commonMain/voice/`. Responsibilities:
- `StateFlow<VoiceCaptureState>` initialized to `Idle`
- `onMicTapped()`: if `Idle`, start pipeline `Job`; if `Recording`, call `audioRecorder.stopRecording()`
- `cancel()`: cancel `pipelineJob`, reset to `Idle`
- `dismissError()`: reset to `Idle`
- Pipeline sequence: `recordToFile()` → `transcribe()` → (NoOp LLM) → `journalService.appendToToday()`
- Temp file cleanup in `finally` block after `transcribe()` returns
- Word-count gate: if `transcript.split().size < 10`, emit `TranscriptResult.Empty` and skip LLM
- Journal insert: `"${timestamp}\n${formattedText}\n\n---\nRaw: ${rawTranscript}"` as a new block
- `Done` carries `insertedText` for the UI confirmation message

Tests: inject `FakeAudioRecorder`, `FakeSpeechToTextProvider` (returns fixed transcript),
`NoOpLlmFormatterProvider`. Verify state transitions for success, empty transcript, network error,
and cancel paths.

**T1.4 — `WhisperSpeechToTextProvider`** [Small, 2h] [STATUS: COMPLETE]

Create `WhisperSpeechToTextProvider(httpClient: HttpClient, apiKey: String)` in `commonMain`.
- Ktor multipart POST to `https://api.openai.com/v1/audio/transcriptions`
- Form fields: `file` (`.m4a` bytes), `model` (`gpt-4o-mini-transcribe`), `response_format` (`text`)
- Maps HTTP 401 → `TranscriptResult.Failure.ApiError(401, "Invalid API key")`
- Maps HTTP 429 → `TranscriptResult.Failure.ApiError(429, "Rate limit exceeded")`
- Maps network exception → `TranscriptResult.Failure.NetworkError`
- Maps empty/whitespace-only response → `TranscriptResult.Empty`
- Does NOT implement the `< 10 word` gate — that lives in `VoiceCaptureViewModel`

Tests: use `MockEngine` (already in `jvmTest` dependencies) to verify multipart request shape,
HTTP 200 success, HTTP 401 error mapping, and empty response mapping.

**T1.5 — `AndroidAudioRecorder`** [Large, 4h] [STATUS: COMPLETE]

Create `AndroidAudioRecorder(context: Context)` in `androidMain`. Critical implementation details:

- Audio source: `AudioSource.VOICE_COMMUNICATION` (not `DEFAULT`) — applies system noise
  cancellation
- `AudioRecord` configuration: 16 kHz sample rate, `AudioFormat.ENCODING_PCM_16BIT`, mono
- `MediaCodec` AAC encoder: 128 kbps, 44.1 kHz output (Whisper-compatible)
- Output: temp file in `context.cacheDir` with `.m4a` extension
- Audio focus: request `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT` before starting; register
  `OnAudioFocusChangeListener`; on `AUDIOFOCUS_LOSS_TRANSIENT`, pause the read loop; on
  `AUDIOFOCUS_LOSS`, stop recording; abandon focus after stop
- Recording loop: runs on `Dispatchers.IO`; reads PCM chunks from `AudioRecord` into a
  `ByteArray` buffer; feeds chunks to `MediaCodec` encoder
- `stopRecording()`: signals the recording loop to stop, drains the encoder, flushes the
  muxer, closes the file
- Permission: wrap `AudioRecord.startRecording()` in `try/catch(SecurityException)`;
  on `SecurityException`, return `PlatformAudioFile("")` and emit a special error signal to
  the ViewModel (via a `MutableStateFlow<Boolean>` `permissionDenied` property on the interface
  — or by throwing a custom sealed exception that the ViewModel maps to `Error(RECORDING, ...)`)

Lifecycle: the `AndroidAudioRecorder` instance is created in `MainActivity` and lives as long
as the `VoiceCaptureViewModel`. It does not hold an `Activity` context.

Tests: unit test the PCM→AAC pipeline with a short fixed PCM input. Integration test requires
an Android device or Robolectric with audio mocks.

**T1.6 — Wire into `App.kt` and `PlatformBottomBar`** [Medium, 3h] [STATUS: COMPLETE]

- Add `voicePipeline: VoicePipelineConfig = remember { VoicePipelineConfig() }` to `StelekitApp`
- In `GraphContent`: create `VoiceCaptureViewModel` with `remember { ... }` (same pattern as
  `JournalsViewModel`)
- Add `ON_PAUSE` lifecycle observer to call `voiceCaptureViewModel.cancel()` (stops recording
  if app is backgrounded)
- Add `voiceCaptureButton: @Composable () -> Unit = {}` slot to `PlatformBottomBar.android.kt`
- Pass `voiceCaptureButton = { VoiceCaptureButton(state, onClick) }` from `GraphContent`
- Wire `AndroidAudioRecorder`, `WhisperSpeechToTextProvider` in `MainActivity`

**T1.7 — `VoiceCaptureButton` composable** [Small, 2h] [STATUS: COMPLETE]

Create `VoiceCaptureButton(state: VoiceCaptureState, onTap: () -> Unit, onDismissError: () -> Unit)`
in `commonMain/ui/components/`.

State-to-visual mapping:
- `Idle`: `Icons.Outlined.Mic`, normal FAB size
- `Recording`: pulsing red `CircleShape` background + `Icons.Filled.Stop`, accessibility
  label "Stop recording"
- `Transcribing`: `CircularProgressIndicator` + label "Transcribing..."
- `Formatting`: `CircularProgressIndicator` + label "Formatting..."
- `Done`: `Icons.Filled.Check` (green), auto-resets after 3 seconds via
  `LaunchedEffect(state) { delay(3000); onAutoReset() }`
- `Error`: `Icons.Filled.ErrorOutline` (red); tapping calls `onDismissError()`

The pulse animation for `Recording` uses `animateFloat` with `RepeatMode.Reverse`.

Screenshot test via Roborazzi for each state variant.

---

## Story 2: LLM formatting + settings [STATUS: READY TO BEGIN]

**Goal**: Format the raw transcript into Logseq outliner syntax via Claude or OpenAI. User can
configure API keys in Settings. Word-count guard blocks LLM call for empty/silence recordings.

**Acceptance criteria**:
- Settings screen has a "Voice Capture" section with fields for Whisper API key, Anthropic
  Claude key, and OpenAI key
- After saving a key, the corresponding provider is activated and the mic button works
  end-to-end with LLM formatting
- Journal entry contains: formatted outliner block + collapsible raw transcript block below
- `< 10 word` transcripts skip LLM and append raw transcript directly (no API cost)
- LLM failure falls back to raw transcript insert with an error notification

### Tasks

**T2.1 — `ClaudeLlmFormatterProvider`** [Small, 2h]

Create `ClaudeLlmFormatterProvider(httpClient: HttpClient, apiKey: String)` in `commonMain`.
- Ktor POST to `https://api.anthropic.com/v1/messages`
- Mirrors `ClaudeTopicEnricher.kt` structure exactly
- Request: `model = "claude-haiku-4-5"`, `max_tokens = max(512, transcriptWordCount * 2)`,
  `messages = [{"role": "user", "content": systemPrompt.replace("{{TRANSCRIPT}}", transcript)}]`
- Maps result to `LlmResult.Success(content)` or appropriate `LlmResult.Failure` variant
- Truncation detection: if `formattedText` last char is not `.`, `?`, `!`, `]`, or `\n`,
  set a flag in `Done` state to show "Formatting may be incomplete" warning

Tests: `MockEngine` for success, 401, 429, malformed JSON response.

**T2.2 — `OpenAiLlmFormatterProvider`** [Small, 2h]

Create `OpenAiLlmFormatterProvider(httpClient: HttpClient, apiKey: String, baseUrl: String = "https://api.openai.com")`
in `commonMain`.
- Ktor POST to `$baseUrl/v1/chat/completions`
- `model = "gpt-4o-mini"`, `messages = [{"role": "system", "content": systemPrompt}, {"role": "user", "content": transcript}]`
- Compatible with any OpenAI-compatible endpoint via `baseUrl`

Tests: same pattern as `ClaudeLlmFormatterProvider`.

**T2.3 — `VoiceSettings` — API key storage** [Small, 2h]

Create `VoiceSettings` interface in `commonMain` with `expect` / `actual` for secure key
storage:
- `getWhisperApiKey(): String?`
- `setWhisperApiKey(key: String)`
- `getAnthropicKey(): String?`
- `setAnthropicKey(key: String)`
- `getOpenAiKey(): String?`
- `setOpenAiKey(key: String)`

Android actual: Android `EncryptedSharedPreferences` (Jetpack Security Crypto).
iOS actual: `UserDefaults` for v1 (Keychain integration is Phase 2 hardening).
JVM actual: encrypted properties file in `~/.stelekit/`.

**T2.4 — Settings UI for voice providers** [Medium, 3h]

Add a "Voice Capture" section to `SettingsDialog.kt`:
- `OutlinedTextField` for each API key (masked input, `visualTransformation = PasswordVisualTransformation()`)
- Save button writes keys via `VoiceSettings`
- On save, `MainActivity` rebuilds `VoicePipelineConfig` with the new providers and calls a
  `StelekitApp`-level recomposition trigger (or uses a `StateFlow<VoicePipelineConfig>`)
- Informational text: "Whisper key: OpenAI audio transcription (~$0.003/min). LLM key: formats
  transcript into Logseq outliner syntax."
- "Use no formatting (append raw transcript)" toggle — sets `llmProvider = NoOpLlmFormatterProvider`

**T2.5 — Connect LLM providers in `VoiceCaptureViewModel`** [Small, 2h]

Update `VoiceCaptureViewModel` pipeline to use the injected `llmProvider`:
- After `TranscriptResult.Success`, emit `VoiceCaptureState.Formatting`
- Call `llmProvider.format(transcript, systemPrompt.replace("{{TRANSCRIPT}}", transcript))`
- On `LlmResult.Success(formatted)`: build journal block:
  ```
  {{formatted}}

  #+BEGIN_QUOTE
  Raw transcript: {{rawTranscript}}
  #+END_QUOTE
  ```
  Call `journalService.appendToToday(block)`
- On `LlmResult.Failure`: fall back to raw transcript insert; emit
  `VoiceCaptureState.Error(LLM, message)` with a "Formatted using raw transcript" notification
- `max_tokens` hint: pass `(transcript.split(" ").size * 2).coerceAtLeast(512)` as
  `systemPrompt` metadata — or add an optional `maxOutputTokens: Int` parameter to
  `LlmFormatterProvider.format()` (evaluate during spike)

**T2.6 — Journal block format** [Micro, 1h]

Define the final journal block format as a constant and write a pure function test:
```
- 📝 Voice note (HH:mm)
  - [formatted bullet 1]
  - [formatted bullet 2]
  - ...
  #+BEGIN_QUOTE
  {{rawTranscript}}
  #+END_QUOTE
```
The `#+BEGIN_QUOTE` block is Logseq's collapsible quote syntax. Unit test the formatter with
known inputs → expected output.

---

## Story 3: iOS adapter + processing state feedback

**Goal**: iOS users have a working voice capture pipeline using `SFSpeechRecognizer`. Android
and iOS both show a visual waveform/pulse during recording to confirm the mic is active.

**Acceptance criteria**:
- iOS: `IosAudioRecorder` records to `.m4a` in `NSTemporaryDirectory`
- iOS: `IosSpeechToTextProvider` uses `SFSpeechRecognizer` as Tier 1 (free), falls back to
  Whisper API when unavailable
- Both platforms: `Recording` state shows a pulsing animation that reacts to audio level
  (amplitude-based pulse scale)
- `AVAudioSession` category is set to `.record` before recording and restored to `.playback`
  after stop
- iOS permission denial navigates user to Settings with an explanatory message

### Tasks

**T3.1 — `IosAudioRecorder`** [Large, 4h]

Create `IosAudioRecorder` in `iosMain` using `AVAudioRecorder`.

Critical sequence:
1. Check `AVAudioSession.recordPermission` — if `.denied`, return early (ViewModel detects
   empty path and emits `Error(RECORDING, "Microphone access denied")`)
2. If `.undetermined`, call `requestRecordPermission` and await callback
3. **Before starting**: `AVAudioSession.sharedInstance().setCategory(.record, mode: .measurement)`
   — this MUST precede `AVAudioRecorder.record()`. Forgetting it causes silent recording failure.
4. `AVAudioSession.setActive(true)`
5. Create `AVAudioRecorder` with URL in `NSTemporaryDirectory()` and AAC settings
6. `recorder.record()`
7. `stopRecording()`: call `recorder.stop()`, `AVAudioSession.setCategory(.playback)`,
   `AVAudioSession.setActive(false)`, return file URL as `PlatformAudioFile`

Handle `AVAudioSessionInterruptionNotification` (phone call): on interruption begin, call
`stopRecording()` and emit signal to ViewModel.

**T3.2 — `IosSpeechToTextProvider`** [Large, 4h]

Create `IosSpeechToTextProvider` in `iosMain` using `SFSpeechRecognizer`.
- `requiresOnDeviceRecognition = true` where `SFSpeechRecognizer.supportsOnDeviceRecognition`
- For recordings longer than 50 seconds: chunk the audio file at silence gaps and transcribe
  each chunk separately, then concatenate with a single space separator
- `SFSpeechRecognizer.requestAuthorization` before first use
- Map `SFSpeechRecognizer` not available → fall back to `WhisperSpeechToTextProvider`
- Map authorization denied → `TranscriptResult.Failure.PermissionDenied`

**T3.3 — Amplitude-reactive pulse in `VoiceCaptureButton`** [Small, 2h]

Extend `AudioRecorder` interface with an optional `amplitudeFlow: Flow<Float>?` property
(defaults to `null`). `AndroidAudioRecorder` emits RMS amplitude from the `AudioRecord` read
loop. `VoiceCaptureButton` uses `amplitudeFlow` when non-null to scale the pulse animation
radius. Falls back to a fixed-period pulse when null (e.g., iOS v1 or NoOp).

**T3.4 — iOS permission rationale screen** [Small, 2h]

When `SFSpeechRecognizer` or microphone authorization is `.denied` on iOS, surface a bottom
sheet explaining why the permission is needed with a "Open Settings" button that deep-links
to `UIApplication.openSettingsURLString`. This replaces the generic `Error` state for the
permission denial case on iOS.

---

## Known Issues

### CRITICAL: AVAudioSession category must be set before recording starts [iOS]

**Description**: `AVAudioSession.sharedInstance().setCategory(.record)` must be called before
`AVAudioRecorder.record()` (or `AVAudioEngine.start()`). If omitted, `AVAudioRecorder` starts
without error but records silence. Whisper transcribes silence as "Thank you." — a confusing
silent failure with no indication anything went wrong.

**Mitigation**:
- Enforce via code ordering in `IosAudioRecorder`: category set is step 3, recording is step 6
- Add a comment `// MUST precede recorder.record() — see ADR-001` at the category-set line
- Integration test: record 3 seconds on simulator, assert temp file size > 1 KB

**Files**: `IosAudioRecorder.kt` (T3.1)

---

### CRITICAL: Use `AudioRecord` not `MediaRecorder` on Android [Android]

**Description**: `MediaRecorder` writes corrupted MP4 box headers when interrupted by a phone
call or audio focus loss on API < 24. The partial file may not be readable by Whisper. The
corruption is silent — no exception is thrown.

**Mitigation**:
- `AndroidAudioRecorder` must use `AudioRecord` (raw PCM) + `MediaCodec` AAC encoder
- Implement `OnAudioFocusChangeListener`: on `AUDIOFOCUS_LOSS_TRANSIENT`, pause the read loop;
  on `AUDIOFOCUS_GAIN`, resume
- Integration test: simulate audio focus loss mid-recording and verify the output `.m4a` is valid

**Files**: `AndroidAudioRecorder.kt` (T1.5)

---

### HIGH: Whisper silence hallucination produces fake transcript [All platforms]

**Description**: If the user records in silence (e.g., forgot to speak, mic covered), Whisper
transcribes silence as "Thank you." or similar tokens. Without a guard, this calls the LLM
which formats "Thank you." into a bullet point appended to the journal.

**Mitigation**:
- Word-count gate in `VoiceCaptureViewModel`: `if (transcript.split(" ").size < 10)` emit
  `TranscriptResult.Empty` and surface "Nothing was captured — try again"
- Do NOT call the LLM on `TranscriptResult.Empty`
- Future: VAD (voice activity detection) gate before Whisper upload

**Files**: `VoiceCaptureViewModel.kt` (T1.3), `WhisperSpeechToTextProvider.kt` (T1.4)

---

### HIGH: `[[wikilink]]` hallucination creates dangling pages [All platforms]

**Description**: LLMs invent `[[Page Names]]` that do not exist in the user's graph. Logseq and
SteleKit create stub pages for these links, polluting the graph with empty pages named after
hallucinated entities.

**Mitigation**:
- System prompt constraint: "Add [[Page Name]] wiki links ONLY for proper nouns or topics
  explicitly named in the transcript — do NOT invent links for terms not spoken"
- Always preserve raw transcript as collapsible `#+BEGIN_QUOTE` block below formatted output
  so the user can verify what was said vs what the LLM produced
- Future (v2): pass graph page name index to LLM as context, constraining links to existing pages

**Files**: `VoicePipelineConfig.kt` (T1.2), `VoiceCaptureViewModel.kt` (T2.5)

---

### HIGH: Android 14+ foreground service requires `foregroundServiceType="microphone"` [Android, Phase 3]

**Description**: For Phase 3 (lock-screen recording via foreground service), omitting
`android:foregroundServiceType="microphone"` in the manifest silently denies microphone access
with targetSdk >= 30. On targetSdk >= 34, the additional
`android.permission.FOREGROUND_SERVICE_MICROPHONE` permission is also required. Omitting either
produces no crash — just empty audio, exactly like the AVAudioSession pitfall.

**Mitigation** (Phase 3 only — not required for Story 1-3 foreground recording):
- Manifest entry: `android:foregroundServiceType="microphone"` in the `<service>` declaration
- Permission: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />`
- The foreground service must be started while the app is still visible (before backgrounding)
- Add a checklist item to the Phase 3 story

**Files**: `AndroidManifest.xml` (Phase 3)

---

### MEDIUM: Android `SecurityException` on mid-session permission revocation [Android]

**Description**: Android 12+ shows a green mic indicator when the microphone is active. A user
can tap it and revoke mic permission from the Privacy Dashboard while recording is in progress.
`AudioRecord.read()` then throws `SecurityException` in the recording coroutine.

**Mitigation**:
- Wrap `AudioRecord.startRecording()` and the read loop in `try/catch(SecurityException)`
- On catch: stop the `AudioRecord`, return `PlatformAudioFile("")`
- `VoiceCaptureViewModel` maps empty path to `Error(RECORDING, "Microphone permission revoked")`
- Show a snackbar with a deep link to app permission settings

**Files**: `AndroidAudioRecorder.kt` (T1.5)

---

### MEDIUM: Temp file leak on app force-kill [All platforms]

**Description**: The `.m4a` temp file is cleaned up in `VoiceCaptureViewModel`'s `finally`
block. If the process is force-killed mid-recording (OOM killer, etc.), the `finally` block
does not run and the temp file leaks in the cache directory.

**Mitigation**:
- On `VoiceCaptureViewModel` initialization, scan `cacheDir` for `.m4a` files older than 1 hour
  and delete them. A single scan on startup costs <1 ms.
- Log leaked files at `WARN` level: `[voice-capture] found leaked temp file: {path}, deleting`

**Files**: `VoiceCaptureViewModel.kt` (T1.3)

---

### LOW: LLM output truncation on long transcripts [All platforms]

**Description**: If `max_tokens` is set too low or the model hits its output limit, the formatted
output is truncated mid-bullet. The last line may be malformed Logseq markdown.

**Mitigation**:
- Set `max_tokens = (transcript.split(" ").size * 2).coerceAtLeast(512).coerceAtMost(4096)`
- Truncation detection: if `formattedText` last character is not `.`, `?`, `!`, `]`, or `\n`,
  set a `isLikelyTruncated = true` flag in `VoiceCaptureState.Done`
- Show "Formatting may be incomplete — check the raw transcript" in the UI when flag is set

**Files**: `VoiceCaptureViewModel.kt` (T2.5)

---

## New Gradle Dependencies

Story 1-3 require no new `commonMain` or `androidMain` dependencies. Ktor 3.1.3 and
`kotlinx-serialization` are already present.

Story 3 (iOS) requires adding `Speech.framework` to the iOS cinterop definition in
`build.gradle.kts`:
```kotlin
val iosMain by getting {
    compilations.getByName("main") {
        cinterops {
            val speech by creating {
                defFile(project.file("src/nativeInterop/cinterop/speech.def"))
            }
        }
    }
}
```

Phase 2 (ML Kit GenAI STT) requires adding to `androidMain`:
```kotlin
implementation("com.google.mlkit:genai-speech-recognition:1.0.0") // verify version
```

---

## Open Spikes

These require short implementation spikes before the relevant story begins:

1. **ML Kit GenAI STT for long recordings** (before Phase 2): The API provides streaming partial +
   final results. Does it handle 5–10 minute recordings stably, or require chunking? Record a
   15-minute test on a Pixel 9. Answers whether `AndroidMlKitSpeechToTextProvider` needs a
   chunking layer.

2. **Optimal Logseq system prompt** (before T2.1): Test 10 diverse transcript samples against the
   v1 system prompt. Measure: (a) correct `- ` bullet format, (b) correct 2-space indentation,
   (c) absence of hallucinated `[[links]]`, (d) no preamble or summary. Iterate until >90%
   compliance. Document winning prompt in `VoicePipelineConfig.kt`.

3. **`StelekitApp` parameter threading strategy** (before T1.6): `StelekitApp` currently has 5
   parameters. Confirm that `VoicePipelineConfig` as a single 6th parameter is the right
   aggregation level. Review whether `urlFetcher` should also be folded into a broader
   `EnrichmentConfig` at the same time.

4. **iOS `PlatformBottomBar` actual** (before T1.6): Confirm whether there is an `iosMain` actual
   for `PlatformBottomBar` or if it shares the Android composable via `commonMain`. The mic
   button slot must appear on both platforms.

---

## Delivery Sequence

**Story 1** is the mandatory first deliverable. It validates the full pipeline plumbing (mic →
file → STT → journal insert) without LLM cost or API key friction. The `NoOpLlmFormatterProvider`
default ensures the pipeline works even without Story 2.

**Story 2** adds the value-delivering formatting step and the settings UX to configure API keys.
It can begin immediately after Story 1 is merged.

**Story 3** adds iOS support and UX polish. It is independent of Story 2 and can run in parallel
once Story 1 interfaces are stable.

**Phase 2 and 3** (ML Kit GenAI STT, foreground service, FoundationModels LLM, waveform
animation) are post-v1 enhancements. They do not require interface changes — all are new
implementations behind the existing `SpeechToTextProvider` and `LlmFormatterProvider` contracts.

---

## Testing Strategy

| Layer | Coverage target | Approach |
|-------|----------------|----------|
| `VoiceCaptureViewModel` | State machine transitions | Unit tests with fake providers in `businessTest` |
| `WhisperSpeechToTextProvider` | HTTP request shape + error mapping | `MockEngine` in `jvmTest` |
| `ClaudeLlmFormatterProvider` | HTTP request shape + error mapping | `MockEngine` in `jvmTest` |
| `OpenAiLlmFormatterProvider` | HTTP request shape + error mapping | `MockEngine` in `jvmTest` |
| `VoiceCaptureButton` | All 6 state variants | Roborazzi screenshot tests in `jvmTest` |
| `AndroidAudioRecorder` | PCM→AAC encoding pipeline | Unit test with fixed PCM input |
| `IosAudioRecorder` | Session configuration | iOS unit tests with `AVAudioSession` mock |
| Word-count gate | Boundary at 9 and 10 words | `businessTest` |
| Temp file cleanup | Finally block fires on cancel | `businessTest` with `FakeAudioRecorder` that tracks deletion calls |
| Journal block format | Known input → expected Logseq markdown | `businessTest` pure function test |

Critical edge cases to cover:
- `SecurityException` during `AudioRecord.startRecording()` → `Error(RECORDING, ...)`
- `TranscriptResult.Empty` (< 10 words) → `Done` with no LLM call
- `LlmResult.Failure` → raw transcript fallback insert
- Cancel during `Transcribing` → temp file deleted, state → `Idle`
- Double-tap during `Recording` → only one `stopRecording()` call

---

## References

- ADR-001: Audio Capture Adapter — `project_plans/mobile-voice-mode/decisions/ADR-001-audio-capture-adapter.md`
- ADR-002: STT Provider Interface — `project_plans/mobile-voice-mode/decisions/ADR-002-stt-provider-interface.md`
- ADR-003: LLM Formatter Provider Interface — `project_plans/mobile-voice-mode/decisions/ADR-003-llm-formatter-provider-interface.md`
- ADR-004: Plugin Registration — `project_plans/mobile-voice-mode/decisions/ADR-004-plugin-registration.md`
- ADR-005: Voice Capture UI State Machine — `project_plans/mobile-voice-mode/decisions/ADR-005-voice-capture-ui-state-machine.md`
- Prior art seam: `project_plans/import-topic-suggestions/decisions/ADR-002-topic-enricher-plugin-interface.md`
- Research synthesis: `project_plans/mobile-voice-mode/research/synthesis.md`
